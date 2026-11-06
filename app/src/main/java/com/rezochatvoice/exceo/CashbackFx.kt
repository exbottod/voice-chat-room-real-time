package com.rezochatvoice.exceo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Satu kiriman gift yang terbang ke avatar penerima.
 * Diambil dari "giftFly/{roomId}" di Realtime Database.
 */
data class GiftFly(
    val from: String,
    val toUid: String,
    val toName: String,
    val seat: Int,
    val emoji: String,
    val price: Long,
    val cb: Long,
    val pecahan: Long,
    val parts: List<Long> = emptyList(),
    val asset: String,
    val mediaUrl: String = "",
    val mediaType: String = "",
    val mult: Int = 0,
    val combo: Int = 0,
    val gname: String = "",
    val jp: Boolean,
    val ts: Long,
)

/**
 * Efek gift terbang: gift meluncur dari sisi pengirim menuju kursi mic penerima
 * (atau ikon user online kalau penerima tidak di mic), lalu pecahan cashback
 * muncul di titik itu. Kalau nilainya besar, banner JP tampil di atas.
 */
@Composable
fun GiftFlyOverlay(roomId: String, seatPoints: Map<Int, Offset>) {
    val flights = remember { mutableStateListOf<GiftFly>() }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(roomId) {
        var isInit = true
        val reg = Rtdb.listen(Rtdb.ref("giftFly/$roomId").limitToLast(1)) { snap ->
            val c = snap.children.lastOrNull()
            val ts = c?.child("ts")?.getValue(Long::class.java) ?: 0L
            val key = c?.key
            if (isInit) {
                isInit = false
                // Saat baru masuk room: gift lama (lebih dari 6 detik) tidak diputar.
                lastKey = if (ts > System.currentTimeMillis() - 6000L) null else key
                return@listen
            }
            if (c != null && key != null && key != lastKey) {
                lastKey = key
                flights.add(
                    GiftFly(
                    from = c.child("from").getValue(String::class.java) ?: "",
                    toUid = c.child("toUid").getValue(String::class.java) ?: "",
                    toName = c.child("toName").getValue(String::class.java) ?: "",
                    seat = (c.child("seat").getValue(Long::class.java) ?: -1L).toInt(),
                    emoji = c.child("emoji").getValue(String::class.java) ?: "🎁",
                    price = c.child("price").getValue(Long::class.java) ?: 0L,
                    cb = c.child("cb").getValue(Long::class.java) ?: 0L,
                    pecahan = c.child("pecahan").getValue(Long::class.java) ?: 0L,
                    parts = (c.child("parts").getValue(String::class.java) ?: "")
                        .split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0L },
                    asset = c.child("asset").getValue(String::class.java) ?: "",
                    mediaUrl = c.child("mediaUrl").getValue(String::class.java) ?: "",
                    mediaType = c.child("mediaType").getValue(String::class.java) ?: "",
                    mult = (c.child("mult").getValue(Long::class.java) ?: 0L).toInt(),
                    combo = (c.child("combo").getValue(Long::class.java) ?: 0L).toInt(),
                    gname = c.child("gname").getValue(String::class.java) ?: "",
                    jp = c.child("jp").getValue(Boolean::class.java) ?: false,
                    ts = ts,
                    ),
                )
            }
        }
        onDispose { reg.remove() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                box = it.size
            },
    ) {
        if (box.width > 0) {
            flights.toList().forEach { f ->
                key(f.ts, f.seat, f.toUid) {
                    GiftFlyOnce(f, seatPoints, origin, box) { flights.remove(f) }
                }
            }
            // Banner biru kombo + angka kombo emas besar, gaya referensi.
            val latest = flights.lastOrNull()
            if (latest != null && latest.combo >= 1) {
                key(latest.ts) { ComboBanner(latest) }
            }
        }
    }
}

