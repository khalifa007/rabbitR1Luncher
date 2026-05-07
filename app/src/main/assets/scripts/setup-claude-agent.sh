#!/system/bin/sh
# setup-claude-agent.sh — one-shot setup so Claude Code (running inside the
# Alpine chroot) can take root actions on the underlying R1 via the carroot
# socket at 127.0.0.1:1337.
#
# Run via: sh /data/local/tmp/setup-claude-agent.sh
set -e

ALPINE=/data/local/tmp/alpine
KEY_FILE=/data/local/tmp/.anthropic_key

if [ ! -d "$ALPINE/usr" ] || [ ! -x "$ALPINE/bin/ash" ]; then
    echo "[FAIL] alpine chroot missing or incomplete at $ALPINE — phase 1 (rootfs) must succeed first"
    exit 1
fi
# Phase 2 (claude binary install via curl|bash) is what populates this. If it
# never ran or failed silently, bail rather than build a broken bridge.
if [ ! -x "$ALPINE/root/.local/bin/claude" ] && [ ! -L "$ALPINE/root/.local/bin/claude" ]; then
    echo "[FAIL] claude binary not installed at /root/.local/bin/claude — phase 2 must succeed first"
    exit 1
fi

# Re-bind /proc /sys /dev if a reboot dropped them.
for d in proc sys dev; do
    if ! grep -q " $ALPINE/$d " /proc/mounts; then
        mount --bind "/$d" "$ALPINE/$d" 2>/dev/null
    fi
done

echo "[r1-claude] installing netcat (for the r1-root bridge)..."
chroot "$ALPINE" /usr/bin/env -i \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/ash -c "apk add --no-cache netcat-openbsd"

echo "[r1-claude] writing /usr/local/bin/r1-root bridge..."
cat > "$ALPINE/usr/local/bin/r1-root" <<'BRIDGE'
#!/bin/ash
# r1-root — execute its arguments as root on the underlying Android via the
# carroot socket. Output streams back. Usage:
#   r1-root pm list packages
#   r1-root "settings put system screen_brightness 200"
#   r1-root "logcat -d -t 100 | grep ActivityManager"
#
# The chroot shares the host's network namespace, so 127.0.0.1:1337 reaches
# the launcher's privileged shell from inside Alpine.
if [ "$#" -eq 0 ]; then
    echo "usage: r1-root <android shell command>" >&2
    exit 2
fi
# Wrap the command with a sentinel so we can distinguish output from EOF
# weirdness. nc closes when the remote (sh) finishes.
printf '%s\n' "$*" | nc -q 1 127.0.0.1 1337
BRIDGE
# Mode 755 (not 700 from `chmod +x`) so the non-root `claude` user inside
# the chroot can execute the bridge — that user is the one Claude Code runs
# under because Claude refuses --dangerously-skip-permissions when uid==0.
chmod 755 "$ALPINE/usr/local/bin/r1-root"

echo "[r1-claude] writing /root/CLAUDE.md (device context)..."
mkdir -p "$ALPINE/root"
cat > "$ALPINE/root/CLAUDE.md" <<'CLAUDEMD'
# R1 Device Context (Claude Code)

You are running inside an Alpine arm64 chroot on a Rabbit R1 device:
LineageOS-based CarrotOS, MT6765 SoC, **480x480 round screen**, no usable browser,
no PTY for interactive REPLs from your perspective. The user controls you from a
custom Compose-based home launcher's terminal panel — short commands, no scrollback.

## Your privilege bridge

Use `r1-root <android shell command>` to execute commands as **root on the underlying Android**
(NOT inside this chroot). Examples:

- `r1-root pm list packages` — list installed apps
- `r1-root dumpsys battery` — battery state
- `r1-root "settings put system screen_brightness 200"` — set brightness
- `r1-root "cmd wifi set-wifi-enabled enabled"` — toggle Wi-Fi (works on this build)
- `r1-root "am start -n com.android.settings/.Settings"` — open Settings app
- `r1-root "logcat -d -t 200"` — read recent logs
- `r1-root "echo hi > /sdcard/note.txt"` — write to user storage

**Inside the chroot itself** (no privilege bridge needed) you have a normal Alpine shell:
node, npm, git (after `apk add git`), python3 (after `apk add python3`), etc. Use
`apk add --no-cache <pkg>` to install missing tooling.

## What works on this device

- Wi-Fi/cellular/Bluetooth toggles via `cmd wifi set-wifi-enabled`, `settings put global mobile_data 1|0`, `cmd bluetooth_manager enable|disable`. Framework APIs silently no-op without system perms — use these `cmd`/`settings` paths via r1-root.
- `svc data` and `svc wifi` are dead shims on this build — don't use them.
- Brightness via `settings put system screen_brightness <1-255>`.
- Volume via `cmd media_session volume --stream 3 --set <0-15>`.
- Hotspot via `cmd wifi start-softap "<ssid>" wpa2 "<password>"` (one-shot form only; the two-step set-ssid + start-softap is rejected).
- The launcher itself runs as `com.r1.launcher`; force-stop it with `am force-stop com.r1.launcher` (it auto-restarts because it's the home activity).
- Reboot via `am broadcast -a android.intent.action.FACTORY_RESET --receiver-foreground -p android` is **destructive** — wipes userdata. Don't do unless explicitly asked.
- Each `r1-root` call is one fresh shell — `cd` and env vars don't persist between calls.

## Hard guardrails (refuse unless the user re-confirms in the same prompt)

- `rm -rf /` or anything wiping `/system`, `/data`, `/sdcard` wholesale
- `dd` against any block device
- factory reset broadcasts
- `pm uninstall` of `com.r1.launcher` (you'd brick the home screen)
- writing to `/system/build.prop`, `/system/etc/init/`, kernel boot args
- exfiltrating SMS, contacts, identity files (`/data/system/users/*/accounts.db`, `/data/data/com.android.providers.telephony/`) to anywhere outside the device

## Style

- Keep replies short — the user sees output in a 480x480 panel. One-paragraph summaries beat multi-section reports.
- When you run a command, briefly say what you're about to do and why. After it returns, summarize the result, don't dump raw output unless the user asked.
- If a single multi-step task would take >5 r1-root calls, plan it out first in 2-3 lines, then execute.
CLAUDEMD

echo "[r1-claude] writing /root/.claude/settings.json (bypass permissions)..."
mkdir -p "$ALPINE/root/.claude"
cat > "$ALPINE/root/.claude/settings.json" <<'SETTINGS'
{
  "permissions": {
    "defaultMode": "bypassPermissions"
  }
}
SETTINGS

if [ ! -s "$KEY_FILE" ]; then
    echo "[r1-claude] WARN: $KEY_FILE missing or empty. Set it with:"
    echo "  echo 'sk-ant-...' > $KEY_FILE && chmod 600 $KEY_FILE"
fi

echo "[r1-claude] DONE — try:"
echo "  claude --print \"use r1-root to read battery percent and report it\""
