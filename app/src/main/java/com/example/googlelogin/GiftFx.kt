package com.example.googlelogin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Katalog gift: gift default (hardcode) + gift kustom dari koleksi Firestore "gifts"
 * yang dikelola lewat Panel Developer. Semua perangkat otomatis menerima gift baru.
 */
object GiftCatalog {
    var list by mutableStateOf<List<Gift>>(emptyList())
        private set

    /** Gift kustom saja (dipakai Panel Developer). */
    var custom by mutableStateOf<List<Gift>>(emptyList())
        private set
    private var started = false
    private val rtdbMap = HashMap<String, Gift>()   // UTAMA: Realtime Database giftCfg
    private val legacyMap = HashMap<String, Gift>()  // gift lama di Firestore
    private fun apply() {
        custom = rtdbMap.values + legacyMap.values
        // Hanya gift yang diunggah sendiri lewat panel. Gift bawaan (GIFTS)
        // sengaja tidak ditampilkan lagi di tab Hadiah.
        list = custom
    }

    fun start() {
        if (started) return
        started = true
        if (list.isEmpty()) list = emptyList()
        // REALTIME DATABASE (utama): node giftCfg/{id} - tidak kena kuota Firestore.
        Rtdb.listen(Rtdb.ref("giftCfg")) { snap ->
            rtdbMap.clear()
            for (c in snap.children) {
                val name = c.child("name").getValue(String::class.java) ?: continue
                val id = c.key ?: continue
                rtdbMap[id] = Gift(
                    id = id,
                    name = name,
                    emoji = c.child("emoji").getValue(String::class.java) ?: "🎁",
                    price = c.child("price").getValue(Long::class.java) ?: 100L,
                    mediaUrl = c.child("mediaUrl").getValue(String::class.java) ?: "",
                    mediaType = c.child("mediaType").getValue(String::class.java) ?: "",
                    special = c.child("special").getValue(Boolean::class.java) ?: false,
                    jpRate = (c.child("jpRate").getValue(Long::class.java) ?: 0L).toInt(),
                    jpMult = c.child("jpMult").getValue(Long::class.java) ?: 0L,
                    cbRate = (c.child("cbRate").getValue(Long::class.java) ?: 0L).toInt(),
                    cbPrice = (c.child("cbPrice").getValue(Long::class.java) ?: 0L),
                    cashback = c.child("cashback").getValue(Boolean::class.java) ?: false,
                    rocket = c.child("rocket").getValue(Boolean::class.java) ?: false,
                )
            }
            apply()
        }
        // FIRESTORE (warisan lama): dimuat SEKALI per buka app (hemat kuota baca).
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val snap = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                    FirebaseFirestore.getInstance().collection("gifts").get().await()
                }
                if (snap != null) {
                    for (d in snap.documents) {
                        val name = d.getString("name") ?: continue
                        legacyMap[d.id] = Gift(
                            id = d.id,
                            name = name,
                            emoji = d.getString("emoji") ?: "🎁",
                            price = d.getLong("price") ?: 100L,
                            mediaUrl = d.getString("mediaUrl") ?: "",
                            mediaType = d.getString("mediaType") ?: "",
                            special = d.getBoolean("special") ?: false,
                            jpRate = (d.getLong("jpRate") ?: 0L).toInt(),
                            jpMult = d.getLong("jpMult") ?: 0L,
                            cbRate = (d.getLong("cbRate") ?: 0L).toInt(),
                            cbPrice = (d.getLong("cbPrice") ?: 0L),
                            cashback = d.getBoolean("cashback") ?: false,
                            rocket = d.getBoolean("rocket") ?: false,
                        )
                    }
                    apply()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                QuotaGuard.handle(e)
            }
        }
    }
}

/** Efek gift: kombo, JP (jackpot) dan cashback gift khas, serta banner global antar room. */
/**
 * Tentukan format dari isi file kalau nama & MIME tidak membantu.
 * Galeri Android kerap memberi URI tanpa ekstensi dan MIME octet-stream,
 * sehingga file .svga (yang sebenarnya ZIP) dulu ditolak "format tidak didukung".
 */
