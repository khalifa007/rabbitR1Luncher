#!/system/bin/sh
# claude-auth-finish.sh — feed the OAuth code into the running
# `claude auth login --claudeai` process via the FIFO, sync the credentials
# file from /root/.claude/ to /home/claude/.claude/ (different uid), and
# verify the auth actually works by running claude --print as the claude user.
#
# Usage:  sh /data/local/tmp/claude-auth-finish.sh '<code>#<state>'
ALPINE=/data/local/tmp/alpine
ROOT_CRED="$ALPINE/root/.claude/.credentials.json"
USER_CRED="$ALPINE/home/claude/.claude/.credentials.json"

CODE="$1"
if [ -z "$CODE" ]; then
    echo "[FAIL] usage: $0 '<code>#<state>'  (paste the value after ?code= from the redirect URL — keep the # and everything after it)" >&2
    exit 2
fi

if [ ! -p "$ALPINE/tmp/claude-auth.pipe" ]; then
    echo "[FAIL] no pending auth: $ALPINE/tmp/claude-auth.pipe missing — run claude-auth-start.sh first" >&2
    exit 3
fi

# Write the code to the FIFO. The auth process reads one line then exchanges
# it for an OAuth token + writes /root/.claude/.credentials.json.
printf '%s\n' "$CODE" > "$ALPINE/tmp/claude-auth.pipe"

# Poll for the credentials file to land. Anthropic's OAuth code-exchange
# round-trip takes ~3-10s depending on network. Bail after 20s with the
# log dump so the user can see whether the code was rejected vs. network
# blocked the exchange.
i=0
while [ $i -lt 20 ]; do
    if [ -s "$ROOT_CRED" ]; then break; fi
    sleep 1
    i=$((i+1))
done

echo "--login log--"
cat "$ALPINE/tmp/claude-auth.log" 2>/dev/null
echo "--root creds dir--"
ls -la "$ALPINE/root/.claude/" 2>/dev/null | head

if [ ! -s "$ROOT_CRED" ]; then
    echo "[FAIL] /root/.claude/.credentials.json never appeared — login did not complete. Likely the OAuth code was expired/reused, or the network blocked the token exchange. Reset credentials and retry with a fresh URL."
    exit 4
fi

# Sync credentials to the unprivileged claude user (the one the chat panel
# invokes via `su -l claude`, because Claude Code refuses
# --dangerously-skip-permissions when uid==0). cp -f (no -p) so we don't
# carry root:root from the source; explicit chmod + numeric chown.
#
# CRITICAL — the previous version used `chroot ... chown claude:claude` and
# that silently left the file as root:<group> on the host filesystem
# (only the group was applied, owner stayed root → claude user got EACCES).
# Numeric chown 1000:1000 from the host always wins because we know:
#   - inside the chroot, adduser made `claude` uid=1000 gid=1000
#   - on Android, uid 1000 = `system`, which is what we want the kernel
#     to see for the file owner
# Plus a chroot-side chown as belt-and-braces in case the alpine /etc/passwd
# ever drifts (unlikely but defensive).
mkdir -p "$ALPINE/home/claude/.claude"
cp -f "$ROOT_CRED" "$USER_CRED"
chmod 600 "$USER_CRED"
chown 1000:1000 "$USER_CRED" 2>/dev/null
chown -R 1000:1000 "$ALPINE/home/claude/.claude" 2>/dev/null
chmod 700 "$ALPINE/home/claude/.claude"
chroot "$ALPINE" /bin/ash -c "chown -R claude:claude /home/claude/.claude" 2>/dev/null || true
# Verify the file is actually readable by uid 1000 — if `chown` silently
# no-op'd (which it has done in the past on bind-mounted alpine paths),
# this catches it before the user hits "Not logged in" again.
OWNER=$(stat -c '%u:%g' "$USER_CRED" 2>/dev/null || echo unknown)
if [ "$OWNER" != "1000:1000" ]; then
    echo "[FAIL] credentials file ownership is $OWNER (expected 1000:1000) — claude user will get EACCES. Check that the bind mounts under $ALPINE are intact."
fi
echo "--user creds dir (post-sync)--"
ls -la "$ALPINE/home/claude/.claude/"
SIZE=$(wc -c < "$USER_CRED" 2>/dev/null || echo 0)
echo "synced credentials -> /home/claude/.claude/.credentials.json ($SIZE bytes)"

# Verify by running claude --print as the claude user. CRITICAL: do NOT
# export ANTHROPIC_API_KEY="" here — empty string is treated as a "set but
# invalid" key per claude's auth precedence, which poisons the OAuth
# fallback and produces the "Not logged in" symptom. Only forward the var
# if a real value exists.
echo "--verify (run as claude user)--"
ENV_EXTRA=""
if [ -r /data/local/tmp/.anthropic_key ]; then
    K=$(cat /data/local/tmp/.anthropic_key | tr -d '\n\r ')
    [ -n "$K" ] && ENV_EXTRA="ANTHROPIC_API_KEY=$K"
fi
chroot "$ALPINE" /usr/bin/env -i \
    TERM=xterm \
    PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    $ENV_EXTRA \
    /bin/su -l claude -s /bin/ash -c \
    "claude --version 2>&1 | head -1; \
     echo '--auth status--'; \
     claude auth status 2>&1 | head -10; \
     echo '--auth probe--'; \
     claude --print 'reply only with PONG' 2>&1 | head -5" 2>&1
