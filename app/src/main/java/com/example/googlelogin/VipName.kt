package com.example.googlelogin

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private fun nameColors(vip: Int): List<Color> {
    val base: List<Color> = when (vip) {
        1 -> listOf(Color(0xFF26C6DA), Color(0xFF66BB6A), Color(0xFFD4E157))
        2 -> listOf(Color(0xFFF06292), Color(0xFFFF8A65), Color(0xFFFFD54F))
        3 -> listOf(Color(0xFFAB47BC), Color(0xFF5C6BC0), Color(0xFF29B6F6))
        4 -> listOf(Color(0xFFFF7043), Color(0xFFEF5350), Color(0xFFEC407A))
        5 -> listOf(Color(0xFFFFCA28), Color(0xFF26A69A), Color(0xFF7E57C2))
        6 -> listOf(Color(0xFF1565C0), Color(0xFF42A5F5), Color(0xFFE3F2FD), Color(0xFF42A5F5))
        7 -> listOf(Color(0xFFFF8F00), Color(0xFFFFD54F), Color(0xFFFFFDE7), Color(0xFFFFD54F))
        8 -> listOf(Color(0xFF00E676), Color(0xFF00C853), Color(0xFFFF1744), Color(0xFFD50000))
        else -> listOf(Color(0xFFFFEB3B), Color(0xFF00E676), Color(0xFF2979FF), Color(0xFFFF1744))
    }
    return base + base.first()
}

private fun nameCycleMs(vip: Int): Int = when (vip) {
    6, 7 -> 2200
    9 -> 3200
    else -> 2800
}

@Composable
fun VipNameText(
    text: String,
    vip: Int,
    until: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    if (!vipActive(vip, until)) {
        Text(
            text, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
            maxLines = maxLines, overflow = overflow, textAlign = textAlign,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "vipName")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(nameCycleMs(vip), easing = LinearEasing), RepeatMode.Restart),
        label = "vipNamePhase",
    )
    val period = if (vip >= 6) 360f else 480f
    val shift = phase * period
    val brush = Brush.linearGradient(
        colors = nameColors(vip),
        start = Offset(shift, 0f),
        end = Offset(shift + period, 0f),
        tileMode = TileMode.Repeated,
    )
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(
            brush = brush,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun VipNameByUid(
    text: String,
    uid: String?,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val u = if (uid.isNullOrBlank()) null else UserCache.peek(uid)
    VipNameText(
        text, u?.vip ?: 0, u?.vipUntil ?: 0L, modifier, color, fontSize, fontWeight, maxLines, overflow, textAlign,
    )
}
