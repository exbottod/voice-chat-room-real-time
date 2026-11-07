package com.rezochatvoice.exceo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Cek apakah HP benar-benar punya internet (data seluler atau Wi-Fi).
 * Dipakai untuk memunculkan pemberitahuan "Jaringan kurang kuat~" saat
 * data internet dimatikan, jadi user tahu kenapa app berhenti memuat.
 */
fun adaInternet(ctx: Context): Boolean = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = cm.activeNetwork
    val c = if (n != null) cm.getNetworkCapabilities(n) else null
    c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
} catch (e: Exception) {
    true
}

/**
 * Pemberitahuan jaringan: muncul di tengah layar saat internet mati,
 * hilang sendiri begitu koneksi kembali. Diberi jeda sebentar supaya
 * perpindahan Wi-Fi ke data tidak memunculkan pemberitahuan palsu.
 */
@Composable
fun NetworkBanner(
    modifier: Modifier = Modifier,
    pesan: String = "Jaringan kurang kuat~",
) {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(adaInternet(ctx)) }
    var tampil by remember { mutableStateOf(!online) }

    DisposableEffect(Unit) {
        val cm = try {
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        } catch (e: Exception) {
            null
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = adaInternet(ctx)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
        }
        onDispose {
            try {
                cm?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
            }
        }
    }

    // Jeda 1 detik: kalau internet langsung kembali, pemberitahuan tidak berkedip.
    LaunchedEffect(online, tampil) {
        if (!online && !tampil) {
            delay(1000)
            if (!online) tampil = true
        } else if (online && tampil) {
            tampil = false
        }
    }

    AnimatedVisibility(visible = tampil, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = BiasAlignment(0f, 0.3f)) {
            Box(
                Modifier
                    .background(Color(0xE6202124), RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text(
                    text = tr(pesan),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
