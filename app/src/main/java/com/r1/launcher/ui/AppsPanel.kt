package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.offset
import com.r1.launcher.AppEntry
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import java.util.concurrent.ConcurrentHashMap

private val iconCache = LruCache<String, ImageBitmap>(50)
private val labelCache = ConcurrentHashMap<String, String>()

private val APP_PALETTE = listOf(
    Color(0xFFFF003C), // Bright Red
    Color(0xFFFF2A9D), // Neon Pink
    Color(0xFF00E5FF), // Cyan/Light Blue
    Color(0xFF9D4EDD), // Lavender/Purple
    Color(0xFF00FF38), // Lime Green
    Color(0xFFFF6B00), // Bright Orange
    Color(0xFFFFD600), // Sunshine Yellow
    Color(0xFF2196F3), // Rabbit Blue
)

private val FOCUSED_HEIGHT = 120.dp
private val COLLAPSED_HEIGHT = 64.dp
private val CARD_SHAPE = RoundedCornerShape(12.dp)
private val CARD_SPRING = spring<androidx.compose.ui.unit.Dp>(
    dampingRatio = 0.7f,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun AppsPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onAppClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.APPS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {


            val listState = rememberLazyListState()
            LaunchedEffect(state.appsFocus, state.apps.size) {
                if (state.apps.isNotEmpty()) {
                    listState.animateScrollToItem(
                        state.appsFocus.coerceIn(0, state.apps.lastIndex),
                    )
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy((-12).dp),
                modifier = Modifier.fillMaxSize().wallpaper(),
            ) {
                itemsIndexed(
                    items = state.apps,
                    key = { _, entry ->
                        when(entry) {
                            is AppEntry.Real -> entry.info.activityInfo.packageName + "/" + entry.info.activityInfo.name
                            is AppEntry.Settings -> "settings/settings"
                            is AppEntry.OpenClaw -> "openclaw/openclaw"
                        }
                    },
                ) { idx, entry ->
                    Box(
                        modifier = Modifier
                            .zIndex(idx.toFloat())
                    ) {
                        AppCard(
                            entry = entry,
                            focused = idx == state.appsFocus,
                            background = APP_PALETTE[idx % APP_PALETTE.size],
                            onClick = { onAppClick(idx) },
                        )
                    }
                }
                
                item {
                    FolderTray(
                        modifier = Modifier
                            .zIndex((state.apps.size + 1).toFloat())
                            .fillMaxWidth()
                            .height(120.dp)
                            .offset(y = (-75).dp)
                    )
                }
            }
        }
    }

@Composable
private fun AppCard(
    entry: AppEntry,
    focused: Boolean,
    background: Color,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val pkg = when (entry) {
        is AppEntry.Real -> entry.info.activityInfo.packageName
        AppEntry.Settings -> "_r1_settings"
        AppEntry.OpenClaw -> "_r1_openclaw"
    }
    val isSettings = entry is AppEntry.Settings
    val isOpenClaw = entry is AppEntry.OpenClaw
    val settingsPainter = if (isSettings) painterResource(R.drawable.ic_settings) else null
    val openClawPainter = if (isOpenClaw) painterResource(R.drawable.ic_wifi_arc) else null

    var iconPainter by remember(pkg) {
        val cached = iconCache.get(pkg)
        mutableStateOf<Painter?>(if (cached != null) BitmapPainter(cached) else null)
    }
    var label by remember(pkg) {
        mutableStateOf(
            when (entry) {
                is AppEntry.Real -> labelCache[pkg] ?: pkg.substringAfterLast('.').lowercase()
                AppEntry.Settings -> "settings"
                AppEntry.OpenClaw -> "openclaw"
            }
        )
    }

    if (entry is AppEntry.Real) {
        val info = entry.info
        LaunchedEffect(pkg) {
            if (iconCache.get(pkg) == null) {
                val (loadedLabel, bitmap) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val l = info.loadLabel(pm).toString().lowercase()
                    val d = info.loadIcon(pm)
                    Pair(l, d.toBitmap(width = 144, height = 144).asImageBitmap())
                }
                iconCache.put(pkg, bitmap)
                labelCache[pkg] = loadedLabel
                label = loadedLabel
                iconPainter = BitmapPainter(bitmap)
            }
        }
    }

    val height by animateDpAsState(
        if (focused) FOCUSED_HEIGHT else COLLAPSED_HEIGHT,
        animationSpec = CARD_SPRING,
        label = "cardHeight",
    )

    var shakeTrigger by remember { mutableStateOf(0) }
    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            val shakeDist = 15f
            offsetX.animateTo(shakeDist, animationSpec = tween(40, easing = LinearEasing))
            offsetX.animateTo(-shakeDist, animationSpec = tween(80, easing = LinearEasing))
            offsetX.animateTo(shakeDist, animationSpec = tween(80, easing = LinearEasing))
            offsetX.animateTo(-shakeDist, animationSpec = tween(80, easing = LinearEasing))
            offsetX.animateTo(0f, animationSpec = tween(40, easing = LinearEasing))
            onClick()
        }
    }

    val outer = Modifier
        .fillMaxWidth()
        .height(height)
        .graphicsLayer {
            translationX = offsetX.value
            rotationZ = offsetX.value / 6f
        }
        .clip(CARD_SHAPE)
        .background(background)
        .border(2.dp, Color.Black, CARD_SHAPE)
        .clickable {
            if (shakeTrigger == 0 || !offsetX.isRunning) {
                shakeTrigger++
            }
        }

    Box(modifier = outer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            val effectivePainter = settingsPainter ?: openClawPainter ?: iconPainter
            if (effectivePainter != null) {
                Image(
                    painter = effectivePainter,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Box(modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = label,
                color = Color.Black,
                style = LocalR1Type.current.appCard,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

class FolderShape : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val path = androidx.compose.ui.graphics.Path().apply {
            val width = size.width
            val height = size.height
            val radius = 12f * density.density
            
            moveTo(0f, height * 0.3f)
            cubicTo(
                width * 0.3f, height * 0.3f,
                width * 0.6f, height * 0.6f,
                width, height * 0.6f
            )
            lineTo(width, height - radius)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(width - radius * 2, height - radius * 2, width, height),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            lineTo(radius, height)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(0f, height - radius * 2, radius * 2, height),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            lineTo(0f, height * 0.3f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

@Composable
fun FolderTray(modifier: Modifier = Modifier) {
    val folderShape = remember { FolderShape() }
    Box(
        modifier = modifier
            .clip(folderShape)
            .background(Color(0xFFF57C00))
            .border(2.dp, Color.Black, folderShape)
    )
}
