package com.example.googlelogin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Foto untuk pesan pribadi: dikecilkan dulu di HP, lalu diunggah ke Cloudinary
 * lewat jalur upload yang sama dengan foto dan gift. Ukuran dikecilkan supaya
 * pengiriman cepat dan kuota Cloudinary tidak cepat habis.
 */
object ChatFoto {
    private const val SISI_MAKS = 1080
    private const val MUTU = 82

    suspend fun unggah(context: Context, uri: Uri): Pair<String?, String?> {
        val kecil = try {
            perkecil(context, uri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null to "Foto tidak bisa dibaca"

        val tmp = try {
            val dir = File(context.cacheDir, "chatfoto").apply { mkdirs() }
            val f = File(dir, "chat_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { out -> kecil.compress(Bitmap.CompressFormat.JPEG, MUTU, out) }
            f
        } catch (e: Exception) {
            return null to "Foto gagal disiapkan"
        }

        // File sementara dibaca lewat ContentResolver dengan skema file://,
        // jadi tidak perlu FileProvider tambahan.
        val pakai: Uri = Uri.fromFile(tmp)
        val (url, err) = GiftFx.uploadMedia(context, pakai)
        try { tmp.delete() } catch (e: Exception) { }
        return url to err
    }

    /** Kecilkan sisi terpanjang ke SISI_MAKS tanpa merusak rasio. */
    private fun perkecil(context: Context, uri: Uri): Bitmap? {
        val opsi = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opsi) }
        if (opsi.outWidth <= 0 || opsi.outHeight <= 0) return null
        var skala = 1
        while (opsi.outWidth / skala > SISI_MAKS || opsi.outHeight / skala > SISI_MAKS) skala *= 2
        val opsi2 = BitmapFactory.Options().apply { inSampleSize = skala }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opsi2)
        } ?: return null
        val panjang = maxOf(bmp.width, bmp.height)
        if (panjang <= SISI_MAKS) return bmp
        val f = SISI_MAKS.toFloat() / panjang
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * f).toInt().coerceAtLeast(1),
            (bmp.height * f).toInt().coerceAtLeast(1),
            true,
        )
    }
}
