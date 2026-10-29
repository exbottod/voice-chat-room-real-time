package com.example.googlelogin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Dragon Vs Tiger REAL TIME per room.
 *
 * Aturan main:
 * - Satu node Realtime Database menyimpan ronde berjalan (nomor, waktu mulai,
 *   waktu selesai, dan seed). Siapa pun yang membuka lebih dulu membuat ronde,
 *   sisanya mengikuti, jadi hitung mundur semua pemain sama.
 * - Kartu dihitung dari seed + nomor ronde, jadi semua pemain melihat hasil
 *   yang sama tanpa perlu server game.
 * - Coin tetap di Supabase: potong saat pasang, bayar saat menang, dengan
 *   transactionId unik per ronde supaya tidak dobel.
 */

private val GoldR = Color(0xFFF5C542)
private const val ROUND_MS = 20000L
private val SYMBOLS = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A")
private val SUITS = listOf("♠", "♥", "♦", "♣")

private fun fmtR(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
        out.append(c)
    }
    return out.toString()
}

private fun cardFrom(seed: String, round: Long, side: Int): String {
    val h = abs((seed + "#" + round + "#" + side).hashCode())
    val rank = SYMBOLS[h % SYMBOLS.size]
    val suit = SUITS[(h / SYMBOLS.size) % SUITS.size]
    return rank + suit
}

private fun cardValue(card: String): Int {
    val r = card.dropLast(1)
    return when (r) {
        "A" -> 14; "K" -> 13; "Q" -> 12; "J" -> 11
        else -> r.toIntOrNull() ?: 0
    }
}

private fun result2(seed: String, round: Long): String {
    val d = cardValue(cardFrom(seed, round, 0))
    val t = cardValue(cardFrom(seed, round, 1))
    return when {
        d == t -> "TIE"
        d > t -> "DRAGON"
        else -> "TIGER"
    }
}

