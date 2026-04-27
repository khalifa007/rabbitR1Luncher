package com.r1.launcher.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.openclaw.ChatMessage

@Composable
fun OpenClawCanvasPanel(
    state: LauncherState,
    onBack: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.OPENCLAW_CANVAS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val listState = rememberLazyListState()
        var lastTick by remember { mutableStateOf(state.canvasScrollIndex) }
        val selected = remember(
            state.chatStreamingText,
            state.chatMessages.size,
            state.chatMessages.lastOrNull()?.id,
        ) {
            resolveCanvasMessage(state)
        }

        LaunchedEffect(state.canvasScrollIndex, selected?.id) {
            if (state.canvasScrollIndex == 0 && lastTick != 0) {
                runCatching { listState.animateScrollToItem(0) }
                lastTick = 0
            } else if (state.canvasScrollIndex != lastTick) {
                val diff = state.canvasScrollIndex - lastTick
                lastTick = state.canvasScrollIndex
                runCatching { listState.animateScrollBy(diff.toFloat() * 260f) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BackPill(label = "chat", onClick = onBack)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "canvas",
                    style = type.appCard.copy(fontSize = 22.sp),
                    color = Color(0xFFFF4500),
                )
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                item {
                    CanvasSurface(message = selected)
                }
            }
        }
    }
}

private fun resolveCanvasMessage(state: LauncherState): ChatMessage? {
    if (state.chatStreamingText.isNotBlank()) {
        return ChatMessage(role = "assistant", text = state.chatStreamingText, streaming = true)
    }
    return state.chatMessages.lastOrNull {
        it.role == "assistant" && (it.text.isNotBlank() || it.imageBase64 != null || it.hasImage)
    } ?: state.chatMessages.lastOrNull {
        it.text.isNotBlank() || it.imageBase64 != null || it.hasImage
    }
}

@Composable
private fun CanvasSurface(message: ChatMessage?) {
    val type = LocalR1Type.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF18191C))
            .border(2.dp, Color(0xFFFF4500), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (message == null) {
            EmptyCanvas()
            return@Column
        }

        CanvasMeta(message)
        when {
            message.imageBase64 != null -> CanvasImage(message.imageBase64)
            message.hasImage -> CanvasImagePlaceholder()
        }
        val text = message.text.trim()
        if (text.isNotEmpty()) {
            CanvasMarkdown(text)
        } else if (!message.hasImage && message.imageBase64 == null) {
            Text(
                text = "nothing to preview yet",
                style = type.appCard.copy(fontSize = 24.sp, textAlign = TextAlign.Center),
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CanvasMeta(message: ChatMessage) {
    val type = LocalR1Type.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (message.role == "assistant") "claw" else "you",
            style = type.appCard.copy(fontSize = 18.sp),
            color = Color(0xFFFF4500),
        )
        Spacer(Modifier.weight(1f))
        if (message.streaming) {
            Text(
                text = "live",
                style = type.appCard.copy(fontSize = 16.sp),
                color = Color(0xFF35D26F),
            )
        }
    }
}

@Composable
private fun CanvasMarkdown(text: String) {
    val type = LocalR1Type.current
    val body = type.appCard.copy(fontSize = 22.sp, lineHeight = 25.sp)
    Markdown(
        content = cleanCanvasMarkdown(text),
        colors = markdownColor(
            text = Color.White,
            codeText = Color(0xFFFFC107),
            codeBackground = Color(0xFF0D0D0E),
        ),
        typography = markdownTypography(
            text = body,
            paragraph = body,
            code = body.copy(fontSize = 18.sp),
            quote = body,
            list = body,
            ordered = body,
            bullet = body,
            h1 = body.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4500)),
            h2 = body.copy(fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4500)),
            h3 = body.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107)),
            h4 = body.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            h5 = body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            h6 = body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        ),
    )
}

private fun cleanCanvasMarkdown(text: String): String {
    return text
        .replace(Regex("```[\\s\\S]*?```")) { m ->
            val inner = m.value.removeSurrounding("```")
            val nl = inner.indexOf('\n')
            if (nl > 0 && inner.substring(0, nl).all {
                    it.isLetterOrDigit() || it == '+' || it == '-' || it == '_'
                }) inner.substring(nl + 1).trim('\n')
            else inner.trim('\n')
        }
        .trim()
}

@Composable
private fun CanvasImage(imageBase64: String) {
    val bitmap = remember(imageBase64) {
        runCatching {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) {
        CanvasImagePlaceholder()
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, Color.Black, RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun CanvasImagePlaceholder() {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF101113))
            .border(2.dp, Color(0xFFFF4500), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "attached image",
            style = type.appCard.copy(fontSize = 22.sp),
            color = Color(0xFFFF4500),
        )
    }
}

@Composable
private fun EmptyCanvas() {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "ask something,\nthen open canvas",
            style = type.appCard.copy(fontSize = 26.sp, textAlign = TextAlign.Center),
            color = Color(0xFFFF4500),
        )
    }
}
