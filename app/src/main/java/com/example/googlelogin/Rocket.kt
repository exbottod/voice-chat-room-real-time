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
    // Syarat energi tiap roket (energi yang harus diisi sebelum roket itu meluncur).
    val defaultExp = listOf(250_000L, 20_200_000L, 100_000_000L, 500_000_000L, 1_000_000_000L)
    val defaultImg = listOf("\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80")
    val defaultRewards = listOf("\uD83C\uDFC5", "\uD83D\uDC51", "\uD83D\uDCA5", "\uD83D\uDE97", "\uD83D\uDEF8", "\uD83C\uDFAB")

    /** Animasi bawaan; dipakai kalau panel belum mengisi animasi per level. */
    var flyUrl by mutableStateOf(
        "https://exbottod.github.io/voice-chat-room-real-time/rocket/roket_emas_v6.svga",
    )

    /** Animasi meluncur tiap roket (index 0..4 = Lv.1..Lv.5), SVGA atau MP4. */
    var flyUrls by mutableStateOf<List<String>>(List(5) { flyUrl })

    /** Hadiah 6 kolom untuk tiap roket (index 0..4 = Lv.1..Lv.5), diisi dari panel admin. */
    var lvRewards by mutableStateOf<List<List<String>>>(List(5) { emptyList() })

    /** Keterangan tiap hadiah roket: "VIP9 · 10 jt" bila diisi admin. */
    var lvRewardInfo by mutableStateOf<List<List<String>>>(List(5) { emptyList() })

    /** Gambar hadiah peringkat: kunci "1".."4" = Top1..Top3 dan Top4 ke bawah. */
    var rankPrize by mutableStateOf<Map<String, String>>(emptyMap())

    /** Keterangan hadiah peringkat. */
    var rankPrizeInfo by mutableStateOf<Map<String, String>>(emptyMap())

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
                    // Nilai 0 dianggap belum diatur, jadi memakai angka bawaan.
                    val v = s.child("exp$i").getValue(Long::class.java)
                    if (v != null && v > 0L) e[i - 1] = v
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
                // Animasi roket meluncur per level: rocketCfg/fly1..fly5.
                flyUrls = (1..5).map { n ->
                    s.child("fly$n").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: flyUrl
                }
                // Hadiah per roket: rocketCfg/lv{N}/r{1..6} (gambar + VIP + coin).
                // Data lama yang hanya berisi gambar di p{K} tetap dibaca.
                fun prizeAt(node: com.google.firebase.database.DataSnapshot): Triple<String, String, String> {
                    val img = node.child("img").getValue(String::class.java).orEmpty()
                    val vip = node.child("vip").getValue(Int::class.java) ?: 0
                    val coins = node.child("coins").getValue(Long::class.java) ?: 0L
                    val label = buildList {
                        if (vip > 0) add("VIP$vip")
                        if (coins > 0L) add(ringkas(coins))
                    }.joinToString(" · ")
                    return Triple(img, label, node.child("eff").getValue(String::class.java).orEmpty())
                }
                lvRewards = (1..5).map { n ->
                    (1..6).map { k ->
                        val o = prizeAt(s.child("lv$n").child("r$k"))
                        o.first.ifBlank {
                            s.child("lv$n").child("p$k").getValue(String::class.java).orEmpty()
                        }
                    }
                }
                lvRewardInfo = (1..5).map { n ->
                    (1..6).map { k -> prizeAt(s.child("lv$n").child("r$k")).second }
                }
                // Hadiah peringkat: rocketCfg/rank/t{1..4}
                rankPrize = (1..4).associate { k ->
                    val o = prizeAt(s.child("rank").child("t$k"))
                    "$k" to o.first.ifBlank { s.child("rank").child("t$k").getValue(String::class.java).orEmpty() }
                }
                rankPrizeInfo = (1..4).associate { k -> "$k" to prizeAt(s.child("rank").child("t$k")).second }
                // Animasi roket meluncur boleh diisi lewat panel.
                s.child("flyUrl").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { flyUrl = it }
            }
        } catch (e: Exception) {
            // pakai nilai bawaan
        }
    }

    /** Animasi meluncur untuk roket level tertentu. */
    fun flyFor(lv: Int): String = flyUrls.getOrNull((lv - 1).coerceIn(0, 4)) ?: flyUrl

    /** Level (1..5) menurut total EXP. */
    fun levelOf(total: Long): Int {
        var lv = 1
        for (i in exp.indices) if (total >= exp[i]) lv = i + 1
        return lv.coerceIn(1, exp.size)
    }
}

