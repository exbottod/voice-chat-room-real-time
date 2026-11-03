package com.example.googlelogin

import android.content.Context
import android.util.Base64

/**
 * Ikon bawaan saat manager/admin mereset profil user.
 * Gambar dibundel di dalam APK (assets/avatar) supaya tidak bergantung internet
 * dan tidak bisa hilang walau link hosting berubah.
 */
object ResetAvatar {
    private const val PRIA = "avatar/reset_male.jpg"
    private const val WANITA = "avatar/reset_female.jpg"

    /** Base64 JPEG ikon bawaan sesuai jenis kelamin profil ("M" / "F"). */
    fun b64(ctx: Context, gender: String): String? {
        val utama = if (gender.trim().uppercase() == "F") WANITA else PRIA
        baca(ctx, utama)?.let { return it }
        return baca(ctx, PRIA)
    }

    private fun baca(ctx: Context, path: String): String? = try {
        val bytes = ctx.assets.open(path).readBytes()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}
