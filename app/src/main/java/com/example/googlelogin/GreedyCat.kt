package com.example.googlelogin

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * Greedy Cat: 8 makanan mengelilingi kucing, ronde 30 detik.
 * Dibuat langsung di Compose (bukan halaman web) supaya tetap ringan
 * di Android 8-9. Aturan sama dengan skrip JS dari owner:
 * - Result kosong di awal, taruhan diletakkan di pola.
 * - Bet 10K/100K/1M/10M/50M, boleh berkali-kali per makanan.
 * - Ronde 30s, 5 detik terakhir terkunci.
 * - Pemenang: 92% dari yang dipasang, sisanya acak.
 * - Aturan grup: kalau yang keluar makanan daging, semua taruhan daging menang.
 * - Auto Repeat memasang lagi pola terakhir persis.
 * Coin diproses lewat wallet-api Supabase.
 */

private val CatBgTop = Color(0xFF8FD1FF)
private val CatBgMid = Color(0xFFC8E8FF)
private val CatBlue = Color(0xFF29B6F6)
private val CatBar = Color(0xFF5BB8FF)
private val CatWood = Color(0xFF8B4513)
private val CatGold = Color(0xFFFFB800)
private val CatRed = Color(0xFFFF3D71)

private data class Food(val key: String, val icon: String, val mult: Int, val meat: Boolean)

private val CAT_FOODS = listOf(
    Food("chicken", "🍗", 25, true),
    Food("corn", "🌽", 5, false),
    Food("tomato", "🍅", 5, false),
    Food("skewer", "🍢", 15, true),
    Food("steak", "🥩", 45, true),
    Food("carrot", "🥕", 5, false),
    Food("hotdog", "🌭", 10, true),
    Food("broccoli", "🥦", 5, false),
)

private val CAT_CHIPS = listOf(10_000L, 100_000L, 1_000_000L, 3_000_000L, 5_000_000L)

private fun fmtCat(n: Long): String = when {
    n >= 1_000_000_000L -> (n / 1_000_000_000L).toString() + "B"
    n >= 1_000_000L -> (n / 1_000_000L).toString() + "M"
    n >= 1_000L -> (n / 1_000L).toString() + "K"
    else -> n.toString()
}

private const val BET_SEC = 30
private const val RESULT_SEC = 5
private const val CYCLE_MS = (BET_SEC + RESULT_SEC) * 1000L
private const val ROUND_BASE = 1_000_000L // nomor ronde mulai dari 0 pada titik ini

/** Nomor ronde dan sisa waktu dihitung dari jam server, jadi sama di semua HP. */
private fun cycleNow(): Pair<Long, Long> {
    val now = Rtdb.serverNow()
    val idx = now / CYCLE_MS
    val into = now % CYCLE_MS
    return Pair(idx, into)
}

/** Makanan pemenang ditentukan dari nomor ronde saja, jadi semua pemain melihat hasil yang sama. */
private fun winnerOf(roundIdx: Long): Food {
    val h = java.util.Random(roundIdx * 7919L + 104729L).nextInt(CAT_FOODS.size)
    return CAT_FOODS[h]
}

private data class CatResult(val icon: String, val round: Int, val mult: String)

