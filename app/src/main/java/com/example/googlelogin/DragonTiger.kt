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
    var lastWin by remember { mutableStateOf(0L) }

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
                        pick == "TIE" && win == "TIE" -> staked * 45
                        pick == win -> staked * 2
                        else -> 0L
                    }
                    if (payout > 0L) {
                        val nb = SupaRepo.waveFlow(payout, 2, UUID.randomUUID().toString(), "dragon-tiger")
                        if (nb != null) balance = nb
                        lastWin = payout
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

    val history = remember { mutableStateOf(listOf("DRAGON", "TIGER", "DRAGON", "DRAGON", "TIGER", "DRAGON", "DRAGON", "TIGER")) }
    var pots by remember { mutableStateOf(listOf(0L, 0L, 0L)) }
    LaunchedEffect(dragon, tiger) {
        val d = dragon
        val t = tiger
        if (d != null && t != null) {
            val w = when {
                d.value == t.value -> "TIE"
                d.value > t.value -> "DRAGON"
                else -> "TIGER"
            }
            history.value = (listOf(w) + history.value).take(8)
            pots = listOf(0L, 0L, 0L)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            // latar: kiri merah (Naga), kanan biru (Tiger)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF8A1608), Color(0xFF3C0707)))))
                Box(Modifier.weight(1f).fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B2C86), Color(0xFF060F36)))))
            }
            Column(Modifier.fillMaxSize()) {
                // bilah atas
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("‹ Kembali", color = Gold2, fontSize = 14.sp, modifier = Modifier.clickable { onClose() }.padding(4.dp))
                    Spacer(Modifier.weight(1f))
                    Text("Dragon and Tiger Slot", color = Gold2, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("win: " + fmt(lastWin), color = Color(0xFFFFE082), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // hitung mundur + dua kartu
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MiniCard(dragon, Color(0xFF9C1C12))
                        Text("Naga", color = Color(0xFFFFB199), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("hitung mundur", color = Color(0xFFFFE082), fontSize = 10.sp)
                        Text(
                            if (dragon == null) left.toString() + "s" else "•",
                            color = Gold2, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MiniCard(tiger, Color(0xFF1B3FA8))
                        Text("Tiger", color = Color(0xFF9CC2FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // riwayat
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Last", color = Color(0xFFE0E0E0), fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    history.value.forEach { h ->
                        Box(
                            Modifier.padding(end = 5.dp).size(22.dp).clip(CircleShape)
                                .background(
                                    when (h) {
                                        "DRAGON" -> Color(0xFFD84315)
                                        "TIGER" -> Color(0xFF1E5BDB)
                                        else -> Color(0xFF8D6E1B)
                                    },
                                )
                                .border(1.dp, Color(0xFFFFE082), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when (h) { "DRAGON" -> "N"; "TIGER" -> "T"; else -> "S" },
                                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("lainnya ›", color = Color(0xFFFFE082), fontSize = 10.sp)
                }

                // tiga kolom: Naga / Seri / Tiger
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DtLane("Naga", "x2.0", pots[0], if (pick == "DRAGON") staked else 0L, Color(0xFF8E1B1B), pick == "DRAGON", Modifier.weight(1f)) { place("DRAGON") }
                    DtLane("Seri", "x45.0", pots[1], if (pick == "TIE") staked else 0L, Color(0xFF7A4A12), pick == "TIE", Modifier.weight(1f)) { place("TIE") }
                    DtLane("Tiger", "x2.0", pots[2], if (pick == "TIGER") staked else 0L, Color(0xFF14398F), pick == "TIGER", Modifier.weight(1f)) { place("TIGER") }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    msg, color = Color(0xFFFFE9C7), fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )

                Spacer(Modifier.weight(1f))

                // saldo + chip
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xFF3A0808))))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("Saldo koin saya:", color = Color(0xFFFFE082), fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🪙 " + balance.toString(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        listOf(500L to Color(0xFFB8860B), 1000L to Color(0xFF6A1B9A), 5000L to Color(0xFF1E88E5), 10000L to Color(0xFFD84315)).forEach { (v, c) ->
                            val on = bet == v
                            Box(
                                Modifier.padding(start = 6.dp).size(44.dp).clip(CircleShape)
                                    .background(if (on) c else c.copy(alpha = 0.65f))
                                    .border(if (on) 3.dp else 1.dp, if (on) Gold2 else Color(0xFFFFE9C7), CircleShape)
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
                }
            }
        }
    }
}

@Composable
private fun MiniCard(card: Card?, back: Color) {
    Box(
        Modifier.size(width = 52.dp, height = 70.dp).clip(RoundedCornerShape(8.dp))
            .background(if (card == null) back else Color(0xFFF6F6F6))
            .border(2.dp, Color(0xFFFFE082), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (card == null) {
            Text("♦", color = Color(0xFFFFE082), fontSize = 22.sp)
        } else {
            Text(
                card.rank + card.suit,
                color = if (card.suit == "♥" || card.suit == "♦") Color(0xFFC62828) else Color(0xFF111111),
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DtLane(
    title: String, mult: String, pot: Long, mine: Long, tint: Color, selected: Boolean,
    mod: Modifier, onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = mod) {
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.55f))
                .border(if (selected) 3.dp else 2.dp, if (selected) Color(0xFFFFF59D) else Color(0xFFE0A93B), RoundedCornerShape(10.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(50))
                        .background(Color(0xFF7A1010)).border(1.dp, Color(0xFFE0A93B), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) { Text(mult, color = Color(0xFFFFE082), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text("Pot:" + pot, color = Color(0xFFE0E0E0), fontSize = 10.sp)
        Text("Me:" + mine, color = Color(0xFFE0E0E0), fontSize = 10.sp)
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
