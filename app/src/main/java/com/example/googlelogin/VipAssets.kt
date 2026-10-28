package com.example.googlelogin

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/** Aset VIP yang diatur dari panel developer: slot = title | frame | entry, kind = image | svga | mp4. */
data class VipAsset(
    val n: Int,
    val slot: String,
    val kind: String,
    val url: String,
    val hasData: Boolean,
    val updatedAt: Long,
    val chunks: Int = 0,
)

object VipAssets {
    private val cfg = mutableStateMapOf<String, VipAsset>()
    private val blobs = HashMap<String, ByteArray>()
    private var reg: ListenerRegistration? = null
    // UTAMA: aset VIP kini dari Realtime Database (vipCfg) supaya upload panel
    // developer tetap tersimpan walau kuota Firestore habis. Firestore jadi cadangan.
    private var rtdbReg: Reg? = null
    private val fsMap = HashMap<String, VipAsset>()
    private val rtdbMap = HashMap<String, VipAsset>()

    /** Pesan error terakhir saat memuat aset (mis. rules Firestore menolak). null = lancar. */
    var lastError by mutableStateOf<String?>(null)
        private set

    fun get(n: Int, slot: String): VipAsset? = cfg["${n}_$slot"]

    private fun apply() {
        val merged = HashMap<String, VipAsset>()
        merged.putAll(fsMap)
        merged.putAll(rtdbMap) // RTDB selalu menang
        cfg.clear()
        cfg.putAll(merged)
    }

    /** Dengarkan aset VIP secara langsung: upload dari panel developer langsung tampil tanpa buka ulang app. */
    fun start(force: Boolean = false) {
        if (reg != null && !force) return
        reg?.remove()
        rtdbReg?.remove()
        // REALTIME DATABASE (utama): node vipCfg/{vipN_slot}.
        rtdbReg = Rtdb.listen(Rtdb.ref("vipCfg")) { snap ->
            rtdbMap.clear()
            for (c in snap.children) {
                val n = (c.child("n").getValue(Long::class.java) ?: 0L).toInt()
                val slot = c.child("slot").getValue(String::class.java) ?: continue
                if (n !in 1..9) continue
                rtdbMap["${n}_$slot"] = VipAsset(
                    n, slot,
                    c.child("kind").getValue(String::class.java) ?: "image",
                    c.child("url").getValue(String::class.java) ?: "",
                    c.child("hasData").getValue(Boolean::class.java) ?: false,
                    c.child("updatedAt").getValue(Long::class.java) ?: 0L,
                    (c.child("chunks").getValue(Long::class.java) ?: 0L).toInt(),
                )
            }
            apply()
            lastError = null
        }
        // FIRESTORE (warisan lama): dimuat SEKALI per buka app, bukan listener
        // realtime, supaya hemat kuota baca harian Firestore.
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val snap = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                    FirebaseFirestore.getInstance().collection("vipConfig")
                        .whereEqualTo("meta", true).get().await()
                }
                if (snap != null) {
                    val fresh = HashMap<String, VipAsset>()
                    for (d in snap.documents) {
                        val n = (d.getLong("n") ?: 0L).toInt()
                        val slot = d.getString("slot") ?: continue
                        if (n !in 1..9) continue
                        fresh["${n}_$slot"] = VipAsset(
                            n, slot,
                            d.getString("kind") ?: "image",
                            d.getString("url") ?: "",
                            d.getBoolean("hasData") ?: false,
                            d.getLong("updatedAt") ?: 0L,
                            (d.getLong("chunks") ?: 0L).toInt(),
                        )
                    }
                    fsMap.clear()
                    fsMap.putAll(fresh)
                    apply()
                    lastError = null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                QuotaGuard.handle(e)
            }
        }
    }

    suspend fun load(force: Boolean = false) {
        start(force)
    }

    /** Ambil isi file yang di-upload dari HP (disimpan base64 di Firestore). Di-cache di memori. */
    suspend fun bytes(a: VipAsset): ByteArray? {
        val key = "${a.n}_${a.slot}_${a.updatedAt}"
        blobs[key]?.let { return it }
        return try {
            val ctx = FirebaseApp.getInstance().applicationContext
            val cache = File(ctx.cacheDir, "vipasset_$key.bin")
            if (cache.exists() && cache.length() > 0) {
                val cached = cache.readBytes()
                blobs[key] = cached
                return cached
            }
            val col = FirebaseFirestore.getInstance().collection("vipConfig")
            val sb = StringBuilder()
            val base = if (a.n == 0) "data_store_${a.slot.removePrefix("st_")}" else "data_vip${a.n}_${a.slot}"
            if (a.chunks > 0) {
                for (i in 0 until a.chunks) {
                    sb.append(col.document("${base}_$i").get().await().getString("data") ?: return null)
                }
            } else {
                sb.append(col.document(base).get().await().getString("data") ?: return null)
            }
            val s = sb.toString()
            val b = Base64.decode(s.substringAfter("base64,", s), Base64.DEFAULT)
            try {
                cache.writeBytes(b)
            } catch (e: Exception) {
            }
            blobs[key] = b
            b
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            QuotaGuard.handle(e)
            null
        }
    }
}