@Composable
private fun GiftFlyOnce(
    f: GiftFly,
    seatPoints: Map<Int, Offset>,
    origin: Offset,
    box: IntSize,
    onDone: () -> Unit,
) {
    val prog = remember(f.ts) { Animatable(0f) }
    var landed by remember(f.ts) { mutableStateOf(false) }

    // Titik tujuan: avatar kursi mic penerima. Kalau tidak di mic, ikon user online.
    val target = remember(f.ts, seatPoints, origin, box) {
        val abs = if (f.seat >= 0) seatPoints[f.seat] else null
        val p = abs ?: Offset(box.width * 0.74f, box.height * 0.40f)
        Offset(p.x - origin.x, p.y - origin.y)
    }
    val start = Offset(box.width * 0.14f, box.height * 0.88f)
    // Aset pecahan sudah ikut di dalam aplikasi: cashback/wois_gift_number_<nilai>.svga
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val localAsset = remember(f.ts, f.pecahan, ctx) {
        val path = "cashback/wois_gift_number_${f.pecahan}.svga"
        try {
            if (f.pecahan <= 0L) "" else {
                ctx.assets.open(path).close()
                "asset/$path"
            }
        } catch (e: Exception) { "" }
    }
    val playSrc = localAsset.ifBlank { f.asset }

    // Gift yang mendarat memudar lalu hilang, tidak berhenti menempel di layar.
    val giftAlpha = remember(f.ts) { Animatable(1f) }

    LaunchedEffect(f.ts) {
        prog.animateTo(1f, tween(durationMillis = 750, easing = FastOutSlowInEasing))
        landed = true
        delay(650L)
        giftAlpha.animateTo(0f, tween(320))
        // Tunggu pecahan selesai diputar sebelum efektor dilepas.
        delay(maxOf(0L, 700L + f.parts.size * 260L))
        onDone()
    }

    if (!landed) {
        val t = prog.value
        val x = start.x + (target.x - start.x) * t
        val y = start.y + (target.y - start.y) * t - 110f * sin(PI * t).toFloat()
        Box(
            Modifier
                .offset { IntOffset(x.toInt(), y.toInt()) }
                .size(78.dp),
            contentAlignment = Alignment.Center,
        ) {
            FlyMedia(f)
        }
        return
    }

    // Sampai di avatar penerima: pecahan cashback pecah di titik itu.
    Box(
        Modifier
            .offset { IntOffset((target.x - 140f).toInt(), (target.y - 140f).toInt()) }
            .size(280.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Gift dan pecahan diputar terpisah: gift tetap di sini, lalu angka pecahan
        // (hasil harga gift x pengali) muncul satu per satu di sekitar titik ini.
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer { alpha = giftAlpha.value },
            contentAlignment = Alignment.Center,
        ) {
            FlyMedia(f)
        }
        // Pecahan disebar ke seluruh layar, bukan menumpuk di avatar.
        f.parts.forEachIndexed { i, v -> PartPop(v, i, box) }
        if (f.mult > 0 && f.cb > 0) MultBall(f.mult, f.cb, target)
    }
    if (f.cb > 0) {
        Box(
            Modifier
                .offset { IntOffset((target.x - 110f).toInt(), (target.y + 62f).toInt()) }
                .width(220.dp)
                .background(Color(0xCC000000), RoundedCornerShape(50))
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tr("+${fmtNum(f.cb)}"),
                color = Color(0xFFFFD54F),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    // Banner JP: hanya untuk cashback besar, terlihat semua orang di room.
    if (f.jp && f.cb > 0) {
        Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .background(Color(0xE64A148C), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    tr("💥 JP ${f.from} +${fmtNum(f.cb)}"),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}


/**
 * Satu pecahan cashback: angka besar (SVGA dari aset aplikasi) muncul bergantian
 * di sekitar avatar penerima. Nilai pecahan = harga gift x pengali undian.
 */
@Composable
private fun PartPop(value: Long, index: Int, box: IntSize) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(value, index) {
        kotlinx.coroutines.delay(index * 220L)
        scale.animateTo(1.12f, tween(200))
        scale.animateTo(1f, tween(110))
    }
    // Semua pecahan tampil DI TENGAH layar, menggerombol rapat di tengah seperti
    // di aplikasi acuan: satu di tengah, sisanya mengelilingi titik tengah.
    val spots = listOf(
        Pair(0f, 0f), Pair(-86f, -78f), Pair(86f, -78f),
        Pair(-86f, 74f), Pair(86f, 74f), Pair(0f, -150f),
        Pair(0f, 148f), Pair(0f, 0f),
    )
    val spot = spots[index % spots.size]
    val size = 156f
    // Angka pecahan bisa jauh lebih besar dari aset yang ada (aset maksimal
    // 10.000). Supaya animasi SVGA tetap muncul, dipakai aset terbesar yang
    // tidak melebihi nilai pecahan, lalu nilai sebenarnya ditulis di bawahnya.
    val aset = assetPecahan(value)
    val cx = box.width / 2f
    val cy = box.height * 0.46f
    Box(
        Modifier
            .offset {
                IntOffset(
                    (cx + spot.first - size / 2f).toInt(),
                    (cy + spot.second - size / 2f).toInt(),
                )
            }
            .size(156.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                GiftSvga("asset/cashback/wois_gift_number_" + aset + ".svga", "🪙", Modifier.fillMaxSize(), onEnd = { })
            }
            // Nilai pecahan yang sebenarnya, supaya jumlahnya tetap terbaca.
            Text(
                fmtCompact(value),
                color = Color(0xFFFFD24D),
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .offset { IntOffset(0, (-6f).toInt()) }
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x88000000))
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Aset angka yang tersedia di aplikasi: 10, 20, 30, 50, 66, 88, 100, 200, 300,
 * 400, 500, 666, 888, 1000, 2000, 3000, 4000, 6000, 8000, 9000, 10000.
 * Dipilih yang paling besar tetapi tidak melebihi nilai pecahan.
 */
private val ASET_PECAHAN = longArrayOf(
    10, 20, 30, 50, 66, 88, 100, 200, 300, 400, 500, 666, 888,
    1000, 2000, 3000, 4000, 6000, 8000, 9000, 10000,
)

private fun assetPecahan(v: Long): Long {
    var pilih = ASET_PECAHAN[0]
    for (a in ASET_PECAHAN) if (a <= v) pilih = a
    return pilih
}


/** Media gift apa adanya (gambar/svga) untuk animasi terbang; emoji kalau kosong. */
@Composable
private fun FlyMedia(f: GiftFly) {
    val url = f.mediaUrl
    when {
        url.isBlank() -> Text(f.emoji.ifBlank { "🎁" }, fontSize = 34.sp)
        isVideoMedia(url, f.mediaType) ->
            Text(f.emoji.ifBlank { "🎁" }, fontSize = 34.sp)
        url.substringBefore('?').lowercase().endsWith(".svga") ||
            f.mediaType.lowercase().startsWith("svga") ->
            GiftSvga(url, f.emoji, Modifier.fillMaxSize(), onEnd = { })
        else -> coil.compose.AsyncImage(
            model = url,
            contentDescription = f.emoji,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
}

/** Bola kemenangan: "win {total}" dan "{mult} Times", muncul di avatar penerima. */
@Composable
private fun MultBall(mult: Int, total: Long, center: Offset) {
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(mult, total) {
        pop.animateTo(1.06f, tween(260))
        pop.animateTo(1f, tween(120))
    }
    Box(
        Modifier
            .offset { IntOffset((center.x - 78f).toInt(), (center.y + 26f).toInt()) }
            .size(156.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Color(0xFFFFE082), Color(0xFFFF9800)),
                ),
                androidx.compose.foundation.shape.CircleShape,
            )
            .border(3.dp, Color(0xFFFFF8E1), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(Color(0x66FFFFFF), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("🪙", fontSize = 18.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                tr("win ${fmtNum(total)}"),
                color = Color(0xFF4A2600),
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                tr("$mult Times"),
                color = Color(0xFF7A3E00),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}


/** Banner biru: pengirim, gift, xKombo, dan total cashback (+12K) di sisi kanan. */
@Composable
private fun ComboBanner(f: GiftFly) {
    var show by remember(f.ts) { mutableStateOf(true) }
    LaunchedEffect(f.ts) {
        kotlinx.coroutines.delay(3200)
        show = false
    }
    if (!show) return
    val k = if (f.cb >= 1000L) "+${fmtNum(f.cb / 1000L)}K" else "+${fmtNum(f.cb)}"
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(top = 60.dp)
                .fillMaxWidth(0.94f)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color(0xFF3D5AFE), Color(0xFF6C8CFF)),
                    ),
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topEnd = 40.dp, bottomEnd = 40.dp, topStart = 40.dp, bottomStart = 40.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(f.from, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    tr("ke ${f.toName.ifBlank { "semua" }}"),
                    color = Color(0xFFE3E9FF),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Text(f.emoji.ifBlank { "🎁" }, fontSize = 22.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                tr("x${f.combo}"),
                color = Color(0xFFFFE082),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
            if (f.cb > 0) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color(0xFFD50000), Color(0xFF8E0000)),
                            ),
                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        )
                        .border(
                            2.dp, Color(0xFFFFD54F),
                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(k, color = Color(0xFFFFE082), fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        if (f.combo >= 2) {
            Text(
                tr("${f.combo}"),
                color = Color(0xFFFFC107),
                fontSize = 96.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
