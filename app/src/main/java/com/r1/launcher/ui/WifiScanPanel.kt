package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

@Composable
fun WifiScanPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.WIFI_SCAN,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val items = mutableListOf<String>()
        items.add("header")
        items.addAll(state.wifiScanResults)

        val listState = rememberLazyListState()
        LaunchedEffect(state.wifiScanFocus) {
            listState.animateScrollToItem(
                state.wifiScanFocus.coerceIn(0, items.lastIndex)
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = items,
                    key = { idx, item -> if (idx == 0) "header" else item },
                ) { idx, item ->
                    if (idx == 0) {
                        AppPageHeader(
                            titleIconRes = R.drawable.ic_network,
                            title = "wi-fi",
                            backFocused = state.wifiScanFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    } else {
                        SettingsRow(
                            label = item,
                            focused = idx == state.wifiScanFocus,
                            toggleChecked = null,
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
}
