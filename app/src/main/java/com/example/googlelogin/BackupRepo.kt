package com.example.googlelogin

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DATABASE CADANGAN untuk transaksi wallet.
 *
 * Saat kuota harian Firestore habis (baca/tulis), transaksi ekonomi app
 * (beli SVIP, beli item store, kirim gift, tukar diamond, topup, klaim level)
 * otomatis diproses lewat server cadangan: Cloudflare Worker + KV.
 *
 * - Auth memakai tiket login Firebase yang sama (worker memverifikasi tanda tangan).
 * - Data wallet cadangan otomatis tersinkron kembali ke Firestore
 *   saat kuota Firestore sudah pulih (lihat WalletRepo.syncBackup).
 * - Kalau server cadangan belum pernah dipakai (ver == 0), wallet dasar
 *   diambil dari cache lokal terakhir (snapshot Firestore sebelum kuota habis).
 */
object BackupRepo {

    // Worker yang sama dengan server token Agora / upload asset.
    const val BASE = "https://rezo-agora-token.bangexx01.workers.dev"

    data class BkWallet(
        val coins: Long = 0L,
        val diamonds: Long = 0L,
        val exp: Long = 0L,
        val vip: Int = 0,
        val vipUntil: Long = 0L,
        val lvClaimed: Long = 0L,
        val inv: Map<String, Long> = emptyMap(),
        val equip: Map<String, String> = emptyMap(),
        val updatedAt: Long = 0L,   // waktu transaksi cadangan terakhir
        val bkSyncedAt: Long = 0L,  // waktu terakhir data cadangan dipindah ke Firestore
        val ver: Long = 0L,
    ) {
        fun toWallet() = Wallet(coins, diamonds, exp, vip, vipUntil, inv, equip, lvClaimed)
    }

    fun parse(o: JSONObject): BkWallet {
        val inv = o.optJSONObject("inv")
            ?.let { j -> j.keys().asSequence().associateWith { j.optLong(it) } }
            ?: emptyMap()
        val eq = o.optJSONObject("equip")
            ?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } }
            ?: emptyMap()
        return BkWallet(
            coins = o.optLong("coins"),
            diamonds = o.optLong("diamonds"),
            exp = o.optLong("exp"),
            vip = o.optInt("vip"),
            vipUntil = o.optLong("vipUntil"),
            lvClaimed = o.optLong("lvClaimed"),
            inv = inv,
            equip = eq,
            updatedAt = o.optLong("updatedAt"),
            bkSyncedAt = o.optLong("bkSyncedAt"),
            ver = o.optLong("ver"),
        )
    }

    /** Wallet dasar untuk transaksi cadangan:
     *  server cadangan kalau sudah pernah dipakai, kalau belum pakai cache lokal. */
    suspend fun base(uid: String): BkWallet? {
        val bk = get()
        if (bk != null && bk.ver > 0L) return bk
        val cached = WalletCache.load(uid) ?: Wallet()
        return BkWallet(
            coins = cached.coins, diamonds = cached.diamonds, exp = cached.exp,
            vip = cached.vip, vipUntil = cached.vipUntil, lvClaimed = cached.lvClaimed,
            inv = cached.inv, equip = cached.equip,
        )
    }

    /** Baca wallet cadangan milik user yang sedang login. null = gagal / offline. */
    suspend fun get(): BkWallet? = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext null
        call("GET", null, t)?.let { parse(it) }
    }

    /** Kirim patch wallet (field yang diubah) ke server cadangan.
     *  Mengembalikan wallet cadangan TERBARU, atau null kalau gagal. */
    suspend fun put(patch: JSONObject): BkWallet? = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext null
        call("POST", JSONObject().put("patch", patch), t)?.let { parse(it) }
    }

    /** Tambah nilai angka (coins/diamonds/exp/lvClaimed) di server cadangan. */
    suspend fun add(fields: JSONObject): BkWallet? = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext null
        call("POST", JSONObject().put("op", "add").put("add", fields), t)?.let { parse(it) }
    }

    private suspend fun token(): String? = try {
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()?.token
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun call(method: String, body: JSONObject?, token: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$BASE/w").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = body != null
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { r -> r.readText() }
            if (code !in 200..299 || txt.isNullOrBlank()) null else JSONObject(txt)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
