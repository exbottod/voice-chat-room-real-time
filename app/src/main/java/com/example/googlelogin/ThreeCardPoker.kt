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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 3 Card Poker dengan tampilan meja seperti aplikasi kasino:
 * kursi pemain di atas, judul, hitung mundur, riwayat hasil, tiga kolom
 * perisai (Wolf / Lion / Fish) dengan Pot dan Me, lalu chip taruhan di bawah.
 * Coin tetap diproses lewat wallet-api Supabase.
 */

private val GoldP = Color(0xFFF3C64B)
private val NeonP = Color(0xFFB84DFF)
private val BgTop = Color(0xFF2A1240)
private val BgBottom = Color(0xFF0E0618)
private val ChipColors = listOf(
    100L to Color(0xFF1FA5A5),
    500L to Color(0xFF3D5AFE),
    1000L to Color(0xFF8E24AA),
    5000L to Color(0xFFD84315),
    10000L to Color(0xFFB71C1C),
    50000L to Color(0xFFD4AF37),
)

private fun fmtP(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
        out.append(c)
    }
    return out.toString()
}

@Composable
fun ThreeCardPokerScreen(myUid: String, myName: String, myAvatarB64: String, onClose: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var balance by remember { mutableStateOf(0L) }
    var bet by remember { mutableStateOf(500L) }
    var pot by remember { mutableStateOf(listOf(0L, 0L, 0L)) }
    var stakes by remember { mutableStateOf(listOf(0L, 0L, 0L)) }
    var hands by remember { mutableStateOf(listOf(listOf<String>(), listOf<String>(), listOf<String>())) }
    var left by remember { mutableStateOf(15) }
    var msg by remember { mutableStateOf("Pilih nominal, lalu ketuk perisai") }
    var history by remember { mutableStateOf(listOf(1, 2, 0, 1, 0, 1, 2)) }
    var dealt by remember { mutableStateOf(false) }
    var roundId by remember { mutableStateOf(1) }

    LaunchedEffect(Unit) {
        balance = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    // Ronde: hitung mundur, bagi kartu, tentukan pemenang, lalu ulang.
    LaunchedEffect(roundId) {
        dealt = false
        left = 15
        while (left > 0) {
            delay(1000)
            left -= 1
        }
        val deck = tpDeck().shuffled()
        val h = listOf(deck.take(3), deck.subList(3, 6), deck.subList(6, 9))
        hands = h
        dealt = true
        val scores = h.map { tpScore(it) }
        val winner = scores.indexOf(scores.maxOrNull() ?: 0)
        val total = stakes.sum()
        if (total > 0L) {
            val win = stakes[winner] * 3
            if (win > 0L) {
                val nb = SupaRepo.waveFlow(win, 2, "3cp-$roundId-$myUid", "3 Card Poker")
                if (nb != null) balance = nb
                msg = "Kolom " + listOf("Wolf", "Lion", "Fish")[winner] + " menang. Kamu dapat " + fmtP(win) + " coin"
            } else {
                msg = "Kolom " + listOf("Wolf", "Lion", "Fish")[winner] + " menang. Kamu kalah"
            }
        } else {
            msg = "Kolom " + listOf("Wolf", "Lion", "Fish")[winner] + " menang"
        }
        history = (listOf(winner) + history).take(8)
        pot = listOf(0L, 0L, 0L)
        stakes = listOf(0L, 0L, 0L)
        delay(5000)
        roundId += 1
    }

    fun placeLane(lane: Int) {
        if (dealt || left <= 2) {
            msg = "Waktu taruhan sudah habis, tunggu ronde berikutnya"
            return
        }
        if (bet > balance) {
            msg = "Coin tidak cukup"
            return
        }
        scope.launch {
            val nb = SupaRepo.waveFlow(bet, 1, UUID.randomUUID().toString(), "3 Card Poker")
            if (nb == null) {
                msg = "Gagal memotong coin, coba lagi"
                return@launch
            }
            balance = nb
            stakes = stakes.toMutableList().also { it[lane] = it[lane] + bet }
            pot = pot.toMutableList().also { it[lane] = it[lane] + bet }
            msg = "Taruhan " + fmtP(stakes[lane]) + " di " + listOf("Wolf", "Lion", "Fish")[lane]
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        ) {
            // ---- Bilah atas: profil, hati, tombol keluar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF3A2352))
                        .border(1.dp, GoldP, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (myName.isBlank()) "?" else myName.take(1).uppercase(),
                        color = GoldP, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(myName.ifBlank { "Pemain" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("REZO", color = Color(0xFFB39DDB), fontSize = 9.sp)
                }
                Text("💗 0", color = Color(0xFFFF79B0), fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
                Text("Keluar", color = GoldP, fontSize = 12.sp, modifier = Modifier.clickable { onClose() }.padding(4.dp))
            }

            // ---- Kursi pemain di atas meja
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf("MC", "No.2", "No.3", "No.4", "No.5").forEach { label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF2C1B42))
                                .border(1.dp, Color(0xFF5E3A8C), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🪑", fontSize = 12.sp)
                        }
                        Text(label, color = Color(0xFF9E86C4), fontSize = 8.sp)
                        Text("0", color = Color(0xFFFF79B0), fontSize = 8.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ---- Judul + hitung mundur
            Text(
                "3 CARD POKER",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF1E4FD8), Color(0xFF6A3DF0))))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (dealt) "membuka kartu…" else "hitung mundur: " + left + "s",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ---- Riwayat hasil
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Last", color = Color(0xFFB39DDB), fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                history.take(8).forEach { h ->
                    Box(
                        Modifier.size(width = 14.dp, height = 18.dp).clip(RoundedCornerShape(3.dp))
                            .background(
                                when (h) {
                                    0 -> Color(0xFFB0BEC5)
                                    1 -> Color(0xFFC62828)
                                    else -> Color(0xFF2E7D32)
                                },
                            ),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("lainnya ›", color = Color(0xFFB39DDB), fontSize = 10.sp)
            }

            Spacer(Modifier.height(10.dp))

            // ---- Tiga kolom perisai
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val lanes = listOf("🐺" to "Wolf", "🦁" to "Lion", "🐟" to "Fish")
                lanes.forEachIndexed { idx, (shield, label) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF231234).copy(alpha = 0.9f))
                            .border(
                                if (stakes[idx] > 0L) 2.dp else 1.dp,
                                if (stakes[idx] > 0L) GoldP else Color(0xFF4A2E6B),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { placeLane(idx) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(3) {
                                Box(
                                    Modifier.size(width = 20.dp, height = 28.dp).clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF2B2B6B)).border(1.dp, Color(0xFF5C5CD6), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("🂠", color = Color(0xFF8E8EF0), fontSize = 12.sp) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (dealt) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                hands.getOrNull(idx)?.take(3)?.forEach { c ->
                                    Box(
                                        Modifier.size(width = 20.dp, height = 28.dp).clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFF4F4F4)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            c,
                                            color = if (c.contains("♥") || c.contains("♦")) Color(0xFFC62828) else Color(0xFF151515),
                                            fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text("Pot " + fmtP(pot.getOrElse(idx) { 0L }), color = GoldP, fontSize = 10.sp)
                            Text(
                                "Me " + fmtP(stakes[idx]),
                                color = Color(0xFFB39DDB), fontSize = 9.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(shield, fontSize = 30.sp)
                        Text(label, color = Color(0xFFD6C7EE), fontSize = 9.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                msg, color = Color(0xFFE0D6F5), fontSize = 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )

            Spacer(Modifier.weight(1f))

            // ---- Dompet + chip
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🪙 " + fmtP(balance), color = GoldP, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChipColors.forEach { (v, c) ->
                    val on = bet == v
                    Box(
                        Modifier.weight(1f).height(30.dp).clip(CircleShape)
                            .background(if (on) c else c.copy(alpha = 0.65f))
                            .border(if (on) 2.dp else 1.dp, if (on) GoldP else Color(0xFF3A2352), CircleShape)
                            .clickable { bet = v },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (v >= 1000) (v / 1000).toString() + "K" else v.toString(),
                            color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
