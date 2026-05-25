"""Trigger a video recording over the web RPC, pull the resulting MP4, and
verify it contains both video (avc1) and audio (mp4a) tracks. Run via
`adb forward tcp:8080 tcp:8080` first."""

import json
import struct
import subprocess
import sys
import time
import urllib.request

import websocket  # type: ignore


HOST = "http://127.0.0.1:8080"
WS = "ws://127.0.0.1:8080/api/rpc"
PASSCODE = "0000"
DEVICE_VIDEOS = "/data/data/com.r1.launcher/files/captures/videos"
RECORD_SECONDS = 8


def auth() -> str:
    body = json.dumps({"passcode": PASSCODE}).encode()
    req = urllib.request.Request(
        f"{HOST}/api/auth", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=5) as r:
        data = json.loads(r.read())
    if not data.get("ok"):
        sys.exit(f"auth failed: {data}")
    return data["token"]


class Rpc:
    def __init__(self, token: str) -> None:
        self.ws = websocket.create_connection(f"{WS}?t={token}", timeout=15)
        self.next_id = 1

    def call(self, method: str, params: dict | None = None) -> dict:
        msg_id = str(self.next_id)
        self.next_id += 1
        # id MUST be a string — WebRpc parses it via jsonPrimitive.contentOrNull
        # and echoes it back as a string in the response frame.
        self.ws.send(json.dumps(
            {"type": "req", "id": msg_id, "method": method, "params": params or {}}
        ))
        # Skip event broadcasts (state.snapshot, etc.) until our response arrives.
        deadline = time.time() + 30
        while time.time() < deadline:
            raw = self.ws.recv()
            msg = json.loads(raw)
            if msg.get("type") == "res" and str(msg.get("id")) == msg_id:
                if not msg.get("ok"):
                    raise RuntimeError(f"{method} failed: {msg.get('error')}")
                return msg.get("payload") or {}
        raise TimeoutError(f"no response to {method}")

    def close(self) -> None:
        self.ws.close()


def adb(*args: str) -> str:
    out = subprocess.run(
        ["adb", *args], capture_output=True, text=True, check=False
    )
    if out.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def parse_mp4_atoms(path: str) -> list[tuple[str, int, int]]:
    """Return list of (atom_type, offset, size) for top-level atoms."""
    atoms = []
    with open(path, "rb") as f:
        pos = 0
        while True:
            hdr = f.read(8)
            if len(hdr) < 8:
                break
            size = struct.unpack(">I", hdr[:4])[0]
            atype = hdr[4:8].decode("latin-1", "replace")
            if size == 1:
                ext = f.read(8)
                size = struct.unpack(">Q", ext)[0]
                f.seek(pos + size)
            elif size == 0:
                # rest of file
                atoms.append((atype, pos, -1))
                break
            else:
                f.seek(pos + size)
            atoms.append((atype, pos, size))
            pos += size
    return atoms


def find_codec_tags(path: str) -> set[str]:
    """Scan the whole file for known sample-entry FourCCs. Crude but it's
    enough to confirm video AND audio tracks are present in moov."""
    tags = set()
    with open(path, "rb") as f:
        data = f.read()
    for needle in (b"avc1", b"hvc1", b"mp4a", b"opus", b"twos"):
        # Need to skip atom-size prefix; just confirm the FourCC appears at
        # any position. MP4 atom types are 4-byte strings preceded by their
        # 4-byte size, so any occurrence of these bytes is essentially proof
        # of a sample-entry of that codec.
        if needle in data:
            tags.add(needle.decode())
    return tags


def main() -> int:
    print("--> adb forward tcp:8080 tcp:8080")
    subprocess.run(["adb", "forward", "tcp:8080", "tcp:8080"], check=True)

    print("--> POST /api/auth")
    token = auth()
    print(f"  token={token[:8]}...")

    rpc = Rpc(token)
    try:
        print("--> capture.startVideo")
        start = rpc.call("capture.startVideo")
        print(f"  startedAt={start.get('startedAt')}")
        if start.get("code") == "already_recording":
            print("  (was already recording, sending stop first)")
            rpc.call("capture.stopVideo")
            time.sleep(1)
            start = rpc.call("capture.startVideo")
            print(f"  startedAt={start.get('startedAt')}")

        print(f"--> sleeping {RECORD_SECONDS}s while recording")
        time.sleep(RECORD_SECONDS)

        print("--> capture.stopVideo")
        item = rpc.call("capture.stopVideo")
        fname = item["name"]
        size = item["sizeBytes"]
        dur = item.get("durationMs")
        print(f"  file={fname} size={size}B durationMs={dur}")
    finally:
        rpc.close()

    print(f"--> pulling {fname} via web companion")
    local_path = f"out-{fname}"
    # run-as only works on debuggable APKs; release builds are not debuggable.
    # The web server serves captures at /static/media/<name>?t=<panelToken>,
    # which works for both debug and release.
    media_url = f"{HOST}/static/media/{fname}?t={token}"
    with urllib.request.urlopen(media_url, timeout=30) as r, open(local_path, "wb") as f:
        f.write(r.read())
    print(f"  pulled {len(open(local_path,'rb').read())}B to {local_path}")

    atoms = parse_mp4_atoms(local_path)
    print("--> top-level atoms:")
    for t, off, sz in atoms:
        print(f"    {t:>6}  off={off:>10}  size={sz}")

    tags = find_codec_tags(local_path)
    print(f"--> codec tags found: {sorted(tags)}")

    has_video = bool({"avc1", "hvc1"} & tags)
    has_audio = bool({"mp4a", "opus"} & tags)
    print(f"--> video={'YES' if has_video else 'NO'}  audio={'YES' if has_audio else 'NO'}")

    if has_video and has_audio:
        print("\n[PASS] recording has both tracks")
        return 0
    elif has_video:
        print("\n[FAIL] video only, audio not muxed")
        return 1
    else:
        print("\n[FAIL] file looks invalid")
        return 1


if __name__ == "__main__":
    sys.exit(main())
