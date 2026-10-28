package com.example.googlelogin

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

data class Family(
    val id: String = "",
    val numId: String = "",
    val name: String = "",
    val label: String = "",
    val logoB64: String? = null,
    val ownerUid: String = "",
    val announcement: String = "",
    val slogan: String = "",
    val needApproval: Boolean = false,
    val exp: Long = 0L,
    val memberCount: Int = 0,
    val createdAt: Long = 0L,
    val nameChangedAt: Long = 0L,
    val labelChangedAt: Long = 0L,
)

data class FamilyMember(
    val uid: String = "",
    val name: String = "",
    val avatarB64: String? = null,
    val role: String = "member", // owner | admin | member
    val contribution: Long = 0L,
    val joinedAt: Long = 0L,
)

data class FamilyRequest(val uid: String, val name: String, val avatarB64: String?, val createdAt: Long)
data class FamilyTask(val id: String, val title: String, val desc: String, val reward: Long, val target: Long)
data class FamilyEvent(val id: String, val title: String, val text: String, val createdAt: Long, val byName: String)

val FAMILY_TASKS = listOf(
    FamilyTask("checkin", "Absen harian family", "Buka halaman family hari ini", 100L, 1),
    FamilyTask("share", "Kirim momen untuk family", "Tulis pengumuman atau kegiatan", 200L, 1),
    FamilyTask("invite", "Undang 1 anggota baru", "Ajak teman bergabung ke family", 500L, 1),
)

data class FamilyClass(val name: String, val roman: String, val need: Long, val perk1: String, val perk2: String, val perk3: String)

val FAMILY_CLASSES = listOf(
    FamilyClass("Iron", "I", 72_000_000L, "Dapatkan Dolar", "Medali Kelas", "Bingkai avatar"),
    FamilyClass("Bronze", "II", 200_000_000L, "Dapatkan Dolar", "Medali Kelas", "Bingkai avatar"),
    FamilyClass("Silver", "III", 600_000_000L, "Dapatkan Dolar", "Medali Kelas", "Bingkai avatar"),
    FamilyClass("Gold", "IV", 1_500_000_000L, "Dapatkan Dolar", "Medali Kelas", "Bingkai avatar"),
)

/** Level family dari total exp. Lv.1 mulai 0, tiap level butuh ambang yang naik. */
fun familyLevelNeed(level: Int): Long = when (level) {
    1 -> 0L
    2 -> 1_000_000L
    3 -> 10_000_000L
    4 -> 100_000_000L
    5 -> 1_000_000_000L
    6 -> 17_010_000_000L
    else -> 17_010_000_000L * (level - 5)
}

fun familyLevel(exp: Long): Int {
    var lv = 1
    while (lv < 10 && exp >= familyLevelNeed(lv + 1)) lv++
    return lv
}

fun familyClassOf(exp: Long): FamilyClass {
    var res = FAMILY_CLASSES.first()
    for (c in FAMILY_CLASSES) if (exp >= c.need / 1000) res = c
    return res
}

/**
 * FAMILY di REALTIME DATABASE (tanpa Firestore, bebas kuota Firestore).
 *   fm/{fid}                  data family
 *   fm/{fid}/members/{uid}    anggota
 *   fm/{fid}/requests/{uid}   permintaan bergabung
 *   fm/{fid}/events/{id}      pengumuman kegiatan
 *   fm/{fid}/taskLog/{key}    log tugas harian
 *   fmUser/{uid}              familyId milik user
 *   fmIdx/next                nomor ID family berurutan
 */
object FamilyRepo {
    private fun fm(id: String) = Rtdb.ref("fm/$id")
    private fun fmUser(uid: String) = Rtdb.ref("fmUser/$uid")

    const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    private fun DataSnapshot.str(k: String): String? = child(k).value as? String
    private fun DataSnapshot.lng(k: String): Long = (child(k).value as? Number)?.toLong() ?: 0L

