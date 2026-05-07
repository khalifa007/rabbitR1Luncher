#!/system/bin/sh
# claude-auth-start.sh — kick off `claude auth login --claudeai` in a fully
# detached session so it survives the carroot socket closing. Output (incl.
# the OAuth URL) lands in /data/local/tmp/alpine/tmp/claude-auth.log; the
# process keeps reading from a FIFO at /tmp/claude-auth.pipe (inside the
# chroot) until you echo the OAuth code into it via claude-auth-finish.sh.
#
# Why `auth login` and NOT `setup-token`: per direct testing on the device,
# `claude setup-token` detects non-TTY stdin and either silently exits 0 or
# hangs without ever printing a URL. `auth login --claudeai` works correctly
# through a FIFO — it prints a `https://claude.com/cai/oauth/...` URL and
# blocks on stdin for the code paste. The credentials.json it writes is then
# synced to /home/claude/.claude/ by claude-auth-finish.sh.
set -e

ALPINE=/data/local/tmp/alpine

# Make sure binds are in place (re-bind after reboot).
for d in proc sys dev; do
    if ! grep -q " $ALPINE/$d " /proc/mounts; then
        mount --bind "/$d" "$ALPINE/$d" 2>/dev/null
    fi
done

# Wipe prior attempt artifacts. Also kill any straggler auth process from
# a previous half-finished attempt — its FIFO writer would otherwise stop
# us from creating a new pipe at the same path. The double-kill (setup-token
# + auth login + sleeper) covers both modes.
for pat in 'claude setup-token' 'claude auth login' 'sleep 86400'; do
    pids=$(ps -ef 2>/dev/null | grep "$pat" | grep -v grep | awk '{print $2}')
    [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true
done
rm -f "$ALPINE/tmp/claude-auth.log" "$ALPINE/tmp/claude-auth.pipe" 2>/dev/null
mkfifo "$ALPINE/tmp/claude-auth.pipe"
chmod 666 "$ALPINE/tmp/claude-auth.pipe"

# Build the inner script that the chroot will run. Important details:
# - `setsid` makes the auth process a session leader so SIGHUP from the carroot
#   shell doesn't reach it.
# - The perpetual sleeper holds the FIFO open as a writer so when the user
#   later echoes the OAuth code in, the auth process sees the line and
#   continues — instead of getting EOF and bailing.
# - All output goes to /tmp/claude-auth.log inside the chroot (= host's
#   /data/local/tmp/alpine/tmp/claude-auth.log).
INNER='
  ( while true; do sleep 86400; done ) > /tmp/claude-auth.pipe 2>/dev/null &
  SLEEPER=$!
  echo "SLEEPER:$SLEEPER" > /tmp/claude-auth.log
  echo "claude binary: $(command -v claude || echo MISSING)" >> /tmp/claude-auth.log
  echo "claude version: $(claude --version 2>&1 | head -1)" >> /tmp/claude-auth.log
  echo "running: claude auth login --claudeai" >> /tmp/claude-auth.log
  claude auth login --claudeai < /tmp/claude-auth.pipe >> /tmp/claude-auth.log 2>&1
  echo "CLAUDE_EXIT:$?" >> /tmp/claude-auth.log
'

# nohup + setsid + redirected stdio so the chroot subtree survives nc closing.
nohup setsid chroot "$ALPINE" /usr/bin/env -i \
    HOME=/root TERM=xterm \
    PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/ash -c "$INNER" </dev/null >/dev/null 2>&1 &

# Poll up to 12s for the URL to appear in the log. claude usually prints it
# within ~1s but the binary cold-start can take longer on the R1's
# underspecced storage. Bail early once the URL lands so the user starts
# copying ASAP.
i=0
while [ $i -lt 12 ]; do
    if grep -q "https://" "$ALPINE/tmp/claude-auth.log" 2>/dev/null; then
        break
    fi
    sleep 1
    i=$((i+1))
done

echo "--auth log--"
cat "$ALPINE/tmp/claude-auth.log" 2>/dev/null
echo "--procs--"
ps -ef 2>/dev/null | grep -E 'claude auth login|sleep 86400' | grep -v grep | head
