package com.example.googlelogin

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object FirebaseSession {
    fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null

    /** Masuk ke Firebase memakai ID token Google. Mengembalikan UID. */
    suspend fun signIn(idToken: String): String {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = FirebaseAuth.getInstance().signInWithCredential(credential).await()
        return result.user?.uid ?: error("UID kosong")
    }

    /** Login dengan email & password (harus sudah ditambahkan lewat Pengaturan). */
    suspend fun signInEmail(email: String, password: String): FirebaseUser {
        val r = FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
        return r.user ?: error("User kosong")
    }

    /** Nama & foto dari Firestore untuk akun yang login lewat email. */
    suspend fun fetchBasic(uid: String, fallbackName: String): Pair<String, String?> {
        return try {
            val doc = FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
            val name = doc.getString("nickname")?.takeIf { it.isNotBlank() }
                ?: doc.getString("name")?.takeIf { it.isNotBlank() }
                ?: fallbackName
            name to doc.getString("photoUrl")
        } catch (e: Exception) {
            fallbackName to null
        }
    }

    fun hasPassword(): Boolean =
        FirebaseAuth.getInstance().currentUser?.providerData?.any { it.providerId == "password" } == true

    fun passwordEmail(): String? =
        FirebaseAuth.getInstance().currentUser?.providerData
            ?.firstOrNull { it.providerId == "password" }?.email

    /** Tambahkan email & password ke akun Google yang sedang login. null = sukses. */
    suspend fun linkEmailPassword(email: String, password: String): Throwable? {
        val u = FirebaseAuth.getInstance().currentUser ?: return IllegalStateException("Belum login")
        return try {
            u.linkWithCredential(EmailAuthProvider.getCredential(email, password)).await()
            null
        } catch (e: Exception) {
            Log.w("FirebaseSession", "Link email gagal", e)
            e
        }
    }

    fun friendlyError(e: Throwable): String = when (e) {
        is FirebaseAuthInvalidCredentialsException -> "Email atau password salah"
        is FirebaseAuthInvalidUserException -> "Akun dengan email ini tidak ditemukan"
        is FirebaseAuthUserCollisionException -> "Email ini sudah dipakai akun lain"
        is FirebaseAuthWeakPasswordException -> "Password terlalu lemah (minimal 6 karakter)"
        is FirebaseAuthRecentLoginRequiredException -> "Keluar lalu login Google lagi, kemudian coba lagi"
        else -> e.message ?: "Terjadi kesalahan"
    }

    /** Simpan profil ke Firestore. Gagal di sini tidak membatalkan login. */
    suspend fun saveProfile(uid: String, user: UserProfile): Throwable? {
        return try {
            val data = hashMapOf<String, Any?>(
                "name" to user.name,
                "email" to user.email,
                "photoUrl" to user.photoUrl,
                "lastLogin" to Timestamp.now(),
            )
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .set(data, SetOptions.merge())
                .await()
            null
        } catch (e: Exception) {
            Log.w("Firestore", "Simpan profil gagal", e)
            e
        }
    }

    fun signOut() = FirebaseAuth.getInstance().signOut()
}
