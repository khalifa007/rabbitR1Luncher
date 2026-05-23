# Hermes Config Panel — retro polish + grouping

**Status:** design pending review 2026-05-23
**Scope:** `app/src/main/java/com/r1/launcher/ui/HermesConfigPanel.kt`, `ui/AppPageHeader.kt`, `ui/SettingsPanel.kt` (refactor share point only)
**Out of scope:** `HermesConnectionEditPanel`, `HermesChatPanel`, web companion mirror, any nav/state/prefs schema changes

## Goal

Bring `HermesConfigPanel` up to the launcher's retro visual language and group rows by purpose so the panel reads as one cohesive design instead of a flat dump of mixed-semantics rows.

## Problems being fixed

1. **Active marker is a Material dot.** 10dp `CircleShape` filled in amber — looks like a soft status indicator, not a pixel-art-adjacent chip. Conflicts with `MinimalSwitch` / `SegmentedLevelCard` shape grammar used everywhere else.
2. **No leading icons on connection rows.** Every other settings row in the launcher has one. Connection rows are bare text → broken row rhythm.
3. **Subtitle uses absolute positioning.** `ConnectionRow` lays the `/v1` subtitle with `padding(top = 36.dp)` instead of `Column` flow → gap feels arbitrary and breaks if label wraps.
4. **Flat list of mixed semantics.** Connection items, connection actions (+ add, qr scan), feature toggles (speak/hide), and a debug action (test) all stack with identical spacing. No visual grouping.
5. **Color identity.** Confirmed: keep `AppThemes.Hermes = #FFB300` (amber) as the per-app accent. Don't switch to the launcher's main orange — amber is the Hermes identity color, parallel to OpenClaw's teal and Settings' orange.

## Design

### Final row layout

```
< back

[hermes-icon] hermes

CONNECTIONS                                  ← section header, amber 12sp uppercase
[ic_network]  hermes.luma.om          [▣]    ← active chip filled amber
              /v1
[ic_network]  192.168.100.165         [□]    ← inactive chip, dim border
              :8642/v1
[ic_settings] + add new connection
[ic_signal]   scan config from qr

CHAT
[ic_voice]    speak replies         [ on]
[ic_messages] hide text input       [off]

DIAGNOSTIC
[ic_about]    test connection
              ok
```

### Active chip — new internal composable

Replace the 10dp `CircleShape` dot with a 14×14dp segmented chip whose visual grammar matches `MinimalSwitch`.

```kotlin
@Composable
private fun ActiveChip(active: Boolean, focused: Boolean, accent: Color) {
    // Always renders (active + inactive) so right-edge column lines up
    // vertically across rows. Same border/fill flip pattern as MinimalSwitch
    // for the focused row.
    val border = when {
        focused -> Color.Black
        active  -> accent
        else    -> Color(0xFF3A3A3A)
    }
    val fill = when {
        focused && active -> Color(0xFF1F0A00)  // dark amber tint
        focused           -> Color(0xFF111111)
        active            -> accent
        else              -> Color.Transparent
    }
    val inner = when {
        focused && active -> accent              // visible knob against dark bg
        active            -> Color.Black         // inner square reads as filled chip
        else              -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(fill)
            .border(2.dp, border, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(inner, RoundedCornerShape(1.dp)),
            )
        }
    }
}
```

Color set deliberately mirrors `MinimalSwitch` so toggles + active chip read as one family.

### Connection row — drop `ConnectionRow`, reuse `SettingsRow`

Delete the bespoke `ConnectionRow` composable. Render connection rows through the existing `SettingsRow` with:

- `leadingIcon = R.drawable.ic_network`
- `label = conn.hostLabel`
- `subtitle = conn.subtitle` (the `/v1` path part, already passed)
- `toggleChecked = null` (no toggle)
- Trailing slot: add `ActiveChip` after the `MinimalSwitch` slot. To avoid muddying `SettingsRow` with a new param, do this by **adding an optional `trailing: (@Composable () -> Unit)? = null` slot to `SettingsRow`**. Rendered after the toggle (mutually exclusive in practice — connection rows pass `trailing`, toggle rows pass `toggleChecked`).

This gives connection rows automatic icon + flowed subtitle + correct focus highlight in ~5 lines instead of 50.

### Leading icons — full mapping

Pick from existing `res/drawable/ic_*.xml`:

| Row | Icon |
|-----|------|
| connection (each) | `ic_network` |
| `+ add new connection` | `ic_settings` |
| `scan config from qr` | `ic_signal_bars` |
| `speak replies` | `ic_voice` |
| `hide text input` | `ic_messages` |
| `test connection` | `ic_about` |

Rationale: no `ic_plus`/`ic_qr`/`ic_test` exist; reuse closest semantic match. Don't add new drawables in this pass.

### Section headers

Three sections: **CONNECTIONS**, **CHAT**, **DIAGNOSTIC**.

Rendered with the same style as `AboutSectionHeader` (currently private in `SettingsPanel.kt`):

