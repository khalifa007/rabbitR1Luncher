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
import com.r1.launcher.survey.SurveyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SurveyDetailPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SURVEY_DETAIL,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val orange = Color(0xFFFF4500)
        val dim = Color(0xFFAAAAAA)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
        val record = remember(state.currentCallRecordId) {
            state.currentCallRecordId?.let { SurveyStore.get(ctx).loadCallRecord(it) }
        }

        val headerRows = listOf(
            "< back" to "",
            "⋮ actions" to "play · email · re-email · delete",
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.surveyDetailFocus) {
            listState.animateScrollToItem(state.surveyDetailFocus.coerceIn(0, headerRows.lastIndex))
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
                        text = record?.contact?.name?.ifBlank { record.contact.phone } ?: "(missing)",
                        color = orange,
                        fontSize = 20.sp,
                        fontFamily = type.appCard.fontFamily,
                    )
                }
                itemsIndexed(headerRows) { idx, (label, sub) ->
                    SettingsRow(
                        label = label,
                        focused = state.surveyDetailFocus == idx,
                        subtitle = sub,
                        subtitleColor = dim,
                        onClick = { onRowClick(idx) },
                    )
                }
                if (record != null) {
                    item {
                        Text(
                            text = "status: ${record.status.name.lowercase()}  ·  ${fmt.format(Date(record.createdAtMs))}",
                            color = dim,
                            fontSize = 12.sp,
                            fontFamily = type.appCard.fontFamily,
                        )
                    }
                    val summary = record.summary
                    if (!summary.isNullOrBlank()) {
                        item {
                            Text(
                                text = summary,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                        }
                    }
                    if (record.structuredAnswers.isNotEmpty()) {
                        item {
                            Text(
                                text = "answers",
                                color = orange,
                                fontSize = 14.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                        }
                        record.structuredAnswers.entries.forEach { (qid, ans) ->
                            item {
                                Text(
                                    text = "$qid → $ans",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontFamily = type.appCard.fontFamily,
                                )
                            }
                        }
                    }
                    val transcript = record.transcript
                    if (!transcript.isNullOrBlank()) {
                        item {
                            Text(
                                text = "transcript",
                                color = orange,
                                fontSize = 14.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                        }
                        item {
                            Text(
                                text = transcript,
                                color = dim,
                                fontSize = 11.sp,
                                fontFamily = type.appCard.fontFamily,
                            )
                        }
                    }
                }
            }
        }
    }
}
