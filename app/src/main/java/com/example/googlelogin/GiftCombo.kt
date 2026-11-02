package com.example.googlelogin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kombo gift cashback: setelah gift dikirim, panel tertutup dan muncul tombol
 * bulat di room. Tiap ketuk mengirim gift yang sama lagi, kombo naik terus.
 */
object GiftCombo {
    data class Sess(
        val roomId: String,
        val giftId: String,
        val count: Int,
        val targetUid: String,
        val targetName: String,
        val seat: Int,
    )

    var session by mutableStateOf<Sess?>(null)
        private set
    var combo by mutableStateOf(0)

    fun start(s: Sess) {
        session = s
        combo = 1
    }

    fun stop() {
        session = null
        combo = 0
    }
}

/** Kirim ulang gift yang sama untuk klik kombo. */
suspend fun sendComboGift(myUid: String, myName: String, s: GiftCombo.Sess): String? {
    val g = GiftCatalog.list.firstOrNull { it.id == s.giftId } ?: return "Gift tidak ditemukan"
    val total = g.price * s.count
    val uids = if (s.targetUid.isNotBlank()) listOf(s.targetUid) else emptyList()
    val err = try {
        WalletRepo.sendGift(myUid, total, g.id, s.roomId, uids, total)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        "Gagal memproses gift"
    }
    if (err != null) return err
    try { RoomExtras.sendGiftMessage(s.roomId, myName, g, s.count, s.targetName) } catch (e: Exception) { }
    try {
        GiftFx.sent(
            s.roomId,
            myUid,
            myName,
            g,
            s.count,
            if (uids.isEmpty()) 1 else uids.size,
            s.targetUid,
            s.targetName,
            s.seat,
            if (s.targetUid.isNotBlank()) listOf(Triple(s.targetUid, s.targetName, s.seat)) else emptyList(),
        )
    } catch (e: Exception) { }
    if (s.seat >= 0) {
        try {
            Rtdb.ref("micPoints/" + s.roomId + "/" + s.seat)
                .setValue(ServerValue.increment(g.price * s.count))
        } catch (e: Exception) { }
    }
    return null
}

/** Tombol kombo bulat gaya "Beri terus" dengan cincin waktu, di kanan bawah. */
@Composable
fun GiftComboBar(myUid: String, myName: String) {
    val s = GiftCombo.session ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val gift = GiftCatalog.list.firstOrNull { it.id == s.giftId }
    val timer = remember { androidx.compose.animation.core.Animatable(1f) }

    LaunchedEffect(GiftCombo.combo) {
        timer.snapTo(1f)
        timer.animateTo(
            0f,
            androidx.compose.animation.core.tween(6000, easing = androidx.compose.animation.core.LinearEasing),
        )
        GiftCombo.stop()
    }

    // Selalu di kanan bawah, di atas bar kontrol.
    Box(
        Modifier
            .fillMaxSize()
            .padding(end = 12.dp, bottom = 132.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                note ?: "Kombo x${GiftCombo.combo}",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC2A0F1C))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 5.dp.toPx()
                    drawArc(
                        color = Color(0x55FFFFFF),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        ),
                    )
                    drawArc(
                        color = Color(0xFFFF4D6D),
                        startAngle = -90f,
                        sweepAngle = 360f * timer.value,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        ),
                    )
                }
                Box(
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFFFF6B8B), Color(0xFFFF2E63)),
                            ),
                        )
                        .clickable(enabled = !busy) {
                            busy = true
                            scope.launch {
                                val e = sendComboGift(myUid, myName, s)
                                if (e == null) {
                                    GiftCombo.combo += 1
                                    note = null
                                } else {
                                    note = e
                                }
                                busy = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(gift?.emoji?.ifBlank { "🎁" } ?: "🎁", fontSize = 18.sp)
                        Text(
                            if (busy) "..." else "Beri",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            if (busy) "" else "terus",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
            Text(
                "✕",
                color = Color(0xFFB8C8C5),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { GiftCombo.stop() }
                    .padding(6.dp),
            )
        }
    }
}
