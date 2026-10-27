package com.example.googlelogin

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

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

object FamilyRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun fam(id: String) = db().collection("families").document(id)
    /** Cari family dari nama (untuk label family di profil & room). */
    suspend fun findByName(name: String): Family? = try {
        db().collection("families").whereEqualTo("name", name).limit(1).get().await()
            .documents.firstOrNull()?.toFamily()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    private fun userFam(uid: String) = db().collection("familyMembers").document(uid)

    const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    private fun com.google.firebase.firestore.DocumentSnapshot.toFamily() = Family(
        id = id,
        numId = getString("numId") ?: "",
        name = getString("name") ?: "",
        label = getString("label") ?: "",
        logoB64 = getString("logoB64"),
        ownerUid = getString("ownerUid") ?: "",
        announcement = getString("announcement") ?: "",
        slogan = getString("slogan") ?: "",
        needApproval = getBoolean("needApproval") ?: false,
        exp = getLong("exp") ?: 0L,
        memberCount = (getLong("memberCount") ?: 0L).toInt(),
        createdAt = getLong("createdAt") ?: 0L,
        nameChangedAt = getLong("nameChangedAt") ?: 0L,
        labelChangedAt = getLong("labelChangedAt") ?: 0L,
    )

    /** Pantau family milik user (null kalau belum punya). */
    fun observeMy(uid: String, onChange: (Family?, Boolean) -> Unit): Reg {
        var famReg: ListenerRegistration? = null
        val memReg = userFam(uid).addSnapshotListener { snap, _ ->
            famReg?.remove()
            famReg = null
            if (snap == null) return@addSnapshotListener
            val fid = snap.getString("familyId")
            if (fid.isNullOrBlank()) {
                onChange(null, true)
            } else {
                famReg = fam(fid).addSnapshotListener { fs, _ ->
                    if (fs == null) return@addSnapshotListener
                    if (!fs.exists()) onChange(null, true) else onChange(fs.toFamily(), true)
                }
            }
        }
        return Reg { memReg.remove(); famReg?.remove() }
    }

    fun observeMembers(fid: String, onChange: (List<FamilyMember>) -> Unit): Reg {
        val r = fam(fid).collection("members").addSnapshotListener { snap, _ ->
            if (snap != null) onChange(
                snap.documents.map {
                    FamilyMember(
                        uid = it.id,
                        name = it.getString("name") ?: "Pengguna",
                        avatarB64 = it.getString("avatarB64"),
                        role = it.getString("role") ?: "member",
                        contribution = it.getLong("contribution") ?: 0L,
                        joinedAt = it.getLong("joinedAt") ?: 0L,
                    )
                }.sortedByDescending { it.contribution },
            )
        }
        return Reg { r.remove() }
    }

    fun observeRequests(fid: String, onChange: (List<FamilyRequest>) -> Unit): Reg {
        val r = fam(fid).collection("requests").addSnapshotListener { snap, _ ->
            if (snap != null) onChange(
                snap.documents.map {
                    FamilyRequest(it.id, it.getString("name") ?: "Pengguna", it.getString("avatarB64"), it.getLong("createdAt") ?: 0L)
                }.sortedByDescending { it.createdAt },
            )
        }
        return Reg { r.remove() }
    }

    fun observeEvents(fid: String, onChange: (List<FamilyEvent>) -> Unit): Reg {
        val r = fam(fid).collection("events").orderBy("createdAt", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(
                    snap.documents.map {
                        FamilyEvent(it.id, it.getString("title") ?: "", it.getString("text") ?: "", it.getLong("createdAt") ?: 0L, it.getString("byName") ?: "")
                    },
                )
            }
        return Reg { r.remove() }
    }

    fun observeDoneTasks(fid: String, uid: String, onChange: (Set<String>) -> Unit): Reg {
        val day = System.currentTimeMillis() / 86_400_000L
        val r = fam(fid).collection("taskLog").whereEqualTo("uid", uid).whereEqualTo("day", day)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { it.getString("task") }.toSet())
            }
        return Reg { r.remove() }
    }

    suspend fun search(q: String): List<Family> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        val byId = db().collection("families").whereEqualTo("numId", t).limit(5).get().await().documents.map { it.toFamily() }
        if (byId.isNotEmpty()) return byId
        return db().collection("families").orderBy("name").startAt(t).endAt(t + "\uf8ff").limit(20).get().await()
            .documents.map { it.toFamily() }
    }

    suspend fun topFamilies(): List<Family> =
        db().collection("families").orderBy("exp", Query.Direction.DESCENDING).limit(30).get().await().documents.map { it.toFamily() }

     /** Ambil satu family berdasarkan id (dipakai halaman family orang lain). */
    suspend fun getFamily(fid: String): Family? =
        fam(fid).get().await().toFamily()

    /** FamilyId milik uid saat ini (null = belum punya family). */
    suspend fun myFamilyId(uid: String): String? =
        userFam(uid).get().await().getString("familyId")
        
    suspend fun create(
        uid: String, myName: String, myAvatar: String?, name: String, label: String,
        logoB64: String?, needApproval: Boolean,
    ): String? = try {
        val now = System.currentTimeMillis()
        val counter = db().collection("counters").document("familyIds")
        val newRef = db().collection("families").document()
        var err: String? = null
        db().runTransaction { tx ->
            val mine = tx.get(userFam(uid)).getString("familyId")
            if (!mine.isNullOrBlank()) {
                err = "Kamu sudah punya family"
                return@runTransaction null
            }
            val next = tx.get(counter).getLong("next") ?: 10001L
            tx.set(counter, hashMapOf("next" to next + 1))
            tx.set(
                newRef,
                hashMapOf<String, Any?>(
                    "numId" to next.toString(), "name" to name, "label" to label, "logoB64" to logoB64,
                    "ownerUid" to uid, "announcement" to "", "slogan" to "", "needApproval" to needApproval,
                    "exp" to 0L, "memberCount" to 1L, "createdAt" to now, "nameChangedAt" to now, "labelChangedAt" to now,
                ),
            )
            tx.set(
                newRef.collection("members").document(uid),
                hashMapOf<String, Any?>("name" to myName, "avatarB64" to myAvatar, "role" to "owner", "contribution" to 0L, "joinedAt" to now),
            )
            tx.set(userFam(uid), hashMapOf("familyId" to newRef.id))
            null
        }.await()
        if (err == null) UserDirectory.setFam(uid, name)
        err
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message ?: "Gagal membuat family" }

    suspend fun join(fid: String, uid: String, myName: String, myAvatar: String?): String? = try {
        val now = System.currentTimeMillis()
        var err: String? = null
        db().runTransaction { tx ->
            val mine = tx.get(userFam(uid)).getString("familyId")
            if (!mine.isNullOrBlank()) { err = "Kamu sudah punya family"; return@runTransaction null }
            val fs = tx.get(fam(fid))
            if (!fs.exists()) { err = "Family tidak ditemukan"; return@runTransaction null }
            if ((fs.getLong("memberCount") ?: 0L) >= 100L) { err = "Family sudah penuh"; return@runTransaction null }
            if (fs.getBoolean("needApproval") == true) {
                tx.set(fam(fid).collection("requests").document(uid), hashMapOf<String, Any?>("name" to myName, "avatarB64" to myAvatar, "createdAt" to now))
                err = "REQUEST_SENT"
            } else {
                tx.set(fam(fid).collection("members").document(uid), hashMapOf<String, Any?>("name" to myName, "avatarB64" to myAvatar, "role" to "member", "contribution" to 0L, "joinedAt" to now))
                tx.update(fam(fid), "memberCount", FieldValue.increment(1))
                tx.set(userFam(uid), hashMapOf("familyId" to fid))
            }
            null
        }.await()
        if (err == null) UserDirectory.setFam(uid, fam(fid).get().await().getString("name") ?: "")
        err
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message ?: "Gagal bergabung" }

    suspend fun approve(fid: String, r: FamilyRequest): String? = try {
        val now = System.currentTimeMillis()
        var err: String? = null
        db().runTransaction { tx ->
            val mine = tx.get(userFam(r.uid)).getString("familyId")
            tx.delete(fam(fid).collection("requests").document(r.uid))
            if (!mine.isNullOrBlank()) { err = "Pengguna sudah masuk family lain"; return@runTransaction null }
            tx.set(fam(fid).collection("members").document(r.uid), hashMapOf<String, Any?>("name" to r.name, "avatarB64" to r.avatarB64, "role" to "member", "contribution" to 0L, "joinedAt" to now))
            tx.update(fam(fid), "memberCount", FieldValue.increment(1))
            tx.set(userFam(r.uid), hashMapOf("familyId" to fid))
            null
        }.await()
        if (err == null) UserDirectory.setFam(r.uid, fam(fid).get().await().getString("name") ?: "")
        err
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun reject(fid: String, uid: String): String? = try {
        fam(fid).collection("requests").document(uid).delete().await(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun leave(fid: String, uid: String): String? = try {
        db().runBatch { b ->
            b.delete(fam(fid).collection("members").document(uid))
            b.update(fam(fid), "memberCount", FieldValue.increment(-1))
            b.delete(userFam(uid))
        }.await()
        UserDirectory.setFam(uid, "")
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun kick(fid: String, uid: String): String? = leave(fid, uid)

    suspend fun setRole(fid: String, uid: String, role: String): String? = try {
        fam(fid).collection("members").document(uid).update("role", role).await(); null
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
            fam(f.id).set(upd, SetOptions.merge()).await(); null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }
    }

    suspend fun setAnnouncement(fid: String, text: String): String? = try {
        fam(fid).update("announcement", text).await(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun setSlogan(fid: String, text: String): String? = try {
        fam(fid).update("slogan", text).await(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun postEvent(fid: String, byName: String, title: String, text: String): String? = try {
        fam(fid).collection("events").add(
            hashMapOf<String, Any?>("title" to title, "text" to text, "byName" to byName, "createdAt" to System.currentTimeMillis()),
        ).await(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    suspend fun deleteEvent(fid: String, id: String): String? = try {
        fam(fid).collection("events").document(id).delete().await(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    /** Selesaikan tugas harian: tambah exp family & kontribusi anggota. Satu kali per tugas per hari. */
    suspend fun completeTask(fid: String, uid: String, t: FamilyTask): String? = try {
        val day = System.currentTimeMillis() / 86_400_000L
        val logRef = fam(fid).collection("taskLog").document("$uid-${t.id}-$day")
        var err: String? = null
        db().runTransaction { tx ->
            if (tx.get(logRef).exists()) { err = "Tugas sudah diselesaikan hari ini"; return@runTransaction null }
            tx.set(logRef, hashMapOf<String, Any?>("uid" to uid, "task" to t.id, "day" to day))
            tx.update(fam(fid), "exp", FieldValue.increment(t.reward))
            tx.update(fam(fid).collection("members").document(uid), "contribution", FieldValue.increment(t.reward))
            null
        }.await()
        err
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }

    /** EXP FAMILY dari gift: nilai gift (koin) menambah exp family + kontribusi pengirim.
     *  Jalan di latar belakang, tidak boleh menghalangi gift. */
    suspend fun addGiftExp(uid: String, amount: Long): String? {
        if (amount <= 0) return null
        return try {
            val fid = try { UserCache.peek(uid)?.fam ?: "" } catch (e: Exception) { "" }
            if (fid.isBlank()) return null
            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                db().runTransaction { tx ->
                    tx.update(fam(fid), "exp", FieldValue.increment(amount))
                    tx.update(fam(fid).collection("members").document(uid), "contribution", FieldValue.increment(amount))
                    null
                }.await()
            } ?: return "timeout"
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message
        }
    }

    /** Hapus family: hanya pemilik. Semua anggota dikeluarkan lalu dokumen dihapus. */
    suspend fun delete(fid: String): String? = try {
        val members = fam(fid).collection("members").get().await().documents
        for (chunk in members.chunked(200)) {
            db().runBatch { b ->
                chunk.forEach { m ->
                    b.delete(userFam(m.id))
                    b.delete(m.reference)
                }
            }.await()
        }
        for (sub in listOf("requests", "events", "taskLog")) {
            val docs = fam(fid).collection(sub).get().await().documents
            for (chunk in docs.chunked(400)) db().runBatch { b -> chunk.forEach { b.delete(it.reference) } }.await()
        }
        fam(fid).delete().await(); null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message }
}
