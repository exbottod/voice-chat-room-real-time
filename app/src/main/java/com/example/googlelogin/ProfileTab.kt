package com.example.googlelogin

import com.google.firebase.auth.FirebaseAuth
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val Navy = Color(0xFF1F2A44)
private val PageBg = Color(0xFFF1F4FB)
private val Teal = Color(0xFF2FC4AE)

/** Avatar bulat: prioritas foto yang diunggah, lalu foto Google, lalu inisial. */
@Composable
fun AvatarImage(user: UserProfile, profile: ProfileData, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(profile.avatarB64) { ProfileRepo.decodeB64(profile.avatarB64) }
    val base = modifier.size(size).clip(CircleShape)
    when {
        bmp != null -> Image(bmp, "Avatar", base, contentScale = ContentScale.Crop)
        !user.photoUrl.isNullOrBlank() -> AsyncImage(
            model = user.photoUrl,
            contentDescription = "Avatar",
            modifier = base,
            contentScale = ContentScale.Crop,
        )
        else -> Box(base.background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
            Text(
                (profile.nickname.ifBlank { user.name }).take(1).uppercase(),
                color = Color.White,
                fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}

@Composable
private fun StatItem(value: String, label: String, ink: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = ink, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Text(label, color = ink.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}

@Composable
private fun MenuItem(emoji: String, label: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFFFE082), Color(0xFFFFB300)))),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 20.sp) }
        Spacer(Modifier.width(14.dp))
        Text(label, color = Navy, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, color = Color(0xFF8A93A6), fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text("›", color = Color(0xFFB0B7C6), fontSize = 22.sp)
    }
}

@Composable
fun ProfileTab(
    user: UserProfile,
    profile: ProfileData,
    userId: String,
    onEditClick: () -> Unit,
    onInviteClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSignOutClick: () -> Unit,
    coins: Long = 0L,
    following: Long = 0L,
    fans: Long = 0L,
    onWalletClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    wallet: Wallet = Wallet(),
    onVipClick: () -> Unit = {},
    onLevelClick: () -> Unit = {},
    onStoreClick: () -> Unit = {},
    onGloryClick: () -> Unit = {},
    onFamilyClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val nick = profile.nickname.ifBlank { user.name }
    val bg = remember(profile.backgroundB64) { ProfileRepo.decodeB64(profile.backgroundB64) }
    val ink = if (bg != null) Color.White else Navy
    val level = levelFromExp(wallet.exp)
    val vipOn = vipActive(wallet.vip, wallet.vipUntil)

    fun soon(label: String) =
        Toast.makeText(context, "$label segera hadir", Toast.LENGTH_SHORT).show()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState()),
    ) {
        // ---------- Header ----------
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(Teal, Color(0xFF8EE0D2), PageBg))),
            )
            if (bg != null) {
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x22000000), PageBg))),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.5f))
                            .clickable(onClick = onEditClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Edit, "Edit profil", tint = Navy, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val vfAsset = frameAssetOf(activeEquip(wallet, "frame"), wallet.vip, wallet.vipUntil)
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.clip(CircleShape).clickable(onClick = onProfileClick)
                                .then(if (vfAsset != null) Modifier else Modifier.border(3.dp, frameBrush(activeEquip(wallet, "frame")), CircleShape))
                                .padding(3.dp),
                        ) {
                            AvatarImage(user, profile, 92.dp)
                        }
                        if (vfAsset != null) FrameOverlay(vfAsset, 128.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.clickable(onClick = onProfileClick)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VipNameText(
                                nick, wallet.vip, wallet.vipUntil,
                                color = ink,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(flagEmoji(profile.countryCode), fontSize = 20.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.clickable {
                                    clipboard.setText(AnnotatedString(userId))
                                    Toast.makeText(context, "ID disalin", Toast.LENGTH_SHORT).show()
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("UID:$userId", color = ink.copy(alpha = 0.75f), fontSize = 15.sp)
                                Spacer(Modifier.width(6.dp))
                                Text("📋", fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            LevelBadge(level)
                        }
                        Spacer(Modifier.height(6.dp))
                        BadgeRow(wallet.vip, wallet.vipUntil, activeEquip(wallet, "badge"))
                        FamTagByUid(FirebaseAuth.getInstance().currentUser?.uid)
                        val myMedals = activeMedalIds(wallet)
                        if (myMedals.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            UserMedals(myMedals.joinToString(","), 22.dp)
                        }
                    }
                }
                if (profile.bio.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(profile.bio, color = ink.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 3)
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatItem(fmtNum(following), "Mengikuti", ink, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(36.dp).background(ink.copy(alpha = 0.25f)))
                    StatItem(fmtNum(fans), "Fans", ink, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(36.dp).background(ink.copy(alpha = 0.25f)))
                    StatItem("0", "Pesona", ink, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        // ---------- Dompet ----------
        Column(Modifier.padding(horizontal = 14.dp)) {
            val goldShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(goldShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFE8A91C), Color(0xFFF7DA5A), Color(0xFFE8A91C))))
                    .border(2.dp, Color(0xFFB8860B), goldShape)
                    .clickable { onWalletClick() },
            ) {
                Text("💰", fontSize = 58.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp))
                Column(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        "Dompet",
                        color = Color(0xFF7A4A00),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontStyle = FontStyle.Italic,
                    )
                    Text("${fmtCompact(coins)} koin", color = Color(0xFF7A4A00), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cardShape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp)
                        .clip(cardShape)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))))
                        .border(1.5.dp, Color(0xFFE8B923), cardShape)
                        .clickable { onVipClick() }
                        .padding(14.dp),
                ) {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        Text("VIP", color = Color(0xFFFFD54F), fontSize = 16.sp)
                        Text(if (vipOn) "VIP${wallet.vip}" else "VIP", color = Color(0xFFFFD54F), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic)
                    }
                    Text("👑", fontSize = 40.sp, modifier = Modifier.align(Alignment.CenterEnd))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(84.dp)
                        .clip(cardShape)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF1FA89A), Color(0xFF0E7C86))))
                        .border(1.5.dp, Color(0xFF7FE3D6), cardShape)
                        .clickable { onLevelClick() }
                        .padding(14.dp),
                ) {
                    Column(Modifier.align(Alignment.CenterStart)) {
                        Text(tr("Level user"), color = Color(0xFFB8F5EC), fontSize = 16.sp)
                        Text("LV.$level", color = Color(0xFFFFE082), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic)
                    }
                    Text("🛡", fontSize = 40.sp, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
            // ---------- Menu ----------
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(vertical = 4.dp),
            ) {
                MenuItem("🎁", "Undang teman dan dapatkan koin") { onInviteClick() }
                MenuItem("⭐", "Lencana glory") { onGloryClick() }
                MenuItem("🎨", "Pusat kustom") { soon("Pusat kustom") }
                MenuItem("🏪", "Store & My Item") { onStoreClick() }
                MenuItem("🧰", "Alat peraga") { soon("Alat peraga") }
                MenuItem("🏰", "Family", trailing = "Liga Peringkat Fam") { onFamilyClick() }
                MenuItem("🏠", "CP house") { soon("CP house") }
                MenuItem("⚙", "Pengaturan") { onSettingsClick() }
                MenuItem("❓", "Bantuan") { soon("Bantuan") }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onSignOutClick,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFD9534F)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD9534F)),
            ) { Text(tr("Keluar")) }
            Spacer(Modifier.height(20.dp))
        }
    }
}
