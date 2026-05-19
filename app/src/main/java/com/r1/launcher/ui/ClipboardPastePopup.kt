package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small overlay that appears on long-press of a chat input field. Shows a
 * single "paste" row with a preview of the current Android system clipboard;
 * tap inserts the text into the field. Tap-outside dismisses. Matches the
 * styling of HermesDropdownMenu / ChatDropdownMenu so the visual language
 * stays consistent across the launcher.
 *
 * Anchored bottom-center (above the input row that triggered it) rather than
 * top-end because the input rows live near the bottom of each chat panel.
 */
@Composable
fun ClipboardPastePopup(
    visible: Boolean,
    themeColor: Color,
    clipboardText: String,
    onPaste: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInVertically(tween(150)) { it / 2 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 2 },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Sits ~88dp from the bottom edge to clear the input row
                // (input row height ~50dp + 8dp margin + breathing room).
                .padding(bottom = 88.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, themeColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp),
            ) {
                ClipboardPasteRow(
                    themeColor = themeColor,
                    clipboardText = clipboardText,
                    onPaste = onPaste,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/**
 * Long-press a chat message → this popup. Two rows: "copy" (whole message
 * via copyToClipboard, same path as code-block copy) and "select text"
 * (flips the bubble into SelectionContainer mode so the next long-press
 * shows Android's native partial-selection handles + Copy/Select-all
 * toolbar). Same scrim + bottom-center anchor as ClipboardPastePopup.
 */
@Composable
fun MessageActionPopup(
    visible: Boolean,
    themeColor: Color,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInVertically(tween(150)) { it / 2 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 2 },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .width(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, themeColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp),
            ) {
                ActionRow(label = "copy", themeColor = themeColor, onClick = onCopy)
                ActionRow(label = "select text", themeColor = themeColor, onClick = onSelectText)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, themeColor: Color, onClick: () -> Unit) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 15.sp),
            color = themeColor,
        )
    }
}

@Composable
private fun ClipboardPasteRow(
    themeColor: Color,
    clipboardText: String,
    onPaste: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val type = LocalR1Type.current
    val hasText = clipboardText.isNotEmpty()
    val preview = if (clipboardText.length > 50) clipboardText.take(50) + "…"
        else clipboardText

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (hasText) onPaste(clipboardText) else onDismiss()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column {
            Text(
                text = if (hasText) "paste" else "clipboard empty",
                style = type.appCard.copy(fontSize = 15.sp),
                color = if (hasText) themeColor else Color(0xFF888888),
            )
            if (hasText) {
                Text(
                    text = preview.replace('\n', ' '),
                    style = type.appCard.copy(fontSize = 12.sp),
                    color = Color(0xFF888888),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

