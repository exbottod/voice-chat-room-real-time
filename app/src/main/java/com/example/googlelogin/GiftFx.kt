package com.example.googlelogin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
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
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (list.isEmpty()) list = GIFTS
        FirebaseFirestore.getInstance().collection("gifts").addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val custom = snap.documents.mapNotNull { d ->
                val name = d.getString("name") ?: return@mapNotNull null
                Gift(
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
                )
            }
            list = GIFTS + custom
        }
    }
}

/** Efek gift: kombo, JP (jackpot) dan cashback gift khas, serta banner global antar room. */
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
    ): Pair<Long, Long> {
        var jpWin = 0L
        var cbWin = 0L
        var comboN = 0
        if (g.special) {
            comboN = combo(roomId, uid)
            if (g.jpRate > 0 && (1..100).random() <= g.jpRate) {
                jpWin = g.price * g.jpMult * count
                WalletRepo.topUp(uid, jpWin)
            }
            // Cashback: gift khas memberi koin kembali sesuai persen harga (diatur di Panel Developer).
            if (g.cbRate > 0) {
                cbWin = g.price * count * g.cbRate / 100
                if (cbWin > 0) {
                    WalletRepo.topUp(uid, cbWin)
                    Rtdb.ref("cashback/$uid/total").setValue(ServerValue.increment(cbWin))
                    Rtdb.ref("cashback/$uid/log").push().setValue(
                        hashMapOf<String, Any?>(
                            "g" to g.name,
                            "cb" to cbWin,
                            "ts" to System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        val room = RoomRepo.getRoom(roomId)

        // Efek layar penuh untuk semua orang di room (mp4/gif/svga) atau emoji besar.
        Rtdb.ref("giftFx/$roomId").push().setValue(
            hashMapOf<String, Any?>(
                "from" to name,
                "gift" to g.name,
                "emoji" to g.emoji,
                "mediaUrl" to g.mediaUrl,
                "count" to count * receivers.coerceAtLeast(1),
                "jp" to (jpWin > 0),
                "combo" to comboN,
                "ts" to System.currentTimeMillis(),
            ),
        )
        Rtdb.ref("giftBanner").setValue(
            hashMapOf<String, Any?>(
                "roomId" to roomId,
                "roomName" to (room?.name ?: ""),
                "from" to name,
                "gift" to g.name,
                "emoji" to g.emoji,
                "mediaUrl" to g.mediaUrl,
                "count" to count * receivers.coerceAtLeast(1),
                "jp" to (jpWin > 0),
                "combo" to comboN,
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
        val endpoint = base.substringBeforeLast('/') + "/upload"

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
        val fname = (uri.lastPathSegment ?: "asset").substringAfterLast('/')
        var ext = fname.substringAfterLast('.', "").lowercase()
        if (ext !in allowed) {
            ext = when (context.contentResolver.getType(uri) ?: "") {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "video/mp4" -> "mp4"
                else -> ""
            }
        }
        if (ext !in allowed) {
            return null to "Format tidak didukung (png, jpg, gif, webp, mp4, svga)"
        }
        val uploadName = "gift." + (if (ext == "jpeg") "jpg" else ext)
        val boundary = "giftfx${System.currentTimeMillis()}"

        // Coba 2x: koneksi seluler kadang putus di tengah pengiriman.
        var res: Pair<String?, String?> = null to null
        for (attempt in 1..2) {
            res = try {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null to "Upload gagal: ${e.message ?: "koneksi bermasalah"}"
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
    val from: String,
    val gift: String,
    val emoji: String,
    val mediaUrl: String,
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
        delay(if (big) 8000L else 3000L)
        fx = null
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (d.mediaUrl.isNotBlank()) {
            VipMedia(
                VipAsset(n = 0, slot = "gift", kind = kindOf(d.mediaUrl), url = d.mediaUrl, hasData = false, updatedAt = d.ts),
                Modifier.fillMaxSize(),
                loop = false,
                contentScale = ContentScale.Fit,
                onEnd = { fx = null },
            )
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

/** Jenis media dari ekstensi URL: mp4, svga, atau image (gif/webp/png). */
private fun kindOf(url: String): String {
    val u = url.substringBefore('?').lowercase()
    return when {
        u.endsWith(".mp4") -> "mp4"
        u.endsWith(".svga") -> "svga"
        else -> "image"
    }
}

/**
 * Banner gift global di bagian atas layar, terlihat di semua room/tab lain.
 * Klik = langsung masuk ke room tempat gift dikirim.
 */
@Composable
fun GlobalGiftBanner(currentRoomId: String?, onOpenRoom: (String) -> Unit) {
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
                from = snap.child("from").getValue(String::class.java) ?: "",
                gift = snap.child("gift").getValue(String::class.java) ?: "",
                emoji = snap.child("emoji").getValue(String::class.java) ?: "🎁",
                mediaUrl = snap.child("mediaUrl").getValue(String::class.java) ?: "",
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
                .clickable { onOpenRoom(d.roomId) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.mediaUrl.isNotBlank()) {
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

/**
 * Panel Developer: kelola katalog gift. Tambah gift dengan nama, harga, media
 * (URL mp4/gif/png/webp atau upload dari perangkat), tandai gift khas,
 * atur rate JP (peluang jackpot) + hadiah JP, dan persen cashback.
 */
@Composable
fun DeveloperGiftPanel(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var gifts by remember { mutableStateOf<List<Gift>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var special by remember { mutableStateOf(false) }
    var jpRate by remember { mutableStateOf("") }
    var jpMult by remember { mutableStateOf("") }
    var cbRate by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val reg = FirebaseFirestore.getInstance().collection("gifts").addSnapshotListener { snap, _ ->
            if (snap != null) {
                gifts = snap.documents.mapNotNull { d ->
                    val n = d.getString("name") ?: return@mapNotNull null
                    Gift(
                        id = d.id,
                        name = n,
                        emoji = d.getString("emoji") ?: "🎁",
                        price = d.getLong("price") ?: 0L,
                        mediaUrl = d.getString("mediaUrl") ?: "",
                        mediaType = d.getString("mediaType") ?: "",
                        special = d.getBoolean("special") ?: false,
                        jpRate = (d.getLong("jpRate") ?: 0L).toInt(),
                        jpMult = d.getLong("jpMult") ?: 0L,
                        cbRate = (d.getLong("cbRate") ?: 0L).toInt(),
                    )
                }
            }
        }
        onDispose { reg.remove() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            msg = "Mengupload ke Cloudinary..."
            scope.launch {
                val (u, err) = GiftFx.uploadMedia(context, uri)
                if (u != null) {
                    url = u
                    msg = "File terupload. Tinggal tekan SIMPAN."
                } else {
                    msg = err ?: "Upload gagal"
                }
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GoldTopBar("Panel Developer: Gift", onBack)
        Spacer(Modifier.height(12.dp))
        Text(
            "Kelola hadiah gift. Media bisa URL atau upload dari perangkat (lewat Cloudinary). " +
                "Gift khas = ada kombo dan bisa JP (jackpot) dengan peluang acak sesuai rate, " +
                "serta bisa diberi cashback (persen harga kembali ke dompet).",
            color = Color(0xFFBDBDBD),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            name, { name = it },
            label = { Text("Nama gift") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                emoji, { emoji = it },
                label = { Text("Emoji") },
                modifier = Modifier.width(90.dp),
                singleLine = true,
            )
            OutlinedTextField(
                price, { price = it.filter { c -> c.isDigit() } },
                label = { Text("Harga (koin)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            url, { url = it },
            label = { Text("URL media (mp4/gif/png/webp)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlineButton("Upload dari perangkat", Modifier.fillMaxWidth()) {
            if (!busy) picker.launch("*/*")
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(special, { special = it })
            Text("Gift khas (kombo + JP)", color = Color.White, fontSize = 13.sp)
        }
        if (special) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    jpRate, { jpRate = it.filter { c -> c.isDigit() } },
                    label = { Text("Peluang JP (%)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    jpMult, { jpMult = it.filter { c -> c.isDigit() } },
                    label = { Text("JP hadiah (x harga)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    cbRate, { cbRate = it.filter { c -> c.isDigit() } },
                    label = { Text("Cashback (%)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlineButton(if (busy) "Menyimpan..." else "SIMPAN GIFT", Modifier.fillMaxWidth()) {
            if (busy) return@OutlineButton
            val p = price.toLongOrNull()
            if (name.isBlank() || p == null || p < 1) {
                msg = "Nama dan harga (angka) wajib diisi"
                return@OutlineButton
            }
            busy = true
            scope.launch {
                try {
                    val id = name.lowercase()
                        .replace(Regex("[^a-z0-9]"), "")
                        .ifBlank { "gift${System.currentTimeMillis()}" }
                    FirebaseFirestore.getInstance().collection("gifts").document(id).set(
                        hashMapOf<String, Any?>(
                            "name" to name.trim(),
                            "emoji" to emoji.trim().ifBlank { "🎁" },
                            "price" to p,
                            "mediaUrl" to url.trim(),
                            "mediaType" to url.trim().substringAfterLast('.', "").lowercase(),
                            "special" to special,
                            "jpRate" to (jpRate.toIntOrNull() ?: 0).coerceIn(0, 100),
                            "jpMult" to (jpMult.toLongOrNull() ?: 0L),
                            "cbRate" to (cbRate.toIntOrNull() ?: 0).coerceIn(0, 100),
                        ),
                    ).await()
                    msg = "Gift tersimpan: ${name.trim()}"
                    name = ""
                    emoji = ""
                    price = ""
                    url = ""
                    special = false
                    jpRate = ""
                    jpMult = ""
                    cbRate = ""
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msg = "Gagal menyimpan: ${e.message}"
                }
                busy = false
            }
        }
        msg?.let { t ->
            Spacer(Modifier.height(6.dp))
            Text(t, color = Color(0xFF7CE38B), fontSize = 12.sp)
        }

        Spacer(Modifier.height(20.dp))
        Text("Gift kustom (${gifts.size}):", color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        gifts.forEach { g ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (g.mediaUrl.isNotBlank()) {
                    AsyncImage(
                        g.mediaUrl, null,
                        Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(g.emoji, fontSize = 26.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val info = buildString {
                        append("${g.name} • ${g.price} koin")
                        if (g.special) append(" • khas (JP ${g.jpRate}% x${g.jpMult})")
                        if (g.cbRate > 0) append(" • CB ${g.cbRate}%")
                    }
                    Text(info, color = Color.White, fontSize = 13.sp)
                    Text(
                        if (g.mediaUrl.isNotBlank()) g.mediaUrl else "emoji saja",
                        color = Color(0xFF9A9A9A),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Hapus",
                    color = Color(0xFFFF8A8A),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch {
                            try {
                                FirebaseFirestore.getInstance().collection("gifts").document(g.id).delete().await()
                                msg = "Dihapus: ${g.name}"
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                msg = "Gagal hapus: ${e.message}"
                            }
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
