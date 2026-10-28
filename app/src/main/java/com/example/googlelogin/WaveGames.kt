package com.example.googlelogin

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Game pihak ketiga (WaveGames). Game berjalan di WebView; coin dipotong dan
 * ditambah oleh Edge Function wallet-api lewat endpoint userInfo / submitFlow.
 * App hanya perlu membuka URL game dengan token login pemain.
 */
object WaveGames {
    // App key PRODUKSI (bukan testing AM8Nc1KMn6).
    const val APP_KEY = "NV67iPG0EV"
    private const val CATALOG = "https://my.wavegames.online/admin/game/list?appKey=$APP_KEY"
    private const val FRONT = "https://wavegames.online"

    data class Game(
        val id: Int,
        val name: String,
        val slug: String,
        val image: String,
        val maxBet: Long,
    )

    /** Daftar game dari katalog WaveGames. Kalau gagal, pakai daftar bawaan. */
    suspend fun catalog(): List<Game> = withContext(Dispatchers.IO) {
        val remote = try {
            val c = (URL(CATALOG).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
            }
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONObject("data")?.optJSONArray("games")
            val out = ArrayList<Game>()
            if (arr != null) for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                if (!g.optBoolean("playable", true)) continue
                out.add(
                    Game(
                        id = g.optInt("gameId"),
                        name = g.optString("name"),
                        slug = g.optString("routeSlug"),
                        image = g.optJSONObject("images")?.optString("listing").orEmpty(),
                        maxBet = g.optLong("maxBetLimit"),
                    ),
                )
            }
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        remote.ifEmpty { fallback }
    }

    private val fallback = listOf(
        Game(82, "Dragon Vs Tiger", "dragonvstiger", "", 500000),
        Game(8, "Fruit Blast Jackpot", "fruitblast", "", 500000),
        Game(16, "Teen Patti", "teenpatti", "", 500000),
        Game(54, "Crazy Fruit", "crazyfruit", "", 500000),
        Game(77, "Lucky 77", "lucky77", "", 500000),
        Game(80, "Greedy King", "greedyking", "", 500000),
        Game(81, "Teen Patti Pro", "teenpattipro", "", 500000),
    )

    /** Simpan nama + foto pemain supaya userInfo WaveGames bisa menampilkannya. */
    suspend fun prepare(name: String, avatar: String) {
        try {
            SupaRepo.wavePrepare(name, avatar)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    private suspend fun token(): String? = try {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            ?.getIdToken(false)?.await()?.token
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** URL game lengkap dengan token pemain. */
    suspend fun url(g: Game, embed: String = "full"): String? {
        val t = token() ?: return null
        val enc = URLEncoder.encode(t, "UTF-8")
        return "$FRONT/games/${g.slug}?appKey=$APP_KEY&gameId=${g.id}&token=$enc&embed=$embed"
    }
}

/** Daftar game + WebView. Ditampilkan penuh di atas layar room. */
@Composable
fun WaveGamesScreen(
    myName: String,
    myAvatarB64: String?,
    onClose: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var games by remember { mutableStateOf<List<WaveGames.Game>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf<Pair<WaveGames.Game, String>?>(null) }
    var err by remember { mutableStateOf("") }
    var fullMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        WaveGames.prepare(myName, myAvatarB64.orEmpty())
        games = WaveGames.catalog()
        loading = false
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0B))) {
            val play = playing
            if (play != null) {
                // Panel bawah: room tetap terlihat. Tombol di header untuk layar penuh.
                val box = if (fullMode) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().fillMaxHeight(0.75f)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                Box(Modifier.fillMaxSize()) {
                    Column(
                        box.align(if (fullMode) Alignment.Center else Alignment.BottomCenter)
                            .background(Color(0xFF0B0B0B)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFF141414))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "‹ Kembali",
                                color = Gold,
                                fontSize = 15.sp,
                                modifier = Modifier.clickable { playing = null }.padding(6.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                play.first.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (fullMode) "▽ Panel" else "⤢ Penuh",
                                color = Gold,
                                fontSize = 14.sp,
                                modifier = Modifier.clickable { fullMode = !fullMode }.padding(6.dp),
                            )
                        }
                        WaveGameView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            game = play.first,
                            url = play.second,
                            onBack = { playing = null },
                        )
                    }
                }
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
                            "🎮 Game",
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
                            Text(err, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(games) { g ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        err = ""
                                        scope.launch {
                                            val u = WaveGames.url(g)
                                            if (u == null) err = "Gagal membuat sesi game, coba lagi" else playing = g to u
                                        }
                                    },
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(1.6f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF1C1C1C)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (g.image.isNotBlank()) {
                                            AsyncImage(
                                                model = g.image,
                                                contentDescription = g.name,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Text("🎲", fontSize = 34.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        g.name,
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WaveGameView(
    modifier: Modifier = Modifier,
    game: WaveGames.Game,
    url: String,
    onBack: () -> Unit,
) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var prog by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf("") }
    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }
    Box(modifier) {
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
                    settings.allowFileAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    // Banyak halaman game menolak WebView default; pakai UA Chrome mobile
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                            return true
                        }
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            prog = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, u: String?, f: android.graphics.Bitmap?) {
                            err = ""
                            prog = 0
                        }
                        override fun onPageFinished(v: WebView?, u: String?) {
                            prog = 100
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
                                err = "Server game membalas " + (e?.statusCode ?: 0) + " " + (r.url?.path ?: "")
                            }
                        }
                    }
                    loadUrl(url)
                    web = this
                }
            },
            onRelease = { it.destroy() },
        )
        if (err.isNotEmpty()) {
            Column(
                Modifier.fillMaxSize().background(Color(0xCC000000)).padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    err,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Muat ulang",
                    color = Gold,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        err = ""
                        web?.reload()
                    }.padding(10.dp),
                )
            }
        }
    }
}
