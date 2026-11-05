package com.example.googlelogin

import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Musik room yang terdengar oleh SEMUA orang di room.
 *
 * Dulu musik hanya diputar lewat speaker HP pemutar, jadi user lain tidak
 * mendengarnya sama sekali (suara room lewat LiveKit, bukan speaker).
 *
 * Cara sekarang: lagu diunggah ke Supabase Storage, lalu tautannya ditulis ke
 * Realtime Database (roomMusic/{roomId}) bersama posisi dan waktu mulai.
 * Setiap HP di room memutar tautan yang sama dan menghitung posisinya dari
 * waktu mulai, jadi suara musik terdengar oleh semua dan tetap sejalan.
 */
object RoomMusicShare {
    private const val BASE = "https://fdemjdqagxkhgqkmfvwk.supabase.co"
    // Kunci publishable, sama seperti RoomImages/SupaRepo.
    private const val KEY = "sb_publishable_4PxD2LedOsBgIJyHLkM47A_bImSQI2W"
    private const val BUCKET = "chatimg"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var player: MediaPlayer? = null
    private var urlSekarang = ""
    private var siap = false
    private var roomId = ""
    @Volatile private var reg: Reg? = null

    /** Posisi musik saat ini (ms) untuk semua HP. */
    fun posisiMs(): Int {
        val p = player ?: return 0
        return try { p.currentPosition } catch (e: Exception) { 0 }
    }

    fun durasiMs(): Int = try { player?.duration ?: -1 } catch (e: Exception) { -1 }

    private var volumeLokal = 80

    /** Volume pemutar bersama (0..100). */
    fun setVolume(v: Int) {
        volumeLokal = v.coerceIn(0, 100)
        val f = volumeLokal / 100f
        try { player?.setVolume(f, f) } catch (e: Exception) { }
    }

    /** Geser posisi lagu bersama; semua HP ikut pindah ke posisi ini. */
    suspend fun geser(room: String, url: String, judul: String, main: Boolean, pos: Long) {
        try { player?.let { if (it.isPlaying) it.seekTo(pos.toInt()) } } catch (e: Exception) { }
        siarkan(room, url, judul, main, pos)
    }

    fun adaLagu(): Boolean = urlSekarang.isNotBlank()

    /** Unggah satu file lagu ke Supabase Storage, hasilnya tautan publik. */
    suspend fun unggah(file: File): Pair<String?, String?> {
        return try {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                ?: return null to "Belum login"
            val bytes = file.readBytes()
            if (bytes.size > 25_000_000) return null to "Lagu terlalu besar (maks 25 MB)"
            val bersih = file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val path = "$uid/musik_${System.currentTimeMillis()}_$bersih"
            val conn = (URL("$BASE/storage/v1/object/$BUCKET/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                doOutput = true
                setRequestProperty("apikey", KEY)
                setRequestProperty("Authorization", "Bearer $KEY")
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return null to "Unggah gagal ($code) ${body.take(80)}"
            }
            "$BASE/storage/v1/object/public/$BUCKET/$path" to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to (e.message ?: "Unggah gagal")
        }
    }

    /** Simpan keadaan musik room supaya semua HP mengikuti. */
    suspend fun siarkan(room: String, url: String, judul: String, main: Boolean, pos: Long) {
        if (room.isBlank()) return
        try {
            Rtdb.ref("roomMusic/$room").updateChildren(
                hashMapOf<String, Any?>(
                    "url" to url,
                    "title" to judul,
                    "playing" to main,
                    "pos" to pos,
                    "at" to System.currentTimeMillis(),
                    "by" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""),
                ),
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { }
    }

    suspend fun hentikan(room: String) {
        if (room.isBlank()) return
        try { Rtdb.ref("roomMusic/$room").removeValue().await() } catch (e: Exception) { }
    }

    /** Mulai mengikuti musik room. Dipanggil sekali saat masuk room. */
    fun observe(room: String) {
        if (room.isBlank()) return
        if (room == roomId && reg != null) return
        lepas()
        roomId = room
        try {
            reg = Rtdb.listen(Rtdb.ref("roomMusic/$room")) { s ->
                val url = s.child("url").getValue(String::class.java).orEmpty()
                val judul = s.child("title").getValue(String::class.java).orEmpty()
                val main = s.child("playing").getValue(Boolean::class.java) ?: false
                val pos = s.child("pos").getValue(Long::class.java) ?: 0L
                val at = s.child("at").getValue(Long::class.java) ?: 0L
                terapkan(url, judul, main, pos, at)
            }
        } catch (e: Exception) { }
    }

    fun lepas() {
        try { reg?.remove() } catch (e: Exception) { }
        reg = null
        roomId = ""
        try { player?.release() } catch (e: Exception) { }
        player = null
        urlSekarang = ""
        siap = false
    }

    private fun targetMs(pos: Long, at: Long): Int {
        if (at <= 0L) return pos.toInt()
        val lewat = (System.currentTimeMillis() - at).coerceAtLeast(0L)
        return (pos + lewat).toInt()
    }

    /** Terapkan keadaan dari database ke pemutar HP ini. */
    private fun terapkan(url: String, judul: String, main: Boolean, pos: Long, at: Long) {
        if (url.isBlank()) {
            try { player?.release() } catch (e: Exception) { }
            player = null
            urlSekarang = ""
            siap = false
            return
        }
        if (url != urlSekarang) {
            try { player?.release() } catch (e: Exception) { }
            player = null
            siap = false
            urlSekarang = url
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setDataSource(url)
                mp.setOnPreparedListener { p ->
                    siap = true
                    try { p.setVolume(volumeLokal / 100f, volumeLokal / 100f) } catch (e: Exception) { }
                    try {
                        p.seekTo(targetMs(pos, at))
                        if (main) p.start() else p.pause()
                    } catch (e: Exception) { }
                }
                mp.setOnCompletionListener { p ->
                    // Lagu habis: kabari room supaya semua ikut berhenti.
                    scope.launch { siarkan(roomId, url, judul, false, 0L) }
                    try { p.seekTo(0) } catch (e: Exception) { }
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                player = null
                urlSekarang = ""
            }
            return
        }
        val p = player ?: return
        try {
            if (!siap) return
            if (main) {
                if (!p.isPlaying) {
                    p.seekTo(targetMs(pos, at))
                    p.start()
                }
            } else if (p.isPlaying) {
                p.pause()
                p.seekTo(targetMs(pos, at))
            } else {
                p.seekTo(targetMs(pos, at))
            }
        } catch (e: Exception) { }
    }
}
