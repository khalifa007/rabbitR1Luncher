package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.translator.Language
import com.r1.launcher.translator.Languages
import com.r1.launcher.translator.TranslationMessage

/**
 * Translator chat surface. Voice-first, but text input works too.
 *
 * Layout (top → bottom):
 *   1. floating page header (back pill + status icon + menu dot)
 *   2. language pair row — source chip + swap arrow + target chip
 *      (tap chip = cycle, long-press = open picker, tap arrow = swap)
 *   3. transcript: each TranslationMessage renders as source bubble (left,
 *      gray) followed by target bubble (right, teal). Tap target = replay TTS.
 *      Tap source = copy source text.
 *   4. input row + send / mic / keyboard overlay (same idiom as Hermes)
 *
 * Wheel up/down cycles target language with a brief toast on each change.
 * Side-button hold → STT → auto-translate → auto-speak (if enabled).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranslatorPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onCycleSource: (Int) -> Unit,
    onCycleTarget: (Int) -> Unit,
    onPickSource: (String) -> Unit,
    onPickTarget: (String) -> Unit,
    onSwapLangs: () -> Unit,
    onReplay: (String) -> Unit,
    onCopySource: (String) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    getClipboardText: () -> String,
    onMicStart: () -> Unit,
    onMicStop: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.TRANSLATOR,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        var menuOpen by remember { mutableStateOf(false) }
        var showKeyboard by remember { mutableStateOf(false) }
        var showPaste by remember { mutableStateOf(false) }
        var pasteText by remember { mutableStateOf("") }
        // null = picker closed. "source" / "target" = which chip is being edited.
        var pickerTarget by remember { mutableStateOf<String?>(null) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize()) {

                Spacer(Modifier.height(46.dp)) // room under the floating header

                // Compact one-line language strip — frees the rest of the
                // screen for the words. Tap a code to cycle, the ⇄ to swap,
                // long-press a code to open the full picker.
                CompactLangStrip(
                    sourceCode = state.translatorSource,
                    targetCode = state.translatorTarget,
                    onCycleSource = onCycleSource,
                    onCycleTarget = onCycleTarget,
                    onPickerSource = { pickerTarget = "source" },
                    onPickerTarget = { pickerTarget = "target" },
                    onSwap = onSwapLangs,
                )

                val listState = rememberLazyListState()
                val messages = state.translatorMessages
                var lastTick by remember { mutableStateOf(state.translatorScrollIndex) }
                var lastSize by remember { mutableStateOf(messages.size) }
                LaunchedEffect(state.translatorScrollIndex, messages.size) {
                    val sizeGrew = messages.size > lastSize
                    lastSize = messages.size
                    if (state.translatorScrollIndex == 0 && (lastTick != 0 || sizeGrew)) {
                        runCatching { listState.animateScrollToItem(0) }
                        lastTick = 0
                    } else if (state.translatorScrollIndex != lastTick) {
                        val diff = state.translatorScrollIndex - lastTick
                        lastTick = state.translatorScrollIndex
                        runCatching { listState.animateScrollBy(diff.toFloat() * 300f) }
                    }
                }

                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (state.translatorPartialText.isNotBlank()) {
                        item("partial") {
                            ListeningCard(
                                partial = state.translatorPartialText,
                                sourceLang = state.translatorSource,
                            )
                        }
                    }
                    if (messages.isEmpty() && state.translatorPartialText.isBlank()) {
                        item {
                            TranslatorEmptyHint(state.translatorStatus)
                        }
                    } else {
                        // Render newest first: reverseLayout=true puts the
                        // logical first item at the bottom, so the reversed
                        // list lands the newest result just above the input.
                        // Each entry is ONE stacked card (source → result),
                        // not a pair of chat bubbles.
                        val reversed = messages.asReversed()
                        itemsIndexed(items = reversed, key = { _, m -> m.id }) { idx, msg ->
                            TranslationCard(
                                msg = msg,
                                // Highlight the most recent result so the eye
                                // lands on it — that's the one you show people.
                                isLatest = idx == 0 && state.translatorPartialText.isBlank(),
                                onReplay = {
                                    if (!msg.pending && msg.error == null && msg.targetText.isNotBlank()) {
                                        onReplay(msg.id)
                                    }
                                },
                                onCopySource = { onCopySource(msg.sourceText) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, AppThemes.Translator, RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .combinedClickable(
                            onClick = { showKeyboard = !showKeyboard },
                            onLongClick = {
                                pasteText = getClipboardText()
                                showPaste = true
                            },
                        )
                        .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.translatorInputText.isEmpty()) "type or hold side button…"
                            else state.translatorInputText + if (showKeyboard) "_" else "",
                        style = type.appCard,
                        color = if (state.translatorInputText.isEmpty()) Color.Gray else Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    HoldToTalkPill(
                        recording = state.translatorRecording,
                        themeColor = AppThemes.Translator,
                        onStart = onMicStart,
                        onStop = onMicStop,
                        size = 32.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                    SendIconButton(
                        tint = AppThemes.Translator,
                        enabled = state.translatorInputText.isNotBlank(),
                        onClick = {
                            if (state.translatorInputText.isNotBlank()) {
                                onSend(state.translatorInputText)
                                state.translatorInputText = ""
                                showKeyboard = false
                            }
                        },
                    )
                }

                AnimatedVisibility(
                    visible = showKeyboard,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    RetroKeyboard(
                        onKeyPress = { ch -> state.translatorInputText += ch },
                        onBackspace = {
                            if (state.translatorInputText.isNotEmpty()) {
                                state.translatorInputText = state.translatorInputText.dropLast(1)
                            }
                        },
                        onDismiss = { showKeyboard = false },
                    )
                }
            }

            AppPageHeader(
                backFocused = false,
                onBack = onBack,
                themeColor = AppThemes.Translator,
                compact = true,
                floating = true,
                modifier = Modifier.align(Alignment.TopCenter),
                trailingContent = {
                    // No persistent status indicator — a translator is a
                    // stateless utility, not a chat with a live connection.
                    // Activity feedback lives inline instead: the mic pill
                    // pulses while recording, the latest card shows
                    // "translating…" while busy, and the ♪ glyph marks
                    // speakable results.
                    MenuDot(
                        themeColor = AppThemes.Translator,
                        focused = menuOpen,
                        onClick = { menuOpen = !menuOpen },
                    )
                },
            )

            TranslatorDropdownMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onSwap = { menuOpen = false; onSwapLangs() },
                onClear = { menuOpen = false; onClear() },
                onSettings = { menuOpen = false; onOpenSettings() },
            )

            ClipboardPastePopup(
                visible = showPaste,
                themeColor = AppThemes.Translator,
                clipboardText = pasteText,
                onPaste = { text ->
                    state.translatorInputText = if (state.translatorInputText.isBlank()) text
                        else state.translatorInputText.trimEnd() + " " + text
                    showPaste = false
                },
                onDismiss = { showPaste = false },
                onClear = if (state.translatorInputText.isNotEmpty()) {
                    { state.translatorInputText = ""; showPaste = false }
                } else null,
            )

            LanguagePickerOverlay(
                visible = pickerTarget != null,
                currentCode = if (pickerTarget == "source") state.translatorSource else state.translatorTarget,
                onPick = { code ->
                    if (pickerTarget == "source") onPickSource(code) else onPickTarget(code)
                    pickerTarget = null
                },
                onDismiss = { pickerTarget = null },
            )
        }
    }
}

/**
 * Compact one-line language strip: `EN  ⇄  AR`. Replaced the tall two-chip row
 * so the translation itself gets the screen. Each code is tappable (cycle) and
 * long-pressable (open picker); the centre ⇄ swaps source↔target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactLangStrip(
    sourceCode: String,
    targetCode: String,
    onCycleSource: (Int) -> Unit,
    onCycleTarget: (Int) -> Unit,
    onPickerSource: () -> Unit,
    onPickerTarget: () -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        LangCode(sourceCode, onTap = { onCycleSource(1) }, onLongPress = onPickerSource)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(AppThemes.Translator)
                .clickable(onClick = onSwap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⇄",
                color = Color.Black,
                fontSize = 16.sp,
                fontFamily = LocalR1Type.current.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        LangCode(targetCode, onTap = { onCycleTarget(1) }, onLongPress = onPickerTarget)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LangCode(code: String, onTap: () -> Unit, onLongPress: () -> Unit) {
    val type = LocalR1Type.current
    val lang = Languages.get(code)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, AppThemes.Translator.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = lang.code.uppercase(),
            color = AppThemes.Translator,
            fontSize = 18.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            // "auto-detect" is too long for the cramped strip — show "detect".
            text = if (Languages.isAuto(code)) "detect" else lang.english,
            color = Color(0xFF999999),
            fontSize = 12.sp,
            fontFamily = type.appCard.fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One translation entry, rendered as a vertical stack rather than chat bubbles:
 *
 *   [EN] where is the bathroom          ← source: small, dim, tap to copy
 *   ────────────────────────────
 *   [AR] أين الحمام؟                ♪   ← result: large, bright, tap to replay
 *
 * The result is the payload — it's what you read and show the other person —
 * so it gets the big bright treatment; the source is reduced to context. The
 * newest entry ([isLatest]) gets a faint tinted background so the eye lands on
 * the most recent result.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranslationCard(
    msg: TranslationMessage,
    isLatest: Boolean,
    onReplay: () -> Unit,
    onCopySource: () -> Unit,
) {
    if (isLatest) {
        TranslationHero(msg = msg, onReplay = onReplay, onCopySource = onCopySource)
    } else {
        TranslationHistoryRow(msg = msg, onReplay = onReplay, onCopySource = onCopySource)
    }
}

/**
 * The newest result — the focus of the screen. Source dim above, translation
 * BIG and centred below, a ♪ hint underneath. The whole result is tappable to
 * replay TTS.
 */
