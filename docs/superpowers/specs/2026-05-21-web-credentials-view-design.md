# Web companion: replace messages + send-text with a credentials view

**Date:** 2026-05-21
**Status:** design — ready for implementation plan
**Scope:** web companion (`app/src/main/assets/web/` + `app/src/main/java/com/r1/launcher/web/`) only. On-device launcher UI is unchanged.

## Goal

Remove the **messages** and **send text** tiles from the web companion home grid. Replace them with a single **credentials** tile that opens a new view where the user can:

- set the ElevenLabs API key + catalog voice id + custom voice id
- manage Hermes connections (list, add, edit, delete, activate)
- set the ntfy.sh topic

This brings the web companion to rough parity with the on-device Settings → Credentials panel, which already manages exactly these three blocks.

## Removals

From `app/src/main/assets/web/index.html`:
- `<button class="app-tile" data-app="sms">` (line ~86)
- `<button class="app-tile" data-app="send">` (line ~91)
- `<section id="view-sms">` (line ~143–156)
- `<section id="view-send">` (line ~158–179)

From `app/src/main/assets/web/app.js`:
- the `view-sms` route handler, `sms-threads` / `sms-thread-body` population logic (around L478 / L515)
- the `text.send` submit handler (around L574)
- any i18n keys scoped to `sms.*` and `send.*` in `i18n.js`

From `app/src/main/assets/web/style.css`:
- `.sms-*`, `.send-*` selectors

From `app/src/main/java/com/r1/launcher/web/WebRpc.kt`:
- `"sms.list"` and `"sms.thread"` dispatch cases
- `"text.send"` dispatch case + `handleTextSend` helper
- `buildSmsList` / `buildSmsThread` private helpers
- `import com.r1.launcher.messages.SmsLoader` (no longer used here)

**Functional consequence:** the existing `text.send` view served three targets:
- `voice_key` — absorbed by the new `credentials.set_voice_key` RPC
- `voice_custom_id` — absorbed by the new `credentials.set_voice_custom_id` RPC
- `openclaw_chat` — **dropped from the web companion entirely**. The OpenClaw chat panel on-device is unchanged.

`SmsLoader` itself is not removed — the on-device Messages panel still uses it. Only the web-side reads of it go away.

## Additions

### Home grid

Replace the two removed tiles with one new tile:

```html
<button class="app-tile" data-app="credentials" style="--i:2">
    <span class="tile-glyph">⚿</span>
    <span class="tile-label" data-i18n="tile.credentials.label">credentials</span>
    <span class="tile-sub" data-i18n="tile.credentials.sub">api keys + topics</span>
</button>
```

Final grid order: terminal · credentials · system · meetings.

### New view section

```html
<section id="view-credentials" class="view view-app"
         data-title-key="view.credentials" data-title="credentials">
    <div class="app-mount"></div>
    <div class="app-body credentials-body">
        <!-- ElevenLabs block -->
        <fieldset class="cred-block">
            <legend data-i18n="cred.eleven.title">elevenlabs</legend>
            <label class="field">
                <span class="field-label" data-i18n="cred.eleven.key">api key</span>
                <input id="cred-eleven-key" type="text" autocomplete="off"
                       data-i18n-placeholder="cred.eleven.keyPh"
                       placeholder="sk_... or 32-char hex">
                <span id="cred-eleven-key-tail" class="cred-tail"></span>
            </label>
            <label class="field">
                <span class="field-label" data-i18n="cred.eleven.voice">catalog voice</span>
                <select id="cred-eleven-voice">
                    <option value="21m00Tcm4TlvDq8ikWAM">rachel</option>
                    <option value="pNInz6obpgDQGcFmaJgB">adam</option>
                    <option value="9BWtsMINqrJLrRacOk9x">aria</option>
                    <option value="EXAVITQu4vr4xnSDxMaL">sarah</option>
                </select>
            </label>
            <label class="field">
                <span class="field-label" data-i18n="cred.eleven.custom">custom voice id (optional)</span>
                <input id="cred-eleven-custom" type="text" autocomplete="off"
                       data-i18n-placeholder="cred.eleven.customPh"
                       placeholder="overrides catalog when set">
            </label>
            <button id="cred-eleven-save" class="primary-btn" data-i18n="cred.save">save</button>
            <div id="cred-eleven-status" class="hint"></div>
        </fieldset>

        <!-- Hermes block -->
        <fieldset class="cred-block">
            <legend data-i18n="cred.hermes.title">hermes</legend>
            <ul id="cred-hermes-list" class="cred-hermes-list"></ul>
            <div id="cred-hermes-add-wrap">
                <label class="field">
                    <span class="field-label" data-i18n="cred.hermes.url">server url</span>
                    <input id="cred-hermes-url" type="text" autocomplete="off"
                           placeholder="https://hermes.example/v1">
                </label>
                <label class="field">
                    <span class="field-label" data-i18n="cred.hermes.bearer">bearer token</span>
                    <input id="cred-hermes-bearer" type="text" autocomplete="off"
                           placeholder="eyJ...">
                </label>
                <button id="cred-hermes-add" class="primary-btn" data-i18n="cred.hermes.add">add connection</button>
                <div id="cred-hermes-status" class="hint"></div>
            </div>
        </fieldset>

        <!-- Ntfy block -->
        <fieldset class="cred-block">
            <legend data-i18n="cred.ntfy.title">ntfy.sh</legend>
            <label class="field">
                <span class="field-label" data-i18n="cred.ntfy.topic">topic</span>
                <input id="cred-ntfy-topic" type="text" autocomplete="off"
                       placeholder="r1-alerts-xxxxx">
            </label>
            <button id="cred-ntfy-save" class="primary-btn" data-i18n="cred.save">save</button>
            <div id="cred-ntfy-status" class="hint"></div>
        </fieldset>
    </div>
</section>
```

