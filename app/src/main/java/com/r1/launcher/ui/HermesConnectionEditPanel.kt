package com.r1.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

/**
 * Add-or-edit a single Hermes connection.
 *
 * In edit-mode (state.hermesConnectionEditId != null) shows all 5 rows:
 *   0  < back
 *   1  server url
 *   2  api key
 *   3  rotate session
 *   4  delete connection (two-step confirm via state.hermesConnectionEditDeleteArmedAt)
 *
 * In new-mode (id = null) row 0 reads "cancel" and rotate/delete are hidden.
 * Saving any URL field in new-mode triggers host.hermesAddConnection(...).
 */
@Composable
fun HermesConnectionEditPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onSaveUrl: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onPasteUrl: () -> Unit,
    onPasteKey: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.HERMES_CONNECTION_EDIT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Hermes
        val warn = Color(0xFFE53935)
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)

        val isNew = state.hermesConnectionEditId == null
        val existing = state.hermesConnectionEditId?.let { editId ->
            state.hermesConnections.firstOrNull { it.id == editId }
        }

        val rows = buildList {
            add(if (isNew) "cancel" else "< back")
            add("server url")
            add("api key")
            if (!isNew) add("rotate session")
            if (!isNew) add("delete connection")
        }

        var editField by remember { mutableStateOf("") }  // "" | "url" | "key"
        val nowArmed = state.hermesConnectionEditDeleteArmedAt > 0L &&
            SystemClock.uptimeMillis() - state.hermesConnectionEditDeleteArmedAt < DELETE_ARM_MS

        val listState = rememberLazyListState()
        LaunchedEffect(state.hermesConnectionEditFocus, rows.size) {
            listState.animateScrollToItem(state.hermesConnectionEditFocus.coerceIn(0, rows.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(rows) { idx, label ->
                    val focused = state.hermesConnectionEditFocus == idx
                    val isDelete = !isNew && idx == 4
                    val subtitle = when (idx) {
                        1 -> if (isNew) state.hermesConnectionEditUrlInput.ifBlank { "(empty)" }
                             else existing?.url.orEmpty().ifBlank { "(empty)" }
                        2 -> if (state.hermesConnectionEditKeyInput.isNotEmpty())
                                 "•".repeat(state.hermesConnectionEditKeyInput.length.coerceAtMost(20))
                             else existing?.keyTail.orEmpty().ifBlank { "(none)" }
                        else -> ""
                    }
                    val labelColor = when {
                        isDelete && nowArmed -> warn
                        isDelete -> Color(0xFFB04040)
                        else -> Color.White
                    }
                    val displayLabel = if (isDelete && nowArmed) "tap again to confirm" else label
                    SettingsRow(
                        label = displayLabel,
                        focused = focused,
                        subtitle = subtitle,
                        subtitleColor = if (idx in 1..2) ok else dim,
                        toggleChecked = null,
                        onClick = {
                            when (idx) {
                                1 -> { editField = "url" }
                                2 -> { editField = "key" }
                                else -> onRowClick(idx)
                            }
                        },
                        labelColor = labelColor,
                    )
                }
            }

            AnimatedVisibility(
                visible = editField.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val isKey = editField == "key"
                val displayInput = if (isKey) state.hermesConnectionEditKeyInput else state.hermesConnectionEditUrlInput
                val maskedInput = if (isKey && displayInput.isNotEmpty())
                    "•".repeat(displayInput.length.coerceAtMost(40)) else displayInput

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isKey) "api key" else "server url",
                        color = accent,
                        fontSize = 16.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF101010))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (displayInput.isEmpty()) "(empty)" else "$maskedInput _",
                            color = if (displayInput.isEmpty()) Color(0xFF707070) else Color.White,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EditPill("save", ok, displayInput.isNotBlank(), Modifier.weight(1f)) {
                            if (isKey) onSaveKey(state.hermesConnectionEditKeyInput)
                            else onSaveUrl(state.hermesConnectionEditUrlInput)
                            editField = ""
                        }
                        EditPill("paste", accent, true, Modifier.weight(1f)) {
                            if (isKey) onPasteKey() else onPasteUrl()
                        }
                        EditPill("clear", warn, displayInput.isNotEmpty(), Modifier.weight(1f)) {
                            if (isKey) state.hermesConnectionEditKeyInput = ""
                            else state.hermesConnectionEditUrlInput = ""
                        }
                        EditPill("close", Color.White, true, Modifier.weight(1f)) {
                            editField = ""
                        }
                    }
                    RetroKeyboard(
                        onKeyPress = { ch ->
                            if (isKey) state.hermesConnectionEditKeyInput += ch
                            else state.hermesConnectionEditUrlInput += ch
                        },
                        onBackspace = {
                            if (isKey) {
                                if (state.hermesConnectionEditKeyInput.isNotEmpty())
                                    state.hermesConnectionEditKeyInput = state.hermesConnectionEditKeyInput.dropLast(1)
                            } else {
                                if (state.hermesConnectionEditUrlInput.isNotEmpty())
                                    state.hermesConnectionEditUrlInput = state.hermesConnectionEditUrlInput.dropLast(1)
                            }
                        },
                        onDismiss = { editField = "" },
                    )
                }
            }
        }
    }
}

const val DELETE_ARM_MS = 3000L

@Composable
private fun EditPill(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val borderColor = if (enabled) color else Color(0xFF333333)
    val textColor = if (enabled) color else Color(0xFF555555)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontFamily = type.appCard.fontFamily,
        )
    }
}
