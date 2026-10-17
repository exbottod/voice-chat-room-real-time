package com.example.googlelogin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

val Gold = Color(0xFFD4AF37)
val GoldLight = Color(0xFFF6E3A1)
val GoldDark = Color(0xFF8E6B1F)
val CardBlack = Color(0xFF121212)
val GoldBrush = Brush.horizontalGradient(listOf(GoldDark, GoldLight, Gold, GoldDark))

@Composable
fun RoyalBackground(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF000000), Color(0xFF14100A), Color(0xFF000000))
            )
        )
        val step = 56.dp.toPx()
        val line = Gold.copy(alpha = 0.05f)
        var x = -size.height
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x + size.height, size.height), 1.dp.toPx())
            drawLine(line, Offset(x + size.height, 0f), Offset(x, size.height), 1.dp.toPx())
            x += step
        }
        drawRect(
            Brush.radialGradient(
                listOf(Gold.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width, 0f),
                radius = size.width * 0.9f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Gold.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(0f, size.height),
                radius = size.width * 0.9f,
            )
        )
    }
}

@Composable
fun Crown(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sx = size.width / 100f
        val sy = size.height / 70f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        val gold = Brush.verticalGradient(listOf(GoldLight, Gold, GoldDark))

        val body = Path().apply {
            moveTo(8 * sx, 62 * sy)
            lineTo(5 * sx, 20 * sy)
            lineTo(29 * sx, 40 * sy)
            lineTo(50 * sx, 8 * sy)
            lineTo(71 * sx, 40 * sy)
            lineTo(95 * sx, 20 * sy)
            lineTo(92 * sx, 62 * sy)
            close()
        }
        drawPath(body, gold)
        drawRect(
            Brush.verticalGradient(listOf(GoldLight, GoldDark)),
            topLeft = p(6f, 52f),
            size = Size(88 * sx, 12 * sy),
        )
        drawCircle(GoldLight, radius = 4f * sx, center = p(5f, 18f))
        drawCircle(GoldLight, radius = 5.5f * sx, center = p(50f, 6f))
        drawCircle(GoldLight, radius = 4f * sx, center = p(95f, 18f))
        listOf(
            25f to Color(0xFF2E7D32),
            50f to Color.White,
            75f to Color(0xFF1565C0),
        ).forEach { (x, c) ->
            drawCircle(c, radius = 3f * sx, center = p(x, 58f))
        }
    }
}

@Composable
fun OrnamentDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val cy = size.height / 2
        val mid = size.width / 2
        val gap = 14.dp.toPx()
        val w = 1.dp.toPx()
        drawLine(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Gold),
                startX = size.width * 0.05f,
                endX = mid - gap,
            ),
            Offset(size.width * 0.05f, cy), Offset(mid - gap, cy), w,
        )
        drawLine(
            Brush.horizontalGradient(
                listOf(Gold, Color.Transparent),
                startX = mid + gap,
                endX = size.width * 0.95f,
            ),
            Offset(mid + gap, cy), Offset(size.width * 0.95f, cy), w,
        )
        val d = 6.dp.toPx()
        val diamond = Path().apply {
            moveTo(mid, cy - d)
            lineTo(mid + d, cy)
            lineTo(mid, cy + d)
            lineTo(mid - d, cy)
            close()
        }
        drawPath(diamond, Gold)
    }
}

@Composable
fun GoogleLogo(modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val sw = size.width * 0.2f
        val arcSize = Size(size.width - sw, size.height - sw)
        val tl = Offset(sw / 2, sw / 2)
        val st = Stroke(width = sw)
        drawArc(Color(0xFFEA4335), 215f, 95f, false, tl, arcSize, style = st)
        drawArc(Color(0xFFFBBC05), 140f, 75f, false, tl, arcSize, style = st)
        drawArc(Color(0xFF34A853), 45f, 95f, false, tl, arcSize, style = st)
        drawArc(Color(0xFF4285F4), -5f, 50f, false, tl, arcSize, style = st)
        drawRect(
            Color(0xFF4285F4),
            topLeft = Offset(size.width / 2, size.height / 2 - sw / 2),
            size = Size(size.width / 2, sw),
        )
    }
}

@Composable
fun EyeIcon(visible: Boolean, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        drawOval(
            GoldLight,
            topLeft = Offset(0f, h * 0.25f),
            size = Size(w, h * 0.5f),
            style = Stroke(width = sw),
        )
        drawCircle(GoldLight, radius = h * 0.14f, center = Offset(w / 2, h / 2))
        if (!visible) {
            drawLine(GoldLight, Offset(w * 0.1f, h * 0.9f), Offset(w * 0.9f, h * 0.1f), sw)
        }
    }
}
