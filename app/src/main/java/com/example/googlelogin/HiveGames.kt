package com.example.googlelogin

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

    /** Daftar game dari Hive. Kalau jaringan gagal, pakai daftar bawaan. */
    suspend fun catalog(): List<Game> = withContext(Dispatchers.IO) {
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
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONArray("data") ?: return@withContext fallback
            val out = ArrayList<Game>()
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
            out.ifEmpty { fallback }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
    }

    // Cadangan kalau katalog tidak bisa dihubungi.
    val fallback = listOf(
        Game(1000, 11923, "KingOfOlympus", "https://d2bd0pfu4eztx8.cloudfront.net/20250820/17556825806565671233b0c.png", "https://www.duolefast.com/common-web/full/game1000/1.1.3/web-mobile/index.html", true, 720, 780),
        Game(1005, 11924, "GatesOfGatotkaca", "https://d2bd0pfu4eztx8.cloudfront.net/20250820/1755682689838e629d93ef5.png", "https://www.duolefast.com/common-web/full/game1005/1.1.3/web-mobile/index.html", true, 720, 780),
        Game(1006, 11925, "Aztec", "https://d2bd0pfu4eztx8.cloudfront.net/20251226/1766743520441442b525a6d.png", "https://www.duolefast.com/common-web/full/game1006/1.1.3/web-mobile/index.html", true, 720, 780),
        Game(1008, 11926, "MahjongWays", "https://d2bd0pfu4eztx8.cloudfront.net/20260330/1774861065266e9b2f828e0.png", "https://www.duolefast.com/common-web/full/game1008/1.1.3/web-mobile/index.html", true, 720, 880),
        Game(1011, 11927, "SweetBonanza", "https://d2bd0pfu4eztx8.cloudfront.net/20260403/177520651058399d652539f.png", "https://www.duolefast.com/common-web/full/game1011/1.1.3/web-mobile/index.html", true, 720, 780),
        Game(1013, 11928, "SugarRush", "https://d2bd0pfu4eztx8.cloudfront.net/20251031/1761906655522eac63b2f5a.png", "https://www.duolefast.com/common-web/full/game1013/1.1.3/web-mobile/index.html", true, 720, 780),
        Game(1014, 11929, "MonkeyKing", "https://d2bd0pfu4eztx8.cloudfront.net/20250820/17556827536300835603814.png", "https://www.duolefast.com/common-web/full/game1014/1.1.3/web-mobile/index.html", true, 720, 880),
        Game(1017, 11930, "LuckyPig", "https://d2bd0pfu4eztx8.cloudfront.net/20250820/1755682609843e661837b7b.png", "https://www.duolefast.com/common-web/full/game1017/1.1.3/web-mobile/index.html", true, 720, 720),
        Game(845, 11931, "GreedyKing", "https://d2bd0pfu4eztx8.cloudfront.net/20260420/1776668984853cb231a85b1.png", "https://www.duolefast.com/common-web/full/game845/1.1.3/web-mobile/index.html", false, 720, 920),
        Game(848, 11932, "FruitParty", "https://d2bd0pfu4eztx8.cloudfront.net/20260423/1776915806327bab501454b.png", "https://www.duolefast.com/common-web/vert/game848/1.1.3/web-mobile/index.html", false, 720, 920),
        Game(841, 11933, "DragonTiger", "https://d2bd0pfu4eztx8.cloudfront.net/20260625/178236620946457f4a36f02.png", "https://www.duolefast.com/common-web/full/game841/1.1.3/web-mobile/index.html", false, 720, 1100),
        Game(1031, 11934, "ScratchCard", "https://d2bd0pfu4eztx8.cloudfront.net/20260706/1783333030006e505b47744.png", "https://www.duolefast.com/common-web/full/game1031/1.1.3/web-mobile/index.html", false, 720, 900),
        Game(851, 11935, "GreedyBanker", "https://d2bd0pfu4eztx8.cloudfront.net/20260703/1783068147269705130bd1d.png", "https://www.duolefast.com/common-web/full/game851/1.1.3/web-mobile/index.html", false, 720, 920),
        Game(853, 11936, "GreedyLion", "https://d2bd0pfu4eztx8.cloudfront.net/20260708/1783492250184e6d20b7761.png", "https://www.duolefast.com/common-web/full/game853/1.1.3/web-mobile/index.html", false, 720, 1040),
        Game(842, 11937, "Crash", "https://d2bd0pfu4eztx8.cloudfront.net/20260825/178763822478197206cd72e.png", "https://www.duolefast.com/common-web/full/game842/1.1.3/web-mobile/index.html", true, 720, 950),
        Game(857, 11938, "GreedyCatKing", "https://d1rbs2pv9iwt9o.cloudfront.net/20260928/179055866379578c68379d6.png", "https://www.duolefast.com/common-web/full/game857/1.1.3/web-mobile/index.html", false, 720, 1040),
        Game(865, 11939, "Roulette", "https://d1rbs2pv9iwt9o.cloudfront.net/20261015/179202841149390c31925a7.png", "https://www.duolefast.com/common-web/full/game865/1.1.3/web-mobile/index.html", false, 720, 1000),
        Game(864, 11940, "Teenpatti", "https://d1rbs2pv9iwt9o.cloudfront.net/20261023/17927337478481a291ef72f.png", "https://www.duolefast.com/common-web/full/game864/1.1.3/web-mobile/index.html", false, 720, 900),
    )
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

