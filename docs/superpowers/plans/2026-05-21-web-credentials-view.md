# Web Credentials View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the *messages* and *send text* tiles from the web companion home grid and replace them with a single *credentials* view that lets the user set the ElevenLabs API key + catalog voice + custom voice id, manage Hermes connections (add/edit/delete/activate), and set the ntfy.sh topic.

**Architecture:** Single new `view-credentials` section in the existing SPA (`assets/web/`), three fieldset blocks (elevenlabs / hermes / ntfy). All reads come back through `credentials.get` + the 1 Hz `state.snapshot` event (secrets are tailed — `sk_…abc12`, `eyJ…xyz12` — never returned in full). All writes go through new `credentials.*` RPC methods in `WebRpc.kt` that delegate to existing `LauncherHost` calls (plus one new host method `voiceSetVoiceId`). The web channel is already token-gated by the launcher's 4-digit panel passcode; masked tails are defense-in-depth, not the primary control.

**Tech Stack:** Kotlin (LauncherActivity, WebRpc, R1WebServer, HermesPrefs/VoicePrefs/NtfyPrefs), vanilla JS/HTML/CSS for the SPA. No unit-test infrastructure — verification is `./gradlew assembleDebug` + `adb install -r` + on-device check.

**Spec:** `docs/superpowers/specs/2026-05-21-web-credentials-view-design.md`

---

## File Map

**Modified:**
- `app/src/main/java/com/r1/launcher/LauncherNav.kt` — add one host method to the interface
- `app/src/main/java/com/r1/launcher/LauncherActivity.kt` — impl the new host method
- `app/src/main/java/com/r1/launcher/web/WebRpc.kt` — remove sms.* + text.send; add credentials.* dispatch + `secretTail` helper + `buildCredentialsBlock`; extend `buildSnapshot` with the credentials block
- `app/src/main/assets/web/index.html` — remove sms + send tiles + sections; add credentials tile + section
- `app/src/main/assets/web/app.js` — remove sms + send JS; add credentials JS controller
- `app/src/main/assets/web/style.css` — remove sms + send styles; add credentials styles
- `app/src/main/assets/web/i18n.js` — remove sms.* + send.* keys (en + ar); add cred.* keys (en + ar)

**Not modified:**
- `LauncherState.kt` — no new state needed; everything in the credentials view reads from existing prefs
- `HermesPrefs.kt`, `VoicePrefs.kt`, `NtfyPrefs.kt` — already expose the operations we need
- `messages/SmsLoader.kt` — still used by the on-device Messages panel; only the web-side import goes away

---

### Task 1: Add `voiceSetVoiceId` host method

The on-device launcher has `voiceCycleVoiceId()` (cycles through the 4-voice catalog) but no method to set a specific voice id directly. The web dropdown needs direct selection.

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/LauncherNav.kt:46-65` (the voice-method block in the `LauncherHost` interface)
- Modify: `app/src/main/java/com/r1/launcher/LauncherActivity.kt` near `voiceCycleVoiceId()` (line ~3502)

- [ ] **Step 1: Add the interface method**

In `LauncherNav.kt`, locate `fun voiceCycleVoiceId()` (around line 45). Immediately below it, add:

```kotlin
    /** Set the catalog voice to the exact id [id]. No-ops with a warning log
     *  when [id] is not in [com.r1.launcher.voice.VoicePrefs.VOICES]. Used by
     *  the web companion's credentials view where the user picks a voice from
     *  a dropdown instead of cycling. */
    fun voiceSetVoiceId(id: String)
```

- [ ] **Step 2: Implement the method in `LauncherActivity`**

In `LauncherActivity.kt`, locate `override fun voiceCycleVoiceId() {` (around line 3502). Immediately below the closing brace of that function, add:

```kotlin
    override fun voiceSetVoiceId(id: String) {
        val voices = com.r1.launcher.voice.VoicePrefs.VOICES
        val match = voices.firstOrNull { it.second == id }
        if (match == null) {
            android.util.Log.w("R1Voice", "voiceSetVoiceId($id) ignored: not in catalog")
            return
        }
        state.voiceId = match.second
        voicePrefs.voiceId = match.second
        toast("voice: ${match.first}")
    }
```

- [ ] **Step 3: Build and confirm no other `LauncherHost` impls broke**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If there are any test doubles or alternate implementations of `LauncherHost` they'll fail to compile — there shouldn't be any (the launcher has no test sources), but the compiler will tell you.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/LauncherNav.kt app/src/main/java/com/r1/launcher/LauncherActivity.kt
git commit -m "voice: add voiceSetVoiceId host method for direct catalog selection"
```

---

### Task 2: Add `secretTail` helper + `buildCredentialsBlock` in WebRpc

The tail rule: secrets keep their first 3 visible chars (or `sk_` / `eyJ` literal), then `…`, then last 5 chars. Identifiers (URLs, voice ids, ntfy topic) come back full. This task adds the helper + the block builder but does not wire them into any dispatch case yet — that's Tasks 4–5.

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/web/WebRpc.kt`

- [ ] **Step 1: Add `secretTail` helper near the top of the `object WebRpc`**

Insert directly after `class RpcException` (around line 29), before `object WebRpc {`:

```kotlin
/**
 * Mask a secret for read-back over the web channel. Keeps the first 3 visible
 * chars of the value (or the `sk_` / `eyJ` literal when the value starts with
 * one) and the last 5, ellipsizing the middle.
 *
 * Examples:
 *   secretTail("")                                  // -> ""
 *   secretTail("sk_abcdef…xyz12345")                // -> "sk_…12345"
 *   secretTail("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXV") // -> "eyJ…cCI6V"  (last 5)
 *   secretTail("rachel")                            // -> "…achel"
 */
internal fun secretTail(value: String): String {
    if (value.isEmpty()) return ""
    val v = value.trim()
    val prefix = when {
        v.startsWith("sk_") -> "sk_"
        v.startsWith("eyJ") -> "eyJ"
        v.length >= 3 -> v.take(3)
        else -> ""
    }
    val last5 = v.takeLast(5)
    // Short values (<= prefix + last5 = 8 chars): just return as-is, no ellipsis.
    if (v.length <= prefix.length + last5.length) return v
    return "$prefix…$last5"
}
```

- [ ] **Step 2: Add `buildCredentialsBlock` private helper inside `object WebRpc`**

Insert at the bottom of the `object WebRpc` body, just before the final `}` that closes the object (after `handleTextSend`'s closing brace — but `handleTextSend` will be removed in Task 6; for now put `buildCredentialsBlock` after `buildSmsThread`).

```kotlin
    /** Build the credentials block for `credentials.get` and for inclusion in
     *  the 1 Hz `state.snapshot`. Secrets are tailed; URLs / voice ids / topic
     *  are returned in full. See spec for the threat model rationale. */
    internal fun buildCredentialsBlock(ctx: Context): JsonObject {
        val voicePrefs = com.r1.launcher.voice.VoicePrefs.get(ctx)
        val hermesPrefs = com.r1.launcher.hermes.HermesPrefs.get(ctx)
        val ntfyPrefs = com.r1.launcher.notifications.NtfyPrefs.get(ctx)

        return buildJsonObject {
            put("elevenlabs", buildJsonObject {
                val key = voicePrefs.elevenlabsKey.orEmpty()
                put("hasApiKey", key.isNotBlank())
                put("apiKeyTail", secretTail(key))
                put("voiceId", voicePrefs.voiceId)
                put("voiceCustomId", voicePrefs.customVoiceId.orEmpty())
            })
            put("hermes", buildJsonObject {
                put("maxConnections", com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS)
                put("activeId", hermesPrefs.active?.id ?: "")
                put("connections", buildJsonArray {
                    hermesPrefs.connections.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("url", c.url)
                            put("hostLabel", c.hostLabel)
                            put("hasBearer", c.apiKey.isNotBlank())
                            put("bearerTail", secretTail(c.apiKey))
                        })
                    }
                })
            })
            put("ntfy", buildJsonObject {
                put("topic", ntfyPrefs.topic)
            })
        }
    }
