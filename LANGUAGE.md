# LANGUAGE.md

Per-app i18n + RTL support for the R1 Launcher. Covers the on-device Compose UI, the `assets/web/` companion panel, fonts, and the cookbook for adding new languages.

Ships English (`en`) and Arabic (`ar`) at the moment. Adding a third language is a 3-file change (see [Adding a new language](#adding-a-new-language)).

## Architecture at a glance

```
        ┌─────────────────────────┐
        │ res/values/strings.xml  │      English source of truth
        │ res/values-ar/...       │      Arabic translations
        └────────────┬────────────┘
                     │
            stringResource(R.string.X)
                     │
        ┌────────────▼─────────────┐       ┌────────────────────┐
        │ Compose UI (Kotlin)       │      │ Web companion (JS)  │
        │ ~280 strings migrated     │      │ assets/web/i18n.js  │
        └────────────┬──────────────┘      │ ~50 strings, en/ar  │
                     │                     └─────────┬──────────┘
              attachBaseContext                      │
              applyLocale(ctx, code)                 │
                     │                       data-i18n attrs
                     ▼                               │
        ┌──────────────────────────┐                 ▼
        │ LocalePrefs (DataStore)  │       ┌──────────────────┐
        │ language code: en / ar    │      │  state.snapshot  │
        │ picked: bool             │       │   .locale        │
        └──────────────────────────┘       └──────────────────┘
                     ▲                               │
                     │                               │
              LauncherHost.setLanguage(code)─────────┘
              (writes pref + recreate())
```

**One source of truth (`LocalePrefs`), two sinks (Compose `attachBaseContext` and the web companion's `state.snapshot.locale`), driven by one host method (`setLanguage(code)` → `recreate()`).**

## Why this design

- **No `androidx.appcompat`.** ~250 KB of transitive deps for one method (`AppCompatDelegate.setApplicationLocales`) when `attachBaseContext` does the same on every API level. We work on minSdk 23.
- **`recreate()` over live-swap.** `stringResource(...)` reads from the Activity's `Resources`. Only a Configuration-wrapped Context replaces those resources. Live-switching without recreate leaves stale strings — verified empirically. Locale changes are rare; one Activity rebuild is the right tradeoff.
- **`-u-nu-latn` BCP-47 extension** (Latin digits in Arabic locale) — the device clock, date, and percentages stay readable as `3:21` / `7` / `100%` even though month/day names render Arabic.
- **Per-glyph font fallback** (`FontFamily(Font(R.font.jersey_15), Font(R.font.marhey))`) — Compose's text shaper resolves glyphs across the font list. Latin codepoints (digits, ASCII proper nouns like "OpenClaw", untranslated panel content, SSIDs) keep Jersey 15's retro pixel character; Arabic codepoints fall through to Marhey (whose weight axis is locked at 700 via `FontVariation` for a bold display look that pairs with Jersey 15).

## Files (Compose / Kotlin side)

| File | Purpose |
|---|---|
| `app/src/main/java/com/r1/launcher/locale/LocalePrefs.kt` | Singleton SharedPreferences wrapper. Holds BCP-47 tag (`en`, `ar`) + `picked` flag. Owns the `SUPPORTED` catalog (`Language(code, displayName, isRtl)`). |
| `app/src/main/java/com/r1/launcher/locale/LocaleContextWrapper.kt` | `applyLocale(base, code)` — builds a `Configuration`, calls `setLocale(...)` + `setLayoutDirection(...)`, returns `base.createConfigurationContext(config)`. Also `Locale.setDefault(...)` so non-resource consumers (`SimpleDateFormat`, `NumberFormat`) honour the locale. |
| `app/src/main/java/com/r1/launcher/LauncherActivity.kt` | Override `attachBaseContext(newBase)` → wraps with `applyLocale`. `setLanguage(code)` host method writes the pref + calls `recreate()`. `hm` / `dt` formatters built lazily with `digitFriendlyLocale()` (locks numerals to `latn`). |
| `app/src/main/java/com/r1/launcher/LauncherState.kt` | `Panel.SETTINGS_LANGUAGE` enum entry; `settingsLanguageFocus`; `openSettingsLanguage()`; `back()` unwind to `SETTINGS`. |
| `app/src/main/java/com/r1/launcher/LauncherNav.kt` | `LauncherHost.setLanguage(code)` interface method. `wheelUp/Down/activate` cases for `SETTINGS_LANGUAGE` (one row per `LocalePrefs.SUPPORTED` entry + back). Onboarding step 0 = language picker (renders each language in its own script). |
| `app/src/main/java/com/r1/launcher/ui/Theme.kt` | `latinType` (Jersey 15) and `arabicType` (Jersey 15 → Marhey @ weight 700) `R1Type` instances. `R1Theme` selects based on `Locale.getDefault().language == "ar"`. |
| `app/src/main/java/com/r1/launcher/ui/SettingsLanguagePanel.kt` | Renders `LocalePrefs.SUPPORTED` as rows; each row in its own script; checkmark on the active language; activate calls `host.setLanguage(code)`. |
| `app/src/main/java/com/r1/launcher/ui/OnboardingPanel.kt` | Inserted `LanguageStep` as step 0 (before Welcome). Shifted Welcome → step 1, Network → 2, Updates → 3, Done → 4. After locale pick + recreate, `onCreate` jumps onboarding to step 1 to avoid a loop. |
| `app/src/main/java/com/r1/launcher/ui/LauncherRoot.kt` | Wraps content in `CompositionLocalProvider(LocalLayoutDirection provides directionFor(currentLocale))` so layouts mirror in RTL automatically. Registers `SettingsLanguagePanel` in the z-stack. |
| `app/src/main/AndroidManifest.xml` | `android:supportsRtl="true"` + `configChanges="...|locale|layoutDirection"` on `LauncherActivity`. |
| `app/src/main/res/values/strings.xml` | English source — ~165 keys covering all Phase 1 + Phase 2 panel strings. |
| `app/src/main/res/values-ar/strings.xml` | Arabic translations of every key in the English source. |

## Files (web companion side)

| File | Purpose |
|---|---|
| `app/src/main/assets/web/i18n.js` | `STRINGS = { en, ar }` flat map + `t(key, ...args)` printf-style lookup + `setLocale(code)` (flips `<html dir>`, re-renders every `[data-i18n]` element) + `applyI18n(root)`. |
| `app/src/main/assets/web/index.html` | Annotated with **44** `data-i18n` attrs + 3 `data-i18n-placeholder` + 1 `data-i18n-aria-label`. Every static user-facing string opts in. App view titles use `data-title-key="view.X"` for i18n lookup. |
| `app/src/main/assets/web/app.js` | On every `state.snapshot`: `setLocale(snapshot.locale)`. Dynamic strings (conn states, hero status, charging yes/no, claude busy/thinking, sms loading, send-text hints) all routed through `t()`. |
| `app/src/main/assets/web/style.css` | `@font-face` for Marhey + extended `--display` font stack: `'Jersey15', 'Marhey', 'Noto Sans Arabic', 'Geeza Pro', 'Tahoma', ...`. `[dir="rtl"]` overrides for the asymmetric rules (`.md-quote` border-left, `.md-list` padding-left, switch knob position). Code blocks + terminal output forced LTR (paths/commands always read LTR). |
| `app/src/main/java/com/r1/launcher/web/WebRpc.kt` | `buildSnapshot(state, ctx?)` puts `locale` from `LocalePrefs` into the snapshot. |
| `app/src/main/java/com/r1/launcher/web/R1WebServer.kt` | `/i18n.js` added to asset router. All `WebRpc.buildSnapshot(state, ...)` callers thread `ctx` through. |

## Fonts

| Font | Coverage | File | Size |
|---|---|---|---|
| **Jersey 15** | Latin (digits, ASCII, common punctuation) | `res/font/jersey_15.ttf` + `assets/web/jersey_15.ttf` | 104 KB |
| **Tajawal Bold** | Arabic (static font, already cut at weight 700) | `res/font/tajawal_bold.ttf` + `assets/web/tajawal_bold.ttf` | 56 KB |

Total per-locale typography overhead: 160 KB across both fonts. We tried two Arabic fonts before settling on Tajawal:

- **Noto Sans Arabic Variable** (initial pick, 844 KB) — pulled because the size was disproportionate and the neutral system look clashed with Jersey 15's strong identity.
- **Marhey Variable** (200 KB) — pulled because the playful display character was distracting in body text. Required `FontVariation` axis locking which added an `ExperimentalTextApi` opt-in.
- **Tajawal Bold** (current, 56 KB static) — clean professional Arabic that holds up at small sizes; no FontVariation needed because the static cut IS already the bold weight.

Compose `FontFamily(Font(R.font.jersey_15), Font(R.font.tajawal_bold))` — the text shaper resolves glyphs across the family per-glyph, no special config required since both fonts are static. If you swap Tajawal for a variable font later, add `FontVariation.Settings(FontVariation.weight(NNN))` on the `Font(...)` entry and an `@OptIn(ExperimentalTextApi::class)` annotation.

## Adding a new language

Three places, no other code touched.

1. **Drop the translations**
   ```bash
   cp app/src/main/res/values/strings.xml app/src/main/res/values-fr/strings.xml
   # then translate every <string> body in values-fr/
   ```
2. **Add the language to the catalog** — one line in `LocalePrefs.kt`:
   ```kotlin
   val SUPPORTED: List<Language> = listOf(
       Language(code = "en", displayName = "English",  isRtl = false),
       Language(code = "ar", displayName = "العربية", isRtl = true),
       Language(code = "fr", displayName = "Français", isRtl = false), // ← new
   )
   ```
3. **Add the same catalog entry to the web companion** — one block in `assets/web/i18n.js`:
   ```js
   STRINGS.fr = {
       'topbar.title': 'r1 // distant',
       // ... (or partial — un-set keys fall back to English)
   };
   ```

Both Settings → Language and Onboarding step 0 auto-render the new entry. The web companion picks it up on the next `state.snapshot`.

**If the script needs a non-Latin font** (e.g. Hindi, Chinese, Korean):
- Add the `.ttf` to `res/font/<name>.ttf`.
- In `Theme.kt`, add a third `R1Type` variant + extend the `R1Theme` selector to dispatch by `Locale.getDefault().language`.
- Add the same font to `assets/web/<name>.ttf` + `@font-face` in `style.css` + extend the `--display` font stack.

## Versions / changelog

| Version | What landed |
|---|---|
| **3.32.0** | Phase 1 — infrastructure + Settings/Onboarding/Apps grid/Topbar/Network panel translated. `LocalePrefs`, `LocaleContextWrapper`, `attachBaseContext`, RTL provider, language picker UI. |
| **3.32.1** | Polish — clock/date formatters lazy-loaded so they capture the post-`attachBaseContext` Locale; clock + date wrapped in `LocalLayoutDirection.Ltr` so digits don't bidi-reorder in RTL paragraphs. |
| **3.33.0** | Phase 2 — full panel migration. ~110 additional strings across OpenClaw chat/settings/camera/sessions/qr, Messages, Terminal, Claude, Brightness/Volume, WifiShare, FactoryConfirm, SettingsVoice. |
| **3.34.0** | Phase 3 — web companion i18n. New `i18n.js`, `data-i18n` markup, `locale` field in `state.snapshot`, RTL CSS rules. |
| **3.34.1** | Marhey font swap — replaces Noto Sans Arabic Variable. Net 646 KB APK shrink. |
| **3.34.2** | Per-glyph fallback — `FontFamily(Font(jersey_15), Font(marhey))` so Latin in Arabic locale stays in Jersey 15. |
| **3.34.3** | Latin digits in Arabic — `Locale.Builder().setUnicodeLocaleKeyword("nu", "latn")`. Clock now renders `3:21` not `٣:٢١`. |
| **3.34.4** | Marhey weight locked at 700 (bold) via `FontVariation` — Arabic body text now bold-display by default; Jersey 15 untouched. |
| **3.34.5** | Distinct `ic_claude.xml` icon (4-point sparkle) — Claude Code no longer reuses OpenClaw's wifi-arc. |
| **3.34.6** | Marhey replaced with Tajawal Bold (smaller static font, more conventional readable Arabic). Dropped `FontVariation` + `ExperimentalTextApi` opt-in since the static font is already at weight 700. |
| **3.34.7** | Promoted `digitFriendlyLocale()` to a top-level helper in `com.r1.launcher.locale`. Fixed `MessagesPanel.kt`'s `SimpleDateFormat` (was eagerly initialized + missing `-u-nu-latn`) so SMS timestamps render Latin digits in Arabic locale. |
| **3.34.8** | TerminalPanel UI chrome (back pill, status indicators, paste/run/clr pills, kbd toggle, empty hint) switched from `FontFamily.Monospace` to `LocalR1Type.current.appCard.fontFamily` so Arabic translations render in Tajawal instead of system Monospace. Shell output, prompt `$`, cwd path, and input text stay Monospace. |

## Anti-patterns / things we tried that don't work

- **`androidx.appcompat` for `AppCompatDelegate.setApplicationLocales`** — pulled it out, manual `attachBaseContext` does the same with no extra dep.
- **Live-switching locale without `recreate()`** — `stringResource(...)` keeps the old strings because the Activity's `Resources` were already bound to the old Context. Recreate is the only working path.
- **`Locale("ar")` constructor** — deprecated semantics on modern Android; some properties don't propagate. Use `Locale.forLanguageTag("ar")` always.
- **Eagerly-initialized `private val SimpleDateFormat = ...`** — captures `Locale.getDefault()` at class construction, BEFORE `attachBaseContext` runs. Result: clock/date stuck in the old locale until next reinstall. Use `by lazy {}` so the resolution happens on first call.
- **`stringResource()` inside `LazyListScope.item { ... }`** at call sites where the surrounding scope is `LazyListScope` (not `@Composable`) — won't compile. Hoist the lookup *above* the `LazyColumn { ... }` block, capture in a local, reference inside the item lambda.
- **`Alignment.CenterStart` / `CenterEnd`** — these *do* mirror in RTL via `BiasAlignment` (we worried they didn't, but they do). Use them freely. Only `Alignment.AbsoluteLeft` / `AbsoluteRight` are LTR-pinned.
- **Setting `fontWeight = FontWeight.Bold` on TextStyles to bold Arabic** — would also synthetically-bold Jersey 15's fixed bitmap glyphs and smear the pixel grid. Use `FontVariation.weight(700)` on the *Font entry* so only the variable Marhey instance is affected.
- **Single-font Arabic typography** — when `R.font.marhey` was the only entry in the FontFamily, Latin chars (digits, "ooredoo", untranslated panel strings) rendered in Marhey's Latin glyphs and looked inconsistent against the rest of the UI. Always include Jersey 15 first in the FontFamily.
- **Arabic-Indic numerals (`٠-٩`) by default in Arabic locale** — looked inconsistent next to the rest of the UI (which mixes Latin everywhere). Force `latn` numerals via the `-u-nu-latn` Unicode extension. Same trick works for Persian, Urdu, etc.
- **Reusing `R.drawable.ic_wifi_arc` for Claude Code** — visual collision with OpenClaw, looked like one app duplicated. Distinct `ic_claude.xml` (4-point sparkle).
- **`FontFamily.Monospace` on translatable UI chrome** (TerminalPanel back pill, status indicators, pills) — bypasses the per-glyph fallback and renders Arabic glyphs in the platform's system mono font, which doesn't match Tajawal. Use `LocalR1Type.current.appCard.fontFamily` (or `body.fontFamily`) for any Text that holds a `stringResource(...)` value. Reserve `FontFamily.Monospace` strictly for shell output / paths / source code / user-typed shell commands. |
- **Eagerly-initialized `SimpleDateFormat` in any panel file** — same eager-init bug as the launcher clock formatters. Anywhere you see `private val xFmt = SimpleDateFormat(..., Locale.getDefault())` at file scope, wrap it in `by lazy { ... }` and pass `com.r1.launcher.locale.digitFriendlyLocale()` instead so post-`attachBaseContext` locale + Latin numerals both apply.

## Verification

Smoke-test after any locale-related change:

```bash
# Build + install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Force-stop the running launcher (am start won't replace foreground)
adb shell 'echo "kill -9 $(pidof com.r1.launcher)" | nc -q 1 127.0.0.1 1337'

# Wake + open apps
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_DPAD_CENTER

# Drive to Settings → Language and pick Arabic
# Verify:
#   - Apps grid: Arabic labels in Marhey bold, Latin in Jersey 15 retro
#   - Topbar: signal/battery on the LEFT, LTE/operator on the RIGHT (RTL flip)
#   - Clock: 3:21 (Latin digits), Arabic AM marker ص
#   - Settings → Sound → speaker: hint reads "العجلة ↑↓ اضغط OK"

# Web companion verification
adb shell 'am broadcast -a com.r1.launcher.TOGGLE_WEB_SERVER --ez on true'
curl -s http://<device-ip>:8080/api/state | python3 -c "import sys,json;print(json.load(sys.stdin).get('locale'))"
# Expect: "ar"
# Then open http://<device-ip>:8080/ in any browser — should show Arabic + RTL.
```

## Out of scope for the current state

- **Date/Number formatters in panels other than the home clock** — most are simple `Int.toString()` calls which are locale-neutral, but if any panel uses `NumberFormat` or `DecimalFormat` directly it'll need the same `digitFriendlyLocale()` trick.
- **A real RTL pass on every panel.** Most layouts auto-flip via the `LocalLayoutDirection` provider, but a few panels (e.g. Wi-Fi share clients list) may have hand-tuned padding that looks subtly off in RTL. We'll catch these as the user reports them.
- **Translations for log messages, toast strings, and exception payloads.** These are debug-facing — can stay English. If user-visible toasts need translation later, add them to `strings.xml` and route through `getString(R.string.X)`.
- **A third language** — architecture supports it; just hasn't been exercised yet. Adding French / Spanish / Hindi at any time costs the 3-step recipe above.
