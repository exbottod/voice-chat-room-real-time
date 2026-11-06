package com.rezochatvoice.exceo

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.util.Locale

private val WInk = Color(0xFF1F2A44)
private val WHint = Color(0xFFB4B9C4)
private val WLine = Color(0xFFEDEFF3)
private val WBlue = Color(0xFF4FC3F7)
private val WGrey = Color(0xFFD0D0D0)
private val WPink = Color(0xFFE91E8C)
private val WYellow = Color(0xFFFFD36B)

/**
 * Layar wajib setelah akun BARU mendaftar: isi avatar, alias, ulang tahun,
 * jenis kelamin, lalu pilih negara kamar. Selesai -> onDone(profil).
 */
@Composable
fun WelcomeProfileScreen(
    user: UserProfile,
    initial: ProfileData,
    onDone: (ProfileData) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var data by remember {
        mutableStateOf(
            initial.copy(
                nickname = initial.nickname.ifBlank { user.name },
                birthday = initial.birthday.ifBlank { "1996-1-1" },
                gender = initial.gender,
            ),
        )
    }
    var step by remember { mutableStateOf(0) } // 0 = data diri, 1 = negara
    var busy by remember { mutableStateOf(false) }

    // Tombol kembali tidak boleh melewati pendaftaran.
    BackHandler(enabled = true) { if (step == 1) step = 0 }

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val b64 = ProfileRepo.encodeImage(context, uri, 320, square = true, quality = 85)
            busy = false
            if (b64 != null) data = data.copy(avatarB64 = b64)
            else Toast.makeText(context, tr("Gagal membaca gambar"), Toast.LENGTH_SHORT).show()
        }
    }

    fun pickBirthday() {
        val p = data.birthday.split("-").mapNotNull { it.toIntOrNull() }
        val (y, m, d) = if (p.size == 3) Triple(p[0], p[1] - 1, p[2]) else Triple(1996, 0, 1)
        DatePickerDialog(
            context,
            { _, yy, mm, dd -> data = data.copy(birthday = "$yy-${mm + 1}-$dd") },
            y, m, d,
        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    Box(Modifier.fillMaxSize().blockTouches().background(Color.White)) {
        if (step == 0) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 26.dp),
            ) {
                Spacer(Modifier.height(64.dp))
                Text(tr("Welcome to APP"), color = WInk, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(tr("Info sempurna untuk cocokkan teman yang akurat~"), color = WInk, fontSize = 15.sp)
                Spacer(Modifier.height(30.dp))

                // Avatar
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Avatar"), color = WHint, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Box(Modifier.border(1.dp, WLine, CircleShape)) { AvatarImage(user, data, 76.dp) }
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(WYellow),
                            Alignment.Center,
                        ) { Text("📷", fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(22.dp))

                // Alias
                Text(tr("Alias"), color = WHint, fontSize = 17.sp)
                BasicTextField(
                    value = data.nickname,
                    onValueChange = { if (it.length <= 20) data = data.copy(nickname = it) },
                    singleLine = true,
                    textStyle = TextStyle(color = WInk, fontSize = 23.sp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(WLine))
                Spacer(Modifier.height(24.dp))

                // Ulang tahun
                Text(tr("Ulang tahun"), color = WHint, fontSize = 17.sp)
                Text(
                    data.birthday,
                    color = WInk,
                    fontSize = 23.sp,
                    modifier = Modifier.fillMaxWidth().clickable { pickBirthday() }.padding(top = 8.dp, bottom = 10.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(WLine))
                Spacer(Modifier.height(24.dp))

                // Jenis kelamin
                Text(tr("Jenis kelamin"), color = WHint, fontSize = 17.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                    GenderChip("♂  COWOK", data.gender == "M", WBlue, Modifier.weight(1f)) { data = data.copy(gender = "M") }
                    GenderChip("♀  CEWEK", data.gender == "F", Color(0xFFF48FB1), Modifier.weight(1f)) { data = data.copy(gender = "F") }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("*Setelah pemilihan jenis kelamin, perubahan selanjutnya tidak dapat dilakukan"),
                    color = WPink, fontSize = 13.sp,
                )
                Spacer(Modifier.height(44.dp))

                val ok = data.nickname.isNotBlank() && data.gender.isNotBlank()
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(240.dp).height(58.dp)
                        .clip(RoundedCornerShape(29.dp))
                        .background(if (ok) WYellow else Color(0xFFEFE3C4))
                        .clickable {
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    if (data.nickname.isBlank()) "Isi alias dulu" else "Pilih jenis kelamin dulu",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else step = 1
                        },
                    Alignment.Center,
                ) { Text(tr("Simpan"), color = WInk, fontSize = 19.sp) }
                Spacer(Modifier.height(40.dp))
            }
        } else {
            CountryStep(
                selected = data.countryCode,
                onBack = { step = 0 },
                onPick = { code ->
                    onDone(data.copy(countryCode = code))
                },
            )
        }
        if (busy) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun GenderChip(label: String, on: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(66.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) color else WGrey)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        Alignment.CenterStart,
    ) { Text(label, color = Color.White, fontSize = 20.sp) }
}

/** Halaman "Negara kamar": rekomendasi Indonesia, daftar A-Z, ada pencarian. */
@Composable
private fun CountryStep(selected: String, onBack: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { ProfileRepo.countries }
    val list = remember(q) { all.filter { it.name.contains(q.trim(), ignoreCase = true) } }
    val grouped = remember(list) { list.groupBy { it.name.take(1).uppercase(Locale.ROOT) }.toSortedMap() }
    val reco = remember { all.firstOrNull { it.code == "ID" } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            Text(
                "‹", color = WInk, fontSize = 34.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 18.dp),
            )
            Text(tr("Negara kamar"), color = WInk, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(24.dp)).background(Color(0xFFF0F2F6)),
            Alignment.Center,
        ) {
            if (q.isEmpty()) Text(tr("🔍 Mencari"), color = WHint, fontSize = 16.sp)
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = TextStyle(color = WInk, fontSize = 16.sp, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        Text(
            tr("Hati-hati: platform merekomendasikan konten berdasarkan negara dan wilayah Anda, daftar statistik, dll."),
            color = Color(0xFFE5484D), fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            if (q.isBlank() && reco != null) {
                item { CountryHeader("rekomendasi") }
                item { CountryRow(reco, onPick) }
            }
            grouped.forEach { (letter, rows) ->
                item(key = "h$letter") { CountryHeader(letter) }
                items(rows, key = { it.code }) { c -> CountryRow(c, onPick) }
            }
        }
    }
}

@Composable
private fun CountryHeader(t: String) {
    Text(
        t, color = Color(0xFF8A90A0), fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F2F6)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CountryRow(c: Country, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(c.code) }.padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.name, color = WInk, fontSize = 19.sp, modifier = Modifier.weight(1f))
        Text(c.flag, fontSize = 26.sp)
    }
}
