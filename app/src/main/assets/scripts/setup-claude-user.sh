#!/system/bin/sh
# setup-claude-user.sh — create a non-root `claude` user inside the Alpine
# chroot and move the existing claude installation + credentials over so that
# `claude --dangerously-skip-permissions` (= bypassPermissions mode) is
# allowed. Claude Code refuses to run with bypass under root.
set -e

ALPINE=/data/local/tmp/alpine

if [ ! -d "$ALPINE/usr" ] || [ ! -x "$ALPINE/bin/ash" ]; then
    echo "[FAIL] alpine chroot missing or incomplete at $ALPINE — earlier phases must succeed first"
    exit 1
fi

# Re-bind kernel pseudofs (needed for adduser, su, etc.)
for d in proc sys dev; do
    if ! grep -q " $ALPINE/$d " /proc/mounts; then
        mount --bind "/$d" "$ALPINE/$d" 2>/dev/null
    fi
done

INNER='
set -e
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# Make sure shadow is installed (provides useradd/usermod, missing in
# busybox-only Alpine in some configurations).
apk add --no-cache shadow >/dev/null 2>&1 || true

# Create the `claude` user idempotently, with a real home dir + ash shell.
if ! id claude >/dev/null 2>&1; then
    adduser -D -h /home/claude -s /bin/ash claude
fi

# Android sandbox: the kernel checks the calling process'\''s gid against
# AID_INET (3003) before allowing socket(AF_INET, ...). A custom user inside
# the chroot defaults to its own gid only and the kernel returns
# EPERM/ECONNREFUSED on every connect, which surfaces as "Unable to connect
# to API (FailedToOpenSocket)" from Claude Code. Add inet + net_raw and put
# claude in inet as the *primary* gid (supplementary groups via setgroups
# inside this kernel build are not honoured for the paranoid_check). 1000
# stays as a supplementary so file ownership in /home/claude still matches.
groupadd -g 3003 inet     2>/dev/null || true
groupadd -g 3004 net_raw  2>/dev/null || true
usermod  -g inet -aG net_raw,claude claude

# DNS files must be readable by the claude user. The bootstrap leaves
# resolv.conf at mode 600 (umask 077 inside the chroot), which makes
# every hostname lookup fail with "Connection refused" before any inet
# permission check even happens.
chmod 644 /etc/resolv.conf 2>/dev/null || true
chmod -R a+rX /etc/ssl 2>/dev/null || true

mkdir -p /home/claude/.claude /home/claude/.local/bin

# Move OAuth credentials + settings + CLAUDE.md into the new home if not
# already there. cp -n so re-runs don'\''t clobber.
if [ -f /root/.claude/.credentials.json ] && [ ! -f /home/claude/.claude/.credentials.json ]; then
    cp -p /root/.claude/.credentials.json /home/claude/.claude/.credentials.json
fi
if [ -f /root/.claude/settings.json ]; then
    cp -np /root/.claude/settings.json /home/claude/.claude/settings.json
fi
if [ -f /root/CLAUDE.md ]; then
    cp -np /root/CLAUDE.md /home/claude/CLAUDE.md
fi

# Make the existing claude binary findable for the new user. It lives at
# /root/.local/bin/claude after the curl|bash install ran as root. Easier to
# symlink into /usr/local/bin than to reinstall.
if [ -x /root/.local/bin/claude ] && [ ! -e /usr/local/bin/claude ]; then
    ln -s /root/.local/bin/claude /usr/local/bin/claude
fi
# /root and its .local subtree are mode 700 by default — make every parent
# of the claude install traversable so the symlink chain resolves for the
# non-root user. The actual binaries inherit their own (already executable)
# perms.
chmod 755 /root /root/.local /root/.local/bin 2>/dev/null || true
chmod 755 /root/.local/lib /root/.local/share /root/.local/state 2>/dev/null || true
chmod -R a+rX /root/.local/share/claude 2>/dev/null || true

chown -R claude:claude /home/claude

echo "DONE — user claude exists, home /home/claude, credentials copied"
ls -la /home/claude/.claude/ /home/claude/CLAUDE.md
'

chroot "$ALPINE" /usr/bin/env -i \
    HOME=/root TERM=xterm \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/ash -c "$INNER"
