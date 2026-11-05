package com.rezochatvoice.exceo

import android.app.Activity
import android.util.Log
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

/**
 * Login nomor HP dengan kode OTP dari Firebase Phone Authentication.
 *
 * Syarat di Firebase Console:
 * 1. Authentication > Sign-in method > Phone diaktifkan.
 * 2. Nomor uji didaftarkan (Phone numbers for testing) beserta kodenya,
 *    supaya bisa dicoba tanpa SMS dan tanpa biaya.
 * 3. Billing (Blaze) aktif untuk pengiriman SMS ke nomor asli.
 */
object PhoneAuthLogin {

    /** Token dari onCodeSent, dipakai saat user menekan "Kirim ulang". */
    private var tokenTerakhir: PhoneAuthProvider.ForceResendingToken? = null

    /**
     * Minta kode dikirim ke [nomor] (format internasional, contoh +628123456789).
     * [hasil] dipanggil dengan verificationId, atau null bila gagal.
     */
    fun mintaKode(
        activity: Activity,
        nomor: String,
        hasil: (String?, String?) -> Unit,
    ) {
        try {
            val opts = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(nomor)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        // Verifikasi otomatis: HP membaca kode sendiri. Sesi Firebase
                        // langsung dibuat; layar login akan berpindah lewat status login.
                        FirebaseAuth.getInstance().signInWithCredential(credential)
                    }

                    override fun onVerificationFailed(e: FirebaseException) {
                        Log.w("PhoneLogin", "Verifikasi gagal", e)
                        hasil(null, pesanGagal(e))
                    }

                    override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                        tokenTerakhir = token
                        hasil(id, null)
                    }
                })
                .build()
            PhoneAuthProvider.verifyPhoneNumber(opts)
        } catch (e: Exception) {
            Log.e("PhoneLogin", "Tidak bisa meminta kode", e)
            hasil(null, e.message ?: "Tidak bisa meminta kode")
        }
    }

    /**
     * Kirim ulang kode ke [nomor] memakai token dari pengiriman pertama,
     * supaya tidak dihitung permintaan baru oleh Firebase.
     */
    fun kirimUlang(
        activity: Activity,
        nomor: String,
        hasil: (String?, String?) -> Unit,
    ) {
        try {
            val builder = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(nomor)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        FirebaseAuth.getInstance().signInWithCredential(credential)
                    }

                    override fun onVerificationFailed(e: FirebaseException) {
                        Log.w("PhoneLogin", "Kirim ulang gagal", e)
                        hasil(null, pesanGagal(e))
                    }

                    override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                        tokenTerakhir = token
                        hasil(id, null)
                    }
                })
            tokenTerakhir?.let { builder.setForceResendingToken(it) }
            PhoneAuthProvider.verifyPhoneNumber(builder.build())
        } catch (e: Exception) {
            Log.e("PhoneLogin", "Tidak bisa mengirim ulang", e)
            hasil(null, e.message ?: "Tidak bisa mengirim ulang kode")
        }
    }

    /** Pesan error Firebase Phone Auth dalam bahasa yang bisa dibaca user. */
    private fun pesanGagal(e: FirebaseException): String = when (e) {
        is FirebaseAuthInvalidCredentialsException ->
            "Nomor HP tidak valid. Periksa kode negara dan nomornya."
        is FirebaseTooManyRequestsException ->
            "Terlalu banyak permintaan kode. Coba lagi beberapa saat."
        is FirebaseAuthMissingActivityForRecaptchaException ->
            "Verifikasi reCAPTCHA butuh layar aplikasi. Coba dari dalam app."
        else -> e.message ?: "Gagal mengirim kode"
    }

    /** Tukar kode OTP yang diketik user menjadi sesi aplikasi. */
    fun verifikasi(
        verificationId: String,
        kode: String,
        hasil: (com.google.firebase.auth.FirebaseUser?, String?) -> Unit,
    ) {
        try {
            val credential = PhoneAuthProvider.getCredential(verificationId, kode)
            masukDengan(credential, hasil)
        } catch (e: Exception) {
            Log.e("PhoneLogin", "Kode tidak valid", e)
            hasil(null, e.message ?: "Kode tidak valid")
        }
    }

    private fun masukDengan(
        credential: PhoneAuthCredential,
        hasil: (com.google.firebase.auth.FirebaseUser?, String?) -> Unit,
    ) {
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) hasil(task.result?.user, null)
                else hasil(null, task.exception?.message ?: "Kode tidak valid")
            }
    }
}
