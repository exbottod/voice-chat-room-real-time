package com.rezochatvoice.exceo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/*
 * Layar login gaya baru (mengikuti desain: latar hijau bokeh, logo kelinci,
 * judul Rezo Star, tiga tombol pil, dan baris persetujuan di bawah).
 *
 * Sengaja TIDAK memakai scroll: seluruh isi dipatok memenuhi layar supaya
 * halaman tidak bisa digeser naik-turun. Ukuran memakai bobot (weight) dan
 * skala ikon agar tetap rapi di layar kecil maupun besar.
 */

private val HijauGelap = Color(0xFF0A2A21)
private val HijauLebih = Color(0xFF061712)
private val HijauAksen = Color(0xFF2ED573)
private val HijauMuda = Color(0xFFB9F5D0)

@Composable
fun LoginScreen(
    loading: Boolean,
    onGoogleClick: () -> Unit,
    onFacebookClick: () -> Unit = {},
    onKirimKode: (String, (String?, String?) -> Unit) -> Unit = { _, cb ->
        cb(null, "Login nomor belum siap")
    },
    onVerifikasiKode: (String, String) -> Unit = { _, _ -> },
    onKirimUlang: (String, (String?, String?) -> Unit) -> Unit = { _, cb -> cb(null, null) },
    perluPassword: Boolean = false,
    modePasswordHp: Boolean = false,
    onLengkapiPendaftaran: (String, String) -> Unit = { _, _ -> },
    onLoginNomorPassword: (String, String) -> Unit = { _, _ -> },
    onGantiNomor: () -> Unit = {},
    onEmailLogin: (String, String) -> Unit,
    onForgotPassword: () -> Unit,
    onSignUp: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var formHp by remember { mutableStateOf(false) }
    var formEmail by remember { mutableStateOf(false) }
    var nomor by remember { mutableStateOf("") }
    var kode by remember { mutableStateOf("") }
    var verifId by remember { mutableStateOf<String?>(null) }
    var menunggu by remember { mutableStateOf(false) }
    var sisaDetik by remember { mutableStateOf(0) }
    var passwordHp by remember { mutableStateOf("") }
    var lihatPassHp by remember { mutableStateOf(false) }
    var modePassword by remember { mutableStateOf(false) }
    var negaraIso by remember { mutableStateOf("ID") }
    var pilihZona by remember { mutableStateOf(false) }
    val namaNegara = remember(negaraIso) { ProfileRepo.countryName(negaraIso) }
    val kodeDial = remember(negaraIso) { KodeArea.dial(negaraIso) }
    androidx.compose.runtime.LaunchedEffect(verifId) {
        if (verifId != null) {
            sisaDetik = 60
            while (sisaDetik > 0) {
                kotlinx.coroutines.delay(1000L)
                sisaDetik -= 1
            }
        }
    }

    if (pilihZona) {
        CountryPickerScreen(
            onPilih = { iso -> negaraIso = iso; pilihZona = false },
            onKembali = { pilihZona = false },
        )
        return
    }
    var lihatSyarat by remember { mutableStateOf(false) }

    // Halaman Persyaratan Pengguna (privacy.html) di atas layar login.
    if (lihatSyarat) {
        BackHandler { lihatSyarat = false }
        Box(Modifier.fillMaxSize()) {
            PrivacyScreen(onBack = { lihatSyarat = false })
        }
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    Box(Modifier.fillMaxSize().background(HijauLebih)) {
        // Latar dari desain (bokeh hijau + kelinci di pojok kanan bawah).
        Image(
            painter = painterResource(R.drawable.bg_login),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alignment = Alignment.BottomEnd,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 26.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---------- Tautan login email di pojok kanan atas ----------
            if (!formHp && !formEmail) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "Login dengan email",
                        color = HijauAksen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { formEmail = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            // ---------- Logo + nama aplikasi ----------
            Spacer(Modifier.weight(if (formHp || formEmail) 0.06f else 0.16f))
            Image(
                painter = painterResource(R.drawable.ic_launcher_logo),
                contentDescription = "Rezo Star",
                modifier = Modifier
                    .size(if (formHp || formEmail) 76.dp else 104.dp)
                    .clip(RoundedCornerShape(26.dp)),
            )
            Spacer(Modifier.height(if (formHp || formEmail) 8.dp else 14.dp))
            Text(
                "Rezo Star",
                color = Color.White,
                fontSize = if (formHp || formEmail) 24.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(Color(0x66000000), Offset(0f, 3f), 8f)),
            )
            if (!formHp && !formEmail) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Suara, room, dan teman baru",
                    color = HijauMuda.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.weight(if (formHp || formEmail) 0.04f else 0.12f))

            if (formEmail) {
                // ---------- Login email & password ----------
                Text(
                    "Login dengan email",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim().take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(26.dp),
                    colors = fieldColorsLogin(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Password") },
                    trailingIcon = {
                        Text(
                            if (showPassword) "Sembunyi" else "Lihat",
                            color = HijauAksen, fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { showPassword = !showPassword }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(26.dp),
                    colors = fieldColorsLogin(),
                )
                Spacer(Modifier.height(14.dp))
                val bisaEmail = email.contains("@") && password.length >= 6 && !loading
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (bisaEmail) Color.White else Color.White.copy(alpha = 0.35f))
                        .clickable(enabled = bisaEmail) { onEmailLogin(email, password) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = Color(0xFF10231C), strokeWidth = 2.4.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Text("Login", color = Color(0xFF10231C), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Kembali",
                        color = HijauMuda, fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { formEmail = false }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                    Text(
                        "Lupa password?",
                        color = HijauAksen, fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onForgotPassword() }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
            } else if (!formHp) {
                // ---------- Tiga tombol pil ----------
                TombolPil(
                    teks = "Login dengan Google",
                    onClick = { if (!loading) onGoogleClick() },
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = Color(0xFF1F1F1F), strokeWidth = 2.4.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        GoogleLogo(Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                TombolPil(
                    teks = "Login dengan Facebook",
                    onClick = { if (!loading) onFacebookClick() },
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = Color(0xFF1F1F1F), strokeWidth = 2.4.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        IkonFacebook()
                    }
                }
                Spacer(Modifier.height(12.dp))
                TombolPil(
                    teks = "Login dengan HP",
                    onClick = { formHp = true },
                ) {
                    Icon(
                        Icons.Default.Phone, null,
                        tint = Color(0xFF1F1F1F), modifier = Modifier.size(21.dp),
                    )
                }
            } else {
                // ---------- Login nomor HP: kode zona + nomor, lalu kode SMS ----------
                val digit = KodeArea.tanpaKode(negaraIso, nomor)
                val nomorLengkap = KodeArea.gabung(negaraIso, nomor)
                val nomorValid = digit.length in 6..14

                if (modePasswordHp && !perluPassword) {
                    // ---------- Nomor sudah terdaftar: langsung password ----------
                    Text(nomorLengkap, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passwordHp,
                        onValueChange = { passwordHp = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Password") },
                        trailingIcon = {
                            Text(
                                if (lihatPassHp) "Sembunyi" else "Lihat",
                                color = HijauAksen, fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable { lihatPassHp = !lihatPassHp }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        },
                        visualTransformation = if (lihatPassHp) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(26.dp),
                        colors = fieldColorsLogin(),
                    )
                    Spacer(Modifier.height(14.dp))
                    val bisaLogin = passwordHp.isNotBlank() && !loading
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                if (bisaLogin) Color.White else Color.White.copy(alpha = 0.35f),
                            )
                            .clickable(enabled = bisaLogin) { onLoginNomorPassword(nomorLengkap, passwordHp) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                color = Color(0xFF10231C), strokeWidth = 2.4.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Text("Login", color = Color(0xFF10231C), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Bukan nomor ini? Ganti nomor",
                        color = HijauMuda, fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onGantiNomor() }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                } else if (perluPassword) {
                    // ---------- Tahap Mendaftar: buat password ----------
                    Text(
                        "Mendaftar", color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(nomorLengkap, color = HijauMuda, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passwordHp,
                        onValueChange = { passwordHp = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Buat password (min. 6 karakter)") },
                        trailingIcon = {
                            Text(
                                if (lihatPassHp) "Sembunyi" else "Lihat",
                                color = HijauAksen, fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable { lihatPassHp = !lihatPassHp }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        },
                        visualTransformation = if (lihatPassHp) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(26.dp),
                        colors = fieldColorsLogin(),
                    )
                    Spacer(Modifier.height(14.dp))
                    val siap = passwordHp.length >= 6 && !loading
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                if (siap) Brush.horizontalGradient(listOf(Color(0xFF2FB500), Color(0xFF008A12)))
                                else Brush.horizontalGradient(listOf(Color(0x552FB500), Color(0x55008A12))),
                            )
                            .clickable(enabled = siap) { onLengkapiPendaftaran(nomorLengkap, passwordHp) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                color = Color.White, strokeWidth = 2.4.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Text(
                                "LENGKAPI PENDAFTARAN",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            )
                        }
                    }
                } else if (verifId == null) {
                    // Baris kode zona (tetap Indonesia +62, bisa diperluas nanti).
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable { pilihZona = true }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(namaNegara, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("($kodeDial)", color = HijauMuda, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nomor,
                        onValueChange = { nomor = it.filter { c -> c.isDigit() }.take(14) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Nomor HP, contoh 81234567890") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(26.dp),
                        colors = fieldColorsLogin(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(if (nomorValid && !menunggu) Color.White else Color.White.copy(alpha = 0.35f))
                            .clickable(enabled = nomorValid && !menunggu) {
                                menunggu = true
                                onKirimKode(nomorLengkap) { vid, err ->
                                    menunggu = false
                                    if (vid != null) {
                                        verifId = vid
                                        kode = ""
                                    } else {
                                        android.widget.Toast.makeText(
                                            context, err ?: "Gagal mengirim kode",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (menunggu) {
                            CircularProgressIndicator(
                                color = Color(0xFF10231C), strokeWidth = 2.4.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Text("Lanjut", color = Color(0xFF10231C), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                } else {
                    Text(
                        "Kode dikirim ke $nomorLengkap",
                        color = HijauMuda, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = kode,
                        onValueChange = { kode = it.filter { c -> c.isDigit() }.take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Masukkan kode 6 angka") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(26.dp),
                        colors = fieldColorsLogin(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(if (kode.length == 6 && !loading) Color.White else Color.White.copy(alpha = 0.35f))
                            .clickable(enabled = kode.length == 6 && !loading) {
                                verifId?.let { onVerifikasiKode(it, kode) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                color = Color(0xFF10231C), strokeWidth = 2.4.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Text("Selanjutnya", color = Color(0xFF10231C), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (verifId == null) "Kembali" else "Ganti nomor",
                        color = HijauMuda, fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                if (verifId == null) formHp = false else {
                                    verifId = null
                                    kode = ""
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                    if (verifId != null) {
                        Text(
                            if (menunggu) "Mengirim..." else if (sisaDetik > 0) "Kirim ulang($sisaDetik)" else "Kirim ulang",
                            color = if (sisaDetik > 0 || menunggu) HijauMuda.copy(alpha = 0.5f) else HijauAksen,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable(enabled = sisaDetik == 0 && !menunggu) {
                                    // Pakai ForceResendingToken dari Firebase, bukan minta kode baru.
                                    menunggu = true
                                    onKirimUlang(nomorLengkap) { vid, err ->
                                        menunggu = false
                                        if (vid != null) {
                                            verifId = vid
                                            kode = ""
                                        } else if (err != null) {
                                            android.widget.Toast.makeText(
                                                context, err, android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.10f))

            // ---------- Baris persetujuan (bisa diklik) ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Setujui Rezo Star ",
                    color = Color(0xFF9FC3B4),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "\u300APersyaratan Pengguna\u300B",
                    color = HijauAksen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { lihatSyarat = true }
                        .padding(vertical = 2.dp),
                )
                Text(
                    " dan login",
                    color = Color(0xFF9FC3B4),
                    fontSize = 12.sp,
                )
            }

            if (!formHp) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Belum punya akun? ",
                    color = Color(0xFF9FC3B4),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onSignUp).padding(6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Latar hijau gelap dengan bulatan bokeh dan butiran cahaya, seperti desain. */
@Composable
private fun LatarBokeh() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF12483A), HijauGelap, HijauLebih),
            ),
        )
        // Bulatan bokeh lembut, posisi tetap supaya tidak berkedip saat recompose.
        val bokeh = listOf(
            Triple(0.16f, 0.14f, 0.30f),
            Triple(0.86f, 0.10f, 0.24f),
            Triple(0.52f, 0.34f, 0.40f),
            Triple(0.10f, 0.62f, 0.34f),
            Triple(0.92f, 0.56f, 0.30f),
            Triple(0.40f, 0.86f, 0.36f),
            Triple(0.74f, 0.78f, 0.26f),
        )
        for ((fx, fy, fr) in bokeh) {
            val r = size.minDimension * fr * 0.5f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF3FE08C).copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * fx, size.height * fy),
                    radius = r,
                ),
                radius = r,
                center = Offset(size.width * fx, size.height * fy),
            )
        }
        // Butiran cahaya kecil.
        for (i in 0 until 60) {
            val sudut = i * 2.399963f
            val rad = (0.10f + ((i * 37) % 90) / 100f)
            val x = size.width * (0.5f + rad * 0.5f * cos(sudut))
            val y = size.height * (0.5f + rad * 0.5f * sin(sudut))
            val r = if (i % 7 == 0) 2.4f else 1.3f
            drawCircle(
                Color.White.copy(alpha = if (i % 5 == 0) 0.55f else 0.28f),
                radius = r, center = Offset(x, y),
            )
        }
        // Cahaya hijau di pojok atas.
        drawRect(
            Brush.radialGradient(
                listOf(HijauAksen.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.width * 0.9f,
            ),
            size = Size(size.width, size.height),
        )
    }
}

/** Tombol pil putih dengan ikon di kiri dan teks di tengah. */
@Composable
private fun TombolPil(
    teks: String,
    onClick: () -> Unit,
    ikon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color(0xFFF7F7F7))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().padding(start = 18.dp), contentAlignment = Alignment.CenterStart) {
            ikon()
        }
        Text(
            teks,
            color = Color(0xFF1F1F1F),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Ikon "f" Facebook digambar sederhana (tanpa aset tambahan). */
@Composable
private fun IkonFacebook() {
    // Logo resmi Facebook dari aset desain, bukan digambar manual.
    Image(
        painter = painterResource(R.drawable.ic_fb_logo),
        contentDescription = "Facebook",
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun fieldColorsLogin() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = HijauAksen,
    focusedBorderColor = HijauAksen,
    unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
    focusedPlaceholderColor = Color(0xFF8FB3A6),
    unfocusedPlaceholderColor = Color(0xFF8FB3A6),
)