fun vipFrameTier(frameId: String?, vip: Int, until: Long): Int {
    if (!frameId.isNullOrBlank()) {
        return if (frameId.startsWith("vipf_")) frameId.removePrefix("vipf_").toIntOrNull() ?: 0 else 0
    }
    return if (vipActive(vip, until)) vip else 0
}

fun hasVipFrame(tier: Int): Boolean = tier in 1..9 && VipAssets.get(tier, "frame") != null

/** Tingkat VIP untuk gelembung obrolan: pakai vipb_N yang dipakai, atau otomatis mengikuti VIP aktif. */
fun vipBubbleTier(bubbleId: String?, vip: Int, until: Long): Int {
    if (!bubbleId.isNullOrBlank()) {
        return if (bubbleId.startsWith("vipb_")) bubbleId.removePrefix("vipb_").toIntOrNull() ?: 0 else 0
    }
    return if (vipActive(vip, until)) vip else 0
}

/**
 * Ukur rasio lebar:tinggi aset supaya lencana/gelar panjang tidak digencet jadi kotak.
 * Bisa: gambar (png/jpg/webp/gif) dan svga. Return null kalau tidak terukur.
 */
internal suspend fun measureAssetRatio(ctx: android.content.Context, a: VipAsset): Float? {
    return when (a.kind) {
        "svga" -> {
            // Ambil bytes DULU (suspend), karena blok suspendCancellableCoroutine bukan suspend.
            val data: ByteArray? = if (a.url.isBlank()) VipAssets.bytes(a) else null
            if (a.url.isBlank() && data == null) {
                null
            } else {
                suspendCancellableCoroutine<Float?> { cont ->
                    val parser = SVGAParser(ctx)
                    val done = object : SVGAParser.ParseCompletion {
                        override fun onComplete(videoItem: SVGAVideoEntity) {
                            if (cont.isActive) {
                                val w = videoItem.videoSize.width
                                val h = videoItem.videoSize.height
                                cont.resume(if (h > 0.0) (w / h).toFloat() else null)
                            }
                        }

                        override fun onError() {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    if (a.url.isNotBlank()) {
                        parser.decodeFromURL(java.net.URL(a.url), done)
                    } else {
                        parser.decodeFromInputStream(
                            ByteArrayInputStream(data!!),
                            "ratio_${a.n}_${a.slot}_${a.updatedAt}",
                            done,
                            true,
                        )
                    }
                }
            }
        }
        "mp4" -> null
        else -> {
            if (a.url.isNotBlank()) {
                val res = coil.Coil.imageLoader(ctx).execute(
                    ImageRequest.Builder(ctx).data(a.url).allowHardware(false).build(),
                )
                val d = res.drawable
                if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                    d.intrinsicWidth.toFloat() / d.intrinsicHeight
                } else {
                    null
                }
            } else {
                val data = VipAssets.bytes(a)
                if (data == null) {
                    null
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, o)
                    if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
                }
            }
        }
    }
}

