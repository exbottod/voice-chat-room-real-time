package com.example.googlelogin

import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer

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

    /** Pesan error terakhir saat memuat aset (mis. rules Firestore menolak). null = lancar. */
    var lastError by mutableStateOf<String?>(null)
        private set

    fun get(n: Int, slot: String): VipAsset? = cfg["${n}_$slot"]

    /** Dengarkan aset VIP secara langsung: upload dari panel developer langsung tampil tanpa buka ulang app. */
    fun start(force: Boolean = false) {
        if (reg != null && !force) return
        reg?.remove()
        reg = FirebaseFirestore.getInstance().collection("vipConfig")
            .whereEqualTo("meta", true)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    lastError = e.message ?: e.toString()
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
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
                cfg.clear()
                cfg.putAll(fresh)
                lastError = null
            }
    }

    suspend fun load(force: Boolean = false) { start(force) }

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
            try { cache.writeBytes(b) } catch (e: Exception) { }
            blobs[key] = b
            b
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
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
private fun VipVideo(a: VipAsset, data: ByteArray?, modifier: Modifier, loop: Boolean, onEnd: () -> Unit) {
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
                    mp.setVolume(0f, 0f)
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
                        override fun onFinished() { onEnd() }
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
) {
    val data by produceState<ByteArray?>(null, a.n, a.slot, a.updatedAt) {
        value = if (a.hasData) VipAssets.bytes(a) else null
    }
    val remote = a.url.isNotBlank()
    if (!remote && data == null) return
    when (a.kind) {
        "mp4" -> VipVideo(a, data, modifier, loop, onEnd)
        "svga" -> VipSvga(a, data, modifier, loop, contentScale, onEnd)
        else -> AsyncImage(
            model = if (remote) a.url else ByteBuffer.wrap(data!!),
            contentDescription = null,
            imageLoader = rememberVipLoader(),
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/** Gelar VIP (medal). Pakai gambar dari panel developer kalau ada, kalau tidak pakai lencana teks. */
@Composable
fun VipTag(n: Int, modifier: Modifier = Modifier) {
    val a = VipAssets.get(n, "title")
    if (a != null) VipMedia(a, modifier.size(60.dp, 24.dp), loop = true)
    else VipTagText(n, modifier)
}

/** Bingkai eksklusif VIP ditumpuk di atas avatar (tidak menangkap sentuhan). */
@Composable
fun VipFrame(tier: Int, size: Dp) {
    if (tier !in 1..9) return
    val a = VipAssets.get(tier, "frame") ?: return
    VipMedia(a, Modifier.requiredSize(size), loop = true)
}

/** Efek masuk VIP di room: mp4 atau gif/webp/svga, diputar sebentar di bagian atas layar. */
@Composable
fun VipEntryEffect(roomId: String) {
    var playing by remember { mutableStateOf<VipAsset?>(null) }
    var first by remember { mutableStateOf(true) }
    var lastId by remember { mutableStateOf<String?>(null) }
    val myUid = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    LaunchedEffect(Unit) { VipAssets.load() }
    DisposableEffect(roomId) {
        val reg = RoomRepo.observeMessages(roomId, 1) { list ->
            val last = list.lastOrNull()
            if (first) {
                first = false
                lastId = last?.id
                val fresh = last != null && System.currentTimeMillis() - last.createdAt < 8000
                if (last != null && fresh && last.vipUid == myUid) {
                    entryAssetOf(last)?.let { playing = it }
                }
            } else if (last != null && last.id != lastId) {
                lastId = last.id
                entryAssetOf(last)?.let { playing = it }
            }
        }
        onDispose { reg.remove() }
    }
    val p = playing
    if (p != null) {
        LaunchedEffect(p) {
            delay(if (p.kind == "mp4" || p.kind == "svga") 15000L else 5000L)
            playing = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VipMedia(
                p,
                Modifier.fillMaxSize(),
                loop = false,
                contentScale = ContentScale.Crop,
                onEnd = { playing = null },
            )
        }
    }
}
