package com.example.googlelogin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Fitur Rocket: level roket naik dari total koin yang dipakai mengirim gift.
 * Semua angka dan aset bisa diatur di Realtime Database node "rocketCfg" tanpa
 * perlu build ulang aplikasi:
 *
 *   rocketCfg/exp1 .. exp5   = syarat EXP tiap level (angka)
 *   rocketCfg/img1 .. img5   = URL / emoji roket tiap level
 *   rocketCfg/rw1 .. rw6     = hadiah grid 3x2 (URL gambar atau emoji)
 *
 * Progres pemain disimpan di rocket/{uid}/exp.
 */
object RocketCfg {
    // Ambang EXP bawaan: Lv1 0, Lv2 250K, Lv3 20,2M, Lv4 100M, Lv5 500M.
    val defaultExp = listOf(0L, 250_000L, 20_200_000L, 100_000_000L, 500_000_000L)
    val defaultImg = listOf("\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80")
    val defaultRewards = listOf("\uD83C\uDFC5", "\uD83D\uDC51", "\uD83D\uDCA5", "\uD83D\uDE97", "\uD83D\uDEF8", "\uD83C\uDFAB")

    /** Animasi roket yang meluncur setelah level 5 pecah (SVGA/MP4). */
    var flyUrl by mutableStateOf(
        "https://exbottod.github.io/voice-chat-room-real-time/rocket/roket_emas_v6.svga",
    )

    /** Hadiah 6 kolom untuk tiap roket (index 0..4 = Lv.1..Lv.5), diisi dari panel admin. */
    var lvRewards by mutableStateOf<List<List<String>>>(List(5) { emptyList() })

    /** Gambar hadiah peringkat: kunci "1".."4" = Top1..Top3 dan Top4 ke bawah. */
    var rankPrize by mutableStateOf<Map<String, String>>(emptyMap())

    var exp by mutableStateOf(defaultExp)
    var img by mutableStateOf(defaultImg)
    var rewards by mutableStateOf(defaultRewards)

    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            Rtdb.listen(Rtdb.ref("rocketCfg")) { s ->
                val e = defaultExp.toMutableList()
                for (i in 1..5) {
                    val v = s.child("exp$i").getValue(Long::class.java)
                    if (v != null && v >= 0L) e[i - 1] = v
                }
                exp = e.toList()
                val im = defaultImg.toMutableList()
                for (i in 1..5) {
                    s.child("img$i").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { im[i - 1] = it }
                }
                img = im.toList()
                val rw = defaultRewards.toMutableList()
                for (i in 1..6) {
                    s.child("rw$i").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { rw[i - 1] = it }
                }
                rewards = rw.toList()
                s.child("flyUrl").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { flyUrl = it }
                // Hadiah per roket: rocketCfg/lv{N}/p{1..6}
                lvRewards = (1..5).map { n ->
                    (1..6).map { k ->
                        s.child("lv$n").child("p$k").getValue(String::class.java).orEmpty()
                    }
                }
                // Hadiah peringkat: rocketCfg/rank/t{1..4}
                rankPrize = (1..4).associate { k ->
                    "$k" to s.child("rank").child("t$k").getValue(String::class.java).orEmpty()
                }
            }
        } catch (e: Exception) {
            // pakai nilai bawaan
        }
    }

    /** Level (1..5) menurut total EXP. */
    fun levelOf(total: Long): Int {
        var lv = 1
        for (i in exp.indices) if (total >= exp[i]) lv = i + 1
        return lv.coerceIn(1, exp.size)
    }
}

object RocketRepo {
    /** EXP roket pemain, disimpan di memori supaya panel langsung tampil benar. */
    var exp by mutableStateOf(0L)
        private set

    /** Peringkat roket room ini pada musim berjalan. */
    var rankRows by mutableStateOf<List<RocketRankRow>>(emptyList())
        private set

    fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    /** Tanggal menurut GMT+3 (Arab Saudi): dasar reset energi roket tiap 00.00. */
    fun dayKey(): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+3"))
        return String.format(
            "%04d-%02d-%02d",
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    /** Kunci musim: tahun + nomor pekan, supaya peringkat bisa direset mingguan. */
    fun season(): String {
        val c = java.util.Calendar.getInstance()
        val wk = c.get(java.util.Calendar.WEEK_OF_YEAR)
        return "${c.get(java.util.Calendar.YEAR)}-W" + (if (wk < 10) "0$wk" else "$wk")
    }

    /** Peringkat saya di room ini (1 = teratas). */
    fun myRank(): Int {
        val u = uid()
        val i = rankRows.indexOfFirst { it.uid == u }
        return if (i < 0) rankRows.size + 1 else i + 1
    }

    /**
     * Penonton boleh klaim hadiah keberuntungan sekali per peluncuran.
     * Return true kalau belum pernah klaim (dan langsung ditandai).
     */
    suspend fun canLucky(uid: String, launchId: String): Boolean {
        if (uid.isBlank() || launchId.isBlank()) return false
        val mark = Rtdb.ref("rocketLucky/$launchId/$uid")
        val done = try {
            mark.get().await().exists()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (done) return false
        try {
            mark.setValue(true).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        return true
    }

    private var launching = false

    /**
     * Roket level 5 pecah lalu meluncur: tulis kejadian ke room supaya semua
     * orang melihat animasi yang sama. Dibatasi sekali per 6 jam per room.
     */
    suspend fun maybeLaunch(roomId: String, name: String) {
        val u = uid()
        if (u.isBlank() || roomId.isBlank() || launching) return
        launching = true
        try {
            val mark = try {
                Rtdb.ref("rocketLaunchMark/$roomId/$u").get().await().getValue(Long::class.java) ?: 0L
            } catch (e: Exception) {
                0L
            }
            val now = System.currentTimeMillis()
            // Satu peluncuran per siklus room; penjaga ini hanya mencegah dobel.
            if (now - mark < 30_000L) return
            Rtdb.ref("rocketFx/$roomId").push().setValue(
                hashMapOf<String, Any?>(
                    "uid" to u,
                    "name" to name,
                    "url" to RocketCfg.flyUrl,
                    "ts" to now,
                ),
            ).await()
            Rtdb.ref("rocketLaunchMark/$roomId/$u").setValue(now).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        } finally {
            launching = false
        }
    }

    /** Room yang sedang dihitung roketnya (EXP disimpan terpisah per room). */
    private var liveRoom = ""

    private val regs = mutableListOf<Reg>()

    /** Pindah konteks ke room lain: EXP dan peringkat dimuat ulang untuk room itu. */
    fun room(roomId: String) {
        if (roomId.isBlank() || roomId == liveRoom) return
        liveRoom = roomId
        exp = 0L
        rankRows = emptyList()
        for (r in regs) {
            try {
                r.remove()
            } catch (e: Exception) {
            }
        }
        regs.clear()
        val u = uid()
        if (u.isBlank()) return
        // EXP roket milik ROOM, bukan per pemain: siapa pun yang mengirim gift
        // di room ini menaikkan roket yang sama untuk semua orang di room.
        try {
            regs += Rtdb.listen(Rtdb.ref("rocket/$roomId")) { s ->
                val day = s.child("day").getValue(String::class.java) ?: ""
                val v = s.child("exp").getValue(Long::class.java) ?: 0L
                exp = if (day == dayKey()) v else 0L
            }
        } catch (e: Exception) {
        }
        try {
            regs += Rtdb.listen(Rtdb.ref("rocketRank/$roomId/${season()}")) { s ->
                rankRows = s.children.mapNotNull { c ->
                    val id = c.key ?: return@mapNotNull null
                    RocketRankRow(
                        uid = id,
                        name = c.child("name").getValue(String::class.java) ?: "Player",
                        coins = c.child("coins").getValue(Long::class.java) ?: 0L,
                    )
                }.sortedByDescending { it.coins }.take(20)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Tambah EXP roket room dari gift yang baru dikirim. Nilainya publik:
     * semua orang di room melihat angka dan level yang sama, dan begitu
     * melewati level 5 roket pecah lalu meluncur untuk seluruh room.
     */
    suspend fun addExp(roomId: String, name: String, coins: Long) {
        val u = uid()
        if (u.isBlank() || coins <= 0L || roomId.isBlank()) return
        if (roomId != liveRoom) room(roomId)
        val maxExp = RocketCfg.exp.getOrNull(4) ?: Long.MAX_VALUE
        var crossed = false
        try {
            Rtdb.tx(Rtdb.ref("rocket/$roomId")) { d ->
                val today = dayKey()
                val savedDay = d.child("day").getValue(String::class.java) ?: ""
                var cur = d.child("exp").getValue(Long::class.java) ?: 0L
                if (savedDay != today) cur = 0L
                d.child("day").value = today
                var next = cur + coins
                if (next >= maxExp) {
                    crossed = true
                    next -= maxExp
                }
                d.child("exp").value = next
                d.child("lastBy").value = name
                d.child("ts").value = System.currentTimeMillis()
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        // Peringkat: siapa paling banyak kirim gift di room ini pekan ini.
        try {
            Rtdb.ref("rocketRank/$roomId/${season()}/$u").setValue(
                hashMapOf<String, Any?>(
                    "name" to name,
                    "coins" to ServerValue.increment(coins),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        if (crossed) maybeLaunch(roomId, name)
    }
}

/** Satu baris peringkat roket. */
data class RocketRankRow(val uid: String, val name: String, val coins: Long)

/** Angka ringkas gaya panel: 374.6K, 20.2M. */
private fun ringkas(n: Long): String = when {
    n >= 1_000_000_000L -> String.format("%.1fB", n / 1_000_000_000.0)
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

/** Tampilan satu aset roket/hadiah: kalau URL dipakai gambar, kalau bukan dipakai teks. */
@Composable
private fun RocketAsset(value: String, size: androidx.compose.ui.unit.Dp, glow: Boolean) {
    if (value.startsWith("http")) {
        Box(contentAlignment = Alignment.Center) {
            if (glow) {
                Box(
                    Modifier.size(size * 1.15f).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0x6600AAFF), Color.Transparent))),
                )
            }
            AsyncImage(
                model = value,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        Box(contentAlignment = Alignment.Center) {
            if (glow) {
                Box(
                    Modifier.size(size * 1.15f).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0x6600AAFF), Color.Transparent))),
                )
            }
            Text(value, fontSize = (size.value * 0.55f).sp)
        }
    }
}

/**
 * Panel melayang di sisi kanan room: ikon roket yang bisa ditekan untuk membuka
 * panel Rocket, dengan bar persen menuju level berikutnya di bawahnya.
 */
@Composable
fun RocketRail(roomId: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    RocketCfg.start()
    LaunchedEffect(roomId) { RocketRepo.room(roomId) }
    val total = RocketRepo.exp
    val exp = RocketCfg.exp
    val lv = RocketCfg.levelOf(total)
    val cur = exp[(lv - 1).coerceIn(0, exp.size - 1)]
    val next = exp.getOrNull(lv)
    val frac = if (next == null) 1f else (((total - cur).toFloat() / (next - cur).coerceAtLeast(1L))).coerceIn(0f, 1f)
    val pct = (frac * 100).toInt()

    Column(
        modifier = modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .border(1.5.dp, GoldLight.copy(alpha = 0.85f), CircleShape)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0x664FC3F7), Color.Transparent))),
            )
            RocketAsset(RocketCfg.img.getOrElse(lv - 1) { "🚀" }, 28.dp, false)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.width(46.dp).height(6.dp).clip(RoundedCornerShape(50))
                .background(Color(0x99000000))
                .border(0.5.dp, GoldLight.copy(alpha = 0.6f), RoundedCornerShape(50)),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(frac.coerceAtLeast(0.04f))
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF4FC3F7), Color(0xFF00AAFF)))),
            )
        }
        Text("$pct%", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Panel Rocket: judul, bar level, deretan 5 roket evolusi, dan grid hadiah 3x2.
 * Dibuka dari ikon roket di room.
 */
@Composable
fun RocketScreen(roomId: String, onDismiss: () -> Unit) {
    RocketCfg.start()
    LaunchedEffect(roomId) { RocketRepo.room(roomId) }
    val total = RocketRepo.exp
    // Tombol back menutup panel roket saja, tidak keluar dari room.
    BackHandler { onDismiss() }

    val expList = RocketCfg.exp
    val lv = RocketCfg.levelOf(total)
    val myUid = remember { RocketRepo.uid() }
    var prizeRank by remember { mutableStateOf(-1) }
    var showRules by remember { mutableStateOf(false) }
    var showRank by remember { mutableStateOf(false) }
    var showPrizes by remember { mutableStateOf(false) }
    // Hadiah yang sedang dilihat: default roket level sekarang, bisa diketuk di deretan roket.
    var viewLv by remember { mutableStateOf(0) }
    val shownLv = if (viewLv in 1..5) viewLv else lv

    val curExp = expList[(lv - 1).coerceIn(0, expList.size - 1)]
    val nextExp = expList.getOrNull(lv)
    val frac = if (nextExp == null) 1f else {
        val span = (nextExp - curExp).coerceAtLeast(1L)
        ((total - curExp).toFloat() / span).coerceIn(0f, 1f)
    }

    val gold = Color(0xFFD4AF37)
    Box(Modifier.fillMaxSize()) {
        // Latar gelap di atas supaya kursi mic room tetap terlihat seperti di desain.
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0A2A66), Color(0xFF061B45), Color(0xFF04122E))))
                .border(1.5.dp, gold.copy(alpha = 0.8f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { },
                )
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))

            // Baris atas: tanda tanya (aturan) di kiri, piala + kado di kanan.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RocketRoundBtn("?") { showRules = true }
                Spacer(Modifier.weight(1f))
                Text(
                    "Rocket",
                    color = Color(0xFFFFE08A),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.weight(1f))
                RocketRoundBtn("\uD83C\uDFC6") { showRank = true }
                Spacer(Modifier.width(8.dp))
                RocketRoundBtn("\uD83C\uDF81") { showPrizes = true }
            }

            Spacer(Modifier.height(10.dp))

            // Deretan 5 roket evolusi dengan panah di antaranya.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..5) {
                    val active = i == lv
                    val unlocked = i <= lv
                    Box(
                        Modifier.clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { viewLv = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.alpha(if (unlocked) 1f else 0.55f)) {
                            RocketAsset(
                                RocketCfg.img.getOrElse(i - 1) { "\uD83D\uDE80" },
                                if (active) 58.dp else 44.dp,
                                active,
                            )
                        }
                    }
                    if (i < 5) Text("\u00BB", color = Color(0xFFFFD54F), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Bar energi: roket kecil di ujung kiri, Lv.sekarang kiri bawah, Lv.berikutnya kanan bawah.
            Box(
                Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xFF0B1B3E))
                    .border(1.5.dp, gold, RoundedCornerShape(50)),
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(frac.coerceAtLeast(0.03f))
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A00), Color(0xFFFFD54F)))),
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("lv.$lv", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (nextExp == null) "MAX" else "${ringkas(total)} / ${ringkas(nextExp)}",
                    color = Color(0xFF9FB6D9), fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (nextExp == null) "lv.$lv" else "lv.${lv + 1}",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Kotak hadiah: 6 kolom (3 x 2) untuk roket yang dilihat.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A1F4D))
                    .border(1.5.dp, gold.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                Text(
                    "Hadiah roket Lv.$shownLv",
                    color = Color(0xFFFFE08A), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                val list = RocketCfg.lvRewards.getOrNull(shownLv - 1).orEmpty()
                for (r in 0 until 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until 3) {
                            val idx = r * 3 + c
                            val v = list.getOrNull(idx).orEmpty().ifBlank { RocketCfg.rewards.getOrElse(idx) { "\uD83C\uDF81" } }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(86.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0E2A63))
                                    .border(1.dp, Color(0xFF3F67B5), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                RocketAsset(v, 62.dp, false)
                            }
                        }
                    }
                    if (r == 0) Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (showRules) RocketRulesDialog { showRules = false }
        if (showRank) RocketRankDialog(myUid, onClaim = { prizeRank = it }) { showRank = false }
        if (showPrizes) RocketRankPrizeDialog { showPrizes = false }
        if (prizeRank >= 0) {
            RocketPrizeDialog(
                myUid = myUid,
                mode = "rank",
                rank = prizeRank,
                onClose = { prizeRank = -1 },
            )
        }
    }
}

