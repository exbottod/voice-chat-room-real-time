package com.rezochatvoice.exceo

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Panel manager & panel admin di tab profil.
 *
 * Hak staf disimpan di server (Turso) dan diberikan lewat panel developer,
 * jadi panel hanya muncul untuk akun yang memang sudah diberi hak. Semua
 * aksi saldo dihitung di server, bukan di HP.
 */

data class PanelStok(val id: String, val kind: String, val name: String, val days: Long)

data class PanelInfo(
    val role: String = "",          // manager | admin
    val manager: String = "",
    val coins: Long = 0L,           // sisa stok coin dari developer
    val stok: List<PanelStok> = emptyList(),
) {
    val isManager: Boolean get() = role == "manager"
    val isAdmin: Boolean get() = role == "admin"
}

object PanelStaffRepo {
    private fun bacaPanel(o: org.json.JSONObject, stok: List<PanelStok>): PanelInfo {
        return PanelInfo(
            role = o.optString("role", ""),
            manager = o.optString("manager", ""),
            coins = o.optLong("coins", 0L),
            stok = stok,
        )
    }

    private fun bacaStok(o: org.json.JSONObject): List<PanelStok> {
        val arr = o.optJSONArray("stok") ?: return emptyList()
        val out = ArrayList<PanelStok>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(
                PanelStok(
                    id = it.optString("id", ""),
                    kind = it.optString("kind", ""),
                    name = it.optString("name", ""),
                    days = it.optLong("days", 7L),
                ),
            )
        }
        return out
    }

    /** Semua panel milik akun ini. Satu akun bisa punya manager dan admin sekaligus. */
    suspend fun panels(): List<PanelInfo> {
        // Hak panel dicek ke server. Kalau jalur pertama sibuk, coba lagi
        // sebentar supaya panel yang sudah dikirim tidak "hilang" dari layar.
        var o = SupaRepo.panelCall("me")
        var coba = 1
        while (o.optInt("ok") != 1 && coba < 3) {
            kotlinx.coroutines.delay(1200L)
            o = SupaRepo.panelCall("me")
            coba++
        }
        if (o.optInt("ok") != 1) return emptyList()
        val stok = bacaStok(o)
        val arr = o.optJSONArray("panels")
        if (arr != null && arr.length() > 0) {
            val out = ArrayList<PanelInfo>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                if (it.optString("role", "").isBlank()) continue
                out.add(bacaPanel(it, bacaStok(it).ifEmpty { stok }))
            }
            if (out.isNotEmpty()) return out
        }
        if (o.optString("role", "").isBlank()) return emptyList()
        return listOf(bacaPanel(o, stok))
    }

    /** Panel pertama (manager didahulukan server). */
    suspend fun me(): PanelInfo? = panels().firstOrNull()

    /** Sisa stok coin untuk panel manager. */
    suspend fun sisaCoin(role: String = "manager"): Long = try {
        panels().firstOrNull { it.role == role }?.coins ?: 0L
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        0L
    }

    /**
     * Panel memakai ID angka, sedangkan server dompet memakai UID Firebase.
     * Tanpa penerjemahan ini coin masuk ke dompet palsu bernama "100129"
     * sehingga penerima sebenarnya tidak menerima apa pun.
     */
    /** Nama penerima untuk konfirmasi, supaya tidak salah orang. */
    suspend fun namaTarget(uid: String): String = try {
        val snap = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("pub/$uid").get().await() }
        val v = snap?.value
        val nm = if (v is Map<*, *>) (v["name"] as? String) ?: "" else ""
        nm.takeIf { it.isNotBlank() } ?: "-"
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        "-"
    }

    suspend fun uidTarget(input: String): String {
        val n = input.trim()
        if (n.isBlank()) throw IllegalStateException("Isi ID user")
        if (!n.all { it.isDigit() }) return n
        val uid = UserDirectory.uidFromId(n) ?: throw IllegalStateException("ID $n tidak ditemukan")
        return uid
    }

    /** Pesan hasil aksi, siap ditampilkan. */
    suspend fun aksi(action: String, params: org.json.JSONObject, sebagai: String = ""): String {
        if (sebagai.isNotBlank()) params.put("sebagai", sebagai)
        val o = SupaRepo.panelCall(action, params)
        return if (o.optInt("ok") == 1) {
            when (action) {
                "send_coin" -> "Coin terkirim. Saldo penerima: ${fmtNum(o.optLong("coins", 0L))}"
                "send_vip" -> "VIP${o.optInt("vip", 0)} aktif ${o.optInt("days", 0)} hari untuk user itu"
                "send_item" -> "Item terkirim: ${o.optString("itemId", "")} (${o.optInt("days", 0)} hari)"
                "ban" -> "User diblokir"
                "unban" -> "Blokir dibuka"
                else -> "Berhasil"
            }
        } else {
            o.optString("e", "Gagal")
        }
    }

    /** Nama setelah direset dari panel manager. */
    const val NAMA_SETELAH_RESET = "Unknown"

    /**
     * Minta reset nama: nama akun dikembalikan ke "Unknown".
     * Penanda nameReset tetap ditulis supaya HP pemilik akun ikut membersihkan
     * simpanan lokalnya, tapi reset juga LANGSUNG diterapkan dari HP ini.
     */
    suspend fun mintaResetNama(target: String): String? {
        if (target.isBlank()) return "ID user kosong"
        var err: String? = null
        try {
            Rtdb.ref("nameReset/$target").setValue(
                hashMapOf<String, Any?>(
                    "at" to System.currentTimeMillis(),
                    "by" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: "Gagal mengirim permintaan"
        }
        val ok = terapkanResetNama(target)
        if (!ok && err == null) err = "Gagal menulis nama baru ke database"
        return err
    }

    /**
     * Tulis nama "Unknown" ke semua tempat nama dibaca: Realtime Database
     * (pub dan daftar ID) serta cermin Firestore. Dijalankan langsung dari HP
     * panel supaya tidak menunggu HP penerima membuka aplikasi.
     */
    suspend fun terapkanResetNama(uid: String): Boolean {
        if (uid.isBlank()) return false
        val nama = NAMA_SETELAH_RESET
        var ok = false
        try {
            Rtdb.ref("pub/$uid").updateChildren(
                hashMapOf<String, Any?>("name" to nama),
            ).await()
            ok = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Daftar ID dipakai layar pencarian; namanya ikut diperbarui.
        try {
            val id = UserDirectory.officialIdOf(uid)
            if (!id.isNullOrBlank()) {
                Rtdb.ref("ids/i/$id").updateChildren(hashMapOf<String, Any?>("name" to nama)).await()
                ok = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            kotlinx.coroutines.withTimeoutOrNull(8000L) {
                db.collection("users").document(uid).set(
                    hashMapOf<String, Any?>("nickname" to nama, "name" to nama),
                    com.google.firebase.firestore.SetOptions.merge(),
                ).await()
            }
            val id = UserDirectory.officialIdOf(uid)
            if (!id.isNullOrBlank()) {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    db.collection("userIds").document(id).set(
                        hashMapOf<String, Any?>("name" to nama),
                        com.google.firebase.firestore.SetOptions.merge(),
                    ).await()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Akun sendiri: simpanan lokal juga diganti.
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser?.uid == uid) {
                val email = auth.currentUser?.email ?: ""
                if (email.isNotBlank()) {
                    val ctx = com.google.firebase.FirebaseApp.getInstance().applicationContext
                    val now = ProfileRepo.loadLocal(ctx, email)
                    val baru = now.copy(nickname = nama)
                    ProfileRepo.saveLocal(ctx, email, baru)
                    kotlinx.coroutines.withTimeoutOrNull(12000L) { ProfileRepo.saveRemote(baru) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        try { UserCache.forget(uid) } catch (e: Exception) { }
        return ok
    }

    /**
     * Minta reset foto profil. Penanda ditulis untuk HP pemilik akun (supaya
     * simpanan lokalnya ikut dibersihkan), DAN reset langsung diterapkan dari
     * HP ini ke database. Dulu resetnya hanya menunggu HP penerima dibuka,
     * jadi kalau penerima tidak membuka aplikasi fotonya tidak berubah.
     */
    suspend fun mintaResetFoto(target: String): String? {
        if (target.isBlank()) return "ID user kosong"
        var err: String? = null
        try {
            Rtdb.ref("photoReset/$target").setValue(
                hashMapOf<String, Any?>(
                    "at" to System.currentTimeMillis(),
                    "by" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: "Gagal mengirim permintaan"
        }
        // Terapkan sekarang juga, tanpa menunggu HP penerima.
        val ok = terapkanResetFoto(target)
        if (!ok && err == null) err = "Gambar bawaan tidak terbaca di HP ini"
        return err
    }

    /**
     * Tulis ikon bawaan ke SEMUA tempat foto dibaca, supaya benar-benar
     * kembali ke ikon bawaan: Realtime Database (sumber tampilan utama),
     * cermin Firestore, dan hapus tautan foto lama.
     */
    suspend fun terapkanResetFoto(uid: String): Boolean {
        if (uid.isBlank()) return false
        val ctx = try {
            com.google.firebase.FirebaseApp.getInstance().applicationContext
        } catch (e: Exception) {
            return false
        }
        val gender = genderDari(uid)
        val ikon = ResetAvatar.b64(ctx, gender) ?: return false
        var ok = false
        // 1) Realtime Database: "av" = foto, "photo" dikosongkan supaya tautan
        //    foto lama tidak dipakai lagi oleh profil publik.
        try {
            Rtdb.ref("pub/$uid").updateChildren(
                hashMapOf<String, Any?>("av" to ikon, "photo" to ""),
            ).await()
            ok = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // 2) Cermin Firestore (kalau aturannya menolak, RTDB tetap jadi acuan).
        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            kotlinx.coroutines.withTimeoutOrNull(7000L) {
                db.collection("users").document(uid).set(
                    hashMapOf<String, Any?>("avatarB64" to ikon, "photoUrl" to null),
                    com.google.firebase.firestore.SetOptions.merge(),
                ).await()
            }
            val id = UserDirectory.officialIdOf(uid)
            if (!id.isNullOrBlank()) {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    db.collection("userIds").document(id).set(
                        hashMapOf<String, Any?>("avatarB64" to ikon, "photoUrl" to null),
                        com.google.firebase.firestore.SetOptions.merge(),
                    ).await()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // 3) Kalau ini akun sendiri: simpanan lokal ikut dibersihkan.
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser?.uid == uid) {
                val email = auth.currentUser?.email ?: ""
                if (email.isNotBlank()) {
                    val now = ProfileRepo.loadLocal(ctx, email)
                    val baru = now.copy(avatarB64 = ikon)
                    ProfileRepo.saveLocal(ctx, email, baru)
                    kotlinx.coroutines.withTimeoutOrNull(12000L) { ProfileRepo.saveRemote(baru) }
                }
                // Foto akun (Google/Firebase) juga dilepas, kalau tidak foto lama
                // bisa muncul lagi dari situ walau foto profil sudah direset.
                try {
                    auth.currentUser?.updateProfile(
                        com.google.firebase.auth.UserProfileChangeRequest.Builder()
                            .setPhotoUri(null)
                            .build(),
                    )
                } catch (e: Exception) { }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        try { UserCache.forget(uid) } catch (e: Exception) { }
        return ok
    }

    /** Jenis kelamin akun target: RTDB dulu, lalu Firestore, lalu lokal (akun sendiri). */
    private suspend fun genderDari(uid: String): String {
        try {
            val s = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("pub/$uid/gender").get().await() }
            val g = s?.value as? String
            if (!g.isNullOrBlank()) return g
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        try {
            val d = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
            }
            val g = d?.getString("gender")
            if (!g.isNullOrBlank()) return g
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        return ""
    }

    /**
     * Dijalankan HP penerima: foto profil dikembalikan ke ikon bawaan sesuai
     * jenis kelamin akun. Nama tidak diubah.
     */
    suspend fun applyPendingPhotoReset(uid: String) {
        if (uid.isBlank()) return
        val snap = try {
            kotlinx.coroutines.withTimeoutOrNull(6000L) { Rtdb.ref("photoReset/$uid").get().await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        if (!snap.exists()) return
        val ok = terapkanResetFoto(uid)
        // Penanda dihapus apa pun hasilnya, supaya tidak menumpuk dan tidak
        // memicu reset berulang setiap aplikasi dibuka.
        try { PublicSync.push(uid) } catch (e: Exception) { }
        try { Rtdb.ref("photoReset/$uid").removeValue().await() } catch (e: Exception) { }
        if (!ok) return
    }

    /**
     * Dijalankan HP penerima: kalau ada permintaan reset nama dari manager,
     * nama diubah kembali ke nama bawaan lalu penandanya dihapus.
     */
    suspend fun applyPendingNameReset(uid: String) {
        if (uid.isBlank()) return
        val snap = try {
            kotlinx.coroutines.withTimeoutOrNull(6000L) { Rtdb.ref("nameReset/$uid").get().await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        if (!snap.exists()) return
        terapkanResetNama(uid)
        try { PublicSync.push(uid) } catch (e: Exception) { }
        try { Rtdb.ref("nameReset/$uid").removeValue().await() } catch (e: Exception) { }
    }
}

private val PanelBg = Color(0xFF12161F)
private val PanelCard = Color(0xFF1C2330)
private val PanelGold = Color(0xFFFFD54F)
private val PanelInk = Color(0xFFE9EEF7)
private val PanelMuted = Color(0xFF8FA1BB)

@Composable
private fun PanelField(label: String, value: String, onChange: (String) -> Unit, angka: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = PanelMuted, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (angka) KeyboardType.Number else KeyboardType.Text,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PanelInk,
            unfocusedTextColor = PanelInk,
            focusedBorderColor = PanelGold,
            unfocusedBorderColor = Color(0xFF39435A),
            cursorColor = PanelGold,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun PanelSection(title: String, isi: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(PanelCard, RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF2C3648), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(title, color = PanelGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        isi()
    }
}

@Composable
private fun PanelAction(text: String, enabled: Boolean = true, warna: Color = PanelGold, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(if (enabled) warna else Color(0xFF38414F), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.Black else Color(0xFF8A8A8A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/**
 * Panel staf. Manager mendapat semua bagian; admin hanya VIP, frame/efek,
 * dan blokir user biasa (sesuai aturan "admin hanya di bawah naungan manager").
 */
@Composable
fun PanelStafDialog(info: PanelInfo, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sibuk by remember { mutableStateOf(false) }
    var pesan by remember { mutableStateOf("") }

    var idUser by remember { mutableStateOf("") }
    var coin by remember { mutableStateOf("") }
    var vipTier by remember { mutableStateOf("") }
    var vipDays by remember { mutableStateOf("30") }
    var alasan by remember { mutableStateOf("") }
    var itemPilih by remember { mutableStateOf<PanelStok?>(null) }
    var sisaCoin by remember { mutableStateOf(info.coins) }
    // Konfirmasi dua langkah: tampilkan nama penerima sebelum coin dikirim.
    var konfirmasi by remember { mutableStateOf("") }

    fun jalankan(action: String, params: org.json.JSONObject) {
        if (sibuk) return
        sibuk = true
        pesan = "Memproses..."
        scope.launch {
            try {
                if (params.has("to")) params.put("to", PanelStaffRepo.uidTarget(params.optString("to")))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                pesan = e.message ?: "ID user tidak ditemukan"
                sibuk = false
                return@launch
            }
            pesan = try {
                PanelStaffRepo.aksi(action, params, info.role)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Gagal"
            }
            sibuk = false
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(PanelBg)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (info.isManager) "\uD83E\uDDD1\u200D\uD83D\uDCBC Panel Manager" else "\uD83D\uDEE1 Panel Admin",
                    color = PanelGold,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(tr("Tutup"), color = PanelMuted, fontSize = 14.sp, modifier = Modifier.clickable { onClose() })
            }
            Text(
                if (info.isAdmin) "Hak admin: VIP, frame/efek, dan blokir user biasa."
                else "Hak manager: coin, VIP, frame/efek, blokir, reset nama.",
                color = PanelMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))

            PanelSection("ID user tujuan") {
                PanelField("ID user (angka)", idUser, { v -> idUser = v.filter { it.isDigit() } }, angka = true)
            }

            if (info.isManager) {
                PanelSection("Kirim coin") {
                    Text(tr("Sisa stok coin Anda: ${fmtNum(sisaCoin)}"),
                        color = if (sisaCoin > 0L) PanelGold else Color(0xFFFF9E9E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(tr("Coin diambil dari stok yang dikirim developer. Kalau habis, kirim coin terkunci."),
                        color = PanelMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    PanelField("Jumlah coin", coin, { v -> coin = v.filter { it.isDigit() } }, angka = true)
                    val n = coin.toLongOrNull() ?: 0L
                    PanelAction(
                        when {
                            sisaCoin <= 0L -> "Stok coin habis"
                            sibuk -> "Mengirim..."
                            else -> "Kirim coin"
                        },
                        enabled = !sibuk && sisaCoin > 0L,
                    ) {
                        if (idUser.isBlank() || n <= 0L) {
                            pesan = "Isi ID user dan jumlah coin"
                        } else if (n > sisaCoin) {
                            pesan = "Stok coin tidak cukup. Sisa ${fmtNum(sisaCoin)} coin."
                        } else if (konfirmasi != idUser.trim() + "|" + n) {
                            sibuk = true
                            pesan = "Mencari penerima..."
                            scope.launch {
                                val uidT = try {
                                    PanelStaffRepo.uidTarget(idUser)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    pesan = e.message ?: "ID user tidak ditemukan"
                                    sibuk = false
                                    return@launch
                                }
                                val nama = PanelStaffRepo.namaTarget(uidT)
                                val diri = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                                konfirmasi = idUser.trim() + "|" + n
                                pesan = "Penerima: " + nama + " (ID " + idUser.trim() + ")" +
                                    (if (uidT == diri) " - INI AKUN ANDA SENDIRI!" else "") +
                                    ". Tekan \"Kirim coin\" sekali lagi untuk mengirim " + fmtNum(n) + " coin."
                                sibuk = false
                            }
                        } else {
                            konfirmasi = ""
                            sibuk = true
                            pesan = "Memproses..."
                            scope.launch {
                                pesan = try {
                                    val hasil = PanelStaffRepo.aksi(
                                        "send_coin",
                                        org.json.JSONObject().put("to", PanelStaffRepo.uidTarget(idUser)).put("coins", n),
                                        info.role,
                                    )
                                    sisaCoin = PanelStaffRepo.sisaCoin(info.role)
                                    "Terkirim ke ID " + idUser.trim() + ". " + hasil
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    e.message ?: "Gagal"
                                }
                                sibuk = false
                            }
                        }
                    }
                }
            }

            PanelSection("Kirim VIP 1-9") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        PanelField("VIP 1-9", vipTier, { v -> vipTier = v.filter { it.isDigit() } }, angka = true)
                    }
                    Box(Modifier.weight(1f)) {
                        PanelField("Hari", vipDays, { v -> vipDays = v.filter { it.isDigit() } }, angka = true)
                    }
                }
                PanelAction(if (sibuk) "Mengirim..." else "Kirim VIP", enabled = !sibuk) {
                    val t = vipTier.toIntOrNull() ?: 0
                    val h = vipDays.toIntOrNull() ?: 0
                    if (idUser.isBlank() || t !in 1..9 || h <= 0) {
                        pesan = "Isi ID user, VIP 1-9, dan durasi hari"
                    } else {
                        jalankan("send_vip", org.json.JSONObject().put("to", idUser).put("tier", t).put("days", h))
                    }
                }
            }

            PanelSection("Kirim frame dan efek masuk") {
                if (info.stok.isEmpty()) {
                    Text(tr("Belum ada item. Developer mengirim item ke panel Anda."), color = PanelMuted, fontSize = 12.sp)
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                        info.stok.forEach { s ->
                            val on = itemPilih?.id == s.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(if (on) Color(0xFF2B3648) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { itemPilih = s }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (s.kind == "frame") "\u2B55" else "\u2728", fontSize = 14.sp)
                                Text(tr(" ") + s.name.ifBlank { s.id } + " ",
                                    color = PanelInk,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(tr("${s.days} hari"), color = PanelGold, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                PanelAction(
                    if (sibuk) "Mengirim..." else "Kirim item terpilih",
                    enabled = !sibuk && itemPilih != null,
                ) {
                    val pilih = itemPilih
                    if (idUser.isBlank() || pilih == null) {
                        pesan = "Isi ID user dan pilih item"
                    } else {
                        jalankan(
                            "send_item",
                            org.json.JSONObject().put("to", idUser).put("itemId", pilih.id).put("days", pilih.days),
                        )
                    }
                }
            }

            PanelSection(if (info.isManager) "Blokir user" else "Blokir user biasa") {
                PanelField("Alasan (opsional)", alasan, { alasan = it })
                PanelAction(if (sibuk) "Memproses..." else "Blokir user", enabled = !sibuk, warna = Color(0xFFFF7A6B)) {
                    if (idUser.isBlank()) pesan = "Isi ID user"
                    else jalankan("ban", org.json.JSONObject().put("to", idUser).put("reason", alasan))
                }
                Spacer(Modifier.height(6.dp))
                PanelAction("Buka blokir user ini", enabled = !sibuk, warna = Color(0xFF8FD7A8)) {
                    if (idUser.isBlank()) pesan = "Isi ID user"
                    else jalankan("unban", org.json.JSONObject().put("to", idUser))
                }
                if (info.isAdmin) {
                    Text(tr("Admin hanya bisa memblokir user biasa. Staf dan manager tidak bisa diblokir dari sini."),
                        color = PanelMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (info.isManager) {
                PanelSection("Reset nama user") {
                    Text(tr("Nama akun langsung diganti menjadi \"Unknown\" (berlaku sekarang, tanpa menunggu HP pemilik akun)."),
                        color = PanelMuted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    PanelAction(
                        if (sibuk) "Memproses..." else "Reset nama",
                        enabled = !sibuk && idUser.isNotBlank(),
                    ) {
                        sibuk = true
                        pesan = "Memproses..."
                        scope.launch {
                            // Antrean reset disimpan per UID; kirim pakai UID asli agar
                            // HP penerima benar-benar menerima perintahnya.
                            val err = try {
                                PanelStaffRepo.mintaResetNama(PanelStaffRepo.uidTarget(idUser))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.message ?: "ID user tidak ditemukan"
                            }
                            pesan = if (err == null) "Nama diganti menjadi Unknown" else "Gagal: $err"
                            sibuk = false
                        }
                    }
                }

                PanelSection("Reset foto profil user") {
                    Text(tr("Foto kembali ke ikon bawaan pria atau wanita sesuai akunnya. Nama tidak diubah."),
                        color = PanelMuted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    PanelAction(
                        if (sibuk) "Memproses..." else "Reset foto profil",
                        enabled = !sibuk && idUser.isNotBlank(),
                    ) {
                        sibuk = true
                        pesan = "Memproses..."
                        scope.launch {
                            val err = try {
                                PanelStaffRepo.mintaResetFoto(PanelStaffRepo.uidTarget(idUser))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.message ?: "ID user tidak ditemukan"
                            }
                            pesan = if (err == null) "Foto dikembalikan ke ikon bawaan" else "Gagal: $err"
                            sibuk = false
                        }
                    }
                }
            }

            if (pesan.isNotBlank()) {
                val jelek = pesan.contains("Gagal", true) || pesan.contains("tidak", true)
                Text(
                    pesan,
                    color = if (jelek) Color(0xFFFF9E9E) else Color(0xFF8FD7A8),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
