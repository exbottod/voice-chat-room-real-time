package com.example.googlelogin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun PowerIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = GoldLight) {
    Canvas(modifier) {
        val w = size.width
        val sw = w * 0.11f
        drawArc(
            color, -60f, 300f, false,
            topLeft = Offset(sw, sw),
            size = Size(w - 2 * sw, w - 2 * sw),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w / 2, sw), Offset(w / 2, w * 0.5f), sw, StrokeCap.Round)
    }
}

@Composable
fun MicIcon(on: Boolean, modifier: Modifier = Modifier.size(24.dp), color: Color = GoldLight) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.36f, h * 0.08f),
            size = Size(w * 0.28f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        )
        drawArc(
            color, 0f, 180f, false,
            topLeft = Offset(w * 0.22f, h * 0.28f),
            size = Size(w * 0.56f, h * 0.4f),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w / 2, h * 0.68f), Offset(w / 2, h * 0.9f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.9f), Offset(w * 0.66f, h * 0.9f), sw, StrokeCap.Round)
        if (!on) {
            drawLine(Color(0xFFFF5252), Offset(w * 0.15f, h * 0.12f), Offset(w * 0.85f, h * 0.92f), sw * 1.2f, StrokeCap.Round)
        }
    }
}

@Composable
fun GridIcon(modifier: Modifier = Modifier.size(24.dp), color: Color = GoldLight) {
    Canvas(modifier) {
        val s = size.width * 0.38f
        val o = listOf(size.width * 0.06f, size.width * 0.56f)
        for (x in o) for (y in o) {
            drawRoundRect(
                color,
                topLeft = Offset(x, y),
                size = Size(s, s),
                cornerRadius = CornerRadius(s * 0.25f, s * 0.25f),
                style = Stroke(size.width * 0.08f),
            )
        }
    }
}
