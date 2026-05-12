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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

/**
 * Settings → Voice → Subscription. Full readout of the user's current
 * ElevenLabs plan + balance. Two rows:
 *   0  < back
 *   1  refresh   (force re-fetch, bypassing the 60s cache)
 *
 * Auto-fetch fires when the row in SettingsVoicePanel opens this panel — see
 * [com.r1.launcher.LauncherActivity.voiceSettingsRowActivate].
 */
@Composable
fun SettingsVoiceSubscriptionPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_VOICE_SUBSCRIPTION,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val ok = Color(0xFF35D26F)
        val warn = Color(0xFFFFA726)
        val red = Color(0xFFE53935)
        val dim = Color(0xFFAAAAAA)

        val data = state.voiceSubData
        val used = data?.characterCount ?: 0L
        val limit = data?.characterLimit ?: 0L
        val pct = if (limit > 0L) (used.toDouble() / limit.toDouble() * 100.0).toInt().coerceIn(0, 100) else 0
        val barColor = when {
            pct >= 90 -> red
            pct >= 70 -> warn
            else -> ok
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Back row — focused row 0
                SubRowButton(
                    label = "< back",
                    focused = state.voiceSubFocus == 0,
                    color = orange,
                    onClick = onBack,
                )

                Text(
                    text = stringResource(R.string.voice_sub_title),
                    color = orange,
                    fontSize = 22.sp,
                    fontFamily = type.appCard.fontFamily,
                    fontWeight = FontWeight.Bold,
                )

                when {
                    state.voiceSubLoading && data == null -> {
                        Text(
                            text = stringResource(R.string.common_loading),
                            color = dim,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    state.voiceSubError != null -> {
                        Text(
                            text = stringResource(R.string.voice_sub_error),
                            color = red,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                        Text(
                            text = state.voiceSubError ?: "",
                            color = red,
                            fontSize = 12.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    data == null -> {
                        Text(
                            text = stringResource(R.string.voice_sub_no_data),
                            color = dim,
                            fontSize = 14.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    else -> {
                        // Tier
                        Text(
                            text = (data.tier ?: "—").uppercase(),
                            color = Color.White,
                            fontSize = 28.sp,
                            fontFamily = type.appCard.fontFamily,
                            fontWeight = FontWeight.Bold,
                        )

                        // Used / Limit
                        Text(
                            text = "${formatNum(used)} / ${formatNum(limit)} credits",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                        Text(
                            text = "${pct}% used",
                            color = barColor,
                            fontSize = 12.sp,
                            fontFamily = type.appCard.fontFamily,
                        )

                        // Progress bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1A1A1A)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(pct / 100f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(barColor),
                            )
                        }

                        Spacer(Modifier.height(2.dp))

                        // Reset date + period
                        if (data.nextResetUnix > 0) {
                            DetailLine(
                                "resets",
                                formatFullDate(data.nextResetUnix * 1000L),
                                type, dim,
                            )
                        }
                        data.refreshPeriod?.let {
                            DetailLine("period", prettyPeriod(it), type, dim)
                        }
                        data.status?.let {
                            DetailLine("status", it, type, dim)
                        }
                        data.currency?.let {
                            DetailLine("currency", it.uppercase(), type, dim)
                        }
                        if (data.canExtendCharacterLimit && data.maxCharacterLimitExtension > 0) {
                            DetailLine(
                                "overage",
                                "+${formatNum(data.maxCharacterLimitExtension)} max",
                                type, dim,
                            )
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.voice_sub_credits_help),
                            color = Color(0xFF707070),
                            fontSize = 11.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Refresh row — focused row 1
                SubRowButton(
                    label = if (state.voiceSubLoading) "refreshing..." else "refresh",
                    focused = state.voiceSubFocus == 1,
                    color = ok,
                    enabled = !state.voiceSubLoading,
                    onClick = onRefresh,
                )

                if (state.voiceSubFetchedAtMs > 0L) {
                    Text(
                        text = stringResource(
                            R.string.voice_sub_fetched_ago,
                            formatRelative(System.currentTimeMillis() - state.voiceSubFetchedAtMs),
                        ),
                        color = Color(0xFF707070),
                        fontSize = 10.sp,
                        fontFamily = type.appCard.fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, type: R1Type, dim: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = dim,
            fontSize = 12.sp,
            fontFamily = type.appCard.fontFamily,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = type.appCard.fontFamily,
        )
    }
}

@Composable
private fun SubRowButton(
    label: String,
    focused: Boolean,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val bg = if (focused) color else Color.Transparent
    val fg = if (focused) Color.Black else color
    val borderColor = if (enabled) color else Color(0xFF333333)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 16.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 12_345 → "12,345"; matches what the ElevenLabs dashboard shows. */
private fun formatNum(n: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)

private fun formatFullDate(unixMs: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
    return sdf.format(java.util.Date(unixMs))
}

private fun prettyPeriod(raw: String): String = when (raw) {
    "monthly_period" -> "monthly"
    "annual_period" -> "annual"
    "3_month_period" -> "quarterly"
    "6_month_period" -> "biannual"
    else -> raw
}

private fun formatRelative(ageMs: Long): String {
    val sec = ageMs / 1000L
    return when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        else -> "${sec / 3600}h"
    }
}
