package com.example.googlelogin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Dialog untuk OWNER: pilih user yang sedang ada di room (belum duduk di mic)
 * lalu mengundangnya naik ke kursi mic yang dipilih.
 */
@Composable
fun SeatInviteDialog(
    members: Set<String>,
    seatedUids: Set<String>,
    index: Int,
    myUid: String,
    onDismiss: () -> Unit,
    onInvite: (PublicUser) -> Unit,
) {
    val candidates = remember(members, seatedUids) {
        members.filter { it != myUid && it !in seatedUids }
    }
    var users by remember(candidates) { mutableStateOf<Map<String, PublicUser?>>(emptyMap()) }
    LaunchedEffect(candidates) {
        val m = HashMap<String, PublicUser?>()
        for (uid in candidates) {
            m[uid] = try {
                UserCache.byUid(uid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        users = m
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14110C),
        title = {
            Text(
                if (index == 0) "Undang ke kursi MC" else "Undang ke kursi No.${index + 1}",
                color = GoldLight,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            if (candidates.isEmpty()) {
                Text("Tidak ada user lain di room ini", color = Color(0xFFBDBDBD))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(candidates.size) { i ->
                        val uid = candidates[i]
                        val u = users[uid]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { u?.let(onInvite) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InvAvatar(u, uid)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u?.name ?: "UID $uid", color = Color.White, fontSize = 15.sp, maxLines = 1)
                                Text(u?.userId ?: "", color = Color(0xFF9A9A9A), fontSize = 12.sp)
                            }
                            Text("Undang", color = Gold, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup", color = Color(0xFFBDBDBD)) } },
    )
}

@Composable
fun InvAvatar(u: PublicUser?, uid: String) {
    val bmp = remember(u?.avatarB64) { ProfileRepo.decodeB64(u?.avatarB64) }
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF263238)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !u?.photoUrl.isNullOrBlank() ->
                AsyncImage(
                    model = u!!.photoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            else -> Text((u?.name ?: uid).take(1).uppercase(), color = GoldLight, fontWeight = FontWeight.Bold)
        }
    }
}
