package com.example.googlelogin

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
private fun PageHeader(title: String) {
    Text(
        title,
        color = Gold,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun EmptyPage(title: String, message: String) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                message,
                color = Color(0xFF8A8A8A),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}

@Composable
fun DiscoverTab() {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Temukan")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("Cari room atau ID") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Gold,
                focusedBorderColor = Gold,
                unfocusedBorderColor = Gold.copy(alpha = 0.6f),
                focusedPlaceholderColor = Color(0xFF8A8A8A),
                unfocusedPlaceholderColor = Color(0xFF8A8A8A),
            ),
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Belum ada hasil pencarian", color = Color(0xFF8A8A8A))
        }
    }
}

@Composable
fun FamilyTab() = EmptyPage("Family", "Belum ada family.\nFitur family segera hadir.")

@Composable
fun MessagesTab() = EmptyPage("Pesan", "Belum ada pesan.")

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(CardBlack.copy(alpha = 0.9f))
            .border(1.dp, Gold.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Text("›", color = GoldLight, fontSize = 20.sp)
    }
}

@Composable
fun ProfileTab(user: UserProfile, onSignOutClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        if (!user.photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = user.photoUrl,
                contentDescription = "Foto profil",
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .border(2.dp, Gold, CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(CardBlack)
                    .border(2.dp, Gold, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(user.name.take(1).uppercase(), color = GoldLight, fontSize = 36.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(user.name, color = GoldLight, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(user.email, color = Color(0xFFBDBDBD), fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        listOf("Edit profil", "Dompet", "Pengaturan", "Bantuan").forEach { label ->
            MenuRow(label) {
                Toast.makeText(context, "$label segera hadir", Toast.LENGTH_SHORT).show()
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onSignOutClick,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, Gold),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight),
        ) { Text("Keluar") }
    }
}
