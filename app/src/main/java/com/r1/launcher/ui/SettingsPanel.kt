package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

private val HIGHLIGHT_BG = Color(0xFFFF4500) // Orange pill background
private val TRACK_OFF = Color(0xFF333333)

internal sealed class SettingsItem(val label: String) {
    class Standard(label: String, val subtitle: String = "") : SettingsItem(label)
    class Toggle(label: String, val checked: Boolean, val subtitle: String = "") : SettingsItem(label)
    class Info(label: String, val value: String) : SettingsItem(label)
}

private sealed class SettingsEntry {
    data class Section(val label: String) : SettingsEntry()
    data class Row(val label: String, val focusIdx: Int, val info: String? = null) : SettingsEntry()
}

@Composable
fun SettingsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val context = LocalContext.current
        val versionName = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("?")
        }

        SettingsCategoryBody(
            iconRes = R.drawable.ic_settings,
            title = stringResource(R.string.settings_title),
            backFocused = state.settingsFocus == 0,
            onBackClick = { onRowClick(0) },
            rows = listOf(
                stringResource(R.string.settings_row_network) to R.drawable.ic_network,
                stringResource(R.string.settings_row_display) to R.drawable.ic_display,
                stringResource(R.string.settings_row_sound) to R.drawable.ic_sound,
                stringResource(R.string.settings_row_voice) to R.drawable.ic_voice,
                "credentials" to R.drawable.ic_about,
                stringResource(R.string.settings_row_device) to R.drawable.ic_device,
                stringResource(R.string.settings_row_about) to R.drawable.ic_about,
            ),
            focus = state.settingsFocus,
            onRowClick = onRowClick,
        )
    }
}

@Composable
fun SettingsDisplayPanel(state: LauncherState, onRowClick: (Int) -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_DISPLAY,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        SettingsCategoryBody(
            iconRes = R.drawable.ic_display,
            title = stringResource(R.string.settings_display_title),
            backFocused = state.settingsDisplayFocus == 0,
            onBackClick = { onRowClick(0) },
            rows = listOf(
                stringResource(R.string.settings_display_row_brightness) to R.drawable.ic_display,
            ),
            focus = state.settingsDisplayFocus,
            onRowClick = onRowClick,
        )
    }
}