Each Hermes list row renders as:

```
host-label              [activate] [edit] [delete]
bearer: eyJ…xyz12
```

The active row gets a `cred-hermes-active` class (orange dot + bold label) and its `[activate]` button is omitted.

`[edit]` opens an inline `<form>` underneath the row with URL + bearer inputs and `[save] [cancel]` buttons.

## RPC surface

New `WebRpc.kt` methods, all in the `credentials.*` namespace:

| method                              | params                                | returns                              |
|-------------------------------------|---------------------------------------|--------------------------------------|
| `credentials.get`                   | —                                     | the snapshot object below            |
| `credentials.set_voice_key`         | `{key: String}`                       | `null`                               |
| `credentials.set_voice_id`          | `{id: String}` (catalog id)           | `null`                               |
| `credentials.set_voice_custom_id`   | `{id: String}` (empty = clear)        | `null`                               |
| `credentials.hermes_add`            | `{url: String, bearer: String}`       | `{id: String}` or error              |
| `credentials.hermes_update`         | `{id, url, bearer}`                   | `null`                               |
| `credentials.hermes_delete`         | `{id: String}`                        | `null`                               |
| `credentials.hermes_activate`       | `{id: String}`                        | `null`                               |
| `credentials.set_ntfy_topic`        | `{topic: String}`                     | `null`                               |

`credentials.get` payload — **secrets get tailed, identifiers stay full**:

```json
{
  "elevenlabs": {
    "hasApiKey":     true,
    "apiKeyTail":    "sk_…abc12",
    "voiceId":       "21m00Tcm4TlvDq8ikWAM",
    "voiceCustomId": ""
  },
  "hermes": {
    "maxConnections": 5,
    "activeId":       "c1",
    "connections": [
      {
        "id":         "c1",
        "url":        "https://hermes.example/v1",
        "hostLabel":  "hermes.example",
        "hasBearer":  true,
        "bearerTail": "eyJ…xyz12"
      }
    ]
  },
  "ntfy": {
    "topic": "r1-alerts-xxxxx"
  }
}
```

**Tail rule:** show the last 5 visible characters of the secret, prefixed with the literal first 3 (for ElevenLabs keys: `sk_`) or `eyJ` (for JWT-shaped Hermes bearers), with `…` in the middle. For non-matching shapes, fall back to `…<last5>`. Helper: `secretTail(s)` in `WebRpc.kt`.

### Snapshot integration

Add a top-level `credentials` block to the 1 Hz `state.snapshot` event so the view auto-refreshes when something changes on the device (e.g. user pastes a key into Settings → Credentials). The block shape matches `credentials.get` exactly so the JS side can pass either through the same renderer.

To avoid bloating every snapshot tick: include the `credentials` block only when the panel is `SETTINGS_CREDENTIALS`, the web view is `view-credentials` (track via a `credentials.subscribe` / `credentials.unsubscribe` RPC pair), or — simpler — always include it. Pick **always include** because the payload is ~300 bytes and the snapshot already serializes the openclaw / hermes / terminal / notifications blocks unconditionally.

