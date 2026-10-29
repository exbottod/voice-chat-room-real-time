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
import kotlin.random.Random

/**
 * Dragon Vs Tiger buatan sendiri. Digambar langsung oleh aplikasi (bukan halaman
 * web), jadi jalan di Android 8 ke atas tanpa bergantung pada server game.
 * Coin dipotong dan dibayar lewat wallet-api Supabase (waveFlow).
 */

private val Gold2 = Color(0xFFF5C542)

private data class Card(val rank: String, val value: Int, val suit: String)

private fun drawCard(): Card {
    val ranks = listOf(
        "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7, "8" to 8,
        "9" to 9, "10" to 10, "J" to 11, "Q" to 12, "K" to 13, "A" to 14,
    )
    val (r, v) = ranks[Random.nextInt(ranks.size)]
    return Card(r, v, listOf("♠", "♥", "♦", "♣")[Random.nextInt(4)])
}

@Composable
fun DragonTigerScreen(
    myUid: String,
    onClose: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var balance by remember { mutableStateOf(0L) }
    var bet by remember { mutableStateOf(1000L) }
    var pick by remember { mutableStateOf("") }        // DRAGON / TIGER / TIE
    var staked by remember { mutableStateOf(0L) }
    var dragon by remember { mutableStateOf<Card?>(null) }
    var tiger by remember { mutableStateOf<Card?>(null) }
    var left by remember { mutableStateOf(20) }
    var msg by remember { mutableStateOf("Pilih Dragon, Tiger, atau Tie lalu tunggu kartu dibuka") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        SupaRepo.wavePrepare("Player", "")
        balance = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    // Hitung mundur per ronde.
    LaunchedEffect(dragon, tiger) {
        if (dragon == null && tiger == null) {
            left = 20
            while (left > 0) {
                delay(1000)
                left -= 1
            }
            if (!busy) {
                val d = drawCard()
                val t = drawCard()
                dragon = d
                tiger = t
                val win = when {
                    d.value == t.value -> "TIE"
                    d.value > t.value -> "DRAGON"
                    else -> "TIGER"
                }
                if (staked > 0L) {
                    val payout = when {
                        pick == "TIE" && win == "TIE" -> staked * 22
                        pick == win -> staked * 2
                        else -> 0L
                    }
                    if (payout > 0L) {
                        val nb = SupaRepo.waveFlow(payout, 2, UUID.randomUUID().toString(), "dragon-tiger")
                        if (nb != null) balance = nb
                        msg = "Kamu menang " + fmt(payout) + " coin!"
                    } else {
                        msg = if (win == "TIE") "Seri. Taruhan hangus." else "Kalah. Coba lagi."
                    }
                } else {
                    msg = "Hasil: " + win
                }
                delay(4500)
                dragon = null
                tiger = null
                staked = 0L
                pick = ""
                msg = "Ronde baru. Pilih Dragon, Tiger, atau Tie."
            }
        }
    }

    fun place(side: String) {
        if (busy || dragon != null) return
        if (bet > balance) {
            msg = "Coin tidak cukup"
            return
        }
        busy = true
        scope.launch {
            val tx = UUID.randomUUID().toString()
            val nb = SupaRepo.waveFlow(bet, 1, tx, "dragon-tiger")
            if (nb == null) {
                msg = "Gagal memotong coin, coba lagi"
            } else {
                balance = nb
                staked = bet
                pick = side
                msg = "Taruhan " + fmt(bet) + " coin di " + side
            }
            busy = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14121A), Color(0xFF0A0A0C)))),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1B1B22)).padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹ Kembali", color = Gold2, fontSize = 15.sp, modifier = Modifier.clickable { onClose() }.padding(6.dp))
                Spacer(Modifier.width(8.dp))
                Text("Dragon Vs Tiger", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(fmt(balance) + " coin", color = Gold2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardBox(Modifier.weight(1f), dragon, "DRAGON", Color(0xFF8E1B1B))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2A2118))
                            .border(2.dp, Gold2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (dragon == null) left.toString() else "•", color = Gold2, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                CardBox(Modifier.weight(1f), tiger, "TIGER", Color(0xFF17356B))
            }

            Text(
                msg,
                color = Color(0xFFCFCFCF),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SideButton("DRAGON", Color(0xFF8E1B1B), pick == "DRAGON", Modifier.weight(1f)) { place("DRAGON") }
                SideButton("TIE x22", Color(0xFF6B4A16), pick == "TIE", Modifier.weight(0.8f)) { place("TIE") }
                SideButton("TIGER", Color(0xFF17356B), pick == "TIGER", Modifier.weight(1f)) { place("TIGER") }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(500L, 1000L, 5000L, 10000L).forEach { v ->
                    val on = bet == v
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Gold2 else Color(0xFF23232B))
                            .clickable { bet = v }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(fmt(v), color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardBox(mod: Modifier, card: Card?, label: String, tint: Color) {
    Column(
        mod.clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.35f)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp))
                .background(if (card == null) Color(0xFF262630) else Color(0xFFF6F6F6)),
            contentAlignment = Alignment.Center,
        ) {
            if (card == null) {
                Text("🂠", color = Color(0xFF4A4A55), fontSize = 34.sp)
            } else {
                Text(
                    card.rank + card.suit,
                    color = if (card.suit == "♥" || card.suit == "♦") Color(0xFFC62828) else Color(0xFF111111),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SideButton(text: String, color: Color, selected: Boolean, mod: Modifier, onClick: () -> Unit) {
    Box(
        mod.clip(RoundedCornerShape(12.dp))
            .background(if (selected) color else color.copy(alpha = 0.55f))
            .border(if (selected) 2.dp else 0.dp, Gold2, RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun fmt(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
        out.append(c)
    }
    return out.toString()
}