@Composable
fun SettingsSoundPanel(state: LauncherState, onRowClick: (Int) -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_SOUND,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val listState = rememberLazyListState()
        val rows = 4 // toggle + system volume + sound + notifications
        LaunchedEffect(state.settingsSoundFocus) {
            listState.animateScrollToItem(state.settingsSoundFocus.coerceIn(0, rows))
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_sound,
                        title = stringResource(R.string.settings_sound_title),
                        backFocused = state.settingsSoundFocus == 0,
                        onBack = { onRowClick(0) },
                        themeColor = AppThemes.Settings,
                    )
                }
                item(key = "system_toggle") {
                    SettingsRow(
                        label = stringResource(R.string.settings_sound_row_system_toggle),
                        focused = state.settingsSoundFocus == 1,
                        toggleChecked = state.uiSoundEnabled,
                        leadingIcon = R.drawable.ic_sound,
                        onClick = { onRowClick(1) },
                    )
                }
                item(key = "system_volume") {
                    SettingsRow(
                        label = stringResource(R.string.settings_sound_row_ui),
                        focused = state.settingsSoundFocus == 2,
                        leadingIcon = R.drawable.ic_sound,
                        onClick = { onRowClick(2) },
                    )
                }
                item(key = "speaker") {
                    SettingsRow(
                        label = stringResource(R.string.settings_sound_row_speaker),
                        focused = state.settingsSoundFocus == 3,
                        leadingIcon = R.drawable.ic_sound,
                        onClick = { onRowClick(3) },
                    )
                }
                item(key = "notifications") {
                    SettingsRow(
                        label = "notifications",
                        focused = state.settingsSoundFocus == 4,
                        toggleChecked = state.notificationSoundEnabled,
                        leadingIcon = R.drawable.ic_notifications,
                        onClick = { onRowClick(4) },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsDevicePanel(state: LauncherState, onRowClick: (Int) -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_DEVICE,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        SettingsCategoryBody(
            iconRes = R.drawable.ic_device,
            title = stringResource(R.string.settings_device_title),
            backFocused = state.settingsDeviceFocus == 0,
            onBackClick = { onRowClick(0) },
            rows = listOf(
                stringResource(R.string.settings_device_row_check_updates) to R.drawable.ic_update,
                stringResource(R.string.settings_device_row_language) to R.drawable.ic_language,
                stringResource(R.string.settings_device_row_reboot) to R.drawable.ic_reboot,
                stringResource(R.string.settings_device_row_power_off) to R.drawable.ic_power,
                stringResource(R.string.settings_device_row_reset_camera) to R.drawable.ic_device,
                stringResource(R.string.settings_device_row_factory_reset) to R.drawable.ic_factory_reset,
            ),
            focus = state.settingsDeviceFocus,
            onRowClick = onRowClick,
        )
    }
}

@Composable
fun SettingsAboutPanel(state: LauncherState, onRowClick: (Int) -> Unit) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_ABOUT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val context = LocalContext.current
        val versionName = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("?")
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppPageHeader(
                    titleIconRes = R.drawable.ic_settings,
                    title = stringResource(R.string.settings_about_title),
                    backFocused = state.settingsAboutFocus == 0,
                    onBack = { onRowClick(0) },
                    themeColor = AppThemes.Settings,
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AboutRow(
                        label = stringResource(R.string.settings_about_app),
                        value = stringResource(R.string.settings_about_version, versionName ?: "?"),
                        focused = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryBody(
    iconRes: Int,
    title: String,
    backFocused: Boolean,
    onBackClick: () -> Unit,
    rows: List<Pair<String, Int>>,
    focus: Int,
    onRowClick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focus) {
        // Item 0 in the list is the header; rows start at item index 1.
        listState.animateScrollToItem(focus.coerceIn(0, rows.size))
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
                        titleIconRes = iconRes,
                        title = title,
                        backFocused = backFocused,
                        onBack = onBackClick,
                        themeColor = AppThemes.Settings,
                    )
                }
                itemsIndexed(
                    items = rows,
                    key = { _, row -> row.first },
                ) { listIdx, row ->
                    val (label, rowIcon) = row
                    val realIdx = listIdx + 1
                    SettingsRow(
                        label = label,
                        focused = realIdx == focus,
                        leadingIcon = rowIcon,
                        onClick = { onRowClick(realIdx) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsRow(
    label: String,
    focused: Boolean,
    toggleChecked: Boolean? = null,
    subtitle: String = "",
    subtitleColor: Color = Color(0xFFFF4500),
    leadingIcon: Int? = null,
    onClick: () -> Unit,
) {
    val bgColor = if (focused) HIGHLIGHT_BG else Color.Transparent
    val textColor = if (focused) Color.Black else Color.White
    val iconTint = if (focused) Color.Black else Color(0xFFFF6B00)
    val type = LocalR1Type.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (leadingIcon != null) {
                Image(
                    painter = painterResource(leadingIcon),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconTint),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 24.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = if (focused) Color.Black else subtitleColor,
                        fontSize = 14.sp,
                        fontFamily = type.appCard.fontFamily,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
            if (toggleChecked != null) {
                Spacer(modifier = Modifier.width(8.dp))
                MinimalSwitch(
                    checked = toggleChecked,
                    focused = focused,
                )
            }
        }
    }
}

@Composable
internal fun MinimalSwitch(checked: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    // Retro segmented toggle: 2px-edge rectangular frame, square knob that
    // snaps left/right. Orange fill when ON, dark when OFF — same color
    // language as SegmentedLevelCard so the whole settings UI reads as one
    // pixel-art-adjacent system instead of a Material switch crammed into it.
    //
    // Frame is 38dp × 20dp; knob is 14dp × 14dp. Knob slides between
    // x = 2.dp (left) and x = 22.dp (right) — symmetric inset of 2dp on
    // each side after the 2dp border is drawn inside the frame.
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(160),
        label = "thumbOffset",
    )

    val frameShape = RoundedCornerShape(2.dp)
    val knobShape = RoundedCornerShape(1.dp)

    // Border + fill flip when the row itself is highlighted (orange bg) so
    // the toggle stays legible against the row tint.
    val borderColor = when {
        focused -> Color.Black
        checked -> Color(0xFFFF4500)
        else -> Color(0xFF3A3A3A)
    }
    val fillColor = when {
        focused && checked -> Color(0xFF1F0A00) // very dark orange — reads as "on" against the bright row
        focused -> Color(0xFF111111)
        checked -> Color(0xFFFF6A00)
        else -> Color(0xFF1A1A1A)
    }
    val knobColor = when {
        focused && checked -> Color(0xFFFF6A00)
        checked -> Color.White
        focused -> Color(0xFF555555)
        else -> Color(0xFF888888)
    }

    Box(
        modifier = modifier
            .size(width = 38.dp, height = 20.dp)
            .clip(frameShape)
            .background(fillColor)
            .border(2.dp, borderColor, frameShape),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .offset(x = thumbOffset, y = 3.dp)
                .background(knobColor, knobShape),
        )
    }
}

@Composable
internal fun AboutRow(
    label: String,
    value: String,
    focused: Boolean,
) {
    val bgColor = if (focused) HIGHLIGHT_BG else Color.Transparent
    val type = LocalR1Type.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            fontSize = 24.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = if (focused) Color(0xFF1A1A1A) else Color(0xFFFF4500),
            fontSize = 14.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Normal,
        )
    }
}
