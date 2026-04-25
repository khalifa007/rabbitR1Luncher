package com.r1.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun RetroKeyboard(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onDismiss: () -> Unit
) {
    var isShifted by remember { mutableStateOf(false) }
    var isNumeric by remember { mutableStateOf(false) }

    val row1 = if (isNumeric) listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0") else listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val row2 = if (isNumeric) listOf("@", "#", "$", "%", "&", "-", "+", "(", ")") else listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = if (isNumeric) listOf("*", "\"", "'", ":", ";", "!", "?", "/", "<") else listOf("^", "z", "x", "c", "v", "b", "n", "m", "<")
    val row4 = listOf(if (isNumeric) "abc" else "123", ",", "space", ".", "hide")

    val type = LocalR1Type.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row1.forEach { key ->
                KeyboardKey(
                    text = if (isShifted && !isNumeric) key.uppercase() else key,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyPress(if (isShifted && !isNumeric) key.uppercase() else key) }
                )
            }
        }
        // Row 2
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row2.forEach { key ->
                KeyboardKey(
                    text = if (isShifted && !isNumeric) key.uppercase() else key,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyPress(if (isShifted && !isNumeric) key.uppercase() else key) }
                )
            }
        }
        // Row 3
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row3.forEach { key ->
                val weight = if (key == "^" || key == "<") 1.5f else 1f
                KeyboardKey(
                    text = key,
                    modifier = Modifier.weight(weight),
                    isSpecial = key == "^" || key == "<",
                    isActive = key == "^" && isShifted,
                    onClick = {
                        when (key) {
                            "^" -> isShifted = !isShifted
                            "<" -> onBackspace()
                            else -> onKeyPress(if (isShifted && !isNumeric) key.uppercase() else key)
                        }
                    }
                )
            }
        }
        // Row 4
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row4.forEach { key ->
                val weight = when (key) {
                    "space" -> 4f
                    "hide" -> 2f
                    else -> 1.5f
                }
                KeyboardKey(
                    text = key,
                    modifier = Modifier.weight(weight),
                    isSpecial = true,
                    onClick = {
                        when (key) {
                            "123" -> { isNumeric = true; isShifted = false }
                            "abc" -> { isNumeric = false; isShifted = false }
                            "space" -> onKeyPress(" ")
                            "hide" -> onDismiss()
                            else -> onKeyPress(key)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    text: String,
    modifier: Modifier = Modifier,
    isSpecial: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val type = LocalR1Type.current
    val bgColor = if (isActive) Color(0xFFFF4500) else Color.Black
    val textColor = if (isActive) Color.Black else if (isSpecial) Color.White else Color(0xFFFF4500)
    val borderColor = if (isSpecial && !isActive) Color.DarkGray else Color(0xFFFF4500)

    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = type.appCard,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}
