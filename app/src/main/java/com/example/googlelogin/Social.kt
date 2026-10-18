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
import androidx.compose.ui.text.style.TextOverflow
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
    val openFullProfile: (String) -> Unit = {},
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

/** Level pengguna. Sementara 1 untuk semua; ganti nanti saat sistem level sudah ada. */
fun userLevel(u: PublicUser?): Int = 1

@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(Color(0xFF6A3DE8), Color(0xFFB04CFF))))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Lv $level", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

/** Deretan medali. Slot kosong sekarang; isi daftar [medals] nanti (emoji / label). */
@Composable
fun MedalRow(medals: List<String> = emptyList(), slots: Int = 8) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        repeat(slots) { i ->
            val m = medals.getOrNull(i)
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(if (m != null) Gold.copy(alpha = 0.2f) else Color(0x22FFFFFF))
                    .border(1.dp, Gold.copy(alpha = if (m != null) 0.9f else 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (m != null) Text(m, fontSize = 18.sp) }
        }
    }
}

@Composable
fun GenderBadge(gender: String) {
    when (gender) {
        "M" -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF1E88E5)), Alignment.Center) {
            Text("♂", color = Color.White, fontSize = 13.sp)
        }
        "F" -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFEC407A)), Alignment.Center) {
            Text("♀", color = Color.White, fontSize = 13.sp)
        }
    }
}

/** Avatar besar dengan bingkai emas (tempat bingkai khusus nanti). */
@Composable
fun FramedAvatar(u: PublicUser?, size: Dp) {
    Box(
        Modifier.size(size + 16.dp)
            .border(3.dp, Brush.sweepGradient(listOf(GoldLight, Gold, GoldDark, Gold, GoldLight)), CircleShape)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) { PublicAvatar(u, size) }
}

@Composable
fun ProfileSheetAction(label: String, icon: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFFD0D0D0), fontSize = 12.sp)
    }
}

/**
 * Sheet profil pengguna lain. Muncul saat profil di mic / hasil pencarian diketuk.
 * Mengetuk bagian atas (avatar, nama, medali, VIP) membuka halaman profil lengkap.
 * [extra] = tombol tambahan (mis. Turun Mic, Hadiah) saat dibuka dari dalam room.
 */
@Composable
fun UserProfileSheet(
    uid: String,
    myUid: String,
    onDismiss: () -> Unit,
    onChat: (PublicUser) -> Unit,
    onFollowChanged: () -> Unit,
    onOpenFull: () -> Unit = {},
    extra: (@Composable RowScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isMe = uid == myUid
    var user by remember(uid) { mutableStateOf(UserCache.peek(uid)) }
    var loading by remember(uid) { mutableStateOf(true) }
    var following by remember(uid) { mutableStateOf(false) }
    var busy by remember(uid) { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(uid) {
        user = UserCache.byUid(uid, force = true) ?: user
        loading = false
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
                } else {
                    FollowRepo.follow(myUid, uid)
                    following = true
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
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF0B0B0B))
                .blockTouches()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val u = user
            if (u == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(color = Gold)
                    else Text(tr("Profil tidak ditemukan"), color = Color(0xFFBDBDBD))
                }
            } else {
                // Bagian atas: ketuk -> halaman profil lengkap
                Column(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenFull).padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FramedAvatar(u, 96.dp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            u.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(flagEmoji(u.countryCode), fontSize = 22.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("UID:${u.userId}", color = Color(0xFFBDBDBD), fontSize = 15.sp)
                        Spacer(Modifier.width(8.dp))
                        LevelBadge(userLevel(u))
                        Spacer(Modifier.width(6.dp))
                        GenderBadge(u.gender)
                    }
                    if (u.bio.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(u.bio, color = Color(0xFFE0E0E0), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                    Spacer(Modifier.height(12.dp))
                    MedalRow()
                    Spacer(Modifier.height(14.dp))
                    // Banner VIP (placeholder, diisi nanti)
                    Box(
                        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF3B2A0A), Color(0xFF1A1206))))
                            .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("VIP", color = GoldLight, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Text(tr("Segera hadir"), color = Color(0xFF9A9A9A), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Ketuk untuk lihat profil lengkap ›"), color = Gold, fontSize = 12.sp)
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (extra != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            extra()
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (!isMe) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (following) {
                                OutlineButton(tr("Mengikuti ✓"), Modifier.weight(1f)) { if (!busy) toggleFollow() }
                            } else {
                                GoldButton(tr("Ikuti"), Modifier.weight(1f), enabled = !busy) { toggleFollow() }
                            }
                            OutlineButton("Chat", Modifier.weight(1f)) { onChat(u) }
                        }
                    } else if (extra == null) {
                        Text(tr("Ini profil kamu"), color = Color(0xFF9A9A9A))
                    }
                }
            }
        }
    }
}
