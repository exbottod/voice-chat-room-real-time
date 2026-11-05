package com.example.googlelogin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Foto dan latar room sengaja TIDAK ikut di daftar room Realtime Database.
 *
 * Dulu base64 foto ikut ditulis ke roomsPub. Akibatnya setiap HP mengunduh
 * gambar semua room setiap kali daftar room berubah, dan kuota unduhan
 * database meledak (ratusan GB per bulan). Sekarang yang ada di roomsPub hanya
 * teks ringan, dan gambar diambil sekali per room dari Firestore lalu disimpan
 * di memori selama aplikasi berjalan.
 */
object RoomImg {
    val foto = mutableStateMapOf<String, String?>()
    val latar = mutableStateMapOf<String, String?>()

    private val sudah = HashSet<String>()
    private val sedang = HashSet<String>()

    /** Ambil gambar room sekali saja. Panggilan berikutnya memakai memori. */
    suspend fun muat(id: String, paksa: Boolean = false) {
        if (id.isBlank()) return
        if (!paksa && (sudah.contains(id) || sedang.contains(id))) return
        sedang.add(id)
        var fotoAda = false
        try {
            // Sumber utama: roomsBg (hanya satu room, bukan seluruh daftar).
            val s = Rtdb.ref("roomsBg/$id").get().await()
            val p = s.child("photo").getValue(String::class.java)
            val b = s.child("bg").getValue(String::class.java)
            if (!p.isNullOrBlank() || !b.isNullOrBlank()) {
                foto[id] = p
                latar[id] = b
                fotoAda = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        try {
            // Cadangan: dokumen room di Firestore (room lama).
            val perluFoto = !fotoAda || foto[id].isNullOrBlank()
            val perluBg = !fotoAda || latar[id].isNullOrBlank()
            if (perluFoto || perluBg) {
                val d = FirebaseFirestore.getInstance().collection("rooms").document(id).get().await()
                if (perluFoto) foto[id] = d.getString("photoB64")
                if (perluBg) latar[id] = d.getString("backgroundB64")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!sudah.contains(id)) {
                if (!fotoAda) foto[id] = null
                if (!fotoAda) latar[id] = null
            }
        } finally {
            sedang.remove(id)
            sudah.add(id)
        }
    }

    fun simpan(id: String, photo: String?, background: String?) {
        if (id.isBlank()) return
        if (photo != null) foto[id] = photo
        if (background != null) latar[id] = background
        sudah.add(id)
    }

    fun lupakan(id: String) {
        foto.remove(id)
        latar.remove(id)
        sudah.remove(id)
    }
}

@Composable
fun rememberRoomPhoto(r: RoomInfo): ImageBitmap? {
    LaunchedEffect(r.id) { RoomImg.muat(r.id) }
    val b64 = RoomImg.foto[r.id] ?: r.photoB64
    return remember(b64) { ProfileRepo.decodeB64(b64) }
}

@Composable
fun rememberRoomBg(r: RoomInfo): ImageBitmap? {
    LaunchedEffect(r.id) { RoomImg.muat(r.id) }
    val b64 = RoomImg.latar[r.id] ?: r.backgroundB64
    return remember(b64) { ProfileRepo.decodeB64(b64) }
}
