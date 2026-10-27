package com.example.googlelogin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jembatan aplikasi ke Supabase (dompet, gift, store).
 * Semua permintaan membawa token login Firebase; token diverifikasi
 * oleh Edge Function wallet-api sebelum menyentuh saldo. Saldo tidak
 * pernah bisa diubah langsung dari luar aplikasi.
 */
object SupaRepo {
    private const val BASE = "https://poeqhncfrxssilhjvibf.supabase.co"
    private const val FN = "$BASE/functions/v1/wallet-api"
    // Kunci publishable: memang boleh ada di dalam aplikasi
    // (setara kunci anon, tanpa akses data karena RLS menutup semua tabel).
    private const val KEY = "sb_publishable_l6orVwlNXTICFL4Cc1QMOw_vobFaSfL"

    class Res(val wallet: Wallet?, val error: String?, val needMigrate: Boolean = false)

    /** POST kecil ke edge function. Semua argumen dompet dikirim sebagai JSON. */
    private suspend fun call(action: String, params: org.json.JSONObject): Res =
        withContext(Dispatchers.IO) {
            try {
                val token = com.google.firebase.auth.FirebaseAuth.getInstance()
                    .currentUser?.getIdToken(true)?.await()?.token
                    ?: return@withContext Res(null, "Belum login")
                val body = org.json.JSONObject()
                    .put("a", action)
                    .put("t", token)
                    .put("p", params)
                val conn = (URL(FN).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 12000
                    doOutput = true
                    setRequestProperty("apikey", KEY)
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val o = if (txt.isNotBlank()) org.json.JSONObject(txt) else org.json.JSONObject()
                if (o.optInt("ok") == 1) {
                    Res(parseWallet(o.optJSONObject("w")), null)
                } else {
                    val err = o.optString("e", "")
                    val nf = err.contains("WALLET_NOT_FOUND")
                    Res(null, err.ifBlank { "Server sibuk, coba lagi" }, nf)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Res(null, "Tidak bisa menghubungi server dompet")
            }
        }

    fun parseWallet(o: org.json.JSONObject?): Wallet {
        if (o == null) return Wallet()
        val inv = HashMap<String, Long>()
        val invO = o.optJSONObject("inv")
        if (invO != null) for (k in invO.keys()) inv[k] = invO.optLong(k)
        val eq = HashMap<String, String>()
        val eqO = o.optJSONObject("equip")
        if (eqO != null) for (k in eqO.keys()) eq[k] = eqO.optString(k)
        return Wallet(
            coins = o.optLong("coins"),
            diamonds = o.optLong("diamonds"),
            exp = o.optLong("exp"),
            vip = o.optInt("vip"),
            vipUntil = o.optLong("vipUntil", o.optLong("vip_until")),
            lvClaimed = o.optLong("lvClaimed", o.optLong("lv_claimed")),
            inv = inv,
            equip = eq,
        )
    }

    private fun uidJson(uid: String) = org.json.JSONObject().put("uid", uid)

    suspend fun get(uid: String) = call("get", uidJson(uid))

    /** Pindahkan saldo lama dari Firestore ke Supabase (sekali seumur user, aman diulang). */
    suspend fun migrate(uid: String, w: Wallet): Res {
        val p = uidJson(uid)
            .put("coins", w.coins).put("diamonds", w.diamonds).put("exp", w.exp)
            .put("vip", w.vip).put("vipUntil", w.vipUntil).put("lvClaimed", w.lvClaimed)
            .put("inv", org.json.JSONObject(w.inv as Map<*, *>))
            .put("equip", org.json.JSONObject(w.equip as Map<*, *>))
        return call("migrate", p)
    }

    suspend fun topUp(uid: String, coins: Long) = call("topup", uidJson(uid).put("coins", coins))

    suspend fun sendGift(uid: String, price: Long, itemId: String? = null, roomId: String? = null): Res {
        val p = uidJson(uid).put("price", price)
        if (itemId != null) p.put("item_id", itemId)
        if (roomId != null) p.put("room_id", roomId)
        return call("send_gift", p)
    }

    suspend fun exchange(uid: String, diamonds: Long) = call("exchange", uidJson(uid).put("diamonds", diamonds))

    suspend fun buyVip(uid: String, tier: Int) = call("buy_vip", uidJson(uid).put("tier", tier))

    suspend fun buyItem(uid: String, item: StoreItem): Res {
        val p = uidJson(uid).put("item_id", item.id).put("price", item.price).put("days", item.days)
        return call("buy_item", p)
    }

    suspend fun equip(uid: String, slot: String, id: String): Res {
        val p = uidJson(uid).put("slot", slot).put("id", id)
        return call("equip", p)
    }

    suspend fun claimLevel(uid: String) = call("claim_level", uidJson(uid))

    suspend fun backfillVip(uid: String) = call("backfill", uidJson(uid))
}
