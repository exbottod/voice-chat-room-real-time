package com.example.googlelogin

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Backsound Greedy Cat.
 * Nada dibuat sendiri oleh aplikasi (file WAV kecil di cache), lalu diputar
 * berulang pelan sebagai latar. Tidak butuh file audio dari internet.
 */
object GreedyMusic {
    private var player: MediaPlayer? = null
    private var loopFile: File? = null

    private val melody = intArrayOf(
        523, 659, 784, 659, 587, 698, 880, 698,
        523, 659, 784, 1046, 880, 784, 659, 523,
    )

    fun start(ctx: Context) {
        if (player != null) return
        try {
            val f = loopFile ?: File(ctx.cacheDir, "greedy_cat_loop.wav").also { loopFile = it }
            if (!f.exists() || f.length() < 1000L) writeWav(f)
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.isLooping = true
            mp.setVolume(0.35f, 0.35f)
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            player = null
        }
    }

    fun stop() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
    }

    private fun writeWav(f: File) {
        val sr = 22050
        val noteLen = sr * 14 / 100
        val total = noteLen * melody.size
        val pcm = ByteArray(total * 2)
        for (i in melody.indices) {
            val freq = melody[i]
            for (s in 0 until noteLen) {
                val t = s.toDouble() / sr
                val env = 1.0 - (s.toDouble() / noteLen) * 0.6
                val v = Math.sin(2 * Math.PI * freq * t) * 0.5 + Math.sin(2 * Math.PI * freq * 2 * t) * 0.12
                val sample = (v * env * 9000.0).toInt().coerceIn(-30000, 30000)
                val idx = ((i * noteLen) + s) * 2
                pcm[idx] = (sample and 0xFF).toByte()
                pcm[idx + 1] = ((sample shr 8) and 0xFF).toByte()
            }
        }
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)
        le16(1)
        le32(sr)
        le32(sr * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(pcm.size)
        out.write(pcm)
        f.writeBytes(out.toByteArray())
    }
}
