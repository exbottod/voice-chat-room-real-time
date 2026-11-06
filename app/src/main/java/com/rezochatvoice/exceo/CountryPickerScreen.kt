package com.rezochatvoice.exceo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Layar "Pilih kode area ponsel": kolom pencarian, bagian rekomendasi
 * (Indonesia di atas), lalu daftar negara urut huruf dengan kode areanya.
 */
@Composable
fun CountryPickerScreen(
    onPilih: (iso: String) -> Unit,
    onKembali: () -> Unit,
) {
    var cari by remember { mutableStateOf("") }
    val semua = remember {
        ProfileRepo.countries.sortedBy { it.name.lowercase(Locale("id")) }
    }
    val hasil = remember(cari) {
        if (cari.isBlank()) semua
        else semua.filter {
            it.name.contains(cari, true) || it.code.contains(cari, true) ||
                KodeArea.dial(it.code).contains(cari.trim().removePrefix("+"))
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B3A26), Color(0xFF07281A), Color(0xFF04150E))),
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack, contentDescription = tr("Kembali"),
                    tint = Color.White, modifier = Modifier.size(24.dp).clickable { onKembali() },
                )
                Text(
                    tr("Pilih kode area ponsel"),
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(24.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextFieldCari(cari) { cari = it }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                if (cari.isBlank()) {
                    item { Judul("rekomendasi") }
                    item { BarisNegara("Indonesia", "+62") { onPilih("ID") } }
                }
                var hurufTerakhir = ""
                items(hasil, key = { it.code }) { c ->
                    val huruf = c.name.take(1).uppercase(Locale("id"))
                    if (huruf != hurufTerakhir) {
                        hurufTerakhir = huruf
                        Judul(huruf)
                    }
                    BarisNegara(c.name, KodeArea.dial(c.code)) { onPilih(c.code) }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun Judul(teks: String) {
    Text(tr(teks), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun BarisNegara(nama: String, dial: String, klik: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = klik)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(nama, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(dial, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasicTextFieldCari(nilai: String, ubah: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = nilai,
        onValueChange = ubah,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            if (nilai.isEmpty()) {
                Text(tr("Mencari"), color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
            }
            inner()
        },
    )
}
