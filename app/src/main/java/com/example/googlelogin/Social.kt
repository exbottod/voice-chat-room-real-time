package com.example.googlelogin

// PERSIST_PUB
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

fun fmtCompact(n: Long): String {
    val abs = kotlin.math.abs(n)
    return when {
        abs < 1_000_000L -> fmtNum(n)
        abs < 1_000_000_000L -> tail(n / 1_000_000.0) + "M"
        abs < 1_000_000_000_000L -> tail(n / 1_000_000_000.0) + "B"
        else -> tail(n / 1_000_000_000_000.0) + "T"
    }
}

private fun tail(v: Double): String {
    val s = if (v >= 100.0) String.format("%.1f", v) else String.format("%.2f", v)
    return s.trimEnd('0').trimEnd('.')
}

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

    private fun sp() = com.google.firebase.FirebaseApp.getInstance().applicationContext
        .getSharedPreferences("user_pub_cache", android.content.Context.MODE_PRIVATE)

    private fun save(u: PublicUser) {
        try {
            sp().edit().putString(
                u.uid,
                org.json.JSONObject().put("userId", u.userId).put("name", u.name)
                    .put("countryCode", u.countryCode).put("photoUrl", u.photoUrl ?: "")
                    .put("gender", u.gender).put("exp", u.exp).put("vip", u.vip)
                    .put("vipUntil", u.vipUntil).put("frame", u.frame)
                    .put("bubble", u.bubble).put("badge", u.badge).put("fam", u.fam)
                    .put("svip", u.svip).put("ad", u.ad).toString(),
            ).apply()
        } catch (e: Exception) {
        }
    }

    private fun disk(uid: String): PublicUser? = try {
        val o = org.json.JSONObject(sp().getString(uid, null) ?: "")
        PublicUser(
            userId = o.optString("userId"),
            uid = uid,
            name = o.optString("name"),
            countryCode = o.optString("countryCode", "ID"),
            bio = "",
            avatarB64 = null,
            photoUrl = o.optString("photoUrl").ifBlank { null },
            gender = o.optString("gender"),
            exp = o.optLong("exp"),
            vip = o.optInt("vip"),
            vipUntil = o.optLong("vipUntil"),
            frame = o.optString("frame"),
            bubble = o.optString("bubble"),
            badge = o.optString("badge"),
            fam = o.optString("fam"),
            svip = o.optString("svip"),
            ad = o.optString("ad"),
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun rtdb(uid: String, base: PublicUser?): PublicUser? = try {
        val s = kotlinx.coroutines.withTimeoutOrNull(6000L) { Rtdb.ref("pub/$uid").get().await() }
        if (s == null || !s.exists()) {
            null
        } else {
            val b = base ?: PublicUser("", uid, "", "ID", "", null, null)
            b.copy(
                exp = (s.child("exp").value as? Number)?.toLong() ?: b.exp,
                vip = (s.child("vip").value as? Number)?.toInt() ?: b.vip,
                vipUntil = (s.child("vipUntil").value as? Number)?.toLong() ?: b.vipUntil,
                frame = s.child("frame").getValue(String::class.java) ?: b.frame,
                bubble = s.child("bubble").getValue(String::class.java) ?: b.bubble,
                badge = s.child("badge").getValue(String::class.java) ?: b.badge,
                svip = s.child("svip").getValue(String::class.java) ?: b.svip,
                ad = s.child("ad").getValue(String::class.java) ?: b.ad,
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun peek(uid: String): PublicUser? = map[uid]?.second ?: disk(uid)

    suspend fun byUid(uid: String, force: Boolean = false): PublicUser? {
        val c = map[uid]
        if (!force && c != null && System.currentTimeMillis() - c.first < 300_000) return c.second
        val fromDb = try {
            kotlinx.coroutines.withTimeoutOrNull(8000L) { UserDirectory.findByUid(uid) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fromDb != null) {
            map[uid] = System.currentTimeMillis() to fromDb
            save(fromDb)
            return fromDb
        }
        val base = c?.second ?: disk(uid)
        val r = rtdb(uid, base) ?: base
        if (r != null) map[uid] = (System.currentTimeMillis() - 240_000L) to r
        return r
    }
}

object FollowRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun docId(a: String, b: String) = "${a}_$b"

    suspend fun isFollowing(me: String, target: String): Boolean =
        db().collection("follows").document(docId(me, target)).get().await().exists()

    suspend fun follow(me: String, target: String) {
        // Can't be followed (SVIP3): user yang mengaktifkan tidak bisa diikuti.
        val pub = try {
            UserDirectory.findByUid(target)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        if (pub != null && vipActive(pub.vip, pub.vipUntil) && pub.vip >= 3 && pub.svipOn("cantFollow")) {
            throw IllegalStateException("User ini mengaktifkan Can't be followed")
        }
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

/** Avatar kecil/sedang dengan bingkai VIP (kalau ada) di atasnya. */
@Composable
fun PublicAvatarFramed(u: PublicUser?, size: Dp, fallback: String = "?", modifier: Modifier = Modifier) {
    val fa = frameAssetOf(u?.frame, u?.vip ?: 0, u?.vipUntil ?: 0L)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        PublicAvatar(u, size, fallback)
        if (fa != null) FrameOverlay(fa, (size.value * 1.36f).dp)
    }
}

/** Level pengguna dihitung dari exp (1-250). */
fun userLevel(u: PublicUser?): Int = levelFromExp(u?.exp ?: 0L)

@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(levelBrush(level))
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
    val fa = frameAssetOf(u?.frame, u?.vip ?: 0, u?.vipUntil ?: 0L)
    Box(
        Modifier.size(size + 16.dp)
            .then(if (fa != null) Modifier else Modifier.border(3.dp, frameBrush(u?.frame), CircleShape))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        PublicAvatar(u, size)
        if (fa != null) FrameOverlay(fa, size + 30.dp)
    }
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
 * [topAction] = tombol di pojok kanan atas sheet (mis. menu titik tiga: Kick dari room).
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
    topAction: (@Composable () -> Unit)? = null,
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
            try {
                following = FollowRepo.isFollowing(myUid, uid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xFF0B0B0B))
                .blockTouches(),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding(),
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
                            VipNameText(
                                u.name,
                                u.vip,
                                u.vipUntil,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(flagEmoji(u.countryCode), fontSize = 22.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        FamTag3D(u.fam)

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
                        UserMedals(u.medals)
                        Spacer(Modifier.height(14.dp))
                        VipBanner(u)
                        Spacer(Modifier.height(8.dp))
                        UserBadges(u)
                        Spacer(Modifier.height(10.dp))
                        Text(tr("Ketuk untuk lihat profil lengkap ›"), color = Gold, fontSize = 12.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (extra != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
            if (topAction != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp)) { topAction() }
            }
        }
    }
}
