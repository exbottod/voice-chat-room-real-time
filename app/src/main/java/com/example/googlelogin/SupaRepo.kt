package com.example.googlelogin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val BASE = "https://fdemjdqagxkhgqkmfvwk.supabase.co"
    private const val FN = "$BASE/functions/v1/smart-processor"
    // Dompet sekarang di Cloudflare Worker + Turso. Saldo, gift, topup, VIP,
    // store, level, dan hadiah roket dilayani dari sini.
    private const val WAL = "https://rezo-wallet.bangexx01.workers.dev"
    // Jalur cadangan (alamat lain, server dan database sama). Dipakai kalau HP
    // tidak bisa menjangkau alamat utama, misalnya diblokir jaringan/ISP.
    private const val WAL2 = "https://rezo-wallet.pages.dev"
    // Jalur utama baru: VPS Helipod (Singapore). Kuota Cloudflare gratis
    // sering habis karena callback game, jadi VPS dipakai lebih dulu dan
    // Cloudflare tinggal menjadi cadangan.
    private const val VPS = "https://43-156-99-4.sslip.io"
    // Baca saldo langsung dari Turso, tanpa lewat Cloudflare. Token di bawah
    // HARUS token baca-saja (read only), karena ikut tertanam di dalam APK.
    // Kalau kosong, app kembali memakai jalur worker seperti biasa.
    private const val TURSO_RO_URL = "https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io"
    private const val TURSO_RO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicm8iLCJpYXQiOjE3OTM3NjQ5NDksImlkIjoiMDFhMWEyMTYtOWIwMS03NjZiLWI4YWYtODdjZGNlMzZhMDVlIiwia2lkIjoiYVFzcmdzZFE2UGx6UVI5T3dvOUgwMWFoWWRVSGRRUnQweFJZNlBka0RFZyIsInJpZCI6IjcxOTI1OGUxLWJlOWQtNGJhNi05NGU0LTM4MDVhYWM5ZWUyOSJ9.bKRSrMwIj6SD4eglay0AQHhAI3ql2aR4svfnVkNK-DR85Dsv4UGvA9tAp6EEBiRt3hSqznKlR0uQ1bB_I_8tAQ"
    @Volatile private var pakaiCadangan = false
    // Kunci publishable: memang boleh ada di dalam aplikasi
    // (setara kunci anon, tanpa akses data karena RLS menutup semua tabel).
    private const val KEY = "sb_publishable_4PxD2LedOsBgIJyHLkM47A_bImSQI2W"

    class Res(val wallet: Wallet?, val error: String?, val needMigrate: Boolean = false)

    // ---------------------------------------------------------------
    // Token login diambil dari simpanan. Mengambil token dengan "paksa
    // segarkan" setiap kali (getIdToken(true)) membuat SETIAP aksi dompet
    // menunggu Google dulu; di jaringan lambat itu terlihat seperti
    // "server dompet lambat". Sekarang token dipakai ulang sampai mendekati
    // kedaluwarsa, dan hanya disegarkan kalau server menolak sesi.
    private var tokCache: String? = null
    private var tokUid: String? = null
    private var tokAt: Long = 0L

    private suspend fun token(): String? {
        val u = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            ?: return null
        val c = tokCache
        // Token hanya dipakai untuk akun yang sama, supaya ganti akun tidak
        // memakai sesi lama.
        if (c != null && tokUid == u.uid && System.currentTimeMillis() - tokAt < 25 * 60_000L) return c
        val t = u.getIdToken(false).await()?.token ?: return null
        tokCache = t
        tokUid = u.uid
        tokAt = System.currentTimeMillis()
        return t
    }

    private suspend fun tokenFresh(): String? {
        val u = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            ?: return null
        tokCache = null
        val t = u.getIdToken(true).await()?.token ?: return null
        tokCache = t
        tokUid = u.uid
        tokAt = System.currentTimeMillis()
        return t
    }

    /** Satu aksi dompet pada satu waktu, supaya kiriman beruntun tidak menumpuk. */
    private val tulisKunci = Mutex()

    /** POST kecil ke edge function. Semua argumen dompet dikirim sebagai JSON. */
    private suspend fun call(action: String, params: org.json.JSONObject): Res {
        // Server kadang bangun dulu (cold start) sebelum menjawab. Untuk aksi
        // baca (get) aman diulang; untuk transaksi tidak diulang otomatis,
        // supaya coin/berlian tidak terpotong dua kali.
        // Aksi tulis (gift, tukar, beli) diantrikan satu per satu: kalau
        // dikirim beruntun, permintaan saling menunggu di server dan
        // akhirnya tampak "server lambat".
        val hasilAwal = if (action == "get") {
            kotlinx.coroutines.withTimeoutOrNull(62_000L) { callInner(action, params) }
        } else {
            tulisKunci.withLock {
                kotlinx.coroutines.withTimeoutOrNull(62_000L) { callInner(action, params) }
            }
        }
        var hasil = hasilAwal ?: Res(null, "Server dompet lambat (timeout), coba lagi")
        // Aksi tulis mengubah saldo: cache lama tidak boleh dipakai lagi.
        if (action != "get") lupakanSaldo()
        if (hasil.error != null && action == "get" && hasil.error.contains("timeout", true)) {
            hasil = kotlinx.coroutines.withTimeoutOrNull(62_000L) {
                callInner(action, params)
            } ?: hasil
        }
        // Sesi ditolak bisa karena UID yang tersimpan di app sudah tidak sama
        // dengan akun yang benar-benar login. Coba sekali pakai UID akun aktif,
        // supaya dompet tidak terus tampil kosong.
        if (hasil.error != null && hasil.error.contains("Sesi tidak sah", true)) {
            val aktif = try {
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            } catch (e: Exception) { null }
            val dipakai = params.optString("uid", "")
            if (!aktif.isNullOrBlank() && aktif != dipakai) {
                val ulangParams = org.json.JSONObject(params.toString()).put("uid", aktif)
                val ulang = kotlinx.coroutines.withTimeoutOrNull(40_000L) {
                    callInner(action, ulangParams)
                }
                if (ulang != null && ulang.error == null) {
                    if (action == "get") cacheSaldo[aktif] = Entri(ulang, System.currentTimeMillis())
                    return ulang
                }
            }
        }
        return hasil
    }

    private fun kirim(alamat: String, body: org.json.JSONObject, tunggu: Int): Pair<Int, String> {
        val conn = (URL(alamat).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = tunggu
            doOutput = true
            setRequestProperty("apikey", KEY)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, txt)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun postWallet(body: org.json.JSONObject): Pair<Int, String> {
        // VPS SELALU dicoba lebih dulu. Cloudflare hanya cadangan terakhir:
        // kuotanya sering habis (429), jadi jawabannya tidak boleh dianggap
        // final selama jalur lain belum dicoba. Permintaan tulis aman diulang
        // karena memakai nomor permintaan (req).
        val urls = listOf(VPS, WAL2, WAL)
        var galat: java.io.IOException? = null
        var terakhir: Pair<Int, String>? = null
        urls.forEachIndexed { i, u ->
            try {
                val hasil = kirim(u, body, if (i == 0) 15_000 else 20_000)
                val (code, txt) = hasil
                val penuh = code == 429 || code >= 500 || txt.contains("1027")
                if (!penuh) return hasil           // 2xx dan 4xx biasa (mis. 401) = jawaban sah
                terakhir = hasil                   // jalur ini penuh/rusak: coba berikutnya
            } catch (e: java.io.IOException) {
                galat = e
            }
        }
        terakhir?.let { return it }
        throw galat ?: java.io.IOException("Jaringan tidak terjangkau")
    }

    private fun parseWalletResponse(code: Int, txt: String): Res {
        if (code !in 200..299 && txt.isBlank()) return Res(null, "Server dompet error $code")
        val o = try {
            if (txt.isNotBlank()) org.json.JSONObject(txt) else org.json.JSONObject()
        } catch (e: Exception) {
            return Res(null, if (code in 200..299) "Jawaban server dompet tidak terbaca" else "Server dompet error $code")
        }
        if (o.optInt("ok") == 1) return Res(parseWallet(o.optJSONObject("w")), null)
        val err = o.optString("e", "")
        return Res(null, err.ifBlank { "Server sibuk, coba lagi" }, err.contains("WALLET_NOT_FOUND"))
    }

    /** Baca baris dompet langsung dari Turso (jalur baca-saja). null = gagal. */
    private fun bacaTurso(uid: String): Wallet? {
        if (TURSO_RO_TOKEN.isBlank() || uid.isBlank()) return null
        val sql = "select uid, coins, diamonds, exp, vip, vip_until, lv_claimed, inv, equip " +
            "from wallets where uid = ?"
        val stmt = org.json.JSONObject().put("sql", sql).put(
            "args", org.json.JSONArray().put(
                org.json.JSONObject().put("type", "text").put("value", uid)
            )
        )
        val body = org.json.JSONObject().put(
            "requests", org.json.JSONArray()
                .put(org.json.JSONObject().put("type", "execute").put("stmt", stmt))
                .put(org.json.JSONObject().put("type", "close"))
        )
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$TURSO_RO_URL/v2/pipeline").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $TURSO_RO_TOKEN")
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) return null
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val hasil = org.json.JSONObject(txt).getJSONArray("results")
                .getJSONObject(0).getJSONObject("response").getJSONObject("result")
            val cols = hasil.getJSONArray("cols")
            val rows = hasil.getJSONArray("rows")
            if (rows.length() == 0) return null
            val row = rows.getJSONArray(0)
            fun kolom(nama: String): org.json.JSONObject? {
                for (i in 0 until cols.length()) {
                    if (cols.getJSONObject(i).optString("name") == nama) {
                        if (row.isNull(i)) return null
                        return row.getJSONObject(i)
                    }
                }
                return null
            }
            fun angka(nama: String) = kolom(nama)?.optString("value")?.toDoubleOrNull()?.toLong() ?: 0L
            fun teks(nama: String): String {
                val k = kolom(nama) ?: return ""
                val v = k.optString("value")
                return if (v == "null") "" else v
            }
            val o = org.json.JSONObject()
                .put("coins", angka("coins"))
                .put("diamonds", angka("diamonds"))
                .put("exp", angka("exp"))
                .put("vip", angka("vip"))
                .put("vipUntil", angka("vip_until"))
                .put("lvClaimed", angka("lv_claimed"))
                .put("inv", org.json.JSONObject(teks("inv").ifBlank { "{}" }))
                .put("equip", org.json.JSONObject(teks("equip").ifBlank { "{}" }))
            parseWallet(o)
        } catch (e: Exception) {
            null
        } finally {
            try { c?.disconnect() } catch (e: Exception) { }
        }
    }

    /**
     * Jeda darurat: kalau server menjawab 429 / "1027" (kuota Cloudflare habis),
     * berhenti menembak sampai waktu ini. Tanpa jeda, app mengulang terus dan
     * malah menghabiskan sisa kuota.
     */
    @Volatile private var jedaSampai = 0L
    private fun kenaBatas(code: Int, txt: String): Boolean {
        if (code == 429 || txt.contains("1027")) {
            jedaSampai = System.currentTimeMillis() + 45_000L
            return true
        }
        return false
    }

    private suspend fun callInner(action: String, params: org.json.JSONObject): Res =
        withContext(Dispatchers.IO) {
            try {
                if (System.currentTimeMillis() < jedaSampai) {
                    // Pakai saldo terakhir yang tersimpan, jangan hubungi server.
                    cacheSaldo[params.optString("uid", "")]?.let { return@withContext it.res }
                    return@withContext Res(null, "Server sedang penuh, coba lagi sebentar")
                }
                var token = token() ?: return@withContext Res(null, "Belum login")
                val body = org.json.JSONObject()
                    .put("a", action)
                    .put("t", token)
                    .put("p", params)
                var (code, txt) = postWallet(body)
                // Sesi kadaluwarsa: segarkan token sekali lalu ulangi. Aman untuk
                // transaksi yang memakai nomor permintaan (req).
                if (code == 401 || code == 403) {
                    val segar = tokenFresh()
                    if (segar != null) {
                        token = segar
                        body.put("t", segar)
                        val ulang = postWallet(body)
                        code = ulang.first
                        txt = ulang.second
                    }
                }
                if (kenaBatas(code, txt)) {
                    cacheSaldo[params.optString("uid", "")]?.let { return@withContext it.res }
                    return@withContext Res(null, "Server sedang penuh, coba lagi sebentar")
                }
                parseWalletResponse(code, txt)
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
            val token = token() ?: return@withContext "Belum login"
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
                val token = token() ?: return@withContext null
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
                val token = token()
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

    // ---- Cache saldo ----
    // Layar game/menu sering meminta saldo saat dibuka, dan game memantaunya
    // berkala. Tanpa cache, tiap permintaan = satu panggilan Edge Function.
    // Kuota free plan cuma 500 ribu panggilan sebulan, jadi saldo disimpan
    // sebentar dan permintaan yang bersamaan digabung jadi satu.
    private const val CACHE_MS = 45_000L
    private class Entri(val res: Res, val at: Long)
    private val cacheSaldo = java.util.concurrent.ConcurrentHashMap<String, Entri>()
    private val bacaKunci = Mutex()

    /** Saldo dari cache kalau masih segar. fresh = true memaksa ambil ulang. */
    suspend fun get(uid: String, fresh: Boolean = false): Res {
        if (!fresh) {
            cacheSaldo[uid]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it.res }
        }
        return bacaKunci.withLock {
            if (!fresh) {
                cacheSaldo[uid]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return@withLock it.res }
            }
            // Jalur utama: baca langsung ke Turso. Tidak memakai kuota Cloudflare
            // dan tidak terpengaruh batas 100 ribu permintaan per hari.
            bacaTurso(uid)?.let { w ->
                val r = Res(w, null)
                cacheSaldo[uid] = Entri(r, System.currentTimeMillis())
                return@withLock r
            }
            val r = call("get", uidJson(uid))
            if (r.wallet != null) cacheSaldo[uid] = Entri(r, System.currentTimeMillis())
            r
        }
    }

    /** Buang cache setelah ada transaksi, supaya saldo di layar ikut berubah. */
    fun lupakanSaldo(uid: String? = null) {
        if (uid == null) cacheSaldo.clear() else cacheSaldo.remove(uid)
    }

    /** Balasan mentah (tidak diubah jadi Wallet), untuk aksi yang bentuknya lain. */
    private suspend fun raw(action: String, params: org.json.JSONObject): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                var token = token() ?: return@withContext null
                val body = org.json.JSONObject().put("a", action).put("t", token).put("p", params)
                var (_, txt) = postWallet(body)
                if (txt.isBlank()) return@withContext null
                org.json.JSONObject(txt)
            } catch (e: Exception) {
                null
            }
        }

    /** Top 1-3 pemenang game Hive (24 jam terakhir) versi REZO. */
    suspend fun hiveRank(game: String): org.json.JSONObject? =
        raw("hive_rank", org.json.JSONObject().put("game", game))

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

    /**
     * Hadiah cek in harian masuk ke dompet Turso. Sebelumnya coin cek in masih
     * dikirim ke server game yang lama, jadi tidak pernah menambah saldo.
     * Server menolak klaim kedua pada tanggal yang sama.
     */
    /**
     * Panggilan panel staf (manager / admin). Balasan JSON apa adanya supaya
     * layar panel bisa menampilkan pesan server dengan tepat.
     */
    /**
     * Panggilan panel manager/admin. Dulu hanya lewat Cloudflare Worker, dan
     * begitu kuota gratisnya habis (HTTP 429 / 1027) hak panel tidak pernah
     * terbaca sehingga tombol panel tidak muncul walau sudah dikirim.
     * Sekarang urutannya VPS lebih dulu, lalu Cloudflare, lalu Pages.
     */
    suspend fun panelCall(action: String, params: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject =
        withContext(Dispatchers.IO) {
            val token = token() ?: return@withContext org.json.JSONObject().put("ok", 0).put("e", "Belum login")
            val body = org.json.JSONObject()
                .put("a", action)
                .put("t", token)
                .put("p", params)
            val urls = listOf(VPS, WAL2, WAL)
            var hasil: org.json.JSONObject? = null
            var pesan = "Server panel tidak terjangkau, coba lagi"
            var i = 0
            while (i < urls.size && hasil == null) {
                val base = urls[i]
                i++
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("$base/panel").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 12_000
                        readTimeout = 25_000
                        doOutput = true
                        setRequestProperty("apikey", KEY)
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val o = try {
                        if (txt.isBlank()) null else org.json.JSONObject(txt)
                    } catch (e: Exception) {
                        null
                    }
                    if (code in 200..299 && o != null && o.optInt("ok") == 1) {
                        pakaiCadangan = (base != VPS)
                        hasil = o
                    } else if (code in 400..498 && code != 429) {
                        // Penolakan sungguhan (sesi tidak sah, uid salah). Tidak
                        // perlu dicoba ke server lain karena jawabannya sama.
                        hasil = o ?: org.json.JSONObject().put("ok", 0).put("e", "Server panel error $code")
                    } else {
                        pesan = o?.optString("e")?.takeIf { it.isNotBlank() }
                            ?: if (code == 429) "Server panel penuh (429), coba lagi"
                            else "Server panel error $code, coba lagi"
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pesan = "Server panel lambat, coba lagi"
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (e: Exception) {
                    }
                }
            }
            hasil ?: org.json.JSONObject().put("ok", 0).put("e", pesan)
        }

    suspend fun signinClaim(uid: String, coins: Long, date: String): Res =
        call("signin_claim", uidJson(uid).put("coins", coins).put("date", date))

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
    class RocketPrize(val coins: Long, val vip: Int, val days: Int, val error: String?, val kartu: Boolean = false)

    /**
     * Klaim hadiah roket. Server yang mengundi hadiahnya:
     * mode "launch" = hadiah acak setelah roket meluncur,
     * mode "rank"   = hadiah peringkat roket (top1..top3, sisanya coin).
     */
    suspend fun rocketClaimPrize(uid: String, mode: String, rank: Int, launch: String, lv: Int = 5): RocketPrize {
        val p = uidJson(uid).put("mode", mode)
        if (rank > 0) p.put("rank", rank)
        p.put("lv", lv.coerceIn(1, 5))
        // Hadiah VIP disimpan sebagai kartu di Props, baru aktif saat ditekan.
        p.put("hold", 1)
        if (launch.isNotBlank()) p.put("launch", launch)
        val j = postRaw("rocket_claim", p)
            ?: return RocketPrize(0, 0, 0, "Server tidak merespons, coba lagi")
        if (j.optInt("ok") != 1) {
            return RocketPrize(0, 0, 0, j.optString("e").ifBlank { "Gagal klaim hadiah" })
        }
        val adaKartu = j.optJSONObject("card") != null
        return RocketPrize(j.optLong("coins"), j.optInt("vip"), j.optInt("days"), null, adaKartu)
    }

    /** Kartu hadiah (Props) milik satu akun. */
    class PropKartu(val id: String, val vip: Int, val days: Int, val src: String) {
        val judul: String get() = if (vip > 0) "VIP$vip · $days hari" else "Hadiah"
    }

    suspend fun propList(uid: String): List<PropKartu> {
        val j = postRaw("prop_list", uidJson(uid)) ?: return emptyList()
        val arr = j.optJSONArray("w") ?: return emptyList()
        val out = mutableListOf<PropKartu>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                PropKartu(
                    id = o.optString("id"),
                    vip = o.optInt("vip"),
                    days = o.optInt("days"),
                    src = o.optString("src").ifBlank { "rocket" },
                ),
            )
        }
        return out
    }

    /** Pakai kartu: masa aktif VIP mulai dihitung saat ini. */
    suspend fun propUse(uid: String, id: String): Res {
        val j = postRaw("prop_use", uidJson(uid).put("id", id))
            ?: return Res(null, "Server tidak merespons, coba lagi")
        if (j.optInt("ok") != 1) return Res(null, j.optString("e").ifBlank { "Gagal memakai kartu" })
        val w = j.optJSONObject("w")
        return Res(if (w != null) parseWallet(w) else null, null)
    }

    suspend fun exchange(uid: String, diamonds: Long): Res {
        // Nomor permintaan (req) membuat tukar aman diulang: server mengenali
        // permintaan yang sama dan tidak memotong berlian dua kali.
        val req = "ex" + System.currentTimeMillis() + uid.takeLast(6)
        val r = call("exchange", uidJson(uid).put("diamonds", diamonds).put("req", req))
        // Kalau gagal karena waktu habis, hasilnya belum pasti: jangan ulangi,
        // cukup beri tahu bahwa saldo sedang dicek ulang.
        if (r.error != null && (r.error.contains("timeout", true) || r.error.contains("Tidak bisa menghubungi", true))) {
            return Res(null, "Server sedang lambat. Saldo dicek ulang otomatis, tunggu sebentar.")
        }
        return r
    }


    // ---------------- Topup lewat payment gateway ----------------

    class TopupStart(val order: String?, val url: String?, val error: String?)

    /**
     * Panggil server dompet dan kembalikan JSON mentah (aksi non-dompet seperti
     * klaim roket dan cashback gift). Ikut jalur failover VPS -> Worker -> Pages:
     * dulu fungsi ini menghubungi Worker Cloudflare langsung, dan karena kuotanya
     * habis (429) semua klaim gagal dengan pesan "Server tidak merespons".
     */
    private suspend fun postRaw(action: String, params: org.json.JSONObject): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val token = token() ?: return@withContext null
                val body = org.json.JSONObject().put("a", action).put("t", token).put("p", params)
                val (code, txt) = postWallet(body)
                if (code !in 200..299 || txt.isBlank()) null
                else runCatching { org.json.JSONObject(txt) }.getOrNull()
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
