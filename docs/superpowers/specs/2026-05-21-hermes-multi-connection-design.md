# Hermes Agent — multi-connection support

**Status:** design approved 2026-05-21
**Scope:** `app/src/main/java/com/r1/launcher/hermes/**`, `ui/HermesConfigPanel.kt`, `LauncherState.kt`, `LauncherActivity.kt`, `LauncherNav.kt`, `LauncherRoot.kt`, `web/WebRpc.kt`
**Out of scope:** per-connection model override, custom labels (auto-host only), web RPCs for connection management, exporting/importing connection lists

## Goal

Let the user maintain up to 5 Hermes Agent connections, pick one as active, and have each connection keep its own chat history and server session. v1 today supports exactly one URL+key pair.

## Requirements

- Multiple connections (hard cap = 5). Default active = first added; user can switch.
- Per-connection chat history (scrollback + `X-Hermes-Session-Id`).
- Auto-label connections from URL host (no rename UI in v1).
- Add via existing manual URL/key flow **or** QR scan — both routed through one `addConnection` API that dedupes on normalized URL.
- Migrate existing single connection on first launch into connection #0 + active. Existing scrollback (`filesDir/hermes-history.json`) preserved as that connection's history.
- Soft cap enforced in UI (hide "add new") and at the API (`addConnection` returns null when at cap).

## Architecture

### Data model — `hermes/HermesConnection.kt` (new)

```kotlin
@Serializable
data class HermesConnection(
    val id: String,           // UUID, stable across renames
    val url: String,          // e.g. "http://hermes.lan:8642/v1"
    val apiKey: String,       // bearer; "" allowed for LAN-only
    val sessionId: String,    // X-Hermes-Session-Id; rotated by clearHistory
)
```

Helper extension: `HermesConnection.hostLabel` returns the URL host portion for display.

### Prefs — `hermes/HermesPrefs.kt` (rewrite)

Process-singleton via `get(ctx)` preserved. Storage split unchanged: `hermes.secure` (EncryptedSharedPreferences) for credentials, `hermes.plain` for UI prefs.

New surface:

- `connections: List<HermesConnection>` — serialized as a JSON blob under `hermes.secure` key `hermes.connections`.
- `activeId: String?` — in `hermes.plain` under `hermes.activeId`.
- `active: HermesConnection?` — `connections.firstOrNull { it.id == activeId } ?: connections.firstOrNull()`. Logs a warning when the `firstOrNull` fallback fires.
- `addConnection(url, key): HermesConnection?` — normalizes URL (trim, lowercase host, strip trailing `/`), dedupes against existing entries. On duplicate: returns the existing entry unchanged. On cap-hit (`size >= 5`): returns null and logs. Generates UUID + fresh session id otherwise; appends; persists.
- `updateConnection(id, url? = null, key? = null)` — partial update, no-op for unknown id.
- `deleteConnection(id)` — removes; if it was active, sets `activeId` to the new list head (or null if empty). No-op for unknown id.
- `setActive(id)` — moves `activeId`; caller (not prefs) is responsible for cancelling inflight calls. No-op for unknown id (logs warning).
- `rotateSessionId(id)` — replaces the session id for that connection. No-op for unknown id.
- `hasConfig(): Boolean = active != null`.
- UI prefs (`fontSize`, `hideChat`) and the `chatCompletionsUrl()` / `healthUrl()` URL builders move to extension functions on `HermesConnection`.
- **Top-level `serverUrl` / `apiKey` / `sessionId` getters removed** — callers go through `active` or a passed-in `HermesConnection`.

**Concurrency**: a private `Mutex` (or `synchronized(this)`) wraps every read-modify-write cycle on the connections JSON. Required because `rotateSessionId` fires from the OkHttp background thread while UI mutations come from the main thread; without the lock, the second write silently overwrites the first.

**Migration** (one-shot, idempotent):

