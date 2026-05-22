# Remote media capture (screenshots + video) — design

Date: 2026-05-22
Status: approved (brainstorm)

## Summary

Add screenshot and screen-recording capability to the web companion. Users tap **snap** to capture a PNG or **● record** to capture an MP4 with mic audio, then browse captures as a thumbnail grid and download them over LAN. All captures live in app-private storage; the companion is the only consumption surface in v1.

## Scope

- **Image + video** in v1.
- **Mic audio** in every video recording (no per-recording toggle in v1).
- **Storage:** app-private (`filesDir/captures/`).
- **Trigger surface:** web companion only — no on-device panel, no side-button gesture.
- **Capture mechanism:** carroot shell (`screencap` + `screenrecord --audio-source mic`). No MediaProjection.

## Architecture

```
companion (browser)            R1 launcher                       carroot (root sh @1337)
─────────────────              ───────────                       ───────────────────────
[snap]   ── capture.screenshot ─▶ MediaCaptureManager.shot()     ─▶ screencap -p > /data/local/tmp/...
                                  cp → filesDir/captures/imgs       cp → app filesDir
[● rec]  ── capture.startVideo ─▶ MediaCaptureManager.start()    ─▶ screenrecord --audio-source mic ... &
                                  persist PID                        (process keeps running)
[■ stop] ── capture.stopVideo  ─▶ MediaCaptureManager.stop()     ─▶ kill -2 <pid> ; cp → app filesDir
                                  MediaMetadataRetriever → thumb
list / GET /static/media/<file>   MediaCaptureManager.list /
                                  R1WebServer.serveStaticMedia
event: capture.added              broadcast on completion           —
event: capture.recording          broadcast on start/stop           —
```

### New module: `media/MediaCaptureManager.kt`

Process-scoped (not activity-scoped). Singleton-ish (`object`).

Surface:

```kotlin
object MediaCaptureManager {
    data class CaptureItem(
        val name: String,
        val kind: String,        // "image" | "video"
        val sizeBytes: Long,
        val takenAt: Long,       // epoch ms
        val durationMs: Long?,   // null for images
        val url: String,         // "/static/media/<name>" — always populated, used for download AND fullsize view
        val thumbUrl: String,    // always populated. images: same as `url`. videos: "/static/media/.thumbs/<basename>.jpg" if thumb exists, else "/static/media/_play_placeholder" (built-in fallback served by R1WebServer)
    )

    fun init(ctx: Context)                          // creates dirs, recover()
    fun captureScreenshot(): Result<CaptureItem>    // synchronous, ~1s
    fun startVideoRecording(): Result<Long>         // returns startedAt
    fun stopVideoRecording(): Result<CaptureItem>   // synchronous, ~500ms
    fun isRecording(): Boolean
    fun recordingStartedAt(): Long
    fun list(limit: Int = 50): List<CaptureItem>
    fun totalBytes(): Long
    fun delete(name: String): Boolean
    fun clear(): Int                                 // returns count deleted
    private fun recover()                            // SIGINT orphan screenrecord, salvage tmp
    private fun enforceRetention()                   // 100 files OR 500 MB cap
    private fun generateThumb(mp4: File): File?
}
```

Constants at top of the file:

```kotlin
private const val MAX_FILES = 100
private const val MAX_BYTES = 500L * 1024 * 1024
private const val VIDEO_TIME_LIMIT_S = 180
private const val VIDEO_BIT_RATE = 4_000_000
private const val LOW_STORAGE_FREE_BYTES = 100L * 1024 * 1024
```

### State additions on `LauncherState`

Only two new fields:

- `mediaRecording: Boolean` — drives recording-state UI in companion (and snapshot broadcasts).
- `mediaRecordingStartedAt: Long` — duration counter source of truth.

No new `Panel`. No on-device UI.

### `LauncherHost` shims

Four thin methods delegating straight to `MediaCaptureManager`:

```kotlin
fun mediaCaptureScreenshot(): Result<CaptureItem>
fun mediaStartVideo(): Result<Long>
fun mediaStopVideo(): Result<CaptureItem>
fun mediaList(limit: Int): List<CaptureItem>
fun mediaDelete(name: String): Boolean
fun mediaClear(): Int
```

`LauncherActivity` implements these by calling `MediaCaptureManager.*` and broadcasting events via `webServer?.broadcast*`.

### Web server / RPC additions

**Six new methods in `WebRpc.dispatch`:**

| Method | Params | Returns | Errors |
|---|---|---|---|
| `capture.screenshot` | — | `CaptureItem` | `{code:"capture_failed"}`, `{code:"carroot_unreachable"}`, `{code:"low_storage"}` |
| `capture.startVideo` | — | `{ok:true, startedAt}` | `{code:"already_recording", startedAt}`, `{code:"low_storage", freeBytes}` |
| `capture.stopVideo` | — | `CaptureItem` | `{code:"not_recording"}`, `{code:"recording_lost"}` |
| `capture.list` | `{limit?: int}` | `{items: CaptureItem[], totalBytes: long}` | — |
| `capture.delete` | `{name: string}` | `{ok:true}` | `{code:"not_found"}` |
| `capture.clear` | — | `{deleted: int}` | — |

