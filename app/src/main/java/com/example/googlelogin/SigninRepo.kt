package com.example.googlelogin

import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Cek in harian (sign-in) 1-7 hari.
 *
 * Hadiah disimpan di Realtime Database (signinCfg) supaya bisa diatur dari
 * panel developer HTML tanpa membangun ulang APK.
 * Hadiah per hari: coin, item (bingkai/badge/medal/kartu), dan VIP.
 */
data class SigninReward(
    val coins: Long = 0L,
    val itemId: String = "",
    val itemDays: Long = 3L,
    val vipTier: Int = 0,
    val vipDays: Int = 0,
)

data class SigninCfg(
    val days: List<SigninReward> = defaultDays(),
    val target: Int = 18,
    val enabled: Boolean = true,
)

data class SigninState(
    val last: String = "",   // yyyy-MM-dd terakhir klaim
    val day: Int = 0,        // hari terakhir yang diklaim (1..7)
    val total: Int = 0,      // jumlah hari total pernah klaim
)

fun defaultDays(): List<SigninReward> = listOf(
    SigninReward(coins = 10_000_000, itemDays = 3),
    SigninReward(coins = 40_000_000, itemDays = 3),
    SigninReward(coins = 80_000_000, itemDays = 3),
    SigninReward(coins = 10_000_000, itemDays = 3),
    SigninReward(coins = 20_000_000, itemDays = 3),
    SigninReward(coins = 50_000_000, itemDays = 3),
    SigninReward(coins = 100_000_000, itemDays = 3, vipTier = 1),
)

object SigninRepo {
    private const val CFG = "signinCfg"
    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = dayFmt().format(Date())

    // Cadangan status cek-in di HP. Dipakai kalau database lambat/menolak,
    // supaya dialog "Sign in" tidak muncul lagi di hari yang sama.
    private fun sp(): android.content.SharedPreferences? = try {
        val ctx = com.google.firebase.FirebaseApp.getInstance().applicationContext
        ctx.getSharedPreferences("signin_local", android.content.Context.MODE_PRIVATE)
    } catch (e: Exception) { null }

    private fun localState(uid: String): SigninState = try {
        val p = sp()
        SigninState(
            last = p?.getString("last_" + uid, "") ?: "",
            day = p?.getInt("day_" + uid, 0) ?: 0,
            total = p?.getInt("total_" + uid, 0) ?: 0,
        )
    } catch (e: Exception) { SigninState() }

    private fun saveLocal(uid: String, st: SigninState) {
        try {
            sp()?.edit()
                ?.putString("last_" + uid, st.last)
                ?.putInt("day_" + uid, st.day)
                ?.putInt("total_" + uid, st.total)
                ?.apply()
        } catch (e: Exception) { }
    }

    private fun parseCfg(s: com.google.firebase.database.DataSnapshot?): SigninCfg {
        if (s == null || !s.exists()) return SigninCfg()
        val target = (s.child("target").value as? Number)?.toInt() ?: 18
        val enabled = (s.child("enabled").value as? Boolean) ?: true
        val days = ArrayList<SigninReward>()
        for (i in 1..7) {
            val d = s.child("days").child(i.toString())
            days.add(
                SigninReward(
                    coins = (d.child("coins").value as? Number)?.toLong() ?: defaultDays()[i - 1].coins,
                    itemId = d.child("item").getValue(String::class.java) ?: defaultDays()[i - 1].itemId,
                    itemDays = (d.child("itemDays").value as? Number)?.toLong() ?: 3L,
                    vipTier = (d.child("vip").value as? Number)?.toInt() ?: 0,
                    vipDays = (d.child("vipDays").value as? Number)?.toInt() ?: 0,
                ),
            )
        }
        return SigninCfg(days = days, target = target, enabled = enabled)
    }