@Composable
private fun rememberVipLoader(): ImageLoader {
    val ctx = LocalContext.current
    return remember {
        ImageLoader.Builder(ctx).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
}

@Composable
private fun VipVideo(a: VipAsset, data: ByteArray?, modifier: Modifier, loop: Boolean, onEnd: () -> Unit, sound: Boolean) {
    val ctx = LocalContext.current
    val uri: Uri? = remember(a.n, a.slot, a.updatedAt, data) {
        if (a.url.isNotBlank()) {
            Uri.parse(a.url)
        } else if (data != null) {
            val f = File(ctx.cacheDir, "vip_${a.n}_${a.slot}_${a.updatedAt}.mp4")
            if (!f.exists()) f.writeBytes(data)
            Uri.fromFile(f)
        } else {
            null
        }
    }
    if (uri == null) return
    AndroidView(
        modifier = modifier,
        factory = { c ->
            VideoView(c).apply {
                setVideoURI(uri)
                setOnPreparedListener { mp ->
                    mp.isLooping = loop
                    // Efek masuk bisa bersuara; aset lain (bingkai/gelar) tetap diam.
                    val vol = if (sound) 1f else 0f
                    mp.setVolume(vol, vol)
                    start()
                }
                setOnCompletionListener { onEnd() }
                setOnErrorListener { _, _, _ -> onEnd(); true }
            }
        },
    )
}

@Composable
private fun VipSvga(
    a: VipAsset,
    data: ByteArray?,
    modifier: Modifier,
    loop: Boolean,
    contentScale: ContentScale,
    onEnd: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            SVGAImageView(c).apply {
                loops = if (loop) 0 else 1
                clearsAfterStop = true
                scaleType = when (contentScale) {
                    ContentScale.Crop -> android.widget.ImageView.ScaleType.CENTER_CROP
                    ContentScale.FillBounds -> android.widget.ImageView.ScaleType.FIT_XY
                    else -> android.widget.ImageView.ScaleType.FIT_CENTER
                }
                if (!loop) {
                    callback = object : com.opensource.svgaplayer.SVGACallback {
                        override fun onPause() {}
                        override fun onFinished() {
                            onEnd()
                        }

                        override fun onRepeat() {}
                        override fun onStep(frame: Int, percentage: Double) {}
                    }
                }
                val parser = SVGAParser(c)
                val done = object : SVGAParser.ParseCompletion {
                    override fun onComplete(videoItem: SVGAVideoEntity) {
                        setVideoItem(videoItem)
                        startAnimation()
                    }

                    override fun onError() {}
                }
                if (a.url.isNotBlank()) {
                    parser.decodeFromURL(java.net.URL(a.url), done)
                } else if (data != null) {
                    parser.decodeFromInputStream(
                        ByteArrayInputStream(data), "vip_${a.n}_${a.slot}_${a.updatedAt}", done, true,
                    )
                }
            }
        },
    )
}

