package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.r1.launcher.AppStore
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import java.util.Locale

/**
 * Store panel — same shell as AppsPanel (back pill + title + scrollable list),
 * but rows are catalog entries with icon + name + status chip (Install / Update / Installed).
 *
 * Icon loading goes through AppStore.loadIcon which caches per-slug and calls
 * back on the main thread. We wrap that with produceState so Compose re-renders
 * when a slow-loading icon arrives.
 */
@Composable
fun StorePanel(
    state: LauncherState,
    appStore: AppStore,
    onBack: () -> Unit,
    onRowClick: (AppStore.Entry) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.STORE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it / 8 },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it / 16 },
    ) {
        val colors = LocalR1Colors.current
        val type = LocalR1Type.current

        Column(modifier = Modifier.fillMaxSize().wallpaper()) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 10.dp, bottom = 6.dp),
            ) {
                BackPill(label = stringResource(R.string.home_back), onClick = onBack)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.store_title),
                        style = type.panelTitle,
                        color = colors.labelBright,
                    )
                }
                Spacer(Modifier.width(54.dp))
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.storeLoadError != null -> {
                        Text(
                            stringResource(R.string.store_error) + ": " + state.storeLoadError,
                            style = type.small,
                            color = colors.muted,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    state.storeLoading && state.storeEntries.isEmpty() -> {
                        Text(
                            "Loading…",
                            style = type.small,
                            color = colors.muted,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    state.storeEntries.isEmpty() -> {
                        Text(
                            stringResource(R.string.store_empty),
                            style = type.small,
                            color = colors.muted,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    else -> StoreList(state, appStore, onRowClick)
                }
            }
        }
    }
}

@Composable
private fun StoreList(
    state: LauncherState,
    appStore: AppStore,
    onRowClick: (AppStore.Entry) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.storeFocus, state.storeEntries.size) {
        if (state.storeEntries.isNotEmpty()) {
            listState.animateScrollToItem(
                state.storeFocus.coerceIn(0, state.storeEntries.lastIndex),
            )
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = state.storeEntries,
            key = { _, e -> e.slug },
        ) { idx, entry ->
            StoreRow(
                entry = entry,
                focused = idx == state.storeFocus,
                downloadingSlug = state.downloadingSlug,
                downloadingPct = state.downloadingPct,
                appStore = appStore,
                onClick = { onRowClick(entry) },
            )
        }
    }
}

@Composable
private fun StoreRow(
    entry: AppStore.Entry,
    focused: Boolean,
    downloadingSlug: String?,
    downloadingPct: Int,
    appStore: AppStore,
    onClick: () -> Unit,
) {
    val colors = LocalR1Colors.current
    val type = LocalR1Type.current
    TileRow(
        focused = focused,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.tile, RoundedCornerShape(10.dp)),
    ) {
        StoreIcon(entry = entry, appStore = appStore)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = type.body,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.tagline.isNotEmpty()) {
                Text(
                    entry.tagline,
                    style = type.statusChip,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusTextFor(entry, downloadingSlug, downloadingPct, appStore),
            style = type.statusChip,
            color = colors.accent,
        )
    }
}

@Composable
private fun StoreIcon(entry: AppStore.Entry, appStore: AppStore) {
    val painter: Painter? by produceState<Painter?>(initialValue = null, entry.slug) {
        appStore.loadIcon(entry) { slug, drawable ->
            if (slug == entry.slug && drawable != null) {
                value = BitmapPainter(
                    drawable.toBitmap(width = 96, height = 96).asImageBitmap(),
                )
            }
        }
    }
    val colors = LocalR1Colors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .background(Color(0xFF222224), RoundedCornerShape(7.dp)),
    ) {
        if (painter != null) {
            Image(
                painter = painter!!,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        } else {
            // Fallback glyph while icon fetches.
            Image(
                painter = painterResource(R.drawable.ic_store),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun statusTextFor(
    e: AppStore.Entry,
    downloadingSlug: String?,
    downloadingPct: Int,
    appStore: AppStore,
): String {
    if (downloadingSlug == e.slug) return "$downloadingPct%"
    val local = appStore.installedVersionCode(e.pkg)
    return when {
        local == 0 -> "Install · " + humanSize(e.sizeBytes)
        local < e.versionCode -> "Update → " + e.versionName
        else -> "Installed v" + e.versionName
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return ""
    if (bytes < 1024L * 1024) return "${bytes / 1024L}K"
    return String.format(Locale.getDefault(), "%.1fM", bytes / 1024f / 1024f)
}
