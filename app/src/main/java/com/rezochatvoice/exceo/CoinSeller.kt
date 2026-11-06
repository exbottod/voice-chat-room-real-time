package com.rezochatvoice.exceo

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Satu penjual coin resmi seperti yang tampil di halaman seller Dompet. */
data class SellerInfo(
    val uid: String,
    val store: String,
    val wa: String,
    val waLink: String,
    val stock: Long,
    val photo: String,
    val pesanan: Int,
    val active: Boolean,
    /** Nama dan foto profil asli akun di aplikasi. */
    val nama: String = "",
    val avatarUrl: String = "",
    val avatarB64: String? = null,
    /** Kode negara dari profil akun (untuk bendera di kartu). */
    val negara: String = "ID",
) {
    /** Nama yang tampil: nama akun aplikasi, cadangan nama toko. */
    val namaTampil: String get() = nama.ifBlank { store }.ifBlank { "Coin Seller" }
}

object SellerRepo {
    private fun baca(o: JSONObject?): SellerInfo? {
        if (o == null) return null
        val u = o.optString("uid", "")
        if (u.isBlank()) return null
        return SellerInfo(
            uid = u,
            store = o.optString("store", ""),
            wa = o.optString("wa", ""),
            waLink = o.optString("wa_link", ""),
            stock = o.optLong("stock", 0L),
            photo = o.optString("photo", ""),
            pesanan = o.optInt("pesanan", 0),
            active = o.optInt("active", 1) == 1,
        )
    }

    /** Daftar seller aktif untuk halaman seller di Dompet. */
    suspend fun list(): Pair<List<SellerInfo>, String> {
        val o = SupaRepo.sellerCall("list")
        val arr = o.optJSONArray("seller") ?: return Pair(emptyList(), o.optString("e", ""))
        val out = ArrayList<SellerInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val d = baca(arr.optJSONObject(i)) ?: continue
            // Nama dan foto diambil dari profil aplikasi, bukan dari data toko,
            // supaya sama dengan yang dilihat user di chat dan profil.
            val pu = try { UserDirectory.findByUid(d.uid) } catch (e: Exception) { null }
            out.add(
                if (pu == null) d else d.copy(
                    nama = pu.name,
                    avatarUrl = pu.photoUrl ?: "",
                    avatarB64 = pu.avatarB64,
                    negara = pu.countryCode.ifBlank { "ID" },
                )
            )
        }
        return Pair(out, "")
    }

    /** Data panel seller milik user ini; null kalau bukan seller. */
    suspend fun me(photo: String = "", nama: String = ""): SellerInfo? {
        val p = JSONObject()
        if (photo.isNotBlank()) p.put("photo", photo)
        if (nama.isNotBlank()) p.put("nama", nama)
        return baca(SupaRepo.sellerCall("me", p).optJSONObject("seller"))
    }

    suspend fun hist(): List<Triple<String, Long, Long>> {
        val o = SupaRepo.sellerCall("hist")
        val arr = o.optJSONArray("riwayat") ?: return emptyList()
        val out = ArrayList<Triple<String, Long, Long>>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            out.add(Triple(r.optString("buyer", ""), r.optLong("coins", 0L), r.optLong("created_at", 0L)))
        }
        return out
    }

    /** Kirim coin dari stok seller ke user penerima (uid akun, bukan ID angka). */
    suspend fun send(toUid: String, coins: Long, note: String = ""): JSONObject {
        val p = JSONObject()
            .put("to", toUid)
            .put("coins", coins)
            .put("note", note)
            .put("req", "s-" + System.currentTimeMillis() + "-" + (1000..9999).random())
        return SupaRepo.sellerCall("send", p)
    }
}

private fun numRingkas(n: Long): String {
    if (n >= 1_000_000_000_000L) return (n / 1_000_000_000_000L).toString() + "T"
    if (n >= 1_000_000_000L) return (n / 1_000_000_000L).toString() + "B"
    if (n >= 1_000_000L) return (n / 1_000_000L).toString() + "M"
    if (n >= 1_000L) return (n / 1_000L).toString() + "K"
    return n.toString()
}

private fun nomorRapi(n: String): String = n

