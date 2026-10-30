package com.example.googlelogin

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Slot 777: mesin slot 5 gulungan yang berjalan offline di dalam app. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Slot777HtmlScreen(myUid: String, myName: String = "Player", onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF120A1F))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF120A1F.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = WebViewClient()
                        addJavascriptInterface(
                            GameBridge(
                                myUid = myUid,
                                myName = myName,
                                label = "Slot 777",
                                gameKey = "slot777",
                                onBalance = { },
                                onClose = onClose,
                                scope = scope,
                            ),
                            "AndroidGame",
                        )
                        loadUrl("file:///android_asset/games/slot777/index.html")
                    }
                },
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp)
                    .background(Color(0xCC000000), CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
