package com.example.googlelogin

import android.app.Activity
import android.app.DatePickerDialog
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.util.Locale

private val Ink = Color(0xFF1F2A44)
private val Hint = Color(0xFF9AA1B0)
private val Line = Color(0xFFEEEEEE)

private enum class EditDialog { Gender, Nickname, Country, Bio }

@Composable
private fun EditRow(
    label: String,
    onClick: (() -> Unit)?,
    chevron: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        content()
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = Color(0xFFB0B7C6), fontSize = 24.sp)
        }
    }
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(Line))
}

@Composable
fun EditProfileScreen(
    user: UserProfile,
    userId: String,
    profile: ProfileData,
    onChange: (ProfileData) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EditDialog?>(null) }

    val nick = profile.nickname.ifBlank { user.name }
    val bgBmp = remember(profile.backgroundB64) { ProfileRepo.decodeB64(profile.backgroundB64) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    BackHandler(onBack = onBack)

    // Ikon status bar & navigasi gelap karena latar halaman ini putih.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.isAppearanceLightStatusBars = true
        ctl?.isAppearanceLightNavigationBars = true
        onDispose {
            ctl?.isAppearanceLightStatusBars = false
            ctl?.isAppearanceLightNavigationBars = false
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val b64 = ProfileRepo.encodeImage(context, uri, 320, square = true, quality = 85)
            busy = false
            if (b64 != null) {
                onChange(profile.copy(avatarB64 = b64))
                toast("Avatar diperbarui")
            } else toast("Gagal membaca gambar")
        }
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val b64 = ProfileRepo.encodeImage(context, uri, 900, square = false, quality = 72)
            busy = false
            if (b64 != null) {
                onChange(profile.copy(backgroundB64 = b64))
                toast("Latar belakang diperbarui")
            } else toast("Gagal membaca gambar")
        }
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    fun showDatePicker() {
        val parts = profile.birthday.split("-").mapNotNull { it.toIntOrNull() }
        val (y, m, d) = if (parts.size == 3) Triple(parts[0], parts[1] - 1, parts[2]) else Triple(2000, 0, 1)
        DatePickerDialog(
            context,
            { _, yy, mm, dd ->
                onChange(profile.copy(birthday = String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd)))
            },
            y, m, d,
        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    Box(Modifier.fillMaxSize().blockTouches().background(Color.White)) {
        Column(Modifier.fillMaxSize()) {
            // ---------- Top bar ----------
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Ink)
                }
                Text(tr("Edit profil"), color = Ink, fontSize = 19.sp, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                // Avatar
                EditRow("Avatar", onClick = { avatarPicker.launch(imageOnly) }) {
                    Box(Modifier.padding(vertical = 10.dp).border(1.dp, Line, CircleShape)) {
                        AvatarImage(user, profile, 56.dp)
                    }
                }
                // User ID
                EditRow("ID Pengguna", onClick = {
                    clipboard.setText(AnnotatedString(userId))
                    toast("ID disalin")
                }, chevron = false) {
                    Text(userId, color = Ink, fontSize = 17.sp)
                }
                // Gender
                EditRow("Jenis kelamin", onClick = { dialog = EditDialog.Gender }) {
                    when (profile.gender) {
                        "M" -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF1E88E5)), Alignment.Center) {
                            Text("♂", color = Color.White, fontSize = 20.sp)
                        }
                        "F" -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFEC407A)), Alignment.Center) {
                            Text("♀", color = Color.White, fontSize = 20.sp)
                        }
                        else -> Text(tr("Belum diatur"), color = Hint, fontSize = 16.sp)
                    }
                }
                // Backgrounds
                EditRow(
                    "Latar belakang(${if (bgBmp != null) 1 else 0}/1)",
                    onClick = { bgPicker.launch(imageOnly) },
                    chevron = false,
                ) {
                    Box(
                        Modifier
                            .padding(vertical = 10.dp)
                            .size(66.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF0F0F0)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bgBmp != null) {
                            Image(bgBmp, "Latar belakang", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .background(Color(0xAA000000), CircleShape)
                                    .clickable {
                                        onChange(profile.copy(backgroundB64 = null))
                                        toast("Latar belakang dihapus")
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text("✕", color = Color.White, fontSize = 11.sp) }
                        } else {
                            Icon(Icons.Default.Add, "Tambah", tint = Hint, modifier = Modifier.size(32.dp))
                        }
                    }
                }
                // Nickname
                EditRow("Nama panggilan", onClick = { dialog = EditDialog.Nickname }) {
                    Text(nick, color = Ink, fontSize = 17.sp, maxLines = 1)
                }
                // Country
                EditRow("Negara", onClick = { dialog = EditDialog.Country }) {
                    Text(flagEmoji(profile.countryCode), fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(ProfileRepo.countryName(profile.countryCode), color = Ink, fontSize = 17.sp, maxLines = 1)
                }
                // Birthday
                EditRow("Tanggal lahir", onClick = { showDatePicker() }) {
                    Text("🗓", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        profile.birthday.ifBlank { "Belum diatur" },
                        color = if (profile.birthday.isBlank()) Hint else Ink,
                        fontSize = 17.sp,
                    )
                }

                // Bio
                Spacer(Modifier.height(28.dp))
                Text(
                    "Perkenalkan diri saya",
                    color = Ink,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .heightIn(min = 140.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFF2F2F2))
                        .clickable { dialog = EditDialog.Bio }
                        .padding(20.dp),
                ) {
                    Text(
                        profile.bio.ifBlank { "Tulis perkenalan singkat tentang dirimu" },
                        color = if (profile.bio.isBlank()) Hint else Ink,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 32.dp),
                    )
                    Icon(
                        Icons.Default.Edit,
                        "Edit bio",
                        tint = Hint,
                        modifier = Modifier.align(Alignment.BottomEnd).size(26.dp),
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }

    // ---------- Dialog ----------
    when (dialog) {
        EditDialog.Gender -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr("Jenis kelamin")) },
            text = {
                Column {
                    listOf("M" to "♂  Pria", "F" to "♀  Wanita").forEach { (code, label) ->
                        Text(
                            label,
                            fontSize = 18.sp,
                            color = if (profile.gender == code) Color(0xFF2FC4AE) else Color.Unspecified,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChange(profile.copy(gender = code))
                                    dialog = null
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ dialog = null }) { Text(tr("Batal")) } },
        )
        EditDialog.Nickname -> TextEditDialog(
            title = "Nama panggilan",
            initial = nick,
            maxLen = 20,
            singleLine = true,
            allowBlank = false,
            onSave = { onChange(profile.copy(nickname = it)); dialog = null },
            onDismiss = { dialog = null },
        )
        EditDialog.Bio -> TextEditDialog(
            title = "Perkenalkan diri saya",
            initial = profile.bio,
            maxLen = 150,
            singleLine = false,
            allowBlank = true,
            onSave = { onChange(profile.copy(bio = it)); dialog = null },
            onDismiss = { dialog = null },
        )
        EditDialog.Country -> CountryDialog(
            onPick = { onChange(profile.copy(countryCode = it.code)); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    maxLen: Int,
    singleLine: Boolean,
    allowBlank: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= maxLen) text = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                supportingText = { Text("${text.length}/$maxLen") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = allowBlank || text.isNotBlank(), onClick = { onSave(text.trim()) }) {
                Text(tr("Simpan"))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("Batal")) } },
    )
}

@Composable
private fun CountryDialog(onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = remember(query) {
        ProfileRepo.countries.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Pilih negara")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(tr("Cari negara")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(list, key = { it.code }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.flag, fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, fontSize = 16.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(tr("Batal")) } },
    )
}
