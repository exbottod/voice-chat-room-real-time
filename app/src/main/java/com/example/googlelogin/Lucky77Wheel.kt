package com.example.googlelogin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * Lucky 77 versi roda. 12 segmen: ceri, semangka, dan 77.
 * Tiga kolom taruhan: Ceri x2, 77 x8, Semangka x2.
 * Digambar dengan Canvas Compose (bukan halaman web), jalan di Android 8+.
 * Coin diproses lewat wallet-api Supabase.
 */

private val GoldW = Color(0xFFF7C948)
private val BgW1 = Color(0xFF3A1458)
private val BgW2 = Color(0xFF12061E)

// 0 = ceri, 1 = semangka, 2 = 77
private val SEG = listOf(2, 0, 1, 0, 1, 0, 2, 1, 0, 1, 0, 1)
private val SEG_COLOR = listOf(
    Color(0xFF7E57C2), Color(0xFFD81B60), Color(0xFF00897B),
)
private val CHIPS = listOf(
    500L to Color(0xFF2E9E5B), 1000L to Color(0xFF3D6BD6), 5000L to Color(0xFF8E44C9), 10000L to Color(0xFFD9503F),
)

private fun fmtW(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
        out.append(c)
    }
    return out.toString()
}

private fun symbolOf(i: Int): String = when (i) {
    0 -> "🍒"
    1 -> "🍉"
    else -> "77"
}

