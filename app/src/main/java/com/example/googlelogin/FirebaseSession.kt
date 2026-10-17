package com.example.googlelogin

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
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
