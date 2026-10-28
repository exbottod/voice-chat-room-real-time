package com.example.googlelogin

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
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
    val svipLv: Int = 0,
    val svipUntil: Long = 0,
)

/** Level SVIP efektif: SVIP yang dibeli (Realtime Database) atau level VIP dompet, mana yang lebih tinggi. */
fun PublicUser.svipLevel(): Int {
    val a = if (vipActive(svipLv, svipUntil)) svipLv else 0
    val b = if (vipActive(vip, vipUntil)) vip else 0
    return maxOf(a, b)
}


object UserDirectory {
    private fun db() = FirebaseFirestore.getInstance()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("userid", Context.MODE_PRIVATE)

    fun cachedId(ctx: Context, uid: String): String? = prefs(ctx).getString(uid, null)

    /** ID RESMI user lain (berurutan dari 100000) dari users/{uid}.userId di Firestore.
     *  Dipakai layar profil supaya UID yang tampil tidak pernah kode room / hash email. */
    suspend fun officialIdOf(uid: String): String? {
        rtdbOfficialId(uid)?.let { return it }
        try {
            val d = db().collection("users").document(uid).get().await()
            val fs = d.getString("userId")?.takeIf { it.isNotBlank() }
            if (fs != null) return fs
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Cadangan: ID tersimpan di Realtime Database.
        return try {
            val s = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("ids/u/$uid").get().await() }
            (s?.value as? String)?.takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { null }
    }

    /**
     * ID unik berurutan mulai 100000. Kini dibuat lewat REALTIME DATABASE
     * (transaksi ids/u/{uid}, ids/i/{id}, ids/next) sehingga daftar akun
     * tidak terganggu kuota Firestore. User lama yang sudah punya ID di
     * Firestore tetap memakai ID lamanya.
     */
    suspend fun ensureUserId(ctx: Context, uid: String): String {
        cachedId(ctx, uid)?.let { return it }
        // 1) User lama: ID yang sudah ada di Firestore tetap dipakai.
        try {
            val d = kotlinx.coroutines.withTimeoutOrNull(4000L) {
                db().collection("users").document(uid).get().await()
            }
            val fsId = d?.getString("userId")?.takeIf { it.isNotBlank() }
            if (fsId != null) {
                prefs(ctx).edit().putString(uid, fsId).apply()
                mirrorIdToRtdb(uid, fsId)
                publishPubId(uid, fsId)
                return fsId
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // 2) ID baru dibuat di Realtime Database.
        val id = rtdbEnsureId(uid)
        prefs(ctx).edit().putString(uid, id).apply()
        publishPubId(uid, id)
        // 3) Cermin best-effort ke Firestore agar fitur lama tetap jalan.
        try {
            kotlinx.coroutines.withTimeoutOrNull(4000L) {
                db().collection("users").document(uid).set(
                    hashMapOf("userId" to id), SetOptions.merge(),
                ).await()
                db().collection("userIds").document(id).set(
                    hashMapOf("uid" to uid), SetOptions.merge(),
                ).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        return id
    }

    /** Tulis ID ke pub/{uid}/id: dibaca layar room & profil orang lain. */
    private suspend fun publishPubId(uid: String, id: String) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(4000L) {
                Rtdb.ref("pub/$uid/id").setValue(id).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
    }

    /** ID resmi user dari Realtime Database: ids/u/{uid}, lalu pub/{uid}/id. */
    suspend fun rtdbOfficialId(uid: String): String? {
        try {
            val a = kotlinx.coroutines.withTimeoutOrNull(3000L) { Rtdb.ref("ids/u/$uid").get().await() }
            (a?.value as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            val b = kotlinx.coroutines.withTimeoutOrNull(3000L) { Rtdb.ref("pub/$uid/id").get().await() }
            (b?.value as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        return null
    }

    /** Simpan ID yang sudah ada ke Realtime Database (ids/u, ids/i). */
    private suspend fun mirrorIdToRtdb(uid: String, id: String) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(4000L) {
                Rtdb.ref("ids/u/$uid").setValue(id).await()
                Rtdb.ref("ids/i/$id/uid").setValue(uid).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
    }

    /** Transaksi pembuatan ID di Realtime Database (seed dari counter Firestore lama). */
    private suspend fun rtdbEnsureId(uid: String): String {
        val seed: Long = try {
            val c = kotlinx.coroutines.withTimeoutOrNull(4000L) {
                db().collection("counters").document("userIds").get().await()
            }
            (c?.getLong("next") ?: FIRST_USER_ID).coerceAtLeast(FIRST_USER_ID)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { FIRST_USER_ID }
        return suspendCancellableCoroutine { cont ->
            Rtdb.ref("ids").runTransaction(object : com.google.firebase.database.Transaction.Handler {
                override fun doTransaction(current: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                    val mine = current.child("u").child(uid).value
                    if (mine == null) {
                        val next = (current.child("next").value as? Long) ?: seed
                        current.child("next").value = next + 1
                        current.child("u").child(uid).value = next.toString()
                        current.child("i").child(next.toString()).value = uid
                    }
                    return com.google.firebase.database.Transaction.success(current)
                }
                override fun onComplete(
                    err: com.google.firebase.database.DatabaseError?,
                    committed: Boolean,
                    current: com.google.firebase.database.DataSnapshot?,
                ) {
                    if (!cont.isActive) return
                    val v = current?.child("u")?.child(uid)?.value
                    if (err == null && v != null) cont.resume(v.toString())
                    else cont.resumeWithException(err?.toException() ?: IllegalStateException("Gagal membuat ID, coba lagi"))
                }
            })
        }
    }

    /** Data publik (nama, avatar, negara, bio) agar bisa dicari lewat ID. */
    suspend fun publish(ctx: Context, user: UserProfile, d: ProfileData): Throwable? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return IllegalStateException("Belum login")
        val id = try {
            cachedId(ctx, uid) ?: ensureUserId(ctx, uid)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return e }
        val data = hashMapOf<String, Any?>(
            "uid" to uid,
            "name" to d.nickname.ifBlank { user.name },
            "countryCode" to d.countryCode,
            "bio" to d.bio,
            "avatarB64" to d.avatarB64,
            "photoUrl" to user.photoUrl,
            "gender" to d.gender,
        )
        // REALTIME DATABASE: profil publik utama (daftar akun tidak bergantung Firestore).
        try {
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                Rtdb.ref("ids/i/$id").updateChildren(data).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return e
        }
        // Cermin best-effort ke Firestore untuk fitur cari lama.
        try {
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                db().collection("userIds").document(id).set(data, SetOptions.merge()).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        return null
    }

    /** Profil publik user dari Realtime Database (ids/i/{id}). */
    private fun rtdbToPublic(id: String, s: com.google.firebase.database.DataSnapshot): PublicUser = PublicUser(
        userId = id,
        uid = (s.child("uid").value as? String) ?: "",
        name = (s.child("name").value as? String)?.takeIf { it.isNotBlank() } ?: "Pengguna $id",
        countryCode = (s.child("countryCode").value as? String) ?: "ID",
        bio = (s.child("bio").value as? String) ?: "",
        avatarB64 = s.child("avatarB64").value as? String,
        photoUrl = s.child("photoUrl").value as? String,
        gender = (s.child("gender").value as? String) ?: "",
    )

    /** ID user dari Realtime Database (ids/u/{uid}); null kalau belum ada. */
    private suspend fun rtdbIdOf(uid: String): String? = try {
        kotlinx.coroutines.withTimeoutOrNull(3000L) { Rtdb.ref("ids/u/$uid").get().await() }?.value as? String
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

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

    /** Cari pengguna berdasarkan ID (Realtime Database dulu, Firestore cadangan). null = tidak ditemukan. */
    suspend fun find(id: String): PublicUser? {
        val key = id.trim()
        try {
            val s = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("ids/i/$key").get().await() }
            if (s != null && s.exists()) return rtdbToPublic(key, s)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        val doc = db().collection("userIds").document(key).get().await()
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
        try {
            val q = db().collection("userIds").whereEqualTo("uid", uid).limit(1).get().await()
            val doc = q.documents.firstOrNull()
            if (doc != null) return PublicUser(
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Cadangan Realtime Database: cari ID lewat ids/u lalu muat profilnya.
        val rid = rtdbIdOf(uid) ?: return null
        return find(rid)
    }

    /** Simpan label family di profil publik (dipanggil saat create/join/leave family). */
    suspend fun setFam(uid: String, fam: String): String? {
        // Realtime Database: label family di profil publik RTDB.
        try {
            val rid = rtdbIdOf(uid)
            if (rid != null) kotlinx.coroutines.withTimeoutOrNull(3000L) {
                Rtdb.ref("ids/i/$rid/fam").setValue(fam).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        return try {
        val q = db().collection("userIds").whereEqualTo("uid", uid).limit(1).get().await()
        val doc = q.documents.firstOrNull() ?: return null
        db().collection("userIds").document(doc.id).set(
            hashMapOf<String, Any?>("fam" to fam), SetOptions.merge(),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }
    }
    
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
            val inviterUid = try {
                db().collection("userIds").document(c).get().await().getString("uid")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                ?: try {
                    val s = kotlinx.coroutines.withTimeoutOrNull(3000L) { Rtdb.ref("ids/i/$c/uid").get().await() }
                    s?.value as? String
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                ?: return "Kode undangan tidak ditemukan"
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
