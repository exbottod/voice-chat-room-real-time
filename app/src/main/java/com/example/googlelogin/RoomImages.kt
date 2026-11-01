package com.example.googlelogin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kirim gambar di chat room.
 *
 * Gambar dikecilkan dulu di HP (maks 1080 px, JPEG), lalu diunggah ke
 * Supabase Storage pada bucket publik "chatimg". Tidak perlu server
 * tambahan dan tidak memakai kuota Firestore.
 *
 * Bucket dan izinnya dibuat lewat supabase/room-image.sql.
 */
object RoomImages {
    private const val BASE = "https://poeqhncfrxssilhjvibf.supabase.co"
    // Kunci publishable, sama seperti yang dipakai SupaRepo (aman ada di app).
    private const val KEY = "sb_publishable_l6orVwlNXTICFL4Cc1QMOw_vobFaSfL"
    private const val BUCKET = "chatimg"
    private const val MAX_DIM = 1080
    private const val MAX_BYTES = 2_000_000

    /** Hasil: (url publik, pesan galat). Salah satu selalu null. */
    suspend fun upload(ctx: android.content.Context, uri: android.net.Uri): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            try {
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@withContext null to "Belum login"
                val raw = try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                } ?: return@withContext null to "Gambar tidak bisa dibaca"

                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    ?: return@withContext null to "Format gambar tidak didukung"

                val biggest = maxOf(bmp.width, bmp.height)
                val kecil = if (biggest > MAX_DIM) {
                    val s = MAX_DIM.toFloat() / biggest
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * s).toInt().coerceAtLeast(1),
                        (bmp.height * s).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    bmp
                }

                var bytes = jpeg(kecil, 80)
                if (bytes.size > MAX_BYTES) bytes = jpeg(kecil, 60)
                if (bytes.size > MAX_BYTES) return@withContext null to "Gambar masih terlalu besar, coba gambar lain"

                val path = "$uid/${System.currentTimeMillis()}.jpg"
                val conn = (URL("$BASE/storage/v1/object/$BUCKET/$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("apikey", KEY)
                    setRequestProperty("Authorization", "Bearer $KEY")
                    setRequestProperty("Content-Type", "image/jpeg")
                }
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    return@withContext null to "Upload gagal ($code) ${body.take(100)}"
                }
                "$BASE/storage/v1/object/public/$BUCKET/$path" to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to (e.message ?: "Upload gagal")
            }
        }

    private fun jpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
