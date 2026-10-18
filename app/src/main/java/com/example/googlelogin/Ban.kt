package com.example.googlelogin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore

/** Memantau koleksi "banned". Kalau uid ini ada di sana, layar penuh menutup aplikasi. */
@Composable
fun BanGate(uid: String, onSignOut: () -> Unit, onBanned: () -> Unit) {
    var banned by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    DisposableEffect(uid) {
        if (uid.isEmpty()) return@DisposableEffect onDispose { }
        val reg = FirebaseFirestore.getInstance().collection("banned").document(uid)
            .addSnapshotListener { s, _ ->
                val b = s?.exists() == true
                banned = b
                reason = s?.getString("reason") ?: ""
                if (b) onBanned()
            }
        onDispose { reg.remove() }
    }
    if (banned) {
        BackHandler { }
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0B)).blockTouches(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("🚫", fontSize = 56.sp)
                Spacer(Modifier.height(12.dp))
                Text("Akun diblokir", color = GoldLight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Akun ini diblokir oleh developer." + (if (reason.isNotBlank()) "\nAlasan: $reason" else ""),
                    color = Color(0xFFBDBDBD), fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                GoldButton("Keluar") { onSignOut() }
            }
        }
    }
}
