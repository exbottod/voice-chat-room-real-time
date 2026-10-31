package com.example.googlelogin

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Satu kejadian menang game yang ditampilkan sebagai banner berjalan. */
data class GameWinEvent(
    val name: String,
    val game: String,
    val amount: Long,
    val ts: Long,
)

/**
 * Banner kemenangan game.
 *
 * Ditulis ke Realtime Database (gameWin) oleh jembatan game, lalu semua orang
 * di room melihat banner terbang: masuk dari kanan, tampil 3 detik, geser ke kiri
 * lalu menutup.
 */
object GameBanner {
    private const val NODE = "gameWin"

    /** Kirim kejadian menang ke room. Dipanggil dari GameBridge setelah coin masuk. */
    fun publish(name: String, game: String, amount: Long) {
        if (amount <= 0) return
        try {
            Rtdb.ref(NODE).setValue(
                hashMapOf(
                    "name" to name.ifBlank { "Player" },
                    "game" to game.ifBlank { "Game" },
                    "amount" to amount,
                    "ts" to System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) { }
    }
}

@Composable
fun GameWinBanner(modifier: Modifier = Modifier) {
    var event by remember { mutableStateOf<GameWinEvent?>(null) }
    var lastTs by remember { mutableStateOf<Long?>(null) }
    var visible by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val reg = Rtdb.listen(Rtdb.ref("gameWin")) { snap ->
            if (!snap.exists()) return@listen
            val ts = snap.child("ts").getValue(Long::class.java) ?: 0L
            val prev = lastTs
            lastTs = ts
            if (prev == null || ts == prev) return@listen
            // Abaikan kejadian lama saat app baru dibuka.
            if (System.currentTimeMillis() - ts > 20_000) return@listen
            event = GameWinEvent(
                name = snap.child("name").getValue(String::class.java) ?: "Player",
                game = snap.child("game").getValue(String::class.java) ?: "Game",
                amount = (snap.child("amount").value as? Number)?.toLong() ?: 0L,
                ts = ts,
            )
        }
        onDispose { reg.remove() }
    }

    // masuk -> tampil 3 detik -> keluar
    LaunchedEffect(event?.ts) {
        if (event == null) return@LaunchedEffect
        visible = true
        delay(3000)
        visible = false
        delay(420)
        event = null
    }

    val e = event ?: return
    val widthPx = with(LocalConfiguration.current) { screenWidthDp }.dp
    // geser: masuk dari kanan, keluar ke kiri
    val shift by animateFloatAsState(
        targetValue = if (visible) 0f else -1.2f,
        animationSpec = tween(durationMillis = if (visible) 320 else 400),
        label = "gameBanner",
    )

    Box(
        modifier.fillMaxWidth().height(46.dp).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .offset(x = (widthPx * shift))
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFF7A4A00), Color(0xFFD4AF37), Color(0xFFFF6A00))))
                .border(1.dp, Color(0xFFFFE9A0), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(Color(0x55000000)),
                contentAlignment = Alignment.Center,
            ) { Text("🏆", fontSize = 14.sp) }
            Text(
                "${e.name} menang ${e.game} 🪙 ${fmtBanner(e.amount)}",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun fmtBanner(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
        out.append(c)
    }
    return out.toString()
}