1. On `HermesPrefs.init`, check plain-prefs flag `hermes.migrated`. If `true`, skip.
2. Else, if `hermes.secure` has the old `hermes.serverUrl` key (read directly via SharedPreferences — the convenience getters have been removed):
   - Build one `HermesConnection` from old `serverUrl` + `apiKey` + `sessionId`.
   - Write the new `hermes.connections` blob and `hermes.activeId`.
   - Set `hermes.migrated = true`.
   - Delete old `hermes.serverUrl` / `hermes.apiKey` / `hermes.sessionId` keys.
3. If migration throws between blob-write and flag-set, the flag stays false but the new blob is present. On next init, the flag check fails, but the `hermes.serverUrl` is still there — re-runs migration. The blob will be overwritten with the same content (deterministic from the same old fields), so duplicate-on-crash is avoided.

### History storage — `hermes/HermesHistoryStore.kt` (rewrite)

- Per-connection files: `filesDir/hermes/history-<connectionId>.json`.
- API: `load(ctx, connectionId)`, `save(ctx, connectionId, messages)`, `clear(ctx, connectionId)`, `deleteAll(ctx, connectionId)`.
- Cap unchanged at 200 messages per file.
- **Migration**: on `load(ctx, migratedConnectionId)`, if the legacy `filesDir/hermes-history.json` exists and the per-conn file does not, copy contents over then delete the legacy file. Gated indirectly on `hermes.migrated=true` from prefs (only the migrated connection id gets the legacy file).

### Client — `hermes/HermesClient.kt` (signature change)

- `streamChat(connection: HermesConnection, history, onDelta, onDone, onError): Call`
- `testConnection(connection: HermesConnection, onResult)`
- `inflight: MutableMap<String, Call>` keyed by `connection.id`. New helpers: `cancel(id)`, `cancelAll()`.
- Reads URL builders and bearer token from the passed-in `connection`, not from `prefs`.
- OkHttp client instance still constructed once (reused across connections).

### State — `LauncherState.kt`

- Remove: `val hermesMessages` (single list).
- Add:
  - `val hermesHistories: SnapshotStateMap<String, SnapshotStateList<HermesMessage>>` — Compose-observable.
  - `fun hermesActiveHistory(): SnapshotStateList<HermesMessage>?` — returns list for `hermesActiveId`, lazily creating an empty list if none exists yet for the active id.
  - `val hermesConnections: SnapshotStateList<HermesConnection>` — observable mirror of prefs.
  - `var hermesActiveId: String?` — observable mirror.
  - `var hermesConnectionEditId: String?` — null = new-mode for the edit sub-panel.
  - `var hermesConnectionEditFocus: Int`.
  - `var hermesConnectionEditDeleteArmedAt: Long` — for the two-step delete confirm.
- Keep (still global; only one chat visible at a time): `hermesStreamingText`, `hermesPartialText`, `hermesBusy`, `hermesStatus`, `hermesTranscribing`, `hermesScrollIndex`, `hermesInputLevel`, `hermesRecording`.
- Mirror fields (`hermesServerUrl`, `hermesApiKeyTail`, `hermesModel`) continue to exist but reflect the **active** connection (or "" when no active). UI panels keep using them as today.

### Config panel UI — `ui/HermesConfigPanel.kt`

Rows scale with `connections.size`:

```
0    < back header
1..N conn rows         host label + URL-tail subtitle + active marker (orange dot, right edge)
N+1  add new           (hidden when size == 5)
N+2  scan from qr      (quick-add path; routes through addConnection)
N+3  speak replies     [toggle]
N+4  hide text input   [toggle]
N+5  test active conn  [status]
```

Wheel-nav rules (no long-press — side button is voice PTT):

