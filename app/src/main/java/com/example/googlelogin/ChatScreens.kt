package com.example.googlelogin

import android.media.RingtoneManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class ChatSummary(
    val id: String,
    val otherUid: String,
    val otherName: String,
    val otherId: String,
    val lastText: String,
    val lastAt: Long,
)

data class ChatMsg(val id: String, val from: String, val text: String, val at: Long)

object ChatRepo {
    private fun db() = FirebaseFirestore.getInstance()

    fun chatId(a: String, b: String) = if (a < b) "${a}_$b" else "${b}_$a"

    fun observeChats(myUid: String, onChange: (List<ChatSummary>) -> Unit): Reg {
        // UTAMA: Realtime Database chatsByUser/{uid} (hemat kuota Firestore).
        val byId = HashMap<String, ChatSummary>()
        fun emit() = onChange(byId.values.sortedByDescending { it.lastAt })
        val rt = Rtdb.listen(Rtdb.ref("chatsByUser/$myUid")) { s ->
            byId.keys.removeAll { it.startsWith("rt_") }
            s.children.forEach { c ->
                val cid = c.key ?: return@forEach
                val otherUid = c.child("otherUid").getValue(String::class.java) ?: return@forEach
                byId["rt_$cid"] = ChatSummary(
                    id = cid,
                    otherUid = otherUid,
                    otherName = c.child("otherName").getValue(String::class.java) ?: tr("Pengguna"),
                    otherId = c.child("otherId").getValue(String::class.java) ?: "",
                    lastText = c.child("lastText").getValue(String::class.java) ?: "",
                    lastAt = c.child("lastAt").getValue(Long::class.java) ?: 0L,
                )
            }
            emit()
        }
        // Riwayat lama di Firestore: dibaca SEKALI per buka tab, dan HANYA kalau
        // daftar dari RTDB masih kosong (hemat kuota Firestore).
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(2000L)
            if (byId.isNotEmpty()) return@launch
            try {
                val snap = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                    db().collection("chats").whereArrayContains("members", myUid).get().await()
                }
                if (snap != null) {
                    snap.documents.forEach { d ->
                        if (byId.containsKey("rt_" + d.id)) return@forEach
                        val members = (d.get("members") as? List<*>)?.filterIsInstance<String>() ?: return@forEach
                        val other = members.firstOrNull { it != myUid } ?: return@forEach
                        val names = d.get("names") as? Map<*, *>
                        val ids = d.get("ids") as? Map<*, *>
                        byId["fs_" + d.id] = ChatSummary(
                            id = d.id,
                            otherUid = other,
                            otherName = names?.get(other) as? String ?: tr("Pengguna"),
                            otherId = ids?.get(other) as? String ?: "",
                            lastText = d.getString("lastText") ?: "",
                            lastAt = d.getLong("lastAt") ?: 0L,
                        )
                    }
                    emit()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
            }
        }
        return rt
    }

    fun observeMessages(chatId: String, onChange: (List<ChatMsg>) -> Unit): ListenerRegistration =
        db().collection("chats").document(chatId).collection("messages")
            .orderBy("at").limitToLast(100)
            .addSnapshotListener { snap, _ ->
                if (snap != null) {
                    onChange(
                        snap.documents.map {
                            ChatMsg(it.id, it.getString("from") ?: "", it.getString("text") ?: "", it.getLong("at") ?: 0L)
                        },
                    )
                }
            }

    suspend fun send(me: String, myName: String, myId: String, other: PublicUser, text: String): String? {
        // Avoid disturbing (SVIP2): hanya orang yang DIA ikuti boleh kirim pesan padanya.
        if (other.svipOn("avoidDisturbing") && vipActive(other.vip, other.vipUntil) && other.vip >= 2) {
            val ok = try {
                FollowRepo.isFollowing(other.uid, me)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) return tr("User ini mengaktifkan Avoid disturbing (hanya menerima pesan dari orang yang dia ikuti)")
        }
        // UTAMA: REALTIME DATABASE (chats/{id} + chatsByUser kedua pihak) -
        // kirim pesan tidak lagi kena kuota Firestore.
        val id = chatId(me, other.uid)
        val now = System.currentTimeMillis()
        try {
            val ok = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                val root = Rtdb.ref("chats/$id")
                root.child("messages").push().setValue(
                    hashMapOf<String, Any?>("from" to me, "text" to text, "at" to now),
                ).await()
                root.updateChildren(
                    hashMapOf<String, Any?>(
                        "members" to mapOf(me to true, other.uid to true),
                        "names" to mapOf(me to myName, other.uid to other.name),
                        "ids" to mapOf(me to myId, other.uid to other.userId),
                        "lastText" to text,
                        "lastAt" to now,
                    ),
                ).await()
                Rtdb.ref("chatsByUser/$me/$id").updateChildren(
                    hashMapOf<String, Any?>(
                        "otherUid" to other.uid, "otherName" to other.name,
                        "otherId" to other.userId, "lastText" to text, "lastAt" to now,
                    ),
                ).await()
                Rtdb.ref("chatsByUser/${other.uid}/$id").updateChildren(
                    hashMapOf<String, Any?>(
                        "otherUid" to me, "otherName" to myName,
                        "otherId" to myId, "lastText" to text, "lastAt" to now,
                    ),
                ).await()
            }
            if (ok == null) return tr("Koneksi lambat, coba lagi")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: tr("Gagal mengirim")
        }
        // Cermin Firestore best-effort supaya versi app lama tetap menerima pesan.
        try {
            val ref = db().collection("chats").document(id)
            val b = db().batch()
            b.set(
                ref,
                hashMapOf<String, Any?>(
                    "members" to listOf(me, other.uid),
                    "names" to mapOf(me to myName, other.uid to other.name),
                    "ids" to mapOf(me to myId, other.uid to other.userId),
                    "lastText" to text,
                    "lastAt" to now,
                ),
                SetOptions.merge(),
            )
            b.set(ref.collection("messages").document(), hashMapOf<String, Any?>("from" to me, "text" to text, "at" to now))
            kotlinx.coroutines.withTimeoutOrNull(4000L) { b.commit().await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
        return null
    }

    /** DATABASE CADANGAN: kirim pesan lewat RTDB chats/{chatId} (kuota terpisah).
     *  Pesan otomatis tampil juga di lawan bicara yang kuotanya habis. */
    private suspend fun sendRtdb(me: String, myName: String, myId: String, other: PublicUser, text: String): String? {
        return try {
            val id = chatId(me, other.uid)
            val now = System.currentTimeMillis()
            val root = Rtdb.ref("chats/$id")
            root.child("messages").push().setValue(
                hashMapOf<String, Any?>("from" to me, "text" to text, "at" to now),
            ).await()
            root.updateChildren(
                hashMapOf<String, Any?>(
                    "members" to mapOf(me to true, other.uid to true),
                    "names" to mapOf(me to myName, other.uid to other.name),
                    "ids" to mapOf(me to myId, other.uid to other.userId),
                    "lastText" to text,
                    "lastAt" to now,
                ),
            ).await()
            Rtdb.ref("chatsByUser/$me/$id").updateChildren(
                hashMapOf<String, Any?>(
                    "otherUid" to other.uid, "otherName" to other.name,
                    "otherId" to other.userId, "lastText" to text, "lastAt" to now,
                ),
            ).await()
            Rtdb.ref("chatsByUser/${other.uid}/$id").updateChildren(
                hashMapOf<String, Any?>(
                    "otherUid" to me, "otherName" to myName,
                    "otherId" to myId, "lastText" to text, "lastAt" to now,
                ),
            ).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: tr("Gagal mengirim")
        }
    }

    /** Gabung pesan dari Firestore + cadangan RTDB, urut waktu, tanpa dobel. */
    /** Pesan chat: REALTIME DATABASE realtime (utama). Riwayat lama di Firestore
     *  dibaca SEKALI saja (tanpa listener) supaya kuota Firestore tidak terbakar. */
    fun observeMessagesMerged(chatId: String, onChange: (List<ChatMsg>) -> Unit): Reg {
        val merged = HashMap<String, ChatMsg>()
        fun emit() = onChange(merged.values.sortedBy { it.at })
        val rt = Rtdb.listen(Rtdb.ref("chats/$chatId/messages")) { s ->
            merged.keys.removeAll { it.startsWith("rt_") }
            s.children.forEach { c ->
                val from = c.child("from").getValue(String::class.java) ?: return@forEach
                merged["rt_" + (c.key ?: c.key.hashCode().toString())] = ChatMsg(
                    "rt_" + (c.key ?: ""), from,
                    c.child("text").getValue(String::class.java) ?: "",
                    c.child("at").getValue(Long::class.java) ?: 0L,
                )
            }
            emit()
        }
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val snap = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                    db().collection("chats").document(chatId).collection("messages")
                        .orderBy("at").limitToLast(100).get().await()
                }
                if (snap != null && merged.keys.none { it.startsWith("fs_") }) {
                    snap.documents.forEach {
                        merged["fs_" + it.id] = ChatMsg(it.id, it.getString("from") ?: "", it.getString("text") ?: "", it.getLong("at") ?: 0L)
                    }
                    emit()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) { }
        }
        return Reg { rt.remove() }
    }
}

