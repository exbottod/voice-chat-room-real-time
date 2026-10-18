package com.example.googlelogin

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
) {
    val locked: Boolean get() = lockHash.isNotEmpty()
}

data class Seat(val index: Int, val uid: String, val name: String, val avatarB64: String?, val muted: Boolean)
data class RoomMsg(val id: String, val text: String, val createdAt: Long)

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
        )
    }

    suspend fun createRoom(ownerUid: String, ownerName: String, name: String, photoB64: String?): RoomInfo {
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
        ref.set(data).await()
        return RoomInfo(ref.id, no, name, ownerUid, ownerName, photoB64, null, "", 8, "", 0)
    }

    fun observeRooms(onChange: (List<RoomInfo>) -> Unit): ListenerRegistration =
        db().collection("rooms").orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { it.toRoom() })
            }

    fun observeRoom(id: String, onChange: (RoomInfo?) -> Unit): ListenerRegistration =
        roomRef(id).addSnapshotListener { snap, _ -> onChange(snap?.toRoom()) }

    fun observeSeats(id: String, onChange: (Map<Int, Seat>) -> Unit): ListenerRegistration =
        roomRef(id).collection("seats").addSnapshotListener { snap, _ ->
            if (snap != null) {
                val list = snap.documents.mapNotNull { d ->
                    val i = d.id.toIntOrNull() ?: return@mapNotNull null
                    Seat(i, d.getString("uid") ?: "", d.getString("name") ?: "", d.getString("avatarB64"), d.getBoolean("muted") == true)
                }
                onChange(list.associateBy { it.index })
            }
        }

    fun observeMessages(id: String, limit: Int = 50, onChange: (List<RoomMsg>) -> Unit): ListenerRegistration =
        roomRef(id).collection("messages").orderBy("createdAt").limitToLast(limit.toLong())
            .addSnapshotListener { snap, _ ->
                if (snap != null) {
                    onChange(snap.documents.map { RoomMsg(it.id, it.getString("text") ?: "", it.getLong("createdAt") ?: 0L) })
                }
            }

    suspend fun join(roomId: String, name: String) {
        try {
            val ref = roomRef(roomId)
            ref.update("online", FieldValue.increment(1)).await()
            ref.collection("messages").add(
                hashMapOf<String, Any?>("text" to "$name masuk kamar", "createdAt" to System.currentTimeMillis())
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        }
    }

    suspend fun leave(roomId: String, uid: String) {
        try {
            val ref = roomRef(roomId)
            val mine = ref.collection("seats").whereEqualTo("uid", uid).get().await()
            mine.documents.forEach { it.reference.delete() }
            ref.update("online", FieldValue.increment(-1)).await()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        }
    }

    suspend fun takeSeat(roomId: String, index: Int, uid: String, name: String, avatarB64: String?): String? {
        return try {
            val seats = roomRef(roomId).collection("seats")
            val mine = seats.whereEqualTo("uid", uid).get().await()
            db().runTransaction { tx ->
                val target = seats.document(index.toString())
                val lockedList = (tx.get(roomRef(roomId)).get("lockedSeats") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
                if (lockedList.contains(index)) throw IllegalStateException("Mic ini dikunci oleh owner")
                val cur = tx.get(target)
                val curUid = cur.getString("uid")
                if (cur.exists() && curUid != null && curUid != uid) {
                    throw IllegalStateException("Kursi sudah terisi")
                }
                mine.documents.forEach { if (it.id != index.toString()) tx.delete(it.reference) }
                tx.set(
                    target,
                    hashMapOf<String, Any?>("uid" to uid, "name" to name, "avatarB64" to avatarB64, "muted" to false),
                )
                true
            }.await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            e.message ?: "Gagal naik kursi"
        }
    }

    suspend fun leaveSeat(roomId: String, index: Int) {
        try {
            roomRef(roomId).collection("seats").document(index.toString()).delete().await()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        }
    }

    suspend fun setSeatMuted(roomId: String, index: Int, muted: Boolean) {
        try {
            roomRef(roomId).collection("seats").document(index.toString()).update("muted", muted).await()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        }
    }

    suspend fun setMicCount(roomId: String, n: Int): String? {
        return try {
            val ref = roomRef(roomId)
            ref.update("micCount", n).await()
            val seats = ref.collection("seats").get().await()
            val over = seats.documents.filter { (it.id.toIntOrNull() ?: 0) >= n }
            if (over.isNotEmpty()) {
                val b = db().batch()
                over.forEach { b.delete(it.reference) }
                b.commit().await()
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            e.message ?: "Gagal mengubah mic"
        }
    }

    suspend fun updateSettings(roomId: String, name: String, photoB64: String?, bgB64: String?, announcement: String): String? {
        return try {
            val m = hashMapOf<String, Any?>("name" to name, "announcement" to announcement)
            if (photoB64 != null) m["photoB64"] = photoB64
            if (bgB64 != null) m["backgroundB64"] = bgB64
            roomRef(roomId).update(m).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            e.message ?: "Gagal menyimpan"
        }
    }

    suspend fun setLock(roomId: String, password: String?): String? {
        return try {
            roomRef(roomId).update("lockHash", if (password == null) "" else sha256(password)).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            e.message ?: "Gagal mengubah kunci"
        }
    }
}
