package com.example.googlelogin

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/** Diisi otomatis oleh skrip dari URL repo GitHub Anda. Boleh diganti manual. */
const val INVITE_BASE_URL = "https://exbottod.github.io/voice-chat-room-real-time/"
const val APK_DOWNLOAD_URL = "https://github.com/exbottod/voice-chat-room-real-time/releases/latest/download/REZO-CHAT-VOICE.apk"
const val FIRST_USER_ID = 100000L

data class PublicUser(
    val userId: String,
    val uid: String,
    val name: String,
    val countryCode: String,
    val bio: String,
    val avatarB64: String?,
    val photoUrl: String?,
    val gender: String = "",
    val exp: Long = 0L,
    val vip: Int = 0,
    val vipUntil: Long = 0L,
    val frame: String = "",
    val bubble: String = "",
    val badge: String = "",
    val medals: String = "",
    val fam: String = "",
    val svip: String = "",
    val ad: String = "",
)

object UserDirectory {
    private fun db() = FirebaseFirestore.getInstance()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("userid", Context.MODE_PRIVATE)

    fun cachedId(ctx: Context, uid: String): String? = prefs(ctx).getString(uid, null)

    /** ID RESMI user lain (berurutan dari 100000) dari users/{uid}.userId di Firestore.
     *  Dipakai layar profil supaya UID yang tampil tidak pernah kode room / hash email. */
    suspend fun officialIdOf(uid: String): String? {
        val d = db().collection("users").document(uid).get().await()
        return d.getString("userId")?.takeIf { it.isNotBlank() }
    }

    /**
     * ID unik berurutan mulai 100000. Dibuat sekali per akun lewat transaksi Firestore
     * (counters/userIds) lalu disimpan di users/{uid} dan userIds/{id}.
     */
    suspend fun ensureUserId(ctx: Context, uid: String): String {
        cachedId(ctx, uid)?.let { return it }
        val db = db()
        val counterRef = db.collection("counters").document("userIds")
        val userRef = db.collection("users").document(uid)
        val id: String = db.runTransaction { tx ->
            val existing = tx.get(userRef).getString("userId")
            if (existing != null) {
                existing
            } else {
                val next = tx.get(counterRef).getLong("next") ?: FIRST_USER_ID
                tx.set(counterRef, hashMapOf("next" to next + 1))
                tx.set(userRef, hashMapOf("userId" to next.toString()), SetOptions.merge())
                tx.set(db.collection("userIds").document(next.toString()), hashMapOf("uid" to uid))
                next.toString()
            }
        }.await()
        prefs(ctx).edit().putString(uid, id).apply()
        return id
    }

