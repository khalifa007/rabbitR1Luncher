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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

@Composable
fun SurveySettingsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SURVEY_SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)

        val rows = listOf(
            "< back" to "",
            "openai key" to (if (state.hasOpenAiKey) "…${state.openAiKeyTail}" else "(not set)"),
            "sip host" to state.sipHostDisplay.ifBlank { "(not set)" },
            "sip user" to state.sipUserDisplay.ifBlank { "(not set)" },
            "sip password" to (if (state.hasSipCreds) "••••••••" else "(not set)"),
            "sip from-number" to state.sipFromDisplay.ifBlank { "(not set)" },
            "consent text" to state.surveyConsentTextDisplay.take(40).ifBlank { "(default)" },
            "voice" to state.realtimeVoiceDisplay,
            "summarizer" to state.summarizerModelDisplay,
            "email recipient" to state.surveyEmailRecipientDisplay.ifBlank { "(not set)" },
            "clear all" to "",
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.surveySettingsFocus) {
            listState.animateScrollToItem(state.surveySettingsFocus.coerceIn(0, rows.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        text = "survey settings",
                        color = orange,
                        fontSize = 22.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                itemsIndexed(rows) { idx, (label, sub) ->
                    val subColor = when (idx) {
                        1 -> if (state.hasOpenAiKey) ok else dim
                        2, 3, 4, 5 -> if (state.hasSipCreds) ok else dim
                        else -> dim
                    }
                    SettingsRow(
                        label = label,
                        focused = state.surveySettingsFocus == idx,
                        subtitle = sub,
                        subtitleColor = subColor,
                        onClick = { onRowClick(idx) },
                    )
                }
            }
        }
    }
}