@Composable
fun Lucky77WheelScreen(myUid: String, onClose: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var balance by remember { mutableStateOf(0L) }
    var bet by remember { mutableStateOf(1000L) }
    // taruhan per kolom: 0 = ceri, 1 = 77, 2 = semangka
    var stake by remember { mutableStateOf(listOf(0L, 0L, 0L)) }
    var angle by remember { mutableStateOf(0f) }
    var spinning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("Pilih chip, ketuk kolom, lalu putar") }
    var history by remember { mutableStateOf(listOf(2, 0, 1, 1, 0, 1, 0, 1)) }
    var resultSeg by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        balance = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    fun addStake(col: Int) {
        if (spinning) return
        if (bet > balance) {
            msg = "Coin tidak cukup"
            return
        }
        scope.launch {
            val nb = SupaRepo.waveFlow(bet, 1, java.util.UUID.randomUUID().toString(), "Lucky 77")
            if (nb == null) {
                msg = "Gagal memotong coin, coba lagi"
                return@launch
            }
            balance = nb
            stake = stake.toMutableList().also { it[col] = it[col] + bet }
            msg = "Taruhan " + fmtW(stake[col]) + " di " + listOf("Ceri", "77", "Semangka")[col]
        }
    }

    fun spin() {
        if (spinning) return
        if (stake.all { it == 0L }) {
            msg = "Pasang taruhan dulu"
            return
        }
        spinning = true
        resultSeg = -1
        scope.launch {
            val target = Random.nextInt(SEG.size)
            val segDeg = 360f / SEG.size
            // putar beberapa kali lalu berhenti tepat di tengah segmen target (pointer di atas)
            val finalAngle = angle - (angle % 360f) + 360f * 5 + (360f - (target * segDeg + segDeg / 2f))
            val steps = 60
            val start = angle
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val ease = 1f - (1f - t) * (1f - t) * (1f - t)
                angle = start + (finalAngle - start) * ease
                delay(45)
            }
            resultSeg = target
            val sym = SEG[target]
            history = (listOf(sym) + history).take(8)
            // kolom taruhan: 0 ceri x2, 1 77 x8, 2 semangka x2
            val col = when (sym) { 0 -> 0; 2 -> 1; else -> 2 }
            val mult = if (sym == 2) 8 else 2
            val win = stake[col] * mult
            if (win > 0L) {
                val nb = SupaRepo.waveFlow(win, 2, java.util.UUID.randomUUID().toString(), "Lucky 77")
                if (nb != null) balance = nb
                msg = "Keluar " + symbolOf(sym) + ". Kamu menang " + fmtW(win) + " coin!"
            } else {
                msg = "Keluar " + symbolOf(sym) + ". Belum beruntung"
            }
            stake = listOf(0L, 0L, 0L)
            spinning = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgW1, BgW2)))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹ Kembali", color = GoldW, fontSize = 14.sp, modifier = Modifier.clickable { onClose() }.padding(4.dp))
                Spacer(Modifier.weight(1f))
                Text("LUCKY 77", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("🪙 " + fmtW(balance), color = GoldW, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // ---- Roda
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize().rotate(angle)) {
                    val seg = 360f / SEG.size
                    val d = size.minDimension
                    val pad = d * 0.04f
                    val box = Size(d - pad * 2, d - pad * 2)
                    SEG.forEachIndexed { i, kind ->
                        drawArc(
                            color = SEG_COLOR[kind],
                            startAngle = -90f + i * seg,
                            sweepAngle = seg,
                            useCenter = true,
                            topLeft = Offset(pad, pad),
                            size = box,
                        )
                        drawArc(
                            color = Color(0xFFF7D774),
                            startAngle = -90f + i * seg,
                            sweepAngle = seg,
                            useCenter = true,
                            topLeft = Offset(pad, pad),
                            size = box,
                            style = Stroke(width = 3f),
                        )
                    }
                }
                // simbol tiap segmen ikut berputar
                Box(Modifier.fillMaxSize().rotate(angle)) {
                    SEG.forEachIndexed { i, kind ->
                        Box(
                            Modifier.fillMaxSize().rotate(i * (360f / SEG.size) + (360f / SEG.size) / 2f),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                symbolOf(kind),
                                fontSize = if (kind == 2) 20.sp else 26.sp,
                                color = if (kind == 2) GoldW else Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 26.dp),
                            )
                        }
                    }
                }
                // lingkaran tengah
                Box(
                    Modifier.size(78.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFFFFE082), Color(0xFFE5A100))))
                        .border(4.dp, Color(0xFFFFF3C4), CircleShape)
                        .clickable { spin() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (spinning) "…" else "PUTAR", color = Color(0xFF4A2200), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // penunjuk
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text("▼", color = Color(0xFFFF5252), fontSize = 26.sp)
                }
            }

            Text(
                msg, color = Color(0xFFEADCFB), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            )

            // ---- Tiga kolom taruhan
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val cols = listOf(Triple("🍒", "x2", 0), Triple("77", "x8", 1), Triple("🍉", "x2", 2))
                cols.forEach { (sym, mult, idx) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (idx == 1) Color(0xFF5B3A1A) else Color(0xFF2B1346))
                            .border(
                                if (stake[idx] > 0L) 2.dp else 1.dp,
                                if (stake[idx] > 0L) GoldW else Color(0xFFF0A93B),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { addStake(idx) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sym, fontSize = if (idx == 1) 22.sp else 26.sp, color = GoldW, fontWeight = FontWeight.Bold)
                            Text(mult, fontSize = 18.sp, color = GoldW, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            Modifier.fillMaxWidth().background(Color(0xFFC62855)).padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(fmtW(stake[idx]), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- Dompet + chip
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF3A2255))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("⭐ " + fmtW(balance), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                CHIPS.forEach { (v, c) ->
                    val on = bet == v
                    Box(
                        Modifier.padding(start = 6.dp).size(46.dp).clip(CircleShape)
                            .background(if (on) c else c.copy(alpha = 0.6f))
                            .border(if (on) 3.dp else 1.dp, if (on) GoldW else Color(0xFFEADCFB), CircleShape)
                            .clickable { bet = v },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (v >= 1000) (v / 1000).toString() + "K" else v.toString(),
                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // ---- Riwayat
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFF2B1346)).padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                history.take(8).forEach { h ->
                    Text(symbolOf(h), fontSize = if (h == 2) 16.sp else 20.sp, color = GoldW, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
