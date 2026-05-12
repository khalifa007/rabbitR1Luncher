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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
fun SurveyConsentPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SURVEY_CONSENT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val warn = Color(0xFFE53935)
        val ok = Color(0xFF35D26F)
        val dim = Color(0xFFAAAAAA)

        val rows = listOf("cancel" to warn, "confirm + dial" to ok)
        val listState = rememberLazyListState()
        LaunchedEffect(state.surveyConsentFocus) {
            listState.animateScrollToItem(state.surveyConsentFocus.coerceIn(0, rows.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "confirm dial",
                    color = orange,
                    fontSize = 22.sp,
                    fontFamily = type.appCard.fontFamily,
                )
                Text(
                    text = "bot will read this to the respondent at the start of the call:",
                    color = dim,
                    fontSize = 12.sp,
                    fontFamily = type.appCard.fontFamily,
                )
                Text(
                    text = state.surveyConsentTextDisplay.ifBlank { "(no consent text set)" },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = type.appCard.fontFamily,
                )
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(rows) { idx, (label, _) ->
                        SettingsRow(
                            label = label,
                            focused = state.surveyConsentFocus == idx,
                            subtitle = "",
                            subtitleColor = dim,
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }
        }
    }
}