/** Tombol bulat emas kecil (?, piala, kado). */
@Composable
private fun RocketRoundBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2B5FB8), Color(0xFF123470))))
            .border(1.5.dp, Color(0xFFD4AF37), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color(0xFFFFE08A), fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

/** Dialog dasar bergaya panel roket: judul, isi, tombol tutup. */
@Composable
private fun RocketSheet(title: String, onClose: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0A2A66), Color(0xFF04122E))))
                .border(1.5.dp, Color(0xFFD4AF37), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { },
                )
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) { Text("\u2039", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = body)
        }
    }
}

/** Aturan roket (tombol ?). */
@Composable
fun RocketRulesDialog(onClose: () -> Unit) {
    val rules = listOf(
        "Memberikan hadiah koin akan menyimpan energi untuk roket. 1 koin = 1 energi.",
        "Ketika energi roket di dalam room sudah penuh, roket akan meluncur dan membagikan hadiah.",
        "Roket berisi sejumlah besar hadiah, termasuk namun tidak terbatas pada hadiah yang ditampilkan di halaman.",
        "Pengguna yang berkontribusi dalam peluncuran roket akan mendapatkan hadiah kontribusi, pengguna lainnya juga berkesempatan mendapatkan hadiah keberuntungan.",
        "Bila kemajuan roket akan direset setiap hari pada pukul 00.00 (GMT+3).",
        "Hak interpretasi akhir acara ini adalah milik APP.",
    )
    RocketSheet("Aturan", onClose) {
        rules.forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("${i + 1}", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                Text(t, color = Color.White, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

/** Peringkat roket room ini (tombol piala) + tombol klaim hadiah peringkat. */
@Composable
fun RocketRankDialog(myUid: String, onClaim: (Int) -> Unit, onClose: () -> Unit) {
    val rows = RocketRepo.rankRows
    val myRank = RocketRepo.myRank()
    RocketSheet("Peringkat roket", onClose) {
        Text("Pengirim gift terbanyak di room ini pekan ini", color = Color(0xFF9FB6D9), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) {
            Text("Belum ada yang mengirim gift.", color = Color(0xFF9FB6D9), fontSize = 13.sp)
        } else {
            rows.take(10).forEachIndexed { i, r0 ->
                val medal = when (i) { 0 -> "\uD83E\uDD47"; 1 -> "\uD83E\uDD48"; 2 -> "\uD83E\uDD49"; else -> "${i + 1}" }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(medal, fontSize = 16.sp, modifier = Modifier.width(30.dp))
                    Text(
                        r0.name,
                        color = if (r0.uid == myUid) Color(0xFFFFD54F) else Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text(ringkas(r0.coins), color = Color(0xFF7FD3FF), fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth().height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFB8860B))))
                .clickable { onClaim(myRank); onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text("Klaim hadiah peringkat (#$myRank)", color = Color(0xFF2A1B00), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Hadiah peringkat (tombol kado): Top1, Top2, Top3, Top4 ke bawah. */
@Composable
fun RocketRankPrizeDialog(onClose: () -> Unit) {
    val rows = listOf(
        "Top 1" to "VIP9 + 100 juta coin",
        "Top 2" to "VIP8 + 50 juta coin",
        "Top 3" to "VIP5 + 10 juta coin",
        "Top 4 ke bawah" to "10 juta coin",
    )
    RocketSheet("Hadiah peringkat", onClose) {
        rows.forEachIndexed { i, (t, d) ->
            val v = RocketCfg.rankPrize["${i + 1}"].orEmpty()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0E2A63))
                    .border(1.dp, Color(0xFF3F67B5), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    RocketAsset(v.ifBlank { when (i) { 0 -> "\uD83E\uDD47"; 1 -> "\uD83E\uDD48"; 2 -> "\uD83E\uDD49"; else -> "\uD83C\uDFC5" } }, 52.dp, false)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(t, color = Color(0xFFFFE08A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(d, color = Color.White, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Hadiah diberikan saat roket meluncur. Pengguna lain juga berkesempatan mendapat hadiah keberuntungan.",
            color = Color(0xFF9FB6D9), fontSize = 11.sp,
        )
    }
}

// ==================== Peluncuran roket level 5 ====================

private var rocketLastSeen = ""

/**
 * Begitu roket level 5 pecah, animasi roket emas meluncur di layar room untuk
 * semua orang. Setelah animasi selesai, pengirimnya langsung dapat dialog hadiah.
 */
@Composable
fun RocketLaunchOverlay(
    roomId: String,
    myUid: String,
    onPrize: (String) -> Unit,
    onLucky: (String) -> Unit = {},
) {
    if (roomId.isBlank()) return
    RocketCfg.start()
    var evId by remember { mutableStateOf("") }
    var evUid by remember { mutableStateOf("") }
    var evName by remember { mutableStateOf("") }

    DisposableEffect(roomId) {
        val reg = Rtdb.listen(Rtdb.ref("rocketFx/$roomId")) { s ->
            val ts = s.child("ts").getValue(Long::class.java) ?: 0L
            val id = s.key ?: ""
            if (id.isNotBlank() && id != rocketLastSeen && ts > System.currentTimeMillis() - 20_000L) {
                rocketLastSeen = id
                evId = id
                evUid = s.child("uid").getValue(String::class.java) ?: ""
                evName = s.child("name").getValue(String::class.java) ?: ""
            }
        }
        onDispose { reg.remove() }
    }

    if (evId.isBlank()) return

    LaunchedEffect(evId) {
        delay(1500)
        if (evUid == myUid) onPrize(evId) else onLucky(evId)
        delay(9000)
        evId = ""
    }

    Box(Modifier.fillMaxSize()) {
        GiftSvga(RocketCfg.flyUrl, "🚀", Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC101010))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "🚀 Roket $evName meluncur!",
                color = GoldLight, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Dialog hadiah roket. Hadiah diundi server, jadi tidak bisa dipilih pemain. */
@Composable
fun RocketPrizeDialog(
    myUid: String,
    mode: String,
    rank: Int,
    launchId: String = "",
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    var loading by remember { mutableStateOf(true) }
    var coins by remember { mutableStateOf(0L) }
    var vip by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mode, rank, launchId) {
        val r = SupaRepo.rocketClaimPrize(myUid, mode, rank, launchId)
        coins = r.coins
        vip = r.vip
        days = r.days
        err = r.error
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF0B2A5B), Color(0xFF06152E))))
                .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(22.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (mode) {
                    "rank" -> "Hadiah peringkat roket"
                    "lucky" -> "Hadiah keberuntungan 🍀"
                    else -> "Hadiah kontribusi roket 🚀"
                },
                color = GoldLight, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (mode) {
                    "rank" -> "Peringkat kamu #$rank pekan ini"
                    "lucky" -> "Roket di room ini meluncur, kamu kebagian!"
                    else -> "Roket emas sudah meluncur!"
                },
                color = Color(0xFF9FB6D9), fontSize = 12.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            when {
                loading -> Text("Mengambil hadiah...", color = Color.White, fontSize = 14.sp)
                err != null -> Text(err ?: "", color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center)
                else -> {
                    if (vip > 0) Text("👑 VIP$vip $days hari", color = Color(0xFFFFD54F), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    if (coins > 0) Text("+${ringkas(coins)} coin", color = Color(0xFFFFD54F), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    Text("Hadiah sudah masuk ke dompetmu.", color = Color(0xFF9FB6D9), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFB8860B))))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Tutup", color = Color(0xFF2A1B00), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

