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

    suspend fun loadState(uid: String): SigninState = try {
        val s = Rtdb.ref("signin/$uid").get().await()
        SigninState(
            last = s.child("last").getValue(String::class.java) ?: "",
            day = (s.child("day").value as? Number)?.toInt() ?: 0,
            total = (s.child("total").value as? Number)?.toInt() ?: 0,
        )
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { SigninState() }

    fun canClaim(st: SigninState): Boolean = st.last != today()

    /** Hari berikutnya yang akan didapat (1..7 bergulir). */
    fun nextDay(st: SigninState): Int = if (st.day in 1..6) st.day + 1 else 1

    fun dayIndexFor(st: SigninState): Int = nextDay(st)

    /** Klaim hadiah hari ini. Mengembalikan pasangan (hari ke, pesan error). */
    suspend fun claim(uid: String): Pair<Int, String?> {
        val st = loadState(uid)
        if (!canClaim(st)) return 0 to "Sudah cek in hari ini"
        val cfg = loadCfg()
        val idx = nextDay(st) - 1
        val r = cfg.days.getOrNull(idx) ?: return 0 to "Hadiah belum diatur"

        // 1) Coin masuk dompet (server Supabase, tercatat di money_tx)
        if (r.coins > 0) {
            SupaRepo.waveFlow(r.coins, 2, UUID.randomUUID().toString(), "Sign-in")
        }
        // 2) Item (bingkai / badge / medal / kartu) masuk My Item
        if (r.itemId.isNotBlank()) {
            val it = findItem(r.itemId)
            if (it != null) {
                SupaRepo.buyItem(uid, it.copy(price = 0, days = if (r.itemDays > 0) r.itemDays else it.days))
            }
        }
        // 3) VIP: tambah coin sebesar harga lalu beli tier-nya (server tetap yang menghitung).
        if (r.vipTier in 1..9) {
            val price = when (r.vipTier) {
                1 -> 500_000L; 2 -> 1_500_000L; 3 -> 3_000_000L; 4 -> 6_000_000L
                5 -> 12_000_000L; 6 -> 25_000_000L; 7 -> 50_000_000L; 8 -> 100_000_000L
                else -> 250_000_000L
            }
            SupaRepo.waveFlow(price, 2, UUID.randomUUID().toString(), "Sign-in VIP")
            SupaRepo.buyVip(uid, r.vipTier)
        }

        // 4) Catat status
        try {
            Rtdb.ref("signin/$uid").setValue(
                hashMapOf(
                    "last" to today(),
                    "day" to idx + 1L,
                    "total" to (st.total + 1).toLong(),
                    "at" to System.currentTimeMillis(),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        try { PublicSync.push(uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        return (idx + 1) to null
    }
}
