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
        /** Semua penerima (uid, nama, kursi). Kosong = hanya penerima tunggal di atas. */
        val receivers: List<Triple<String, String, Int>> = emptyList(),
    ) {
        /** Daftar penerima yang dipakai tiap ketukan kombo. */
        fun all(): List<Triple<String, String, Int>> = when {
            receivers.isNotEmpty() -> receivers
            targetUid.isNotBlank() -> listOf(Triple(targetUid, targetName, seat))
            else -> emptyList()
        }
    }

    var session by mutableStateOf<Sess?>(null)
    var combo by mutableStateOf(0)
    var expiry by mutableStateOf(0L)

    fun start(s: Sess) {
        session = s
        combo = 1
        expiry = System.currentTimeMillis() + 12000L
    }

    /** Setiap ketukan menambah kombo dan memperpanjang waktu tombol tampil. */
    fun touch() {
        combo += 1
        expiry = System.currentTimeMillis() + 12000L
    }

    fun stop() {
        session = null
        combo = 0
        expiry = 0L
    }
}

/** Efek lokal yang langsung tampil saat tombol diketuk (tanpa menunggu server). */
private val visualScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
)

fun playComboVisuals(myUid: String, myName: String, s: GiftCombo.Sess) {
    visualScope.launch {
        val g = GiftCatalog.list.firstOrNull { it.id == s.giftId } ?: return@launch
        val rec = s.all()
        val first = rec.firstOrNull()
        val toText = when {
            rec.size > 1 -> "semua mic (${rec.size})"
            else -> s.targetName
        }
        try { RoomExtras.sendGiftMessage(s.roomId, myName, g, s.count, toText) } catch (e: Exception) { }
        try {
            GiftFx.sent(
                s.roomId,
                myUid,
                myName,
                g,
                s.count,
                if (rec.isEmpty()) 1 else rec.size,
                first?.first ?: "",
                first?.second ?: "",
                first?.third ?: -1,
                rec,
            )
        } catch (e: Exception) { }
        // Poin mic naik untuk SETIAP penerima, bukan hanya yang pertama.
        for (r in rec) {
            if (r.third < 0) continue
            try {
                Rtdb.ref("micPoints/" + s.roomId + "/" + r.third)
                    .setValue(ServerValue.increment(g.price * s.count))
            } catch (e: Exception) { }
        }
    }
}

/**
 * Antrean kirim kombo.
 *
 * Ketukan cepat digabung dalam jendela 150 ms lalu dikirim sebagai satu
 * permintaan, jadi tombol terasa instan dan server tidak kebanjiran request.
 */
object GiftComboSend {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private class Tap(val s: GiftCombo.Sess, val n: Int, val uid: String)
    private val taps = kotlinx.coroutines.channels.Channel<Tap>(256)

    /** Pesan error terakhir untuk ditampilkan di tombol kombo. */
    var lastError by mutableStateOf<String?>(null)

    init {
        scope.launch {
            for (first in taps) {
                var cur = first
                var n = first.n
                // Kumpulkan ketukan berikutnya yang cepat.
                while (true) {
                    val more = kotlinx.coroutines.withTimeoutOrNull(150L) { taps.receive() }
                        ?: break
                    if (more.s.giftId != cur.s.giftId || more.s.roomId != cur.s.roomId) {
                        // Gift/room berbeda: kirim yang terkumpul dulu, sisanya antre berikutnya.
                        taps.trySend(more)
                        break
                    }
                    cur = more
                    n += more.n
                }
                val s = cur.s
                val g = GiftCatalog.list.firstOrNull { it.id == s.giftId } ?: continue
                val rec = s.all()
                val uids = rec.map { it.first }
                // Harga per penerima x jumlah ketukan; total dikali jumlah penerima.
                val perReceiver = g.price * s.count * n
                val total = perReceiver * uids.size.coerceAtLeast(1)
                val err = try {
                    WalletRepo.sendGift(cur.uid, total, g.id, s.roomId, uids, perReceiver)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Gagal memproses gift"
                }
                if (err != null) {
                    lastError = err
                } else if (g.cashback || g.cbRate > 0) {
                    // Kombo juga wajib ikut undian cashback, kalau tidak pengali/JP
                    // tidak pernah muncul walau gift sudah terkirim.
                    undiCashbackKombo(cur.uid, s, g, s.count * n)
                }
            }
        }
    }