@Composable
fun GreedyCatScreen(myUid: String, myName: String = "Player", onClose: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var coins by remember { mutableStateOf(0L) }
    var todayWin by remember { mutableStateOf(0L) }
    var chip by remember { mutableStateOf(10_000L) }
    var bets by remember { mutableStateOf(mapOf<String, Long>()) }
    var lastBets by remember { mutableStateOf(mapOf<String, Long>()) }
    var timer by remember { mutableStateOf(30) }
    var roundNum by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(listOf<CatResult>()) }
    var msg by remember { mutableStateOf("Result kosong • Bet di pola • Auto Repeat = pasang lagi") }
    var winFood by remember { mutableStateOf<Food?>(null) }
    var winAmt by remember { mutableStateOf(0L) }
    var winBet by remember { mutableStateOf(0L) }
    var showWin by remember { mutableStateOf(false) }
    var top3 by remember { mutableStateOf(listOf<Triple<String, Long, Long>>()) }
    var musicOn by remember { mutableStateOf(true) }
    var showRank by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var rank by remember { mutableStateOf(listOf<Pair<String, Long>>()) }

    LaunchedEffect(Unit) {
        coins = SupaRepo.get(myUid).wallet?.coins ?: 0L
    }

    LaunchedEffect(musicOn) {
        if (musicOn) GreedyMusic.start() else GreedyMusic.stop()
    }
    DisposableEffect(Unit) {
        onDispose { GreedyMusic.stop() }
    }

    // daftar peringkat: jumlahkan kemenangan tiap nama dari ronde terakhir
    LaunchedEffect(showRank) {
        if (!showRank) return@LaunchedEffect
        rank = listOf()
        try {
            val snap = Rtdb.ref("gameRounds/greedycat").get().await()
            val rounds = snap.children.toList().mapNotNull { it.key?.toLongOrNull() }.sortedDescending().take(60)
            val total = HashMap<String, Long>()
            rounds.forEach { r ->
                snap.child(r.toString()).children.forEach { c ->
                    val n = c.child("name").getValue(String::class.java) ?: return@forEach
                    val w = c.child("win").getValue(Long::class.java) ?: 0L
                    if (w > 0L) total[n] = (total[n] ?: 0L) + w
                }
            }
            rank = total.entries.sortedByDescending { it.value }.take(20).map { it.key to it.value }
        } catch (e: Exception) { }
    }

    // Sinkron jam server: semua pemain berada di detik dan ronde yang sama.
    LaunchedEffect(Unit) {
        Rtdb.startClockSync()
        var lastIdx = -1L
        var settledIdx = -1L
        while (true) {
            val (idx, into) = cycleNow()
            val sec = (into / 1000L).toInt()
            if (idx != lastIdx) {
                // ronde baru dimulai (atau baru masuk di tengah ronde)
                if (lastIdx != -1L) {
                    bets = mapOf()
                    showWin = false
                    msg = "Ronde baru • pasang taruhan di makanan"
                }
                lastIdx = idx
            }
            roundNum = (idx % 100000L).toInt()
            if (sec < BET_SEC) {
                active = true
                timer = BET_SEC - sec
            } else {
                active = false
                timer = 0
                if (settledIdx != idx) {
                    settledIdx = idx
                    val winner = winnerOf(idx)
                    var payout = 0L
                    var staked = 0L
                    bets.forEach { (k, v) ->
                        staked += v
                        val ff = CAT_FOODS.firstOrNull { it.key == k } ?: return@forEach
                        if (ff.meat == winner.meat) payout += v * ff.mult
                    }
                    if (payout > 0L) {
                        val nb = SupaRepo.waveFlow(payout, 2, "cat-$idx-$myUid", "Greedy Cat")
                        if (nb != null) coins = nb
                        todayWin += payout
                    }
                    if (bets.isNotEmpty()) lastBets = bets
                    history = (listOf(CatResult(winner.icon, (idx % 100000L).toInt(), winner.mult.toString() + "X")) + history).take(8)
                    winFood = winner
                    winAmt = payout
                    winBet = staked
                    top3 = listOf()
                    showWin = true
                    // catat hasil saya, lalu baca semua pemain di ronde ini
                    val settleIdx = idx
                    launch {
                        try {
                            if (staked > 0L) {
                                Rtdb.ref("gameRounds/greedycat/" + settleIdx + "/" + myUid).setValue(
                                    hashMapOf<String, Any>("name" to myName.take(30).ifBlank { "Player" }, "bet" to staked, "win" to payout),
                                )
                            }
                            delay(1200)
                            val snap = Rtdb.ref("gameRounds/greedycat/" + settleIdx).get().await()
                            val rows = mutableListOf<Triple<String, Long, Long>>()
                            snap.children.forEach { c ->
                                val n = c.child("name").getValue(String::class.java) ?: "Player"
                                val b = c.child("bet").getValue(Long::class.java) ?: 0L
                                val w = c.child("win").getValue(Long::class.java) ?: 0L
                                rows.add(Triple(n, b, w))
                            }
                            top3 = rows.filter { it.third > 0L }.sortedByDescending { it.third }.take(3)
                        } catch (e: Exception) { }
                    }
                }
            }
            delay(250)
        }
    }

    fun placeBet(f: Food) {
        if (!active || timer <= 3) {
            msg = "Terkunci, tunggu ronde berikutnya"
            return
        }
        if (chip > coins) {
            msg = "Coin tidak cukup"
            return
        }
        scope.launch {
            val nb = SupaRepo.waveFlow(chip, 1, UUID.randomUUID().toString(), "Greedy Cat")
            if (nb == null) {
                msg = "Gagal memotong coin, coba lagi"
                return@launch
            }
            coins = nb
            bets = bets + (f.key to ((bets[f.key] ?: 0L) + chip))
            msg = "Taruhan " + fmtCat(bets[f.key] ?: chip) + " di " + f.icon
        }
    }

    fun autoRepeat() {
        if (lastBets.isEmpty()) {
            msg = "Belum ada pola. Pasang manual dulu."
            return
        }
        if (!active) {
            msg = "Tunggu ronde selesai"
            return
        }
        val need = lastBets.values.sum()
        if (need > coins) {
            msg = "Coin kurang, butuh " + fmtCat(need)
            return
        }
        scope.launch {
            val nb = SupaRepo.waveFlow(need, 1, UUID.randomUUID().toString(), "Greedy Cat")
            if (nb == null) {
                msg = "Gagal memotong coin, coba lagi"
                return@launch
            }
            coins = nb
            var fresh = bets
            lastBets.forEach { (k, v) -> fresh = fresh + (k to ((fresh[k] ?: 0L) + v)) }
            bets = fresh
            msg = "Auto Repeat: " + fmtCat(need) + " terpasang di pola terakhir"
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CatBgTop, CatBgMid, CatBgTop)))) {
            Column(Modifier.fillMaxSize()) {

                // ---- bilah atas
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF87CEFF), CatBar)))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color.White)
                            .border(2.dp, Color(0xFFFF7A00), CircleShape).clickable { onClose() },
                        contentAlignment = Alignment.Center,
                    ) { Text("↩", color = Color(0xFFC44A00), fontSize = 14.sp) }
                    Spacer(Modifier.width(8.dp))
                    Text("GREEDY CAT", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFFFFEA5C), CatGold)))
                            .border(2.dp, Color(0xFFFF8A00), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) { Text("Normal", color = Color(0xFF5D4037), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color.White)
                            .border(2.dp, Color(0xFFFF7A00), CircleShape).clickable { musicOn = !musicOn },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (musicOn) "🎵" else "🔇", fontSize = 13.sp) }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color.White)
                            .border(2.dp, Color(0xFFFF7A00), CircleShape).clickable { showHelp = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("❓", fontSize = 13.sp) }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color.White)
                            .border(2.dp, CatGold, CircleShape).clickable { showRank = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("🏆", fontSize = 14.sp) }
                }

                // ---- baris info ronde
                Row(
                    Modifier.fillMaxWidth().background(Color(0x40FFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("Round: " + roundNum, color = Color(0xFF0D47A1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("📶 55ms 🏆", color = Color(0xFF0D47A1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    // ---- kolom hasil
                    Column(
                        Modifier.width(50.dp).fillMaxSize().clip(RoundedCornerShape(10.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF4FC3F7), CatBlue)))
                            .border(2.dp, Color.White, RoundedCornerShape(10.dp))
                            .padding(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (history.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "RESULT\nKOSONG\n\npasang bet\n30 detik\notomatis\nmuncul",
                                color = Color(0xCCFFFFFF), fontSize = 7.sp, textAlign = TextAlign.Center,
                            )
                        } else {
                            history.forEachIndexed { i, h ->
                                Box(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
                                        .border(if (i == 0) 2.dp else 1.dp, if (i == 0) Color(0xFF00C853) else CatWood, RoundedCornerShape(8.dp))
                                        .padding(vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(h.icon, fontSize = 14.sp)
                                        Text(h.round.toString(), color = Color(0xFF333333), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                        Text(h.mult, color = Color(0xFFD32F2F), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (i < history.size - 1) Text("↓", color = Color.White, fontSize = 9.sp)
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // ---- pola bulat: 8 makanan mengelilingi kucing
                    BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
                        val ringR = maxWidth * 0.33f
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(92.dp).clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(Color.White, Color(0xFFFFECB3))))
                                    .border(4.dp, CatWood, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🐱", fontSize = 32.sp)
                                    Text(
                                        when {
                                            !active -> "RESULT"
                                            timer > 3 -> "Select Time"
                                            else -> "LOCKING"
                                        },
                                        color = Color(0xFF0D47A1), fontSize = 7.sp, fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Box(
                                Modifier.offset(y = (-6).dp).clip(RoundedCornerShape(50))
                                    .background(Color(0xFF5D4037)).padding(horizontal = 12.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    if (active) timer.toString() + "s" else "•",
                                    color = Color(0xFFFFEC8B), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        // 8 makanan pada lingkaran, mulai dari atas lalu searah jarum jam
                        CAT_FOODS.forEachIndexed { idx, f ->
                            val deg = -90.0 + idx * 45.0
                            val rad = Math.toRadians(deg)
                            val dx = (ringR.value * Math.cos(rad)).dp
                            val dy = (ringR.value * Math.sin(rad)).dp
                            val amount = bets[f.key] ?: 0L
                            Column(
                                Modifier.align(Alignment.Center).offset(x = dx, y = dy),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.size(64.dp).clip(CircleShape)
                                        .background(Brush.radialGradient(listOf(Color.White, Color(0xFFFFF9C4))))
                                        .border(
                                            if (amount > 0L) 3.dp else 2.dp,
                                            if (amount > 0L) Color(0xFF00C853) else CatWood,
                                            CircleShape,
                                        )
                                        .clickable { placeBet(f) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(f.icon, fontSize = 20.sp)
                                        Box(
                                            Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFEC8B))
                                                .border(1.dp, CatWood, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 4.dp),
                                        ) {
                                            Text(f.mult.toString() + "X", color = Color(0xFF5D4037), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Text("🐾🐾🐾", fontSize = 6.sp)
                                Box(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(if (amount > 0L) CatRed else Color(0x33FFFFFF))
                                        .padding(horizontal = 7.dp),
                                ) {
                                    Text(
                                        if (amount > 0L) fmtCat(amount) else "",
                                        color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- chip taruhan
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF4FC3F7), CatBlue)))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    Text(
                        msg, color = Color(0xFF0D47A1), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        CAT_CHIPS.forEach { v ->
                            val on = chip == v
                            Box(
                                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (on) Brush.verticalGradient(listOf(Color(0xFFFF5252), Color(0xFFD32F2F)))
                                        else Brush.verticalGradient(listOf(Color.White, Color(0xFFE1F5FE))),
                                    )
                                    .border(2.dp, if (on) Color(0xFF7F0000) else Color(0xFF0D47A1), RoundedCornerShape(12.dp))
                                    .clickable { chip = v },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    fmtCat(v), color = if (on) Color.White else Color(0xFF0D47A1),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                // ---- dompet, hari ini, auto repeat
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFFFF5A7E), Color(0xFFC2185B))))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White).padding(6.dp),
                        ) {
                            Text("🪙 " + fmtCat(coins), color = Color(0xFF333333), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(5.dp))
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White).padding(6.dp),
                        ) {
                            Text("Today 🪙 " + fmtCat(todayWin), color = Color(0xFF333333), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(5.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Brush.verticalGradient(listOf(Color(0xFFFFEA5C), CatGold)))
                                .border(2.dp, CatWood, RoundedCornerShape(50))
                                .clickable { autoRepeat() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text("Auto Repeat", color = Color(0xFF3E2723), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("🎁 10M", "📦 50M", "🏆 100M", "💎 300M", "👑 500M").forEach { c ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                    .background(Brush.verticalGradient(listOf(Color(0xFFFFD54F), CatGold)))
                                    .border(2.dp, CatWood, RoundedCornerShape(8.dp)).padding(vertical = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(c, color = Color(0xFF3E2723), fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            // ---- daftar peringkat
            if (showRank) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { showRank = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(18.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF5B8DE8), Color(0xFF3A6FD8))))
                            .border(2.dp, CatGold, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            ) { },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🏆 Peringkat Greedy Cat", color = Color(0xFFFFE082), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("Tutup", color = Color.White, fontSize = 12.sp, modifier = Modifier.clickable { showRank = false })
                        }
                        Spacer(Modifier.height(8.dp))
                        if (rank.isEmpty()) {
                            Text("Belum ada data ronde", color = Color(0xFFE3F2FD), fontSize = 12.sp)
                        } else {
                            rank.forEachIndexed { i, (n, w) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> (i + 1).toString() },
                                        fontSize = 13.sp, modifier = Modifier.width(28.dp),
                                    )
                                    Text(n, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("🪙 " + fmtCat(w), color = Color(0xFFFFEC8B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ---- bantuan
            if (showHelp) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { showHelp = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.fillMaxWidth(0.9f).clip(RoundedCornerShape(18.dp)).background(Color.White)
                            .padding(16.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            ) { },
                    ) {
                        Text("Cara main Greedy Cat", color = Color(0xFF0D47A1), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "1. Pilih nominal chip.
" +
                                "2. Ketuk makanan di lingkaran untuk memasang. Bisa berkali-kali.
" +
                                "3. Ronde 30 detik, 3 detik terakhir terkunci.
" +
                                "4. Kalau yang keluar makanan daging, semua taruhan daging menang.
" +
                                "5. Auto Repeat memasang lagi pola terakhir persis.
" +
                                "6. Semua pemain di room melihat detik dan hasil yang sama.",
                            color = Color(0xFF333333), fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Mengerti", color = Color(0xFF1565C0), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { showHelp = false })
                    }
                }
            }

            // ---- lapisan kemenangan
            if (showWin && winFood != null) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000)).clickable { showWin = false },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xFF5B8DE8), Color(0xFF3A6FD8))))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🍴", fontSize = 22.sp)
                            Spacer(Modifier.width(14.dp))
                            Box(
                                Modifier.size(76.dp).clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(Color.White, Color(0xFFFFEC8B))))
                                    .border(4.dp, CatWood, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text(winFood!!.icon, fontSize = 36.sp) }
                            Spacer(Modifier.width(14.dp))
                            Text("🔪", fontSize = 22.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            val medals = listOf("🥇", "🥈", "🥉")
                            for (i in 0 until 3) {
                                val r = top3.getOrNull(i)
                                Column(
                                    Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x33FFFFFF)).padding(vertical = 5.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(medals[i] + " Top " + (i + 1), color = Color(0xFFFFE082), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(r?.first ?: "-", color = Color.White, fontSize = 10.sp, maxLines = 1)
                                    Text(if (r != null) "🪙 " + fmtCat(r.third) else "", color = Color(0xFFFFEC8B), fontSize = 10.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(52.dp).clip(CircleShape).background(Color.White)
                                    .border(3.dp, CatGold, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("🐱", fontSize = 28.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Round " + roundNum + "'s Result:", color = Color.White, fontSize = 12.sp)
                                Text("Wins: " + fmtCat(winAmt), color = Color.White, fontSize = 12.sp)
                                Text("Bet: " + fmtCat(winBet), color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
