package com.example.googlelogin

import android.content.Context
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Pendeteksi kuota harian Firestore habis (RESOURCE_EXHAUSTED / quota exceeded).
 * Saat terjadi: tampilkan toast sekali per menit supaya user paham data kosong
 * karena limit, bukan error; data tetap diambil dari cache lokal.
 */
object QuotaGuard {
    @Volatile
    var appContext: Context? = null
        private set

    private const val MSG = "Kuota Firestore habis. Transaksi store diproses lewat server cadangan."

    fun init(ctx: Context) {
        appContext = ctx
    }

    /** Apakah error ini disebabkan kuota Firestore habis? */
    fun isExhausted(e: Throwable?): Boolean {
        var t = e
        repeat(6) {
            if (t == null) return false
            // Deteksi paling akurat: kode error resmi Firestore.
            if (t is FirebaseFirestoreException &&
                t.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
            ) return true
            val m = t.message ?: t.toString()
            if (m.contains("RESOURCE_EXHAUSTED", true) ||
                m.contains("quota exceeded", true) ||
                m.contains("exceeded your current quota", true) ||
                m.contains("billing", true) && m.contains("maximum", true)
            ) return true
            t = t.cause
        }
        return false
    }

    // Tanda kuota sedang habis. Selama tanda ini aktif, transaksi store/wallet
    // LANGSUNG diproses di server cadangan tanpa mencoba Firestore dulu
    // (percobaan Firestore saat kuota habis lama ~30 detik karena diulang berkali-kali).
    // Tanda hangus sendiri setelah 30 menit / saat Firestore kembali sehat.
    @Volatile
    private var downUntil = 0L

    /** Tandai kuota habis (sekaligus tampilkan toast, maks 1x/menit). */
    fun markDown() {
        downUntil = System.currentTimeMillis() + 30 * 60_000L
        notifyOnce()
    }

    /** Apakah kuota sedang habis? Transaksi sebaiknya langsung ke server cadangan. */
    fun isDown(): Boolean = downUntil > System.currentTimeMillis()

    /** Firestore sehat lagi: transaksi kembali normal ke Firestore. */
    fun markUp() {
        downUntil = 0L
    }

    /** Toast "kuota habis" sekali per 60 detik, biar tidak spam. */
    fun notifyOnce() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastToast < 60_000L) return
        lastToast = now
        Toast.makeText(ctx, MSG, Toast.LENGTH_LONG).show()
    }

    /** Cek error; kalau kuota habis, beri tahu user sekali. */
    fun handle(e: Throwable?) {
        if (e != null && isExhausted(e)) notifyOnce()
    }

    private var lastToast = 0L
}