@Composable
private fun TranslationHero(
    msg: TranslationMessage,
    onReplay: () -> Unit,
    onCopySource: () -> Unit,
) {
    val type = LocalR1Type.current
    val tgtLang = Languages.get(msg.targetLang)
    val resultColor = when {
        msg.error != null -> Color(0xFFFF6B6B)
        msg.pending -> Color(0xFF888888)
        else -> AppThemes.Translator
    }
    val speakable = !msg.pending && msg.error == null && msg.targetText.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppThemes.Translator.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Source — context, dim, tap to copy.
        Text(
            text = msg.sourceText,
            style = type.appCard.copy(fontSize = 15.sp, textAlign = TextAlign.Center),
            color = Color(0xFF8C8C8C),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onCopySource),
        )
        // Result — the hero. Big, centred, themed.
        Text(
            text = when {
                msg.error != null -> "couldn't translate\n${msg.error}"
                msg.pending -> "translating…"
                else -> msg.targetText
            },
            style = type.appCard.copy(
                fontSize = if (msg.error != null) 15.sp else 30.sp,
                textAlign = TextAlign.Center,
            ),
            color = resultColor,
            modifier = Modifier.fillMaxWidth().clickable(enabled = speakable, onClick = onReplay),
        )
        if (speakable) {
            Text(
                text = "♪ tap to replay",
                style = type.appCard.copy(fontSize = 12.sp),
                color = AppThemes.Translator.copy(alpha = 0.6f),
                modifier = Modifier.clickable(onClick = onReplay),
            )
        }
    }
}

