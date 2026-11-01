package com.example.googlelogin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Satu kiriman gift yang terbang ke avatar penerima.
 * Diambil dari "giftFly/{roomId}" di Realtime Database.
 */
data class GiftFly(
    val from: String,
    val toName: String,
    val seat: Int,
    val emoji: String,
    val price: Long,
    val cb: Long,
    val pecahan: Long,
    val asset: String,
    val jp: Boolean,
    val ts: Long,
)

/**
 * Efek gift terbang: gift meluncur dari sisi pengirim menuju kursi mic penerima
 * (atau ikon user online kalau penerima tidak di mic), lalu pecahan cashback
 * muncul di titik itu. Kalau nilainya besar, banner JP tampil di atas.
 */
@Composable
fun GiftFlyOverlay(roomId: String, seatPoints: Map<Int, Offset>) {
    var fly by remember { mutableStateOf<GiftFly?>(null) }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(roomId) {
        var isInit = true
        val reg = Rtdb.listen(Rtdb.ref("giftFly/$roomId").limitToLast(1)) { snap ->
            val c = snap.children.lastOrNull()
            val ts = c?.child("ts")?.getValue(Long::class.java) ?: 0L
            val key = c?.key
            if (isInit) {
                isInit = false
                // Saat baru masuk room: gift lama (lebih dari 6 detik) tidak diputar.
                lastKey = if (ts > System.currentTimeMillis() - 6000L) null else key
                return@listen
            }
            if (c != null && key != null && key != lastKey) {
                lastKey = key
                fly = GiftFly(
                    from = c.child("from").getValue(String::class.java) ?: "",
                    toName = c.child("toName").getValue(String::class.java) ?: "",
                    seat = (c.child("seat").getValue(Long::class.java) ?: -1L).toInt(),
                    emoji = c.child("emoji").getValue(String::class.java) ?: "🎁",
                    price = c.child("price").getValue(Long::class.java) ?: 0L,
                    cb = c.child("cb").getValue(Long::class.java) ?: 0L,
                    pecahan = c.child("pecahan").getValue(Long::class.java) ?: 0L,
                    asset = c.child("asset").getValue(String::class.java) ?: "",
                    jp = c.child("jp").getValue(Boolean::class.java) ?: false,
                    ts = ts,
                )
            }
        }
        onDispose { reg.remove() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                box = it.size
            },
    ) {
        val f = fly
        if (f != null && box.width > 0) {
            GiftFlyOnce(f, seatPoints, origin, box) { fly = null }
        }
    }
}

@Composable
private fun GiftFlyOnce(
    f: GiftFly,
    seatPoints: Map<Int, Offset>,
    origin: Offset,
    box: IntSize,
    onDone: () -> Unit,
) {
    val prog = remember(f.ts) { Animatable(0f) }
    var landed by remember(f.ts) { mutableStateOf(false) }

    // Titik tujuan: avatar kursi mic penerima. Kalau tidak di mic, ikon user online.
    val target = remember(f.ts, seatPoints, origin, box) {
        val abs = if (f.seat >= 0) seatPoints[f.seat] else null
        val p = abs ?: Offset(box.width * 0.74f, box.height * 0.40f)
        Offset(p.x - origin.x, p.y - origin.y)
    }
    val start = Offset(box.width * 0.14f, box.height * 0.88f)

    LaunchedEffect(f.ts) {
        prog.animateTo(1f, tween(durationMillis = 750, easing = FastOutSlowInEasing))
        landed = true
        delay(if (f.asset.isNotBlank()) 3400L else 2200L)
        onDone()
    }

    if (!landed) {
        val t = prog.value
        val x = start.x + (target.x - start.x) * t
        val y = start.y + (target.y - start.y) * t - 110f * sin(PI * t).toFloat()
        Box(
            Modifier
                .offset { IntOffset(x.toInt(), y.toInt()) }
                .size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(f.emoji.ifBlank { "🎁" }, fontSize = 32.sp)
        }
        return
    }

    // Sampai di avatar penerima: pecahan cashback pecah di titik itu.
    Box(
        Modifier
            .offset { IntOffset((target.x - 140f).toInt(), (target.y - 140f).toInt()) }
            .size(280.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (f.asset.isNotBlank()) {
            GiftSvga(f.asset, f.emoji, Modifier.fillMaxSize()) { }
        } else {
            Text(f.emoji.ifBlank { "🎁" }, fontSize = 56.sp)
        }
    }
    if (f.cb > 0) {
        Box(
            Modifier
                .offset { IntOffset((target.x - 110f).toInt(), (target.y + 62f).toInt()) }
                .width(220.dp)
                .background(Color(0xCC000000), RoundedCornerShape(50))
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "+${fmtNum(f.cb)}",
                color = Color(0xFFFFD54F),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    // Banner JP: hanya untuk cashback besar, terlihat semua orang di room.
    if (f.jp && f.cb > 0) {
        Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .background(Color(0xE64A148C), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    "💥 JP ${f.from} +${fmtNum(f.cb)}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