private fun bukaWa(ctx: android.content.Context, s: SellerInfo) {
    val alamat = s.waLink.ifBlank {
        val digit = s.wa.filter { it.isDigit() }
        if (digit.isBlank()) "" else "https://wa.me/" + digit
    }
    if (alamat.isBlank()) {
        Toast.makeText(ctx, tr("Seller belum mengisi WhatsApp"), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(alamat)))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("WhatsApp tidak bisa dibuka"), Toast.LENGTH_SHORT).show()
    }
}

/** Foto bulat seller; kalau kosong pakai inisial nama toko. */
@Composable
private fun SellerAvatar(s: SellerInfo, size: Int = 52) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(0xFFE9EEF5)), contentAlignment = Alignment.Center) {
        val bmp = remember(s.avatarB64) { ProfileRepo.decodeB64(s.avatarB64) }
        val url = s.avatarUrl.ifBlank { s.photo }
        if (bmp != null) {
            Image(bmp, s.namaTampil, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (url.startsWith("http")) {
            AsyncImage(
                model = url,
                contentDescription = s.namaTampil,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                s.namaTampil.take(1).uppercase().ifBlank { "S" },
                color = Color(0xFF2A4A63),
                fontSize = (size / 2.4).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Halaman seller di Dompet. Kartu berisi foto, nama toko, bendera, pesanan 3
 * hari terakhir, stok coin, nomor WhatsApp (bisa disalin), tombol Pesan dan
 * tombol ikon WhatsApp.
 */
@Composable
fun SellerListScreen(
    onBack: () -> Unit,
    onChat: (SellerInfo) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var daftar by remember { mutableStateOf<List<SellerInfo>>(emptyList()) }
    var memuat by remember { mutableStateOf(true) }
    var galat by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val (rows, err) = SellerRepo.list()
        daftar = rows
        galat = err
        memuat = false
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F2A3C), Color(0xFF123E56), Color(0xFF0B1F2C)))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(54.dp)) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 40.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).clickable { onBack() },
                )
                Text(
                    tr("Coin Seller"),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (memuat) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tr("Memuat seller..."), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
                return@Column
            }
            if (daftar.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (galat.isNotBlank()) galat else "Belum ada coin seller.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                }
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(daftar, key = { it.uid }) { s ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFE3C46B), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SellerAvatar(s)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.namaTampil,
                                        color = Color(0xFF12324A),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(flagEmoji(s.negara), fontSize = 13.sp)
                                }
                                if (s.store.isNotBlank() && s.store != s.namaTampil) {
                                    Text(
                                        s.store,
                                        color = Color(0xFF7A8FA0),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                // Nomor WhatsApp tampil di bawah nama toko, bisa disalin.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        nomorRapi(s.wa),
                                        color = Color(0xFF4A6B80),
                                        fontSize = 12.sp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "⧉",
                                        color = Color(0xFF3B7A9E),
                                        fontSize = 13.sp,
                                        modifier = Modifier.clickable {
                                            clipboard.setText(AnnotatedString(s.wa))
                                            Toast.makeText(ctx, tr("Nomor disalin"), Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    tr("Pesanan 3 hari: ") + s.pesanan,
                                    color = Color(0xFF7A8B99),
                                    fontSize = 11.sp,
                                )
                            }
                            // Tombol ikon WhatsApp: langsung ke link WhatsApp seller.
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF25D366))
                                    .clickable { bukaWa(ctx, s) },
                                contentAlignment = Alignment.Center,
                            ) { Text("✆", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFFF6DF))
                                .border(1.dp, Color(0xFFE8D49A), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("💰", fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Stok"), color = Color(0xFF8A6A17), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text(
                                numRingkas(s.stock) + "+",
                                color = Color(0xFF8A6A17),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Brush.horizontalGradient(listOf(Color(0xFF1F7AA8), Color(0xFF2B9FD0))))
                                    .clickable { onChat(s) },
                                contentAlignment = Alignment.Center,
                            ) { Text(tr("Pesan"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF25D366))
                                    .clickable { bukaWa(ctx, s) },
                                contentAlignment = Alignment.Center,
                            ) { Text(tr("WhatsApp"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

/**
 * Panel seller di tab Profil. Hanya muncul untuk akun yang didaftarkan
 * developer sebagai coin seller. Isinya stok, kirim coin ke user, dan riwayat.
 * Seller tidak bisa menambah stok atau menarik coin.
 */
@Composable
fun SellerPanelScreen(
    myUid: String,
    myName: String,
    myPhoto: String,
    onBack: () -> Unit,
    onWalletChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SellerInfo?>(null) }
    var memuat by remember { mutableStateOf(true) }
    var galat by remember { mutableStateOf("") }
    var idTujuan by remember { mutableStateOf("") }
    var jumlah by remember { mutableStateOf("") }
    var kirim by remember { mutableStateOf(false) }
    var riwayat by remember { mutableStateOf<List<Triple<String, Long, Long>>>(emptyList()) }

    LaunchedEffect(Unit) {
        info = SellerRepo.me(photo = myPhoto, nama = "")
        riwayat = SellerRepo.hist()
        memuat = false
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F2A3C), Color(0xFF123E56), Color(0xFF0B1F2C)))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(54.dp)) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 40.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).clickable { onBack() },
                )
                Text(
                    tr("Panel Seller"),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (memuat) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tr("Memuat..."), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
                return@Column
            }
            val me = info
            if (me == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(tr("Akun ini bukan coin seller."), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Color.White).padding(14.dp),
                    ) {
                        Text(me.store.ifBlank { myName }, color = Color(0xFF12324A), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(tr("Stok coin"), color = Color(0xFF7A8B99), fontSize = 12.sp)
                        Text(
                            "💰 " + String.format("%,d", me.stock),
                            color = Color(0xFF8A6A17),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!me.active) {
                            Spacer(Modifier.height(6.dp))
                            Text(tr("Status: dinonaktifkan developer"), color = Color(0xFFB23B3B), fontSize = 12.sp)
                        }
                    }
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Color.White).padding(14.dp),
                    ) {
                        Text(tr("Kirim coin ke user"), color = Color(0xFF12324A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = idTujuan,
                            onValueChange = { idTujuan = it.filter { c -> c.isLetterOrDigit() } },
                            label = { Text(tr("User ID tujuan")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = jumlah,
                            onValueChange = { jumlah = it.filter { c -> c.isDigit() } },
                            label = { Text(tr("Jumlah coin")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (kirim || idTujuan.isBlank() || jumlah.isBlank()) Color(0xFF9FB4C2)
                                    else Color(0xFF1F7AA8),
                                )
                                .clickable(enabled = !kirim && idTujuan.isNotBlank() && jumlah.isNotBlank()) {
                                    scope.launch {
                                        kirim = true
                                        try {
                                            val uidTujuan = UserDirectory.uidFromId(idTujuan.trim())
                                            if (uidTujuan.isNullOrBlank()) {
                                                galat = "User ID tidak ditemukan"
                                            } else {
                                                val n = jumlah.toLongOrNull() ?: 0L
                                                val o = SellerRepo.send(uidTujuan, n)
                                                if (o.optInt("ok") == 1) {
                                                    galat = ""
                                                    jumlah = ""
                                                    idTujuan = ""
                                                    Toast.makeText(ctx, tr("Coin terkirim ke ") + idTujuan, Toast.LENGTH_SHORT).show()
                                                    info = SellerRepo.me()
                                                    riwayat = SellerRepo.hist()
                                                    onWalletChanged()
                                                } else {
                                                    galat = o.optString("e", "Gagal mengirim coin")
                                                }
                                            }
                                        } finally {
                                            kirim = false
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (kirim) "Mengirim..." else "Kirim",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (galat.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(galat, color = Color(0xFFB23B3B), fontSize = 12.sp)
                        }
                    }
                }
                item {
                    Text(tr("Riwayat kirim"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                if (riwayat.isEmpty()) {
                    item { Text(tr("Belum ada kiriman."), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) }
                }
                items(riwayat.size) { i ->
                    val (buyer, coins, at) = riwayat[i]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0x22FFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("Ke ") + buyer, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("+" + String.format("%,d", coins), color = Color(0xFF7FE3A1), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