- Activate on **inactive** conn → `host.hermesSetActiveConnection(id)`; marker flips, status resets.
- Activate on **active** conn → `state.openHermesConnectionEdit(id)`.
- Activate on **add new** → `state.openHermesConnectionEdit(null)` (new-mode).
- Active marker = small orange dot at the right edge. Subtitle shows `:port/path` so two same-host connections are distinguishable.

### Connection-edit sub-panel — `ui/HermesConnectionEditPanel.kt` (new), `Panel.HERMES_CONNECTION_EDIT` (new)

Rows:

```
0  < back / cancel      (returns to HERMES_CONFIG; refresh focus on originating row)
1  server url   [tail]  → RetroKeyboard
2  api key      [tail]  → RetroKeyboard
3  rotate session       (only in edit-mode, not new-mode)
4  delete connection    (only in edit-mode; red text; two-step confirm)
```

- **New-mode** (`hermesConnectionEditId == null`): row 0 reads `cancel`; rotate + delete hidden. First valid URL save triggers `host.hermesAddConnection(url, key)`, switches active to the new id, returns to `HERMES_CONFIG`.
- **Edit-mode**: row 1 / 2 save calls `host.hermesUpdateConnection(id, …)`. Rotate calls `host.hermesRotateSession(id)`. Delete: first activate sets `hermesConnectionEditDeleteArmedAt = now`; second activate within 3s calls `host.hermesDeleteConnection(id)` and pops back; after 3s the second tap re-arms.

### Activity wiring — `LauncherActivity.kt`

- `applyHermesPrefs()` syncs `hermesConnections`, `hermesActiveId`, active-mirror fields. Lazily loads per-conn history into `hermesHistories[id]` on first need.
- `hermesSendText(text)`:
  - Capture active connection snapshot at call entry. Bail + open `HERMES_CONFIG` if none.
  - Resolve `historyFor(activeId)`, append user msg, set busy, call `hermesClient.streamChat(activeConn, history, …)`.
  - `onDelta` / `onDone` / `onError` write to the **captured-id** history (defends against active-switch race; we also cancel on switch).
  - On `onDone`, `HermesHistoryStore.save(this, capturedId, history)`.
- New host methods on `LauncherHost`:
  - `hermesSetActiveConnection(id)` — `prefs.setActive(id)`; `hermesClient.cancel(outgoingId)`; clear `hermesStreamingText` / `hermesPartialText` / `hermesBusy`; refresh state mirrors.
  - `hermesAddConnection(url, key): HermesConnection?` — calls `prefs.addConnection` (dedupes; returns null at cap); refreshes state. Returns existing entry on duplicate.
  - `hermesUpdateConnection(id, url? = null, key? = null)` — calls prefs; cancels inflight for `id` if creds change; refreshes state.
  - `hermesDeleteConnection(id)` — `prefs.deleteConnection(id)`; `hermesClient.cancel(id)`; removes from `state.hermesHistories`; `HermesHistoryStore.deleteAll(this, id)`; refreshes state.
  - `hermesRotateSession(id)` — `prefs.rotateSessionId(id)`; clears the in-memory history list for `id`; `HermesHistoryStore.clear(this, id)`; refreshes state.
- QR scan path (`applyHermesConfigCode`) and v1 "scan from qr" flow: switch from direct `serverUrl=`/`apiKey=` writes to `hermesAddConnection(url, key)` + `setActive` on the returned id. Over-cap shows `state.hermesQrError = "max 5 connections — delete one first"`.
- `HermesImageLoader.loadImage` updated to read the **active** connection's bearer (not the singleton).

### Nav — `LauncherNav.kt`, `LauncherRoot.kt`

- Register `Panel.HERMES_CONNECTION_EDIT`. Back unwinds to `Panel.HERMES_CONFIG`, restoring focus to the originating row.
- Add wheel-up / wheel-down / activate dispatching for the new panel's row count.
- Add `HermesConnectionEditPanel(...)` to the `LauncherRoot.kt` z-stack with its dispatcher in the panel's `onRowClick` handler in `LauncherActivity`.

