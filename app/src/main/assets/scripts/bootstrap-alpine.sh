#!/system/bin/sh
# bootstrap-alpine.sh — one-shot installer for an Alpine arm64 chroot on the R1.
# Adds Node.js + npm so the launcher's terminal panel can run JS workloads
# (e.g. the openclaw gateway) without depending on Termux.
#
# Run once via: sh /data/local/tmp/bootstrap-alpine.sh
# Then use:     sh /data/local/tmp/r1-alpine "npm install <pkg>"
set -e

ALPINE=/data/local/tmp/alpine
ALPINE_VER=3.20
ALPINE_PATCH=3.20.3
TARBALL=alpine-minirootfs-${ALPINE_PATCH}-aarch64.tar.gz
URL=https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VER}/releases/aarch64/${TARBALL}

echo "[r1-alpine] target: $ALPINE"

# A previous run that crashed mid-extract leaves /data/local/tmp/alpine half-
# populated. The presence-check below would then skip the download and every
# downstream phase would chroot into garbage. Detect the obvious incomplete
# states and wipe before deciding whether to re-fetch.
if [ -d "$ALPINE" ] && [ ! -x "$ALPINE/bin/ash" ]; then
    echo "[r1-alpine] WARN: $ALPINE exists but /bin/ash missing — wiping partial extract"
    rm -rf "$ALPINE"
fi

if [ -d "$ALPINE/usr" ] && [ -x "$ALPINE/bin/ash" ]; then
    echo "[r1-alpine] already extracted; skipping download"
else
    cd /data/local/tmp
    echo "[r1-alpine] downloading $URL"
    if ! curl -fsSL --max-time 180 -o "$TARBALL" "$URL"; then
        echo "[FAIL] curl could not download $URL (network down or mirror unreachable)"
        rm -f "$TARBALL"
        exit 1
    fi
    # Reject HTML error pages (size sanity) + corrupt downloads (gzip -t).
    SIZE=$(wc -c < "$TARBALL" 2>/dev/null || echo 0)
    if [ "$SIZE" -lt 1000000 ] || ! gzip -t "$TARBALL" 2>/dev/null; then
        echo "[FAIL] downloaded tarball is not a valid gzip (size=$SIZE) — likely a captive portal or truncated transfer"
        rm -f "$TARBALL"
        exit 1
    fi
    mkdir -p "$ALPINE"
    echo "[r1-alpine] extracting"
    if ! tar -xzf "$TARBALL" -C "$ALPINE"; then
        echo "[FAIL] tar extraction failed — wiping partial $ALPINE so next run starts clean"
        rm -rf "$ALPINE"
        rm -f "$TARBALL"
        exit 1
    fi
    rm -f "$TARBALL"
fi

# DNS so apk can reach the package mirror from inside the chroot.
echo "nameserver 1.1.1.1" > "$ALPINE/etc/resolv.conf"
echo "nameserver 8.8.8.8" >> "$ALPINE/etc/resolv.conf"

# Bind-mount the kernel pseudo-filesystems apk needs. Idempotent: skip if
# already mounted (re-binding stacks new mounts on top, which leaks slots).
for d in proc sys dev; do
    mkdir -p "$ALPINE/$d"
    if ! grep -q " $ALPINE/$d " /proc/mounts; then
        mount --bind "/$d" "$ALPINE/$d" || echo "[r1-alpine] WARN: bind /$d failed"
    fi
done

echo "[r1-alpine] enter chroot, install nodejs+npm+bash+curl"
# Android's parent PATH lacks /sbin and /usr/sbin where Alpine stores apk;
# set a sane Alpine PATH explicitly before invoking apk.
# bash is required by the Claude Code installer (`curl ... | bash`); minirootfs
# doesn't ship it. curl is also not in minirootfs and we use it directly later.
chroot "$ALPINE" /bin/ash -c "
set -e
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
apk update
apk add --no-cache nodejs npm bash curl
"

NODE_VER=$(chroot "$ALPINE" /bin/ash -c "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin node --version" 2>/dev/null || echo 'unknown')
NPM_VER=$(chroot "$ALPINE" /bin/ash -c "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin npm --version" 2>/dev/null || echo 'unknown')
echo "[r1-alpine] DONE — node $NODE_VER / npm $NPM_VER"
echo "[r1-alpine] use:  sh /data/local/tmp/r1-alpine \"<command>\""
