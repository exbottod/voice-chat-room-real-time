package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val PageTop = Color(0xFF1E3D38)
private val PageBottom = Color(0xFF0A1514)
private val Muted = Color(0xFF9FB3AE)

/**
 * Halaman profil lengkap pengguna lain (layar penuh).
 * Dibuka dengan mengetuk sheet profil (di mic maupun hasil pencarian).
 * Medali, VIP, family, penjaga, sawer & pesona masih placeholder, diisi nanti.
 */
@Composable
fun OtherProfileScreen(
    uid: String,
    myUid: String,
    onBack: () -> Unit,
    onChat: (PublicUser) -> Unit,
    onFansClick: () -> Unit = {},
    onFollowingClick: () -> Unit = {},
    onEnterLiveRoom: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val isMe = uid == myUid

    // FIX Anonymous: profil orang lain jangan dirender dari cache lama (svip bisa basi),
    // tunggu data segar supaya status Hide my profile pasti terbaca.
    var user by remember(uid) { mutableStateOf(if (isMe) UserCache.peek(uid) ?: UserCache.meLocal(uid) else null) }
    var loading by remember(uid) { mutableStateOf(true) }
    var counts by remember(uid) { mutableStateOf(0L to 0L) }
    var following by remember(uid) { mutableStateOf(false) }
    var busy by remember(uid) { mutableStateOf(false) }
    var moments by remember(uid) { mutableStateOf<List<Moment>?>(null) }
    var tab by remember(uid) { mutableStateOf(0) }
    var online by remember(uid) { mutableStateOf(false) }
    var famFid by remember(uid) { mutableStateOf<String?>(null) }
    var famOpen by remember { mutableStateOf(false) }
    var myName by remember { mutableStateOf("") }

    // Status online live dari RTDB (fitur SVIP5 Hide Online Status).
    DisposableEffect(uid) {
        val reg = Rtdb.listen(Rtdb.ref("status/$uid")) { s -> online = s.value == true }
        onDispose { reg.remove() }
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(uid) {
        // 1) Tampilkan profil secepat mungkin: cache lokal langsung, lalu data segar
        //    (UserCache sudah punya timeout + jalur cadangan RTDB saat kuota Firestore habis).
        if (user == null) user = UserCache.peek(uid)
        if (user != null) loading = false
        // Profil sendiri: pakai cache (tanpa force) supaya seketika; orang lain: data segar.
        val fresh = kotlinx.coroutines.withTimeoutOrNull(if (isMe) 2000L else 5000L) {
            try { UserCache.byUid(uid, force = !isMe) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        }
        user = fresh ?: user
        // svip cadangan dari RTDB pub/{uid} (kuota terpisah dari Firestore).
        try {
            val u3 = user
            if (u3 != null && u3.svip.isBlank()) {
                val s = kotlinx.coroutines.withTimeoutOrNull(3000L) { Rtdb.ref("pub/$uid").get().await() }
                val csv = s?.child("svip")?.getValue(String::class.java) ?: ""
                if (csv.isNotBlank()) user = u3.copy(svip = csv)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        loading = false

        // 2) Data pelengkap diambil PARALEL, masing-masing dibatasi waktu,
        //    supaya satu yang lambat/gagal (kuota habis) tidak menahan yang lain.
        kotlinx.coroutines.coroutineScope {
            launch {
                if (QuotaGuard.isDown()) return@launch
                try {
                    kotlinx.coroutines.withTimeoutOrNull(4000L) { FollowRepo.counts(uid) }?.let { counts = it }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (QuotaGuard.isExhausted(e)) QuotaGuard.markDown()
                }
            }
            launch {
                if (isMe || QuotaGuard.isDown()) return@launch
                try {
                    kotlinx.coroutines.withTimeoutOrNull(4000L) { FollowRepo.isFollowing(myUid, uid) }?.let { following = it }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (QuotaGuard.isExhausted(e)) QuotaGuard.markDown()
                }
            }
            launch {
                moments = if (QuotaGuard.isDown()) emptyList() else try {
                    kotlinx.coroutines.withTimeoutOrNull(5000L) { MomentRepo.byUid(uid) } ?: emptyList()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (QuotaGuard.isExhausted(e)) QuotaGuard.markDown()
                    emptyList()
                }
            }
            launch {
                // Family: label dari profil publik (fam) sudah ada; baca data asli hanya kalau Firestore sehat.
                if (QuotaGuard.isDown()) return@launch
                try {
                    val u2 = user
                    if (u2 == null || u2.fam.isBlank() || famFid == null) {
                        val fid = kotlinx.coroutines.withTimeoutOrNull(4000L) { FamilyRepo.myFamilyId(uid) }
                        if (!fid.isNullOrBlank()) {
                            famFid = fid
                            val f = kotlinx.coroutines.withTimeoutOrNull(4000L) { FamilyRepo.getFamily(fid) }
                            if (f != null && f.name.isNotBlank()) user = (u2 ?: user)?.copy(fam = f.name)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    if (QuotaGuard.isExhausted(e)) QuotaGuard.markDown()
                }
            }
            launch {
                myName = try {
                    kotlinx.coroutines.withTimeoutOrNull(4000L) { UserCache.byUid(myUid)?.name } ?: ""
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }
            }
            launch {
                // Hide visit records (SVIP5): kunjungan tidak dicatat kalau aktif.
                if (!isMe && user != null && !QuotaGuard.isDown()) {
                    try {
                        if (!SvipRepo.myOn(myUid, "hideVisits", 5)) VisitRepo.record(uid, myUid)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
                }
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
                    counts = counts.first to (counts.second - 1).coerceAtLeast(0)
                } else {
                    FollowRepo.follow(myUid, uid)
                    following = true
                    counts = counts.first to counts.second + 1
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    // Room tempat user ini sedang berada (untuk badge LIVE).
    var liveRoomId by remember(uid) { mutableStateOf<String?>(null) }
    LaunchedEffect(uid) {
        liveRoomId = try {
            RoomRepo.liveRoomOf(uid)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }

    // Status misterius dihitung sejak awal supaya latar animasi raja bisa tampil full screen.
    val uBg = user
    val hidden = !isMe && uBg != null && uBg.svipOn("hideProfile") && vipActive(uBg.vip, uBg.vipUntil) && uBg.vip >= 6

    Box(
        Modifier.fillMaxSize().blockTouches()
            .background(Brush.verticalGradient(listOf(PageTop, PageBottom))),
    ) {
        // Profil misterius: latar animasi raja full screen (zoom pelan + kilau emas).
        if (hidden) KingBg()
        val u = user
        if (u == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(color = Gold)
                else Text(tr("Profil tidak ditemukan"), color = Color(0xFFBDBDBD))
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(bottom = if (isMe) 16.dp else 88.dp),
            ) {
                Spacer(Modifier.statusBarsPadding().height(64.dp))

                // Avatar + identitas
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (hidden) MysteryAvatar(84.dp) else FramedAvatar(u, 84.dp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (hidden || !online) Color(0xFF555555) else Color(0xFF2ED573)))
                        Spacer(Modifier.width(8.dp))
                        if (hidden) {
                            MysteryName(24.sp)
                        } else {
                            VipNameText(
                                u.name, u.vip, u.vipUntil, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            GenderBadge(u.gender)
                            Spacer(Modifier.width(6.dp))
                            LevelBadge(userLevel(u))
                            Spacer(Modifier.width(6.dp))
                            Text(flagEmoji(u.countryCode), fontSize = 22.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // ID yang tampil SELALU ID resmi akun (bukan kode room / hash email):
                    // profil sendiri dari cache ID resmi, profil lain dari ID resmi di userIds.
                    var shownId by remember(uid, u.userId) {
                        mutableStateOf(if (isMe) (UserDirectory.cachedId(context, uid) ?: u.userId) else u.userId)
                    }
                    LaunchedEffect(uid) {
                        if (!isMe) {
                            try {
                                val off = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                                    UserDirectory.officialIdOf(uid)
                                }
                                if (!off.isNullOrBlank()) shownId = off
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) { }
                        }
                    }
                    Row(
                        Modifier.clickable {
                            clipboard.setText(AnnotatedString(shownId))
                            Toast.makeText(context, "ID disalin", Toast.LENGTH_SHORT).show()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("UID:${shownId}", color = Muted, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("📋", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (!hidden) {
                        UserMedals(u.medals)
                        Spacer(Modifier.height(10.dp))
                        UserBadges(u)
                        if (u.fam.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.clickable(enabled = famFid != null) { famOpen = true }) { FamTag3D(u.fam) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Chip level & negara
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoChip(if (hidden) "Lv ?" else "Lv ${userLevel(u)}", Color(0xFF6A3DE8))
                        InfoChip("${flagEmoji(u.countryCode)} ${ProfileRepo.countryName(u.countryCode)}", Color(0xFF2B6CB0))
                        InfoChip(if (vipActive(u.vip, u.vipUntil)) "VIP${u.vip}" else "VIP –", Color(0xFF8E6B1F))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Statistik
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF)).padding(vertical = 14.dp),
                ) {
                    ProfStat(
                        if (hidden) "?" else fmtNum(counts.first), "Mengikuti", Modifier.weight(1f),
                        if (hidden || isMe) null else onFollowingClick,
                    )
                    ProfStat(
                        if (hidden) "?" else fmtNum(counts.second), "Fans", Modifier.weight(1f),
                        if (hidden || isMe) null else onFansClick,
                    )
                    ProfStat(if (hidden) "?" else "0", "Sawer", Modifier.weight(1f))
                    ProfStat(if (hidden) "?" else "0", "Pesona", Modifier.weight(1f))
                }

                if (!hidden && u.bio.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(u.bio, color = Color(0xFFE0E0E0), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
                }

                Spacer(Modifier.height(22.dp))

                // Tab
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ProfTab("Tentang saya", tab == 0) { tab = 0 }
                    ProfTab("Momen", tab == 1) { tab = 1 }
                }
                Spacer(Modifier.height(16.dp))

                if (tab == 0)  {
                    SectionTitle("Family saya")
                    if (u.fam.isNotBlank()) {
                        Box(Modifier.clickable(enabled = famFid != null) { famOpen = true }) { FamCard(u.fam) }
                    } else PlaceholderCard("Belum bergabung dengan family")                    
                    Spacer(Modifier.height(18.dp))
                    SectionTitle("Penjaga saya")
                    PlaceholderCard("Belum memiliki penjaga")
                    if (isMe) {
                        Spacer(Modifier.height(18.dp))
                        SectionTitle("Pengunjung profil")
                        var visitors by remember { mutableStateOf<List<PublicUser>?>(null) }
                        LaunchedEffect(uid) { visitors = VisitRepo.recent(uid) }
                        val vs = visitors
                        when {
                            vs == null -> PlaceholderCard("Memuat pengunjung...")
                            vs.isEmpty() -> PlaceholderCard("Belum ada pengunjung")
                            else -> Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                vs.forEach { v ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(56.dp),
                                    ) {
                                        PublicAvatarFramed(v, 44.dp, v.name)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            v.name.take(8), color = Muted, fontSize = 11.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                val list = moments
                    when {
                        list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Gold)
                        }
                        list.isEmpty() -> PlaceholderCard("Belum ada momen")
                        else -> list.forEach { m ->
                            Column(
                                Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp)).background(Color(0x33FFFFFF)).padding(14.dp),
                            ) {
                                if (m.text.isNotBlank()) Text(m.text, color = Color.White, fontSize = 15.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${timeAgo(m.createdAt)}  ·  ❤ ${m.likes}  ·  💬 ${m.comments}",
                                    color = Muted, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            // Tombol bawah (hanya untuk pengguna lain)
            if (!isMe) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0xE60A1514)).navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (following) {
                        OutlineButton(tr("Mengikuti ✓"), Modifier.weight(1f)) { if (!busy) toggleFollow() }
                    } else {
                        GoldButton(tr("Ikuti"), Modifier.weight(1f), enabled = !busy) { toggleFollow() }
                    }
                    OutlineButton("Chat", Modifier.weight(1f)) { onChat(u) }
                }
            }
        }

        // FIX family: dialog profil family saat label/kartu family diklik.
        if (famOpen && famFid != null) {
            FamilyProfileDialog(
                fid = famFid!!,
                myUid = myUid,
                myName = myName,
                myAvatar = null,
                onDismiss = { famOpen = false },
            )
        }

        // Bar atas: kembali + nama
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White)
            }
            Text(
                user?.name ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        // Badge LIVE di kanan atas: tampil saat user sedang ada di room.
        // Klik untuk langsung masuk ke room tempat dia online.
        liveRoomId?.let { rid ->
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFE53935))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .clickable { onEnterLiveRoom(rid) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(5.dp))
                Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun ProfStat(value: String, label: String, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val m = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ProfTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.Start) {
        Text(
            text,
            color = if (selected) GoldLight else Muted,
            fontSize = if (selected) 24.sp else 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.width(26.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) Gold else Color.Transparent),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = GoldLight, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("›", color = Muted, fontSize = 22.sp)
    }
}

@Composable
private fun PlaceholderCard(text: String) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(90.dp)
            .clip(shape).background(Color(0x22FFFFFF)).border(1.dp, Gold.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Muted, fontSize = 14.sp) }
}

@Composable
private fun FamCard(fam: String) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(90.dp)
            .clip(shape).background(Color(0x22FFFFFF)).border(1.dp, Gold.copy(alpha = 0.25f), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Gold, GoldDark))),
            contentAlignment = Alignment.Center,
        ) { Text("🏰", fontSize = 26.sp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(fam, color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Bergabung dengan family ini", color = Color(0xFFE0E0E0), fontSize = 13.sp)
        }
    }
}

/**
 * Latar animasi raja untuk profil misterius: singa mengaung hidup (dua frame
 * dipertukaran halus: tenang -> mengaung -> tenang), zoom pelan + kilau emas.
 */
@Composable
private fun KingBg() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // GIF singa mengaung full screen 9:16 (animasi asli, halus).
        KingGifLayer(Modifier.fillMaxSize())
        // lapisan gelap tipis supaya teks tetap terbaca
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x80121212), Color(0x40121212), Color(0x80121212))),
            ),
        )
    }
}

/** Pemutar GIF animasi full screen (ImageDecoder, Android 9+). */
@Composable
private fun KingGifLayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember {
        try {
            (ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(context.resources, R.drawable.king_anim),
            ) as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                start()
            }
        } catch (e: Exception) { null }
    }
    if (drawable == null) return
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val dw = drawable.intrinsicWidth.coerceAtLeast(1)
        val dh = drawable.intrinsicHeight.coerceAtLeast(1)
        val s = maxOf(w / dw, h / dh)
        drawIntoCanvas { c ->
            c.save()
            c.scale(s, s)
            c.translate((w / s - dw) / 2f, (h / s - dh) / 2f)
            drawable.setBounds(0, 0, dw, dh)
            drawable.draw(c.nativeCanvas)
            c.restore()
        }
    }
}
