package com.example.googlelogin

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Fruits Party: game jackpot buah dari zip asli, wallet lewat GameBridge (Supabase). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FruitsPartyHtmlScreen(myUid: String, myName: String = "Player", onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val loadingBg = remember {
        runCatching {
            appCtx.assets.open("games/fruitsparty/assets/loading-bg-e-bSVvi5.jpg")
                .use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1B0B36))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val mime = mapOf(
                        "html" to "text/html",
                        "js" to "application/javascript",
                        "css" to "text/css",
                        "png" to "image/png",
                        "jpg" to "image/jpeg",
                        "jpeg" to "image/jpeg",
                        "webp" to "image/webp",
                        "svg" to "image/svg+xml",
                        "json" to "application/json",
                        "woff" to "font/woff",
                        "woff2" to "font/woff2",
                        "ttf" to "font/ttf",
                    )
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF1B0B36.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.allowFileAccess = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                val url = request.url
                                // Aset game disajikan lewat https supaya script module tidak
                                // diblokir CORS seperti saat memakai file://
                                if (url.host != "appassets.androidplatform.net") return null
                                val path = url.path ?: return null
                                if (!path.startsWith("/assets/")) return null
                                val assetPath = path.removePrefix("/assets/")
                                return try {
                                    val ext = assetPath.substringAfterLast('.', "").lowercase()
                                    WebResourceResponse(
                                        mime[ext] ?: "application/octet-stream",
                                        "UTF-8",
                                        200,
                                        "OK",
                                        mapOf(
                                            "Access-Control-Allow-Origin" to "*",
                                            "Cache-Control" to "no-cache",
                                        ),
                                        ctx.assets.open(assetPath),
                                    )
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    loadError = "Gagal memuat game, coba tutup lalu buka lagi"
                                }
                            }
                        }
                        addJavascriptInterface(
                            GameBridge(
                                myUid = myUid,
                                myName = myName,
                                label = "Fruits Party",
                                gameKey = "fruitsparty",
                                onBalance = { },
                                onClose = onClose,
                                scope = scope,
                            ),
                            "AndroidGame",
                        )
                        loadUrl("file:///android_asset/games/fruitsparty/index.html")
                    }
                },
            )

            if (loading) {
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF2A0C4E), Color(0xFF12061F), Color(0xFF2A0C4E)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val bg = loadingBg
                        if (bg != null) {
                            Image(
                                bitmap = bg.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(160.dp),
                            )
                        }
                        CircularProgressIndicator(color = Color(0xFFF5C542), strokeWidth = 3.dp)
                        Text(
                            "Menyiapkan Fruits Party...",
                            color = Color(0xFFFFE9A8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (loadError.isNotEmpty()) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xE6110820)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(loadError, color = Color.White, fontSize = 14.sp)
                }
            }

            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp)
                    .background(Color(0xCC000000), CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
