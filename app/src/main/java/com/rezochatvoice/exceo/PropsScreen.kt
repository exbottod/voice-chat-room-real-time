package com.rezochatvoice.exceo

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * Halaman Props: tempat kartu hadiah VIP dari roket.
 * Kartu belum aktif saat menang; masa berlakunya mulai dihitung ketika
 * pemiliknya menekan tombol pakai. Hadiah coin tidak muncul di sini karena
 * langsung masuk saldo saat roket meluncur.
 */
@Composable
fun PropsScreen(myUid: String, onBack: () -> Unit, onWalletChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daftar by remember { mutableStateOf<List<SupaRepo.PropKartu>>(emptyList()) }
    var memuat by remember { mutableStateOf(true) }
    var pesan by remember { mutableStateOf("") }
    var sibuk by remember { mutableStateOf("") }

    suspend fun muat() {
        memuat = true
        daftar = try {
            SupaRepo.propList(myUid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        memuat = false
    }

    LaunchedEffect(myUid) { muat() }

    Column(Modifier.fillMaxSize().background(Color(0xFF10131A))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("‹"), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
            )
            Text(tr("Props"), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Text(tr("Kartu VIP dari roket. Tekan Pakai untuk mulai menghitung masa aktifnya."),
            color = Color(0xFF9AA4B2), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        if (pesan.isNotBlank()) {
            Text(pesan, color = Color(0xFFFFD54F), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(6.dp))
        }
        if (memuat) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                Text(tr("Memuat..."), color = Color(0xFF9AA4B2), fontSize = 13.sp)
            }
        } else if (daftar.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                Text(tr("Belum ada kartu. Menangkan roket untuk mendapat kartu VIP."),
                    color = Color(0xFF9AA4B2), fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(daftar) { k ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF2A2118), Color(0xFF3A2C1C))))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("\uD83C\uDFAB"), fontSize = 26.sp)
                        Spacer(Modifier.padding(start = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.judul, color = Color(0xFFFFD54F), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(tr("Dari roket"), color = Color(0xFF9AA4B2), fontSize = 11.sp)
                        }
                        Text(
                            if (sibuk == k.id) "..." else "Pakai",
                            color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFFFC107))
                                .clickable(enabled = sibuk.isBlank()) {
                                    sibuk = k.id
                                    scope.launch {
                                        val r = SupaRepo.propUse(myUid, k.id)
                                        sibuk = ""
                                        if (r.error == null) {
                                            pesan = "Kartu dipakai, VIP aktif sekarang"
                                            onWalletChanged()
                                            muat()
                                        } else {
                                            pesan = r.error ?: "Gagal memakai kartu"
                                        }
                                        Toast.makeText(context, pesan, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}
