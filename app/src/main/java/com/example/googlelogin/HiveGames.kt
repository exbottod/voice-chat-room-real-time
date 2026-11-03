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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        val sep = if (g.startUrl.contains("?")) "&" else "?"
        val code = URLEncoder.encode(code(uid), "UTF-8")
        val u = URLEncoder.encode(uid, "UTF-8")
        val extra = URLEncoder.encode("{\"uid\":\"$uid\"}", "UTF-8")
        return g.startUrl + sep +
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
                        landscape = g.optString("screen").contains("横"),
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
    private val fallback = listOf(
        Game(10000, 11923, "KingOfOlympus", "", "https://www.duolefast.com/common-web/full/game1000/1.1.3/web-mobile/index.html", true, 720, 780),
        Game(10001, 11926, "MahjongWays", "", "https://www.duolefast.com/common-web/full/game1008/1.1.3/web-mobile/index.html", true, 720, 880),
        Game(10002, 11927, "SweetBonanza", "", "https://www.duolefast.com/common-web/full/game1011/1.1.3/web-mobile/index.html", true, 720, 780),
        Game(10003, 11931, "GreedyKing", "", "https://www.duolefast.com/common-web/full/game845/1.1.3/web-mobile/index.html", false, 720, 920),
        Game(10004, 11932, "FruitParty", "", "https://www.duolefast.com/common-web/vert/game848/1.1.3/web-mobile/index.html", false, 720, 920),
        Game(10005, 11933, "DragonTiger", "", "https://www.duolefast.com/common-web/full/game841/1.1.3/web-mobile/index.html", false, 720, 1100),
        Game(10006, 11940, "Teenpatti", "", "https://www.duolefast.com/common-web/full/game864/1.1.3/web-mobile/index.html", false, 720, 900),
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
) {
    @JavascriptInterface
    fun open() = onOpen()

    @JavascriptInterface
    fun close() = onClose()

    @JavascriptInterface
    fun gameRecharge(param: String) = onRecharge(param)
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

/** WebView game Hive + jembatan JsBirge. */
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
    DisposableEffect(game.landscape, act) {
        val old = act?.requestedOrientation
        if (game.landscape && act != null) {
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (game.landscape && act != null && old != null) act.requestedOrientation = old
        }
    }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0B))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF141414)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Kembali",
                color = Gold,
                fontSize = 15.sp,
                modifier = Modifier.clickable { onClose() }.padding(6.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                game.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Browser",
                color = Color(0xFF7FD1C1),
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    try {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                    }
                }.padding(6.dp),
            )
        }
        if (prog in 1..99) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF2A2A2A))) {
                Box(Modifier.fillMaxWidth(prog / 100f).height(2.dp).background(Gold))
            }
        }
        if (err.isNotBlank()) {
            Text(err, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(10.dp))
        }
        if (!started) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(false)
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                        // HP lama (vivo/Android 9) gagal menggambar canvas dengan layer hardware.
                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        setBackgroundColor(android.graphics.Color.parseColor("#0B0B0B"))
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(
                            HiveBridge(
                                onOpen = { started = true },
                                onClose = { onClose() },
                                onRecharge = {
                                    // Game minta top-up: tampilkan pesan, isi coin lewat menu Top Up.
                                    err = "Isi coin lewat menu Top Up dulu, lalu buka game lagi"
                                },
                            ),
                            "JsBirge",
                        )
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                prog = newProgress
                                if (newProgress > 40) started = true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, u: String?, f: android.graphics.Bitmap?) {
                                prog = 0
                            }
                            override fun onPageFinished(v: WebView?, u: String?) {
                                prog = 100
                                started = true
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
                        loadUrl(url)
                        web = this
                    }
                },
            )
        }
    }
}
