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
        kotlinx.coroutines.withTimeoutOrNull(28_000L) {
            callInner(action, params)
        } ?: Res(null, "Server dompet lambat (timeout), coba lagi")

    private suspend fun callInner(action: String, params: org.json.JSONObject): Res =
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
                    connectTimeout = 12_000
                    // Edge function kadang perlu bangun dulu (cold start) sebelum
                    // menjawab. Waktu baca diperpanjang supaya tidak dianggap mati.
                    readTimeout = 22_000
                    doOutput = true
                    setRequestProperty("apikey", KEY)
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299 && txt.isBlank()) {
                    return@withContext Res(null, "Server dompet error $code")
                }
                val o = try {
                    if (txt.isNotBlank()) org.json.JSONObject(txt) else org.json.JSONObject()
                } catch (e: Exception) {
                    return@withContext Res(
                        null,
                        if (code in 200..299) "Jawaban server dompet tidak terbaca"
                        else "Server dompet error $code",
                    )
                }
                if (o.optInt("ok") == 1) {
                    Res(parseWallet(o.optJSONObject("w")), null)
                } else {
                    val err = o.optString("e", "")
                    val nf = err.contains("WALLET_NOT_FOUND")
                    Res(null, err.ifBlank { "Server sibuk, coba lagi" }, nf)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                Res(null, "Server dompet lambat (timeout), coba lagi")
            } catch (e: java.net.UnknownHostException) {
                Res(null, "Internet bermasalah, cek koneksi")
            } catch (e: javax.net.ssl.SSLException) {
                Res(null, "Koneksi ke server dompet gagal, coba lagi")
            } catch (e: Exception) {
                Res(null, "Tidak bisa menghubungi server dompet")
            }
        }

    /**
     * Cek jalur coin untuk game: panggil endpoint yang sama dengan yang dipakai
     * WaveGames (userInfo). Hasilnya dipakai untuk menampilkan pesan jelas kalau
     * server coin belum siap, bukan layar game kosong.
     */
    suspend fun waveCheck(): String? = withContext(Dispatchers.IO) {
        try {
            val token = com.google.firebase.auth.FirebaseAuth.getInstance()
                .currentUser?.getIdToken(true)?.await()?.token ?: return@withContext "Belum login"
            val conn = (URL("$FN/wave/game/userInfo").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("apikey", KEY)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val o = try { org.json.JSONObject(txt) } catch (e: Exception) { org.json.JSONObject() }
            if (code in 200..299 && o.optBoolean("success")) {
                val d = o.optJSONObject("data")
                val bal = d?.optLong("balance", -1L) ?: -1L
                if (bal < 0) "Data saldo game tidak lengkap (userInfo tanpa balance)" else null
            } else {
                "Server coin game menolak (" + code + "): " + o.optString("message", txt.take(120))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Tidak bisa menghubungi server coin game: " + (e.message ?: "koneksi gagal")
        }
    }


    /**
     * Potong (kind 1) atau tambah (kind 2) coin dari dalam game.
     * Endpoint sama dengan yang dipakai WaveGames, jadi aturan saldo tetap di server.
     */
    suspend fun waveFlow(amount: Long, kind: Int, tx: String, game: String): Long? =
        withContext(Dispatchers.IO) {
            try {
                val token = com.google.firebase.auth.FirebaseAuth.getInstance()
                    .currentUser?.getIdToken(true)?.await()?.token ?: return@withContext null
                val body = org.json.JSONObject()
                    .put("betAmount", amount)
                    .put("type", kind)
                    .put("transactionId", tx)
                    .put("gameName", game)
                    .put("token", token)
                val conn = (URL("$FN/wave/game/submitFlow").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 22_000
                    doOutput = true
                    setRequestProperty("apikey", KEY)
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val o = try { org.json.JSONObject(txt) } catch (e: Exception) { org.json.JSONObject() }
                if (o.optBoolean("success")) o.optJSONObject("data")?.optLong("balance") else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** Sama seperti waveFlow, tapi mengembalikan pesan server supaya bisa ditampilkan. */
    suspend fun waveFlowMsg(amount: Long, kind: Int, tx: String, game: String): Pair<Long?, String> =
        withContext(Dispatchers.IO) {
            try {
                val token = com.google.firebase.auth.FirebaseAuth.getInstance()
                    .currentUser?.getIdToken(true)?.await()?.token
                    ?: return@withContext Pair(null, "Belum login, buka ulang aplikasi")
                val body = org.json.JSONObject()
                    .put("betAmount", amount)
                    .put("type", kind)
                    .put("transactionId", tx)
                    .put("gameName", game)
                    .put("token", token)
                val conn = (URL("$FN/wave/game/submitFlow").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("apikey", KEY)
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val o = try { org.json.JSONObject(txt) } catch (e: Exception) { org.json.JSONObject() }
                if (o.optBoolean("success")) {
                    Pair(o.optJSONObject("data")?.optLong("balance"), "OK")
                } else {
                    val m = o.optString("message")
                    Pair(null, if (m.isBlank()) "HTTP " + code else m)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Pair(null, "Jaringan: " + (e.message ?: "gagal"))
            }
        }

    /** Simpan nama + foto pemain untuk game WaveGames (dipakai userInfo). */
    suspend fun wavePrepare(name: String, avatar: String): String? {
        val r = call("wave_prepare", org.json.JSONObject().put("name", name).put("avatar", avatar))
        return r.error
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

    suspend fun sendGift(
        uid: String,
        price: Long,
        itemId: String? = null,
        roomId: String? = null,
        to: List<String> = emptyList(),
        each: Long = 0L,
    ): Res {
        val p = uidJson(uid).put("price", price)
        if (itemId != null) p.put("item_id", itemId)
        if (roomId != null) p.put("room_id", roomId)
        // Penerima gift: server menambah diamond mereka sebesar nilai gift.
        if (to.isNotEmpty()) {
            val arr = org.json.JSONArray()
            to.forEach { if (it.isNotBlank()) arr.put(it) }
            p.put("to", arr)
        }
        if (each > 0) p.put("each", each)
        return call("send_gift", p)
    }

    /** Hasil undian cashback gift dari server (amasuk ke pengirim, pecahan untuk animasi). */
    class CashbackRes(
        val amount: Long,
        val pecahan: Long,
        val recv: Long,
        val jp: Boolean,
        val asset: String,
        val error: String?,
        val mult: Int = 0,
        val parts: List<Long> = emptyList(),
    )

    suspend fun giftCashback(uid: String, price: Long, count: Int, to: String = ""): CashbackRes {
        val p = uidJson(uid).put("price", price).put("count", count)
        if (to.isNotBlank()) p.put("to", to)
        val j = postRaw("gift_cashback", p)
            ?: return CashbackRes(0, 0, 0, false, "", "Server tidak merespons")
        if (j.optInt("ok") != 1) {
            return CashbackRes(0, 0, 0, false, "", j.optString("e").ifBlank { "Cashback gagal" })
        }
        val parts = ArrayList<Long>()
        j.optJSONArray("parts")?.let { a ->
            for (i in 0 until a.length()) parts.add(a.optLong(i))
        }
        return CashbackRes(
            amount = j.optLong("amount"),
            pecahan = j.optLong("pecahan"),
            recv = j.optLong("recv"),
            jp = j.optInt("jp") == 1,
            asset = j.optString("asset"),
            error = null,
            mult = j.optInt("mult"),
            parts = parts,
        )
    }

    /** Hadiah hasil klaim roket dari server. */
    class RocketPrize(val coins: Long, val vip: Int, val days: Int, val error: String?)

    /**
     * Klaim hadiah roket. Server yang mengundi hadiahnya:
     * mode "launch" = hadiah acak setelah roket meluncur,
     * mode "rank"   = hadiah peringkat roket (top1..top3, sisanya coin).
     */
    suspend fun rocketClaimPrize(uid: String, mode: String, rank: Int, launch: String, lv: Int = 5): RocketPrize {
        val p = uidJson(uid).put("mode", mode)
        if (rank > 0) p.put("rank", rank)
        p.put("lv", lv.coerceIn(1, 5))
        if (launch.isNotBlank()) p.put("launch", launch)
        val j = postRaw("rocket_claim", p)
            ?: return RocketPrize(0, 0, 0, "Server tidak merespons, coba lagi")
        if (j.optInt("ok") != 1) {
            return RocketPrize(0, 0, 0, j.optString("e").ifBlank { "Gagal klaim hadiah" })
        }
        return RocketPrize(j.optLong("coins"), j.optInt("vip"), j.optInt("days"), null)
    }

    suspend fun exchange(uid: String, diamonds: Long) = call("exchange", uidJson(uid).put("diamonds", diamonds))


    // ---------------- Topup lewat payment gateway ----------------

    class TopupStart(val order: String?, val url: String?, val error: String?)

    /** Panggil edge function dan kembalikan JSON mentah (untuk aksi non-dompet). */
    private suspend fun postRaw(action: String, params: org.json.JSONObject): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val token = com.google.firebase.auth.FirebaseAuth.getInstance()
                    .currentUser?.getIdToken(true)?.await()?.token ?: return@withContext null
                val body = org.json.JSONObject().put("a", action).put("t", token).put("p", params)
                val conn = (URL(FN).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("apikey", KEY)
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (txt.isBlank()) null else org.json.JSONObject(txt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** Panggil fungsi SQL Supabase langsung (cadangan kalau edge function menolak aksi). */
    private suspend fun restRpc(fn: String, params: org.json.JSONObject): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL("$BASE/rest/v1/rpc/$fn").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("apikey", KEY)
                    setRequestProperty("Authorization", "Bearer $KEY")
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(params.toString().toByteArray()) }
                val code = conn.responseCode
                val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (txt.isBlank()) null
                else if (txt.trimStart().startsWith("[")) {
                    val arr = org.json.JSONArray(txt)
                    if (arr.length() > 0) arr.optJSONObject(0) else null
                } else org.json.JSONObject(txt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun myUid(): String? =
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

    /** Buat transaksi topup (Midtrans). Server yang menentukan harga paket. */
    suspend fun topupCreate(coins: Long): TopupStart {
        val name = com.google.firebase.auth.FirebaseAuth.getInstance()
            .currentUser?.displayName?.take(40).orEmpty()
        val o = postRaw("topup_create", org.json.JSONObject().put("coins", coins).put("name", name))
            ?: return TopupStart(null, null, "Tidak bisa menghubungi server pembayaran")
        if (o.optInt("ok") != 1) {
            return TopupStart(null, null, o.optString("e").ifBlank { "Gagal memulai pembayaran" })
        }
        return TopupStart(o.optString("order"), o.optString("url"), null)
    }


    // ---------------- Topup manual DANA ----------------

    class PayInfo(val dana: String, val name: String, val gopay: String = "", val qris: String = "")

    private fun parsePay(pay: org.json.JSONObject?): PayInfo? {
        if (pay == null) return null
        val dana = pay.optString("dana")
        val gopay = pay.optString("gopay")
        val qris = pay.optString("qris")
        if (dana.isBlank() && gopay.isBlank() && qris.isBlank()) return null
        return PayInfo(dana = dana, name = pay.optString("name"), gopay = gopay, qris = qris)
    }

    suspend fun payInfo(): PayInfo? {
        val o = postRaw("pay_info", org.json.JSONObject())
        if (o != null && o.optInt("ok") == 1) parsePay(o.optJSONObject("pay"))?.let { return it }
        return parsePay(restRpc("w_app_pay", org.json.JSONObject()))
    }

    class ManualStart(val order: String?, val error: String?)

    /**
     * Ajukan topup manual. Harga ditentukan server (tabel topup_price).
     * Kalau edge function masih versi lama (menolak aksi), pengajuan dikirim
     * lewat fungsi SQL w_app_topup_ask supaya topup tetap jalan.
     */
    suspend fun manualCreate(coins: Long, payer: String, method: String = "dana"): ManualStart {
        val o = postRaw(
            "manual_create",
            org.json.JSONObject().put("coins", coins).put("payer", payer).put("method", method),
        )
        if (o != null && o.optInt("ok") == 1) return ManualStart(o.optString("order"), null)
        val pesan = o?.optString("e").orEmpty()
        if (o != null && pesan.isNotBlank() && pesan != "Permintaan tidak valid") {
            return ManualStart(null, pesan)
        }
        val uid = myUid() ?: return ManualStart(null, "Sesi login tidak ditemukan")
        val r = restRpc(
            "w_app_topup_ask",
            org.json.JSONObject()
                .put("p_uid", uid).put("p_coins", coins)
                .put("p_payer", payer).put("p_method", method),
        ) ?: return ManualStart(null, "Tidak bisa menghubungi server")
        if (r.optInt("ok") == 1) return ManualStart(r.optString("order"), null)
        return ManualStart(null, r.optString("e").ifBlank { "Gagal mengajukan topup" })
    }

    /** Cek status pesanan: "pending", "paid", "rejected". */
    suspend fun topupStatus(order: String): String? {
        val o = postRaw("topup_status", org.json.JSONObject().put("order", order))
        if (o != null && o.optInt("ok") == 1) {
            val st = o.optJSONObject("order")?.optString("status")
            if (!st.isNullOrBlank()) return st
        }
        val uid = myUid() ?: return null
        val r = restRpc(
            "w_app_topup_state",
            org.json.JSONObject().put("p_uid", uid).put("p_id", order),
        ) ?: return null
        return if (r.optInt("ok") == 1) r.optString("status") else null
    }


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