/** Tampilkan satu aset VIP (gambar/gif/webp, svga, atau mp4). */
@Composable
fun VipMedia(
    a: VipAsset,
    modifier: Modifier = Modifier,
    loop: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit,
    onEnd: () -> Unit = {},
    sound: Boolean = false,
) {
    val data by produceState<ByteArray?>(null, a.n, a.slot, a.updatedAt) {
        value = if (a.hasData) VipAssets.bytes(a) else null
    }
    val remote = a.url.isNotBlank()
    if (!remote && data == null) return
    when (a.kind) {
        "mp4" -> VipVideo(a, data, modifier, loop, onEnd, sound)
        "svga" -> VipSvga(a, data, modifier, loop, contentScale, onEnd)
        else -> AsyncImage(
            model = if (remote) a.url else ByteBuffer.wrap(data!!),
            contentDescription = null,
            imageLoader = rememberVipLoader(),
            modifier = modifier,
            contentScale = contentScale,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
    }
}

/** Gelar VIP (medal). Pakai gambar dari panel developer kalau ada, kalau tidak pakai lencana teks. */
@Composable
fun VipTag(n: Int, modifier: Modifier = Modifier) {
    val a = VipAssets.get(n, "title")
    if (a == null) {
        VipTagText(n, modifier)
        return
    }
    // Gelar custom: tinggi tetap 24.dp, lebar otomatis mengikuti rasio file asli.
    var ratio by remember(a.n, a.slot, a.updatedAt) { mutableStateOf(2.5f) }
    val ctx = LocalContext.current
    LaunchedEffect(a.n, a.slot, a.updatedAt) {
        ratio = runCatching { measureAssetRatio(ctx, a) }.getOrNull() ?: 2.5f
    }
    VipMedia(a, modifier.height(24.dp).width((24f * ratio).dp), loop = true)
}

/** Bingkai eksklusif VIP ditumpuk di atas avatar (tidak menangkap sentuhan). */
@Composable
fun VipFrame(tier: Int, size: Dp) {
    if (tier !in 1..9) return
    val a = VipAssets.get(tier, "frame") ?: return
    VipMedia(a, Modifier.requiredSize(size), loop = true)
}

/** Ic banner efek masuk VIP1-9 (drawable di dalam APK). */
fun vipBannerRes(n: Int): Int = when (n) {
    1 -> R.drawable.ic_banner1
    2 -> R.drawable.ic_banner2
    3 -> R.drawable.ic_banner3
    4 -> R.drawable.ic_banner4
    5 -> R.drawable.ic_banner5
    6 -> R.drawable.ic_banner6
    7 -> R.drawable.ic_banner7
    8 -> R.drawable.ic_banner8
    9 -> R.drawable.ic_banner9
    else -> R.drawable.ic_banner1
}

/** Banner efek masuk dari pesan "masuk kamar":
 *  item store custom (sx_) MENANG, lalu item banner VIP (vipbn_, drawable di APK),
 *  terakhir otomatis sesuai level VIP user. */
fun bannerAssetOf(m: RoomMsg): VipAsset? =
    if (m.banner.startsWith("sx_")) StoreCatalog.find(m.banner)?.asset else null

fun bannerResOf(m: RoomMsg): Int? = when {
    m.banner.startsWith("vipbn_") -> m.banner.removePrefix("vipbn_").toIntOrNull()?.let { vipBannerRes(it) }
    m.vip in 1..9 -> vipBannerRes(m.vip)
    else -> null
}

/** Efek masuk VIP di room: mp4 atau gif/webp/svga, diputar sebentar di bagian atas layar.
 *  Banner efek masuk (kalau dipakai) tampil DI DEPAN efek masuk. */
@Composable
fun VipEntryEffect(roomId: String) {
    var playing by remember { mutableStateOf<VipAsset?>(null) }
    var banner by remember { mutableStateOf<VipAsset?>(null) }
    var bannerRes by remember { mutableStateOf<Int?>(null) }
    var bannerName by remember { mutableStateOf<String?>(null) }
    var bannerUid by remember { mutableStateOf<String?>(null) }
    var first by remember { mutableStateOf(true) }
    var lastId by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<RoomMsg?>(null) }
    val myUid = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }

    LaunchedEffect(Unit) {
        VipAssets.load()
        // Pastikan katalog store termuat: efek masuk sx_ butuh data ini.
        StoreCatalog.start()
    }

    // Pesan masuk dengan item sx_ yang belum ketemu di katalog (katalog masih
    // termuat / offline): coba lagi sampai ketemu supaya efeknya konsisten.
    // Nama user untuk banner: dari pesan, atau dari cache profil bila pesan tanpa nama.
    fun msgName(m: RoomMsg): String {
        if (m.name.isNotBlank()) return m.name
        val n = UserCache.peek(m.vipUid)?.name
        return if (!n.isNullOrBlank()) n else ""
    }

    LaunchedEffect(pending) {
        val m = pending ?: return@LaunchedEffect
        repeat(15) {
            val a = entryAssetOf(m)
            val b = bannerAssetOf(m)
            if (a != null || b != null || bannerResOf(m) != null) {
                playing = a
                banner = b
                bannerRes = if (b != null) null else bannerResOf(m)
                bannerName = msgName(m).takeIf { it.isNotBlank() }
                bannerUid = m.vipUid.takeIf { it.isNotBlank() }
                return@LaunchedEffect
            }
            delay(400)
        }
        pending = null
    }

    DisposableEffect(roomId) {
        val reg = RoomRepo.observeMessages(roomId, 1) { list ->
            val last = list.lastOrNull()
            if (first) {
                first = false
                lastId = last?.id
                val fresh = last != null && System.currentTimeMillis() - last.createdAt < 8000
                if (last != null && fresh && last.vipUid == myUid) {
                    val a = entryAssetOf(last)
                    val b = bannerAssetOf(last)
                    val br = bannerResOf(last)
                    if (a != null || b != null || br != null) {
                        playing = a
                        banner = b
                        bannerRes = if (b != null) null else br
                        bannerName = msgName(last).takeIf { it.isNotBlank() }
                        bannerUid = last.vipUid.takeIf { it.isNotBlank() }
                    } else if (last.entry.startsWith("sx_") || last.banner.startsWith("sx_")) pending = last
                }
            } else if (last != null && last.id != lastId) {
                lastId = last.id
                val a = entryAssetOf(last)
                val b = bannerAssetOf(last)
                val br = bannerResOf(last)
                if (a != null || b != null || br != null) {
                    playing = a
                    banner = b
                    bannerRes = if (b != null) null else br
                    bannerName = msgName(last).takeIf { it.isNotBlank() }
                        bannerUid = last.vipUid.takeIf { it.isNotBlank() }
                } else if (last.entry.startsWith("sx_") || last.banner.startsWith("sx_")) pending = last
            }
        }
        onDispose { reg.remove() }
    }

    val p = playing
    val b = banner
    val br = bannerRes
    // Posisi geser banner: 1f = penuh di kanan layar, 0f = tengah, negatif = keluar kiri.
    val slide = remember { Animatable(1f) }
    if (p != null || b != null || br != null) {
        if (p != null) {
            LaunchedEffect(p) {
                delay(if (p.kind == "mp4" || p.kind == "svga") 15000L else 5000L)
                playing = null
            }
        }
        if (b != null || br != null) {
            // Banner geser: dari kanan CEPAT ke tengah, di tengah LAMBAT,
            // saat menutup CEPAT lagi ke kiri. Total 3 detik.
            LaunchedEffect(b, br) {
                slide.snapTo(1f)
                slide.animateTo(0f, tween(800, easing = FastOutSlowInEasing))
                slide.animateTo(-0.08f, tween(1400, easing = LinearEasing))
                slide.animateTo(-1.25f, tween(800, easing = FastOutLinearInEasing))
                banner = null
                bannerRes = null
                bannerName = null
                bannerUid = null
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (p != null) {
                VipMedia(
                    p,
                    Modifier.fillMaxSize(),
                    loop = false,
                    contentScale = ContentScale.Crop,
                    onEnd = { playing = null },
                    sound = true,
                )
            }
            // Banner efek masuk: di ATAS layar, digambar SETELAH efek jadi tampil di depannya.
            // Ukuran banner TETAP sesuai file aslinya (tidak diperbesar selebar layar).
            if (b != null || br != null) {
                val painter = if (br != null) painterResource(br) else null
                val density = LocalDensity.current
                val bSize = remember(painter, density) {
                    val ins = painter?.intrinsicSize
                    val base = if (ins != null && ins.width > 0f && ins.height > 0f) {
                        with(density) { DpSize(ins.width.toDp(), ins.height.toDp()) }
                    } else DpSize(320.dp, 90.dp)
                    // Banner diperkecil sedikit (80%) supaya tidak kebesaran di layar.
                    DpSize(base.width * 0.8f, base.height * 0.8f)
                }
                BoxWithConstraints(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .statusBarsPadding().padding(top = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(bSize).offset(x = maxWidth * slide.value)) {
                        if (b != null) {
                            VipMedia(b, Modifier.fillMaxSize(), loop = true, contentScale = ContentScale.Fit)
                        } else {
                            Image(painter!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    // Foto profil user dipasang di ujung kiri banner (di atas bola emas).
                    val bu = bannerUid?.let { UserCache.peek(it) }
                    if (bu != null) {
                        val av = (bSize.height.value * 0.75f).dp
                        Box(
                            Modifier.align(Alignment.CenterStart)
                                .padding(start = (bSize.height.value * 0.09f).dp)
                                .size(av + 4.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color(0xFFFFE9A0), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { PublicAvatar(bu, av) }
                    }
                    // Nama user tampil DI DEPAN banner (overlay di tengahnya).
                    val nm = bannerName
                    if (!nm.isNullOrBlank()) {
                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                            Text(
                                nm,
                                color = Color(0xFFFFE9A0),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = Shadow(Color(0xCC000000), blurRadius = 10f),
                                ),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}
