package com.example.googlelogin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.width
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
import java.util.UUID
import kotlin.random.Random

/**
 * Kumpulan game REZO. Semua digambar langsung oleh aplikasi (Compose), tanpa
 * halaman web dan tanpa server game pihak ketiga, jadi jalan di Android 8 ke atas.
 * Coin dipotong/dibayar lewat wallet-api Supabase (SupaRepo.waveFlow).
 */

private val GoldG = Color(0xFFF5C542)

private fun fmtG(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
        out.append(c)
    }
    return out.toString()
}

/** Halaman daftar game. */
@Composable
fun GamesHub(myUid: String, roomId: String, myName: String = "", onClose: () -> Unit) {
    var open by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        SupaRepo.wavePrepare("Player", "")
        balance = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    when (open) {
        "slot777" -> Slot777HtmlScreen(myUid = myUid, myName = myName, onClose = { open = "" })
        "greedybaby" -> GreedyBabyHtmlScreen(myUid = myUid, myName = myName.ifBlank { "Player" }, onClose = { open = "" })
        "fruitsparty" -> FruitsPartyHtmlScreen(myUid = myUid, myName = myName.ifBlank { "Player" }, onClose = { open = "" })
    "dvt" -> DragonTigerScreen(myUid = myUid, myName = myName, onClose = { open = "" })
        "lucky77" -> Lucky77HtmlScreen(myUid = myUid, myName = myName, onClose = { open = "" })
        "poker" -> ThreeCardPokerScreen(myUid = myUid, myName = myName, myAvatarB64 = "", onClose = { open = "" })
        "greedy" -> GreedyCatScreen(myUid = myUid, myName = myName.ifBlank { "Player" }, onClose = { open = "" })
        "teenpatti" -> TeenPattiScreen(myUid = myUid, onClose = { open = "" })
        else -> Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            // Bottom sheet "Game Center": area atas tembus pandang, sheet coklat di bawah.
            Box(Modifier.fillMaxSize().clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { onClose() }) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxSize(0.62f)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF4A2F1B), Color(0xFF2B190E))))
                        .border(1.dp, Color(0xFF8A5A2B), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) { },
                ) {
                    // hiasan puncak sheet
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        Text("◆", color = Color(0xFFE0553A), fontSize = 12.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("〰", color = Color(0xFFB08A5A), fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("Game Center", color = Color(0xFFF0DDBF), fontSize = 17.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("〰", color = Color(0xFFB08A5A), fontSize = 14.sp)
                    }

                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // ---- Dimainkan terkini
                        GcSection("Dimainkan terkini") {
                            GcRow(
                                listOf(
                                    GcItem("DragonTiger", R.drawable.ic_game_dvt, "dvt"),
                                    GcItem("TeenPatti", R.drawable.ic_game_poker, "teenpatti"),
                                    GcItem("Lucky77", R.drawable.ic_game_lucky, "lucky77"),
                                    GcItem("GreedyCat", R.drawable.ic_game_cat, "greedy"),
                                    GcItem("Slot777", R.drawable.ic_game_slot, "slot777"),
                                    GcItem("GreedyBaby", R.drawable.ic_game_baby, "greedybaby"),
                                    GcItem("FruitsParty", R.drawable.ic_game_fruits, "fruitsparty"),
                                ),
                            ) { open = it }
                        }

                        // ---- Rekomendasi game
                        GcSection("Rekomendasi game") {
                            GcRow(
                                listOf(
                                    GcItem("DragonTigerPk", R.drawable.ic_game_dvt, "dvt"),
                                    GcItem("3CardPoker", R.drawable.ic_game_poker, "poker"),
                                    GcItem("Lucky77", R.drawable.ic_game_lucky, "lucky77"),
                                ),
                            ) { open = it }
                            Spacer(Modifier.height(8.dp))
                            GcRow(
                                listOf(
                                    GcItem("TeenPatti", R.drawable.ic_game_poker, "teenpatti"),
                                    GcItem("GreedyCat", R.drawable.ic_game_cat, "greedy"),
                                    GcItem("GreedyBaby", R.drawable.ic_game_baby, "greedybaby"),
                                    GcItem("FruitsParty", R.drawable.ic_game_fruits, "fruitsparty"),
                                ),
                            ) { open = it }
                        }

                        // ---- Daftar peringkat
                        Box(
                            Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(14.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF9EE0E8), Color(0xFFB9A8F0), Color(0xFFA8E6C8)))),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                "Daftar peringkat", color = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, bottom = 28.dp),
                            )
                            Row(Modifier.padding(start = 90.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("1", "2", "3").forEach { n ->
                                    Box(
                                        Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(Color(0xFF2E7D32))
                                            .border(2.dp, Color(0xFFFFE082), androidx.compose.foundation.shape.CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(n, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                        Spacer(Modifier.height(70.dp))
                    }
                }

                // ---- bilah bawah: game yang sedang dipakai
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF4A2F1B))
                        .border(2.dp, Color(0xFFB07A3A), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    listOf(
                        GcItem("Greedy", R.drawable.ic_game_cat, "greedy"),
                        GcItem("Lucky77", R.drawable.ic_game_lucky, "lucky77"),
                    ).forEach { it2 ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { open = it2.key },
                        ) {
                            Image(
                                painterResource(it2.icon), contentDescription = it2.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Text(it2.name, color = Color(0xFFD9C3A0), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private data class GcItem(val name: String, val icon: Int, val key: String)

@Composable
private fun GcSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF5A3C27)).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(title, color = Color(0xFFF0DDBF), fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun GcRow(items: List<GcItem>, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { g ->
            Column(
                Modifier.weight(1f).clickable { onPick(g.key) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                        .border(2.dp, Color(0xFFE0B54A), RoundedCornerShape(16.dp)),
                ) {
                    Image(
                        painterResource(g.icon), contentDescription = g.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(g.name, color = Color(0xFFF0DDBF), fontSize = 11.sp, maxLines = 1)
            }
        }
        // isi sisa kolom supaya ukuran ikon sama
        repeat(4 - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun GameTile(title: String, sub: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.75f))
            .border(1.dp, GoldG.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Color(0xFFE0E0E0), fontSize = 12.sp)
        }
        Text("Main ›", color = GoldG, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Slot 3 gulungan, dipakai untuk Lucky 77, Greedy King, dan Slot Buah. */
@Composable
fun SlotGameScreen(
    myUid: String,
    title: String,
    symbols: List<String>,
    onClose: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var balance by remember { mutableStateOf(0L) }
    var bet by remember { mutableStateOf(1000L) }
    var reels by remember { mutableStateOf(listOf(symbols[0], symbols[1], symbols[2])) }
    var msg by remember { mutableStateOf("Tekan SPIN untuk mulai") }
    var spinning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        balance = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    fun spin() {
        if (spinning) return
        if (bet > balance) {
            msg = "Coin tidak cukup"
            return
        }
        spinning = true
        scope.launch {
            val nb = SupaRepo.waveFlow(bet, 1, UUID.randomUUID().toString(), title)
            if (nb == null) {
                msg = "Gagal memotong coin, coba lagi"
                spinning = false
                return@launch
            }
            balance = nb
            repeat(8) {
                reels = listOf(
                    symbols[Random.nextInt(symbols.size)],
                    symbols[Random.nextInt(symbols.size)],
                    symbols[Random.nextInt(symbols.size)],
                )
                kotlinx.coroutines.delay(90)
            }
            val r = reels
            val winCount = if (r[0] == r[1] && r[1] == r[2]) 3 else if (r[0] == r[1] || r[1] == r[2] || r[0] == r[2]) 2 else 0
            val payout = when (winCount) {
                3 -> bet * 5
                2 -> bet * 2
                else -> 0L
            }
            if (payout > 0L) {
                val after = SupaRepo.waveFlow(payout, 2, UUID.randomUUID().toString(), title)
                if (after != null) balance = after
                msg = "Menang " + fmtG(payout) + " coin!"
            } else {
                msg = "Belum beruntung, coba lagi"
            }
            spinning = false
        }
    }

    GameScaffold(title, balance, onClose) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            reels.forEach { sym ->
                Box(
                    Modifier.weight(1f).height(110.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E1E27)).border(2.dp, GoldG.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(sym, fontSize = 40.sp, color = Color.White)
                }
            }
        }
        Text(msg, color = Color(0xFFCFCFCF), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500L, 1000L, 5000L, 10000L).forEach { v ->
                val on = bet == v
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (on) GoldG else Color(0xFF23232B)).clickable { bet = v }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(fmtG(v), color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (spinning) Color(0xFF3A3A44) else Color(0xFFE2A21B))
                .clickable { spin() }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (spinning) "Berputar…" else "SPIN", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Teen Patti sederhana: kartu pemain lawan 3 bot, menang bila tangan tertinggi. */
@Composable
fun TeenPattiScreen(myUid: String, onClose: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var balance by remember { mutableStateOf(0L) }
    var bet by remember { mutableStateOf(1000L) }
    var chips by remember { mutableStateOf(listOf(500L, 1000L, 5000L)) }
    var mine by remember { mutableStateOf(listOf<String>()) }
    var bots by remember { mutableStateOf(listOf(listOf<String>(), listOf<String>(), listOf<String>())) }
    var msg by remember { mutableStateOf("Tekan MAIN untuk membagi kartu") }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = BetCfg.load("teenpatti", listOf(500L, 1000L, 5000L))
        chips = list
        if (list.none { it == bet }) bet = list.first()
        balance = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    fun play() {
        if (playing) return
        if (bet > balance) {
            msg = "Coin tidak cukup"
            return
        }
        playing = true
        scope.launch {
            val nb = SupaRepo.waveFlow(bet, 1, UUID.randomUUID().toString(), "Teen Patti")
            if (nb == null) {
                msg = "Gagal memotong coin, coba lagi"
                playing = false
                return@launch
            }
            balance = nb
            val deck = tpDeck().shuffled()
            mine = deck.take(3)
            bots = listOf(deck.subList(3, 6), deck.subList(6, 9), deck.subList(9, 12))
            val myScore = tpScore(mine)
            val botScores = bots.map { tpScore(it) }
            val best = botScores.maxOrNull() ?: 0
            if (myScore > best) {
                val payout = bet * 3
                val after = SupaRepo.waveFlow(payout, 2, UUID.randomUUID().toString(), "Teen Patti")
                if (after != null) balance = after
                msg = "Kamu menang " + fmtG(payout) + " coin!"
            } else if (myScore == best) {
                val refund = SupaRepo.waveFlow(bet, 2, UUID.randomUUID().toString(), "Teen Patti")
                if (refund != null) balance = refund
                msg = "Seri, taruhan dikembalikan"
            } else {
                msg = "Kalah, coba lagi"
            }
            playing = false
        }
    }

    GameScaffold("Teen Patti", balance, onClose) {
        Text("Kartu kamu", color = GoldG, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mine.isEmpty()) {
                repeat(3) { BackCard() }
            } else {
                mine.forEach { c -> CardChip(c) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Bot", color = Color(0xFFBDBDBD), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        bots.forEachIndexed { i, b ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bot " + (i + 1) + "  ", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                if (b.isEmpty()) repeat(3) { BackCard(small = true) } else b.forEach { c -> CardChip(c, small = true) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(msg, color = Color(0xFFCFCFCF), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { v ->
                val on = bet == v
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (on) GoldG else Color(0xFF23232B)).clickable { bet = v }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(fmtG(v), color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (playing) Color(0xFF3A3A44) else Color(0xFF2E7D32))
                .clickable { play() }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (playing) "Membagi kartu…" else "MAIN", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GameScaffold(title: String, balance: Long, onClose: () -> Unit, body: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14121A), Color(0xFF07070A))))) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1B1B22)).padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹ Kembali", color = GoldG, fontSize = 15.sp, modifier = Modifier.clickable { onClose() }.padding(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(fmtG(balance) + " coin", color = GoldG, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                body()
            }
        }
    }
}

@Composable
private fun BackCard(small: Boolean = false) {
    Box(
        Modifier.width(if (small) 34.dp else 56.dp).height(if (small) 48.dp else 78.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A36))
            .border(1.dp, Color(0xFF44445A), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("🂠", color = Color(0xFF6A6A80), fontSize = if (small) 16.sp else 24.sp)
    }
}

@Composable
private fun CardChip(card: String, small: Boolean = false) {
    val red = card.contains("♥") || card.contains("♦")
    Box(
        Modifier.width(if (small) 34.dp else 56.dp).height(if (small) 48.dp else 78.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF7F7F7)),
        contentAlignment = Alignment.Center,
    ) {
        Text(card, color = if (red) Color(0xFFC62828) else Color(0xFF111111), fontSize = if (small) 14.sp else 20.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun tpDeck(): List<String> {
    val suits = listOf("♠", "♥", "♦", "♣")
    val ranks = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A")
    val out = ArrayList<String>()
    for (s in suits) for (r in ranks) out.add(r + s)
    return out
}

/** Nilai tangan Teen Patti: 6 trail, 5 pure sequence, 4 sequence, 3 warna, 2 pair, 1 kartu tertinggi. */
internal fun tpScore(cards: List<String>): Int {
    val vals = cards.map { c ->
        when (c.dropLast(1)) {
            "A" -> 14; "K" -> 13; "Q" -> 12; "J" -> 11
            else -> c.dropLast(1).toIntOrNull() ?: 0
        }
    }.sortedDescending()
    val suits = cards.map { it.last() }
    val sameSuit = suits.distinct().size == 1
    val trail = vals[0] == vals[1] && vals[1] == vals[2]
    val seq = (vals[0] - vals[1] == 1) && (vals[1] - vals[2] == 1)
    val high = vals[0] * 1000 + vals[1] * 10 + vals[2]
    return when {
        trail -> 60000 + vals[0]
        seq && sameSuit -> 50000 + high
        seq -> 40000 + high
        sameSuit -> 30000 + high
        vals[0] == vals[1] || vals[1] == vals[2] -> 20000 + high
        else -> 10000 + high
    }
}