**Two broadcast events:**

- `capture.recording` — `{recording: bool, startedAt: long?}`. Fired on every start/stop.
- `capture.added` — `CaptureItem`. Fired right after a screenshot completes or a video finalizes.

**Snapshot extension** in `WebRpc.buildSnapshot`:

```json
"media": {
  "recording": false,
  "startedAt": 0,
  "count": 23,
  "totalBytes": 49521203
}
```

Auto-broadcast at the existing 1 Hz cadence so fresh browser loads pick up in-progress recordings.

**Static asset routing** — `R1WebServer.serveHttp` gets a new branch before the existing `/static/<x>` rule:

- `GET /static/media/<filename>` → resolve `filesDir/captures/images/<filename>` or `videos/<filename>` (whichever exists). Serve with the existing `guessMime()` and `Content-Disposition: attachment; filename="<filename>"` IF the request has a `?download=1` query param, otherwise serve inline (lightbox needs inline display for `<img>`/`<video>` to render; download anchors set `?download=1`). 404 if not found (covers retention races).
- `GET /static/media/.thumbs/<filename>` → resolve `filesDir/captures/videos/.thumbs/<filename>`; serve inline.
- `GET /static/media/_play_placeholder` → serve a static play-glyph SVG embedded in the launcher resources. Used when video thumbnail generation failed.

## Storage layout

```
filesDir/captures/
├── images/
│   ├── shot-20260522-143512-001.png
│   └── shot-20260522-143601-002.png
└── videos/
    ├── rec-20260522-143820-001.mp4
    ├── rec-20260522-143955-002.mp4
    └── .thumbs/
        ├── rec-20260522-143820-001.jpg
        └── rec-20260522-143955-002.jpg
```

**Filename format:** `<kind>-<YYYYMMDD>-<HHMMSS>-<NNN>.<ext>`.
- Timestamp uses device local timezone via `SimpleDateFormat("yyyyMMdd-HHmmss")`.
- `NNN` is a per-second counter (3-digit, zero-padded) that resets to `001` whenever the timestamp string changes. Prevents collisions on sub-second double-taps.
- Lexicographic sort = chronological sort.

**Tempfile path:** `/data/local/tmp/r1cap-<rand>.{png,mp4}` — same dir, different basename per capture. Cleared on launcher start via `rm -f /data/local/tmp/r1cap-*` in `MediaCaptureManager.init`.

**Thumbnails for video:** generated on stop via `MediaMetadataRetriever.getFrameAtTime(1_000_000)` (1-second mark). JPEG quality 70, full 480×480 source resolution. Written to `videos/.thumbs/<basename>.jpg`. Images need no thumbnail — the source PNG is already small (50–200 KB) and serves directly as both thumbnail and full image.

**Retention:** checked on every successful capture, after the new file is written. If `count > MAX_FILES` OR `totalBytes > MAX_BYTES`, evict oldest by `lastModified()` until under both caps. Eviction deletes the main file AND its `.thumbs/` companion if present. Logged: `MediaCapture: evicted N files (was ${beforeBytes}B, now ${afterBytes}B)`.

## Companion UI

New view `view-media` in `index.html`, slotted between `view-system` and `view-meetings`. Follows the existing `view view-app` pattern with `tpl-app-header` clone for back-pill + title + status.

**Home grid tile:** new "media" tile, same 2 px edge style, orange `#FF6A00` palette. Icon: reuse the existing `ic_messages` vector for v1 (visually closest in the current set). A dedicated `ic_media` glyph is a follow-up frontend polish item, not blocking.

**Layout:**

```
┌────────────────────────────────────────┐
│ < media                                │
├────────────────────────────────────────┤
│  [ snap ]        [ ● record ]          │
│  23 items · 47.2 MB        [clear all] │
├────────────────────────────────────────┤
│ ┌────┐ ┌────┐ ┌────┐ ┌────┐            │
│ │ ▷  │ │📷  │ │ ▷  │ │📷  │            │
│ │MP4 │ │PNG │ │MP4 │ │PNG │            │
│ │14:38│ │14:35│ │14:32│ │14:30│        │
│ └────┘ └────┘ └────┘ └────┘            │
│ ┌────┐ ...                             │
└────────────────────────────────────────┘
```

**Grid:** CSS `display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px;`. Same CSS scales from R1's 480 px (3 columns) up to desktop (8+ columns) with no breakpoints.

**Tile:**
- Aspect 1:1, `thumbUrl` fills via `<img loading="lazy">`.
- Top-right corner: `×` delete button. Always tappable (covers touch). Renders at 60% opacity → 100% on hover (desktop) for visual subtlety. Tapping `×` triggers the two-tap confirm pattern, not the lightbox.
- Bottom strip: kind badge (`PNG` / `MP4`) + relative-time label (`14:38`, then re-renders to `2m ago` after a minute, then `1h ago`, etc.).
- Tap anywhere except `×` → open lightbox (uses `url` field, NOT `thumbUrl`, so the lightbox always shows the full-size original).

