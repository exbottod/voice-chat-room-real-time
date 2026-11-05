package com.rezochatvoice.exceo

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await

/**
 * Nilai chip taruhan tiap game, diatur dari panel developer (tab Bet Game).
 * Datanya ada di Realtime Database: betCfg/<kunci game>/chips.
 * Kalau belum diatur atau gagal dibaca, game memakai nilai bawaan masing-masing.
 */
object BetCfg {
    private val memo = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()

    /** 2 = betCfg (lokasi utama), 1 = pub/_betCfg (cermin). Dipakai agar nilai utama menang. */
    private val rank = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var live = false 

    /** Nilai yang sudah terbaca sebelumnya (dipakai langsung supaya chip tidak berkedip). */
    fun cached(key: String, fallback: List<Long>): List<Long> = memo[key] ?: fallback

    /**
     * Dengarkan betCfg terus-menerus. Jadi begitu pemilik mengubah nilai taruhan
     * di panel, REZO langsung memakai nilai baru tanpa perlu tutup aplikasi.
     */
    fun startLive() {
        if (live) return
        live = true
        val paths = listOf("pub/_betCfg" to 1, "betCfg" to 2)
        for ((path, r) in paths) {
            try {
                Rtdb.ref(path).addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        for (c in snap.children) {
                            val key = c.key ?: continue
                            val chips = c.child("chips").children.mapNotNull { ch ->
                                ch.getValue(Long::class.java) ?: ch.getValue(Int::class.java)?.toLong()
                            }.filter { it > 0L }.distinct().sorted()
                            if (chips.isEmpty() || chips.size > 6) continue
                            val lama = rank[key] ?: 0
                            if (r >= lama) {
                                memo[key] = chips
                                rank[key] = r
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
            } catch (e: Exception) {
                // abaikan, game tetap memakai nilai bawaan
            }
        }
    }

    /** Ambil nilai chip terbaru. Kedengarannya live, jadi selalu ikut panel. */
    suspend fun load(key: String, fallback: List<Long>): List<Long> {
        startLive()
        memo[key]?.let { return it }
        var out: List<Long> = fallback
        for (base in listOf("betCfg", "pub/_betCfg")) {
            try {
                val snap = Rtdb.ref("$base/$key/chips").get().await()
                val list = snap.children.mapNotNull { c ->
                    c.getValue(Long::class.java) ?: c.getValue(Int::class.java)?.toLong()
                }.filter { it > 0L }.distinct().sorted()
                if (list.isNotEmpty() && list.size <= 6) {
                    out = list
                    break
                }
            } catch (e: Exception) {
                // coba lokasi berikutnya
            }
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
