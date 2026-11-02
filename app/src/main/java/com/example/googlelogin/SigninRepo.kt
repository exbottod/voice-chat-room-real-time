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

    /**
     * Klaim hadiah hari ini. Mengembalikan pasangan (hari ke, pesan error).
     *
     * Setiap hadiah dicoba satu per satu dan kegagalannya tidak menghentikan
     * proses, karena status "sudah sign-in hari ini" WAJIB tersimpan supaya
     * dialog tidak muncul lagi di hari yang sama.
     */
    suspend fun claim(uid: String): Pair<Int, String?> {
        val st = loadState(uid)
        if (!canClaim(st)) return 0 to "Sudah cek in hari ini"
        val cfg = loadCfg()
        val idx = nextDay(st) - 1
        val r = cfg.days.getOrNull(idx) ?: return 0 to "Hadiah belum diatur"

        var gagal = ""
        fun catat(pesan: String) { if (gagal.isBlank()) gagal = pesan }

        // 1) Coin masuk dompet (server Supabase, tercatat di money_tx)
        if (r.coins > 0) {
            try {
                val ok = SupaRepo.waveFlow(r.coins, 2, UUID.randomUUID().toString(), "Sign-in")
                if (ok == null) catat("Coin belum masuk, coba lagi nanti")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                catat("Coin tertunda: ${e.message}")
            }
        }
        // 2) Item (bingkai / badge / medal / kartu) masuk My Item
        if (r.itemId.isNotBlank()) {
            try {
                val it = findItem(r.itemId)
                if (it != null) {
                    SupaRepo.buyItem(uid, it.copy(price = 0, days = if (r.itemDays > 0) r.itemDays else it.days))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                catat("Item tertunda: ${e.message}")
            }
        }
        // 3) VIP: tambah coin sebesar harga lalu beli tier-nya (server tetap yang menghitung).
        if (r.vipTier in 1..9) {
            try {
                val price = when (r.vipTier) {
                    1 -> 500_000L; 2 -> 1_500_000L; 3 -> 3_000_000L; 4 -> 6_000_000L
                    5 -> 12_000_000L; 6 -> 25_000_000L; 7 -> 50_000_000L; 8 -> 100_000_000L
                    else -> 250_000_000L
                }
                val ok = SupaRepo.waveFlow(price, 2, UUID.randomUUID().toString(), "Sign-in VIP")
                if (ok == null) catat("VIP belum masuk, coba lagi nanti")
                SupaRepo.buyVip(uid, r.vipTier)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                catat("VIP tertunda: ${e.message}")
            }
        }

        // 4) Catat status: lokal dulu (tidak pernah gagal), lalu database.
        saveLocal(uid, SigninState(last = today(), day = idx + 1, total = st.total + 1))
        val simpan = try {
            Rtdb.ref("signin/$uid").setValue(
                hashMapOf(
                    "last" to today(),
                    "day" to (idx + 1).toLong(),
                    "total" to (st.total + 1).toLong(),
                    "at" to System.currentTimeMillis(),
                ),
            ).await()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        try { PublicSync.push(uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        if (!simpan) return (idx + 1) to "Hadiah masuk. Status tersimpan di HP, pasang rules database agar tersinkron."
        return (idx + 1) to gagal.ifBlank { null }
    }
}