**Lightbox:**
- Full-viewport overlay, dark backdrop, click-outside-to-close.
- Image: `<img src="/static/media/<name>">` at native size (centered, max 100vw/100vh).
- Video: `<video src="/static/media/<name>" controls autoplay>`.
- Bottom toolbar: `[ download ]  [ delete ]  [ × ]`. Download uses `<a href="..." download>`. Delete shows the two-tap confirm pattern.

**Recording state:**
- `record` button morphs to `■ stop (00:42)`. Pulsing red dot left of label.
- `snap` button greyed out and unclickable (concurrent screencap returns black on MTK).
- Duration counter ticks once per second client-side, driven by `state.media.startedAt`.
- At `02:55`, button label appends ` — auto in 05s` and ticks down to `00s`.
- At `03:00`, the server's `--time-limit` triggers, `capture.added` fires, UI rolls back to idle and the new MP4 appears in the grid.

**Empty state:** centered muted text `no captures yet. tap snap or record.`

**Clear all:** at the right end of the stats row, only rendered when `count > 0`. Two-tap confirm.

**i18n keys** (add to `en` and `ar` blocks in `i18n.js`):

```
media.snap        media.record         media.stop
media.recording   media.autoStop       media.clearAll
media.empty       media.statsItems     media.statsBytes
media.download    media.delete         media.confirmDelete
media.confirmClear
```

## Error handling

| Failure | Detection | Behavior |
|---|---|---|
| `screencap` returns black/empty frame | First-byte check + size < 1 KB | Retry once after 200 ms; on second failure return `{code:"capture_failed", message:"framebuffer unavailable"}`. Companion toast `capture failed — try again`. |
| `screenrecord` exits before stop signal | PID gone at SIGINT time | Salvage tmp file. If file < 1 KB or `moov` atom unparseable, `{code:"recording_lost"}`. Clear `mediaRecording=false` regardless. |
| Carroot socket dead | `sendToCarroot` returns false | `{code:"carroot_unreachable"}`. State not mutated. |
| `cp /data/local/tmp/... filesDir/...` fails | exit code ≠ 0 | `{code:"copy_failed"}`. Tmp file left in place; next launcher start cleans it. |
| `MediaMetadataRetriever` throws on stop | try/catch in `generateThumb` | Logged, capture still succeeds. Companion falls back to play-glyph for that row. |
| Retention eviction races a download | File deleted mid-`GET` | `serveStaticMedia` returns 404; companion list refresh removes the stale row on next `capture.list`. |
| Process restarted mid-recording | `init()` finds orphan screenrecord PID via `ps -ef \| grep screenrecord \| grep -v grep` | SIGINT the orphan, salvage tmp file, broadcast `capture.added`, set `mediaRecording=false`. |
| Browser disconnects mid-recording | WS close | Recording continues. Reconnecting browser pulls `media.recording=true` + `startedAt` from snapshot and resumes the counter. |
| Two browsers, one hits Stop | First wins | Second gets `{code:"not_recording"}`. `capture.recording` broadcast keeps both in sync. |
| 3-min cap not honored by MTK | `screenrecord` still running at 185 s | Watchdog `Handler.postDelayed(185_000)` fires `stopVideoRecording` belt-and-braces. |
| Storage < 100 MB free | `StatFs(filesDir).availableBytes` at `startVideo` | Refuse: `{code:"low_storage", freeBytes}`. No mid-recording check. |

### Explicitly NOT handled in v1

- Mid-recording storage-full (`screenrecord` silently truncates).
- Screen-off captures (`screencap` returns the last frozen frame; acceptable).
- DRM/secure-flag windows (black out natively in `screencap`; acceptable).
- Audio-only-toggle or no-audio-toggle (mic audio is committed for v1).
- On-device gallery or side-button gesture (deferred; web-only).

## Auth & gating

Same as every other web RPC. The companion is LAN-only and only accessible when the user has explicitly enabled the web server in Settings → Network. No additional auth for capture methods.

## Testing notes

- **Manual on R1:** install, open companion, tap snap → PNG appears in grid, downloads cleanly. Start record, wait 5 s, stop → MP4 appears with thumb, plays back with audio in browser.
- **3-min auto-stop:** start record, let it run; verify auto-stop and that the file is playable.
- **Retention:** drop `MAX_FILES = 5` locally, take 7 screenshots, verify oldest 2 evicted.
- **Recovery:** start record, force-stop launcher via `am force-stop`, reopen → check log for `recovered orphan screenrecord` and that the salvaged MP4 appears.
- **Two-browser sync:** open companion on two devices, start record from one, verify the other shows the pulsing rec chip and synced counter; stop from the second, verify first rolls back.
- **Low storage:** fill `filesDir` with a junk file leaving < 100 MB, attempt record → expect `low_storage` error.

## Out of scope (future)

- On-device panel with thumbnail grid and side-button gesture.
- Audio toggle (mic / silent / system+mic) per recording.
- Editing tools (trim, crop, annotate).
- Cloud upload / share targets.
- Captures triggered via the existing ntfy push channel.
- Time-based retention (`older than X days`).
