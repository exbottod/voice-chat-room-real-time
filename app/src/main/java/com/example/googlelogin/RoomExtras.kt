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
import com.google.firebase.auth.FirebaseAuth
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

data class SeatEmote(val emoji: String, val at: Long)

/** Emote kustom yang di-upload dari panel developer (gif/png/jpg). */
data class RoomEmote(val id: String, val name: String, val url: String, val order: Int = 0)

/** Daftar emote kustom. Cache 5 menit supaya hemat kuota Firestore. */
object RoomEmotes {
    private const val TTL = 5 * 60_000L
    private var cache: List<RoomEmote>? = null
    private var loadedAt = 0L

    suspend fun load(force: Boolean = false): List<RoomEmote> {
        val c = cache
        if (!force && c != null && System.currentTimeMillis() - loadedAt < TTL) return c
        return try {
            val snap = FirebaseFirestore.getInstance().collection("vipConfig")
                .whereEqualTo("slot", "emote").get().await()
            val list = snap.documents.mapNotNull { d ->
                val url = d.getString("url") ?: return@mapNotNull null
                RoomEmote(d.getString("id") ?: d.id, d.getString("name") ?: "", url, (d.getLong("order") ?: 0L).toInt())
            }.sortedWith(compareBy({ it.order }, { it.id }))
            cache = list
            loadedAt = System.currentTimeMillis()
            list
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { c ?: emptyList() }
    }
}


object RoomExtras {
    private fun db() = FirebaseFirestore.getInstance()
    private fun room(id: String) = db().collection("rooms").document(id)

    private suspend fun addMessage(roomId: String, text: String): String? = try {
        Rtdb.ref("messages/$roomId").push().setValue(
            hashMapOf<String, Any?>("text" to text, "createdAt" to System.currentTimeMillis()),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengirim"
    }

    suspend fun sendChat(roomId: String, uid: String, name: String, text: String): String? = try {
        Rtdb.ref("messages/$roomId").push().setValue(
            hashMapOf<String, Any?>(
                "text" to text,
                "uid" to uid,
                "name" to name,
                "createdAt" to System.currentTimeMillis(),
            ),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengirim"
    }

    suspend fun sendGiftMessage(roomId: String, name: String, g: Gift, count: Int, to: String = ""): String? =
        addMessage(roomId, "🎁 $name mengirim ${g.emoji} ${g.name} x$count" + (if (to.isNotBlank()) " ke $to" else ""))

    suspend fun sendEmote(roomId: String, uid: String, emoji: String): String? = try {
        Rtdb.ref("emotes/$roomId/$uid").setValue(
            hashMapOf<String, Any?>("emoji" to emoji, "at" to System.currentTimeMillis()),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengirim emot"
    }

    fun observeEmotes(roomId: String, onChange: (Map<String, SeatEmote>) -> Unit): Reg =
        Rtdb.listen(Rtdb.ref("emotes/$roomId")) { snap ->
            onChange(
                snap.children.associate {
                    (it.key ?: "") to SeatEmote(
                        it.child("emoji").getValue(String::class.java) ?: "",
                        (it.child("at").value as? Number)?.toLong() ?: 0L,
                    )
                },
            )
        }

    /** Kunci mic: kursi dikosongkan dan tidak bisa dinaiki sampai dibuka owner. */
    suspend fun lockSeat(roomId: String, index: Int): String? = try {
        Rtdb.root().updateChildren(
            hashMapOf<String, Any?>("locks/$roomId/$index" to true, "seats/$roomId/$index" to null),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengunci mic"
    }

    suspend fun unlockSeat(roomId: String, index: Int): String? = try {
        Rtdb.ref("locks/$roomId/$index").removeValue().await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal membuka kunci"
    }
}

@Composable
fun RoomTypingBar(text: String, myFam: String = "", onTextChange: (String) -> Unit, onSend: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        if (text.isNotBlank()) {
            onSend(text.trim())
            onTextChange("")
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (myFam.isNotBlank()) FamTag3D(myFam)
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 120) onTextChange(it) },
            // Gelembung chat tinggi tetap 64dp supaya tidak "menciut" saat mengetik,
            // dan teks punya jarak lega ke garis luar gelembung.
            modifier = Modifier.weight(1f).focusRequester(focus).height(64.dp),
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
fun RoomEmoteBar(emotes: List<RoomEmote> = emptyList(), onPick: (String) -> Unit) {
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
        // Hanya emote kustom dari panel developer (gif/png/jpg); emote bawaan dihapus.
        emotes.forEach { em ->
            AsyncImage(
                model = em.url,
                contentDescription = em.name.ifBlank { "emote" },
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(34.dp).clickable { onPick(em.url) }.padding(2.dp),
            )
        }
    }
}

/** Banner singkat saat ada yang mengirim gift di room. */
@Composable
fun GiftBanner(roomId: String) {
    var text by remember { mutableStateOf<String?>(null) }
    // Hide top gift banners (SVIP1): sembunyikan banner gift di bagian atas.
    var hideBanner by remember { mutableStateOf(false) }
    val myUid = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    LaunchedEffect(myUid) { if (myUid.isNotEmpty()) hideBanner = SvipRepo.myOn(myUid, "hideGiftBanner", 1) }
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
    if (t != null && !hideBanner) {
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
