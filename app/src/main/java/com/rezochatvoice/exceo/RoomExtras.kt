package com.rezochatvoice.exceo

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
import androidx.compose.ui.res.painterResource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
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
        // UTAMA: Realtime Database (vipCfg) -> tidak kena kuota Firestore.
        try {
            val snap = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("vipCfg").get().await() }
            if (snap != null && snap.exists()) {
                val list = snap.children.mapNotNull { d ->
                    if (d.child("slot").getValue(String::class.java) != "emote") return@mapNotNull null
                    val url = d.child("url").getValue(String::class.java) ?: return@mapNotNull null
                    RoomEmote(
                        d.child("id").getValue(String::class.java) ?: d.key ?: "",
                        d.child("name").getValue(String::class.java) ?: "",
                        url,
                        (d.child("order").getValue(Long::class.java) ?: 0L).toInt(),
                    )
                }.sortedWith(compareBy({ it.order }, { it.id }))
                if (list.isNotEmpty()) {
                    cache = list
                    loadedAt = System.currentTimeMillis()
                    return list
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // Cadangan: emote lama yang masih di Firestore.
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            QuotaGuard.handle(e)
            c ?: emptyList()
        }
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

    /** Kirim pesan berisi gambar (URL hasil upload RoomImages). */
    suspend fun sendImage(roomId: String, uid: String, name: String, url: String, text: String = ""): String? = try {
        Rtdb.ref("messages/$roomId").push().setValue(
            hashMapOf<String, Any?>(
                "text" to text,
                "img" to url,
                "uid" to uid,
                "name" to name,
                "createdAt" to System.currentTimeMillis(),
            ),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal mengirim gambar"
    }

    /** Notifikasi sistem di chat (tanpa nama pengirim), mis. user dikeluarkan dari room. */
    suspend fun sendSystemNotice(roomId: String, text: String): String? = addMessage(roomId, text)

    suspend fun sendGiftMessage(roomId: String, name: String, g: Gift, count: Int, to: String = ""): String? {
        // Gift cashback punya penanda sendiri (💎) supaya banner gift biasa tidak ikut muncul.
        val tag = if (g.cashback || g.cbRate > 0) "\uD83D\uDC8E" else "🎁"
        return addMessage(roomId, "$tag $name mengirim ${g.emoji} ${g.name} x$count" + (if (to.isNotBlank()) " ke $to" else ""))
    }

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
fun RoomTypingBar(
    text: String,
    myFam: String = "",
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    onPickImage: () -> Unit = {},
    imgBusy: Boolean = false,
) {
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
        // Kirim gambar dari galeri, di sebelah kiri kolom ketik.
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x33FFFFFF))
                .clickable(enabled = !imgBusy) { onPickImage() },
            contentAlignment = Alignment.Center,
        ) {
            if (imgBusy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Gold, strokeWidth = 2.dp)
            } else {
                Image(
                    painterResource(R.drawable.ic_chat_image),
                    contentDescription = tr("Kirim gambar"),
                    modifier = Modifier.size(22.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
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
        IconButton(onClick = onClose) { Text(tr("✕"), color = Color.White, fontSize = 18.sp) }
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

/**
 * Banner khusus gift cashback. Punya bentuk dan warna sendiri, dan hanya muncul
 * saat jackpot cashback 10.000 koin ke atas.
 */
@Composable
fun CashbackBanner(roomId: String) {
    var data by remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
    var lastTs by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(roomId) {
        val reg = Rtdb.listen(Rtdb.ref("giftCbBanner")) { snap ->
            val id = snap.child("roomId").getValue(String::class.java) ?: ""
            val cb = snap.child("cb").getValue(Long::class.java) ?: 0L
            val ts = snap.child("ts").getValue(Long::class.java) ?: 0L
            val from = snap.child("from").getValue(String::class.java) ?: ""
            val gfit = snap.child("gift").getValue(String::class.java) ?: ""
            val prev = lastTs
            lastTs = ts
            if (id != roomId || cb < 10_000L) return@listen
            if (prev != null && ts == prev) return@listen
            data = Triple(if (from.isBlank()) gfit else "$from • $gfit", cb, ts)
        }
        onDispose { reg.remove() }
    }
    LaunchedEffect(data) {
        if (data != null) {
            delay(3500)
            data = null
        }
    }
    val d = data ?: return
    Box(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 70.dp, start = 16.dp, end = 16.dp)
            .geserTutup(d) { data = null },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF0E8F62), Color(0xFF1FBF7A), Color(0xFFD4AF37))))
                .border(1.dp, Color(0xFFFFE082), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("\uD83D\uDC8E CASHBACK JP"),
                    color = Color(0xFF07301F), fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, fontSize = 13.sp,
                )
                Text(tr("${d.first}  +${fmtNum(d.second)}"),
                    color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Banner di atas layar bisa ditutup dengan menggesernya ke atas. Dipakai bersama
 * oleh banner gift, banner cashback JP, dan banner efek masuk.
 * [onDismiss] dipanggil setelah banner digeser cukup jauh ke atas.
 */
@Composable
fun Modifier.geserTutup(key: Any?, onDismiss: () -> Unit): Modifier {
    val offsetY = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ambang = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
    return this
        .graphicsLayer {
            translationY = offsetY.value
            alpha = (1f + offsetY.value / (ambang * 4f)).coerceIn(0f, 1f)
        }
        .pointerInput(key) {
            detectVerticalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (offsetY.value < -ambang) {
                            offsetY.animateTo(-ambang * 6f, androidx.compose.animation.core.tween(160))
                            onDismiss()
                        } else {
                            offsetY.animateTo(0f, androidx.compose.animation.core.tween(140))
                        }
                    }
                },
                onDragCancel = { scope.launch { offsetY.animateTo(0f) } },
            ) { change: androidx.compose.ui.input.pointer.PointerInputChange, drag: Float ->
                change.consume()
                // Hanya boleh digeser ke atas.
                scope.launch { offsetY.snapTo((offsetY.value + drag).coerceAtMost(0f)) }
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
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 70.dp, start = 16.dp, end = 16.dp)
                .geserTutup(t) { text = null },
            contentAlignment = Alignment.Center,
        ) {
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
