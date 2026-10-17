package com.example.googlelogin

import android.util.Patterns
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
private fun SettingRow(label: String, value: String? = null, onClick: () -> Unit) {
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
        if (value != null) {
            Text(value, color = Color(0xFF9A9A9A), fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        Text("›", color = GoldLight, fontSize = 20.sp)
    }
}

@Composable
fun SettingsScreen(
    user: UserProfile,
    userId: String,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    var showAddEmail by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    var hasPassword by remember { mutableStateOf(FirebaseSession.hasPassword()) }

    if (showPrivacy) {
        PrivacyScreen(onBack = { showPrivacy = false })
        return
    }

    BackHandler(onBack = onBack)

    OverlayBox {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            GoldTopBar("Pengaturan", onBack)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                SettingRow("Akun", user.email) {}
                SettingRow("ID pengguna", userId) {}
                SettingRow(
                    "Email & password",
                    if (hasPassword) (FirebaseSession.passwordEmail() ?: "Aktif") else "Belum diatur",
                ) {
                    if (hasPassword) {
                        Toast.makeText(context, "Login email & password sudah aktif", Toast.LENGTH_SHORT).show()
                    } else showAddEmail = true
                }
                SettingRow("Kebijakan privasi") { showPrivacy = true }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { showLogout = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Gold),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldLight),
                ) { Text("Keluar") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddEmail) {
        AddEmailDialog(
            onDone = { hasPassword = FirebaseSession.hasPassword(); showAddEmail = false },
            onDismiss = { showAddEmail = false },
        )
    }
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Keluar dari akun?") },
            text = { Text("Kamu perlu login lagi untuk memakai aplikasi.") },
            confirmButton = { TextButton(onClick = { showLogout = false; onSignOut() }) { Text("Keluar") } },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun AddEmailDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Tambah email & password") },
        text = {
            Column {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Password (min. 6 karakter)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass2,
                    onValueChange = { pass2 = it },
                    label = { Text("Ulangi password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val e = email.trim()
                    when {
                        !Patterns.EMAIL_ADDRESS.matcher(e).matches() -> error = "Format email tidak valid"
                        pass.length < 6 -> error = "Password minimal 6 karakter"
                        pass != pass2 -> error = "Konfirmasi password tidak sama"
                        else -> {
                            error = null
                            busy = true
                            scope.launch {
                                val err = FirebaseSession.linkEmailPassword(e, pass)
                                busy = false
                                if (err == null) {
                                    Toast.makeText(context, "Berhasil. Sekarang bisa login dengan email & password", Toast.LENGTH_LONG).show()
                                    onDone()
                                } else error = FirebaseSession.friendlyError(err)
                            }
                        }
                    }
                },
            ) { Text(if (busy) "Menyimpan..." else "Simpan") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Batal") } },
    )
}

/** Menampilkan docs/privacy.html (disalin ke assets/privacy.html). */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    OverlayBox {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            GoldTopBar("Kebijakan privasi", onBack)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.WHITE)
                        loadUrl("file:///android_asset/privacy.html")
                    }
                },
            )
        }
    }
}
