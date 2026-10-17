package com.example.googlelogin

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun InviteScreen(uid: String, myUserId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val ready = myUserId.isNotEmpty() && myUserId.all { it.isDigit() }
    var invitedBy by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf(0L) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    suspend fun refresh() {
        try { invitedBy = UserDirectory.invitedBy(uid) } catch (e: Exception) { }
        try { count = UserDirectory.inviteCount(uid) } catch (e: Exception) { }
    }
    LaunchedEffect(uid) { refresh() }

    fun share() {
        val text = "Ayo gabung di REZO CHAT VOICE!\n" +
            "Unduh aplikasi terbaru: $APK_DOWNLOAD_URL\n" +
            "Kode undangan saya: $myUserId"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Bagikan undangan"))
    }

    val shape = RoundedCornerShape(16.dp)
    OverlayBox {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            GoldTopBar("Undang teman", onBack)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(CardBlack.copy(alpha = 0.9f))
                        .border(1.dp, Gold.copy(alpha = 0.4f), shape)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Kode undangan kamu", color = Color(0xFFBDBDBD), fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (ready) myUserId else "...",
                        color = GoldLight,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row {
                        OutlinedButton(
                            enabled = ready,
                            onClick = {
                                clipboard.setText(AnnotatedString(myUserId))
                                Toast.makeText(context, "Kode disalin", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, Gold),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight),
                        ) { Text("Salin kode") }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            enabled = ready,
                            onClick = { share() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
                        ) { Text("Bagikan link") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Teman yang kamu undang: $count", color = Color(0xFFE0E0E0), fontSize = 14.sp)
                }

                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(CardBlack.copy(alpha = 0.9f))
                        .border(1.dp, Gold.copy(alpha = 0.4f), shape)
                        .padding(20.dp),
                ) {
                    Text("Punya kode undangan?", color = GoldLight, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val inviter = invitedBy
                    if (inviter != null) {
                        Text("✓ Kamu sudah diundang oleh ID $inviter", color = Color(0xFF7FE3D6), fontSize = 15.sp)
                    } else {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { c -> c.isDigit() }.take(12) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Masukkan kode undangan", color = Color(0xFF8A8A8A)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = Gold.copy(alpha = 0.5f),
                                cursorColor = Gold,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            enabled = ready && !busy && code.isNotBlank(),
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    val err = UserDirectory.redeemInvite(uid, myUserId, code)
                                    busy = false
                                    if (err == null) {
                                        Toast.makeText(context, "Kode undangan berhasil dipakai", Toast.LENGTH_SHORT).show()
                                        code = ""
                                        refresh()
                                    } else message = err
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
                        ) { Text(if (busy) "Memproses..." else "Gunakan kode") }
                        message?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, textAlign = TextAlign.Start)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
