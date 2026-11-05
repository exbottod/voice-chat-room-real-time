package com.example.googlelogin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Login Facebook.
 *
 * App ID dan Client Token diambil dari res/values/strings.xml
 * (facebook_app_id, facebook_client_token) yang nilainya diambil dari
 * Facebook Developer Console > Settings > Basic.
 *
 * SDK sengaja TIDAK auto-init (lihat AndroidManifest) supaya aplikasi tetap
 * jalan normal kalau App ID belum diisi. Inisialisasi dilakukan di sini hanya
 * saat tombol Facebook benar-benar dipakai.
 */
object FbLogin {
    private var callbackManager: CallbackManager? = null
    private var sudahInit = false

    /** App ID Facebook yang terpasang di aplikasi. */
    fun appId(ctx: Context): String =
        try {
            ctx.getString(R.string.facebook_app_id).trim()
        } catch (e: Exception) {
            ""
        }

    /** True kalau App ID dan Client Token sudah diisi (bukan placeholder). */
    fun terkonfigurasi(ctx: Context): Boolean {
        val id = appId(ctx)
        val token = try {
            ctx.getString(R.string.facebook_client_token).trim()
        } catch (e: Exception) {
            ""
        }
        return id.length >= 10 && id.all { it.isDigit() } && id.any { it != '0' } &&
            token.isNotBlank() && !token.startsWith("GANTI")
    }

    private fun siapkan(ctx: Context): Boolean {
        if (!terkonfigurasi(ctx)) return false
        if (!sudahInit) {
            try {
                FacebookSdk.setApplicationId(appId(ctx))
                FacebookSdk.setClientToken(ctx.getString(R.string.facebook_client_token).trim())
                // Tanpa iklan: tidak perlu event otomatis.
                FacebookSdk.setAutoLogAppEventsEnabled(false)
                FacebookSdk.setAdvertiserIDCollectionEnabled(false)
                FacebookSdk.sdkInitialize(ctx.applicationContext)
                sudahInit = true
            } catch (e: Exception) {
                Log.e("FbLogin", "Gagal inisialisasi SDK Facebook", e)
                return false
            }
        }
        return true
    }

    /** Meneruskan hasil dari FacebookActivity. Panggil di onActivityResult MainActivity. */
    fun hasil(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return try {
            callbackManager?.onActivityResult(requestCode, resultCode, data) ?: false
        } catch (e: Exception) {
            Log.w("FbLogin", "onActivityResult gagal", e)
            false
        }
    }

    /**
     * Buka dialog Facebook lalu kembalikan access token.
     * Melempar IllegalStateException berisi pesan siap tampil kalau gagal.
     */
    suspend fun mintaToken(activity: Activity): String = suspendCancellableCoroutine { cont ->
        if (!siapkan(activity)) {
            cont.resumeWithException(
                IllegalStateException("App ID Facebook belum diisi di app"),
            )
            return@suspendCancellableCoroutine
        }
        val cm = CallbackManager.Factory.create()
        callbackManager = cm
        LoginManager.getInstance().registerCallback(
            cm,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    val t = result.accessToken?.token
                    if (cont.isActive) {
                        if (t.isNullOrBlank()) {
                            cont.resumeWithException(IllegalStateException("Token Facebook kosong"))
                        } else {
                            cont.resume(t)
                        }
                    }
                }

                override fun onCancel() {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Login Facebook dibatalkan"))
                }

                override fun onError(error: FacebookException) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException(error.message ?: "Login Facebook gagal"),
                        )
                    }
                }
            },
        )
        try {
            LoginManager.getInstance().logInWithReadPermissions(
                activity,
                listOf("public_profile", "email"),
            )
        } catch (e: Exception) {
            if (cont.isActive) {
                cont.resumeWithException(IllegalStateException(e.message ?: "Facebook tidak bisa dibuka"))
            }
        }
    }

    /** Keluar dari sesi Facebook di perangkat (dipanggil saat logout aplikasi). */
    fun keluar() {
        try {
            LoginManager.getInstance().logOut()
        } catch (e: Exception) {
            Log.w("FbLogin", "Logout Facebook gagal", e)
        }
    }
}
