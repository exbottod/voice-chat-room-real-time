package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class Moment(
    val id: String,
    val uid: String,
    val name: String,
    val text: String,
    val photoB64: String?,
    val createdAt: Long,
    val likes: Int,
    val comments: Int,
)

data class MomentComment(val id: String, val uid: String, val name: String, val text: String, val at: Long)

object MomentRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun ref(id: String) = db().collection("moments").document(id)

    fun observe(onChange: (List<Moment>) -> Unit): ListenerRegistration =
        db().collection("moments").orderBy("createdAt", Query.Direction.DESCENDING).limit(30)
            .addSnapshotListener { snap, _ ->
                if (snap != null) {
                    onChange(
                        snap.documents.map {
                            Moment(
                                id = it.id,
                                uid = it.getString("uid") ?: "",
                                name = it.getString("name") ?: "Pengguna",
                                text = it.getString("text") ?: "",
                                photoB64 = it.getString("photoB64"),
                                createdAt = it.getLong("createdAt") ?: 0L,
                                likes = (it.getLong("likeCount") ?: 0L).toInt().coerceAtLeast(0),
                                comments = (it.getLong("commentCount") ?: 0L).toInt().coerceAtLeast(0),
                            )
                        },
                    )
                }
            }

    suspend fun byUid(uid: String): List<Moment> =
        db().collection("moments").whereEqualTo("uid", uid).limit(50).get().await().documents.map {
            Moment(
                id = it.id,
                uid = it.getString("uid") ?: "",
                name = it.getString("name") ?: "Pengguna",
                text = it.getString("text") ?: "",
                photoB64 = it.getString("photoB64"),
                createdAt = it.getLong("createdAt") ?: 0L,
                likes = (it.getLong("likeCount") ?: 0L).toInt().coerceAtLeast(0),
                comments = (it.getLong("commentCount") ?: 0L).toInt().coerceAtLeast(0),
            )
        }.sortedByDescending { it.createdAt }

    suspend fun post(uid: String, name: String, text: String, photoB64: String?): String? = try {
        db().collection("moments").add(
            hashMapOf<String, Any?>(
                "uid" to uid, "name" to name, "text" to text, "photoB64" to photoB64,
                "createdAt" to System.currentTimeMillis(), "likeCount" to 0, "commentCount" to 0,
            ),
        ).await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal posting"
    }

    suspend fun isLiked(id: String, uid: String): Boolean =
        ref(id).collection("likes").document(uid).get().await().exists()

    suspend fun setLike(id: String, uid: String, like: Boolean): String? = try {
        val b = db().batch()
        val likeRef = ref(id).collection("likes").document(uid)
        if (like) b.set(likeRef, hashMapOf<String, Any?>("at" to System.currentTimeMillis())) else b.delete(likeRef)
        b.update(ref(id), "likeCount", FieldValue.increment(if (like) 1L else -1L))
        b.commit().await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal"
    }

    fun observeComments(id: String, onChange: (List<MomentComment>) -> Unit): ListenerRegistration =
        ref(id).collection("comments").orderBy("at").limitToLast(100).addSnapshotListener { snap, _ ->
            if (snap != null) {
                onChange(
                    snap.documents.map {
                        MomentComment(it.id, it.getString("uid") ?: "", it.getString("name") ?: "", it.getString("text") ?: "", it.getLong("at") ?: 0L)
                    },
                )
            }
        }

    suspend fun addComment(id: String, uid: String, name: String, text: String): String? = try {
        val b = db().batch()
        b.set(
            ref(id).collection("comments").document(),
            hashMapOf<String, Any?>("uid" to uid, "name" to name, "text" to text, "at" to System.currentTimeMillis()),
        )
        b.update(ref(id), "commentCount", FieldValue.increment(1L))
        b.commit().await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        e.message ?: "Gagal"
    }
}

