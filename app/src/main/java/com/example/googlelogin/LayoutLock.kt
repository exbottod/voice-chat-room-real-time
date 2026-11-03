package com.example.googlelogin

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Kunci tampilan agar SAMA di semua HP: lebar sisi pendek layar selalu 360 dp
 * dan skala font selalu 1. Memakai ukuran layar FISIK sebenarnya, bukan nilai
 * displayMetrics yang bisa berubah karena "ukuran tampilan"/mode hemat/split-screen.
 */
object LayoutLock {
    private const val ACUAN_DP = 400f

    /**
     * Sisi pendek RUANG LAYAR yang benar-benar dipakai app, dalam piksel.
     * Sengaja memakai displayMetrics (ukuran jendela), bukan ukuran panel fisik:
     * di sebagian HP ukuran panel terbaca lebih besar dan membuat semua isi membesar.
     */
    private fun sisiPendekPx(ctx: Context): Int {
        return try {
            val dm = ctx.resources.displayMetrics
            minOf(dm.widthPixels, dm.heightPixels)
        } catch (e: Exception) {
            0
        }
    }

    /** Konfigurasi terkunci berdasarkan konfigurasi dasar [base]. */
    fun kunci(ctx: Context, base: Configuration): Configuration {
        val cfg = Configuration(base)
        cfg.fontScale = 1.0f
        val px = sisiPendekPx(ctx)
        if (px > 0) {
            // px per dp = px / 360, dpi = px per dp * 160
            cfg.densityDpi = (px / ACUAN_DP * 160f).toInt().coerceIn(120, 640)
        }
        return cfg
    }

    fun bungkus(ctx: Context): Context =
        ctx.createConfigurationContext(kunci(ctx, ctx.resources.configuration))
}