@Composable
fun DragonTigerRT(roomId: String, myUid: String, onClose: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val base = "rt/$roomId/dvt"

    var round by remember { mutableStateOf(0L) }
    var startAt by remember { mutableStateOf(0L) }
    var endAt by remember { mutableStateOf(0L) }
    var seed by remember { mutableStateOf("") }
    var left by remember { mutableStateOf(ROUND_MS / 1000) }
    var balance by remember { mutableStateOf(0L) }
    var bet by remember { mutableStateOf(1000L) }
    var myBet by remember { mutableStateOf(0L) }
    var mySide by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("Pasang taruhan sebelum waktu habis") }
    var settledRound by remember { mutableStateOf(0L) }
    var players by remember { mutableStateOf(0) }
    val curBet by androidx.compose.runtime.rememberUpdatedState(myBet)
    val curSide by androidx.compose.runtime.rememberUpdatedState(mySide)

    LaunchedEffect(Unit) {
        balance = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    // Ikuti ronde berjalan dari Realtime Database.
    LaunchedEffect(roomId) {
        val reg = Rtdb.listen(Rtdb.ref("$base/cur")) { snap ->
            val n = snap.child("n").getValue(Long::class.java) ?: 0L
            if (n > 0L) {
                round = n
                startAt = snap.child("s").getValue(Long::class.java) ?: 0L
                endAt = snap.child("e").getValue(Long::class.java) ?: 0L
                seed = snap.child("seed").getValue(String::class.java).orEmpty()
            }
        }
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            reg.remove()
        }
    }

    // Hitung mundur bersama.
    LaunchedEffect(endAt, round) {
        if (endAt <= 0L) return@LaunchedEffect
        while (true) {
            val remain = endAt - System.currentTimeMillis()
            left = ((remain + 999) / 1000).toInt().coerceAtLeast(0)
            if (remain <= 0L) break
            kotlinx.coroutines.delay(200)
        }
        // Ronde selesai: selesaikan taruhan sendiri, lalu buka ronde baru.
        val r = round
        val sd = seed
        if (r > 0L && sd.isNotEmpty() && settledRound != r) {
            settledRound = r
            val win = result2(sd, r)
            if (curSide.isNotEmpty() && curBet > 0L) {
                val payout = when {
                    curSide == "TIE" && win == "TIE" -> curBet * 22
                    curSide == win -> curBet * 2
                    else -> 0L
                }
                if (payout > 0L) {
                    val nb = SupaRepo.waveFlow(payout, 2, "dvt-$roomId-$r-$myUid", "Dragon Vs Tiger")
                    if (nb != null) balance = nb
                    msg = "Ronde $r: $win. Kamu menang " + fmtR(payout) + " coin"
                } else {
                    msg = "Ronde $r: $win. Kamu kalah."
                }
            } else {
                msg = "Ronde $r: $win"
            }
            myBet = 0L
            mySide = ""
            cycleRound(base)
        }
    }

    // Jaga ronde tetap ada (kalau room sepi, ronde baru dibuat lagi).
    LaunchedEffect(roomId, endAt) {
        if (endAt == 0L) cycleRound(base)
    }

    // Jumlah pemain yang ikut ronde ini.
    LaunchedEffect(round) {
        if (round <= 0L) return@LaunchedEffect
        val reg = Rtdb.listen(Rtdb.ref("$base/bets/$round")) { snap -> players = snap.childrenCount.toInt() }
        try {
            kotlinx.coroutines.delay(30000)
        } finally {
            reg.remove()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14121A), Color(0xFF08080B))))) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1B1B22)).padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹ Kembali", color = GoldR, fontSize = 15.sp, modifier = Modifier.clickable { onClose() }.padding(6.dp))
                Spacer(Modifier.width(8.dp))
                Text("Dragon Vs Tiger", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(fmtR(balance) + " coin", color = GoldR, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                "Ronde " + round + " • " + players + " pemain",
                color = Color(0xFF9E9E9E), fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
            )

            val showCards = endAt > 0L && System.currentTimeMillis() >= endAt && seed.isNotEmpty()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RtCard(Modifier.weight(1f), if (showCards) cardFrom(seed, round, 0) else "", "DRAGON", Color(0xFF8E1B1B))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF2A2118)).border(2.dp, GoldR, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (left > 0) left.toString() else "•", color = GoldR, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                RtCard(Modifier.weight(1f), if (showCards) cardFrom(seed, round, 1) else "", "TIGER", Color(0xFF17356B))
            }

            Text(
                msg,
                color = Color(0xFFD6D6D6), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RtSide("DRAGON", Color(0xFF8E1B1B), mySide == "DRAGON", Modifier.weight(1f)) {
                    placeBet(roomId, round, myUid, bet, "DRAGON", left > 0, scope) { nb, newSide ->
                        balance = nb; myBet = bet; mySide = newSide
                        msg = "Taruhan " + fmtR(bet) + " coin di DRAGON"
                    }?.let { msg = it }
                }
                RtSide("TIE", Color(0xFF6B4A16), mySide == "TIE", Modifier.weight(0.8f)) {
                    placeBet(roomId, round, myUid, bet, "TIE", left > 0, scope) { nb, newSide ->
                        balance = nb; myBet = bet; mySide = newSide
                        msg = "Taruhan " + fmtR(bet) + " coin di TIE"
                    }?.let { msg = it }
                }
                RtSide("TIGER", Color(0xFF17356B), mySide == "TIGER", Modifier.weight(1f)) {
                    placeBet(roomId, round, myUid, bet, "TIGER", left > 0, scope) { nb, newSide ->
                        balance = nb; myBet = bet; mySide = newSide
                        msg = "Taruhan " + fmtR(bet) + " coin di TIGER"
                    }?.let { msg = it }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(500L, 1000L, 5000L, 10000L).forEach { v ->
                    val on = bet == v
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (on) GoldR else Color(0xFF23232B)).clickable { bet = v }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(fmtR(v), color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (myBet > 0L) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Taruhanmu: " + fmtR(myBet) + " coin di " + mySide,
                    color = GoldR, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Potong coin pemain dan catat taruhannya di ronde ini. Return pesan error atau null. */
private fun placeBet(
    roomId: String,
    round: Long,
    uid: String,
    amount: Long,
    side: String,
    open: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    done: (Long, String) -> Unit,
): String? {
    if (!open) return "Waktu taruhan sudah habis"
    if (round <= 0L) return "Menunggu ronde berikutnya"
    scope.launch {
        val tx = "dvt-bet-$roomId-$round-$uid"
        val nb = SupaRepo.waveFlow(amount, 1, tx, "Dragon Vs Tiger")
        if (nb == null) return@launch
        Rtdb.ref("rt/$roomId/dvt/bets/$round/$uid").setValue(mapOf("side" to side, "amt" to amount))
        done(nb, side)
    }
    return null
}

/** Buat ronde baru kalau ronde lama sudah selesai. Siapa pun boleh memicu, hasil tetap sama. */
private fun cycleRound(base: String) {
    val now = System.currentTimeMillis()
    val ref = Rtdb.ref("$base/cur")
    ref.runTransaction(object : com.google.firebase.database.Transaction.Handler {
        override fun doTransaction(d: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
            val e = d.child("e").getValue(Long::class.java) ?: 0L
            if (e > now) return com.google.firebase.database.Transaction.abort()
            val n = (d.child("n").getValue(Long::class.java) ?: 0L) + 1L
            d.child("n").value = n
            d.child("s").value = now
            d.child("e").value = now + ROUND_MS
            d.child("seed").value = "s" + n + "-" + Random.nextInt(100000, 999999)
            return com.google.firebase.database.Transaction.success(d)
        }

        override fun onComplete(
            error: com.google.firebase.database.DatabaseError?,
            committed: Boolean,
            snapshot: com.google.firebase.database.DataSnapshot?,
        ) {}
    })
}

@Composable
private fun RtCard(mod: Modifier, card: String, label: String, tint: Color) {
    Column(
        mod.clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.35f)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp))
                .background(if (card.isEmpty()) Color(0xFF262630) else Color(0xFFF6F6F6)),
            contentAlignment = Alignment.Center,
        ) {
            if (card.isEmpty()) {
                Text("🂠", color = Color(0xFF4A4A55), fontSize = 34.sp)
            } else {
                Text(
                    card,
                    color = if (card.contains("♥") || card.contains("♦")) Color(0xFFC62828) else Color(0xFF111111),
                    fontSize = 32.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun RtSide(text: String, color: Color, selected: Boolean, mod: Modifier, onClick: () -> Unit) {
    Box(
        mod.clip(RoundedCornerShape(12.dp))
            .background(if (selected) color else color.copy(alpha = 0.55f))
            .clickable { onClick() }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
