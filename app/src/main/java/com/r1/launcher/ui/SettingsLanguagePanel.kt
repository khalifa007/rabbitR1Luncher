package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.locale.Language
import com.r1.launcher.locale.LocalePrefs

private val ACCENT = Color(0xFFFF6B00)
private val HIGHLIGHT_BG = Color(0xFFFF4500)

@Composable
fun SettingsLanguagePanel(state: LauncherState, onRowClick: (Int) -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_LANGUAGE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val languages = LocalePrefs.SUPPORTED
        val listState = rememberLazyListState()
        LaunchedEffect(state.settingsLanguageFocus) {
            listState.animateScrollToItem(
                state.settingsLanguageFocus.coerceIn(0, languages.size)
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") {
                        AppPageHeader(
                            titleIconRes = R.drawable.ic_language,
                            title = "language",
                            backFocused = state.settingsLanguageFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    }
                    itemsIndexed(
                        items = languages,
                        key = { _, lang -> lang.code },
                    ) { listIdx, lang ->
                        val realIdx = listIdx + 1
                        val active = LocalePrefs.get(androidx.compose.ui.platform.LocalContext.current).language == lang.code
                        LanguageRow(
                            lang = lang,
                            focused = realIdx == state.settingsLanguageFocus,
                            active = active,
                            onClick = { onRowClick(realIdx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    lang: Language,
    focused: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (focused) HIGHLIGHT_BG else Color.Transparent
    val fg = if (focused) Color.Black else Color.White
    val accentInline = if (focused) Color(0xFF1A1A1A) else ACCENT
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = lang.displayName,
                color = fg,
                fontSize = 24.sp,
                // Render each language in its own script regardless of the
                // currently active typography — this row is the picker itself,
                // so seeing the script previewed before switching is the point.
                fontFamily = if (lang.isRtl) {
                    FontFamily(Font(R.font.tajawal_bold))
                } else {
                    LocalR1Type.current.appCard.fontFamily
                },
                fontWeight = FontWeight.Medium,
            )
            if (active) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "✓",
                    color = accentInline,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
