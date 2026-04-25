package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

/**
 * Store detail overlay — scrim + centered card with Back / Open / Uninstall.
 * Triggered when the user activates an already-installed store row.
 */
@Composable
fun StoreDetail(
    state: LauncherState,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.DETAIL,
        enter = fadeIn(tween(ANIM_OPEN_MS)),
        exit = fadeOut(tween(ANIM_CLOSE_MS)),
    ) {
        val colors = LocalR1Colors.current
        val type = LocalR1Type.current
        val entry = state.detailEntry ?: return@AnimatedVisibility
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .background(colors.sheet, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Text(
                    entry.name,
                    style = type.panelTitle,
                    color = colors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                )

                TileRow(
                    focused = state.detailFocus == 0,
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("← Back", style = type.body, color = colors.muted)
                }
                Spacer(Modifier.height(2.dp))
                TileRow(
                    focused = state.detailFocus == 1,
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open", style = type.body, color = colors.fg)
                }
                Spacer(Modifier.height(2.dp))
                TileRow(
                    focused = state.detailFocus == 2,
                    onClick = onUninstall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Uninstall", style = type.body, color = colors.fg)
                }
            }
        }
    }
}
