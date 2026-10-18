package com.example.googlelogin

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Query
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

const val RTDB_URL = "https://arboreal-cat-511705-p6-default-rtdb.asia-southeast1.firebasedatabase.app"

/** Pembungkus sederhana supaya pemanggil cukup memanggil remove(). */
class Reg(private val onRemove: () -> Unit) {
    fun remove() = onRemove()
}

object Rtdb {
    fun root(): DatabaseReference = FirebaseDatabase.getInstance(RTDB_URL).reference
    fun ref(path: String): DatabaseReference = root().child(path)

    fun listen(q: Query, onData: (DataSnapshot) -> Unit): Reg {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onData(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        q.addValueEventListener(l)
        return Reg { q.removeEventListener(l) }
    }

    /** Transaksi pada satu node. Return null kalau sukses, atau pesan error/alasan batal. */
    suspend fun tx(ref: DatabaseReference, update: (MutableData) -> String?): String? =
        suspendCancellableCoroutine { cont ->
            var abortMsg: String? = null
            ref.runTransaction(object : Transaction.Handler {
                override fun doTransaction(d: MutableData): Transaction.Result {
                    abortMsg = update(d)
                    return if (abortMsg != null) Transaction.abort() else Transaction.success(d)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    if (cont.isActive) {
                        cont.resume(
                            when {
                                error != null -> error.message
                                !committed -> abortMsg ?: "Dibatalkan"
                                else -> null
                            },
                        )
                    }
                }
            })
        }
}
