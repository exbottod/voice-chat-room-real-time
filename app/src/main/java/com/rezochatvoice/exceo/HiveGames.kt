package com.rezochatvoice.exceo

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Game pihak ketiga Hive Play.
 *
 * Alur: app membuka [Game.startUrl] dengan parameter gameid/code/uid/bizAppId.
 * Saat game butuh saldo, server Hive memanggil Edge Function smart-processor
 * (route /hive/...) milik REZO, jadi coin tetap dipotong dan ditambah di dompet
 * Supabase yang sama dengan fitur lain.
 *
 * code = "uid.expDetik.md5(uid.expDetik.salt)" dan hanya berlaku sebentar,
 * supaya orang tidak bisa memalsukan uid pemain lain.
 */
object HiveGames {
    const val APP_ID = "100198"
    private const val APP_KEY = "eb09c56d97454d43ab2698ad548697ab"
    private const val CODE_SALT = "rezo-hive-2026"
    private const val CATALOG = "https://api2023.duolepoker.com/v1/h5-game/third/game_info"
    private const val ENV = "prod"

    /** Cermin katalog: dipakai kalau domain Hive tidak bisa dihubungi dari HP. */
    private val CERMIN = listOf(
        // Server Base44 ditanya lebih dulu: github.io dan domain Hive sering
        // diblokir operator, sedangkan alamat ini selalu bisa dihubungi.
        "https://superagent-6187eafe.base44.app/functions/hiveCatalog",
        "https://exbottod.github.io/voice-chat-room-real-time/hivegames.json",
        "https://rezo-wallet.bangexx01.workers.dev/hivecatalog",
        "https://rezo-wallet.pages.dev/hivecatalog",
    )

    data class Game(
        val id: Int,
        val thirdPartyId: Int,
        val name: String,
        val icon: String,
        val startUrl: String,
        val landscape: Boolean,
        val screenWidth: Int,
        val safeHeight: Int,
    )

    fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
        return sb.toString()
    }

    /** code sementara untuk dibawa ke URL game. */
    fun code(uid: String, minutes: Int = 60): String {
        val exp = System.currentTimeMillis() / 1000L + minutes * 60L
        return "$uid.$exp.${md5("$uid.$exp.$CODE_SALT")}"
    }

    /** URI game lengkap dengan code pemain. */
    fun launchUrl(g: Game, uid: String): String {
        val base = if (g.landscape) g.startUrl else g.startUrl.replace("/common-web/full/", "/common-web/vert/")
        val sep = if (base.contains("?")) "&" else "?"
        val code = URLEncoder.encode(code(uid), "UTF-8")
        val u = URLEncoder.encode(uid, "UTF-8")
        val extra = URLEncoder.encode("{\"uid\":\"$uid\"}", "UTF-8")
        return base + sep +
            "gameid=" + g.thirdPartyId +
            "&code=" + code +
            "&uid=" + u +
            "&bizAppId=" + APP_ID +
            "&language=3" +
            "&exparams=" + extra
    }

    // Daftar terakhir dari server. Dipakai Game Center supaya game yang baru
    // ditambahkan di console Hive langsung muncul tanpa update APK.
    @Volatile private var cache: List<Game>? = null
    /** true kalau daftar terakhir benar-benar datang dari server Hive. */
    @Volatile var dariServer: Boolean = false
    @Volatile private var cacheAt: Long = 0L

    /** Daftar game terbaru (cache 2 menit, lalu ambil ulang dari Hive). */
    suspend fun list(): List<Game> {
        val c = cache
        if (c != null && System.currentTimeMillis() - cacheAt < 2 * 60_000L) return c
        val fresh = catalog()
        cache = fresh
        cacheAt = System.currentTimeMillis()
        return fresh
    }

    /** Paksa ambil ulang dari Hive (dipakai tombol "Muat ulang"). */
    suspend fun listSegar(): List<Game> {
        cache = null
        cacheAt = 0L
        return list()
    }

    private var appCtx: android.content.Context? = null

    /** Dipanggil sekali dari Game Center supaya daftar terakhir bisa disimpan. */
    fun init(c: android.content.Context) {
        if (appCtx == null) appCtx = c.applicationContext
    }

    private fun prefs(): android.content.SharedPreferences? =
        appCtx?.getSharedPreferences("rezo_hive", android.content.Context.MODE_PRIVATE)

    /** Daftar terakhir yang berhasil diambil, disimpan di HP. */
    private fun simpan(txt: String) {
        try {
            prefs()?.edit()?.putString("katalog2", txt)?.apply()
        } catch (e: Exception) {
        }
    }

    private fun katalogTersimpan(): List<Game> {
        return try {
            // "katalog2": nama baru supaya daftar bawaan lama (18 game, sudah
            // dihapus di console) tidak dipakai lagi di HP yang belum update.
            val txt = prefs()?.getString("katalog2", null) ?: return emptyList()
            baca(txt)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Urai jawaban katalog Hive. */
    private fun baca(txt: String): List<Game> {
        val out = ArrayList<Game>()
        val arr = JSONObject(txt).optJSONArray("data") ?: return out
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val start = g.optString("startUrl")
            if (start.isBlank()) continue
            out.add(
                Game(
                    id = g.optInt("gameId"),
                    thirdPartyId = g.optInt("thirdPartyId"),
                    name = g.optString("gameName"),
                    icon = g.optString("gameIcon"),
                    startUrl = start,
                    landscape = g.optString("screen").contains("\u6a2a"),
                    screenWidth = g.optString("screenWidth").toIntOrNull() ?: 720,
                    safeHeight = g.optString("safeHeight").toIntOrNull() ?: 900,
                ),
            )
        }
        return out
    }

    /** Cari game menurut id, dari daftar terbaru maupun daftar cadangan. */
    fun find(id: Int): Game? =
        (cache ?: emptyList()).firstOrNull { it.id == id } ?: fallback.firstOrNull { it.id == id }

    /**
     * Daftar game dari Hive. Kalau jaringan gagal, dipakai daftar terakhir yang
     * tersimpan di HP, baru terakhir daftar bawaan APK.
     */
    suspend fun catalog(): List<Game> = withContext(Dispatchers.IO) {
        repeat(2) { percobaan ->
            try {
                val ts = System.currentTimeMillis().toString()
                val nonce = ts + (1000..9999).random()
                val sig = md5(APP_ID + APP_KEY + nonce + ts)
                val payload = JSONObject()
                    .put("appId", APP_ID)
                    .put("timestamp", ts)
                    .put("nonce", nonce)
                    .put("signature", sig)
                    .put("env", ENV)
                    .toString()
                val conn = (URL(CATALOG).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val out = baca(txt)
                if (out.isNotEmpty()) {
                    simpan(txt)
                    dariServer = true
                    return@withContext out
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Coba sekali lagi; HP dengan jaringan lambat sering gagal
                // pada percobaan pertama.
                if (percobaan == 0) kotlinx.coroutines.delay(1200)
            }
        }
        // Jaringan HP sering memblokir domain Hive. Coba cermin katalog dulu
        // sebelum menyerah: GitHub Pages (diperbarui tiap 10 menit) lalu server kita.
        for (url in CERMIN) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val out = baca(txt)
                if (out.isNotEmpty()) {
                    simpan(txt)
                    dariServer = true
                    return@withContext out
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
        dariServer = false
        // TIDAK memakai daftar bawaan: game yang sudah dihapus di console tidak
        // boleh muncul lagi. Kalau belum ada data, tampilkan kosong + tombol muat ulang.
        katalogTersimpan()
    }

    /**
     * Sengaja KOSONG: daftar bawaan APK membuat game yang sudah dihapus di
     * console Hive tetap muncul. Sumber resmi hanya Hive + cermin katalog.
     */
    val fallback: List<Game> = emptyList()
}

/** Cari Activity dari Context (Compose memberi ContextWrapper). */
private fun android.content.Context.activityOrNull(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/** Jembatan untuk dipanggil halaman game (nama objek wajib "JsBirge"). */
private class HiveBridge(
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
    private val onRecharge: (String) -> Unit,
    private val onConfig: (String) -> Unit = {},
) {
    @JavascriptInterface
    fun open() = onOpen()

    @JavascriptInterface
    fun close() = onClose()

    @JavascriptInterface
    fun gameRecharge(param: String) = onRecharge(param)

    @JavascriptInterface
    fun gameRecharge() = onRecharge("")

    /** Game meminta data pemain: {"jsCallback":"onNativeGetInfo"}. */
    @JavascriptInterface
    fun getConfig(json: String) = onConfig(json)

    @JavascriptInterface
    fun gameInitialized() = onOpen()

    @JavascriptInterface
    fun gameLoaded() = onOpen()
}

/** Daftar game Hive. */
@Composable
fun HiveHubScreen(uid: String, onClose: () -> Unit) {
    var games by remember { mutableStateOf<List<HiveGames.Game>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf<HiveGames.Game?>(null) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        games = HiveGames.catalog()
        loading = false
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0B))) {
            val g = playing
            if (g != null) {
                HivePlayScreen(uid = uid, game = g, onClose = { playing = null })
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF141414)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Tutup",
                            color = Gold,
                            fontSize = 15.sp,
                            modifier = Modifier.clickable { onClose() }.padding(6.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "🎰 Hive Play",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Gold)
                        }
                    } else {
                        if (err.isNotBlank()) {
                            Text(
                                err,
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(games) { item ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        if (uid.isBlank()) {
                                            err = "Uid kosong, coba login ulang"
                                        } else {
                                            err = ""
                                            playing = item
                                        }
                                    },
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(1.6f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                                            .background(Color(0xFF1C1C1C)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (item.icon.isNotBlank()) {
                                            AsyncImage(
                                                model = item.icon,
                                                contentDescription = item.name,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Text("🎲", fontSize = 34.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        item.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Window tempat layar game berada: bisa window dialog, bisa window activity. */
private fun windowDari(v: android.view.View): android.view.Window? {
    var p: android.view.ViewParent? = v.parent
    while (p != null) {
        if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window
        p = p.parent
    }
    return (v.context as? android.app.Activity)?.window
}
/** WebView game Hive + jembatan JsBirge. Layar penuh supaya game tidak terpotong. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HivePlayScreen(uid: String, game: HiveGames.Game, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var prog by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val url = remember(game.id, uid) { HiveGames.launchUrl(game, uid) }

    // Nama dan foto pemain dikirim ke server sebelum game dibuka, supaya game
    // Hive menampilkan nama asli dan bukan "Player" lagi. Server Hive membaca
    // data ini saat meminta user_info ke dompet.
    LaunchedEffect(uid) {
        try {
            val me = UserCache.meLocal(uid)
            val nama = me?.name?.trim().orEmpty().ifBlank { "Player" }
            val foto = me?.photoUrl?.takeIf { it.startsWith("http") }
                ?: me?.avatarB64?.takeIf { it.isNotBlank() }?.let { "data:image/jpeg;base64,$it" }
                ?: ""
            SupaRepo.wavePrepare(nama, foto)
        } catch (e: Exception) { }
    }
    val act = ctx.activityOrNull()
    val view = LocalView.current

    // ==== Top 3 pemenang versi REZO ====
    // Dialog di dalam game Hive milik server Hive, jadi peringkat pemain REZO
    // ditampilkan di lapisan app: tombol 🏆 kapan saja + otomatis saat menang.
    var top3 by remember { mutableStateOf<List<JsonTop>>(emptyList()) }
    var myWin by remember { mutableStateOf(0L) }
    var rankMode by remember { mutableStateOf("day") }
    var showTop by remember { mutableStateOf(false) }
    var topLoading by remember { mutableStateOf(false) }
    val scope3 = rememberCoroutineScope()

    fun muatTop3(buka: Boolean) {
        if (topLoading) return
        topLoading = true
        scope3.launch {
            val o = SupaRepo.hiveRank(game.thirdPartyId.toString())
            if (o != null && o.optInt("ok") == 1) {
                val arr = o.optJSONArray("top")
                val list = ArrayList<JsonTop>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val x = arr.optJSONObject(i) ?: continue
                    list.add(
                        JsonTop(
                            rank = x.optInt("rank", i + 1),
                            name = x.optString("name", "Player").ifBlank { "Player" },
                            avatar = x.optString("avatar", ""),
                            win = x.optLong("win"),
                        )
                    )
                }
                top3 = list
                myWin = o.optJSONObject("me")?.optLong("win") ?: 0L
                rankMode = o.optString("mode", "day")
            }
            topLoading = false
            if (buka) showTop = true
        }
    }

    // Kemenangan dikirim server lewat Firebase Realtime (node hiveWin/<uid>),
    // jadi dialog Top 3 muncul seketika tanpa perlu polling ke Supabase.
    DisposableEffect(uid, game.id) {
        val ref = Rtdb.ref("hiveWin/" + uid)
        var tsLama = 0L
        val l = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val ts = snapshot.child("ts").getValue(Long::class.java) ?: 0L
                if (ts <= 0L || ts == tsLama) return
                val pertama = tsLama == 0L
                tsLama = ts
                // Baca pertama hanya menyimpan penanda, bukan kemenangan baru.
                if (!pertama) muatTop3(buka = true)
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) { }
        }
        ref.addValueEventListener(l)
        onDispose { ref.removeEventListener(l) }
    }

    // Cadangan kalau sinyal realtime belum aktif: cek jauh lebih jarang
    // supaya kuota Supabase tidak tersedot.
    LaunchedEffect(uid, game.id) {
        var saldoLama: Long? = null
        while (true) {
            delay(60_000)
            if (!AppAktif.ya) continue
            val w = SupaRepo.get(uid).wallet?.coins
            if (w != null) {
                val lama = saldoLama
                if (lama != null && w > lama) muatTop3(buka = true)
                saldoLama = w
            }
        }
    }

    // Game lanskap diputar otomatis; orientasi lama dikembalikan saat keluar.
    // Status/navigation bar disembunyikan selama game dibuka, karena HUD bawah
    // game tertutup tombol navigasi Android dan terlihat seperti terpotong.
    DisposableEffect(game.landscape, act, view) {
        val old = act?.requestedOrientation
        if (act != null) {
            act.requestedOrientation = if (game.landscape) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        // Sebagian HP mengabaikan permintaan putar pertama (misalnya saat
        // baru masuk dari layar tegak). Diulang sekali setelah jeda pendek.
        if (act != null && game.landscape) {
            view.postDelayed({
                if (act.requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }, 500)
        }
        val ctrl = windowDari(view)?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (act != null && old != null) act.requestedOrientation = old
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!started) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold)
            }
        }
        if (err.isNotBlank()) {
            Text(err, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 16.dp, end = 16.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val wv = WebView(c)
                val density = c.resources.displayMetrics.density
                // Halaman game dilukis pada lebar desain 720 (CSS px) dan
                // TINGGINYA mengikuti bentuk asli halaman. Seluruh halaman lalu
                // diperkecil supaya utuh masuk layar dan ditaruh pas di tengah,
                // jadi bagian bawah (tombol taruhan/spin) tidak terpotong.
                val desainCss = 720f
                var aspek = if (game.landscape) 16f / 9f else 9f / 16f // lebar / tinggi
                var atur: (() -> Unit)? = null
                val fit = android.widget.FrameLayout(c).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(
                        wv,
                        android.widget.FrameLayout.LayoutParams(
                            (desainCss * density).toInt(),
                            (desainCss / aspek * density).toInt(),
                            android.view.Gravity.CENTER,
                        ),
                    )
                    fun applyFit() {
                        if (width <= 0 || height <= 0) return
                        val lp = wv.layoutParams as android.widget.FrameLayout.LayoutParams
                        val wPx = (desainCss * density).toInt().coerceAtLeast(1)
                        val hPx = (desainCss / aspek * density).toInt().coerceAtLeast(1)
                        if (lp.width != wPx || lp.height != hPx) {
                            lp.width = wPx
                            lp.height = hPx
                            lp.gravity = android.view.Gravity.CENTER
                            wv.layoutParams = lp
                        }
                        // min(...) = utuh semuanya; tidak ada sisi yang terpotong.
                        val sc = minOf(width.toFloat() / wPx, height.toFloat() / hPx)
                        wv.scaleX = sc
                        wv.scaleY = sc
                    }
                    atur = { applyFit() }
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFit() }
                    post { applyFit() }
                }
                wv.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // HARUS sama seperti Chrome di HP: halaman game memakai
                    // meta viewport width=device-width, jadi WebView juga harus
                    // menghormati meta itu. Kalau dimatikan, WebView memakai
                    // lebar desktop (~980px) dan isi game melebar keluar layar.
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    // Layer software membuat canvas/WebGL game jadi hitam, jadi tetap hardware.
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(
                        HiveBridge(
                            onOpen = { started = true },
                            onClose = { onClose() },
                            onRecharge = { err = "Isi coin lewat menu Top Up, lalu buka game lagi" },
                            onConfig = { req ->
                                val cb = try {
                                    JSONObject(req).optString("jsCallback", "onNativeGetInfo")
                                } catch (e: Exception) { "onNativeGetInfo" }
                                val cfg = JSONObject()
                                    .put("bizAppId", HiveGames.APP_ID)
                                    .put("uid", uid)
                                    .put("new_uid", "0")
                                    .put("code", HiveGames.code(uid))
                                    .put("gameid", game.thirdPartyId)
                                    .put("exparams", "{}")
                                    .put("language", 3)
                                    .put("platform", "android")
                                val js = "javascript:(function(){try{window." + cb + "(" +
                                    JSONObject.quote(cfg.toString()) + ")}catch(e){}})()"
                                web?.post { web?.evaluateJavascript(js, null) }
                            },
                        ),
                        "JsBirge",
                    )
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            prog = newProgress
                            if (newProgress > 40) started = true
                        }

                        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                            request?.grant(request.resources)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, u: String?) {
                            prog = 100
                            started = true
                            // Baca bentuk asli halaman game, lalu pas-kan ulang.
                            for (jeda in longArrayOf(250L, 1200L, 2500L)) {
                                v?.postDelayed({
                                    v.evaluateJavascript(
                                        "(function(){try{var e=document.querySelector('canvas')||document.body;" +
                                            "var r=e.getBoundingClientRect();var w=Math.round(r.width),h=Math.round(r.height);" +
                                            "if(w<50||h<50){w=window.innerWidth;h=window.innerHeight;}" +
                                            "return JSON.stringify({w:w,h:h});}catch(e){return ''}})()",
                                    ) { hasil ->
                                        // Hasil evaluateJavascript berupa string JSON
                                        // ter-escape, jadi didekode lewat JSONArray.
                                        val t = try {
                                            org.json.JSONArray("[" + hasil + "]").optString(0, "")
                                        } catch (e: Exception) {
                                            ""
                                        }
                                        if (t.startsWith("{")) {
                                            try {
                                                val o = JSONObject(t)
                                                val lw = o.optInt("w")
                                                val lh = o.optInt("h")
                                                if (lw > 50 && lh > 50) {
                                                    val ras = lw.toFloat() / lh.toFloat()
                                                    // Batas wajar supaya halaman error
                                                    // tidak merusak ukuran.
                                                    if (ras > 0.3f && ras < 3.5f) {
                                                        aspek = ras
                                                        atur?.invoke()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                            }
                                        }
                                    }
                                }, jeda)
                            }
                        }

                        override fun onReceivedError(
                            v: WebView?,
                            r: android.webkit.WebResourceRequest?,
                            e: android.webkit.WebResourceError?,
                        ) {
                            if (r?.isForMainFrame == true) {
                                err = "Gagal memuat game: " + (e?.description ?: "koneksi ditolak")
                            }
                        }

                        override fun onReceivedHttpError(
                            v: WebView?,
                            r: android.webkit.WebResourceRequest?,
                            e: android.webkit.WebResourceResponse?,
                        ) {
                            if (r?.isForMainFrame == true) {
                                err = "Server game membalas " + (e?.statusCode ?: 0)
                            }
                        }
                    }
                    web = this
                    loadUrl(url)
                }
                // Kalau HP menolak berputar (mode putar terkunci, layar terbagi,
                // atau pengaturan aksesibilitas) padahal game-nya lanskap, isi
                // game diputar sendiri 90 derajat dengan ukuran lanskap penuh.
                // Diputar lewat View (bukan efek gambar) supaya sentuhan tombol
                // tetap jatuh di posisi yang benar.
                val akar = android.widget.FrameLayout(c).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
                val dalam = android.widget.FrameLayout(c).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(
                        fit,
                        android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                akar.addView(dalam, android.widget.FrameLayout.LayoutParams(1, 1))
                fun rapikan() {
                    val W = akar.width
                    val H = akar.height
                    if (W <= 0 || H <= 0) return
                    val putar = game.landscape && W < H
                    val lp = dalam.layoutParams as android.widget.FrameLayout.LayoutParams
                    val wBaru = if (putar) H else W
                    val hBaru = if (putar) W else H
                    if (lp.width != wBaru || lp.height != hBaru) {
                        lp.width = wBaru
                        lp.height = hBaru
                        dalam.layoutParams = lp
                    }
                    dalam.rotation = if (putar) 90f else 0f
                    dalam.x = (W - wBaru) / 2f
                    dalam.y = (H - hBaru) / 2f
                }
                akar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rapikan() }
                akar.post { rapikan() }
                akar
            },
        )

        // Tombol papan Top 3 REZO (tidak menutupi tombol game).
        Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Color(0x99000000))
                .clickable { muatTop3(buka = true) },
            contentAlignment = Alignment.Center,
        ) { Text("🏆", fontSize = 14.sp) }

        if (showTop) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { showTop = false })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TopThreeCard(
                    list = top3,
                    myWin = myWin,
                    mode = rankMode,
                    loading = topLoading,
                    onClose = { showTop = false },
                )
            }
        }
    }
}

private data class JsonTop(val rank: Int, val name: String, val avatar: String, val win: Long)

private fun fmtWin(n: Long): String {
    val v = n.toDouble()
    val s = when {
        v >= 1e12 -> "%.2fT".format(v / 1e12)
        v >= 1e9 -> "%.2fB".format(v / 1e9)
        v >= 1e6 -> "%.2fM".format(v / 1e6)
        v >= 1e3 -> "%.1fK".format(v / 1e3)
        else -> n.toString()
    }
    return s.replace(".00", "").replace("T", "T").let { if (it.endsWith(".0")) it.dropLast(2) else it }
}

/** Kartu Top 1-3 pemenang game (data dari catatan menang di server REZO). */
@Composable
private fun TopThreeCard(
    list: List<JsonTop>,
    myWin: Long,
    mode: String,
    loading: Boolean,
    onClose: () -> Unit,
) {
    Column(
        Modifier.padding(18.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141018))
            .border(1.dp, Color(0xFF3A2E4A), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Top 3 Pemenang", color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            if (mode == "round") "Putaran ini · pemain REZO" else "24 jam terakhir · pemain REZO",
            color = Color(0xFF9A93A6),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (loading && list.isEmpty()) {
            CircularProgressIndicator(color = Gold, modifier = Modifier.size(22.dp))
        } else if (list.isEmpty()) {
            Text("Belum ada pemenang", color = Color.White, fontSize = 12.sp)
        } else {
            list.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (t.rank) { 1 -> "1"; 2 -> "2"; else -> "3" },
                        color = when (t.rank) { 1 -> Gold; 2 -> Color(0xFFC9C9D1); else -> Color(0xFFC58B58) },
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(20.dp),
                    )
                    if (t.avatar.isNotBlank()) {
                        AsyncImage(
                            model = t.avatar,
                            contentDescription = t.name,
                            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)),
                        )
                    } else {
                        Box(
                            Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF2A2135)),
                            contentAlignment = Alignment.Center,
                        ) { Text("👤", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.name, color = Color.White, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("🪙 " + fmtWin(t.win), color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (myWin > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Kemenangan kamu: 🪙 " + fmtWin(myWin), color = Color(0xFF7CE38B), fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Gold).clickable { onClose() }.padding(vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Tutup", color = Color(0xFF1A1206), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}
