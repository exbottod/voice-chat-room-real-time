package com.example.googlelogin

// PERSIST_PUB
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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
                    .put("bubble", u.bubble).put("badge", u.badge).put("fam", u.fam).put("medals", u.medals)
                    .put("svip", u.svip).put("ad", u.ad)
                    // FIX avatar hilang saat kuota habis: foto ikut disimpan di cache disk.
                    .put("avatarB64", u.avatarB64 ?: "").toString(),
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
            photoUrl = o.optString("photoUrl").ifBlank { null },
            gender = o.optString("gender"),
            avatarB64 = o.optString("avatarB64").ifBlank { null },
            exp = o.optLong("exp"),
            vip = o.optInt("vip"),
            vipUntil = o.optLong("vipUntil"),
            frame = o.optString("frame"),
            bubble = o.optString("bubble"),
            badge = o.optString("badge"),
            fam = o.optString("fam"),
            medals = o.optString("medals"),
            svip = o.optString("svip"),
            ad = o.optString("ad"),
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun rtdb(uid: String, base: PublicUser?): PublicUser? = try {
        val s = kotlinx.coroutines.withTimeoutOrNull(4000L) { Rtdb.ref("pub/$uid").get().await() }
        if (s == null || !s.exists()) {
            null
        } else {
            val b = base ?: PublicUser("", uid, "", "ID", "", null, null)
            b.copy(
                // PROFIL UTAMA SEKARANG DI REALTIME DATABASE: nama, ID, bio, negara,
                // gender ikut dibaca dari RTDB pub supaya profil tampil instan.
                userId = s.child("id").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: b.userId,
                name = s.child("name").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: b.name,
                countryCode = s.child("cc").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: b.countryCode,
                bio = s.child("bio").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: b.bio,
                gender = s.child("gender").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: b.gender,
                exp = (s.child("exp").value as? Number)?.toLong() ?: b.exp,
                vip = (s.child("vip").value as? Number)?.toInt() ?: b.vip,
                vipUntil = (s.child("vipUntil").value as? Number)?.toLong() ?: b.vipUntil,
                // FIX on/off: kalau kunci ADA di pub (walau nilainya kosong), itu
                // nilai sebenarnya. Jangan pakai nilai lama, biar mematikan
                // lencana/svip/medal langsung terlihat di profil orang lain.
                frame = if (s.hasChild("frame")) s.child("frame").getValue(String::class.java) ?: b.frame else b.frame,
                bubble = if (s.hasChild("bubble")) s.child("bubble").getValue(String::class.java) ?: b.bubble else b.bubble,
                badge = if (s.hasChild("badge")) s.child("badge").getValue(String::class.java) ?: b.badge else b.badge,
                medals = if (s.hasChild("medals")) s.child("medals").getValue(String::class.java) ?: b.medals else b.medals,
                fam = if (s.hasChild("fam")) s.child("fam").getValue(String::class.java) ?: b.fam else b.fam,
                svip = if (s.hasChild("svip")) s.child("svip").getValue(String::class.java) ?: b.svip else b.svip,
                ad = s.child("ad").getValue(String::class.java) ?: b.ad,
                // FIX avatar: foto profil ikut dibaca dari RTDB pub (field "av")
                // supaya avatar tetap tampil saat kuota Firestore habis.
                avatarB64 = s.child("av").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: b.avatarB64,
                photoUrl = s.child("photo").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: b.photoUrl,
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun peek(uid: String): PublicUser? = map[uid]?.second ?: disk(uid)

    /** Profil SENDIRI instan dari data lokal (profil + wallet cache + SVIP lokal),
     *  TANPA jaringan sama sekali. Dipakai saat membuka profil sendiri di room /
     *  OtherProfile supaya tidak loading lama saat kuota Firestore habis. */
    fun meLocal(uid: String): PublicUser? {
        return try {
            val cu = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return null
            val email = cu.email ?: return null
            val photoUrl = cu.photoUrl?.toString()
            val ctx = com.google.firebase.FirebaseApp.getInstance().applicationContext
            val d = ProfileRepo.loadLocal(ctx, email)
            val w = WalletCache.load(uid)
            PublicUser(
                // ID RESMI akun (berurutan dari 100000), BUKAN hash email 8 digit
                // yang mirip kode room. Kosong dulu kalau cache ID belum siap.
                userId = UserDirectory.cachedId(ctx, uid) ?: "",
                uid = uid,
                name = d.nickname,
                countryCode = d.countryCode,
                bio = d.bio,
                avatarB64 = d.avatarB64,
                photoUrl = photoUrl,
                gender = d.gender,
                exp = w?.exp ?: 0L,
                vip = w?.vip ?: 0,
                vipUntil = w?.vipUntil ?: 0L,
                frame = w?.let { activeEquip(it, "frame") } ?: "",
                bubble = w?.let { activeEquip(it, "bubble") } ?: "",
                badge = w?.let { activeEquip(it, "badge") } ?: "",
                medals = w?.let { activeMedalIds(it).joinToString(",") } ?: "",
                fam = "",
                svip = SvipLocal.csv(uid),
                ad = SvipLocal.ad(uid),
            )
        } catch (e: Exception) { null }
    }

    /** Gabungkan: kalau data baru kosong untuk frame/lencana/vip tapi data lama ada, pertahankan yang lama.
     *  Mencegah frame & lencana "hilang lagi" saat sumber data (RTDB/Firestore) sedang lambat/kosong. */
    private fun keepGood(new: PublicUser, old: PublicUser?): PublicUser {
        if (old == null) return new
        return new.copy(
            // FIX avatar: jangan buang foto lama kalau data baru tidak membawanya.
            avatarB64 = new.avatarB64 ?: old.avatarB64,
            photoUrl = new.photoUrl ?: old.photoUrl,
            userId = new.userId.ifBlank { old.userId },
            name = new.name.ifBlank { old.name },
            countryCode = new.countryCode.ifBlank { old.countryCode },
            bio = new.bio.ifBlank { old.bio },
            gender = new.gender.ifBlank { old.gender },
            // FIX on/off: nilai kosong (mematikan) tidak ditimpa nilai lama lagi.
            frame = new.frame,
            badge = new.badge,
            bubble = new.bubble,
            medals = new.medals,
            vip = if (new.vip == 0 && vipActive(old.vip, old.vipUntil)) old.vip else new.vip,
            vipUntil = if (new.vip == 0 && vipActive(old.vip, old.vipUntil)) old.vipUntil else new.vipUntil,
            svip = new.svip,
            fam = new.fam.ifBlank { old.fam },
        )
    }

    suspend fun byUid(uid: String, force: Boolean = false): PublicUser? {
        val c = map[uid]
        if (!force && c != null && System.currentTimeMillis() - c.first < 300_000) return c.second
        val prev = c?.second ?: disk(uid)

        // PROFIL SENDIRI: instan dari data lokal, tanpa jaringan sama sekali.
        val me = try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) { null }
        if (uid == me) {
            val local = meLocal(uid)
            if (local != null) {
                val fresh = rtdb(uid, local)
                val good = if (fresh != null) keepGood(fresh, local) else local
                map[uid] = System.currentTimeMillis() to good
                save(good)
                return good
            }
        }

        // PROFIL ORANG LAIN: UTAMA dari Realtime Database (cepat, kuota lega).
        // Firestore hanya dipakai kalau pub RTDB belum lengkap (nama/ID kosong).
        val fresh = rtdb(uid, prev)
        if (fresh != null && fresh.name.isNotBlank() && fresh.userId.isNotBlank()) {
            val good = keepGood(fresh, prev)
            map[uid] = System.currentTimeMillis() to good
            save(good)
            return good
        }
        val fromDb = if (QuotaGuard.isDown()) null else try {
            kotlinx.coroutines.withTimeoutOrNull(3000L) { UserDirectory.findByUid(uid) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fromDb != null) {
            val good = keepGood(fromDb, prev ?: fresh)
            map[uid] = System.currentTimeMillis() to good
            save(good)
            return good
        }
        val base = prev
        val r = if (fresh != null) keepGood(fresh, base) else base
        if (r != null) {
            map[uid] = (System.currentTimeMillis() - 240_000L) to r
            if (fresh != null) save(r)
        }
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

    /** Daftar uid yang diikuti (terbaru dulukan). Limit default 30 untuk hemat kuota.
     *  Tanpa orderBy di server: kombinasi where + orderBy minta composite index
     *  (belum dibuat), query malah gagal dan daftar tampak kosong.
     *  Urutkan createdAt di perangkat saja, hasilnya sama. */
    suspend fun listFollowing(uid: String, limit: Long = 30): List<String> = try {
        db().collection("follows").whereEqualTo("followerUid", uid).limit(limit).get().await()
            .documents.mapNotNull { d ->
                val t = d.getString("targetUid")?.ifBlank { null } ?: return@mapNotNull null
                t to (d.getLong("createdAt") ?: 0L)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }

    /** Daftar uid pengikut / fans (terbaru dulukan). Limit default 30 untuk hemat kuota. */
    suspend fun listFans(uid: String, limit: Long = 30): List<String> = try {
        db().collection("follows").whereEqualTo("targetUid", uid).limit(limit).get().await()
            .documents.mapNotNull { d ->
                val f = d.getString("followerUid")?.ifBlank { null } ?: return@mapNotNull null
                f to (d.getLong("createdAt") ?: 0L)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }

    /** Jumlah fans seorang user (untuk baris daftar). */
    suspend fun fansCount(uid: String): Long = try {
        db().collection("follows").whereEqualTo("targetUid", uid)
            .count().get(AggregateSource.SERVER).await().count
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { 0L }

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
            when (u?.gender) {
                // Foto profil default cowo / cewek saat awal daftar akun (belum upload foto).
                "M" -> Image(
                    painterResource(R.drawable.ic_default_avatar_male), null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
                "F" -> Image(
                    painterResource(R.drawable.ic_default_avatar_female), null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
                else -> Text(
                    (u?.name ?: fallback).take(1).uppercase(),
                    color = GoldLight,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.4f).sp,
                )
            }
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

/** Drawable badge level sesuai rentang level (1-4, 5-9, dst. s.d. 200-250). */
fun levelBadgeRes(level: Int): Int = when {
    level <= 4 -> R.drawable.ic_level_1_4
    level <= 9 -> R.drawable.ic_level_5_9
    level <= 14 -> R.drawable.ic_level_10_14
    level <= 20 -> R.drawable.ic_level_15_20
    level <= 30 -> R.drawable.ic_level_21_30
    level <= 39 -> R.drawable.ic_level_31_39
    level <= 49 -> R.drawable.ic_level_40_49
    level <= 69 -> R.drawable.ic_level_50_69
    level <= 99 -> R.drawable.ic_level_70_99
    level <= 119 -> R.drawable.ic_level_100_119
    level <= 169 -> R.drawable.ic_level_120_169
    level <= 199 -> R.drawable.ic_level_170_199
    else -> R.drawable.ic_level_200_250
}

@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier, height: Dp = 18.dp) {
    // Angka level digambar DI DALAM icon badge, pas di tengah,
    // jadi tulisan level tidak pernah keluar dari batas icon level.
    Box(modifier.height(height), contentAlignment = Alignment.Center) {
        Image(
            painterResource(levelBadgeRes(level)),
            null,
            Modifier.fillMaxHeight(),
            contentScale = ContentScale.Fit,
        )
        // Hanya angka level saja (1, 50, 120), pas di tengah icon.
        Text(
            "$level",
            color = Color.White,
            fontSize = (height.value * 0.8f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.65f), blurRadius = 2f)),
        )
    }
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
    var user by remember(uid) { mutableStateOf(UserCache.peek(uid) ?: if (isMe) UserCache.meLocal(uid) else null) }
    var loading by remember(uid) { mutableStateOf(true) }
    var following by remember(uid) { mutableStateOf(false) }
    var busy by remember(uid) { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(uid) {
        if (isMe) {
            // Profil sendiri: equip dari wallet lokal/cadangan dipakai langsung, tanpa nunggu jaringan.
            val w = WalletCache.load(uid)
            val u0 = user
            if (w != null && u0 != null) {
                user = u0.copy(
                    frame = activeEquip(w, "frame").ifBlank { u0.frame },
                    badge = activeEquip(w, "badge").ifBlank { u0.badge },
                    bubble = activeEquip(w, "bubble").ifBlank { u0.bubble },
                    vip = if (w.vip > 0) w.vip else u0.vip,
                    vipUntil = if (w.vip > 0) w.vipUntil else u0.vipUntil,
                )
            }
        }
        user = UserCache.byUid(uid) ?: user
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
                        UserMedals(u.medals, 44.dp, Modifier.fillMaxWidth())
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

// ===== Layar daftar Fans / Mengikuti =====
const val FOLLOW_MODE_FANS = "fans"
const val FOLLOW_MODE_FOLLOWING = "following"

/**
 * Daftar Fans atau Mengikuti. Klik user untuk langsung masuk ke halaman profilnya.
 * Desain terang: latar putih, nama hitam tebal, detail abu-abu, tombol pill kuning.
 */
@Composable
fun FollowListScreen(
    uid: String,
    mode: String,
    myUid: String,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var list by remember(uid, mode) { mutableStateOf<List<String>?>(null) }
    var users by remember(uid, mode) { mutableStateOf<Map<String, PublicUser>>(emptyMap()) }
    var fansN by remember(uid, mode) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var iFollowMap by remember(uid, mode) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var mutualMap by remember(uid, mode) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uid, mode) {
        // Daftar uid dibatasi 10 detik supaya layar tidak berputar selamanya
        // kalau Firestore lambat; kalau gagal tampil pesan kosong, bukan loading abadi.
        list = try {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                if (mode == FOLLOW_MODE_FANS) FollowRepo.listFans(uid) else FollowRepo.listFollowing(uid)
            } ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    LaunchedEffect(list) {
        val ids = list ?: return@LaunchedEffect
        // FIX list kosong: semua user dimuat PARALEL dan tiap hasil langsung
        // ditampilkan begitu siap. Dulu berurutan + baru tampil setelah semua
        // selesai, sehingga satu panggilan lambat membuat seluruh baris kosong.
        kotlinx.coroutines.coroutineScope {
            for (id in ids) {
                launch {
                    // 1) PROFIL dulu (nama + foto): Realtime Database, batas 6 detik.
                    try {
                        val u = kotlinx.coroutines.withTimeoutOrNull(6000L) { UserCache.byUid(id) }
                            ?: UserCache.peek(id)
                        if (u != null) users = users + (id to u)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        UserCache.peek(id)?.let { users = users + (id to it) }
                    }
                    // 2) Status ikuti (tombol), batas 5 detik.
                    try {
                        if (myUid.isNotEmpty() && id != myUid) {
                            val r = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                                FollowRepo.isFollowing(myUid, id) to FollowRepo.isFollowing(id, myUid)
                            }
                            if (r != null) {
                                iFollowMap = iFollowMap + (id to r.first)
                                mutualMap = mutualMap + (id to r.second)
                            }
                        } else {
                            iFollowMap = iFollowMap + (id to true)
                            mutualMap = mutualMap + (id to true)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
                    // 3) Jumlah fans (informasi tambahan), batas 5 detik.
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(5000L) { FollowRepo.fansCount(id) }
                            ?.let { fansN = fansN + (id to it) }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
                }
            }
        }
    }
    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "\u2039", color = Color.Black, fontSize = 30.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 16.dp),
            )
            Text(
                tr(if (mode == FOLLOW_MODE_FANS) "Semua fans" else "Semua mengikuti"),
                color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(color = Color(0xFFF2F2F2))
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFDD835))
            }
            list!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr(if (mode == FOLLOW_MODE_FANS) "Belum ada fans" else "Belum mengikuti siapa pun"), color = Color(0xFF9E9E9E))
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list!!, key = { it }) { id ->
                    FollowRow(
                        u = users[id],
                        fans = fansN[id],
                        iFollow = iFollowMap[id],
                        mutual = mutualMap[id],
                        busy = busy == id,
                        onClick = { onOpenUser(id) },
                        onToggle = {
                            if (myUid.isEmpty() || id == myUid) return@FollowRow
                            val fol = iFollowMap[id] == true
                            busy = id
                            scope.launch {
                                val err = try {
                                    if (fol) {
                                        FollowRepo.unfollow(myUid, id)
                                    } else {
                                        FollowRepo.follow(myUid, id)
                                    }
                                    null
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                                    e.message ?: "Gagal"
                                }
                                busy = null
                                if (err == null) {
                                    iFollowMap = iFollowMap.toMutableMap().also { it[id] = !fol }
                                } else {
                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Satu baris user pada daftar Fans/Mengikuti. */
@Composable
private fun FollowRow(
    u: PublicUser?,
    fans: Long?,
    iFollow: Boolean?,
    mutual: Boolean?,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val hidden = u != null && u.svipOn("hideProfile") && vipActive(u.vip, u.vipUntil) && u.vip >= 6
    val name = when {
        u == null -> "..."
        hidden -> tr("Pengguna Misterius")
        u.name.isNotBlank() -> u.name
        u.userId.isNotBlank() -> u.userId
        else -> tr("Pengguna")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val b64 = if (hidden) null else u?.avatarB64
        val bmp = remember(b64) { ProfileRepo.decodeB64(b64) }
        val photo = if (hidden) null else u?.photoUrl
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(0xFFEEEEEE))
                .border(1.dp, Color(0xFFE0E0E0), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bmp != null -> Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                !photo.isNullOrBlank() -> AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                u != null -> Text(
                    name.take(1).uppercase(),
                    color = Color(0xFF757575), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                (if (u != null && u.userId.isNotBlank() && !hidden) "ID ${u.userId} \u00b7 " else "") +
                    "${ProfileRepo.countryName(u?.countryCode ?: "")} \u00b7 Fans ${fans ?: "-"}",
                color = Color(0xFF757575), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            iFollow == true && mutual == true ->
                Text(tr("Saling mengikuti"), color = Color(0xFF9E9E9E), fontSize = 13.sp)
            iFollow == true ->
                FollowPill(tr("Mengikuti"), busy) { onToggle() }
            else ->
                FollowPill(tr("Ikuti"), busy) { onToggle() }
        }
    }
    HorizontalDivider(color = Color(0xFFF2F2F2))
}

/** Tombol pill kuning seperti pada desain. */
@Composable
private fun FollowPill(text: String, busy: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = Color.Black,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (busy) Color(0xFFF0F0F0) else Color(0xFFFDD835))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}