object RocketRepo {
    /** Energi roket pada tahap yang sedang diisi (bukan total). */
    var exp by mutableStateOf(0L)
        private set

    /** Roket yang sedang diisi energinya (1..5). Naik setelah roket meluncur. */
    var stage by mutableStateOf(1)
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
        stage = 1
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
                val st = s.child("stage").getValue(Int::class.java) ?: 1
                if (day == dayKey()) {
                    exp = v
                    stage = st.coerceIn(1, 5)
                } else {
                    exp = 0L
                    stage = 1
                }
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
     * Tambah energi roket room dari gift yang baru dikirim. Energi diisi untuk
     * roket tahap sekarang; begitu penuh roket itu meluncur, lalu tahap
     * berikutnya mulai dari nol. Setelah roket 5 meluncur, kembali ke roket 1.
     */
    suspend fun addExp(roomId: String, name: String, coins: Long) {
        val u = uid()
        if (u.isBlank() || coins <= 0L || roomId.isBlank()) return
        if (roomId != liveRoom) room(roomId)
        val launches = mutableListOf<Int>()
        try {
            Rtdb.tx(Rtdb.ref("rocket/$roomId")) { d ->
                val today = dayKey()
                val savedDay = d.child("day").getValue(String::class.java) ?: ""
                var cur = d.child("exp").getValue(Long::class.java) ?: 0L
                var st = d.child("stage").getValue(Int::class.java) ?: 1
                if (savedDay != today) {
                    cur = 0L
                    st = 1
                }
                st = st.coerceIn(1, 5)
                d.child("day").value = today
                var left = cur + coins
                var need = RocketCfg.exp.getOrElse(st - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
                var guard = 0
                while (left >= need && guard < 30) {
                    left -= need
                    launches.add(st)
                    st = if (st >= 5) 1 else st + 1
                    need = RocketCfg.exp.getOrElse(st - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
                    guard++
                }
                d.child("exp").value = left
                d.child("stage").value = st
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
        // Tiap roket yang penuh dicatat berurutan supaya semua orang melihat
        // roket 1 meluncur dulu, lalu roket berikutnya.
        for (lv in launches) publishLaunch(roomId, name, lv)
    }

    /**
     * Catat satu peluncuran roket ke room. Semua orang membaca node ini lalu
     * memutar animasi roket sesuai levelnya, satu per satu.
     */
    private suspend fun publishLaunch(roomId: String, name: String, lv: Int) {
        val u = uid()
        if (u.isBlank()) return
        try {
            Rtdb.ref("rocketFx/$roomId").push().setValue(
                hashMapOf<String, Any?>(
                    "uid" to u,
                    "name" to name,
                    "lv" to lv,
                    "url" to RocketCfg.flyFor(lv),
                    "ts" to System.currentTimeMillis(),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
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
    // Bar di sisi room mengikuti energi roket yang sedang diisi.
    val lv = RocketRepo.stage.coerceIn(1, 5)
    val need = exp.getOrElse(lv - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
    val frac = (total.toFloat() / need.toFloat()).coerceIn(0f, 1f)
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
    // Roket yang sedang diisi energinya (1..5), bukan level menurut total EXP.
    val lv = RocketRepo.stage.coerceIn(1, 5)
    val myUid = remember { RocketRepo.uid() }
    var prizeRank by remember { mutableStateOf(-1) }
    var showRules by remember { mutableStateOf(false) }
    var showRank by remember { mutableStateOf(false) }
    var showPrizes by remember { mutableStateOf(false) }
    // Hadiah yang sedang dilihat: default roket level sekarang, bisa diketuk di deretan roket.
    var viewLv by remember { mutableStateOf(0) }
    val shownLv = if (viewLv in 1..5) viewLv else lv

    // Energi roket tahap ini dibandingkan dengan syarat levelnya.
    val need = expList.getOrElse(lv - 1) { Long.MAX_VALUE }.coerceAtLeast(1L)
    val frac = (total.toFloat() / need.toFloat()).coerceIn(0f, 1f)

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
                .fillMaxHeight(0.80f)
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

            // Baris atas: tanda tanya di kiri, piala + kado di kanan.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RocketRoundBtn("?") { showRules = true }
                Spacer(Modifier.weight(1f))
                Text(
                    "Rocket",
                    color = Color(0xFFFFD86B),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.weight(1f))
                RocketRoundBtn("\uD83C\uDFC6") { showRank = true }
                Spacer(Modifier.width(8.dp))
                RocketRoundBtn("\uD83C\uDF81") { showPrizes = true }
            }

            Spacer(Modifier.height(14.dp))

            // Deretan 5 roket evolusi, dipisah tanda ">".
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..5) {
                    if (i > 1) Text(">", color = Color(0xFFE8C489), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    val active = i == lv
                    val unlocked = i <= lv
                    Box(
                        Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { viewLv = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.alpha(if (unlocked) 1f else 0.6f)) {
                            RocketAsset(
                                RocketCfg.img.getOrElse(i - 1) { "\uD83D\uDE80" },
                                if (active) 54.dp else 42.dp,
                                active,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Garis kemajuan tipis dengan roket kecil di ujung kiri, lv. sekarang dan berikutnya di bawahnya.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDE80", fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(50))
                        .background(Color(0xFF102A5E)),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(frac.coerceAtLeast(0.03f))
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFFFFC107), Color(0xFFFFE082)))),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("lv.$lv", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (lv >= 5) "lv.1" else "lv.${lv + 1}",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Kotak hadiah: 6 kotak persegi bergaris emas tipis.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFFE8C489), RoundedCornerShape(6.dp))
                    .padding(8.dp),
            ) {
                Column {
                    val list = RocketCfg.lvRewards.getOrNull(shownLv - 1).orEmpty()
                    for (r in 0 until 2) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in 0 until 3) {
                                val idx = r * 3 + c
                                val v = list.getOrNull(idx).orEmpty()
                                    .ifBlank { RocketCfg.rewards.getOrElse(idx) { "\uD83C\uDF81" } }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0A2455))
                                        .border(1.dp, Color(0x99E8C489), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        RocketAsset(v, 58.dp, false)
                                        val info = RocketCfg.lvRewardInfo.getOrNull(shownLv - 1)?.getOrNull(idx).orEmpty()
                                        if (info.isNotBlank()) {
                                            Text(info, color = Color(0xFFFFE08A), fontSize = 9.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                        if (r == 0) Spacer(Modifier.height(8.dp))
                    }
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
                    val info = RocketCfg.rankPrizeInfo["${i + 1}"].orEmpty()
                    if (info.isNotBlank()) Text(info, color = Color(0xFF7FD3FF), fontSize = 11.sp)
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

// ==================== Peluncuran roket (level 1..5) ====================

private var rocketLastSeen = ""

/** Satu kejadian peluncuran roket yang diterima dari room. */
private data class LaunchEv(val id: String, val uid: String, val name: String, val lv: Int, val url: String)

/**
 * Semua peluncuran roket di room, dimainkan berurutan: roket 1 meluncur dan
 * memutar animasinya, lalu dialog hadiah dengan tombol Okey. Setelah Okey
 * ditekan barulah roket berikutnya meluncur, begitu seterusnya.
 */
@Composable
fun RocketLaunchOverlay(roomId: String, myUid: String) {
    if (roomId.isBlank()) return
    RocketCfg.start()
    var queue by remember(roomId) { mutableStateOf(listOf<LaunchEv>()) }
    var current by remember(roomId) { mutableStateOf<LaunchEv?>(null) }
    var showPrize by remember(roomId) { mutableStateOf(false) }
    var showLucky by remember(roomId) { mutableStateOf(false) }

    DisposableEffect(roomId) {
        val reg = Rtdb.listen(Rtdb.ref("rocketFx/$roomId")) { s ->
            val id = s.key ?: return@listen
            val ts = s.child("ts").getValue(Long::class.java) ?: 0L
            if (id == rocketLastSeen) return@listen
            // Abaikan kejadian lama dari sebelum aplikasi dibuka.
            if (ts <= 0L || ts < System.currentTimeMillis() - 20_000L) return@listen
            rocketLastSeen = id
            queue = queue + LaunchEv(
                id = id,
                uid = s.child("uid").getValue(String::class.java) ?: "",
                name = s.child("name").getValue(String::class.java) ?: "",
                lv = (s.child("lv").getValue(Int::class.java) ?: 5).coerceIn(1, 5),
                url = s.child("url").getValue(String::class.java)
                    ?.takeIf { it.isNotBlank() } ?: RocketCfg.flyFor(5),
            )
        }
        onDispose { reg.remove() }
    }

    // Ambil satu kejadian dari antrean hanya kalau tidak ada yang sedang jalan.
    LaunchedEffect(queue, current, showPrize, showLucky) {
        if (current == null && !showPrize && !showLucky && queue.isNotEmpty()) {
            current = queue.first()
            queue = queue.drop(1)
        }
    }

    val ev = current
    if (ev != null && !showPrize && !showLucky) {
        var ended by remember(ev.id) { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            if (isVideoMedia(ev.url)) {
                GiftVideo(ev.url, Modifier.fillMaxSize()) { ended = true }
            } else {
                GiftSvga(ev.url, "\uD83D\uDE80", Modifier.fillMaxSize()) { ended = true }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC101010))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "\uD83D\uDE80 Roket Lv.${ev.lv} ${ev.name} meluncur!",
                    color = GoldLight, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        // Animasi selesai (atau penjaga waktu kalau file gagal diputar).
        LaunchedEffect(ev.id) {
            delay(if (isVideoMedia(ev.url)) 8000 else 6000)
            ended = true
        }
        LaunchedEffect(ended) {
            if (!ended) return@LaunchedEffect
            if (ev.uid == myUid) showPrize = true else showLucky = true
        }
    }

    if (ev != null && showPrize) {
        RocketPrizeDialog(
            myUid = myUid,
            mode = "launch",
            rank = 0,
            launchId = ev.id,
            lv = ev.lv,
            onClose = {
                showPrize = false
                current = null
            },
        )
    }
    if (ev != null && showLucky) {
        // Penonton: sekali per peluncuran, lalu lanjut roket berikutnya.
        var can by remember(ev.id) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(ev.id) { can = RocketRepo.canLucky(myUid, ev.id) }
        when (can) {
            null -> Unit
            false -> LaunchedEffect(ev.id) {
                showLucky = false
                current = null
            }
            else -> RocketPrizeDialog(
                myUid = myUid,
                mode = "lucky",
                rank = 0,
                launchId = ev.id,
                lv = ev.lv,
                onClose = {
                    showLucky = false
                    current = null
                },
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
    lv: Int = 5,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    var loading by remember { mutableStateOf(true) }
    var coins by remember { mutableStateOf(0L) }
    var vip by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mode, rank, launchId, lv) {
        val r = SupaRepo.rocketClaimPrize(myUid, mode, rank, launchId, lv)
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
                    "lucky" -> "Roket Lv.$lv di room meluncur, kamu kebagian!"
                    else -> "Roket Lv.$lv sudah meluncur!"
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
                Text("Okey", color = Color(0xFF2A1B00), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