```

Note: `VoicePrefs.elevenlabsKey`, `VoicePrefs.customVoiceId`, and `NtfyPrefs.topic` are nullable-or-blank in different ways depending on the prefs class. Verify the exact property names before committing — if a `get(ctx)` factory isn't right or the property is named differently, use the same access pattern that `LauncherActivity` uses (search for `voicePrefs.elevenlabsKey` and `voicePrefs.customVoiceId` to confirm).

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Helper not yet called from anywhere; this step just confirms it compiles.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/web/WebRpc.kt
git commit -m "web: secretTail helper + buildCredentialsBlock builder"
```

---

### Task 3: Wire `credentials` block into `state.snapshot`

The 1 Hz snapshot event needs to carry the credentials block so the web view can refresh on its own when the on-device Settings → Credentials panel changes something.

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/web/WebRpc.kt` — `buildSnapshot` function (line ~219)

- [ ] **Step 1: Add the credentials block to `buildSnapshot`**

In `WebRpc.kt`, locate the `fun buildSnapshot(...)` body. After the `put("notifications", ...) { … }` block (the last existing top-level field, around line 268), add:

```kotlin
        ctx?.let {
            put("credentials", buildCredentialsBlock(it))
        }
```

The `ctx?.let` guards against the rare caller that doesn't thread `ctx` through. Existing callers do.

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Install + verify the snapshot field appears**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

From a LAN client, exchange the passcode for a token at `POST /api/auth`, then `GET /api/state?t=<token>`. Confirm the response JSON has a top-level `credentials` object with `elevenlabs`, `hermes`, `ntfy` sub-blocks.

Expected `credentials.elevenlabs.apiKeyTail` matches whatever's currently saved (or empty string if no key set).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/web/WebRpc.kt
git commit -m "web: include credentials block in state.snapshot"
```

---

### Task 4: Add `credentials.*` write RPCs for ElevenLabs + Ntfy

Five new methods: `credentials.get`, `credentials.set_voice_key`, `credentials.set_voice_id`, `credentials.set_voice_custom_id`, `credentials.set_ntfy_topic`. All delegate to existing host methods (plus `voiceSetVoiceId` from Task 1).

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/web/WebRpc.kt` — `dispatch` when-block (line ~39)

- [ ] **Step 1: Add the five `credentials.*` cases to `dispatch`**

In `WebRpc.kt`, locate the `when (method)` block inside `dispatch`. Add these cases — a logical place is right after the existing `"voice.clear_custom_id"` case (around line 78). Order them so the read sits first:

```kotlin
        "credentials.get" -> buildCredentialsBlock(ctx)

        "credentials.set_voice_key" -> {
            host.voiceSaveKey(params.requireString("key")); JsonNull
        }
        "credentials.set_voice_id" -> {
            host.voiceSetVoiceId(params.requireString("id")); JsonNull
        }
        "credentials.set_voice_custom_id" -> {
            val v = params.requireString("id").trim()
            if (v.isEmpty()) host.voiceClearCustomVoiceId()
            else host.voiceSaveCustomVoiceId(v)
            JsonNull
        }
        "credentials.set_ntfy_topic" -> {
            host.ntfySetTopic(params.requireString("topic").trim()); JsonNull
        }
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Smoke-test from the device**

Install + restart as in Task 3. Open the existing web companion, open the browser console, and run:

```js
window.R1WS.rpc('credentials.get').then(console.log);
window.R1WS.rpc('credentials.set_ntfy_topic', {topic: 'r1-test-2026'}).then(() => 
    window.R1WS.rpc('credentials.get').then(console.log));
```

