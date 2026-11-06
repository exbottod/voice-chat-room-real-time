package com.rezochatvoice.exceo

import android.annotation.SuppressLint
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Zeus Rezo: slot 5 gulungan berbasis HTML5 yang disajikan dari jsDelivr.
 *
 * Game hanya menggambar. Hasil putaran, potong coin, dan kemenangan
 * diputuskan server dompet lewat endpoint /g/spin, jadi tidak bisa diakali
 * dari dalam WebView. Pemain dibuka dengan ?code=uid.exp.md5(uid.exp.salt).
 */
object ZeusRezo {
    private const val SALT = "rezo-game-2026"
    private const val ALAMAT =
        "https://cdn.jsdelivr.net/gh/exbottod/voice-chat-room-real-time@main/games/zeus/index.html"

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
        return sb.toString()
    }

    /** code sementara, berlaku 12 jam. */
    fun code(uid: String): String {
        val exp = System.currentTimeMillis() / 1000L + 12L * 3600L
        return "$uid.$exp.${md5("$uid.$exp.$SALT")}"
    }

    fun url(uid: String): String = ALAMAT + "?code=" + URLEncoder.encode(code(uid), "UTF-8")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZeusRezoHtmlScreen(myUid: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF04120F))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF04120F.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = WebViewClient()
                        loadUrl(ZeusRezo.url(myUid))
                    }
                },
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(34.dp)
                    .background(Color(0xCC000000), CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Text("\u2715", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
