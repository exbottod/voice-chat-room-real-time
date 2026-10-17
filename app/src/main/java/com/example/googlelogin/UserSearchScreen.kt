package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun UserSearchScreen(myUserId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PublicUser?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (!q.all { it.isDigit() }) {
            message = "ID hanya berisi angka"
            result = null
            return
        }
        focus.clearFocus()
        loading = true
        message = null
        result = null
        scope.launch {
            try {
                val u = UserDirectory.find(q)
                if (u == null) message = "ID $q tidak ditemukan" else result = u
            } catch (e: Exception) {
                message = "Gagal mencari: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    OverlayBox {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            GoldTopBar("Cari pengguna", onBack)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.filter { c -> c.isDigit() }.take(12) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Masukkan ID pengguna", color = Color(0xFF8A8A8A)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Gold.copy(alpha = 0.5f),
                        cursorColor = Gold,
                    ),
                )
                IconButton(onClick = { search() }) {
                    Icon(Icons.Default.Search, "Cari", tint = GoldLight)
                }
            }
            Spacer(Modifier.height(16.dp))

            if (loading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    CircularProgressIndicator(color = Gold)
                }
            }
            message?.let {
                Text(it, color = Color(0xFFBDBDBD), modifier = Modifier.padding(horizontal = 20.dp))
            }
            result?.let { u ->
                val bmp = remember(u.avatarB64) { ProfileRepo.decodeB64(u.avatarB64) }
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(CardBlack.copy(alpha = 0.9f))
                        .border(1.dp, Gold.copy(alpha = 0.4f), shape)
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val base = Modifier.size(64.dp).clip(CircleShape).border(2.dp, Gold, CircleShape)
                        when {
                            bmp != null -> Image(bmp, "Avatar", base, contentScale = ContentScale.Crop)
                            !u.photoUrl.isNullOrBlank() -> AsyncImage(u.photoUrl, "Avatar", base, contentScale = ContentScale.Crop)
                            else -> androidx.compose.foundation.layout.Box(
                                base.background(Color(0xFF263238)),
                                contentAlignment = Alignment.Center,
                            ) { Text(u.name.take(1).uppercase(), color = GoldLight, fontSize = 24.sp) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.name, color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Spacer(Modifier.width(8.dp))
                                Text(flagEmoji(u.countryCode), fontSize = 18.sp)
                            }
                            Text(
                                "ID: ${u.userId}" + if (u.userId == myUserId) "  (kamu)" else "",
                                color = Color(0xFFBDBDBD),
                                fontSize = 14.sp,
                            )
                        }
                    }
                    if (u.bio.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(u.bio, color = Color(0xFFE0E0E0), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(u.userId))
                            Toast.makeText(context, "ID disalin", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Gold),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight),
                    ) { Text("Salin ID") }
                }
            }
        }
    }
}