(If the SPA's RPC entry isn't exposed as `window.R1WS.rpc`, find the equivalent by reading `app.js` — the existing `rpc()` function is module-scoped; you may need to temporarily expose it for this smoke test, or just trust the next task's UI to exercise the wiring.)

Expected: second call's snapshot has `ntfy.topic = "r1-test-2026"`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/web/WebRpc.kt
git commit -m "web: credentials.get + voice/ntfy set RPCs"
```

---

### Task 5: Add `credentials.hermes_*` write RPCs

Four new methods: `credentials.hermes_add`, `credentials.hermes_update`, `credentials.hermes_delete`, `credentials.hermes_activate`. All delegate to existing host methods.

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/web/WebRpc.kt` — `dispatch` when-block

- [ ] **Step 1: Add the four `credentials.hermes_*` cases**

In `WebRpc.kt`, immediately below the cases from Task 4, add:

```kotlin
        "credentials.hermes_add" -> {
            val url = params.requireString("url").trim()
            val bearer = params.requireString("bearer").trim()
            val added = host.hermesAddConnection(url, bearer)
            if (added == null) {
                throw RpcException(
                    "hermes_add_failed",
                    "could not add (cap reached or invalid url)",
                )
            }
            buildJsonObject { put("id", added.id) }
        }
        "credentials.hermes_update" -> {
            host.hermesUpdateConnection(
                id = params.requireString("id"),
                url = params.requireString("url").trim(),
                key = params.requireString("bearer").trim(),
            )
            JsonNull
        }
        "credentials.hermes_delete" -> {
            host.hermesDeleteConnection(params.requireString("id")); JsonNull
        }
        "credentials.hermes_activate" -> {
            host.hermesSetActiveConnection(params.requireString("id")); JsonNull
        }
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Smoke-test**

Install + restart. From the browser console:

```js
window.R1WS.rpc('credentials.hermes_add', {url: 'https://example.test/v1', bearer: 'eyJtest'})
    .then(r => console.log('added', r))
    .then(() => window.R1WS.rpc('credentials.get').then(console.log));
```

Expected: the second call's snapshot has the new connection in `hermes.connections[]` with `bearerTail: "eyJ…ytest"` (or similar tail of the bearer you sent).

Then delete it:

```js
window.R1WS.rpc('credentials.hermes_delete', {id: '<the id from above>'});
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/r1/launcher/web/WebRpc.kt
git commit -m "web: credentials.hermes_* add/update/delete/activate RPCs"
```

---

### Task 6: Remove `sms.*` and `text.send` RPCs from WebRpc

Now that the credentials view absorbs the voice-key and voice-custom-id targets, the legacy `text.send` and the `sms.*` reads are dead. The `openclaw_chat` send-target is dropped entirely (per spec, out of scope to re-home).

**Files:**
- Modify: `app/src/main/java/com/r1/launcher/web/WebRpc.kt`

- [ ] **Step 1: Delete the four legacy dispatch cases**

In `WebRpc.kt`, delete these cases from the `when` block:

- `"sms.list" -> buildSmsList(ctx)`
- `"sms.thread" -> buildSmsThread(ctx, params.requireString("address"))`
- `"text.send" -> handleTextSend(host, state, params)`

- [ ] **Step 2: Delete the dead helpers**

Delete the three private helpers: `buildSmsList`, `buildSmsThread`, `handleTextSend` (entire function bodies including the `/** ... */` KDoc above `handleTextSend`).

- [ ] **Step 3: Remove the now-unused import**

Delete the line `import com.r1.launcher.messages.SmsLoader` from the imports at the top of `WebRpc.kt`.

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If the build fails with "unresolved reference: SmsLoader", the helpers weren't fully deleted; re-check Step 2.

- [ ] **Step 5: Install + verify the methods now 404**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'
```

From the browser console (assuming the SPA still loads — it will, the JS side cleanup is Task 10):

```js
window.R1WS.rpc('sms.list').catch(e => console.log('expected:', e.message));
window.R1WS.rpc('text.send', {target:'voice_key', text:'sk_x'}).catch(e => console.log('expected:', e.message));
```

Expected: both reject with `unknown_method: ...`. The SPA itself may throw uncaught errors on the home screen because `sms-refresh` etc. still exist — that's expected until Task 10.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/r1/launcher/web/WebRpc.kt
git commit -m "web: remove sms.* and text.send RPCs (replaced by credentials view)"
```

---

### Task 7: Swap tiles + add `view-credentials` section in `index.html`

**Files:**
- Modify: `app/src/main/assets/web/index.html`

- [ ] **Step 1: Replace the two old tiles with one new tile**

In `index.html`, find the `<nav class="apps-grid">` block (line ~80). Delete these two `<button>` blocks (lines ~86–95):

```html
<button class="app-tile" data-app="sms" style="--i:2">
    <span class="tile-glyph">✉</span>
    <span class="tile-label" data-i18n="tile.sms.label">messages</span>
    <span class="tile-sub" data-i18n="tile.sms.sub">sms threads</span>
</button>
<button class="app-tile" data-app="send" style="--i:3">
    <span class="tile-glyph">↗</span>
    <span class="tile-label" data-i18n="tile.send.label">send text</span>
    <span class="tile-sub" data-i18n="tile.send.sub">paste into r1</span>
</button>
```

Replace them with one new tile (keeping `--i:2` so the grid order stays sane):

```html
<button class="app-tile" data-app="credentials" style="--i:2">
    <span class="tile-glyph">⚿</span>
    <span class="tile-label" data-i18n="tile.credentials.label">credentials</span>
    <span class="tile-sub" data-i18n="tile.credentials.sub">api keys + topics</span>
</button>
```

Also re-number the remaining tiles' `--i` indices to keep the staggered-entry animation tidy: the `system` tile becomes `--i:3` and `meetings` becomes `--i:4`.

- [ ] **Step 2: Delete the `view-sms` and `view-send` sections**

Delete the entire `<section id="view-sms">…</section>` block (lines ~143–156) and the entire `<section id="view-send">…</section>` block (lines ~158–179) from `index.html`.

- [ ] **Step 3: Insert the new `view-credentials` section**

Insert this block where the deleted `view-send` section was (after the closing `</section>` of `view-terminal`, before the existing `view-system`):

```html
<!-- CREDENTIALS -->
<section id="view-credentials" class="view view-app"
         data-title-key="view.credentials" data-title="credentials">
    <div class="app-mount"></div>
    <div class="app-body credentials-body">
        <!-- ElevenLabs -->
        <fieldset class="cred-block">
            <legend data-i18n="cred.eleven.title">elevenlabs</legend>
            <label class="field">
                <span class="field-label" data-i18n="cred.eleven.key">api key</span>
                <input id="cred-eleven-key" type="text" autocomplete="off" spellcheck="false"
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
                <input id="cred-eleven-custom" type="text" autocomplete="off" spellcheck="false"
                       data-i18n-placeholder="cred.eleven.customPh"
                       placeholder="overrides catalog when set">
            </label>
            <button id="cred-eleven-save" class="primary-btn" data-i18n="cred.save">save</button>
            <div id="cred-eleven-status" class="hint"></div>
        </fieldset>

        <!-- Hermes -->
        <fieldset class="cred-block">
            <legend data-i18n="cred.hermes.title">hermes</legend>
            <ul id="cred-hermes-list" class="cred-hermes-list"></ul>
            <div id="cred-hermes-add-wrap">
                <label class="field">
                    <span class="field-label" data-i18n="cred.hermes.url">server url</span>
                    <input id="cred-hermes-url" type="text" autocomplete="off" spellcheck="false"
                           placeholder="https://hermes.example/v1">
                </label>
                <label class="field">
                    <span class="field-label" data-i18n="cred.hermes.bearer">bearer token</span>
                    <input id="cred-hermes-bearer" type="text" autocomplete="off" spellcheck="false"
                           placeholder="eyJ...">
                </label>
                <button id="cred-hermes-add" class="primary-btn" data-i18n="cred.hermes.add">add connection</button>
                <div id="cred-hermes-status" class="hint"></div>
            </div>
        </fieldset>

        <!-- Ntfy -->
        <fieldset class="cred-block">
            <legend data-i18n="cred.ntfy.title">ntfy.sh</legend>
            <label class="field">
                <span class="field-label" data-i18n="cred.ntfy.topic">topic</span>
                <input id="cred-ntfy-topic" type="text" autocomplete="off" spellcheck="false"
                       placeholder="r1-alerts-xxxxx">
            </label>
            <button id="cred-ntfy-save" class="primary-btn" data-i18n="cred.save">save</button>
            <div id="cred-ntfy-status" class="hint"></div>
        </fieldset>
    </div>
</section>
```

- [ ] **Step 4: Build + install**

Run: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'`

Open the web companion. Expected:
- Home grid shows terminal · credentials · system · meetings (the credentials tile may render with the raw key `tile.credentials.label` because i18n isn't wired yet — fixed in Task 8).
- Click the credentials tile — it tries to set `view-credentials` active. The section renders with three fieldsets but they're unstyled (Task 9) and non-functional (Task 10).
- The JS console will throw because `document.getElementById('sms-threads')` returns null on the legacy code paths that haven't been deleted yet. That's expected — Task 10 cleans them up.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/web/index.html
git commit -m "web: swap sms+send tiles for credentials tile + view markup"
```

---

### Task 8: i18n — remove sms.* / send.* keys, add cred.* keys (en + ar)

The launcher's i18n has two locales (`en`, `ar`). No `fr` block exists despite what the spec mentions — only en + ar.

**Files:**
- Modify: `app/src/main/assets/web/i18n.js`

- [ ] **Step 1: Delete all `sms.*` and `send.*` keys from `en` block**

In `i18n.js`, in the `en:` block, delete:
- `'tile.sms.label'`, `'tile.sms.sub'` (lines ~34–35)
- `'tile.send.label'`, `'tile.send.sub'` (lines ~36–37)
- `'view.sms'`, `'view.send'` (lines ~43–44)
- The whole `// sms` comment block + 5 keys (lines ~53–58)
- The whole `// send text` comment block + 14 keys (lines ~59–75)

- [ ] **Step 2: Delete the same keys from the `ar:` block**

Same set of keys, in the `ar:` block (lines ~113–149). The block boundaries are similar but offset.

- [ ] **Step 3: Add the new `cred.*` keys to the `en:` block**

Insert this group inside the `en:` block, in a logical location (e.g. after the deleted send-text block). Add to home-grid + view-titles in their existing locations:

In the home-grid section (after `'tile.terminal.sub'`):
```javascript
        'tile.credentials.label': 'credentials',
        'tile.credentials.sub': 'api keys + topics',
```

In the view-titles section (after `'view.terminal'`):
```javascript
        'view.credentials': 'credentials',
```

In a new credentials block (replacing the deleted sms + send blocks):
```javascript
        // credentials
        'cred.save': 'save',
        'cred.eleven.title': 'elevenlabs',
        'cred.eleven.key': 'api key',
        'cred.eleven.keyPh': 'sk_... or 32-char hex',
        'cred.eleven.voice': 'catalog voice',
        'cred.eleven.custom': 'custom voice id (optional)',
        'cred.eleven.customPh': 'overrides catalog when set',
        'cred.hermes.title': 'hermes',
        'cred.hermes.url': 'server url',
        'cred.hermes.bearer': 'bearer token',
        'cred.hermes.add': 'add connection',
        'cred.hermes.activate': 'activate',
        'cred.hermes.edit': 'edit',
        'cred.hermes.delete': 'delete',
        'cred.hermes.cancel': 'cancel',
        'cred.hermes.activeMark': 'active',
        'cred.hermes.capReached': 'connection cap reached (5)',
        'cred.ntfy.title': 'ntfy.sh',
        'cred.ntfy.topic': 'topic',
        'cred.status.saved': 'saved ✓',
        'cred.status.failed': 'failed: %s',
        'cred.status.tail': 'current: %s',
        'cred.status.unset': 'not set',
```

- [ ] **Step 4: Add the same keys to the `ar:` block (translated)**

```javascript
        'tile.credentials.label': 'بيانات الاعتماد',
        'tile.credentials.sub': 'مفاتيح API ومواضيع',
        'view.credentials': 'بيانات الاعتماد',
        'cred.save': 'حفظ',
        'cred.eleven.title': 'ElevenLabs',
        'cred.eleven.key': 'مفتاح API',
        'cred.eleven.keyPh': 'sk_... أو 32 رقم سداسي',
        'cred.eleven.voice': 'الصوت من الكتالوج',
        'cred.eleven.custom': 'معرّف صوت مخصص (اختياري)',
        'cred.eleven.customPh': 'يتجاوز الكتالوج عند ضبطه',
        'cred.hermes.title': 'Hermes',
        'cred.hermes.url': 'عنوان الخادم',
        'cred.hermes.bearer': 'رمز الحامل',
        'cred.hermes.add': 'إضافة اتصال',
        'cred.hermes.activate': 'تفعيل',
        'cred.hermes.edit': 'تعديل',
        'cred.hermes.delete': 'حذف',
        'cred.hermes.cancel': 'إلغاء',
        'cred.hermes.activeMark': 'نشط',
        'cred.hermes.capReached': 'الحد الأقصى للاتصالات (5)',
        'cred.ntfy.title': 'ntfy.sh',
        'cred.ntfy.topic': 'الموضوع',
        'cred.status.saved': 'تم الحفظ ✓',
        'cred.status.failed': 'فشل: %s',
        'cred.status.tail': 'الحالي: %s',
        'cred.status.unset': 'غير محدد',
```

- [ ] **Step 5: Build + install + verify i18n keys resolve**

Run: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'`

Open the web companion. The credentials tile should now say "credentials" / "api keys + topics" (not the raw key). Switch the launcher's locale to Arabic on-device and verify the tile re-renders in Arabic.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/web/i18n.js
git commit -m "web: i18n — drop sms/send keys, add cred.* keys (en + ar)"
```

---

### Task 9: CSS — remove sms/send styles, add credentials styles

**Files:**
- Modify: `app/src/main/assets/web/style.css`

- [ ] **Step 1: Delete the `.sms-*` and `.send-*` rule blocks**

In `style.css`, delete:
- All `.sms-body`, `.sms-list`, `.sms-threads`, `.sms-thread`, `.sms-thread-header`, `.sms-thread-body`, `.sms-msg`, `.sms-time` rules (line ~511–570).
- All `.send-body` rules (line ~573 and any continuations).
- The RTL overrides at the bottom referencing `.sms-*` (line ~711, 718).

Use `grep -n '\.sms-\|\.send-' app/src/main/assets/web/style.css` before deleting to make sure you catch them all, including any deep selectors and media-query overrides.

- [ ] **Step 2: Add the credentials styles**

Append the following to `style.css` (placement: near the other view-specific style blocks, e.g. after the `.system-body` rules). Match the existing visual language: 1-px accent borders, monospace numerals, no rounded corners beyond what `--radius` already gives.

```css
/* ============ credentials view ============ */
.credentials-body {
    max-width: 560px;
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding-bottom: 24px;
}

.cred-block {
    border: 1px solid var(--edge);
    padding: 12px 14px 14px;
    background: rgba(255, 255, 255, 0.02);
}

.cred-block > legend {
    padding: 0 6px;
    color: var(--accent);
    text-transform: lowercase;
    letter-spacing: 0.04em;
}

.cred-block .field {
    display: block;
    margin-bottom: 10px;
}

.cred-block .field-label {
    display: block;
    color: var(--muted);
    font-size: 12px;
    margin-bottom: 4px;
}

.cred-block input,
.cred-block select {
    width: 100%;
    background: #0e0e10;
    color: var(--fg-bright);
    border: 1px solid var(--edge);
    padding: 8px 10px;
    font: inherit;
}

.cred-block input:focus,
.cred-block select:focus {
    outline: none;
    border-color: var(--accent);
}

.cred-tail {
    display: block;
    color: var(--muted);
    font-size: 11px;
    margin-top: 4px;
}

.cred-block .primary-btn { margin-top: 4px; }

.cred-block .hint {
    margin-top: 8px;
    color: var(--muted);
    font-size: 12px;
    min-height: 1.2em;
}

.cred-hermes-list {
    list-style: none;
    margin: 0 0 12px;
    padding: 0;
}

.cred-hermes-list li {
    border: 1px solid var(--edge);
    padding: 8px 10px;
    margin-bottom: 6px;
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.cred-hermes-list li.cred-hermes-active {
    border-left: 3px solid var(--accent);
}

.cred-hermes-row {
    display: flex;
    align-items: center;
    gap: 8px;
    justify-content: space-between;
}

.cred-hermes-host {
    color: var(--fg-bright);
    flex: 1;
    word-break: break-all;
}

.cred-hermes-active-mark {
    color: var(--accent);
    font-size: 11px;
    text-transform: lowercase;
}

.cred-hermes-actions {
    display: flex;
    gap: 6px;
}

.cred-hermes-actions button {
    background: transparent;
    border: 1px solid var(--edge);
    color: var(--fg-bright);
    padding: 4px 8px;
    font: inherit;
    cursor: pointer;
}

.cred-hermes-actions button:hover { border-color: var(--accent); color: var(--accent); }

.cred-hermes-bearer {
    color: var(--muted);
    font-size: 11px;
}

.cred-hermes-edit-form {
    margin-top: 6px;
    padding-top: 6px;
    border-top: 1px dashed var(--edge);
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.cred-hermes-edit-form .field { margin-bottom: 0; }

.cred-hermes-edit-form-actions {
    display: flex;
    gap: 6px;
}

#cred-hermes-add[disabled] {
    opacity: 0.4;
    cursor: not-allowed;
}
```

The color custom-properties (`--accent`, `--muted`, `--fg-bright`, `--edge`, `--radius`) are defined at the top of `style.css` — verify the exact names before committing. If `--edge` is named `--border` (or similar) in this codebase, substitute accordingly.

- [ ] **Step 3: Build + install + visual check**

`./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`

Open the credentials tile in the web companion. Expected: three labelled fieldsets stack vertically, fields are dark with the orange accent on focus. Hermes list is empty (renders nothing yet — JS in Task 10). Buttons render but do nothing yet.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/web/style.css
git commit -m "web: drop sms/send styles, add credentials view styles"
```

---

### Task 10: JS — remove sms/send code, add credentials controller

This is the largest task. It removes the legacy SMS + send-text JS, then adds a credentials controller that:
1. Renders the three blocks from the snapshot's `credentials` block whenever a snapshot arrives.
2. Wires the eleven / ntfy save buttons to their RPCs.
3. Builds the Hermes list with per-row activate / edit / delete, plus the add form (greys out when cap reached).

**Files:**
- Modify: `app/src/main/assets/web/app.js`

- [ ] **Step 1: Remove the `// ============== sms ==============` block**

In `app.js`, delete from `// ============== sms ==============` (around line 474) through the end of the `document.getElementById('sms-refresh').addEventListener(...)` line (line 540 inclusive).

- [ ] **Step 2: Remove the `// ============== send text ==============` block**

Delete from `// ============== send text ==============` (around line 542) through the end of the `sendBtn.addEventListener('click', async () => { ... })` block (around line 585).

- [ ] **Step 3: Find the snapshot-handler and remove any sms/send refresh calls**

Search `app.js` for `refreshSmsList` and any other sms-/send- specific calls in the snapshot handler. Remove them. The snapshot handler typically lives in the WS message dispatcher (look for `if (event === 'state.snapshot')`).

- [ ] **Step 4: Add the credentials controller**

Append this block to `app.js` (place it after the `// ============== system ==============` block, before the final `setView('home')` line):

```javascript
// ============== credentials ==============

// Catalog ids must match the server-side VoicePrefs.VOICES list. If you change
// either, change both. The label-to-id mapping is duplicated in index.html as
// the dropdown <option> values.
const CRED_VOICE_CATALOG = [
    '21m00Tcm4TlvDq8ikWAM', // rachel
    'pNInz6obpgDQGcFmaJgB', // adam
    '9BWtsMINqrJLrRacOk9x', // aria
    'EXAVITQu4vr4xnSDxMaL', // sarah
];

const credElevenKey     = document.getElementById('cred-eleven-key');
const credElevenKeyTail = document.getElementById('cred-eleven-key-tail');
const credElevenVoice   = document.getElementById('cred-eleven-voice');
const credElevenCustom  = document.getElementById('cred-eleven-custom');
const credElevenSave    = document.getElementById('cred-eleven-save');
const credElevenStatus  = document.getElementById('cred-eleven-status');

const credHermesList    = document.getElementById('cred-hermes-list');
const credHermesUrl     = document.getElementById('cred-hermes-url');
const credHermesBearer  = document.getElementById('cred-hermes-bearer');
const credHermesAdd     = document.getElementById('cred-hermes-add');
const credHermesStatus  = document.getElementById('cred-hermes-status');

const credNtfyTopic     = document.getElementById('cred-ntfy-topic');
const credNtfySave      = document.getElementById('cred-ntfy-save');
const credNtfyStatus    = document.getElementById('cred-ntfy-status');

// Track which Hermes row (if any) is in edit mode so the user's in-flight
// edits aren't blown away when a 1 Hz snapshot triggers re-render.
let credHermesEditingId = null;
let credHermesEditDraft = { url: '', bearer: '' };

// True while the user is focused inside one of the editable fields. Skip
// re-rendering that field's value from a snapshot tick while the user is
// typing, so the cursor doesn't jump.
function credFieldFocused(el) {
    return document.activeElement === el;
}

/** Apply the credentials block from a state.snapshot or credentials.get reply. */
function applyCredentialsSnapshot(cred) {
    if (!cred) return;

    // --- ElevenLabs ---
    const eleven = cred.elevenlabs || {};
    if (!credFieldFocused(credElevenKey)) {
        credElevenKey.value = '';
        credElevenKey.placeholder = eleven.hasApiKey ? (eleven.apiKeyTail || 'sk_...') : 'sk_... or 32-char hex';
    }
    credElevenKeyTail.textContent = eleven.hasApiKey
        ? t('cred.status.tail', eleven.apiKeyTail || '')
        : t('cred.status.unset');

    if (!credFieldFocused(credElevenVoice)) {
        // Select the option matching the saved voiceId; fall back to first option.
        const wanted = eleven.voiceId;
        const opts = Array.from(credElevenVoice.options).map(o => o.value);
        credElevenVoice.value = opts.includes(wanted) ? wanted : opts[0];
    }

    if (!credFieldFocused(credElevenCustom)) {
        credElevenCustom.value = eleven.voiceCustomId || '';
    }

    // --- Hermes ---
    const hermes = cred.hermes || { connections: [], maxConnections: 5, activeId: '' };
    renderHermesList(hermes);

    // --- Ntfy ---
    const ntfy = cred.ntfy || {};
    if (!credFieldFocused(credNtfyTopic)) {
        credNtfyTopic.value = ntfy.topic || '';
    }
}

function renderHermesList(hermes) {
    const conns = Array.isArray(hermes.connections) ? hermes.connections : [];
    const activeId = hermes.activeId || '';
    const cap = hermes.maxConnections || 5;

    credHermesList.innerHTML = '';
    conns.forEach((c) => {
        const li = document.createElement('li');
        if (c.id === activeId) li.classList.add('cred-hermes-active');

        const row = document.createElement('div');
        row.className = 'cred-hermes-row';

        const host = document.createElement('div');
        host.className = 'cred-hermes-host';
        host.textContent = c.hostLabel || c.url || '';
        row.appendChild(host);

        if (c.id === activeId) {
            const mark = document.createElement('span');
            mark.className = 'cred-hermes-active-mark';
            mark.textContent = t('cred.hermes.activeMark');
            row.appendChild(mark);
        }

        const actions = document.createElement('div');
        actions.className = 'cred-hermes-actions';
        if (c.id !== activeId) {
            const btnActivate = document.createElement('button');
            btnActivate.type = 'button';
            btnActivate.textContent = t('cred.hermes.activate');
            btnActivate.addEventListener('click', () => activateHermesConn(c.id));
            actions.appendChild(btnActivate);
        }
        const btnEdit = document.createElement('button');
        btnEdit.type = 'button';
        btnEdit.textContent = t('cred.hermes.edit');
        btnEdit.addEventListener('click', () => beginEditHermesConn(c));
        actions.appendChild(btnEdit);

        const btnDelete = document.createElement('button');
        btnDelete.type = 'button';
        btnDelete.textContent = t('cred.hermes.delete');
        btnDelete.addEventListener('click', () => deleteHermesConn(c.id));
        actions.appendChild(btnDelete);

        row.appendChild(actions);
        li.appendChild(row);

        const bearer = document.createElement('div');
        bearer.className = 'cred-hermes-bearer';
        bearer.textContent = c.hasBearer
            ? t('cred.status.tail', c.bearerTail || '')
            : t('cred.status.unset');
        li.appendChild(bearer);

        if (credHermesEditingId === c.id) {
            li.appendChild(buildHermesEditForm(c));
        }

        credHermesList.appendChild(li);
    });

    const atCap = conns.length >= cap;
    credHermesAdd.disabled = atCap;
    credHermesStatus.textContent = atCap ? t('cred.hermes.capReached') : '';
}

function buildHermesEditForm(c) {
    const form = document.createElement('div');
    form.className = 'cred-hermes-edit-form';

    const urlLabel = document.createElement('label');
    urlLabel.className = 'field';
    urlLabel.innerHTML = `<span class="field-label">${escapeHtml(t('cred.hermes.url'))}</span>`;
    const urlInput = document.createElement('input');
    urlInput.type = 'text';
    urlInput.autocomplete = 'off';
    urlInput.spellcheck = false;
    urlInput.value = credHermesEditDraft.url || c.url || '';
    urlInput.addEventListener('input', () => { credHermesEditDraft.url = urlInput.value; });
    urlLabel.appendChild(urlInput);
    form.appendChild(urlLabel);

    const bearerLabel = document.createElement('label');
    bearerLabel.className = 'field';
    bearerLabel.innerHTML = `<span class="field-label">${escapeHtml(t('cred.hermes.bearer'))}</span>`;
    const bearerInput = document.createElement('input');
    bearerInput.type = 'text';
    bearerInput.autocomplete = 'off';
    bearerInput.spellcheck = false;
    bearerInput.placeholder = c.hasBearer ? (c.bearerTail || '') : '';
    bearerInput.value = credHermesEditDraft.bearer || '';
    bearerInput.addEventListener('input', () => { credHermesEditDraft.bearer = bearerInput.value; });
    bearerLabel.appendChild(bearerInput);
    form.appendChild(bearerLabel);

    const actions = document.createElement('div');
    actions.className = 'cred-hermes-edit-form-actions';

    const saveBtn = document.createElement('button');
    saveBtn.type = 'button';
    saveBtn.className = 'primary-btn';
    saveBtn.textContent = t('cred.save');
    saveBtn.addEventListener('click', () => saveHermesEdit(c));
    actions.appendChild(saveBtn);

    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.textContent = t('cred.hermes.cancel');
    cancelBtn.addEventListener('click', () => cancelHermesEdit());
    actions.appendChild(cancelBtn);

    form.appendChild(actions);
    return form;
}

function beginEditHermesConn(c) {
    credHermesEditingId = c.id;
    credHermesEditDraft = { url: c.url || '', bearer: '' };
    // Snapshot tick will re-render with the edit form.
    rpc('credentials.get').then(applyCredentialsSnapshot).catch(() => {});
}

function cancelHermesEdit() {
    credHermesEditingId = null;
    credHermesEditDraft = { url: '', bearer: '' };
    rpc('credentials.get').then(applyCredentialsSnapshot).catch(() => {});
}

async function saveHermesEdit(c) {
    try {
        await rpc('credentials.hermes_update', {
            id: c.id,
            url: credHermesEditDraft.url || c.url,
            // Empty bearer means "leave unchanged" — the server's
            // hermesUpdateConnection treats trimmed strings as the new value,
            // so we substitute the existing tail-less value... actually the
            // host always replaces, so an empty bearer would wipe it. Block
            // that: refuse to save when bearer field is empty AND the
            // connection currently has one.
            bearer: credHermesEditDraft.bearer.trim()
                || (c.hasBearer ? '' : ''), // see note below
        });
        flash(t('cred.status.saved'));
        cancelHermesEdit();
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    }
}
```

Note about `saveHermesEdit`: the host method `hermesUpdateConnection(id, url, key)` replaces the bearer with whatever is passed. A typo-safe behavior is **refuse to save when the bearer field is empty while a bearer is already saved** — better than silently wiping it. Fix the implementation to:

```javascript
async function saveHermesEdit(c) {
    const newUrl = (credHermesEditDraft.url || c.url || '').trim();
    const newBearer = credHermesEditDraft.bearer.trim();
    if (!newUrl) {
        credHermesStatus.textContent = t('cred.status.failed', 'url required');
        return;
    }
    if (!newBearer && c.hasBearer) {
        // The user opened edit, didn't type a new bearer. Leave the bearer
        // alone by sending the empty string and... no, the host overwrites.
        // Defensive: refuse and tell the user to retype the bearer.
        credHermesStatus.textContent = t('cred.status.failed', 'bearer required (retype to confirm)');
        return;
    }
    try {
        await rpc('credentials.hermes_update', { id: c.id, url: newUrl, bearer: newBearer });
        flash(t('cred.status.saved'));
        cancelHermesEdit();
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    }
}
```

Then add the remaining handlers:

```javascript
async function activateHermesConn(id) {
    try {
        await rpc('credentials.hermes_activate', { id });
        flash(t('cred.status.saved'));
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    }
}

async function deleteHermesConn(id) {
    if (!window.confirm(t('cred.hermes.delete') + '?')) return;
    try {
        await rpc('credentials.hermes_delete', { id });
        if (credHermesEditingId === id) cancelHermesEdit();
        flash(t('cred.status.saved'));
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    }
}

credHermesAdd.addEventListener('click', async () => {
    const url = (credHermesUrl.value || '').trim();
    const bearer = (credHermesBearer.value || '').trim();
    if (!url || !bearer) {
        credHermesStatus.textContent = t('cred.status.failed', 'url + bearer required');
        return;
    }
    credHermesAdd.disabled = true;
    try {
        await rpc('credentials.hermes_add', { url, bearer });
        credHermesUrl.value = '';
        credHermesBearer.value = '';
        flash(t('cred.status.saved'));
    } catch (e) {
        credHermesStatus.textContent = t('cred.status.failed', e.message);
    } finally {
        credHermesAdd.disabled = false;
    }
});

credElevenSave.addEventListener('click', async () => {
    const key = credElevenKey.value.trim();
    const voiceId = credElevenVoice.value;
    const customId = credElevenCustom.value.trim();
    credElevenSave.disabled = true;
    try {
        // Order matters: set the key first (in case the catalog/custom calls
        // ever start to depend on it server-side). Only PUSH the key when the
        // user actually typed something — empty input leaves the existing
        // key alone, matching the placeholder-shows-tail UX.
        if (key) await rpc('credentials.set_voice_key', { key });
        await rpc('credentials.set_voice_id', { id: voiceId });
        await rpc('credentials.set_voice_custom_id', { id: customId });
        credElevenKey.value = '';
        flash(t('cred.status.saved'));
        credElevenStatus.textContent = t('cred.status.saved');
    } catch (e) {
        credElevenStatus.textContent = t('cred.status.failed', e.message);
    } finally {
        credElevenSave.disabled = false;
    }
});

credNtfySave.addEventListener('click', async () => {
    const topic = credNtfyTopic.value.trim();
    credNtfySave.disabled = true;
    try {
        await rpc('credentials.set_ntfy_topic', { topic });
        flash(t('cred.status.saved'));
        credNtfyStatus.textContent = t('cred.status.saved');
    } catch (e) {
        credNtfyStatus.textContent = t('cred.status.failed', e.message);
    } finally {
        credNtfySave.disabled = false;
    }
});
```

- [ ] **Step 5: Wire `applyCredentialsSnapshot` into the snapshot handler**

Find the WS message handler in `app.js` — it dispatches on `event === 'state.snapshot'`. After the existing snapshot-handling code that updates topbar / system / etc., add:

```javascript
applyCredentialsSnapshot(snapshot.credentials);
```

Pass whatever the snapshot variable is in scope (it's usually called `snap` or `s` or `data` in this codebase — match the existing pattern).

- [ ] **Step 6: Build + install + smoke test**

`./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell 'am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity'`

From a LAN client, open the web companion. Open the credentials tile. Expected:
- ElevenLabs section shows current voice id selected in the dropdown and the custom id (if any). Key field is empty with the tail as placeholder.
- Hermes section lists existing connections with bearer tails. Active row has the orange left border.
- Ntfy section shows the current topic.
- All save / add / activate / delete / edit buttons work.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/assets/web/app.js
git commit -m "web: drop sms/send JS, add credentials view controller"
```

---

### Task 11: Final verification + edge cases

A pass through the corner cases listed in the spec's Testing section.

**Files:** none

- [ ] **Step 1: Custom voice id clearing**

In the credentials view, type something into "custom voice id (optional)" and save. Confirm on-device that Settings → Voice now shows the custom id. Then in the web view, clear the field and save again. Confirm on-device the custom id is cleared (effective voice falls back to catalog).

- [ ] **Step 2: Hermes cap**

Add Hermes connections one at a time until you have 5. The "add connection" button must grey out + show "connection cap reached (5)". Delete one, button re-enables.

- [ ] **Step 3: Hermes activate**

Have 2+ connections. Activate the non-active one. On-device Hermes config panel should reflect the new active connection within ~1 second (via the snapshot tick).

- [ ] **Step 4: Two-way sync**

Change the ntfy topic from the on-device Settings → Credentials panel. Within ~1 s the web view's ntfy field should re-render with the new value (only if the user isn't currently focused in the field — that's the focused-field guard in `applyCredentialsSnapshot`).

- [ ] **Step 5: Tail rendering**

Set the ElevenLabs key to a long value. The "current: sk_…abc12" line should show the tail. Save a Hermes connection with a JWT-shaped bearer; the per-row bearer line should show "current: eyJ…xyz12".

- [ ] **Step 6: Locale flip**

Switch the launcher's locale to Arabic on-device. All credentials view labels should re-render in Arabic, and the page direction flips to RTL.

- [ ] **Step 7: Legacy methods are gone**

From the web console, try `window.R1WS.rpc('sms.list')` and `window.R1WS.rpc('text.send', {target:'voice_key', text:''})`. Both must reject with `unknown_method`.

- [ ] **Step 8: Wrap-up commit (if any leftovers)**

```bash
git status
# If anything is dirty (typos found during verification), fix and:
git add -p
git commit -m "web: verification pass — <what you fixed>"
```

If everything's clean, this task ends without a commit.

---

## Out of Scope (not in this plan)

- Re-homing the `openclaw_chat` send-target somewhere else. The web companion loses the ability to push text into a live OpenClaw chat session; the on-device chat panel is untouched.
- Per-Hermes-connection labels or models (would need a `HermesConnection` schema change).
- Passcode auth UX changes. The existing 4-digit panel passcode flow is unchanged.
- Reading Termux / Claude credentials from the web. Termux auth lives in Termux; Claude inherits from `claude auth login`.

## Self-Review

- **Spec coverage:** Every spec section maps to a task. Home grid + removals → Task 7. Credentials view layout → Tasks 7 + 9. RPC surface → Tasks 2–6. Host-side `voiceSetVoiceId` → Task 1. Security stance (tails) → Tasks 2 + 10 (tails generated server-side, rendered client-side). i18n → Task 8. Testing → Task 11.
- **Placeholder scan:** All code blocks are concrete. The one ambiguity in Task 2 Step 2 — exact `VoicePrefs` / `NtfyPrefs` property names — is flagged for verification rather than left vague.
- **Type consistency:** `voiceSetVoiceId(id: String)` is the same signature in Tasks 1 (interface), 1 (impl), and 4 (call site). `credentials.hermes_*` method names are consistent across Tasks 5 and 10. `secretTail` is `internal fun` in Kotlin (Task 2) and never referenced from JS (the tail comes back already-masked from `credentials.get` / snapshot).
- **Note on spec security framing:** the spec says "unauthenticated LAN HTTP channel" — the actual web server is gated by the 4-digit panel passcode + token (`R1WebServer.kt:151–199`). Masked tails are defense-in-depth rather than the only control. The design decision still holds; the implementation doesn't change.
