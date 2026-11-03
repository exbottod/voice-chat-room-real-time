package com.example.googlelogin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

/**
 * Daftar orang yang sedang ada di room, dibuka dari pil jumlah orang (👤 N)
 * di bar atas room. [members] adalah UID live dari node members room;
 * profil tiap orang dimuat bertahap lewat UserCache, pola yang sama dengan
 * SeatInviteDialog. Yang sedang duduk di mic ditampilkan lebih dulu dari
 * data kursi, sisanya masuk seksi Penonton.
 */
@Composable
fun RoomMembersSheet(
    members: Set<String>,
    seats: List<Seat>,
    ownerUid: String,
    myUid: String,
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val seatedUids = remember(seats) { seats.map { it.uid }.toSet() }
    val audience = remember(members, seatedUids) { (members - seatedUids).toList() }
    var users by remember(members) { mutableStateOf<Map<String, PublicUser?>>(emptyMap()) }
    LaunchedEffect(members) {
        val m = HashMap<String, PublicUser?>()
        for (uid in members) {
            m[uid] = try {
                UserCache.byUid(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        users = m
    }
    // Penonton: pemilik room paling atas, lalu urut nama setelah profil termuat.
    // Data members tidak menyimpan waktu masuk, jadi urutan lama-di-room tidak tersedia.
    val audienceSorted = remember(audience, users) {
        audience.sortedWith(
            compareBy<String> { it != ownerUid }
                .thenBy { users[it]?.name?.lowercase() ?: it },
        )
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
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(0xFF14110C))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Orang di room") + " (${members.size})",
                    color = GoldLight,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    color = Color(0xFFBDBDBD),
                    fontSize = 15.sp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                if (seats.isNotEmpty()) {
                    item { MemberSectionTitle(tr("Di mic") + " (${seats.size})") }
                    items(seats, key = { "seat-" + it.index }) { s ->
                        RoomMemberRow(
                            u = users[s.uid],
                            uid = s.uid,
                            seat = s,
                            isOwner = s.uid == ownerUid,
                            isMe = s.uid == myUid,
                            onClick = { onOpenProfile(s.uid) },
                        )
                    }
                }
                if (audienceSorted.isNotEmpty()) {
                    item { MemberSectionTitle(tr("Penonton") + " (${audienceSorted.size})") }
                    items(audienceSorted, key = { "aud-$it" }) { uid ->
                        RoomMemberRow(
                            u = users[uid],
                            uid = uid,
                            seat = null,
                            isOwner = uid == ownerUid,
                            isMe = uid == myUid,
                            onClick = { onOpenProfile(uid) },
                        )
                    }
                }
                if (members.isEmpty()) {
                    item {
                        Text(
                            tr("Belum ada orang di room ini"),
                            color = Color(0xFFBDBDBD),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberSectionTitle(text: String) {
    Text(
        text,
        color = Gold,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun MemberChip(label: String) {
    Text(
        label,
        color = GoldLight,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x33FFC107))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun RoomMemberRow(
    u: PublicUser?,
    uid: String,
    seat: Seat?,
    isOwner: Boolean,
    isMe: Boolean,
    onClick: () -> Unit,
) {
    // Selama profil publik belum termuat, data kursi (nama + avatar) dipakai
    // sebagai tampilan sementara untuk yang sedang di mic.
    val shown = u ?: seat?.let {
        PublicUser(
            userId = "",
            uid = it.uid,
            name = it.name,
            countryCode = "",
            bio = "",
            avatarB64 = it.avatarB64,
            photoUrl = null,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InvAvatar(shown, uid)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shown?.name ?: "UID $uid",
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
                if (seat != null && seat.index == 0) {
                    Spacer(Modifier.width(6.dp))
                    MemberChip("MC")
                }
                if (isOwner) {
                    Spacer(Modifier.width(6.dp))
                    MemberChip("Owner")
                }
                if (isMe) {
                    Spacer(Modifier.width(6.dp))
                    MemberChip(tr("Anda"))
                }
            }
            val idLine = u?.userId?.takeIf { it.isNotBlank() }
            val svip = u?.svipLevel() ?: 0
            val sub = when {
                idLine != null && svip > 0 -> "ID $idLine · SVIP $svip"
                idLine != null -> "ID $idLine"
                svip > 0 -> "SVIP $svip"
                else -> ""
            }
            if (sub.isNotEmpty()) {
                Text(sub, color = Color(0xFF9A9A9A), fontSize = 12.sp)
            }
        }
        Text("›", color = Color(0xFF9A9A9A), fontSize = 18.sp)
    }
}