@Composable
private fun ChatRow(c: ChatSummary) {
    val actions = LocalActions.current
    var u by remember(c.otherUid) { mutableStateOf(UserCache.peek(c.otherUid)) }
    LaunchedEffect(c.otherUid) { if (u == null) u = UserCache.byUid(c.otherUid) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                actions.openChat(u ?: PublicUser(c.otherId, c.otherUid, c.otherName, "ID", "", null, null))
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clickable { actions.openProfile(c.otherUid) }) { PublicAvatarFramed(u, 52.dp, c.otherName) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u?.name ?: c.otherName, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
            Text(c.lastText, color = Color(0xFFBDBDBD), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(timeAgo(c.lastAt), color = Color(0xFF8A8A8A), fontSize = 11.sp)
    }
}

/** Isi tab Pesan: daftar chat pribadi. */
@Composable
fun ChatListTab(myUid: String) {
    var chats by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    DisposableEffect(myUid) {
        val r = ChatRepo.observeChats(myUid) { chats = it }
        onDispose { r.remove() }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Pesan", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    tr("Belum ada pesan.\nBuka profil seseorang lalu tekan Chat."),
                    color = Color(0xFF8A8A8A), textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(chats, key = { it.id }) { c -> ChatRow(c) }
            }
        }
    }
}

