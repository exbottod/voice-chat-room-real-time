package com.example.googlelogin

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Pemangkas chat lama di Realtime Database.
 *
 * Supaya chat tidak menumpuk jadi "sampah": pesan yang lebih tua dari [HARI]
 * hari dibuang, dan setiap chat tetap menyimpan minimal [SIMPAN_MIN] pesan
 * terbaru. Berjalan otomatis paling banyak sekali per hari saat aplikasi dibuka,
 * dan bisa dipicu manual dari Pengaturan.
 */
object ChatBersih {
    const val HARI = 30
    private const val SIMPAN_MIN = 100
    private const val MAKS_CHAT_PER_JALAN = 40

    /** Sapu otomatis sekali sehari. Aman dipanggil berkali-kali. */
    fun otomatis(ctx: Context, uid: String) {
        if (uid.isBlank()) return
        val p = ctx.applicationContext.getSharedPreferences("chat_bersih", Context.MODE_PRIVATE)
        val sekarang = System.currentTimeMillis()
        if (sekarang - p.getLong("terakhir", 0L) < 20L * 3600L * 1000L) return
        p.edit().putLong("terakhir", sekarang).apply()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                hapusLama(uid, HARI)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    /** Hapus pesan lebih tua dari [hari] hari. Return jumlah pesan yang dibuang. */
    suspend fun hapusLama(uid: String, hari: Int, maksChat: Int = MAKS_CHAT_PER_JALAN): Int {
        if (uid.isBlank()) return 0
        val batas = System.currentTimeMillis() - hari.toLong() * 86_400_000L
        val daftar = try {
            Rtdb.ref("chatsByUser/$uid").get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return 0
        }
        var dihapus = 0
        var diproses = 0
        for (c in daftar.children) {
            if (diproses >= maksChat) break
            val cid = c.key ?: continue
            diproses++
            val snap = try {
                Rtdb.ref("chats/$cid/messages").get().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (!snap.exists()) continue
            val semua = snap.children.toList()
            if (semua.size <= SIMPAN_MIN) continue
            // Pesan terbaru selalu disimpan; sisanya dibuang kalau sudah lewat batas.
            val urut = semua.sortedBy { it.child("at").getValue(Long::class.java) ?: 0L }
            val kandidat = urut.dropLast(SIMPAN_MIN).filter {
                (it.child("at").getValue(Long::class.java) ?: 0L) < batas
            }
            if (kandidat.isEmpty()) continue
            for (m in kandidat) {
                try {
                    m.ref.removeValue().await()
                    dihapus++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }
        }
        return dihapus
    }
}
