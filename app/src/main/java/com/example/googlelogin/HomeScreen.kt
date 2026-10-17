package com.example.googlelogin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// TODO: ganti isi layar ini dengan Scaffold + NavigationBar (tab navigasi).
@Composable
fun HomeScreen(user: UserProfile, onSignOutClick: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        RoyalBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!user.photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = "Foto profil",
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .border(2.dp, Gold, CircleShape),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                user.name,
                color = GoldLight,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(user.email, color = Color(0xFFBDBDBD), fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Text("Halaman utama (tab navigasi) menyusul", color = Color(0xFF8A8A8A))
            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = onSignOutClick,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Gold),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight),
            ) {
                Text("Keluar")
            }
        }
    }
}
