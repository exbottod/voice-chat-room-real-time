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

    private var live = false

    fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    /** Dengarkan EXP sekali saja; panel yang dibuka belakangan langsung dapat nilai terakhir. */
    fun start() {
        if (live) return
        val u = uid()
        if (u.isBlank()) return
        live = true
        try {
            Rtdb.listen(Rtdb.ref("rocket/$u")) { s ->
                val v = s.child("exp").getValue(Long::class.java) ?: 0L
                if (v > exp) exp = v
            }
        } catch (e: Exception) {
            live = false
        }
        // Cadangan: kalau aturan database belum mengizinkan node "rocket",
        // EXP tetap tersimpan di node pub milik sendiri.
        try {
            Rtdb.listen(Rtdb.ref("pub/$u/rocketExp")) { s ->
                val v = s.getValue(Long::class.java) ?: 0L
                if (v > exp) exp = v
            }
        } catch (e: Exception) {
        }
    }

    /** Tambah EXP roket dari koin yang dipakai untuk gift. */
    suspend fun addExp(coins: Long) {
        val u = uid()
        if (u.isBlank() || coins <= 0L) return
        exp += coins
        val total = exp
        val saved = try {
            Rtdb.ref("rocket/$u/exp").setValue(total).await()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!saved) {
            try {
                Rtdb.ref("pub/$u/rocketExp").setValue(total).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }
}

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
fun RocketRail(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    RocketCfg.start()
    LaunchedEffect(Unit) { RocketRepo.start() }
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
fun RocketScreen(onDismiss: () -> Unit) {
    RocketCfg.start()
    LaunchedEffect(Unit) { RocketRepo.start() }
    val total = RocketRepo.exp
    // Tombol back menutup panel roket saja, tidak keluar dari room.
    BackHandler { onDismiss() }

    val expList = RocketCfg.exp
    val lv = RocketCfg.levelOf(total)
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
                "Naikkan level roket dengan mengirim gift",
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
    }
}
