package com.rezochatvoice.exceo

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
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
    /** Item Unique ID yang dipakai akun ini (id "sx_..."), tampil menggantikan angka ID. */
    val uidItem: String = "",
)

/** Level SVIP efektif: SVIP yang dibeli (Realtime Database) atau level VIP dompet, mana yang lebih tinggi. */
fun PublicUser.svipLevel(): Int {
    val a = if (vipActive(svipLv, svipUntil)) svipLv else 0
    val b = if (vipActive(vip, vipUntil)) vip else 0
    return maxOf(a, b)
}


object UserDirectory {
    /** Email pemilik aplikasi; akun ini selalu memakai ID 100001. */
    private const val OWNER_EMAIL = "developerex100@gmail.com"

    /** ID tetap milik pemilik (sekaligus akun Customer Service). */
    const val ID_OWNER = "100001"

    private fun emailSekarang(): String = try {
        FirebaseAuth.getInstance().currentUser?.email?.lowercase() ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Rapikan catatan ID satu user supaya [ids/u], [ids/i], dan [pub/{uid}/id]
     * selalu cocok. Tidak pernah membuat nomor baru, jadi aman dipanggil setiap
     * aplikasi dibuka. Akun pemilik dipaksa memakai ID 100001.
     */
    suspend fun perbaikiId(ctx: Context, uid: String): String {
        if (uid.isBlank()) return ""
        // Cache ID di HP bisa basi (mis. masih 100876). Bandingkan dengan
        // server dulu; kalau beda, server yang menang.
        try {
            val (pasti, dariServer) = rtdbIdPasti(uid)
            if (pasti && !dariServer.isNullOrBlank() && cachedId(ctx, uid) != dariServer) {
                prefs(ctx).edit().putString(uid, dariServer).apply()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        val sekarang = ensureUserId(ctx, uid)
        // Pemilik dikenali dari email developer ATAU dari penanda _owner/uid di
        // database (diisi lewat panel developer), jadi tidak bergantung email.
        val pemilik = emailSekarang() == OWNER_EMAIL || try {
            kotlinx.coroutines.withTimeoutOrNull(4000L) {
                Rtdb.ref("_owner/uid").get().await().value as? String
            } == uid
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        val target = if (pemilik) ID_OWNER else sekarang
        if (target.isBlank()) return sekarang
        prefs(ctx).edit().putString(uid, target).apply()
        try {
            Rtdb.root().updateChildren(
                hashMapOf<String, Any?>(
                    "ids/u/$uid" to target,
                    "ids/i/$target/uid" to uid,
                    "pub/$uid/id" to target,
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        // Cermin ke Firestore, tidak menahan tampilan.
        mirrorScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(4000L) {
                    db().collection("users").document(uid).set(
                        hashMapOf("userId" to target), SetOptions.merge(),
                    ).await()
                    db().collection("userIds").document(target).set(
                        hashMapOf("uid" to uid), SetOptions.merge(),
                    ).await()
                }
            } catch (e: Exception) {
            }
        }
        return target
    }

    private val mirrorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
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
        cachedId(ctx, uid)?.takeIf { it.isNotBlank() && it != "..." }?.let { return it }
        // 1) Sudah punya ID di Realtime Database? Pakai itu (cepat, tanpa Firestore).
        //    Kalau ada beberapa nomor untuk uid ini (pernah dapat ID baru karena
        //    bacaan gagal), nomor paling lama yang dipakai supaya ID tidak berubah.
        val (pasti, idRtdb) = rtdbIdPasti(uid)
        if (idRtdb != null) {
            prefs(ctx).edit().putString(uid, idRtdb).apply()
            publishPubId(uid, idRtdb)
            rtdbPulihkanId(ctx, uid)?.let { if (it != idRtdb) return it }
            return idRtdb
        }
        rtdbPulihkanId(ctx, uid)?.let { return it }
        // 2) User lama: ID yang sudah ada di Firestore tetap dipakai (hanya cadangan, timeout singkat).
        try {
            val d = kotlinx.coroutines.withTimeoutOrNull(2500L) {
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
        // Kalau bacaan Realtime Database gagal (bukan "memang belum punya"),
        // JANGAN buat nomor baru. Cukup kosongkan dulu; nanti dicoba lagi.
        if (!pasti) return ""
        // 3) Akun baru: ID dibuat di Realtime Database.
        val id = rtdbEnsureId(uid)
        if (id.isBlank()) return ""
        prefs(ctx).edit().putString(uid, id).apply()
        publishPubId(uid, id)
        // 4) Cermin best-effort ke Firestore di BELAKANG layar (tidak menahan tampilan ID).
        mirrorScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(4000L) {
                    db().collection("users").document(uid).set(
                        hashMapOf("userId" to id), SetOptions.merge(),
                    ).await()
                    db().collection("userIds").document(id).set(
                        hashMapOf("uid" to uid), SetOptions.merge(),
                    ).await()
                }
            } catch (e: Exception) { }
        }
        return id
    }

    /**
     * Baca ID dari Realtime Database dengan status kepastian.
     * first = true artinya server sudah menjawab (ada isinya atau memang kosong),
     * jadi aman untuk memutuskan membuat ID baru. first = false artinya bacaan
     * gagal/waktu habis, sehingga ID TIDAK boleh dibuat ulang.
     */
    private suspend fun rtdbIdPasti(uid: String): Pair<Boolean, String?> {
        return try {
            val a = kotlinx.coroutines.withTimeoutOrNull(4000L) {
                Rtdb.ref("ids/u/$uid").get().await()
            } ?: return Pair(false, null)
            val v = a.value?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            if (v != null) Pair(true, v) else Pair(true, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    /** Semua nomor ID yang pernah tercatat untuk satu uid (urut dari yang paling kecil). */
    private suspend fun rtdbSemuaId(uid: String): List<String> {
        return try {
            val q = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                Rtdb.ref("ids/i").orderByChild("uid").equalTo(uid).get().await()
            } ?: return emptyList()
            q.children.mapNotNull { c ->
                c.key?.takeIf { k -> k.isNotEmpty() && k.all { it.isDigit() } }
            }.sortedBy { it.toLongOrNull() ?: Long.MAX_VALUE }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Pakai ID paling lama milik uid. Kalau perangkat sempat gagal membaca
     * Realtime Database, akun bisa dapat nomor baru (mis. 100876). Nomor lama
     * masih tersimpan di ids/i, jadi dikembalikan supaya ID tidak berubah-ubah.
     */
    private suspend fun rtdbPulihkanId(ctx: Context, uid: String): String? {
        val semua = rtdbSemuaId(uid)
        if (semua.isEmpty()) return null
        val pilih = semua.first()
        try {
            Rtdb.root().updateChildren(
                hashMapOf<String, Any?>(
                    "ids/u/$uid" to pilih,
                    "ids/i/$pilih/uid" to uid,
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        prefs(ctx).edit().putString(uid, pilih).apply()
        publishPubId(uid, pilih)
        return pilih
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

    /**
     * Kebalikan dari ID resmi: cari UID Firebase dari ID angka (mis. 100129).
     * Dipakai panel manager/admin supaya coin dikirim ke AKUN asli, bukan ke
     * dompet palsu bernama "100129".
     */
    suspend fun uidFromId(id: String): String? {
        val n = id.trim()
        if (!n.all { it.isDigit() }) return n.takeIf { it.isNotBlank() }
        try {
            val s = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("ids/i/$n/uid").get().await() }
            val v = s?.value
            if (v is String && v.isNotBlank()) return v
            if (v is Map<*, *>) (v["uid"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Cadangan 1: pemetaan terbalik ids/u/{uid} = id (akun yang terdaftar dari sisi uid).
        try {
            val snap = kotlinx.coroutines.withTimeoutOrNull(6000L) { Rtdb.ref("ids/u").get().await() }
            if (snap != null && snap.exists()) {
                var hit: String? = null
                snap.children.forEach { c ->
                    val v = c.value
                    val idStr = when (v) {
                        is Map<*, *> -> (v["id"] ?: v["n"])?.toString()
                        null -> null
                        else -> v.toString()
                    }?.trim()
                    if (idStr == n || idStr?.toLongOrNull() == n.toLongOrNull()) hit = c.key
                }
                if (hit != null) {
                    // Perbaiki catatan ids/i supaya pencarian berikutnya langsung cepat.
                    try { Rtdb.ref("ids/i/$n/uid").setValue(hit).await() } catch (e: Exception) { }
                    return hit
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Cadangan 2 dulu membaca SELURUH node "pub" hanya untuk mencari satu
        // ID. Node pub menyimpan foto profil base64 semua user, jadi satu
        // pencarian bisa mengunduh ratusan MB dan menghabiskan kuota database.
        // Pencarian sekarang cukup lewat ids/u dan Firestore.
        // Cadangan terakhir: data lama di Firestore.
        try {
            val d = kotlinx.coroutines.withTimeoutOrNull(4000L) { db().collection("userIds").document(n).get().await() }
            (d?.getString("uid"))?.takeIf { it.isNotBlank() }?.let { return it }
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

    /**
     * Ambil nomor ID bekas akun yang dihapus (node ids/free) supaya nomor urut
     * tidak bolong. Transaksi memilih nomor terkecil lalu menghapusnya.
     */
    private suspend fun rtdbClaimFreedId(): String? {
        var diklaim: String? = null
        return try {
            val hasil: Boolean = suspendCancellableCoroutine { cont ->
                Rtdb.ref("ids/free").runTransaction(object : com.google.firebase.database.Transaction.Handler {
                    override fun doTransaction(cur: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                        var best: String? = null
                        var bestN = Long.MAX_VALUE
                        cur.children.forEach { c ->
                            val k = c.key ?: return@forEach
                            val n = k.toLongOrNull() ?: return@forEach
                            if (n >= FIRST_USER_ID && n < bestN) { best = k; bestN = n }
                        }
                        val ambil = best
                        if (ambil == null) return com.google.firebase.database.Transaction.abort()
                        diklaim = ambil
                        cur.child(ambil).value = null
                        return com.google.firebase.database.Transaction.success(cur)
                    }

                    override fun onComplete(
                        err: com.google.firebase.database.DatabaseError?,
                        committed: Boolean,
                        snap: com.google.firebase.database.DataSnapshot?,
                    ) {
                        if (!cont.isActive) return
                        cont.resume(err == null && committed)
                    }
                })
            }
            if (hasil) diklaim else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Buat ID di Realtime Database. Transaksi HANYA di ids/next (izin tulis ada di
     * rules), lalu ids/u/{uid} + ids/i/{id} ditulis per-path. Tidak lagi memakai
     * transaksi di node induk "ids" yang ditolak rules (PERMISSION_DENIED).
     */
    private suspend fun rtdbEnsureId(uid: String): String {
        // Sudah punya ID? pakai itu.
        rtdbOfficialId(uid)?.let { return it }
        // Pakai dulu nomor bekas akun yang dihapus, supaya ID tetap urut.
        try {
            val bekas = rtdbClaimFreedId()
            if (bekas != null) {
                val terpakai = Rtdb.ref("ids/i/$bekas/uid").get().await().value
                if (terpakai == null) {
                    Rtdb.root().updateChildren(
                        hashMapOf<String, Any?>(
                            "ids/u/$uid" to bekas,
                            "ids/i/$bekas/uid" to uid,
                            "pub/$uid/id" to bekas,
                        ),
                    ).await()
                    return bekas
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        val seed: Long = try {
            val c = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                db().collection("counters").document("userIds").get().await()
            }
            (c?.getLong("next") ?: FIRST_USER_ID).coerceAtLeast(FIRST_USER_ID)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { FIRST_USER_ID }
        var lastErr: Exception? = null
        repeat(8) {
            try {
                val claimed: Long = suspendCancellableCoroutine { cont ->
                    Rtdb.ref("ids/next").runTransaction(object : com.google.firebase.database.Transaction.Handler {
                        override fun doTransaction(cur: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                            val n = (cur.value as? Long) ?: seed
                            cur.value = n + 1
                            return com.google.firebase.database.Transaction.success(cur)
                        }
                        override fun onComplete(
                            err: com.google.firebase.database.DatabaseError?,
                            committed: Boolean,
                            snap: com.google.firebase.database.DataSnapshot?,
                        ) {
                            if (!cont.isActive) return
                            val v = (snap?.value as? Long)
                            if (err == null && committed && v != null) cont.resume(v - 1)
                            else cont.resumeWithException(err?.toException() ?: IllegalStateException("Gagal mengambil nomor ID"))
                        }
                    })
                }
                val idStr = claimed.toString()
                // Pastikan nomor belum dipakai orang lain (ids/i/{id}).
                val taken = Rtdb.ref("ids/i/$idStr/uid").get().await().value
                if (taken != null && taken.toString() != uid) return@repeat
                Rtdb.root().updateChildren(
                    hashMapOf<String, Any?>(
                        "ids/u/$uid" to idStr,
                        "ids/i/$idStr/uid" to uid,
                        "pub/$uid/id" to idStr,
                    ),
                ).await()
                return idStr
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                kotlinx.coroutines.delay(600L)
            }
        }
        throw lastErr ?: IllegalStateException("Gagal membuat ID, coba lagi")
    }

    /** Data publik (nama, avatar, negara, bio) agar bisa dicari lewat ID. */
    suspend fun publish(ctx: Context, user: UserProfile, d: ProfileData): Throwable? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return IllegalStateException("Belum login")
        val id = try {
            cachedId(ctx, uid) ?: ensureUserId(ctx, uid)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return e }
        // ID kosong = server belum bisa dipastikan; jangan menimpa ID yang ada.
        if (id.isBlank()) return IllegalStateException("ID belum tersedia")
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
        // ID custom dari item Unique ID (contoh: 1000) dipetakan di uidCustom.
        try {
            val alias = kotlinx.coroutines.withTimeoutOrNull(3000L) { Rtdb.ref("uidCustom/$key").get().await() }
            val uidAlias = alias?.child("uid")?.getValue(String::class.java).orEmpty()
            if (uidAlias.isNotBlank()) findByUid(uidAlias)?.let { return it.copy(userId = key) }
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
        // Realtime Database: label family di profil publik (tanpa Firestore).
        return try {
            val rid = rtdbOfficialId(uid) ?: rtdbIdOf(uid)
            if (rid != null) kotlinx.coroutines.withTimeoutOrNull(4000L) {
                Rtdb.ref("ids/i/$rid/fam").setValue(fam).await()
            }
            kotlinx.coroutines.withTimeoutOrNull(4000L) {
                Rtdb.ref("pub/$uid/fam").setValue(fam).await()
            }
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

/**
 * ID custom (item Unique ID) dipetakan ke akun lewat RTDB:
 *   uidCustom/{nomorID} = { uid, itemId }  -> dipakai pencarian & profil
 *   uidAliasOf/{uid}    = nomorID          -> supaya bisa dilepas lagi
 * Satu nomor hanya boleh dipakai satu akun; akun lain ditolak.
 */
object UidAlias {
    suspend fun klaim(customId: String, uid: String, itemId: String): String? {
        val nomor = customId.trim()
        if (nomor.isBlank() || uid.isBlank()) return "Nomor ID custom kosong"
        return try {
            val lama = Rtdb.ref("uidCustom/$nomor/uid").get().await().getValue(String::class.java).orEmpty()
            if (lama.isNotBlank() && lama != uid) return "ID $nomor sedang dipakai akun lain"
            // Nomor lama milik akun ini dilepas supaya tidak dobel.
            val bekas = Rtdb.ref("uidAliasOf/$uid").get().await().getValue(String::class.java).orEmpty()
            if (bekas.isNotBlank() && bekas != nomor) {
                val pemilikBekas = Rtdb.ref("uidCustom/$bekas/uid").get().await().getValue(String::class.java).orEmpty()
                if (pemilikBekas == uid) Rtdb.ref("uidCustom/$bekas").removeValue().await()
            }
            Rtdb.ref("uidCustom/$nomor").setValue(
                hashMapOf<String, Any?>("uid" to uid, "itemId" to itemId, "at" to System.currentTimeMillis()),
            ).await()
            Rtdb.ref("uidAliasOf/$uid").setValue(nomor).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "ID $nomor belum bisa dipakai, coba lagi"
        }
    }

    suspend fun lepas(uid: String) {
        try {
            val nomor = Rtdb.ref("uidAliasOf/$uid").get().await().getValue(String::class.java).orEmpty()
            if (nomor.isNotBlank()) {
                val pemilik = Rtdb.ref("uidCustom/$nomor/uid").get().await().getValue(String::class.java).orEmpty()
                if (pemilik == uid) Rtdb.ref("uidCustom/$nomor").removeValue().await()
            }
            Rtdb.ref("uidAliasOf/$uid").removeValue().await()
        } catch (e: Exception) { }
    }
}