- 12sp, `FontWeight.Bold`, font family from `LocalR1Type.appCard`
- Color: theme accent (`AppThemes.Hermes` for this panel, `AppThemes.Settings` for the existing about-panel callers)
- Padding: `top = 14.dp, bottom = 2.dp, start = 12.dp, end = 12.dp`
- Text uppercase

**Refactor:** extract `AboutSectionHeader` from `SettingsPanel.kt` into `ui/AppPageHeader.kt` as a public `SectionHeader(label, themeColor)` so both panels call the same composable. Update the existing call site in `SettingsAboutPanel` to pass `themeColor = AppThemes.Settings`. No visual change for that panel.

### Focus / nav — unchanged

Section headers are **non-focusable**. The flat focus index space stays:

```
0           = back
1..N        = connections
N+1         = + add new (if canAdd)
N+1 or N+2  = scan
+1          = speak
+1          = hide
+1          = test
```

→ `LauncherNav.kt` wheelUp/wheelDown unchanged.
→ `LauncherActivity.hermesConfigRowActivate(idx)` unchanged.

Implementation: build an in-panel list of `RowKind` items in render order:

```kotlin
sealed class RowKind {
    data class Header(val label: String) : RowKind()
    data class Focusable(val focusIdx: Int, val render: @Composable () -> Unit) : RowKind()
}
```

Emit headers between the right groups. Scroll-on-focus-change becomes:

```kotlin
LaunchedEffect(state.hermesConfigFocus, rowKinds) {
    val itemIdx = rowKinds.indexOfFirst {
        it is RowKind.Focusable && it.focusIdx == state.hermesConfigFocus
    }
    if (itemIdx >= 0) listState.animateScrollToItem(itemIdx)
}
```

This is the only change in scroll-tracking logic. Today the code does `scrollToItem(hermesConfigFocus)` because list index == focus index; with headers interleaved, they diverge.

### Color tokens

| Token | Color | Where |
|---|---|---|
| Per-app accent | `AppThemes.Hermes` = `#FFB300` | Title, section headers, active chip fill, active chip border |
| Focus highlight bg | `#FF4500` | Focused row background (global, all panels) |
| Dim border | `#3A3A3A` | Inactive chip border |
| OK subtitle | `#35D26F` | "ok" on test row (existing) |
| Error subtitle | `#E53935` | "error: …" on test row (existing) |

## Files touched

- `app/src/main/java/com/r1/launcher/ui/HermesConfigPanel.kt` — full rewrite (~150 LOC; current is 215)
- `app/src/main/java/com/r1/launcher/ui/AppPageHeader.kt` — add `SectionHeader(label, themeColor)` composable
- `app/src/main/java/com/r1/launcher/ui/SettingsPanel.kt`:
  - Add optional `trailing: (@Composable () -> Unit)? = null` param to `SettingsRow`, rendered after `MinimalSwitch` slot
  - Delete private `AboutSectionHeader`, route the one call site through new shared `SectionHeader`

## Files NOT touched

- `LauncherState.kt` — no new state
- `LauncherActivity.kt` — `hermesConfigRowActivate` and all other dispatch unchanged
- `LauncherNav.kt` — wheel mapping unchanged
- `HermesPrefs.kt` / `HermesConnection.kt` — no schema change
- Every other panel
- `web/WebRpc.kt` — no new RPCs

## Testing

**Visual** (manual, on device):

- Build APK, install, open Hermes app → settings.
- Screenshot each focus position: back, conn1 (active), conn2 (inactive), +add, scan, speak, hide, test.
- Confirm right-edge active chips align vertically with `MinimalSwitch` toggles.
- Confirm leading icons render in amber when row not focused, black when focused (existing tint pattern).
- Confirm section headers render uppercase amber 12sp above each group.
- Confirm subtitle (`/v1`, `:8642/v1`) sits directly under label with `Column` flow (not floating).
- Confirm panel doesn't overflow vertically with 5 connections + 3 section headers + 4 other rows = 13 total items. The 480×480 round screen scrolls fine; just confirm scrollbar/edge feel.

**Functional**:

- Wheel up/down moves focus through 0 → conn1 → conn2 → +add → scan → speak → hide → test → and back. Section headers are skipped.
- Activate on each focusable row: back closes, connection (inactive) makes it active, connection (active) opens edit panel, +add opens edit-new, scan opens QR camera, speak/hide flip toggles, test runs `hermesTestConnection()`.
- Confirm `state.hermesConfigFocus` value matches what `hermesConfigRowActivate(idx)` expects — no off-by-one between visual position and focus index.

**Regression**:

- About panel still renders section headers identically (refactor target).
- Every existing `SettingsRow` call site still compiles (new `trailing` param has default `null`).

## Risks / unknowns

- **Trailing slot vs. toggle slot collision.** If anyone in future passes both `toggleChecked` and `trailing`, render order matters. Decision: render toggle first, then trailing. Document in `SettingsRow` KDoc with one line. Don't enforce mutual exclusion in code — keep the API simple.
- **`ic_signal_bars` for QR scan is a stretch.** Best of the existing set. Real fix: add `ic_qr.xml` later. Not in this pass.
- **`scrollToItem` translation cost.** `indexOfFirst` over ~13 items per recomposition is negligible.
