package com.example.googlelogin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Data profil yang bisa diedit. gender: "M", "F", atau "" (belum diatur). */
data class ProfileData(
    val nickname: String = "",
    val gender: String = "",
    val countryCode: String = "ID",
    val birthday: String = "",
    val bio: String = "",
    val avatarB64: String? = null,
    val backgroundB64: String? = null,
)

data class Country(val code: String, val name: String) {
    val flag: String get() = flagEmoji(code)
}

fun flagEmoji(code: String): String {
    if (code.length != 2) return "🏳️"
    val up = code.uppercase(Locale.US)
    val a = Character.codePointAt(up, 0) - 'A'.code + 0x1F1E6
    val b = Character.codePointAt(up, 1) - 'A'.code + 0x1F1E6
    return String(Character.toChars(a)) + String(Character.toChars(b))
}

fun userIdFrom(email: String): String {
    val n = (email.lowercase().hashCode().toLong() and 0x7FFFFFFFL) % 90000000L + 10000000L
    return n.toString()
}

object ProfileRepo {
    private const val PREFS = "profile"

    val countries: List<Country> by lazy {
        val id = Locale("id")
        Locale.getISOCountries()
            .map { Country(it, Locale("", it).getDisplayCountry(id)) }
            .filter { it.name.isNotBlank() }
            .sortedBy { it.name }
    }

    fun countryName(code: String): String =
        countries.firstOrNull { it.code == code }?.name ?: code

    // ---------- Penyimpanan lokal (cache) ----------

    fun loadLocal(ctx: Context, email: String): ProfileData {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun k(n: String) = "$email|$n"
        return ProfileData(
            nickname = p.getString(k("nickname"), "") ?: "",
            gender = p.getString(k("gender"), "") ?: "",
            countryCode = p.getString(k("country"), "ID") ?: "ID",
            birthday = p.getString(k("birthday"), "") ?: "",
            bio = p.getString(k("bio"), "") ?: "",
            avatarB64 = p.getString(k("avatar"), null),
            backgroundB64 = p.getString(k("background"), null),
        )
    }

    fun saveLocal(ctx: Context, email: String, d: ProfileData) {
        fun k(n: String) = "$email|$n"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(k("nickname"), d.nickname)
            .putString(k("gender"), d.gender)
            .putString(k("country"), d.countryCode)
            .putString(k("birthday"), d.birthday)
            .putString(k("bio"), d.bio)
            .putString(k("avatar"), d.avatarB64)
            .putString(k("background"), d.backgroundB64)
            .apply()
    }

    // ---------- Firestore: users/{uid} ----------

    suspend fun loadRemote(): ProfileData? {
        return try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
            val doc = FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
            if (!doc.exists() || doc.getBoolean("profileSaved") != true) return null
            ProfileData(
                nickname = doc.getString("nickname") ?: "",
                gender = doc.getString("gender") ?: "",
                countryCode = doc.getString("countryCode") ?: "ID",
                birthday = doc.getString("birthday") ?: "",
                bio = doc.getString("bio") ?: "",
                avatarB64 = doc.getString("avatarB64"),
                backgroundB64 = doc.getString("backgroundB64"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    suspend fun saveRemote(d: ProfileData): Throwable? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return IllegalStateException("Belum login")
        return try {
            val data = hashMapOf<String, Any?>(
                "profileSaved" to true,
                "nickname" to d.nickname,
                "gender" to d.gender,
                "countryCode" to d.countryCode,
                "birthday" to d.birthday,
                "bio" to d.bio,
                "avatarB64" to d.avatarB64,
                "backgroundB64" to d.backgroundB64,
            )
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .set(data, SetOptions.merge()).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            e
        }
    }

    // ---------- Gambar ----------

    fun decodeB64(b64: String?): ImageBitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }

    /**
     * Baca gambar dari galeri, perbaiki rotasi, (opsional) crop persegi,
     * perkecil, lalu jadikan JPEG base64 supaya muat di dokumen Firestore (<1MB).
     */
    suspend fun encodeImage(
        ctx: Context,
        uri: Uri,
        maxSide: Int,
        square: Boolean,
        quality: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null

            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            }

            var x = 0
            var y = 0
            var w = src.width
            var h = src.height
            if (square) {
                val side = min(w, h)
                x = (w - side) / 2
                y = (h - side) / 2
                w = side
                h = side
            }
            val scale = maxSide.toFloat() / max(w, h)
            if (scale < 1f) m.postScale(scale, scale)

            val out: Bitmap = Bitmap.createBitmap(src, x, y, w, h, m, true)
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            null
        }
    }
}