    suspend fun loadCfg(): SigninCfg = try {
        parseCfg(Rtdb.ref(CFG).get().await())
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { SigninCfg() }

    suspend fun loadState(uid: String): SigninState {
        val local = localState(uid)
        val remote = try {
            val s = Rtdb.ref("signin/$uid").get().await()
            SigninState(
                last = s.child("last").getValue(String::class.java) ?: "",
                day = (s.child("day").value as? Number)?.toInt() ?: 0,
                total = (s.child("total").value as? Number)?.toInt() ?: 0,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SigninState()
        }
        // Ambil catatan yang paling baru, supaya status tidak pernah "mundur".
        val last = if (remote.last >= local.last) remote.last else local.last
        val day = if (remote.last >= local.last) remote.day else local.day
        val total = maxOf(remote.total, local.total)
        return SigninState(last = last, day = day, total = total)
    }

    fun canClaim(st: SigninState): Boolean = st.last != today()

    /** Hari berikutnya yang akan didapat (1..7 bergulir). */
    fun nextDay(st: SigninState): Int = if (st.day in 1..6) st.day + 1 else 1

    fun dayIndexFor(st: SigninState): Int = nextDay(st)

    // ---- Hadiah yang belum tentu masuk, dicoba ulang otomatis ----

    private data class Pending(
        val date: String,
        val day: Int,
        val coins: Long,
        val itemId: String,
        val itemDays: Long,
        val vipTier: Int,
        val vipDays: Int,
    )

    private fun savePending(uid: String, p: Pending?) {
        try {
            val e = sp()?.edit()
            if (p == null) e?.remove("pending_$uid") else e?.putString(
                "pending_$uid",
                listOf(p.date, p.day, p.coins, p.itemId, p.itemDays, p.vipTier, p.vipDays).joinToString("|"),
            )
            e?.apply()
        } catch (e: Exception) { }
    }

    private fun loadPending(uid: String): Pending? = try {
        val raw = sp()?.getString("pending_$uid", "").orEmpty()
        val f = raw.split("|")
        if (f.size < 7) null else Pending(
            date = f[0], day = f[1].toIntOrNull() ?: 0, coins = f[2].toLongOrNull() ?: 0L,
            itemId = f[3], itemDays = f[4].toLongOrNull() ?: 3L,
            vipTier = f[5].toIntOrNull() ?: 0, vipDays = f[6].toIntOrNull() ?: 0,
        )
    } catch (e: Exception) { null }

    /** Kode transaksi tetap per hari, jadi percobaan ulang tidak pernah dobel. */
    private fun txFor(uid: String, date: String, apa: String): String =
        UUID.nameUUIDFromBytes("signin|$uid|$date|$apa".toByteArray()).toString()

    private fun vipPrice(tier: Int): Long = when (tier) {
        1 -> 500_000L; 2 -> 1_500_000L; 3 -> 3_000_000L; 4 -> 6_000_000L
        5 -> 12_000_000L; 6 -> 25_000_000L; 7 -> 50_000_000L; 8 -> 100_000_000L
        else -> 250_000_000L
    }

    /**
     * Kirim hadiah ke server. Tiap langkah dibatasi waktu supaya dialog tidak
     * pernah menggantung. null = semua masuk.
     */
    private suspend fun deliver(uid: String, p: Pending): String? {
        var gagal: String? = null
        fun catat(pesan: String) { if (gagal == null) gagal = pesan }

        if (p.coins > 0L) {
            // Jalur utama: dompet Turso (sumber saldo yang dipakai aplikasi).
            val utama = try {
                kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                    SupaRepo.signinClaim(uid, p.coins, p.date)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { null }
            if (utama?.wallet == null) {
                // Cadangan: alur server game lama, untuk akun lama yang belum
                // punya dompet Turso.
                val ok = try {
                    kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        SupaRepo.waveFlow(p.coins, 2, txFor(uid, p.date, "coin"), "Sign-in")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) { null }
                if (ok == null) catat("Coin belum masuk, akan dicoba otomatis")
            }
        }
        if (p.itemId.isNotBlank()) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    val it = findItem(p.itemId)
                    if (it != null) {
                        SupaRepo.buyItem(uid, it.copy(price = 0, days = if (p.itemDays > 0) p.itemDays else it.days))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                catat("Item tertunda, akan dicoba otomatis")
            }
        }
        if (p.vipTier in 1..9) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    SupaRepo.waveFlow(vipPrice(p.vipTier), 2, txFor(uid, p.date, "vipcoin"), "Sign-in VIP")
                    SupaRepo.buyVip(uid, p.vipTier)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                catat("VIP tertunda, akan dicoba otomatis")
            }
        }
        return gagal
    }

    /** Coba kirim ulang hadiah yang tertunda (dipanggil saat aplikasi dibuka). */
    suspend fun retryPending(uid: String) {
        val p = loadPending(uid) ?: return
        if (p.day <= 0) { savePending(uid, null); return }
        val err = deliver(uid, p)
        // Kalau masih gagal, simpan lagi untuk dicoba lain waktu.
        if (err == null) savePending(uid, null)
        try { PublicSync.push(uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }

    private suspend fun saveRemote(uid: String, st: SigninState): Boolean = try {
        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            Rtdb.ref("signin/$uid").setValue(
                hashMapOf(
                    "last" to st.last,
                    "day" to st.day.toLong(),
                    "total" to st.total.toLong(),
                    "at" to System.currentTimeMillis(),
                ),
            ).await()
            true
        } ?: false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Klaim hadiah hari ini.
     *
     * Status "sudah sign-in" disimpan lebih dulu (lokal lalu database), baru
     * hadiah dikirim ke server. Jadi tombol tidak pernah berhenti di
     * "Memproses…" walau server lambat, dan hadiah yang gagal dikirim ulang
     * otomatis dengan kode transaksi tetap (tidak bisa dobel).
     */
    suspend fun claim(uid: String): Pair<Int, String?> {
        val st = loadState(uid)
        if (!canClaim(st)) return 0 to "Sudah cek in hari ini"
        val cfg = loadCfg()
        val idx = nextDay(st) - 1
        val r = cfg.days.getOrNull(idx) ?: return 0 to "Hadiah belum diatur"

        val baru = SigninState(last = today(), day = idx + 1, total = st.total + 1)
        saveLocal(uid, baru)
        val simpan = saveRemote(uid, baru)

        val p = Pending(today(), idx + 1, r.coins, r.itemId, r.itemDays, r.vipTier, r.vipDays)
        savePending(uid, p)
        val gagal = deliver(uid, p)
        if (gagal == null) savePending(uid, null)
        try { PublicSync.push(uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }

        val pesan = when {
            gagal != null -> gagal
            !simpan -> "Hadiah masuk. Status tersimpan di HP, pasang rules database agar tersinkron."
            else -> null
        }
        return (idx + 1) to pesan
    }
}