/** An older translation — compact single stack so history stays scannable but
 *  doesn't compete with the hero. */
@Composable
private fun TranslationHistoryRow(
    msg: TranslationMessage,
    onReplay: () -> Unit,
    onCopySource: () -> Unit,
) {
    val type = LocalR1Type.current
    val srcLang = Languages.get(msg.sourceLang)
    val tgtLang = Languages.get(msg.targetLang)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = msg.sourceText,
            style = type.appCard.copy(fontSize = 12.sp),
            color = Color(0xFF6E6E6E),
            textAlign = if (srcLang.rtl) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onCopySource),
        )
        Text(
            text = if (msg.error != null) "(failed)" else msg.targetText,
            style = type.appCard.copy(fontSize = 16.sp),
            color = if (msg.error != null) Color(0xFFFF6B6B) else AppThemes.Translator.copy(alpha = 0.85f),
            textAlign = if (tgtLang.rtl) TextAlign.End else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = msg.error == null && msg.targetText.isNotBlank(),
                    onClick = onReplay,
                ),
        )
    }
}

/** Live STT preview shown while the mic is open. Centred + large so the words
 *  you're speaking are the focus; the actual translation replaces it on commit. */
@Composable
private fun ListeningCard(partial: String, sourceLang: String) {
    val type = LocalR1Type.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF161616))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "listening…",
            style = type.appCard.copy(fontSize = 12.sp),
            color = AppThemes.Translator.copy(alpha = 0.7f),
        )
        Text(
            text = partial.ifBlank { "…" },
            style = type.appCard.copy(fontSize = 22.sp, textAlign = TextAlign.Center),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TranslatorEmptyHint(status: String) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        val text = when {
            status.startsWith("error") -> status
            else -> "hold the side button to speak\nor tap below to type"
        }
        Text(
            text = text,
            style = type.appCard.copy(textAlign = TextAlign.Center, fontSize = 14.sp),
            color = if (status.startsWith("error")) Color(0xFFFF6B6B) else AppThemes.Translator.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun TranslatorDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSwap: () -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
) {
    if (expanded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(120)) + slideInVertically(tween(150)) { -it / 2 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { -it / 2 },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 44.dp, end = 12.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Column(
                modifier = Modifier
                    .width(160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, AppThemes.Translator.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
            ) {
                TranslatorMenuItem(label = "swap langs", onClick = onSwap)
                TranslatorMenuItem(label = "clear", onClick = onClear)
                TranslatorMenuItem(label = "settings", onClick = onSettings)
            }
        }
    }
}

@Composable
private fun TranslatorMenuItem(label: String, onClick: () -> Unit) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 15.sp),
            color = AppThemes.Translator,
        )
    }
}

