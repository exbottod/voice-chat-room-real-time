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
            regs += Rtdb.listen(Rtdb.ref("rocket/$roomId/exp")) { s ->
                exp = s.getValue(Long::class.java) ?: 0L
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
                val cur = d.child("exp").getValue(Long::class.java) ?: 0L
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
    val curExp = expList[(lv - 1).coerceIn(0, expList.size - 1)]
    val nextExp = expList.getOrNull(lv)
    val frac = if (nextExp == null) 1f else {
        val span = (nextExp - curExp).coerceAtLeast(1L)
        ((total - curExp).toFloat() / span).coerceIn(0f, 1f)
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xEE04122E)).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF071C42), Color(0xFF03102A), Color(0xFF071C42)),
                    ),
                )
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { },
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 16.sp) }
            }

            // Judul Rocket
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Rocket",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .border(0.dp, Color.Transparent)
                        .padding(0.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("🪐", fontSize = 22.sp)
            }
            Text(
                "Roket milik room ini. Setiap gift dari siapa pun menaikkannya.",
                color = Color(0xFF9FB6D9),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))

            // Kartu profil + level
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC0A1A3A))
                    .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0x33D4AF37)), contentAlignment = Alignment.Center) {
                    RocketAsset(RocketCfg.img.getOrElse(lv - 1) { "🚀" }, 30.dp, false)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Level roket kamu", color = Color(0xFF9FB6D9), fontSize = 11.sp)
                    Text("Lv.$lv", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (nextExp == null) "Level tertinggi tercapai" else "${ringkas(total)} / ${ringkas(nextExp)} menuju Lv.${lv + 1}",
                        color = Color.White, fontSize = 12.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("EXP", color = Color(0xFF9FB6D9), fontSize = 10.sp)
                    Text(ringkas(total), color = Color(0xFF7FD3FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Bar level
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Lv.$lv", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(50))
                        .background(Color(0xFF0B2550))
                        .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.5f), RoundedCornerShape(50)),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(frac)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF4FC3F7), Color(0xFF00AAFF)))),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (nextExp == null) "MAX" else "Lv.${lv + 1}",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${ringkas(total)} / ${ringkas(nextExp ?: total)}",
                color = Color(0xFF9FB6D9), fontSize = 11.sp,
            )

            Spacer(Modifier.height(16.dp))

            // Deretan roket evolusi
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                for (i in 1..5) {
                    val active = i == lv
                    val unlocked = i <= lv
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RocketAsset(
                            RocketCfg.img.getOrElse(i - 1) { "🚀" },
                            if (active) 62.dp else 44.dp,
                            active,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Lv.$i",
                            color = if (unlocked) Color.White else Color(0xFF5A6E8C),
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.alpha(if (unlocked) 1f else 0.7f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            RocketRankCard(myUid) { prizeRank = it }
            Spacer(Modifier.height(18.dp))

            // Grid hadiah 3x2
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC08183A))
                    .border(1.5.dp, Color(0xFFD4AF37).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
            ) {
                Text("Hadiah level", color = GoldLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                for (r in 0 until 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in 0 until 3) {
                            val idx = r * 3 + c
                            // Hadiah dianggap terbuka kalau level roket sudah melewati tingkatnya.
                            val open = lv > idx / 2
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(84.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0B2550))
                                    .border(
                                        1.dp,
                                        if (open) Color(0xFF7FD3FF).copy(alpha = 0.8f) else Color(0x33FFFFFF),
                                        RoundedCornerShape(12.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.alpha(if (open) 1f else 0.45f),
                                ) {
                                    RocketAsset(RocketCfg.rewards.getOrElse(idx) { "🎁" }, 38.dp, false)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (open) "Lv.${idx / 2 + 1}" else "Lv.${idx / 2 + 1}",
                                        color = Color(0xFF9FB6D9), fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                    if (r == 0) Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "EXP roket bertambah setiap kali kamu mengirim gift ke siapa pun di room.",
                    color = Color(0xFF8AA3C6), fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(18.dp))
            GoldButton("Tutup", Modifier.fillMaxWidth()) { onDismiss() }
            Spacer(Modifier.height(24.dp))
        }

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


// ==================== Peluncuran roket level 5 ====================

private var rocketLastSeen = ""

/**
 * Begitu roket level 5 pecah, animasi roket emas meluncur di layar room untuk
 * semua orang. Setelah animasi selesai, pengirimnya langsung dapat dialog hadiah.
 */
@Composable
fun RocketLaunchOverlay(roomId: String, myUid: String, onPrize: (String) -> Unit) {
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
        if (evUid == myUid) onPrize(evId)
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
                if (mode == "rank") "Hadiah peringkat roket" else "Hadiah roket 🚀",
                color = GoldLight, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (mode == "rank") "Peringkat kamu #$rank pekan ini" else "Roket emas sudah meluncur!",
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

/**
 * Peringkat roket pekan ini + daftar hadiahnya:
 * #1 VIP9 + 100 juta coin, #2 VIP8 + 50 juta, #3 VIP5 + 10 juta,
 * #4 ke bawah 10 juta coin.
 */
@Composable
fun RocketRankCard(myUid: String, onClaim: (Int) -> Unit) {
    val rows = RocketRepo.rankRows
    val myRank = RocketRepo.myRank()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC08183A))
            .border(1.5.dp, Color(0xFFD4AF37).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text("Peringkat roket pekan ini", color = GoldLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text("Belum ada yang mengirim gift pekan ini.", color = Color(0xFF9FB6D9), fontSize = 11.sp)
        } else {
            for ((i, r0) in rows.take(3).withIndex()) {
                val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; else -> "🥉" }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(medal, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        r0.name,
                        color = if (r0.uid == myUid) Color(0xFFFFD54F) else Color.White,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text(ringkas(r0.coins), color = Color(0xFF7FD3FF), fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "#1 VIP9 + 100 juta coin\n#2 VIP8 + 50 juta coin\n#3 VIP5 + 10 juta coin\n#4 ke bawah 10 juta coin",
            color = Color(0xFF9FB6D9), fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth().height(42.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFB8860B))))
                .clickable { onClaim(myRank) },
            contentAlignment = Alignment.Center,
        ) {
            Text("Klaim hadiah peringkat (#$myRank)", color = Color(0xFF2A1B00), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
