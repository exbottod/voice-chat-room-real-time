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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val isMe = uid == myUid

    var user by remember(uid) { mutableStateOf(UserCache.peek(uid)) }
    var loading by remember(uid) { mutableStateOf(true) }
    var counts by remember(uid) { mutableStateOf(0L to 0L) }
    var following by remember(uid) { mutableStateOf(false) }
    var busy by remember(uid) { mutableStateOf(false) }
    var moments by remember(uid) { mutableStateOf<List<Moment>?>(null) }
    var tab by remember(uid) { mutableStateOf(0) }
    var online by remember(uid) { mutableStateOf(false) }

    // Status online live dari RTDB (fitur SVIP5 Hide Online Status).
    DisposableEffect(uid) {
        val reg = Rtdb.listen(Rtdb.ref("status/$uid")) { s -> online = s.value == true }
        onDispose { reg.remove() }
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(uid) {
        user = UserCache.byUid(uid, force = true) ?: user
        loading = false
        try { counts = FollowRepo.counts(uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        if (!isMe) {
            try { following = FollowRepo.isFollowing(myUid, uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        }
        moments = try { MomentRepo.byUid(uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        // Hide visit records (SVIP5): kunjungan tidak dicatat kalau aktif.
        if (!isMe && user != null) {
            try {
                if (!SvipRepo.myOn(myUid, "hideVisits", 5)) VisitRepo.record(uid, myUid)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
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

    Box(
        Modifier.fillMaxSize().blockTouches()
            .background(Brush.verticalGradient(listOf(PageTop, PageBottom))),
    ) {
        val u = user
        if (u == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(color = Gold)
                else Text(tr("Profil tidak ditemukan"), color = Color(0xFFBDBDBD))
            }
        } else {
            // FIX Hide my profile (SVIP6): orang lain melihat profil misterius.
            val hidden = !isMe && u.svipOn("hideProfile") && vipActive(u.vip, u.vipUntil) && u.vip >= 6
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
                    Row(
                        Modifier.clickable {
                            clipboard.setText(AnnotatedString(u.userId))
                            Toast.makeText(context, "ID disalin", Toast.LENGTH_SHORT).show()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("UID:${u.userId}", color = Muted, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("📋", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (!hidden) {
                        UserMedals(u.medals)
                        Spacer(Modifier.height(10.dp))
                        UserBadges(u)
                        if (u.fam.isNotBlank()) { Spacer(Modifier.height(6.dp)); FamTag3D(u.fam) }
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
                    ProfStat(if (hidden) "?" else fmtNum(counts.first), "Mengikuti", Modifier.weight(1f))
                    ProfStat(if (hidden) "?" else fmtNum(counts.second), "Fans", Modifier.weight(1f))
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
                    if (u.fam.isNotBlank()) FamCard(u.fam)
                    else PlaceholderCard("Belum bergabung dengan family")                    
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
private fun ProfStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