/**
 * Full-screen scrollable language picker. Tapping a row commits the choice
 * and closes the overlay. Tapping outside the list dismisses without change.
 */
@Composable
private fun LanguagePickerOverlay(
    visible: Boolean,
    currentCode: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(100)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF111111))
                    .border(1.dp, AppThemes.Translator, RoundedCornerShape(12.dp))
                    .clickable(enabled = false, onClick = {}) // swallow inner taps
                    .padding(vertical = 8.dp),
            ) {
                val type = LocalR1Type.current
                Text(
                    text = "pick language",
                    color = AppThemes.Translator,
                    fontSize = 14.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(
                    modifier = Modifier.height(280.dp),
                ) {
                    items(items = Languages.ALL, key = { it.code }) { lang ->
                        LanguagePickerRow(
                            lang = lang,
                            selected = lang.code == currentCode,
                            onClick = { onPick(lang.code) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerRow(lang: Language, selected: Boolean, onClick: () -> Unit) {
    val type = LocalR1Type.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AppThemes.Translator.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "[${lang.code.uppercase()}]",
            color = AppThemes.Translator,
            fontSize = 13.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lang.english,
                color = Color.White,
                fontSize = 15.sp,
                fontFamily = type.appCard.fontFamily,
            )
            if (lang.english != lang.endonym) {
                Text(
                    text = lang.endonym,
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    fontFamily = type.appCard.fontFamily,
                )
            }
        }
        if (selected) {
            Text(
                text = "•",
                color = AppThemes.Translator,
                fontSize = 22.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
