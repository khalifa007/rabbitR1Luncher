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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SurveyListPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SURVEY_LIST,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val dim = Color(0xFFAAAAAA)
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

        val headers: List<Pair<String, String>> = listOf(
            "< back" to "",
            "settings" to "openai key, sip, consent…",
            "+ new campaign" to "pick survey + contacts on web",
        )
        val rows = headers + state.campaigns.map { c ->
            val date = fmt.format(Date(c.createdAtMs))
            "campaign ${c.id.take(6)}" to "${c.contactCount} contacts · ${c.status.name.lowercase()} · $date"
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.surveyListFocus) {
            listState.animateScrollToItem(state.surveyListFocus.coerceIn(0, rows.lastIndex))
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
                        text = "surveyor",
                        color = orange,
                        fontSize = 22.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                if (state.campaigns.isEmpty()) {
                    item {
                        Text(
                            text = "no campaigns yet — author one in the web companion (settings → network → remote panel)",
                            color = dim,
                            fontSize = 12.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                }
                itemsIndexed(rows) { idx, (label, sub) ->
                    SettingsRow(
                        label = label,
                        focused = state.surveyListFocus == idx,
                        subtitle = sub,
                        subtitleColor = dim,
                        onClick = { onRowClick(idx) },
                    )
                }
            }
        }
    }
}
