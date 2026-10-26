package com.example.googlelogin

import android.content.Context
import android.widget.Toast

/**
 * Pendeteksi kuota harian Firestore habis (RESOURCE_EXHAUSTED / quota exceeded).
 * Saat terjadi: tampilkan toast sekali per menit supaya user paham data kosong
 * karena limit, bukan error; data tetap diambil dari cache lokal.
 */
object QuotaGuard {
    @Volatile
    var appContext: Context? = null
        private set

    private const val MSG = "Kuota harian habis. Sebagian data tampil dari cache, coba lagi besok."

    fun init(ctx: Context) {
        appContext = ctx
    }

    /** Apakah error ini disebabkan kuota Firestore habis? */
    fun isExhausted(e: Throwable?): Boolean {
        var t = e
        repeat(6) {
            if (t == null) return false
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