    /** Undian cashback untuk kiriman kombo, lalu kirim datanya ke layar room. */
    private suspend fun undiCashbackKombo(uid: String, s: GiftCombo.Sess, g: Gift, count: Int) {
        val harga = if (g.cbPrice > 0L) g.cbPrice else g.price
        var cbWin = 0L
        var mult = 0
        var jp = false
        var parts = ""
        var pecahan = 0L
        try {
            val cb = kotlinx.coroutines.withTimeoutOrNull(9000L) {
                SupaRepo.giftCashback(uid, harga, count, s.targetUid)
            }
            if (cb != null && cb.error == null && cb.amount > 0) {
                cbWin = cb.amount
                mult = cb.mult
                jp = cb.jp
                pecahan = if (cb.parts.isNotEmpty()) cb.parts.first() else cb.pecahan
                parts = cb.parts.joinToString(",")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        if (cbWin <= 0L) return
        val dari = try { UserCache.meLocal(uid)?.name ?: "" } catch (e: Exception) { "" }
        s.all().forEach { t ->
            try {
                Rtdb.ref("giftFly/${s.roomId}").push().setValue(
                    hashMapOf<String, Any?>(
                        "from" to dari,
                        "toName" to t.second,
                        "toUid" to t.first,
                        "seat" to t.third.toLong(),
                        "gift" to g.name,
                        "emoji" to g.emoji,
                        "mediaUrl" to g.mediaUrl,
                        "mediaType" to g.mediaType,
                        "price" to g.price * count,
                        "cb" to cbWin,
                        "pecahan" to pecahan,
                        "parts" to parts,
                        "mult" to mult,
                        "combo" to GiftCombo.combo.toLong(),
                        "asset" to "",
                        "jp" to jp,
                        "ts" to System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) { }
        }
        try {
            Rtdb.ref("cashback/$uid/total").setValue(com.google.firebase.database.ServerValue.increment(cbWin))
        } catch (e: Exception) { }
    }

    fun enqueue(s: GiftCombo.Sess, uid: String, n: Int = 1) {
        taps.trySend(Tap(s, n, uid))
    }
}

/** Tombol kombo bulat gaya "Beri terus" dengan cincin waktu, di kanan bawah. */
@Composable
fun GiftComboBar(myUid: String, myName: String) {
    val s = GiftCombo.session ?: return
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var frac by remember { mutableStateOf(1f) }
    val gift = remember(s.giftId) { GiftCatalog.list.firstOrNull { it.id == s.giftId } }

    // Tidak di-restart saat diketuk: hanya mengecek sisa waktu.
    LaunchedEffect(s) {
        while (true) {
            val left = GiftCombo.expiry - System.currentTimeMillis()
            frac = (left / 12000f).coerceIn(0f, 1f)
            if (left <= 0L) { GiftCombo.stop(); break }
            delay(100)
        }
    }

    // Ditempel ke kanan bawah layar (popup), tidak ikut alur tata letak room.
    val dens = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomEnd,
        offset = androidx.compose.ui.unit.IntOffset(
            with(dens) { (-10).dp.roundToPx() },
            with(dens) { (-140).dp.roundToPx() },
        ),
        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                note ?: GiftComboSend.lastError ?: "Kombo x${GiftCombo.combo}",
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
                Canvas(Modifier.fillMaxSize()) {
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
                        sweepAngle = 360f * frac,
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
                        .clickable {
                            // Instan: kombo naik, gift langsung terbang, tagihan diantre di belakang.
                            GiftCombo.touch()
                            note = null
                            GiftComboSend.lastError = null
                            playComboVisuals(myUid, myName, s)
                            GiftComboSend.enqueue(s, myUid, 1)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(gift?.emoji?.ifBlank { "🎁" } ?: "🎁", fontSize = 18.sp)
                        Text(
                            "Beri",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "terus",
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