private fun sniffExt(b: ByteArray): String {
    fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
    if (at(0) == 0x50 && at(1) == 0x4B) return "svga"          // PK.. = SVGA 1.x (zip)
    // SVGA 2.x = protobuf yang dikompres zlib: header 78 01 / 78 5E / 78 9C / 78 DA.
    if (at(0) == 0x78 && (at(1) == 0x01 || at(1) == 0x5E || at(1) == 0x9C || at(1) == 0xDA)) return "svga"
    if (at(0) == 0x89 && at(1) == 0x50) return "png"
    if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return "gif"
    if (at(0) == 0xFF && at(1) == 0xD8) return "jpg"
    if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46) return "webp"
    if (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) return "mp4"
    return ""
}

object GiftFx {

    /** Kombo: kirim gift khas lagi dalam 6 detik = kombo naik. Return jumlah kombo. */
    suspend fun combo(roomId: String, uid: String): Int {
        var out = 1
        Rtdb.tx(Rtdb.ref("giftCombo/$roomId/$uid")) { d ->
            val t = d.child("t").getValue(Long::class.java) ?: 0L
            val now = System.currentTimeMillis()
            out = if (now - t < 6000L) {
                (d.child("c").getValue(Long::class.java) ?: 0L).toInt() + 1
            } else {
                1
            }
            d.child("c").setValue(out.toLong())
            d.child("t").setValue(now)
            null
        }
        return out
    }