    /** Data publik (nama, avatar, negara, bio) agar bisa dicari lewat ID. */
    suspend fun publish(ctx: Context, user: UserProfile, d: ProfileData): Throwable? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return IllegalStateException("Belum login")
        val id = cachedId(ctx, uid) ?: return IllegalStateException("ID belum siap")
        return try {
            db().collection("userIds").document(id).set(
                hashMapOf<String, Any?>(
                    "uid" to uid,
                    "name" to d.nickname.ifBlank { user.name },
                    "countryCode" to d.countryCode,
                    "bio" to d.bio,
                    "avatarB64" to d.avatarB64,
                    "photoUrl" to user.photoUrl,
                    "gender" to d.gender,
                ),
                SetOptions.merge(),
            ).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            e
        }
    }

    /** Ubah dokumen koleksi userIds menjadi PublicUser (dipakai masking ranking). */
    fun toPublic(doc: com.google.firebase.firestore.DocumentSnapshot): PublicUser = PublicUser(
        userId = doc.id,
        uid = doc.getString("uid") ?: "",
        name = doc.getString("name") ?: "Pengguna ${doc.id}",
        countryCode = doc.getString("countryCode") ?: "ID",
        bio = doc.getString("bio") ?: "",
        avatarB64 = doc.getString("avatarB64"),
        photoUrl = doc.getString("photoUrl"),
        gender = doc.getString("gender") ?: "",
        exp = doc.getLong("exp") ?: 0L,
        vip = (doc.getLong("vip") ?: 0L).toInt(),
        vipUntil = doc.getLong("vipUntil") ?: 0L,
        frame = doc.getString("frame") ?: "",
        bubble = doc.getString("bubble") ?: "",
        badge = doc.getString("badge") ?: "",
        medals = doc.getString("medals") ?: "",
        fam = doc.getString("fam") ?: "",
        svip = doc.getString("svip") ?: "",
        ad = doc.getString("ad") ?: "",
    )

    /** Cari pengguna berdasarkan ID. null = tidak ditemukan. */
    suspend fun find(id: String): PublicUser? {
        val doc = db().collection("userIds").document(id.trim()).get().await()
        if (!doc.exists()) return null
        return PublicUser(
            userId = doc.id,
            uid = doc.getString("uid") ?: "",
            name = doc.getString("name") ?: "Pengguna ${doc.id}",
            countryCode = doc.getString("countryCode") ?: "ID",
            bio = doc.getString("bio") ?: "",
            avatarB64 = doc.getString("avatarB64"),
            photoUrl = doc.getString("photoUrl"),
            gender = doc.getString("gender") ?: "",
            exp = doc.getLong("exp") ?: 0L,
            vip = (doc.getLong("vip") ?: 0L).toInt(),
            vipUntil = doc.getLong("vipUntil") ?: 0L,
            frame = doc.getString("frame") ?: "",
            bubble = doc.getString("bubble") ?: "",
            badge = doc.getString("badge") ?: "",
            medals = doc.getString("medals") ?: "",
            fam = doc.getString("fam") ?: "",
            svip = doc.getString("svip") ?: "",
            ad = doc.getString("ad") ?: "",
        )
    }

    suspend fun findByUid(uid: String): PublicUser? {
        val q = db().collection("userIds").whereEqualTo("uid", uid).limit(1).get().await()
        val doc = q.documents.firstOrNull() ?: return null
        return PublicUser(
            userId = doc.id,
            uid = doc.getString("uid") ?: "",
            name = doc.getString("name") ?: "Pengguna ${doc.id}",
            countryCode = doc.getString("countryCode") ?: "ID",
            bio = doc.getString("bio") ?: "",
            avatarB64 = doc.getString("avatarB64"),
            photoUrl = doc.getString("photoUrl"),
            gender = doc.getString("gender") ?: "",
            exp = doc.getLong("exp") ?: 0L,
            vip = (doc.getLong("vip") ?: 0L).toInt(),
            vipUntil = doc.getLong("vipUntil") ?: 0L,
            frame = doc.getString("frame") ?: "",
            bubble = doc.getString("bubble") ?: "",
            badge = doc.getString("badge") ?: "",
            medals = doc.getString("medals") ?: "",
            fam = doc.getString("fam") ?: "",
        )
    }
    
/** Simpan label family di profil publik (dipanggil saat create/join/leave family). */
    suspend fun setFam(uid: String, fam: String): String? = try {
        val q = db().collection("userIds").whereEqualTo("uid", uid).limit(1).get().await()
        val doc = q.documents.firstOrNull() ?: return null
        db().collection("userIds").document(doc.id).set(
            hashMapOf<String, Any?>("fam" to fam), SetOptions.merge(),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }
    
    // ---------- Undangan ----------

    suspend fun invitedBy(uid: String): String? =
        db().collection("invites").document(uid).get().await().getString("inviterId")

    suspend fun inviteCount(uid: String): Long =
        db().collection("invites").whereEqualTo("inviterUid", uid)
            .count().get(AggregateSource.SERVER).await().count

    /** Pakai kode undangan (= ID pengundang). Mengembalikan pesan error, null jika sukses. */
    suspend fun redeemInvite(myUid: String, myId: String, code: String): String? {
        val c = code.trim()
        if (c.length < 6 || !c.all { it.isDigit() }) return "Kode tidak valid"
        if (c == myId) return "Tidak bisa memakai kode sendiri"
        return try {
            val mine = db().collection("invites").document(myUid).get().await()
            if (mine.exists()) return "Kamu sudah pernah memakai kode undangan"
            val inviter = db().collection("userIds").document(c).get().await()
            val inviterUid = inviter.getString("uid") ?: return "Kode undangan tidak ditemukan"
            val batch = db().batch()
            batch.set(
                db().collection("invites").document(myUid),
                hashMapOf<String, Any?>(
                    "inviterUid" to inviterUid,
                    "inviterId" to c,
                    "inviteeId" to myId,
                    "createdAt" to Timestamp.now(),
                ),
            )
            batch.commit().await()
            // Hadiah undangan masuk dompet Supabase (dompet tidak lagi di Firestore).
            WalletRepo.topUp(myUid, INVITE_REWARD_COINS)
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            "Gagal: ${e.message}"
        }
    }
}
