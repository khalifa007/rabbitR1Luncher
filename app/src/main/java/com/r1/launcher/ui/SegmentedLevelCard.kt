package com.r1.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ORANGE_FILL = Color(0xFFFF6A00)
private val ORANGE_EDGE = Color(0xFFFF4500)
private val EMPTY_FILL = Color(0xFF1A1A1A)
private val EMPTY_EDGE = Color(0xFF3A3A3A)

/**
 * Retro segmented level meter — N vertical bars, filled = orange, empty = dark.
 * Matches the launcher's 2px-edge tile aesthetic and complements the Jersey 15
 * font used elsewhere. Used by both volume panels (UI sound + speaker).
 *
 * Sized so 15 bars fit comfortably inside the 480-px round screen.
 */
@Composable
fun SegmentedLevelCard(
    title: String,
    hint: String,
    level: Int,
    max: Int,
    onScrimClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val safeMax = max.coerceAtLeast(1)
    val safeLevel = level.coerceIn(0, safeMax)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onScrimClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = title,
                fontSize = 28.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Spacer(Modifier.height(28.dp))

            Row(
                horizontalArrangement = Arrangement_SpaceBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..safeMax) {
                    Segment(filled = i <= safeLevel)
                }
            }
            Spacer(Modifier.height(28.dp))

            Text(
                text = formatLevel(safeLevel, safeMax),
                fontSize = 24.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
                color = ORANGE_EDGE,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = hint,
                fontSize = 16.sp,
                fontFamily = type.appCard.fontFamily,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun Segment(filled: Boolean) {
    val (fill, edge) = if (filled) ORANGE_FILL to ORANGE_EDGE else EMPTY_FILL to EMPTY_EDGE
    Box(
        modifier = Modifier
            .size(width = 10.dp, height = 36.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(2.dp)),
    )
}

private fun formatLevel(level: Int, max: Int): String {
    val l = level.toString().padStart(2, '0')
    val m = max.toString().padStart(2, '0')
    return "$l / $m"
}

// Tiny shim so we don't pull in Arrangement.* import collisions in the panel files.
@Suppress("FunctionName")
private fun Arrangement_SpaceBy(space: androidx.compose.ui.unit.Dp) =
    androidx.compose.foundation.layout.Arrangement.spacedBy(space)
