package com.example.googlelogin

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebStorage
import coil.imageLoader
import java.io.File

/**
 * Pembersih "sampah" aplikasi.
 *
 * Yang dihapus: cache gambar (Coil), folder cache aplikasi, cache web/WebView,
 * dan file sementara. Data akun, coin, gift, room, dan chat TIDAK ikut terhapus
 * oleh bagian ini (chat lama dipangkas terpisah oleh [ChatBersih]).
 *
 * Setelah bersih aplikasi langsung dimuat ulang supaya terasa baru dibuka.
 */
object CacheBersih {

    /** Daftar folder yang isinya boleh dibuang. */
    private fun folderSampah(ctx: Context): List<File> = listOfNotNull(
        ctx.cacheDir,
        ctx.externalCacheDir,
        ctx.codeCacheDir,
        File(ctx.filesDir, "tmp"),
        File(ctx.filesDir, "cache"),
        File(ctx.filesDir, "image_cache"),
    )

    private fun ukuranFolder(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        return try {
            if (f.isFile) f.length()
            else (f.listFiles() ?: emptyArray()).sumOf { ukuranFolder(it) }
        } catch (e: Exception) {
            0L
        }
    }

    /** Ukuran sampah sekarang, dipakai untuk label di Pengaturan. */
    fun ukuran(ctx: Context): Long = folderSampah(ctx).sumOf { ukuranFolder(it) }

    /** Hapus semua sampah. Return perkiraan byte yang dibebaskan. */
    fun bersihkan(ctx: Context): Long {
        val sebelum = ukuran(ctx)
        // Cache gambar di memori & disk milik Coil.
        try {
            val loader = ctx.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        } catch (e: Exception) {
        }
        // Cache web (WebView game dan halaman internal).
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
        }
        // Buku besar cache aplikasi.
        folderSampah(ctx).forEach { folder ->
            try {
                folder.listFiles()?.forEach { isi -> isi.deleteRecursively() }
            } catch (e: Exception) {
            }
        }
        val sesudah = ukuran(ctx)
        return (sebelum - sesudah).coerceAtLeast(0L)
    }

    /** Label ramah: 0 B, 24 KB, 7,8 MB, 1,2 GB. */
    fun format(byte: Long): String {
        if (byte < 1024) return "$byte B"
        val kb = byte / 1024.0
        if (kb < 1024) return "${angka(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${angka(mb)} MB"
        return "${angka(mb / 1024.0)} GB"
    }

    private fun angka(v: Double): String {
        val bulat = Math.round(v * 10.0) / 10.0
        return if (bulat == Math.floor(bulat)) bulat.toInt().toString()
        else bulat.toString().replace('.', ',')
    }

    /**
     * Muat ulang aplikasi dari nol (bukan sekadar pindah tab).
     * Toast diberi waktu tampil dulu, baru proses lama ditutup.
     */
    fun muatUlang(ctx: Context) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val i = Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                ctx.startActivity(i)
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: Exception) {
            }
        }, 800L)
    }
}
