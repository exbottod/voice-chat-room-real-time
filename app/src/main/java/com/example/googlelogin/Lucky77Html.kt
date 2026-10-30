package com.example.googlelogin

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Lucky 77 memakai kode HTML/CSS/JS milik owner, dijalankan di dalam app.
 * Koin disambungkan ke dompet akun lewat jembatan AndroidGame.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Lucky77HtmlScreen(myUid: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var balance by remember { mutableStateOf(0L) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF14001C))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF14001C.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = WebViewClient()
                        addJavascriptInterface(
                            GameBridge(
                                myUid = myUid,
                                onBalance = { balance = it },
                                onClose = onClose,
                                scope = scope,
                            ),
                            "AndroidGame",
                        )
                        loadUrl("file:///android_asset/games/lucky77.html")
                    }
                },
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp)
                    .background(Color(0xCC000000), CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Jembatan JS -> dompet Supabase. Dipanggil dari JavaScript halaman game. */
private class GameBridge(
    private val myUid: String,
    private val onBalance: (Long) -> Unit,
    private val onClose: () -> Unit,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    @JavascriptInterface
    fun balance(): Long = runBlocking {
        val b = try {
            SupaRepo.get(myUid).wallet?.coins ?: 0L
        } catch (e: Exception) {
            0L
        }
        onBalance(b)
        b
    }

    /** Potong coin untuk taruhan. Kembalikan saldo baru, atau -1 kalau gagal. */
    @JavascriptInterface
    fun bet(amount: Long): Long = runBlocking {
        val nb = try {
            SupaRepo.waveFlow(amount, 1, UUID.randomUUID().toString(), "Lucky 77")
        } catch (e: Exception) {
            null
        }
        if (nb == null) -1L else {
            onBalance(nb)
            nb
        }
    }

    /** Tambah coin saat menang atau batal taruhan. */
    @JavascriptInterface
    fun win(amount: Long): Long = runBlocking {
        val nb = try {
            SupaRepo.waveFlow(amount, 2, UUID.randomUUID().toString(), "Lucky 77")
        } catch (e: Exception) {
            null
        }
        if (nb == null) -1L else {
            onBalance(nb)
            nb
        }
    }

    @JavascriptInterface
    fun close() {
        scope.launch { onClose() }
    }
}
