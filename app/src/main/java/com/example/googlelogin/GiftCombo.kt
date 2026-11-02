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

/** Tombol kombo bulat yang muncul otomatis setelah gift cashback dikirim. */
@Composable
fun GiftComboBar(myUid: String, myName: String) {
    val s = GiftCombo.session ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val gift = GiftCatalog.list.firstOrNull { it.id == s.giftId }
    val teal = Color(0xFF2BD9C4)

    // Berhenti sendiri kalau 8 detik tidak ada klik.
    LaunchedEffect(GiftCombo.combo) {
        delay(8000)
        GiftCombo.stop()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xE607161A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "Kombo x${GiftCombo.combo}",
                color = teal,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                note ?: (gift?.name ?: "Gift"),
                color = Color(0xFFB8C8C5),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (busy) Color(0xFF16302F) else teal)
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
                Text(gift?.emoji?.ifBlank { "🎁" } ?: "🎁", fontSize = 22.sp)
                Text(
                    if (busy) "..." else "Klik",
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0xFF16302F))
                .clickable { GiftCombo.stop() },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Color(0xFF8FA3A0), fontSize = 12.sp)
        }
    }
}
