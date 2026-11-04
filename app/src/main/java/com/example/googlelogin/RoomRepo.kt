package com.example.googlelogin

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

data class RoomInfo(
    val id: String,
    val roomNo: String,
    val name: String,
    val ownerUid: String,
    val ownerName: String,
    val photoB64: String?,
    val backgroundB64: String?,
    val announcement: String,
    val micCount: Int,
    val lockHash: String,
    val online: Int,
    val lockedSeats: List<Int> = emptyList(),
    val pinned: Boolean = false,
    val pinnedAt: Long = 0L,
) {
    val locked: Boolean get() = lockHash.isNotEmpty()
}

data class Seat(val index: Int, val uid: String, val name: String, val avatarB64: String?, val muted: Boolean)

data class RoomMsg(
    val id: String,
    val text: String,
    val createdAt: Long,
    val uid: String = "",
    val name: String = "",
    val vip: Int = 0,
    val vipUid: String = "",
    val entry: String = "",
    val banner: String = "",
    val img: String = "",
)

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun micColumns(count: Int): Int = if (count == 8 || count == 12 || count == 16) 4 else 5

object RoomRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun roomRef(id: String) = db().collection("rooms").document(id)

    private fun DocumentSnapshot.toRoom(): RoomInfo? {
        if (!exists()) return null
        return RoomInfo(
            id = id,
            roomNo = getString("roomNo") ?: "",
            name = getString("name") ?: "Room",
            ownerUid = getString("ownerUid") ?: "",
            ownerName = getString("ownerName") ?: "",
            photoB64 = getString("photoB64"),
            backgroundB64 = getString("backgroundB64"),
            announcement = getString("announcement") ?: "",
            micCount = (getLong("micCount") ?: 8L).toInt(),
            lockHash = getString("lockHash") ?: "",
            online = (getLong("online") ?: 0L).toInt().coerceAtLeast(0),
            lockedSeats = (get("lockedSeats") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
            pinned = getBoolean("pinned") ?: false,
            pinnedAt = getLong("pinnedAt") ?: 0L,
        )
    }


    /**
     * Room milik sendiri. Room yang sudah dihapus permanen dari panel dev
     * (ditandai tombstone di Realtime Database) diabaikan, supaya tidak
     * "hidup lagi" dari cache Firestore saat user membuat room baru.
     */
    suspend fun findOwned(ownerUid: String): RoomInfo? {
        val kandidat = try {
            db().collection("rooms").whereEqualTo("ownerUid", ownerUid)
                .get(com.google.firebase.firestore.Source.SERVER).await()
                .documents.mapNotNull { it.toRoom() } +
                try {
                    db().collection("rooms").whereEqualTo("ownerUid", ownerUid)
                        .get(com.google.firebase.firestore.Source.CACHE).await()
                        .documents.mapNotNull { it.toRoom() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                db().collection("rooms").whereEqualTo("ownerUid", ownerUid).limit(1).get().await()
                    .documents.firstOrNull()?.toRoom()
                    ?.let { listOf(it) } ?: emptyList()
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (e2: Exception) {
                emptyList()
            }
        }
        return kandidat.distinctBy { it.id }.firstOrNull()
    }

    /**
     * Room yang "dihapus" dari panel dev disembunyikan (roomsHidden/{id}), bukan
     * dimusnahkan: hilang dari daftar, tapi saat pemilik membuat room lagi room
     * yang sama muncul kembali dengan nama/foto yang sudah ada.
     */
    suspend fun isHidden(roomId: String): Boolean = try {
        val s = kotlinx.coroutines.withTimeoutOrNull(4000L) {
            Rtdb.ref("roomsHidden/$roomId").get().await()
        }
        s?.exists() == true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Tampilkan lagi room milik sendiri (dipakai saat pemilik membuat room lagi). */
    suspend fun restoreOwned(roomId: String) {
        try { Rtdb.ref("roomsHidden/$roomId").removeValue().await() } catch (e: Exception) { }
        try { getRoom(roomId, abaikanSembunyi = true)?.let { mirrorRoom(it) } } catch (e: Exception) { }
    }

    suspend fun createRoom(ownerUid: String, ownerName: String, name: String, photoB64: String?): RoomInfo {
        findOwned(ownerUid)?.let { return it }
        val ref = db().collection("rooms").document()
        val no = (10000000..99999999).random().toString()
        val data = hashMapOf<String, Any?>(
            "roomNo" to no,
            "name" to name,
            "ownerUid" to ownerUid,
            "ownerName" to ownerName,
            "photoB64" to photoB64,
            "backgroundB64" to null,
            "announcement" to "",
            "micCount" to 8,
            "lockHash" to "",
            "online" to 0,
            "createdAt" to System.currentTimeMillis(),
        )
        val created = RoomInfo(ref.id, no, name, ownerUid, ownerName, photoB64, null, "", 8, "", 0)
        // RTDB dulu (tidak kena kuota Firestore), lalu Firestore.
        mirrorRoom(created)
        ref.set(data).await()
        return created
    }

    // ===== DAFTAR ROOM DI REALTIME DATABASE (roomsPub/{roomId}) =====
    // Tab Party tidak lagi bergantung Firestore: setiap room dicermin ke RTDB,
    // jadi daftar room tetap tampil walau kuota Firestore habis / error.
    fun mirrorRoom(r: RoomInfo) {
        try {
            // Room lama yang sudah dihapus permanen tidak boleh dicerminkan ulang.
            if (r.id.isBlank()) return
            val m = hashMapOf<String, Any?>(
                "roomNo" to r.roomNo, "name" to r.name, "ownerUid" to r.ownerUid,
                "ownerName" to r.ownerName, "photoB64" to r.photoB64,
                "announcement" to r.announcement, "micCount" to r.micCount.toLong(),
                "lockHash" to r.lockHash, "pinned" to r.pinned, "pinnedAt" to r.pinnedAt,
                "createdAt" to (System.currentTimeMillis()),
            )
            Rtdb.ref("roomsPub/" + r.id).updateChildren(m)
        } catch (e: Exception) { }
    }

    private fun com.google.firebase.database.DataSnapshot.toRtRoom(): RoomInfo? {
        val id = key ?: return null
        val owner = child("ownerUid").getValue(String::class.java) ?: return null
        return RoomInfo(
            id = id,
            roomNo = child("roomNo").getValue(String::class.java) ?: "",
            name = child("name").getValue(String::class.java) ?: "Room",
            ownerUid = owner,
            ownerName = child("ownerName").getValue(String::class.java) ?: "",
            photoB64 = child("photoB64").getValue(String::class.java),
            backgroundB64 = null,
            announcement = child("announcement").getValue(String::class.java) ?: "",
            micCount = (child("micCount").value as? Number)?.toInt() ?: 8,
            lockHash = child("lockHash").getValue(String::class.java) ?: "",
            online = 0,
            lockedSeats = emptyList(),
            pinned = child("pinned").getValue(Boolean::class.java) ?: false,
            pinnedAt = (child("pinnedAt").value as? Number)?.toLong() ?: 0L,
        )
    }

    /**
     * Nama pemilik room HARUS mengikuti nama profil terbaru.
     * Dulu nama disimpan sekali saat room dibuat, jadi kalau user ganti nama,
     * tulisan di bawah nama room tetap nama lama. Fungsi ini memperbarui:
     *  - roomsPub/{roomId}/ownerName (dibaca daftar room)
     *  - kursi mic aktif milik user tersebut (seats/{roomId})
     *  - salinan Firestore rooms (kalau kuota tersedia)
     */
    fun syncOwnerName(uid: String, name: String) {
        val nm = name.trim()
        if (uid.isBlank() || nm.isEmpty()) return
        try {
            Rtdb.ref("roomsPub").get().addOnSuccessListener { snap ->
                snap.children.forEach { c ->
                    if ((c.child("ownerUid").value as? String) == uid) {
                        c.ref.child("ownerName").setValue(nm)
                    }
                }
            }
            Rtdb.ref("seats").get().addOnSuccessListener { snap ->
                snap.children.forEach { room ->
                    room.children.forEach { seat ->
                        if ((seat.child("uid").value as? String) == uid) {
                            seat.ref.child("name").setValue(nm)
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        try {
            db().collection("rooms").whereEqualTo("ownerUid", uid).get()
                .addOnSuccessListener { qs -> qs.documents.forEach { it.reference.update("ownerName", nm) } }
        } catch (e: Exception) { }
    }

    /** Daftar room: REALTIME DATABASE saja (roomsPub). Firestore tidak lagi dipakai
     *  di sini karena listener 100 dokumen terus-menerus menghabiskan kuota baca. */
    fun observeRoomsMerged(onChange: (List<RoomInfo>) -> Unit): Reg {
        val r1 = Rtdb.listen(Rtdb.ref("roomsPub")) { snap ->
            onChange(snap.children.mapNotNull { it.toRtRoom() })
        }
        return Reg { r1.remove() }
    }

    fun observeRooms(onChange: (List<RoomInfo>) -> Unit): ListenerRegistration =
        db().collection("rooms").orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { it.toRoom() })
            }

    /** Data room realtime dari Realtime Database (bukan Firestore). */
    fun observeRoom(id: String, onChange: (RoomInfo?) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("roomsPub/$id")) { snap -> onChange(pubToRoom(id, snap)) }

    /** Room dari mirror Realtime Database roomsPub/{id}: dipakai sebagai cadangan
     *  saat Firestore macet / kuota habis supaya masuk room tidak muter-muter. */
    fun pubToRoom(id: String, s: com.google.firebase.database.DataSnapshot): RoomInfo? {
        if (!s.exists()) return null
        // Mirror parsial (misal hanya micCount) belum cukup untuk membuka room.
        if ((s.child("name").value as? String).isNullOrBlank() && (s.child("ownerUid").value as? String).isNullOrBlank()) return null
        fun str(k: String): String = s.child(k).value as? String ?: ""
        return RoomInfo(
            id = id,
            roomNo = str("roomNo"),
            name = str("name").ifBlank { "Room" },
            ownerUid = str("ownerUid"),
            ownerName = str("ownerName"),
            photoB64 = str("photoB64").ifBlank { null },
            backgroundB64 = null,
            announcement = str("announcement"),
            micCount = ((s.child("micCount").value as? Long) ?: 8L).toInt(),
            lockHash = str("lockHash"),
            online = 0,
            lockedSeats = emptyList(),
            pinned = s.child("pinned").value as? Boolean ?: false,
            pinnedAt = (s.child("pinnedAt").value as? Long) ?: 0L,
        )
    }

    suspend fun getRoom(id: String, abaikanSembunyi: Boolean = false): RoomInfo? {
        // Room yang disembunyikan dari panel tidak bisa dimasuki lewat daftar/link,
        // kecuali pemiliknya sedang membuka lagi room tersebut.
        if (!abaikanSembunyi && isHidden(id)) return null
        // REALTIME DATABASE dulu (bebas kuota Firestore): roomsPub/{id}.
        try {
            val snap = kotlinx.coroutines.withTimeoutOrNull(5000L) { Rtdb.ref("roomsPub/$id").get().await() }
            val rt = snap?.let { pubToRoom(id, it) }
            if (rt != null) return rt
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Cadangan Firestore (kalau mirror RTDB belum ada).
        return try {
            val fs = kotlinx.coroutines.withTimeoutOrNull(6000L) { db().collection("rooms").document(id).get().await().toRoom() }
            if (fs != null) mirrorRoom(fs)
            fs
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun observeSeats(id: String, onChange: (Map<Int, Seat>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("seats/$id")) { snap ->
            val list = snap.children.mapNotNull { c ->
                val i = c.key?.toIntOrNull() ?: return@mapNotNull null
                val uid = c.child("uid").getValue(String::class.java) ?: return@mapNotNull null
                Seat(
                    i,
                    uid,
                    c.child("name").getValue(String::class.java) ?: "",
                    c.child("avatarB64").getValue(String::class.java),
                    c.child("muted").value == true,
                )
            }
            onChange(list.associateBy { it.index })
        }

    fun observeMessages(id: String, limit: Int = 50, onChange: (List<RoomMsg>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("messages/$id").limitToLast(limit)) { snap ->
            onChange(
                snap.children.map { c ->
                    RoomMsg(
                        c.key ?: "",
                        c.child("text").getValue(String::class.java) ?: "",
                        (c.child("createdAt").value as? Number)?.toLong() ?: 0L,
                        c.child("uid").getValue(String::class.java) ?: "",
                        c.child("name").getValue(String::class.java) ?: "",
                        (c.child("vip").value as? Number)?.toInt() ?: 0,
                        c.child("vipUid").getValue(String::class.java) ?: "",
                        c.child("entry").getValue(String::class.java) ?: "",
                        c.child("banner").getValue(String::class.java) ?: "",
                        c.child("img").getValue(String::class.java) ?: "",
                    )
                },
            )
        }

    /** Room tempat user sedang berada; null jika tidak sedang live. */
    suspend fun liveRoomOf(uid: String): String? = try {
        Rtdb.ref("live/$uid").get().await().getValue(String::class.java)?.ifBlank { null }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    fun observeAllOnline(onChange: (Map<String, Int>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("members")) { snap ->
            onChange(snap.children.associate { (it.key ?: "") to it.childrenCount.toInt() })
        }

    fun observeMembers(id: String, onChange: (Set<String>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("members/$id")) { snap ->
            onChange(snap.children.mapNotNull { it.key }.toSet())
        }

    fun observeLocks(id: String, onChange: (Set<Int>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("locks/$id")) { snap ->
            onChange(snap.children.mapNotNull { it.key?.toIntOrNull() }.toSet())
        }

    /** Hapus semua chat publik di room (untuk semua orang). null = sukses. */
    suspend fun clearMessages(roomId: String): String? = try {
        Rtdb.ref("messages/$roomId").removeValue().await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Gagal menghapus chat"
    }

    suspend fun join(roomId: String, name: String, vip: Int = 0, entry: String = "", banner: String = "") {
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            if (isBanned(roomId, uid)) return
            // Invisible in rooms (SVIP7): masuk room tanpa terlihat sampai naik mic.
            if (SvipRepo.myOn(uid, "invisibleRoom", 7)) return
            val me = Rtdb.ref("members/$roomId/$uid")
            me.onDisconnect().removeValue()
            me.setValue(true).await()
            // Penanda "sedang live di room ini" untuk badge LIVE di halaman profil.
            val live = Rtdb.ref("live/$uid")
            live.onDisconnect().removeValue()
            live.setValue(roomId).await()
            Rtdb.ref("messages/$roomId").push().setValue(
                hashMapOf<String, Any?>(
                    "text" to "$name masuk kamar",
                    "name" to name,
                    "createdAt" to System.currentTimeMillis(),
                    "vip" to vip,
                    "entry" to entry,
                    "banner" to banner,
                    "vipUid" to (if (vip > 0 || entry.isNotBlank() || banner.isNotBlank()) uid else ""),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    /** Satu catatan kick (daftar hitam) di sebuah room. */
    data class RoomBan(
        val uid: String,
        val name: String,
        val until: Long, // 0 = permanen
        val at: Long,
    ) {
        fun active(now: Long = System.currentTimeMillis()): Boolean = until <= 0L || until > now

        /** Sisa waktu dalam bahasa manusia. */
        fun sisa(now: Long = System.currentTimeMillis()): String {
            if (until <= 0L) return "Permanen"
            val ms = until - now
            if (ms <= 0L) return "Sudah berakhir"
            val mnt = ms / 60000L
            return when {
                mnt < 60L -> "$mnt menit lagi"
                mnt < 1440L -> "${mnt / 60L} jam ${mnt % 60L} menit lagi"
                else -> "${mnt / 1440L} hari lagi"
            }
        }
    }

    private fun parseBan(uid: String, s: com.google.firebase.database.DataSnapshot): RoomBan {
        // Data lama disimpan sebagai "true" (berarti permanen).
        if (s.value is Boolean) return RoomBan(uid = uid, name = "", until = 0L, at = 0L)
        return RoomBan(
            uid = uid,
            name = s.child("name").getValue(String::class.java) ?: "",
            until = (s.child("until").value as? Number)?.toLong() ?: 0L,
            at = (s.child("at").value as? Number)?.toLong() ?: 0L,
        )
    }

    /** Data kick yang masih berlaku di satu room. null = tidak di-kick. */
    suspend fun banOf(roomId: String, uid: String): RoomBan? {
        val s = try {
            Rtdb.ref("blacklist/$roomId/$uid").get().await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (!s.exists()) return null
        val b = parseBan(uid, s)
        if (!b.active()) {
            // Kick sementara sudah lewat: bersihkan sendiri supaya tidak mengunci selamanya.
            try {
                Rtdb.ref("blacklist/$roomId/$uid").removeValue().await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            return null
        }
        return b
    }

    /** Apakah user di-kick (daftar hitam) dari room ini. */
    suspend fun isBanned(roomId: String, uid: String): Boolean = banOf(roomId, uid) != null

    /**
     * Owner menendang user keluar room. User otomatis masuk daftar hitam.
     * [durationMs] = 0 berarti permanen, selain itu kick sementara.
     * null = sukses.
     */
    suspend fun kickFromRoom(roomId: String, uid: String, name: String = "", durationMs: Long = 0L): String? {
        // FIX Anti Kick (SVIP5): user yang mengaktifkan tidak bisa ditendang.
        val pub = try {
            UserDirectory.findByUid(uid)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        if (pub != null && vipActive(pub.vip, pub.vipUntil) && pub.vip >= 5 && pub.svipOn("antiKick")) {
            return "User ini mengaktifkan Anti Kick"
        }
        val now = System.currentTimeMillis()
        val until = if (durationMs > 0L) now + durationMs else 0L
        return try {
            val seats = Rtdb.ref("seats/$roomId").get().await()
            val seatKey = seats.children.firstOrNull { it.child("uid").getValue(String::class.java) == uid }?.key
            if (seatKey != null) Rtdb.ref("seats/$roomId/$seatKey").removeValue().await()
            Rtdb.ref("members/$roomId/$uid").removeValue().await()
            Rtdb.ref("live/$uid").removeValue().await()
            Rtdb.ref("blacklist/$roomId/$uid").setValue(
                hashMapOf(
                    "name" to (name.ifBlank { pub?.name ?: "" }),
                    "until" to until,
                    "at" to now,
                ),
            ).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gagal menendang user"
        }
    }

    /** Membuka kick: user boleh masuk room lagi. */
    suspend fun unbanFromRoom(roomId: String, uid: String): String? = try {
        Rtdb.ref("blacklist/$roomId/$uid").removeValue().await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Gagal membuka kick"
    }

    fun observeBans(roomId: String, onChange: (List<RoomBan>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("blacklist/$roomId")) { snap ->
            val now = System.currentTimeMillis()
            onChange(
                snap.children.mapNotNull { c ->
                    val id = c.key ?: return@mapNotNull null
                    val b = parseBan(id, c)
                    if (b.active(now)) b else null
                }.sortedBy { it.name.lowercase() },
            )
        }

    fun observeBlacklist(roomId: String, onChange: (Set<String>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("blacklist/$roomId")) { snap ->
            onChange(snap.children.mapNotNull { it.key }.toSet())
        }

    suspend fun leave(roomId: String, uid: String) {
        try {
            val me = Rtdb.ref("members/$roomId/$uid")
            me.onDisconnect().cancel()
            me.removeValue().await()
            Rtdb.ref("live/$uid").removeValue().await()
            val base = Rtdb.ref("seats/$roomId")
            val upd = HashMap<String, Any?>()
            for (c in base.get().await().children) {
                if (c.child("uid").getValue(String::class.java) == uid) upd[c.key!!] = null
            }
            if (upd.isNotEmpty()) base.updateChildren(upd).await()
            // Poin mic ikut di-reset saat user turun kursi, supaya
            // orang yang duduk berikutnya tidak mewarisi poin lama.
            for (k in upd.keys) {
                Rtdb.ref("micPoints/$roomId/$k").removeValue()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    suspend fun takeSeat(roomId: String, index: Int, uid: String, name: String, avatarB64: String?): String? {
        return try {
            val base = Rtdb.ref("seats/$roomId")
            if (Rtdb.ref("locks/$roomId/$index").get().await().exists()) return "Mic ini dikunci oleh owner"
            val present = Rtdb.ref("members/$roomId").get().await().children.mapNotNull { it.key }.toSet()
            val err = Rtdb.tx(base.child(index.toString())) { d ->
                val curUid = d.child("uid").getValue(String::class.java)
                if (curUid != null && curUid != uid && curUid in present) {
                    "Kursi sudah terisi"
                } else {
                    d.value = hashMapOf<String, Any?>("uid" to uid, "name" to name, "avatarB64" to avatarB64, "muted" to false)
                    null
                }
            }
            if (err != null) return err
            val upd = HashMap<String, Any?>()
            for (c in base.get().await().children) {
                if (c.key != index.toString() && c.child("uid").getValue(String::class.java) == uid) upd[c.key!!] = null
            }
            if (upd.isNotEmpty()) base.updateChildren(upd).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gagal naik kursi"
        }
    }

    suspend fun leaveSeat(roomId: String, index: Int) {
        try {
            Rtdb.ref("seats/$roomId/$index").removeValue().await()
            Rtdb.ref("micPoints/$roomId/$index").removeValue()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    /** Turunkan user lain dari kursi mic (owner / admin room). null = sukses. */
    suspend fun removeFromSeat(roomId: String, index: Int): String? = try {
        Rtdb.ref("seats/$roomId/$index").removeValue().await()
        Rtdb.ref("micPoints/$roomId/$index").removeValue()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Gagal menurunkan dari mic"
    }

    suspend fun setSeatMuted(roomId: String, index: Int, muted: Boolean) {
        try {
            Rtdb.tx(Rtdb.ref("seats/$roomId/$index")) { d ->
                if (d.child("uid").value == null) {
                    "Kursi kosong"
                } else {
                    d.child("muted").value = muted
                    null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    suspend fun setMicCount(roomId: String, n: Int): String? {
        return try {
            val ref = roomRef(roomId)
            try { Rtdb.ref("roomsPub/$roomId/micCount").setValue(n.toLong()) } catch (e: Exception) { }
            ref.update("micCount", n).await()
            val seatsRef = Rtdb.ref("seats/$roomId")
            val upd = HashMap<String, Any?>()
            for (c in seatsRef.get().await().children) {
                if ((c.key?.toIntOrNull() ?: 0) >= n) upd[c.key!!] = null
            }
            if (upd.isNotEmpty()) seatsRef.updateChildren(upd).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gagal mengubah mic"
        }
    }

    suspend fun updateSettings(roomId: String, name: String, photoB64: String?, bgB64: String?, announcement: String): String? {
        return try {
            val m = hashMapOf<String, Any?>("name" to name, "announcement" to announcement)
            if (photoB64 != null) m["photoB64"] = photoB64
            if (bgB64 != null) m["backgroundB64"] = bgB64
            // RTDB dulu supaya daftar Party langsung ikut berubah.
            try {
                val rm = hashMapOf<String, Any?>("name" to name, "announcement" to announcement)
                if (photoB64 != null) rm["photoB64"] = photoB64
                Rtdb.ref("roomsPub/$roomId").updateChildren(rm)
            } catch (e: Exception) { }
            roomRef(roomId).update(m).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gagal menyimpan"
        }
    }

    /** OWNER mengundang user yang ada di room naik ke kursi mic (otomatis pindah dari kursi lama). */
    suspend fun inviteSeat(roomId: String, index: Int, uid: String, name: String, avatarB64: String?): String? {
        return try {
            if (Rtdb.ref("locks/$roomId/$index").get().await().exists()) return "Mic ini dikunci"
            // FIX Anti Go After (SVIP3): user yang mengaktifkan tidak bisa diundang ke mic.
            val pub = UserDirectory.findByUid(uid)
            if (pub != null && vipActive(pub.vip, pub.vipUntil) && pub.vip >= 3 && pub.svipOn("antiGoAfter")) {
                return "User ini mengaktifkan Anti Go After"
            }
            val base = Rtdb.ref("seats/$roomId")
            val upd = HashMap<String, Any?>()
            for (c in base.get().await().children) {
                if (c.key != index.toString() && c.child("uid").getValue(String::class.java) == uid) upd[c.key!!] = null
            }
            if (upd.isNotEmpty()) base.updateChildren(upd).await()
            Rtdb.ref("seats/$roomId/$index").setValue(
                hashMapOf<String, Any?>("uid" to uid, "name" to name, "avatarB64" to avatarB64, "muted" to false),
            ).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gagal mengundang ke mic"
        }
    }

    /**
     * Kunci / buka kunci room.
     *
     * FIX "lock room tidak bisa": hash password dulu hanya ditulis ke Firestore,
     * padahal app membaca data room dari Realtime Database (roomsPub). Jadi
     * lockHash tidak pernah sampai dan room selalu dianggap publik.
     * Sekarang ditulis ke RTDB lebih dulu, Firestore hanya cadangan.
     */
    suspend fun setLock(roomId: String, password: String?): String? {
        val h = if (password == null) "" else sha256(password)
        return try {
            Rtdb.ref("roomsPub/" + roomId + "/lockHash").setValue(h).await()
            try {
                roomRef(roomId).update("lockHash", h).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Firestore boleh gagal (aturan/kuota); kunci sudah tersimpan di RTDB.
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gagal mengubah kunci"
        }
    }
}