    /**
     * Dipanggil SETELAH gift sukses terkirim. Mengurus kombo, JP, cashback, banner
     * global, dan pengumuman di chat room. Return (koin JP, koin cashback).
     */
    suspend fun sent(
        roomId: String,
        uid: String,
        name: String,
        g: Gift,
        count: Int,
        receivers: Int,
        targetUid: String = "",
        targetName: String = "",
        targetSeat: Int = -1,
        seats: List<Triple<String, String, Int>> = emptyList(),
    ): Pair<Long, Long> {
        var jpWin = 0L
        var cbWin = 0L
        var cbPecahan = 0L
        var cbAsset = ""
        var cbParts = ""
        var cbJp = false
        var cbMult = 0
        var comboN = 0
        if (g.special) {
            comboN = try {
                kotlinx.coroutines.withTimeoutOrNull(3000L) { combo(roomId, uid) } ?: 1
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { 1 }
            if (g.jpRate > 0 && (1..100).random() <= g.jpRate) {
                jpWin = g.price * g.jpMult * count
                WalletRepo.topUp(uid, jpWin)
            }
            // Cashback: server yang mengundi pecahan (RTP dan tabel diatur di panel).
            val cb = try {
                kotlinx.coroutines.withTimeoutOrNull(9000L) {
                    // Harga cashback dari panel; kalau kosong pakai harga gift.
                    SupaRepo.giftCashback(uid, if (g.cbPrice > 0L) g.cbPrice else g.price, count, targetUid)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (cb != null && cb.error == null && cb.amount > 0) {
                cbWin = cb.amount
                cbPecahan = if (cb.parts.isNotEmpty()) cb.parts.first() else cb.pecahan
                cbAsset = ""
                cbParts = cb.parts.joinToString(",")
                cbJp = cb.jp
                cbMult = cb.mult
                try {
                    Rtdb.ref("cashback/$uid/total").setValue(ServerValue.increment(cbWin))
                    Rtdb.ref("cashback/$uid/log").push().setValue(
                        hashMapOf<String, Any?>(
                            "g" to g.name,
                            "cb" to cbWin,
                            "p" to cbPecahan,
                            "ts" to System.currentTimeMillis(),
                        ),
                    )
                } catch (e: Exception) { }
            }
        }
        // Server belum siap / belum di-deploy: undi lokal dari pengaturan panel
        // supaya animasi pecahan tetap jalan.
        if (cbWin <= 0L && (cbParts.isBlank() || cbMult <= 0)) {
            val value = (if (g.cbPrice > 0L) g.cbPrice else g.price) * count
            val local = CashbackCfg.roll(value)
            if (local != null && local.parts.isNotEmpty()) {
                cbParts = local.parts.joinToString(",")
                cbPecahan = local.parts.first()
                cbMult = local.mult
                cbWin = local.parts.sum()
                cbJp = cbWin >= local.jpMin
                try { WalletRepo.topUp(uid, cbWin) } catch (e: Exception) { }
                try {
                    Rtdb.ref("cashback/$uid/total").setValue(ServerValue.increment(cbWin))
                    Rtdb.ref("cashback/$uid/log").push().setValue(
                        hashMapOf<String, Any?>(
                            "g" to g.name,
                            "cb" to cbWin,
                            "p" to cbPecahan,
                            "ts" to System.currentTimeMillis(),
                        ),
                    )
                } catch (e: Exception) { }
            }
        }
        // Gift terbang ke avatar penerima, lalu pecahan cashback muncul di titik itu.
        // Dikirim satu per penerima supaya tiap kursi kebagian.
        val flyTo = if (seats.isNotEmpty()) seats else listOf(Triple(targetUid, targetName, targetSeat))
        flyTo.filter { it.third >= 0 || it.second.isNotBlank() }.forEach { t ->
        try {
            Rtdb.ref("giftFly/$roomId").push().setValue(
                hashMapOf<String, Any?>(
                    "from" to name,
                    "toName" to t.second,
                    "toUid" to t.first,
                    "seat" to t.third.toLong(),
                    "gift" to g.name,
                    "emoji" to g.emoji,
                    "mediaUrl" to g.mediaUrl,
                    "mediaType" to g.mediaType,
                    "price" to g.price * count,
                    "cb" to cbWin,
                    "pecahan" to cbPecahan,
                    "parts" to cbParts,
                    "mult" to cbMult,
                    "combo" to comboN,
                    "asset" to cbAsset,
                    "jp" to cbJp,
                    "ts" to System.currentTimeMillis(),
                ),
            )
        } catch (e: Exception) { }
        }
        // EXP roket: bertambah dari koin yang dipakai mengirim gift.
        try {
            RocketRepo.addExp(roomId, name, g.price * count)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            RocketRepo.noteAddFail(e.message ?: "error")
        }
        // Efek layar penuh untuk semua orang di room (mp4/gif/svga) atau emoji besar.
        // Gift cashback dilewati: animasinya sudah terbang ke kursi penerima.
        if (!(g.cashback || g.cbRate > 0)) Rtdb.ref("giftFx/$roomId").push().setValue(
            hashMapOf<String, Any?>(
                "from" to name,
                "gift" to g.name,
                "emoji" to g.emoji,
                "mediaUrl" to g.mediaUrl,
                "mediaType" to g.mediaType,
                "rocket" to g.rocket,
                "count" to count * receivers.coerceAtLeast(1),
                "jp" to (jpWin > 0),
                "combo" to comboN,
                "ts" to System.currentTimeMillis(),
            ),
        )
        val room = try {
            kotlinx.coroutines.withTimeoutOrNull(3000L) { RoomRepo.getRoom(roomId) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        // Gift cashback memakai jalur banner sendiri (giftCbBanner) dan hanya
        // dipublikasikan saat jackpot cashback 10.000 koin ke atas. Gift biasa
        // tetap memakai giftBanner seperti sebelumnya.
        val isCb = g.cashback || g.cbRate > 0
        val cbBig = if (cbWin > jpWin) cbWin else jpWin
        val bannerNode = if (isCb) "giftCbBanner" else "giftBanner"
        if (!isCb || cbBig >= 10_000L) Rtdb.ref(bannerNode).setValue(
            hashMapOf<String, Any?>(
                "roomId" to roomId,
                "roomName" to (room?.name ?: ""),
                "roomNo" to (room?.roomNo ?: ""),
                "ownerUid" to (room?.ownerUid ?: ""),
                "ownerName" to (room?.ownerName ?: ""),
                "micCount" to (room?.micCount ?: 8).toLong(),
                "lockHash" to (room?.lockHash ?: ""),
                "from" to name,
                "gift" to g.name,
                "emoji" to g.emoji,
                "mediaUrl" to g.mediaUrl,
                "mediaType" to g.mediaType,
                "count" to count * receivers.coerceAtLeast(1),
                "jp" to (jpWin > 0),
                "combo" to comboN,
                "cb" to cbBig,
                "ts" to System.currentTimeMillis(),
            ),
        )
        if (comboN >= 2) sysMsg(roomId, "🔥 $name kombo x$comboN ${g.emoji}!")
        if (jpWin > 0) sysMsg(roomId, "🎉 JP! $name memenangkan jackpot ${g.emoji} +$jpWin koin!")
        return jpWin to cbWin
    }

    /**
     * Upload file gift dari perangkat ke Cloudinary lewat endpoint /upload
     * di token-worker (sama seperti upload efek/frame admin). Return (url, error).
     */
    suspend fun uploadMedia(context: android.content.Context, uri: Uri): Pair<String?, String?> {
        val base = BuildConfig.AGORA_TOKEN_URL
        if (base.isBlank()) return null to "AGORA_TOKEN_URL belum diatur"
        // Bentuk endpoint aman untuk semua format URL worker:
        //   https://x.workers.dev, https://x.workers.dev/, https://x.workers.dev/token
        val trimmed = base.trim().trimEnd('/')
        val root = if (trimmed.endsWith("/token") || trimmed.endsWith("/upload")) {
            trimmed.substringBeforeLast('/')
        } else {
            trimmed
        }
        val endpoint = "$root/upload"

        // Tiket login selalu segar.
        val idToken = try {
            FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()?.token
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null to "Sesi login bermasalah, coba login ulang"

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null to "File tidak bisa dibaca"

        if (bytes.size > 25 * 1024 * 1024) return null to "File terlalu besar (maks 25 MB)"

        // URI dari galeri sering tanpa ekstensi (mis. ".../images/100123"), jadi
        // tentukan format dari tipe MIME bila nama file tidak punya ekstensi.
        val allowed = listOf("png", "jpg", "jpeg", "gif", "webp", "mp4", "svga")
        // Nama file asli diambil dari penyedia file (galeri/Files/Vivo sering
        // memberi URI angka tanpa ekstensi, jadi lastPathSegment tidak cukup).
        val displayName = try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        } catch (e: Exception) { null }
        val fname = (displayName ?: uri.lastPathSegment ?: "asset").substringAfterLast('/')
        var ext = fname.substringAfterLast('.', "").lowercase()
        if (ext !in allowed) {
            val mime = (context.contentResolver.getType(uri) ?: "").lowercase()
            ext = when {
                mime.contains("svga") -> "svga"
                mime == "image/png" -> "png"
                mime == "image/jpeg" || mime == "image/jpg" -> "jpg"
                mime == "image/gif" -> "gif"
                mime == "image/webp" -> "webp"
                mime == "video/mp4" -> "mp4"
                mime == "application/zip"
                    || mime == "application/octet-stream"
                    || mime == "application/x-zip-compressed" -> sniffExt(bytes)
                else -> sniffExt(bytes)
            }
        }
        if (ext !in allowed) {
            val head = bytes.take(4).joinToString(" ") { "%02X".format(it) }
            return null to "Format tidak dikenali (nama: $fname, kepala: $head). Gunakan png, jpg, gif, webp, mp4, svga"
        }
        val uploadName = "gift." + (if (ext == "jpeg") "jpg" else ext)
        val boundary = "giftfx${System.currentTimeMillis()}"

        // Coba 2x: koneksi seluler kadang putus di tengah pengiriman.
        var res: Pair<String?, String?> = null to null
        for (attempt in 1..2) {
            res = try {
                withContext(Dispatchers.IO) {
                val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 120000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $idToken")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                c.outputStream.use { out ->
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\ngift\r\n".toByteArray())
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$uploadName\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val code = c.responseCode
                // errorStream bisa null saat server menolak tanpa isi, jadi jangan sampai error aslinya tertelan.
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                val o = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    JSONObject()
                }
                val u = o.optString("url")
                if (u.isNotBlank()) {
                    u to null
                } else {
                    null to o.optString("error").ifBlank { "Upload gagal (HTTP $code)" }
                }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null to "Upload gagal: ${e.message ?: e.javaClass.simpleName}"
            }
            if (res.first != null) break
            if (attempt == 1) delay(2000)
        }
        return res
    }

    private suspend fun sysMsg(roomId: String, text: String) {
        try {
            Rtdb.ref("messages/$roomId").push().setValue(
                hashMapOf<String, Any?>("text" to text, "createdAt" to System.currentTimeMillis()),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }
}

private data class GlobalBannerData(
    val roomId: String,
    val roomName: String,
    val roomNo: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val micCount: Int = 8,
    val lockHash: String = "",
    val from: String,
    val gift: String,
    val emoji: String,
    val mediaUrl: String,
    val mediaType: String = "",
    val rocket: Boolean = false,
    val count: Long,
    val jp: Boolean,
    val combo: Int,
    val ts: Long,
)

/**
 * Efek gift di layar room: saat gift dikirim, semua orang di room melihat
 * efeknya di tengah layar. mediaUrl mp4 -> video (VipMedia), gif/webp ->
 * animasi gambar, kalau tanpa media -> emoji besar. Efek gift khas lebih
 * besar dan lebih lama; gift biasa kecil dan sebentar.
 */
@Composable
fun GiftFxOverlay(roomId: String) {
    var fx by remember { mutableStateOf<GlobalBannerData?>(null) }
    var lastKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(roomId) {
        var isInit = true
        val reg = Rtdb.listen(Rtdb.ref("giftFx/$roomId").limitToLast(1)) { snap ->
            val c = snap.children.lastOrNull()
            val ts = c?.child("ts")?.getValue(Long::class.java) ?: 0L
            val key = c?.key
            // Pesan pertama saat membuka room: abaikan kalau sudah lebih dari 8 detik.
            if (isInit) {
                isInit = false
                lastKey = if (ts > System.currentTimeMillis() - 8000L) null else key
                return@listen
            }
            if (c != null && key != null && key != lastKey) {
                lastKey = key
                fx = GlobalBannerData(
                    roomId = roomId,
                    roomName = "",
                    from = c.child("from").getValue(String::class.java) ?: "",
                    gift = c.child("gift").getValue(String::class.java) ?: "",
                    emoji = c.child("emoji").getValue(String::class.java) ?: "🎁",
                    mediaUrl = c.child("mediaUrl").getValue(String::class.java) ?: "",
                    mediaType = c.child("mediaType").getValue(String::class.java) ?: "",
                    rocket = c.child("rocket").getValue(Boolean::class.java) ?: false,
                    count = c.child("count").getValue(Long::class.java) ?: 1L,
                    jp = c.child("jp").getValue(Boolean::class.java) ?: false,
                    combo = (c.child("combo").getValue(Long::class.java) ?: 0L).toInt(),
                    ts = ts,
                )
            }
        }
        onDispose { reg.remove() }
    }

    val d = fx
    if (d == null) return
    val big = d.jp || d.combo >= 2 || d.mediaUrl.isNotBlank()

    LaunchedEffect(d.ts) {
        // SVGA butuh waktu unduh + urai, jadi diberi waktu lebih panjang.
        val ms = when {
            d.mediaUrl.isNotBlank() && kindOf(d.mediaUrl, d.mediaType) == "svga" -> 15000L
            big -> 8000L
            else -> 3000L
        }
        delay(ms)
        fx = null
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (d.mediaUrl.isNotBlank()) {
            if (isVideoMedia(d.mediaUrl, d.mediaType)) {
                // mp4: diputar otomatis. TextureView dipakai supaya tidak
                // tertutup lapisan video Agora (SurfaceView) di room.
                GiftVideo(d.mediaUrl, Modifier.fillMaxSize(), onEnd = { fx = null })
            } else {
                val kind = kindOf(d.mediaUrl, d.mediaType)
                if (kind == "svga") {
                    GiftSvga(d.mediaUrl, d.emoji, Modifier.fillMaxSize(), onEnd = { fx = null })
                } else {
                    AsyncImage(
                        model = d.mediaUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        } else {
            Text(d.emoji, fontSize = if (big) 120.sp else 64.sp)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC101010))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val text = buildString {
                if (d.jp) append("🎉 JP! ")
                append("${d.from} mengirim ${d.gift} x${d.count}")
                if (d.combo >= 2) append(" • kombo x${d.combo}")
            }
            Text(text, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/**
 * Roket terbang melintasi layar room: dari kiri bawah ke kanan atas, dengan
 * ekor api dan tulisan pengirim. Dipakai gift bertanda "roket".
 */
@Composable
private fun RocketFly(from: String) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, animationSpec = tween(durationMillis = 2400, easing = LinearEasing))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val startX = (-90).dp
        val endX = maxWidth + 90.dp
        val startY = maxHeight * 0.82f
        val endY = maxHeight * 0.10f
        val x = startX + (endX - startX) * p.value
        val y = startY + (endY - startY) * p.value
        // ekor api
        Text("🔥", fontSize = 38.sp, modifier = Modifier.offset(x = x - 26.dp, y = y + 16.dp).alpha(0.9f))
        Text("💨", fontSize = 30.sp, modifier = Modifier.offset(x = x - 62.dp, y = y + 24.dp).alpha(0.5f))
        Text("🚀", fontSize = 60.sp, modifier = Modifier.offset(x = x, y = y))
        Text(
            "$from meluncurkan roket",
            color = GoldLight,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.offset(x = x, y = y + 62.dp).alpha(if (p.value > 0.9f) 0f else 1f),
        )
    }
}

/** True kalau media gift berupa video (mp4/mov/m4v), dilihat dari tipe simpanan
 *  maupun dari URL Cloudinary (termasuk /video/upload/ yang tanpa ekstensi). */
fun isVideoMedia(url: String, mediaType: String = ""): Boolean {
    val t = mediaType.substringBefore('?').substringAfterLast('/').lowercase()
    if (t.startsWith("mp4") || t.startsWith("video") || t == "mov" || t == "m4v") return true
    val u = url.substringBefore('?').lowercase()
    return u.endsWith(".mp4") || u.endsWith(".m4v") || u.endsWith(".mov") || u.contains("/video/upload/")
}

/** True kalau media gift berupa animasi SVGA. */
fun isSvgaMedia(url: String, mediaType: String = ""): Boolean {
    val t = mediaType.substringBefore('?').substringAfterLast('/').lowercase()
    if (t.startsWith("svga")) return true
    return url.substringBefore('?').lowercase().endsWith(".svga")
}

/** Jenis media: mp4, svga, atau image (gif/webp/png). */
private fun kindOf(url: String, mediaType: String = ""): String {
    if (isVideoMedia(url, mediaType)) return "mp4"
    val u = url.substringBefore('?').lowercase()
    return when {
        u.endsWith(".svga") || mediaType.lowercase().startsWith("svga") -> "svga"
        else -> "image"
    }
}

/**
 * Banner gift global di bagian atas layar, terlihat di semua room/tab lain.
 * Klik = langsung masuk ke room tempat gift dikirim.
 */
@Composable
fun GlobalGiftBanner(currentRoomId: String?, onOpenRoom: (RoomInfo) -> Unit) {
    var data by remember { mutableStateOf<GlobalBannerData?>(null) }
    var lastTs by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        val reg = Rtdb.listen(Rtdb.ref("giftBanner")) { snap ->
            if (!snap.exists()) {
                data = null
                return@listen
            }
            val ts = snap.child("ts").getValue(Long::class.java) ?: 0L
            val prev = lastTs
            lastTs = ts
            // Banner lama tidak muncul saat buka ulang app: snapshot pertama hanya dicatat.
            if (prev == null || ts == prev || System.currentTimeMillis() - ts > 30_000) {
                if (prev == null) return@listen
                if (ts != prev) data = null
                return@listen
            }
            data = GlobalBannerData(
                roomId = snap.child("roomId").getValue(String::class.java) ?: "",
                roomName = snap.child("roomName").getValue(String::class.java) ?: "",
                roomNo = snap.child("roomNo").getValue(String::class.java) ?: "",
                ownerUid = snap.child("ownerUid").getValue(String::class.java) ?: "",
                ownerName = snap.child("ownerName").getValue(String::class.java) ?: "",
                micCount = ((snap.child("micCount").value as? Number)?.toInt() ?: 8),
                lockHash = snap.child("lockHash").getValue(String::class.java) ?: "",
                from = snap.child("from").getValue(String::class.java) ?: "",
                gift = snap.child("gift").getValue(String::class.java) ?: "",
                emoji = snap.child("emoji").getValue(String::class.java) ?: "🎁",
                mediaUrl = snap.child("mediaUrl").getValue(String::class.java) ?: "",
                mediaType = snap.child("mediaType").getValue(String::class.java) ?: "",
                count = snap.child("count").getValue(Long::class.java) ?: 1L,
                jp = snap.child("jp").getValue(Boolean::class.java) ?: false,
                combo = (snap.child("combo").getValue(Long::class.java) ?: 0L).toInt(),
                ts = snap.child("ts").getValue(Long::class.java) ?: 0L,
            )
        }
        onDispose { reg.remove() }
    }

    LaunchedEffect(data?.ts) {
        if (data != null) {
            delay(3000)
            data = null
        }
    }

    val d = data
    // Di dalam room tidak ada banner dari room lain; banner room sendiri diputar
    // oleh GiftBanner(roomId) di layar room.
    if (currentRoomId != null) return
    if (d == null || d.roomId.isBlank() || d.roomId == currentRoomId) return

    Box(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        if (d.jp) listOf(Color(0xFFFF6A00), Gold) else listOf(Color(0xFF5B2BD9), Color(0xFFC9A227)),
                    ),
                )
                .border(1.dp, Gold, RoundedCornerShape(50))
                .clickable {
                    onOpenRoom(
                        RoomInfo(
                            id = d.roomId, roomNo = d.roomNo, name = d.roomName.ifBlank { "Room" },
                            ownerUid = d.ownerUid, ownerName = d.ownerName, photoB64 = null,
                            backgroundB64 = null, announcement = "", micCount = d.micCount,
                            lockHash = d.lockHash, online = 0,
                        ),
                    )
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.mediaUrl.isNotBlank() && !isVideoMedia(d.mediaUrl, d.mediaType)) {
                    AsyncImage(
                        model = d.mediaUrl,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(d.emoji, fontSize = 20.sp)
                }
                val text = buildString {
                    if (d.jp) append("🎉 JP! ")
                    append("${d.from} mengirim ${d.gift} x${d.count}")
                    if (d.combo >= 2) append(" • kombo x${d.combo}")
                    append(" • ${d.roomName} ›")
                }
                Text(
                    text,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Banner cashback global (di luar room). Hanya untuk JP 10.000 ke atas. */
@Composable
fun GlobalCashbackBanner(currentRoomId: String?, onOpenRoom: (RoomInfo) -> Unit) {
    var data by remember { mutableStateOf<Triple<String, Long, String>?>(null) }
    var lastTs by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) {
        val reg = Rtdb.listen(Rtdb.ref("giftCbBanner")) { snap ->
            val ts = snap.child("ts").getValue(Long::class.java) ?: 0L
            val prev = lastTs
            lastTs = ts
            if (prev == null || ts == prev || System.currentTimeMillis() - ts > 30_000) {
                if (prev == null) return@listen
                if (ts != prev) data = null
                return@listen
            }
            val cb = snap.child("cb").getValue(Long::class.java) ?: 0L
            val rid = snap.child("roomId").getValue(String::class.java) ?: ""
            val from = snap.child("from").getValue(String::class.java) ?: ""
            val rname = snap.child("roomName").getValue(String::class.java) ?: ""
            val gift = snap.child("gift").getValue(String::class.java) ?: ""
            if (cb < 10_000L || rid.isBlank()) {
                data = null
                return@listen
            }
            data = Triple("\uD83D\uDC8E $from • $gift  +${fmtNum(cb)}  •  $rname ›", cb, rid)
        }
        onDispose { reg.remove() }
    }
    LaunchedEffect(data?.third) {
        if (data != null) {
            delay(3500)
            data = null
        }
    }
    val d = data ?: return
    if (d.third == currentRoomId) return
    Box(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFF0E8F62), Color(0xFF1FBF7A), Color(0xFFD4AF37))))
                .border(1.dp, Color(0xFFFFE082), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                d.first,
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Pemutar video gift (mp4). TextureView dipilih karena digambar di dalam
 * hierarki tampilan, jadi efek gift tetap kelihatan walau di room ada
 * permukaan video Agora. Otomatis mulai saat siap, tanpa suara, sekali putar.
 */
@Composable
fun GiftVideo(
    url: String,
    modifier: Modifier = Modifier,
    sound: Boolean = false,
    onEnd: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val endRef = rememberUpdatedState(onEnd)
    AndroidView(
        modifier = modifier,
        factory = { c ->
            android.view.TextureView(c).apply {
                var player: android.media.MediaPlayer? = null
                fun fitMatrix(vw: Int, vh: Int) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    if (vw <= 0 || vh <= 0 || w <= 0f || h <= 0f) return
                    val scale = minOf(w / vw, h / vh)
                    val m = android.graphics.Matrix()
                    m.setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f)
                    setTransform(m)
                }
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        try {
                            player = android.media.MediaPlayer().apply {
                                setSurface(android.view.Surface(st))
                                // "asset/..." = video yang ikut di dalam APK (aset roket).
                                if (url.startsWith("asset/")) {
                                    val afd = context.assets.openFd(url.removePrefix("asset/"))
                                    afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                                } else {
                                    setDataSource(context, Uri.parse(url))
                                }
                                isLooping = false
                                setVolume(if (sound) 1f else 0f, if (sound) 1f else 0f)
                                setOnVideoSizeChangedListener { _, vw, vh -> fitMatrix(vw, vh) }
                                setOnPreparedListener { mp -> fitMatrix(mp.videoWidth, mp.videoHeight); mp.start() }
                                setOnCompletionListener { endRef.value() }
                                setOnErrorListener { _, _, _ -> endRef.value(); true }
                                prepareAsync()
                            }
                        } catch (e: Exception) {
                            endRef.value()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        player?.let { fitMatrix(it.videoWidth, it.videoHeight) }
                    }

                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        try { player?.release() } catch (e: Exception) { }
                        player = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
            }
        },
    )
}

/** Cache SVGA yang sudah diunduh supaya gift yang sama tidak diunduh berulang. */
private val svgaCache = HashMap<String, ByteArray>()
private val asetSvgaCache = HashMap<String, ByteArray>()

/**
 * Pemutar SVGA gift, dibuat berlapis supaya animasi tetap jalan di HP yang
 * rewel soal jaringan:
 *   1. unduh + urai langsung dari URL (paling tahan, sama seperti efek VIP),
 *   2. kalau gagal, unduh sendiri lalu urai dari memori,
 *   3. kalau dua-duanya gagal, tampilkan emoji gift supaya layar tidak kosong.
 */
@Composable
fun GiftSvga(
    url: String,
    emoji: String = "\uD83C\uDF81",
    modifier: Modifier = Modifier,
    onEnd: () -> Unit = {},
    loops: Int = 1,
) {
    val endRef = rememberUpdatedState(onEnd)
    // 0 = coba dari URL, 1 = pakai byte hasil unduhan, 2 = gagal total.
    var mode by remember(url) { mutableStateOf(0) }
    var bytes by remember(url) { mutableStateOf(svgaCache[url]) }

    LaunchedEffect(url, mode) {
        if (url.startsWith("asset/")) return@LaunchedEffect
        if (mode != 1 || bytes != null) return@LaunchedEffect
        val got = withContext(Dispatchers.IO) {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "*/*")
                }
                conn.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
        if (got != null && got.size > 16) {
            svgaCache[url] = got
            bytes = got
        } else {
            mode = 2
        }
    }

    if (mode == 2) {
        Text(emoji, fontSize = 110.sp)
        return
    }

    // key(mode) memaksa tampilan baru dibuat saat berpindah dari jalur URL ke byte.
    key(mode) {
        AndroidView(
            modifier = modifier,
            factory = { c ->
                com.opensource.svgaplayer.SVGAImageView(c).apply {
                    this.loops = loops
                    clearsAfterStop = true
                    isClickable = false
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    callback = object : com.opensource.svgaplayer.SVGACallback {
                        override fun onPause() {}
                        override fun onFinished() { endRef.value() }
                        override fun onRepeat() {}
                        override fun onStep(frame: Int, percentage: Double) {}
                    }
                    val parser = com.opensource.svgaplayer.SVGAParser(c)
                    val done = object : com.opensource.svgaplayer.SVGAParser.ParseCompletion {
                        override fun onComplete(videoItem: com.opensource.svgaplayer.SVGAVideoEntity) {
                            setVideoItem(videoItem)
                            startAnimation()
                        }

                        override fun onError() {
                            mode = if (mode == 0) 1 else 2
                        }
                    }
                    // "asset/..." = file SVGA yang sudah ikut di dalam aplikasi.
                    if (url.startsWith("asset/")) {
                        val path = url.removePrefix("asset/")
                        try {
                            // Simpan byte aset sekali saja: memutar pecahan berulang
                            // jadi tidak perlu baca dan decode berkas dari nol (penyebab lag).
                            val b = asetSvgaCache[path] ?: c.assets.open(path).use { it.readBytes() }
                                .also { asetSvgaCache[path] = it }
                            parser.decodeFromInputStream(
                                java.io.ByteArrayInputStream(b), path, done, true,
                            )
                        } catch (e: Exception) {
                            mode = 2
                        }
                    } else if (mode == 0) {
                        try {
                            parser.decodeFromURL(java.net.URL(url), done)
                        } catch (e: Exception) {
                            mode = 1
                        }
                    } else {
                        val b = bytes
                        if (b == null) {
                            mode = 2
                        } else {
                            parser.decodeFromInputStream(
                                java.io.ByteArrayInputStream(b), "gift_" + url.hashCode(), done, true,
                            )
                        }
                    }
                }
            },
        )
    }
}