    private fun DataSnapshot.toFamily(): Family? {
        if (!exists()) return null
        return Family(
            id = key ?: "",
            numId = str("numId") ?: "",
            name = str("name") ?: "",
            label = str("label") ?: "",
            logoB64 = str("logoB64"),
            ownerUid = str("ownerUid") ?: "",
            announcement = str("announcement") ?: "",
            slogan = str("slogan") ?: "",
            needApproval = (child("needApproval").value as? Boolean) ?: false,
            exp = lng("exp"),
            memberCount = lng("memberCount").toInt(),
            createdAt = lng("createdAt"),
            nameChangedAt = lng("nameChangedAt"),
            labelChangedAt = lng("labelChangedAt"),
        )
    }

    private suspend fun <T> readOnce(r: com.google.firebase.database.Query, f: (DataSnapshot) -> T): T? =
        try {
            kotlinx.coroutines.withTimeoutOrNull(8000L) { r.get().await() }?.let(f)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    /** Cari family dari nama (untuk label family di profil & room). */
    /** Ambil sebagian daftar family lalu saring di aplikasi. Cadangan kalau query
     *  terindeks gagal (rules .indexOn belum dipublish) sehingga hasil tidak kosong. */
    private suspend fun scanAll(): List<Family> =
        readOnce(Rtdb.ref("fm").limitToFirst(300)) { s -> s.children.mapNotNull { it.toFamily() } } ?: emptyList()

    // ================= MIGRASI FAMILY LAMA (Firestore -> RTDB) =================
    // Family yang dibuat sebelum migrasi hanya tersimpan di Firestore (families,
    // familyMembers). Tanpa ini, label family di profil menampilkan toast
    // "Family tidak ditemukan" karena pencarian hanya membaca fm di RTDB.
    private val fs by lazy { FirebaseFirestore.getInstance() }

    /** Salin satu dokumen family Firestore ke fm/{id} di RTDB (sekali saja). */
    private suspend fun importFamily(id: String, name: String? = null): Family? {
        if (id.isBlank()) return null
        val doc = try {
            kotlinx.coroutines.withTimeoutOrNull(6000L) { fs.collection("families").document(id).get().await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        if (!doc.exists()) return null
        val f = Family(
            id = doc.id,
            numId = (doc.getString("numId") ?: doc.getLong("numId")?.toString() ?: ""),
            name = doc.getString("name") ?: name ?: "",
            label = doc.getString("label") ?: "",
            logoB64 = doc.getString("logoB64"),
            ownerUid = doc.getString("ownerUid") ?: "",
            announcement = doc.getString("announcement") ?: "",
            slogan = doc.getString("slogan") ?: "",
            needApproval = doc.getBoolean("needApproval") ?: false,
            exp = doc.getLong("exp") ?: 0L,
            memberCount = (doc.getLong("memberCount") ?: 0L).toInt(),
            createdAt = doc.getLong("createdAt") ?: 0L,
            nameChangedAt = doc.getLong("nameChangedAt") ?: 0L,
            labelChangedAt = doc.getLong("labelChangedAt") ?: 0L,
        )
        if (f.name.isBlank()) return null
        val upd = hashMapOf<String, Any?>(
            "fm/${f.id}/numId" to f.numId, "fm/${f.id}/name" to f.name, "fm/${f.id}/label" to f.label,
            "fm/${f.id}/logoB64" to f.logoB64, "fm/${f.id}/ownerUid" to f.ownerUid,
            "fm/${f.id}/announcement" to f.announcement, "fm/${f.id}/slogan" to f.slogan,
            "fm/${f.id}/needApproval" to f.needApproval, "fm/${f.id}/exp" to f.exp,
            "fm/${f.id}/memberCount" to f.memberCount.toLong(), "fm/${f.id}/createdAt" to f.createdAt,
            "fm/${f.id}/nameChangedAt" to f.nameChangedAt, "fm/${f.id}/labelChangedAt" to f.labelChangedAt,
        )
        try {
            kotlinx.coroutines.withTimeout(8000L) { Rtdb.root().updateChildren(upd).await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        return f
    }

    /** Cari family lama di Firestore berdasarkan nama. */
    private suspend fun fsFindByName(name: String): Family? {
        if (name.isBlank()) return null
        val snap = try {
            kotlinx.coroutines.withTimeoutOrNull(7000L) {
                fs.collection("families").whereEqualTo("name", name).limit(1).get().await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val doc = snap.documents.firstOrNull() ?: return null
        return importFamily(doc.id, name)
    }

    /** Family id milik user dari Firestore lama (familyMembers/{uid}). */
    private suspend fun fsMyFamilyId(uid: String): String? = try {
        val fid = kotlinx.coroutines.withTimeoutOrNull(6000L) {
            fs.collection("familyMembers").document(uid).get().await()
        }?.getString("familyId")
        if (fid.isNullOrBlank()) null else {
            // Baca RTDB langsung (jangan panggil myFamilyId: itu memanggil fungsi ini lagi).
            val already = try {
                kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("fmUser/$uid").get().await() }?.value as? String
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (already.isNullOrBlank()) {
                try {
                    kotlinx.coroutines.withTimeout(6000L) { Rtdb.ref("fmUser/$uid").setValue(fid).await() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
            }
            fid
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun findByName(name: String): Family? =
        (readOnce(Rtdb.ref("fm").orderByChild("name").equalTo(name).limitToFirst(1)) { s ->
            s.children.firstOrNull()?.toFamily()
        }) ?: scanAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: fsFindByName(name)

    private fun listen(r: com.google.firebase.database.Query, on: (DataSnapshot) -> Unit): Reg {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { try { on(snapshot) } catch (e: Exception) { } }
            override fun onCancelled(error: DatabaseError) {}
        }
        r.addValueEventListener(l)
        return Reg { r.removeEventListener(l) }
    }

    /** Pantau family milik user (null kalau belum punya). */
    fun observeMy(uid: String, onChange: (Family?, Boolean) -> Unit): Reg {
        var famReg: Reg? = null
        val memReg = listen(fmUser(uid)) { snap ->
            famReg?.remove()
            famReg = null
            val fid = snap.value as? String
            if (fid.isNullOrBlank()) {
                // Belum ada di RTDB: coba family lama di Firestore sekali, lalu salin.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val old = fsMyFamilyId(uid)
                    if (old != null) importFamily(old)
                }
                onChange(null, true)
            } else {
                famReg = listen(fm(fid)) { fs ->
                    val f = fs.toFamily()
                    onChange(f, true)
                }
            }
        }
        return Reg { memReg.remove(); famReg?.remove() }
    }

    fun observeMembers(fid: String, onChange: (List<FamilyMember>) -> Unit): Reg =
        listen(fm(fid).child("members")) { snap ->
            onChange(
                snap.children.map {
                    FamilyMember(
                        uid = it.key ?: "",
                        name = it.str("name") ?: "Pengguna",
                        avatarB64 = it.str("avatarB64"),
                        role = it.str("role") ?: "member",
                        contribution = it.lng("contribution"),
                        joinedAt = it.lng("joinedAt"),
                    )
                }.sortedByDescending { it.contribution },
            )
        }

    fun observeRequests(fid: String, onChange: (List<FamilyRequest>) -> Unit): Reg =
        listen(fm(fid).child("requests")) { snap ->
            onChange(
                snap.children.map {
                    FamilyRequest(it.key ?: "", it.str("name") ?: "Pengguna", it.str("avatarB64"), it.lng("createdAt"))
                }.sortedByDescending { it.createdAt },
            )
        }

    fun observeEvents(fid: String, onChange: (List<FamilyEvent>) -> Unit): Reg =
        listen(fm(fid).child("events").orderByChild("createdAt").limitToLast(50)) { snap ->
            onChange(
                snap.children.map {
                    FamilyEvent(it.key ?: "", it.str("title") ?: "", it.str("text") ?: "", it.lng("createdAt"), it.str("byName") ?: "")
                }.sortedByDescending { it.createdAt },
            )
        }

    fun observeDoneTasks(fid: String, uid: String, onChange: (Set<String>) -> Unit): Reg {
        val day = System.currentTimeMillis() / 86_400_000L
        return listen(fm(fid).child("taskLog")) { snap ->
            onChange(
                snap.children.filter { it.str("uid") == uid && it.lng("day") == day }
                    .mapNotNull { it.str("task") }.toSet(),
            )
        }
    }

    suspend fun search(q: String): List<Family> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        val byId = readOnce(Rtdb.ref("fm").orderByChild("numId").equalTo(t).limitToFirst(5)) { s ->
            s.children.mapNotNull { it.toFamily() }
        } ?: emptyList()
        if (byId.isNotEmpty()) return byId
        val byName = readOnce(Rtdb.ref("fm").orderByChild("name").startAt(t).endAt(t + "\uf8ff").limitToFirst(20)) { s ->
            s.children.mapNotNull { it.toFamily() }
        } ?: emptyList()
        if (byName.isNotEmpty()) return byName
        // Cadangan: saring di aplikasi (nama mengandung kata / numId sama), tanpa peduli huruf besar-kecil.
        val low = t.lowercase()
        val local = scanAll().filter { it.name.lowercase().contains(low) || it.numId == t }.take(20)
        if (local.isNotEmpty()) return local
        // Family lama: cari di Firestore lalu salin ke RTDB (sekali).
        try {
            val snap = kotlinx.coroutines.withTimeoutOrNull(7000L) {
                fs.collection("families").orderBy("name").startAt(t).endAt(t + "\uf8ff").limit(20).get().await()
            }
            val docs = snap?.documents ?: emptyList()
            val out = docs.mapNotNull { importFamily(it.id, it.getString("name")) }
            if (out.isNotEmpty()) return out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        return emptyList()
    }

    suspend fun topFamilies(): List<Family> =
        readOnce(Rtdb.ref("fm").orderByChild("exp").limitToLast(30)) { s ->
            s.children.mapNotNull { it.toFamily() }.sortedByDescending { it.exp }
        } ?: emptyList()

    /** Ambil satu family berdasarkan id (dipakai halaman family orang lain). */
    suspend fun getFamily(fid: String): Family? = readOnce(fm(fid)) { it.toFamily() }

    /** FamilyId milik uid saat ini (null = belum punya family). */
    suspend fun myFamilyId(uid: String): String? =
        readOnce(fmUser(uid)) { it.value as? String }?.takeIf { it.isNotBlank() } ?: fsMyFamilyId(uid)

    /** Ambil nomor family berikutnya (transaksi HANYA di fmIdx/next). */
    private suspend fun nextNum(): Long = suspendCancellableCoroutine { cont ->
        Rtdb.ref("fmIdx/next").runTransaction(object : Transaction.Handler {
            override fun doTransaction(cur: MutableData): Transaction.Result {
                val n = (cur.value as? Number)?.toLong() ?: 10001L
                cur.value = n + 1
                return Transaction.success(cur)
            }
            override fun onComplete(err: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                if (!cont.isActive) return
                val v = (snap?.value as? Number)?.toLong()
                if (err == null && committed && v != null) cont.resume(v - 1)
                else cont.resume(-1L)
            }
        })
    }

    suspend fun create(
        uid: String, myName: String, myAvatar: String?, name: String, label: String,
        logoB64: String?, needApproval: Boolean,
    ): String? = try {
        val now = System.currentTimeMillis()
        if (!myFamilyId(uid).isNullOrBlank()) {
            "Kamu sudah punya family"
        } else {
            var num = -1L
            repeat(3) { if (num < 0) num = nextNum() }
            if (num < 0) num = 10000L + (now % 900000L)
            val fid = Rtdb.ref("fm").push().key ?: "f$now"
            val data = hashMapOf<String, Any?>(
                "fm/$fid/numId" to num.toString(), "fm/$fid/name" to name, "fm/$fid/label" to label,
                "fm/$fid/logoB64" to logoB64, "fm/$fid/ownerUid" to uid, "fm/$fid/announcement" to "",
                "fm/$fid/slogan" to "", "fm/$fid/needApproval" to needApproval, "fm/$fid/exp" to 0L,
                "fm/$fid/memberCount" to 1L, "fm/$fid/createdAt" to now, "fm/$fid/nameChangedAt" to now,
                "fm/$fid/labelChangedAt" to now,
                "fm/$fid/members/$uid/name" to myName, "fm/$fid/members/$uid/avatarB64" to myAvatar,
                "fm/$fid/members/$uid/role" to "owner", "fm/$fid/members/$uid/contribution" to 0L,
                "fm/$fid/members/$uid/joinedAt" to now,
                "fmUser/$uid" to fid,
            )
            kotlinx.coroutines.withTimeout(10000L) { Rtdb.root().updateChildren(data).await() }
            UserDirectory.setFam(uid, name)
            null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message ?: "Gagal membuat family" }

    suspend fun join(fid: String, uid: String, myName: String, myAvatar: String?): String? = try {
        val now = System.currentTimeMillis()
        if (!myFamilyId(uid).isNullOrBlank()) "Kamu sudah punya family" else {
            val fs = readOnce(fm(fid)) { it } 
            val f = fs?.toFamily()
            if (f == null) "Family tidak ditemukan"
            else if (f.memberCount >= 100) "Family sudah penuh"
            else if (f.needApproval) {
                kotlinx.coroutines.withTimeout(8000L) {
                    Rtdb.root().updateChildren(hashMapOf<String, Any?>(
                        "fm/$fid/requests/$uid/name" to myName, "fm/$fid/requests/$uid/avatarB64" to myAvatar,
                        "fm/$fid/requests/$uid/createdAt" to now,
                    )).await()
                }
                "REQUEST_SENT"
            } else {
                addMember(fid, uid, myName, myAvatar, now)
                UserDirectory.setFam(uid, f.name)
                null
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message ?: "Gagal bergabung" }

    private suspend fun bumpCount(fid: String, delta: Long) {
        suspendCancellableCoroutine<Unit> { cont ->
            fm(fid).child("memberCount").runTransaction(object : Transaction.Handler {
                override fun doTransaction(cur: MutableData): Transaction.Result {
                    cur.value = maxOf(0L, ((cur.value as? Number)?.toLong() ?: 0L) + delta)
                    return Transaction.success(cur)
                }
                override fun onComplete(err: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }

    private suspend fun addMember(fid: String, uid: String, name: String, avatar: String?, now: Long) {
        kotlinx.coroutines.withTimeout(8000L) {
            Rtdb.root().updateChildren(hashMapOf<String, Any?>(
                "fm/$fid/members/$uid/name" to name, "fm/$fid/members/$uid/avatarB64" to avatar,
                "fm/$fid/members/$uid/role" to "member", "fm/$fid/members/$uid/contribution" to 0L,
                "fm/$fid/members/$uid/joinedAt" to now, "fmUser/$uid" to fid,
                "fm/$fid/requests/$uid" to null,
            )).await()
        }
        bumpCount(fid, 1)
    }

    suspend fun approve(fid: String, r: FamilyRequest): String? = try {
        val now = System.currentTimeMillis()
        if (!myFamilyId(r.uid).isNullOrBlank()) {
            kotlinx.coroutines.withTimeout(8000L) { fm(fid).child("requests/${r.uid}").removeValue().await() }
            "Pengguna sudah masuk family lain"
        } else {
            addMember(fid, r.uid, r.name, r.avatarB64, now)
            UserDirectory.setFam(r.uid, getFamily(fid)?.name ?: "")
            null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun reject(fid: String, uid: String): String? = try {
        kotlinx.coroutines.withTimeout(8000L) { fm(fid).child("requests/$uid").removeValue().await() }; null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun leave(fid: String, uid: String): String? = try {
        kotlinx.coroutines.withTimeout(8000L) {
            Rtdb.root().updateChildren(hashMapOf<String, Any?>(
                "fm/$fid/members/$uid" to null, "fmUser/$uid" to null,
            )).await()
        }
        bumpCount(fid, -1)
        UserDirectory.setFam(uid, "")
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun kick(fid: String, uid: String): String? = leave(fid, uid)

    suspend fun setRole(fid: String, uid: String, role: String): String? = try {
        kotlinx.coroutines.withTimeout(8000L) { fm(fid).child("members/$uid/role").setValue(role).await() }; null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun saveSettings(f: Family, name: String, label: String, logoB64: String?, needApproval: Boolean): String? {
        return try {
            val now = System.currentTimeMillis()
            val upd = hashMapOf<String, Any?>("logoB64" to logoB64, "needApproval" to needApproval)
            if (name != f.name) {
                if (now - f.nameChangedAt < MONTH_MS) return "Nama family hanya dapat diubah 1 kali per bulan"
                upd["name"] = name; upd["nameChangedAt"] = now
            }
            if (label != f.label) {
                if (now - f.labelChangedAt < MONTH_MS) return "Label family hanya dapat diubah 1 kali per bulan"
                upd["label"] = label; upd["labelChangedAt"] = now
            }
            kotlinx.coroutines.withTimeout(8000L) { fm(f.id).updateChildren(upd).await() }
            if (name != f.name) {
                // Label family di profil semua anggota ikut nama baru.
                try {
                    val ms = readOnce(fm(f.id).child("members")) { s -> s.children.mapNotNull { it.key } } ?: emptyList()
                    ms.forEach { UserDirectory.setFam(it, name) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }
    }

    suspend fun setAnnouncement(fid: String, text: String): String? = try {
        kotlinx.coroutines.withTimeout(8000L) { fm(fid).child("announcement").setValue(text).await() }; null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun setSlogan(fid: String, text: String): String? = try {
        kotlinx.coroutines.withTimeout(8000L) { fm(fid).child("slogan").setValue(text).await() }; null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun postEvent(fid: String, byName: String, title: String, text: String): String? = try {
        val ref = fm(fid).child("events").push()
        kotlinx.coroutines.withTimeout(8000L) {
            ref.setValue(hashMapOf<String, Any?>("title" to title, "text" to text, "byName" to byName, "createdAt" to System.currentTimeMillis())).await()
        }; null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun deleteEvent(fid: String, id: String): String? = try {
        kotlinx.coroutines.withTimeout(8000L) { fm(fid).child("events/$id").removeValue().await() }; null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    private suspend fun incr(r: com.google.firebase.database.DatabaseReference, amount: Long) {
        suspendCancellableCoroutine<Unit> { cont ->
            r.runTransaction(object : Transaction.Handler {
                override fun doTransaction(cur: MutableData): Transaction.Result {
                    cur.value = ((cur.value as? Number)?.toLong() ?: 0L) + amount
                    return Transaction.success(cur)
                }
                override fun onComplete(err: DatabaseError?, committed: Boolean, snap: DataSnapshot?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }

    /** Selesaikan tugas harian: tambah exp family & kontribusi anggota. Satu kali per tugas per hari. */
    suspend fun completeTask(fid: String, uid: String, t: FamilyTask): String? = try {
        val day = System.currentTimeMillis() / 86_400_000L
        val key = "$uid-${t.id}-$day"
        val exists = readOnce(fm(fid).child("taskLog/$key")) { it.exists() } ?: false
        if (exists) "Tugas sudah diselesaikan hari ini" else {
            kotlinx.coroutines.withTimeout(8000L) {
                fm(fid).child("taskLog/$key").setValue(hashMapOf<String, Any?>("uid" to uid, "task" to t.id, "day" to day)).await()
            }
            incr(fm(fid).child("exp"), t.reward)
            incr(fm(fid).child("members/$uid/contribution"), t.reward)
            null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    /** EXP FAMILY dari gift: nilai gift (koin) menambah exp family + kontribusi pengirim.
     *  Jalan di latar belakang, tidak boleh menghalangi gift. */
    suspend fun addGiftExp(uid: String, amount: Long): String? {
        if (amount <= 0) return null
        return try {
            var fid = try { UserCache.peek(uid)?.fam ?: "" } catch (e: Exception) { "" }
            // UserCache.fam berisi NAMA family; cari id family dari fmUser bila perlu.
            val myFid = myFamilyId(uid)
            if (!myFid.isNullOrBlank()) fid = myFid
            if (fid.isBlank()) return null
            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                incr(fm(fid).child("exp"), amount)
                incr(fm(fid).child("members/$uid/contribution"), amount)
            } ?: return "timeout"
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message
        }
    }

    /** Hapus family: hanya pemilik. Semua anggota dikeluarkan lalu data dihapus. */
    suspend fun delete(fid: String): String? = try {
        val members = readOnce(fm(fid).child("members")) { s -> s.children.mapNotNull { it.key } } ?: emptyList()
        val upd = HashMap<String, Any?>()
        members.forEach { upd["fmUser/$it"] = null }
        upd["fm/$fid"] = null
        kotlinx.coroutines.withTimeout(10000L) { Rtdb.root().updateChildren(upd).await() }
        members.forEach { UserDirectory.setFam(it, "") }
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }
}
