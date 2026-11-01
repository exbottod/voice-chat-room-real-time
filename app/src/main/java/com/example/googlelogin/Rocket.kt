package com.example.googlelogin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Fitur Rocket: level roket naik dari total koin yang dipakai mengirim gift.
 * Semua angka dan aset bisa diatur di Realtime Database node "rocketCfg" tanpa
 * perlu build ulang aplikasi:
 *
 *   rocketCfg/exp1 .. exp5   = syarat EXP tiap level (angka)
 *   rocketCfg/img1 .. img5   = URL / emoji roket tiap level
 *   rocketCfg/rw1 .. rw6     = hadiah grid 3x2 (URL gambar atau emoji)
 *
 * Progres pemain disimpan di rocket/{uid}/exp.
 */
object RocketCfg {
    // Ambang EXP bawaan: Lv1 0, Lv2 250K, Lv3 20,2M, Lv4 100M, Lv5 500M.
    val defaultExp = listOf(0L, 250_000L, 20_200_000L, 100_000_000L, 500_000_000L)
    val defaultImg = listOf("\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80", "\uD83D\uDE80")
    val defaultRewards = listOf("\uD83C\uDFC5", "\uD83D\uDC51", "\uD83D\uDCA5", "\uD83D\uDE97", "\uD83D\uDEF8", "\uD83C\uDFAB")

    var exp by mutableStateOf(defaultExp)
    var img by mutableStateOf(defaultImg)
    var rewards by mutableStateOf(defaultRewards)

    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            Rtdb.listen(Rtdb.ref("rocketCfg")) { s ->
                val e = defaultExp.toMutableList()
                for (i in 1..5) {
                    val v = s.child("exp$i").getValue(Long::class.java)
                    if (v != null && v >= 0L) e[i - 1] = v
                }
                exp = e.toList()
                val im = defaultImg.toMutableList()
                for (i in 1..5) {
                    s.child("img$i").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { im[i - 1] = it }
                }
                img = im.toList()
                val rw = defaultRewards.toMutableList()
                for (i in 1..6) {
                    s.child("rw$i").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { rw[i - 1] = it }
                }
                rewards = rw.toList()
            }
        } catch (e: Exception) {
            // pakai nilai bawaan
        }
    }

    /** Level (1..5) menurut total EXP. */
    fun levelOf(total: Long): Int {
        var lv = 1
        for (i in exp.indices) if (total >= exp[i]) lv = i + 1
        return lv.coerceIn(1, exp.size)
    }
}

object RocketRepo {
    /** EXP roket pemain, disimpan di memori supaya panel langsung tampil benar. */
    var exp by mutableStateOf(0L)
        private set

    private var live = false

    fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    /** Dengarkan EXP sekali saja; panel yang dibuka belakangan langsung dapat nilai terakhir. */
    fun start() {
        if (live) return
        val u = uid()
        if (u.isBlank()) return
        live = true
        try {
            Rtdb.listen(Rtdb.ref("rocket/$u")) { s ->
                val v = s.child("exp").getValue(Long::class.java) ?: 0L
                if (v > exp) exp = v
            }
        } catch (e: Exception) {
            live = false
        }
        // Cadangan: kalau aturan database belum mengizinkan node "rocket",
        // EXP tetap tersimpan di node pub milik sendiri.
        try {
            Rtdb.listen(Rtdb.ref("pub/$u/rocketExp")) { s ->
                val v = s.getValue(Long::class.java) ?: 0L
                if (v > exp) exp = v
            }
        } catch (e: Exception) {
        }
    }

    /** Tambah EXP roket dari koin yang dipakai untuk gift. */
    suspend fun addExp(coins: Long) {
        val u = uid()
        if (u.isBlank() || coins <= 0L) return
        exp += coins
        val total = exp
        val saved = try {
            Rtdb.ref("rocket/$u/exp").setValue(total).await()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!saved) {
            try {
                Rtdb.ref("pub/$u/rocketExp").setValue(total).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }
}
