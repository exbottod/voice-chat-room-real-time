package com.example.googlelogin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Versi aplikasi, otomatis sinkron dengan versionName APK yang terpasang. */
@Composable
fun appVersionName(): String {
    val ctx = LocalContext.current
    return try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "-"
    } catch (e: Exception) {
        "-"
    }
}

/** Halaman "Tentang Rezo Chat Voice": logo, nama aplikasi, dan versi APK. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.White)) {
        // Top bar putih: tombol back + judul di tengah
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp)) {
            Text(
                "‹", color = Color(0xFF222222), fontSize = 38.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack)
                    .padding(horizontal = 18.dp),
            )
            Text(
                "Tentang Rezo Chat Voice", color = Color(0xFF222222), fontSize = 19.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_logo), null,
                Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(22.dp))
            Text("REZO CHAT VOICE", color = Color(0xFF444444), fontSize = 24.sp)
            Spacer(Modifier.height(6.dp))
            Text("Version ${appVersionName()}", color = Color(0xFF8A86A0), fontSize = 18.sp)
        }
    }
}