@Composable
fun ChatScreen(myUid: String, myName: String, myId: String, other: PublicUser, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current
    val chatId = remember(other.uid) { ChatRepo.chatId(myUid, other.uid) }
    var msgs by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(chatId) {
        val r = ChatRepo.observeMessagesMerged(chatId) { msgs = it }
        onDispose { r.remove() }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    BackHandler(onBack = onBack)

    // Mute ringtone (SVIP1): bunyi notifikasi saat pesan masuk, kecuali di-mute.
    var prevCount by remember { mutableStateOf(-1) }
    LaunchedEffect(msgs.size) {
        val last = msgs.lastOrNull()
        if (last != null && last.from != myUid && prevCount > 0 && msgs.size > prevCount) {
            if (!SvipRepo.myOn(myUid, "muteRingtone", 1)) {
                try {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    RingtoneManager.getRingtone(context, uri)?.play()
                } catch (e: Exception) { }
            }
        }
        prevCount = msgs.size
    }

    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        text = ""
        scope.launch {
            ChatRepo.send(myUid, myName, myId, other, t)?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    OverlayBox {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = GoldLight)
                }
                Row(
                    Modifier.clickable { actions.openProfile(other.uid) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PublicAvatarFramed(other, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(other.name, color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Personal Ads (SVIP1): iklan pribadi pemilik profil tampil di kotak masuk.
            if (other.ad.isNotBlank()) {
                Text(
                    "📢 ${other.ad}",
                    color = Color(0xFFFFE082),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0x22FFC107)).padding(10.dp),
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(msgs, key = { it.id }) { m ->
                    val mine = m.from == myUid
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (mine) Gold else Color(0xFF1E1E1E))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(m.text, color = if (mine) Color.Black else Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 500) text = it },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    placeholder = { Text(tr("Tulis pesan...")) },
                    maxLines = 3,
                    shape = RoundedCornerShape(24.dp),
                    colors = goldFieldColors(),
                )
                IconButton(onClick = { send() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Kirim", tint = Gold)
                }
            }
        }
    }
}
