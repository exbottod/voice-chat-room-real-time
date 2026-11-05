package com.rezochatvoice.exceo

import com.google.firebase.database.FirebaseDatabase

/**
 * Penanda nomor yang sudah punya akun, supaya login nomor terdaftar
 * langsung meminta password tanpa mengirim SMS lagi.
 * Disimpan di Realtime Database node `phoneDir/{nomor}` = uid.
 */
object NomorTerdaftar {

    private fun kunci(nomorE164: String) = nomorE164.filter { it.isDigit() }

    suspend fun cek(nomorE164: String): Boolean {
        return try {
            val snap = FirebaseDatabase.getInstance()
                .getReference("phoneDir").child(kunci(nomorE164)).get()
                .await()
            snap.exists() && !snap.value.toString().isNullOrBlank()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun daftarkan(nomorE164: String, uid: String) {
        try {
            FirebaseDatabase.getInstance()
                .getReference("phoneDir").child(kunci(nomorE164)).setValue(uid).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tidak fatal: hanya memengaruhi kenyamanan login berikutnya.
        }
    }
}
