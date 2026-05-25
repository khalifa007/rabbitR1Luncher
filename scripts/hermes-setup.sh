#!/usr/bin/env bash
# hermes-setup.sh — one-shot Hermes Agent provisioner for the R1 launcher.
#
# Detects a Hermes install, writes API_SERVER_* to ~/.hermes/.env, restarts the
# service, probes /health, fires a /v1/chat/completions ping to confirm the
# upstream LLM provider is wired, and emits a pairing QR the R1 scans from
# Hermes → ⋮ → settings → "scan config from qr".
#
# Run on the host that has Hermes installed (your LAN box or VPS), not on the
# R1 itself. See `--help` for flags.

set -euo pipefail

# ---------------------------------------------------------------------------
# Style helpers (match the in-tree convention from other scripts/*)
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
    C_DIM=$'\e[2m'; C_RED=$'\e[31m'; C_GRN=$'\e[32m'; C_YEL=$'\e[33m'
    C_CYA=$'\e[36m'; C_RST=$'\e[0m'
else
    C_DIM=""; C_RED=""; C_GRN=""; C_YEL=""; C_CYA=""; C_RST=""
fi
log()    { printf '%s[hermes-setup]%s %s\n' "$C_CYA" "$C_RST" "$*"; }
ok()     { printf '%s[ ok ]%s %s\n' "$C_GRN" "$C_RST" "$*"; }
warn()   { printf '%s[warn]%s %s\n' "$C_YEL" "$C_RST" "$*" >&2; }
die()    { printf '%s[fail]%s %s\n' "$C_RED" "$C_RST" "$*" >&2; exit 1; }

command_exists() { command -v "$1" >/dev/null 2>&1; }