## Host-side support needed

`LauncherHost` in `LauncherNav.kt` already exposes:
- `voiceSaveKey(key)` ✓
- `voiceSaveCustomVoiceId(id)` ✓
- `voiceClearCustomVoiceId()` ✓ — invoked when `set_voice_custom_id` receives an empty string
- `hermesAddConnection(url, key)` ✓
- `hermesUpdateConnection(id, url?, key?)` ✓
- `hermesDeleteConnection(id)` ✓
- `hermesSetActiveConnection(id)` ✓
- `ntfySetTopic(topic)` ✓

**One new host method required:**
- `fun voiceSetVoiceId(id: String)` — writes `voicePrefs.voiceId = id` + `state.voiceId = id`. Validates `id` against the 4-voice catalog in `VoicePrefs.kt` and no-ops on unknown ids (logs a warning). On-device `voiceCycleVoiceId` already exists but doesn't accept a target id; the web wants direct selection.

## Security stance

This view exposes credential-setting and partial credential-reading over the web companion's **unauthenticated LAN HTTP channel**. The existing posture in `WebRpc.kt` (the `notifications.token` comment) deliberately refuses to expose secrets — this design relaxes that posture for these three blocks under the rule below:

- **Secrets are tailed, not full.** A passive snooper on the LAN sees `sk_…abc12` for the ElevenLabs key and `eyJ…xyz12` for each Hermes bearer — enough for the user to confirm a value, not enough to drain billing or impersonate. They do not see full values via any read path (`credentials.get` or `state.snapshot`).
- **Writes are full-value and unauthenticated.** Anyone reachable on the LAN can overwrite all of these. This matches the existing behaviour of `text.send → voice_key`, which the credentials view replaces.
- **Ntfy topic is shown in full** because the user needs to read it to configure their webhook sender. The topic is more identifier than secret; anyone who knows it can listen to your pushes, but you'd already be sharing it externally to receive anything.
- **Mitigations remain user-controlled:** remote panel off by default (Settings → Network → "remote panel"). When enabled on a hostile LAN, all bets are off — same as before.

A passcode-gated web companion (`Panel.PANEL_PASSCODE` already exists in the enum) would fix the write-side risk but is out of scope for this change.

## i18n

New keys to add to `app/src/main/assets/web/i18n.js`, in `en` / `ar` / `fr`:

- `tile.credentials.label` / `tile.credentials.sub`
- `view.credentials`
- `cred.save`
- `cred.eleven.title` / `cred.eleven.key` / `cred.eleven.keyPh` / `cred.eleven.voice` / `cred.eleven.custom` / `cred.eleven.customPh`
- `cred.hermes.title` / `cred.hermes.url` / `cred.hermes.bearer` / `cred.hermes.add` / `cred.hermes.activate` / `cred.hermes.edit` / `cred.hermes.delete` / `cred.hermes.activeMark` / `cred.hermes.capReached`
- `cred.ntfy.title` / `cred.ntfy.topic`
- `cred.status.saved` / `cred.status.failed`

Remove unused `sms.*` and `send.*` keys at the same time.

## Testing

- `./gradlew assembleDebug && adb install -r …` then open the web companion from another LAN device.
- Verify the messages + send-text tiles are gone and the credentials tile takes their place.
- Set an ElevenLabs key, reload, confirm the tail shows. Save a Hermes connection, observe the on-device Settings → Credentials reflects it after the 1 Hz snapshot tick.
- Delete a Hermes connection from the device; confirm the web view removes the row within ~1 s.
- Hit the 5-connection cap; confirm the add button greys out + i18n `cred.hermes.capReached` shows.
- Set an empty custom voice id; confirm the override is cleared on-device.
- Set the ntfy topic; confirm `NtfyPrefs.topic` updates and the subscriber resubscribes (this should already happen via the existing `ntfySetTopic` path).
- Confirm `text.send`, `sms.list`, `sms.thread` now return `unknown_method` over RPC and the SPA no longer references them.

## Out of scope

- OpenClaw chat send-target from the web (deliberately dropped — say so on the next pass if you want it kept).
- Passcode auth for the web companion.
- Per-Hermes-connection labels or models (the launcher data model doesn't support them; would need a `HermesConnection` schema change).
- Reading Termux / Claude credentials from the web.