### Web RPC — `web/WebRpc.kt`

Minimal changes for v1:

- `hermes.send` / `hermes.clear` / `hermes.history` continue to operate on the active connection.
- `state.hermesMessages.size` → `state.hermesActiveHistory()?.size ?: 0`.
- `hasConfig` check → `state.hermesActiveId != null`.
- No new `hermes.connections.*` RPCs (out of scope; user manages connections from the device).

## Data flow

### Send turn

1. User submits text in `HermesChatPanel` → `host.hermesSendText(text)`.
2. Activity captures `activeConn = prefs.active` (snapshot). Bails to `HERMES_CONFIG` if null.
3. Resolves `historyFor(activeConn.id)`; appends `HermesMessage(role="user", text=…)`.
4. Sets `state.hermesBusy = true`, `state.hermesStreamingText = ""`.
5. Calls `hermesClient.streamChat(activeConn, history, onDelta, onDone, onError)`.
6. Callbacks operate on the **captured id** (not `prefs.active.id`).
7. On `onDone`, appends assistant message and persists via `HermesHistoryStore.save(this, capturedId, history)`.

### Switch active

1. User taps inactive row in `HermesConfigPanel`.
2. Panel calls `host.hermesSetActiveConnection(targetId)`.
3. Activity: `hermesClient.cancel(state.hermesActiveId)`; `state.hermesStreamingText = ""`; `state.hermesPartialText = ""`; `state.hermesBusy = false`.
4. `prefs.setActive(targetId)`; `applyHermesPrefs()`.
5. Chat panel (when opened next) reads `state.hermesActiveHistory()` → the new connection's history.

### Add via QR

1. QR scanner decodes payload → `(url, key)`.
2. Activity calls `host.hermesAddConnection(url, key)`.
3. Prefs normalizes URL, dedupes, returns existing or appends new (or null at cap).
4. On non-null: `prefs.setActive(returned.id)`; `applyHermesPrefs()`; navigate back to `HERMES_CONFIG`.
5. On null: `state.hermesQrError = "max 5 connections — delete one first"`; scanner stays open.

## Error handling & edge cases

(See spec Section 4 for full list. Highlights:)

- **Stream error mid-flight**: existing handling — `hermesStatus = "error: …"`, busy cleared, error scoped to active conn's history.
- **Prefs migration failure**: log + leave old keys intact + `migrated` flag stays false; `hasConfig()` returns false until user re-pairs.
- **History migration failure**: legacy file preserved, new file simply starts empty.
- **Duplicate URL**: existing entry returned; UI opens edit panel for that entry with toast `"using existing <host>"`. Key not silently overwritten.
- **Switch active mid-stream**: outgoing stream cancelled; outgoing conn's history retains user message + any committed delta.
- **Edit URL/key on active connection while streaming**: save cancels inflight first.
- **`HermesImageLoader` race during connection switch**: v1 uses whichever connection is active at fetch time; acceptable (worst case = 401).

## Testing

Manual on-device matrix (no unit tests in this codebase). See spec Section 5 for the 17-item walkthrough — covers fresh install, upgrade migration, idempotent re-migration, add via QR/manual, dedup, switch active, edit inactive, rotate session, delete active/last, soft cap, mid-stream switch, history independence, two-step delete timing, edit-while-streaming.

Logcat tags to watch: `HermesPrefs`, `HermesClient`, `HermesHistoryStore`, `R1Motor`.

No new permissions required.

## Migration risk

The two failure modes that could brick a user:

1. **Prefs-write succeeds, flag-write fails**: next launch re-runs migration from the still-present legacy keys, overwriting the blob with the same content. Idempotent — safe.
2. **History-file rename mid-write**: best-effort copy with try/catch; failure leaves legacy file in place and new file empty. User loses no data; just sees an empty conversation for that connection until they send a new turn.

Both are non-data-loss outcomes.