usage() {
    cat <<EOF
Usage: $0 [flags]

Flags:
  --bind=<mode>          lan | localhost | public | <host[:port]>      (default: lan)
                         lan     = first IP from \`hostname -I\` (often wrong on VPSes
                                   where a private/management IP comes first)
                         public  = auto-detect via ifconfig.me / icanhazip / ipinfo
                                   (binds on 0.0.0.0, advertises the public IP)
  --port=<n>             override API_SERVER_PORT             (default: 8642)
  --key=<str>            specific API key (default: random 32 bytes hex)
  --scheme=http|https    force scheme (default: derived)
  --restart-cmd="<cmd>"  command run after .env edit          (default: autodetect)
  --skip-restart         don't restart Hermes
  --no-ping              skip /v1/chat/completions round-trip
  --qr-png=<path>        also write a PNG copy of the pairing QR
  --no-qr                skip QR rendering; print plain values
  -h, --help             this help
EOF
}

# ---------------------------------------------------------------------------
# Defaults + arg parsing
# ---------------------------------------------------------------------------
BIND="lan"
PORT="8642"
PORT_SET=0
KEY=""
SCHEME=""
RESTART_CMD=""
SKIP_RESTART=0
NO_PING=0
QR_PNG=""
NO_QR=0

for arg in "$@"; do
    case "$arg" in
        --bind=*)         BIND="${arg#*=}" ;;
        --port=*)         PORT="${arg#*=}"; PORT_SET=1 ;;
        --key=*)          KEY="${arg#*=}" ;;
        --scheme=*)       SCHEME="${arg#*=}" ;;
        --restart-cmd=*)  RESTART_CMD="${arg#*=}" ;;
        --skip-restart)   SKIP_RESTART=1 ;;
        --no-ping)        NO_PING=1 ;;
        --qr-png=*)       QR_PNG="${arg#*=}" ;;
        --no-qr)          NO_QR=1 ;;
        -h|--help)        usage; exit 0 ;;
        *)                die "unknown flag: $arg (try --help)" ;;
    esac
done

# ---------------------------------------------------------------------------
# Step 1 — detect install
# ---------------------------------------------------------------------------
log "checking Hermes install…"

if ! command_exists hermes; then
    cat >&2 <<EOF
${C_RED}[fail]${C_RST} hermes CLI not on PATH.

Install Hermes Agent first:

  curl -fsSL https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh | bash

Docs: https://github.com/nousresearch/hermes-agent
EOF
    exit 1
fi
HERMES_BIN=$(command -v hermes)
ok "hermes CLI: $HERMES_BIN"

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
if [[ ! -d "$HERMES_HOME" ]]; then
    die "$HERMES_HOME not found — run \`hermes gateway setup\` first to bootstrap config"
fi
ok "config dir: $HERMES_HOME"

ENV_FILE="$HERMES_HOME/.env"
if [[ -f "$ENV_FILE" ]]; then
    ok "env file: $ENV_FILE (will update)"
else
    : > "$ENV_FILE"; chmod 600 "$ENV_FILE"
    ok "env file: $ENV_FILE (created)"
fi

# ---------------------------------------------------------------------------
# Step 2 — generate key + compute URL
# ---------------------------------------------------------------------------
if [[ -z "$KEY" ]]; then
    if command_exists openssl; then
        KEY=$(openssl rand -hex 32)
    elif command_exists python3; then
        KEY=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
    else
        die "need openssl or python3 to generate a key — install one or pass --key=…"
    fi
fi

# Resolve host + port + scheme from --bind.
HOST=""
URL_PORT=""
HOST_HAS_PORT=0
case "$BIND" in
    lan)
        HOST=$(hostname -I 2>/dev/null | awk '{print $1}')
        [[ -z "$HOST" ]] && die "couldn't derive a LAN IP from \`hostname -I\` (got empty)"
        HOST_BIND="0.0.0.0"
        URL_PORT=":$PORT"
        ;;
    public)
        log "detecting public IP…"
        HOST=$(curl -fsS --max-time 5 https://ifconfig.me 2>/dev/null \
            || curl -fsS --max-time 5 https://ipv4.icanhazip.com 2>/dev/null \
            || curl -fsS --max-time 5 https://ipinfo.io/ip 2>/dev/null \
            || true)
        HOST=$(printf '%s' "$HOST" | tr -d '[:space:]')
        [[ -z "$HOST" ]] && die "public IP lookup failed — pass --bind=<ip>:<port> instead"
        ok "public IP: $HOST"
        HOST_BIND="0.0.0.0"
        URL_PORT=":$PORT"
        ;;
    localhost)
        HOST="localhost"
        HOST_BIND="127.0.0.1"
        URL_PORT=":$PORT"
        warn "--bind=localhost — the R1 won't reach this from another machine"
        ;;
    *:*)
        HOST="${BIND%:*}"
        URL_PORT=":${BIND##*:}"
        HOST_BIND=""        # caller is fronting Hermes themselves; don't touch host/port
        HOST_HAS_PORT=1
        ;;
    *)
        HOST="$BIND"
        URL_PORT=""         # no port → assume nginx + TLS in front, port comes from scheme
        HOST_BIND=""
        HOST_HAS_PORT=1
        ;;
esac

if [[ -z "$SCHEME" ]]; then
    if [[ "$HOST_HAS_PORT" -eq 1 && -z "$URL_PORT" ]]; then
        SCHEME="https"  # bare domain → public-internet shape
    else
        SCHEME="http"
    fi
fi

URL="${SCHEME}://${HOST}${URL_PORT}/v1"
HEALTH_URL="${SCHEME}://${HOST}${URL_PORT}/health"

# ---------------------------------------------------------------------------
# Step 3 — update ~/.hermes/.env (idempotent in-place edit)
# ---------------------------------------------------------------------------
log "updating $ENV_FILE…"
BACKUP="$ENV_FILE.bak.$(date +%s)"
cp "$ENV_FILE" "$BACKUP"
ok "backup: $BACKUP"

# upsert_env KEY VALUE — replace ^KEY=… line if present, else append.
upsert_env() {
    local k="$1" v="$2" file="$ENV_FILE"
    if grep -q "^${k}=" "$file"; then
        # `|` as sed delimiter so URLs/paths don't need escaping. Escape `|`,
        # `\`, and `&` in the replacement value.
        local esc; esc=$(printf '%s' "$v" | sed 's/[\\|&]/\\&/g')
        sed -i "s|^${k}=.*|${k}=${esc}|" "$file"
    else
        printf '%s=%s\n' "$k" "$v" >> "$file"
    fi
}

upsert_env "API_SERVER_ENABLED" "true"
upsert_env "API_SERVER_KEY" "$KEY"
if [[ -n "$HOST_BIND" ]]; then
    upsert_env "API_SERVER_HOST" "$HOST_BIND"
    upsert_env "API_SERVER_PORT" "$PORT"
else
    log "--bind=$BIND — skipping API_SERVER_HOST/PORT (assume proxy fronts Hermes)"
fi
chmod 600 "$ENV_FILE"
ok ".env written"

# ---------------------------------------------------------------------------
# Step 4 — restart Hermes
# ---------------------------------------------------------------------------
restart_hermes() {
    if [[ "$SKIP_RESTART" -eq 1 ]]; then
        warn "--skip-restart set; you must restart Hermes manually for changes to apply"
        return 0
    fi
    if [[ -n "$RESTART_CMD" ]]; then
        log "running --restart-cmd: $RESTART_CMD"
        bash -c "$RESTART_CMD" && ok "restart command exited 0" && return 0
        die "--restart-cmd failed"
    fi
    # Hermes ships two distinct gateway commands: `start` (manages an installed
    # systemd/launchd unit; fails with "Gateway service is not installed" on
    # fresh boxes) and `run` (foreground daemon, suitable for nohup'ing). We
    # always try the service-manager path first, then fall back to `run`.
    if systemctl --user is-active --quiet hermes 2>/dev/null; then
        log "systemctl --user restart hermes"
        systemctl --user restart hermes
        ok "user-systemd unit restarted"; return 0
    fi
    if sudo -n systemctl is-active --quiet hermes 2>/dev/null; then
        log "sudo systemctl restart hermes"
        sudo -n systemctl restart hermes
        ok "system-systemd unit restarted"; return 0
    fi
    # No service unit found. Use `hermes gateway run --replace`, which Hermes
    # itself recommends for replacing a live instance — it detects the existing
    # PID via Hermes's own lockfile (more reliable than pgrep-by-argv, which
    # misses processes started with non-standard argv, inside containers, etc.)
    # and handles the "nothing running yet" case the same way.
    log "starting via 'hermes gateway run --replace' (handles existing instance)…"
    nohup hermes gateway run --replace >"$HERMES_HOME/setup-restart.log" 2>&1 &
    ok "launched in background (logs: $HERMES_HOME/setup-restart.log)"
}
restart_hermes

# ---------------------------------------------------------------------------
# Step 5 — verify /health
# ---------------------------------------------------------------------------
HEALTH_OK=0
if [[ "$SKIP_RESTART" -eq 0 ]]; then
    # Cold-start budget: gateway boot loads model registry, plugins, FTS index,
    # and binds the api_server port. On a fresh box that's 5–20s; on a warm
    # one it's <1s. 30s ceiling covers both without making failures slow.
    log "probing $HEALTH_URL (up to 30s)…"
    LAST_ERR=""
    for i in $(seq 1 30); do
        STATUS=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 2 "$HEALTH_URL" 2>&1) || STATUS="000"
        case "$STATUS" in
            200)        ok "/health → 200 (attempt $i)"; HEALTH_OK=1; break ;;
            401|403)    warn "/health → $STATUS (auth-gated on this deploy; treating as healthy)"; HEALTH_OK=1; break ;;
            000)        LAST_ERR="connection refused / DNS / timeout" ;;
            *)          LAST_ERR="HTTP $STATUS" ;;
        esac
        sleep 1
    done
    if [[ "$HEALTH_OK" -ne 1 ]]; then
        warn "last 30 lines of $HERMES_HOME/setup-restart.log:"
        tail -n 30 "$HERMES_HOME/setup-restart.log" 2>/dev/null | sed 's/^/    /' >&2 || true
        die "/health never returned 200 (last: $LAST_ERR)"
    fi
fi

# ---------------------------------------------------------------------------
# Step 6 — ping /v1/chat/completions
# ---------------------------------------------------------------------------
if [[ "$NO_PING" -eq 0 && "$SKIP_RESTART" -eq 0 ]]; then
    log "POST $URL/chat/completions (ping)…"
    RESP=$(curl -sS --max-time 30 -X POST "$URL/chat/completions" \
        -H "Authorization: Bearer $KEY" \
        -H "Content-Type: application/json" \
        -d '{"model":"hermes-agent","messages":[{"role":"user","content":"ping"}],"stream":false}' \
        2>&1) || die "curl failed: $RESP"

    # Parse via python3 reading $RESP from argv (not stdin) — feeding both the
    # source AND the response on stdin to `python3 -` leaves it ambiguous which
    # one wins and the parser silently saw an empty body in the wild.
    PARSED=$(python3 -c '
import sys, json
raw = sys.argv[1].strip() if len(sys.argv) > 1 else ""
try:
    j = json.loads(raw)
except Exception:
    print("RAW\t" + raw[:300]); sys.exit(0)
err = (j.get("error") or {})
if err:
    msg = err.get("message") if isinstance(err, dict) else None
    print("ERR\t" + (msg or json.dumps(err))[:500]); sys.exit(0)
try:
    msg = j["choices"][0]["message"]["content"]
    print("OK\t" + (msg or "")[:300])
except Exception:
    print("RAW\t" + raw[:300])
' "$RESP")
    KIND=$(printf '%s' "$PARSED" | head -1 | cut -f1)
    TEXT=$(printf '%s' "$PARSED" | head -1 | cut -f2-)
    case "$KIND" in
        OK)  ok "ping reply: ${TEXT}" ;;
        ERR) die "ping failed: $TEXT" ;;
        *)   die "unexpected ping response: $TEXT" ;;
    esac
fi

# ---------------------------------------------------------------------------
# Step 7 — emit pairing QR
# ---------------------------------------------------------------------------
PAYLOAD_JSON=$(printf '{"v":1,"url":"%s","key":"%s"}' "$URL" "$KEY")
PAYLOAD_B64=$(printf '%s' "$PAYLOAD_JSON" | base64 -w0 2>/dev/null | tr '+/' '-_' | tr -d '=')
[[ -z "$PAYLOAD_B64" ]] && PAYLOAD_B64=$(printf '%s' "$PAYLOAD_JSON" | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '=')
PAIR_URI="r1-hermes://v1/${PAYLOAD_B64}"

EMIT_QR=0
if [[ "$NO_QR" -eq 0 ]]; then
    if command_exists qrencode; then
        EMIT_QR=1
    else
        warn "qrencode not installed — falling back to plain output"
        warn "install with: sudo apt install qrencode    (Debian/Ubuntu)"
        warn "         or: brew install qrencode         (macOS)"
    fi
fi

# ---------------------------------------------------------------------------
# Step 8 — summary
# ---------------------------------------------------------------------------
echo
printf '%s──────────────────────────────────────────────%s\n' "$C_DIM" "$C_RST"
printf '%sHermes is up.%s\n\n' "$C_GRN" "$C_RST"
printf '  URL:  %s\n' "$URL"
printf '  Key:  %s%s%s\n\n' "$C_YEL" "$KEY" "$C_RST"

if [[ "$EMIT_QR" -eq 1 ]]; then
    echo "Scan this from the R1:"
    echo "  Apps → Hermes → ⋮ → settings → \"scan config from qr\""
    echo
    qrencode -t ANSIUTF8 -m 1 "$PAIR_URI"
    if [[ -n "$QR_PNG" ]]; then
        qrencode -o "$QR_PNG" -s 8 -m 2 "$PAIR_URI"
        ok "QR PNG: $QR_PNG"
    fi
    echo
    echo "Or paste manually:"
else
    echo "Paste these into the R1 launcher (Hermes → ⋮ → settings):"
fi
printf '  server url  →  %s\n' "$URL"
printf '  api key     →  %s\n' "$KEY"
printf '%s──────────────────────────────────────────────%s\n' "$C_DIM" "$C_RST"
