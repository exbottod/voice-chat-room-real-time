package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Aksi global yang bisa dipanggil dari layar mana pun (profil, chat, dompet). */
class AppActions(
    val openProfile: (String) -> Unit,
    val openChat: (PublicUser) -> Unit,
    val openWallet: () -> Unit,
)

val LocalActions = staticCompositionLocalOf { AppActions({}, {}, {}) }

fun fmtNum(n: Long): String = NumberFormat.getInstance(Locale("in", "ID")).format(n)

fun timeAgo(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60000
    return when {
        m < 1 -> "baru saja"
        m < 60 -> "$m mnt lalu"
        m < 1440 -> "${m / 60} jam lalu"
        else -> "${m / 1440} hari lalu"
    }
}

/** Cache profil publik berdasarkan UID supaya tidak membaca Firestore berulang. */
object UserCache {
    private val map = ConcurrentHashMap<String, Pair<Long, PublicUser>>()
    fun peek(uid: String): PublicUser? = map[uid]?.second
    suspend fun byUid(uid: String, force: Boolean = false): PublicUser? {
        val c = map[uid]
        if (!force && c != null && System.currentTimeMillis() - c.first < 300_000) return c.second
        return try {
            UserDirectory.findByUid(uid)?.also { map[uid] = System.currentTimeMillis() to it }
        } catch (e: Exception) {
            c?.second
        }
    }
}

object FollowRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun docId(a: String, b: String) = "${a}_$b"

    suspend fun isFollowing(me: String, target: String): Boolean =
        db().collection("follows").document(docId(me, target)).get().await().exists()

    suspend fun follow(me: String, target: String) {
        db().collection("follows").document(docId(me, target)).set(
            hashMapOf<String, Any?>(
                "followerUid" to me,
                "targetUid" to target,
                "createdAt" to System.currentTimeMillis(),
            ),
        ).await()
    }

    suspend fun unfollow(me: String, target: String) {
        db().collection("follows").document(docId(me, target)).delete().await()
    }

    /** Pair(mengikuti, fans) */
    suspend fun counts(uid: String): Pair<Long, Long> {
        val c = db().collection("follows")
        val following = c.whereEqualTo("followerUid", uid).count().get(AggregateSource.SERVER).await().count
        val fans = c.whereEqualTo("targetUid", uid).count().get(AggregateSource.SERVER).await().count
        return following to fans
    }
}

@Composable
fun PublicAvatar(u: PublicUser?, size: Dp, fallback: String = "?", modifier: Modifier = Modifier) {
    val bmp = remember(u?.avatarB64) { ProfileRepo.decodeB64(u?.avatarB64) }
    val base = modifier.size(size).clip(CircleShape)
    when {
        bmp != null -> Image(bmp, null, base, contentScale = ContentScale.Crop)
        u != null && !u.photoUrl.isNullOrBlank() ->
            AsyncImage(model = u.photoUrl, contentDescription = null, modifier = base, contentScale = ContentScale.Crop)
        else -> Box(base.background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
            Text(
                (u?.name ?: fallback).take(1).uppercase(),
                color = GoldLight,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}

@Composable
private fun SheetStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = Color(0xFF9A9A9A), fontSize = 12.sp)
    }
}

/** Sheet profil pengguna lain: ikuti / berhenti mengikuti, dan chat pribadi. */
@Composable
fun UserProfileSheet(
    uid: String,
    myUid: String,
    onDismiss: () -> Unit,
    onChat: (PublicUser) -> Unit,
    onFollowChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isMe = uid == myUid
    var user by remember(uid) { mutableStateOf(UserCache.peek(uid)) }
    var loading by remember(uid) { mutableStateOf(true) }
    var following by remember(uid) { mutableStateOf(false) }
    var counts by remember(uid) { mutableStateOf(0L to 0L) }
    var busy by remember(uid) { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(uid) {
        user = UserCache.byUid(uid, force = true) ?: user
        loading = false
        try { counts = FollowRepo.counts(uid) } catch (e: Exception) { }
        if (!isMe) {
            try { following = FollowRepo.isFollowing(myUid, uid) } catch (e: Exception) { }
        }
    }

    fun toggleFollow() {
        busy = true
        scope.launch {
            try {
                if (following) {
                    FollowRepo.unfollow(myUid, uid)
                    following = false
                    counts = counts.first to (counts.second - 1).coerceAtLeast(0)
                } else {
                    FollowRepo.follow(myUid, uid)
                    following = true
                    counts = counts.first to counts.second + 1
                }
                onFollowChanged()
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

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
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF0B0B0B))
                .blockTouches()
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val u = user
            if (u == null) {
                if (loading) CircularProgressIndicator(color = Gold)
                else Text("Profil tidak ditemukan", color = Color(0xFFBDBDBD))
            } else {
                Box(Modifier.border(3.dp, Gold, CircleShape).padding(3.dp)) { PublicAvatar(u, 84.dp) }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(flagEmoji(u.countryCode), fontSize = 18.sp)
                }
                Text("ID: ${u.userId}", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                if (u.bio.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(u.bio, color = Color(0xFFE0E0E0), fontSize = 14.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    SheetStat(fmtNum(counts.first), "Mengikuti", Modifier.weight(1f))
                    SheetStat(fmtNum(counts.second), "Fans", Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                if (isMe) {
                    Text("Ini profil kamu", color = Color(0xFF9A9A9A))
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (following) {
                            OutlineButton("Mengikuti ✓", Modifier.weight(1f)) { if (!busy) toggleFollow() }
                        } else {
                            GoldButton("Ikuti", Modifier.weight(1f), enabled = !busy) { toggleFollow() }
                        }
                        OutlineButton("Chat", Modifier.weight(1f)) { onChat(u) }
                    }
                }
            }
        }
    }
}
