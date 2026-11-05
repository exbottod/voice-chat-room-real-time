package com.rezochatvoice.exceo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kode masuk lewat WhatsApp, bukan SMS.
 *
 * Server VPS Helipod yang membuat 6 angka dan mengirimnya lewat gateway
 * WhatsApp, jadi pendaftaran nomor tidak memakan kuota SMS Firebase.
 * Setelah kode benar, aplikasi membuat akun email+password tersembunyi
 * (nomor@phone.rezostar.app) sehingga login berikutnya cukup nomor+password.
 */
object OtpWhatsApp {

    private const val VPS = "https://43-156-99-4.sslip.io"
    const val ID_WA = "wa"

    /** Minta kode. null = berhasil, selain itu pesan galat untuk ditampilkan. */
    suspend fun minta(nomorE164: String): String? {
        return try {
            val j = pos("/otp/minta", JSONObject().put("nomor", nomorE164))
            if (j.optInt("ok") == 1) null else j.optString("e").ifBlank { "Gagal mengirim kode" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Tidak bisa menghubungi server kode: ${e.message ?: "jaringan bermasalah"}"
        }
    }

    /** Periksa kode. null = benar. */
    suspend fun cek(nomorE164: String, kode: String): String? {
        return try {
            val j = pos("/otp/cek", JSONObject().put("nomor", nomorE164).put("kode", kode))
            if (j.optInt("ok") == 1) null else j.optString("e").ifBlank { "Kode salah" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Tidak bisa memeriksa kode: ${e.message ?: "jaringan bermasalah"}"
        }
    }

    private suspend fun pos(jalur: String, isi: JSONObject): JSONObject =
        withTimeout(25_000) {
            withContext(Dispatchers.IO) {
                val c = (URL(VPS + jalur).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    c.outputStream.use { it.write(isi.toString().toByteArray()) }
                    val kode = c.responseCode
                    val teks = (if (kode in 200..299) c.inputStream else c.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    JSONObject(teks.ifBlank { "{}" })
                } finally {
                    c.disconnect()
                }
            }
        }
}