@Composable
private fun MomentCard(m: Moment, myUid: String, onComments: (Moment) -> Unit) {
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var author by remember(m.uid) { mutableStateOf(UserCache.peek(m.uid)) }
    var liked by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.uid) { if (author == null) author = UserCache.byUid(m.uid) }
    LaunchedEffect(m.id) { liked = try { MomentRepo.isLiked(m.id, myUid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false } }
    val photo = remember(m.photoB64) { ProfileRepo.decodeB64(m.photoB64) }
    val shape = RoundedCornerShape(16.dp)
    var viewPhoto by remember { mutableStateOf(false) }

    // Review foto full screen: ketuk foto momen untuk memperbesar.
    if (viewPhoto && photo != null) {
        Dialog(
            onDismissRequest = { viewPhoto = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            var zoom by remember { mutableStateOf(1f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, z, _ ->
                            zoom = (zoom * z).coerceIn(1f, 6f)
                        }
                    }
                    .clickable { viewPhoto = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    photo, null,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = zoom; scaleY = zoom }
                        .clickable { viewPhoto = false },
                    contentScale = ContentScale.Fit,
                )
                Text(
                    tr("Ketuk untuk menutup"),
                    color = Color(0xFF9A9A9A),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBlack.copy(alpha = 0.9f))
            .border(1.dp, Gold.copy(alpha = 0.3f), shape)
            .padding(14.dp),
    ) {
        Row(Modifier.clickable { actions.openProfile(m.uid) }, verticalAlignment = Alignment.CenterVertically) {
            PublicAvatarFramed(author, 42.dp, m.name)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(author?.name ?: m.name, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(timeAgo(m.createdAt), color = Color(0xFF8A8A8A), fontSize = 12.sp)
            }
        }
        if (m.text.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(m.text, color = Color.White, fontSize = 15.sp)
        }
        if (photo != null) {
            Spacer(Modifier.height(10.dp))
            Image(
                photo, null,
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewPhoto = true },
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable {
                    val want = !liked
                    liked = want
                    scope.launch {
                        MomentRepo.setLike(m.id, myUid, want)?.let {
                            liked = !want
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (liked) "❤️" else "🤍", fontSize = 20.sp)
                Spacer(Modifier.width(4.dp))
                Text("${m.likes}", color = Color(0xFFD0D0D0), fontSize = 14.sp)
            }
            Spacer(Modifier.width(16.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable { onComments(m) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("💬", fontSize = 20.sp)
                Spacer(Modifier.width(4.dp))
                Text("${m.comments}", color = Color(0xFFD0D0D0), fontSize = 14.sp)
            }
        }
    }
}

/** Isi tab Moment: feed publik. */
@Composable
fun MomentTab(myUid: String, onPost: () -> Unit, onComments: (Moment) -> Unit) {
    var moments by remember { mutableStateOf<List<Moment>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val r = MomentRepo.observe { moments = it; loaded = true }
        onDispose { r.remove() }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Moment"), color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPost) { Icon(Icons.Default.Add, "Posting moment", tint = GoldLight) }
            }
        }
        if (moments.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (loaded) "Belum ada moment.\nTekan + untuk posting" else "Memuat...",
                        color = Color(0xFF8A8A8A), textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(moments, key = { it.id }) { m -> MomentCard(m, myUid, onComments) }
    }
}

@Composable
fun PostMomentScreen(uid: String, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var photoB64 by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val preview = remember(photoB64) { ProfileRepo.decodeB64(photoB64) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val b64 = ProfileRepo.encodeImage(context, uri, 900, square = false, quality = 70)
            busy = false
            if (b64 != null) photoB64 = b64 else Toast.makeText(context, "Gagal membaca gambar", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(onBack = onBack)

    OverlayBox {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            GoldTopBar(tr("Buat moment"), onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 300) text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    placeholder = { Text(tr("Apa yang sedang kamu pikirkan?")) },
                    supportingText = { Text("${text.length}/300", color = Color(0xFF8A8A8A)) },
                    colors = goldFieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 300.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1B1B1B))
                        .border(1.dp, Gold.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview != null) {
                        Image(preview, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    } else {
                        Text(tr("+ Tambah foto"), color = GoldLight, fontSize = 16.sp, modifier = Modifier.padding(40.dp))
                    }
                }
                if (preview != null) {
                    TextButton(onClick = { photoB64 = null }) { Text(tr("Hapus foto"), color = Color(0xFFFF8A80)) }
                }
                Spacer(Modifier.height(16.dp))
                GoldButton(
                    if (busy) "Memproses..." else "Posting",
                    Modifier.fillMaxWidth(),
                    enabled = !busy && (text.isNotBlank() || photoB64 != null),
                ) {
                    busy = true
                    scope.launch {
                        val err = MomentRepo.post(uid, name, text.trim(), photoB64)
                        busy = false
                        if (err == null) {
                            Toast.makeText(context, "Moment diposting", Toast.LENGTH_SHORT).show()
                            onBack()
                        } else Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun MomentCommentsPanel(m: Moment, myUid: String, myName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current
    var list by remember { mutableStateOf<List<MomentComment>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    DisposableEffect(m.id) {
        val r = MomentRepo.observeComments(m.id) { list = it }
        onDispose { r.remove() }
    }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF0B0B0B))
                .blockTouches()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        ) {
            Text("Komentar (${list.size})", color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) item { Text(tr("Belum ada komentar"), color = Color(0xFF8A8A8A)) }
                items(list, key = { it.id }) { c ->
                    Row {
                        Text(
                            c.name,
                            color = Gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { actions.openProfile(c.uid) },
                        )
                        Text("  ${c.text}", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 200) text = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(tr("Tulis komentar...")) },
                    shape = RoundedCornerShape(24.dp),
                    colors = goldFieldColors(),
                )
                IconButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        text = ""
                        scope.launch {
                            MomentRepo.addComment(m.id, myUid, myName, t)?.let {
                                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Kirim", tint = Gold) }
            }
        }
    }
}
