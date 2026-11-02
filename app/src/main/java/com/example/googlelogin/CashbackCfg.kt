package com.example.googlelogin

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await

/**
 * Pengaturan cashback dari panel (Realtime Database, pub/_cashbackCfg).
 * Dipakai sebagai cadangan kalau wallet-api belum menjawab, supaya animasi
 * pecahan tetap jalan di room.
 */
object CashbackCfg {
    class Roll(val mult: Int, val parts: List<Long>, val jpMin: Long)

    private val ladder = listOf(
        10000L, 9000L, 8000L, 6000L, 4000L, 3000L, 2000L, 1000L, 888L, 666L,
        500L, 400L, 300L, 200L, 100L, 88L, 66L, 50L, 30L, 20L, 10L,
    )

    /** Pecahan bulat yang menjumlah jadi total cashback. */
    private fun split(total: Long): List<Long> {
        if (total < 10L || total > 50000L) return emptyList()
        val out = ArrayList<Long>()
        var rem = total
        var guard = 0
        while (rem >= 10L && out.size < 8 && guard++ < 16) {
            val v = ladder.firstOrNull { it <= rem } ?: break
            out.add(v)
            rem -= v
        }
        return out
    }

    suspend fun roll(value: Long): Roll? {
        if (value <= 0L) return null
        val cfg = try {
            withTimeoutOrNull(4000L) { Rtdb.ref("pub/_cashbackCfg").get().await() }
        } catch (e: Exception) { null } ?: return null
        if (cfg.child("on").getValue(Boolean::class.java) == false) return null

        // Tabel pengali: t1..t4, dipilih dari harga gift.
        var rows: List<Pair<Int, Double>> = emptyList()
        var bestMin = -1L
        for (k in listOf("t1", "t2", "t3", "t4")) {
            val node = cfg.child(k)
            val min = node.child("min").getValue(Long::class.java) ?: 0L
            val list = ArrayList<Pair<Int, Double>>()
            for (c in node.child("rows").children) {
                val v = c.child("v").getValue(Long::class.java) ?: continue
                val w = c.child("w").getValue(Double::class.java) ?: continue
                if (v > 0 && w > 0) list.add(Pair(v.toInt(), w))
            }
            if (list.isNotEmpty() && value >= min && min >= bestMin) {
                rows = list
                bestMin = min
            }
        }
        if (rows.isEmpty()) return null

        val total = rows.sumOf { it.second }
        var x = Math.random() * total
        var mult = rows.last().first
        for (r in rows) {
            x -= r.second
            if (x <= 0) { mult = r.first; break }
        }
        val jpMin = cfg.child("jpMin").getValue(Long::class.java) ?: 1000L
        return Roll(mult = mult, parts = split(value * mult), jpMin = jpMin)
    }
}
