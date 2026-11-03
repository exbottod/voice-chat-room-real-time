package com.example.googlelogin

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
        val o = SupaRepo.panelCall("me")
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

    /** Minta reset nama: diterapkan HP penerima saat aplikasinya dibuka. */
    suspend fun mintaResetNama(target: String): String? {
        return try {
            Rtdb.ref("nameReset/$target").setValue(
                hashMapOf<String, Any?>(
                    "at" to System.currentTimeMillis(),
                    "by" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""),
                ),
            ).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gagal mengirim permintaan"
        }
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
        try {
            val ctx = com.google.firebase.FirebaseApp.getInstance().applicationContext
            val email = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: return
            val now = ProfileRepo.loadLocal(ctx, email)
            // Nama bawaan selalu "User <ID resmi>". Sebelumnya ID bisa tidak terbaca
            // sehingga namanya berakhir kosong dan tampil sebagai "unknown".
            val idResmi = UserDirectory.officialIdOf(uid)
                ?: UserDirectory.cachedId(ctx, uid)
                ?: uid.takeLast(4)
            val bawaan = "User " + idResmi
            // Foto ikut direset ke ikon bawaan sesuai jenis kelamin akun.
            val ikon = ResetAvatar.b64(ctx, now.gender) ?: now.avatarB64
            val baru = now.copy(nickname = bawaan, avatarB64 = ikon)
            ProfileRepo.saveLocal(ctx, email, baru)
            kotlinx.coroutines.withTimeoutOrNull(15000L) { ProfileRepo.saveRemote(baru) }
            try {
                Rtdb.ref("pub/$uid").updateChildren(
                    hashMapOf<String, Any?>(
                        "name" to bawaan,
                        "av" to (ikon ?: ""),
                        "id" to idResmi,
                    ),
                ).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { }
            try {
                PublicSync.push(uid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { }
            Rtdb.ref("nameReset/$uid").removeValue().await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
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

    fun jalankan(action: String, params: org.json.JSONObject) {
        if (sibuk) return
        sibuk = true
        pesan = "Memproses..."
        scope.launch {
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
                Text("Tutup", color = PanelMuted, fontSize = 14.sp, modifier = Modifier.clickable { onClose() })
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
                    Text(
                        "Sisa stok coin Anda: ${fmtNum(sisaCoin)}",
                        color = if (sisaCoin > 0L) PanelGold else Color(0xFFFF9E9E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Coin diambil dari stok yang dikirim developer. Kalau habis, kirim coin terkunci.",
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
                        } else {
                            sibuk = true
                            pesan = "Memproses..."
                            scope.launch {
                                pesan = try {
                                    val hasil = PanelStaffRepo.aksi(
                                        "send_coin",
                                        org.json.JSONObject().put("to", idUser).put("coins", n),
                                        info.role,
                                    )
                                    sisaCoin = PanelStaffRepo.sisaCoin(info.role)
                                    hasil
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
                    Text("Belum ada item. Developer mengirim item ke panel Anda.", color = PanelMuted, fontSize = 12.sp)
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
                                Text(
                                    " " + s.name.ifBlank { s.id } + " ",
                                    color = PanelInk,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${s.days} hari", color = PanelGold, fontSize = 11.sp)
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
                    Text(
                        "Admin hanya bisa memblokir user biasa. Staf dan manager tidak bisa diblokir dari sini.",
                        color = PanelMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (info.isManager) {
                PanelSection("Reset nama user") {
                    Text(
                        "Nama dikembalikan ke nama bawaan. Berlaku saat pemilik akun membuka aplikasinya.",
                        color = PanelMuted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    PanelAction(
                        if (sibuk) "Memproses..." else "Reset nama",
                        enabled = !sibuk && idUser.isNotBlank(),
                    ) {
                        sibuk = true
                        scope.launch {
                            val err = PanelStaffRepo.mintaResetNama(idUser)
                            pesan = if (err == null) "Permintaan reset nama terkirim" else "Gagal: $err"
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
