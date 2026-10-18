package com.example.googlelogin

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

data class SeatEmote(val emoji: String, val at: Long)

val EMOTES = listOf("😀", "😂", "🤣", "😍", "🥰", "😎", "😭", "😡", "🤔", "👍", "👏", "🙏", "🎉", "🔥", "💋", "😴")

object RoomExtras {
    private fun db() = FirebaseFirestore.getInstance()
    private fun room(id: String) = db().collection("rooms").document(id)

    private suspend fun addMessage(roomId: String, text: String): String? = try {
        room(roomId).collection("messages").add(
            hashMapOf<String, Any?>("text" to text, "createdAt" to System.currentTimeMillis()),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengirim"
    }

    suspend fun sendChat(roomId: String, name: String, text: String): String? =
        addMessage(roomId, "$name: $text")

    suspend fun sendGiftMessage(roomId: String, name: String, g: Gift, count: Int, to: String = ""): String? =
        addMessage(roomId, "🎁 $name mengirim ${g.emoji} ${g.name} x$count" + (if (to.isNotBlank()) " ke $to" else ""))

    suspend fun sendEmote(roomId: String, uid: String, emoji: String): String? = try {
        room(roomId).collection("emotes").document(uid).set(
            hashMapOf<String, Any?>("emoji" to emoji, "at" to System.currentTimeMillis()),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengirim emot"
    }

    fun observeEmotes(roomId: String, onChange: (Map<String, SeatEmote>) -> Unit): ListenerRegistration =
        room(roomId).collection("emotes").addSnapshotListener { snap, _ ->
            if (snap != null) {
                onChange(snap.documents.associate { it.id to SeatEmote(it.getString("emoji") ?: "", it.getLong("at") ?: 0L) })
            }
        }

    /** Kunci mic: kursi dikosongkan dan tidak bisa dinaiki sampai dibuka owner. */
    suspend fun lockSeat(roomId: String, index: Int): String? = try {
        val b = db().batch()
        b.update(room(roomId), "lockedSeats", FieldValue.arrayUnion(index))
        b.delete(room(roomId).collection("seats").document(index.toString()))
        b.commit().await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengunci mic"
    }

    suspend fun unlockSeat(roomId: String, index: Int): String? = try {
        room(roomId).update("lockedSeats", FieldValue.arrayRemove(index)).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal membuka kunci"
    }
}

@Composable
fun RoomTypingBar(onSend: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        if (text.isNotBlank()) {
            onSend(text.trim())
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 120) text = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            placeholder = { Text(tr("Ketik pesan...")) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            shape = RoundedCornerShape(24.dp),
            colors = goldFieldColors(),
        )
        IconButton(onClick = { submit() }) { Icon(Icons.AutoMirrored.Filled.Send, "Kirim", tint = Gold) }
        IconButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 18.sp) }
    }
}

@Composable
fun RoomEmoteBar(onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xCC000000))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EMOTES.forEach { e ->
            Text(e, fontSize = 28.sp, modifier = Modifier.clickable { onPick(e) }.padding(4.dp))
        }
    }
}

/** Banner singkat saat ada yang mengirim gift di room. */
@Composable
fun GiftBanner(roomId: String) {
    var text by remember { mutableStateOf<String?>(null) }
    var lastId by remember { mutableStateOf<String?>(null) }
    var first by remember { mutableStateOf(true) }
    DisposableEffect(roomId) {
        val reg = RoomRepo.observeMessages(roomId, 1) { list ->
            val last = list.lastOrNull()
            if (first) {
                first = false
                lastId = last?.id
            } else if (last != null && last.id != lastId) {
                lastId = last.id
                if (last.text.startsWith("🎁")) text = last.text
            }
        }
        onDispose { reg.remove() }
    }
    LaunchedEffect(text) {
        if (text != null) {
            delay(3500)
            text = null
        }
    }
    val t = text
    if (t != null) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 70.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF5B2BD9), Color(0xFFC9A227))))
                    .border(1.dp, Gold, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text(t, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center) }
        }
    }
}
