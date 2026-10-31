package com.example.googlelogin

import kotlinx.coroutines.tasks.await

/**
 * Nilai chip taruhan tiap game, diatur dari panel developer (tab Bet Game).
 * Datanya ada di Realtime Database: betCfg/<kunci game>/chips.
 * Kalau belum diatur atau gagal dibaca, game memakai nilai bawaan masing-masing.
 */
object BetCfg {
    private val memo = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()

    /** Nilai yang sudah terbaca sebelumnya (dipakai langsung supaya chip tidak berkedip). */
    fun cached(key: String, fallback: List<Long>): List<Long> = memo[key] ?: fallback

    /** Ambil nilai chip dari server sekali per sesi, lalu simpan di memori. */
    suspend fun load(key: String, fallback: List<Long>): List<Long> {
        memo[key]?.let { return it }
        val out = try {
            val snap = Rtdb.ref("betCfg/$key/chips").get().await()
            val list = snap.children.mapNotNull { c ->
                c.getValue(Long::class.java) ?: c.getValue(Int::class.java)?.toLong()
            }.filter { it > 0L }.distinct().sorted()
            if (list.isNotEmpty() && list.size <= 6) list else fallback
        } catch (e: Exception) {
            fallback
        }
        memo[key] = out
        return out
    }

    /** Label chip: 1000 -> 1K, 1500000 -> 1.5M. */
    fun label(v: Long): String = when {
        v >= 1_000_000L -> trimNum(v / 1_000_000.0) + "M"
        v >= 1_000L -> trimNum(v / 1_000.0) + "K"
        else -> v.toString()
    }

    private fun trimNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString()
        else String.format("%.1f", d).trimEnd('0').trimEnd('.')
}