/**
 * Skrip penyesuai ukuran halaman game.
 *
 * Sebagian game memakai ukuran tetap sehingga di layar HP isinya melebar keluar
 * layar dan terpotong. Skrip ini mengukur halaman; kalau isi lebih besar dari
 * layar (lebih dari 3%), halaman diperkecil dengan `zoom` sampai muat.
 * Halaman tidak pernah diperbesar, jadi game yang sudah pas tidak berubah.
 */
private const val FIT_JS = """(function(){
  if (window.__rezoFitInit) { try{window.__rezoFit();}catch(e){} return; }
  window.__rezoFitInit = 1;
  window.__rezoFit = function(){
    try {
      var h = document.documentElement, b = document.body;
      if (!h || !b) return;
      var vw = window.innerWidth, vh = window.innerHeight;
      if (vw < 50 || vh < 50) return;
      var z = window.__rezoZ || 1;
      for (var pass = 0; pass < 6; pass++) {
        h.style.zoom = z < 0.995 ? String(z) : "";
        var maxR = vw, maxB = vh;
        var els = b.getElementsByTagName("*");
        for (var i = 0; i < els.length; i++) {
          var r = els[i].getBoundingClientRect();
          if (r.width < 24 || r.height < 24) continue;
          if (r.right > maxR) maxR = r.right;
          if (r.bottom > maxB) maxB = r.bottom;
        }
        if (h.scrollWidth > maxR) maxR = h.scrollWidth;
        if (h.scrollHeight > maxB) maxB = h.scrollHeight;
        var perlu = Math.min(vw / maxR, vh / maxB);
        if (perlu > 0.995) break;
        var baru = Math.max(0.6, z * perlu);
        if (baru >= z - 0.004) break;
        z = baru;
      }
      window.__rezoZ = z;
      h.style.zoom = z < 0.995 ? String(z) : "";
    } catch(e) {}
  };
  var jadwal = [400, 900, 1500, 2400, 3600, 5200, 7500, 10000, 14000, 19000];
  for (var k = 0; k < jadwal.length; k++) setTimeout(window.__rezoFit, jadwal[k]);
  window.addEventListener("resize", function(){ setTimeout(window.__rezoFit, 300); });
  window.addEventListener("orientationchange", function(){ setTimeout(window.__rezoFit, 600); });
  try {
    var mo = new MutationObserver(function(){
      clearTimeout(window.__rezoFitT);
      window.__rezoFitT = setTimeout(window.__rezoFit, 500);
    });
    document.addEventListener("DOMContentLoaded", function(){
      try { mo.observe(document.body, {childList:true, subtree:true}); } catch(e){}
    });
  } catch(e) {}
})();"""


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

    // Saldo naik = baru menang: dialog Top 3 muncul sendiri.
    LaunchedEffect(uid, game.id) {
        var saldoLama: Long? = null
        while (true) {
            delay(10_000)
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
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // Biarkan halaman game memakai ukuran viewport normal; game menghitung sendiri
                    // skalanya dari innerWidth/innerHeight.
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
                    post {
                        addOnLayoutChangeListener { _, l, t, rr, bb, ol, ot, orr, obb ->
                            if (rr - l != orr - ol || bb - t != obb - ot) {
                                evaluateJavascript("try{window.__rezoFit&&window.__rezoFit()}catch(e){}", null)
                            }
                        }
                    }
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
                            v?.evaluateJavascript(FIT_JS, null)
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
