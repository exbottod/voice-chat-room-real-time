package com.example.googlelogin

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * Backsound Greedy Cat. Nada dibuat langsung oleh aplikasi (tanpa file audio),
 * diputar berulang pelan-pelan sebagai latar. Bisa dimatikan dari tombol musik.
 */
object GreedyMusic {
    private const val SR = 22050
    private var track: AudioTrack? = null
    private var running = false
    private var worker: Thread? = null

    // melodi ringan 16 nada, diputar berulang
    private val melody = intArrayOf(
        523, 659, 784, 659, 587, 698, 880, 698,
        523, 659, 784, 1046, 880, 784, 659, 523,
    )

    fun start() {
        if (running) return
        running = true
        worker = thread {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val bufSize = if (minBuf > 0) minBuf * 2 else SR
                val at = AudioTrack(
                    AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM,
                )
                track = at
                at.setVolume(0.25f)
                at.play()
                while (running) {
                    val pcm = renderLoop()
                    var off = 0
                    while (off < pcm.size && running) {
                        val n = at.write(pcm, off, pcm.size - off)
                        if (n <= 0) break
                        off += n
                    }
                }
                try {
                    at.stop()
                    at.release()
                } catch (e: Exception) {
                }
                track = null
            } catch (e: Exception) {
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try {
            track?.pause()
            track?.flush()
        } catch (e: Exception) {
        }
        worker = null
    }

    private fun renderLoop(): ShortArray {
        val noteLen = SR * 14 / 100 // 0.14 detik per nada
        val out = ShortArray(noteLen * melody.size)
        for (i in melody.indices) {
            val f = melody[i]
            val start = i * noteLen
            for (s in 0 until noteLen) {
                val t = s.toDouble() / SR
                // gelombang sinus + harmonik kecil, dengan peluruhan lembut
                val env = 1.0 - (s.toDouble() / noteLen) * 0.6
                val v = Math.sin(2 * Math.PI * f * t) * 0.5 + Math.sin(2 * Math.PI * f * 2 * t) * 0.12
                val sample = (v * env * 9000).toInt().coerceIn(-30000, 30000)
                out[start + s] = sample.toShort()
            }
        }
        return out
    }
}
