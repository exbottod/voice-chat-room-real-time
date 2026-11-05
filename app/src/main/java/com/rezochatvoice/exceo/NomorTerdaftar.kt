package com.rezochatvoice.exceo

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Penanda nomor yang sudah punya akun, supaya login nomor terdaftar
 * langsung meminta password tanpa mengirim SMS lagi.
 * Disimpan di Realtime Database node `phoneDir/{nomor}` = uid.
 */
object NomorTerdaftar {

    private fun kunci(nomorE164: String) = nomorE164.filter { it.isDigit() }

    /** true bila nomor sudah punya akun. Gagal baca = dianggap belum terdaftar. */
    suspend fun cek(nomorE164: String): Boolean {
        return try {
            suspendCancellableCoroutine { lanjut ->
                FirebaseDatabase.getInstance()
                    .getReference("phoneDir").child(kunci(nomorE164))
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snap: DataSnapshot) {
                            val ada = snap.exists() && !snap.value.toString().isNullOrBlank()
                            if (lanjut.isActive) lanjut.resume(ada)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            if (lanjut.isActive) lanjut.resume(false)
                        }
                    })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Catat nomor setelah akun dibuat. Tidak fatal bila gagal. */
    suspend fun daftarkan(nomorE164: String, uid: String) {
        try {
            suspendCancellableCoroutine { lanjut ->
                FirebaseDatabase.getInstance()
                    .getReference("phoneDir").child(kunci(nomorE164))
                    .setValue(uid) { _, _ -> if (lanjut.isActive) lanjut.resume(Unit) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Hanya memengaruhi kenyamanan login berikutnya.
        }
    }
}
