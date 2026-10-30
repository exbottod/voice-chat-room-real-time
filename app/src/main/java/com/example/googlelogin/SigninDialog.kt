package com.example.googlelogin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private val Teal = Color(0xFF0E8C8C)
private val TealDark = Color(0xFF08605F)
private val GoldA = Color(0xFFD4AF37)
private val GoldB = Color(0xFFFFD700)
private val Cream = Color(0xFFF6F0DC)

/** Ikon hadiah kecil: coin atau item. */
@Composable
private fun RewardChip(text: String, sub: String, highlight: Boolean) {
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, color = if (highlight) Color(0xFF0B4F4F) else Color(0xFF4A4A4A), fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(3.dp))
        Text(sub, fontSize = 11.sp, color = if (highlight) Color(0xFF0B4F4F) else Color(0xFF6B6B6B))
    }
}

/**
 * Dialog cek in harian 1-7 hari.
 * Muncul otomatis sekali sehari sesudah ganti tanggal.
 */
@Composable
fun SigninDialog(
    uid: String,
    onClose: () -> Unit,
    onClaimed: (Long) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(SigninCfg()) }
    var st by remember { mutableStateOf(SigninState()) }
    var loaded by remember { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(uid) {
        cfg = SigninRepo.loadCfg()
        st = SigninRepo.loadState(uid)
        loaded = true
    }

    val next = SigninRepo.nextDay(st)
    val canClaim = SigninRepo.canClaim(st)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(22.dp))
                .background(Cream)
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- header emas
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(GoldA, Color(0xFFB8860B))))
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Terima hadiah dengan sign-in",
                        color = Color(0xFF3B2A00), fontWeight = FontWeight.Black, fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("Cek in harian, hadiah makin besar tiap hari", color = Color(0xFF5A4300), fontSize = 11.sp)
                }
            }

            // ---- progress ke peti
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDDD6C0)),
                    ) {
                        val frac = (st.total.toFloat() / cfg.target.coerceAtLeast(1)).coerceIn(0.05f, 1f)
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(frac)
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(Teal, Color(0xFF20B2AA)))),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("🎁", fontSize = 20.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "(${st.total} hari/${cfg.target} hari)",
                    fontSize = 11.sp, color = Color(0xFF6B6250),
                )
                Text(
                    if (canClaim) "Cek in hari ini untuk hadiah hari ke-$next" else "Hari ke-${st.day} sudah diklaim, besok lagi ya",
                    fontSize = 12.sp, color = TealDark, fontWeight = FontWeight.Bold,
                )
            }

            // ---- grid 7 hari
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                for (rowStart in listOf(1, 5)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in rowStart until minOf(rowStart + 4, 8)) {
                            val reward = cfg.days.getOrNull(d - 1) ?: SigninReward()
                            val claimed = st.day >= d && st.total > 0
                            val isNext = d == next && canClaim
                            DayCard(
                                day = d,
                                reward = reward,
                                claimed = claimed,
                                highlight = isNext,
                                big = d == 7,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowStart == 5) Spacer(Modifier.weight(0.15f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ---- tombol sign in
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            if (canClaim) listOf(GoldB, GoldA) else listOf(Color(0xFFBFB9A6), Color(0xFF9E9A8C)),
                        ),
                    )
                    .clickable(enabled = canClaim && !claiming && loaded) {
                        claiming = true
                        msg = ""
                        scope.launch {
                            val (day, err) = SigninRepo.claim(uid)
                            claiming = false
                            if (err != null) {
                                msg = err
                            } else {
                                val r = cfg.days.getOrNull(day - 1)
                                if (r != null && r.coins > 0) onClaimed(r.coins)
                                st = SigninRepo.loadState(uid)
                                msg = if (day == 7) "Hadiah besar hari ke-7 diterima!" else "Hadiah hari ke-$day diterima!"
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (claiming) "Memproses…" else if (canClaim) "Sign in" else "Sudah cek in hari ini",
                    color = Color(0xFF3B2A00), fontWeight = FontWeight.Black, fontSize = 16.sp,
                )
            }

            if (msg.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(msg, fontSize = 12.sp, color = TealDark, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Tutup", fontSize = 12.sp, color = Color(0xFF8A8472),
                modifier = Modifier.clickable { onClose() }.padding(6.dp),
            )
        }
    }
}

@Composable
private fun DayCard(
    day: Int,
    reward: SigninReward,
    claimed: Boolean,
    highlight: Boolean,
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(
                if (highlight) 2.dp else 1.dp,
                if (highlight) Teal else Color(0xFFE2DCC8),
                RoundedCornerShape(10.dp),
            )
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(if (big) GoldA else Teal),
                contentAlignment = Alignment.Center,
            ) { Text("$day", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            if (claimed) Text("✓", color = Color(0xFF9E9E9E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        if (big) {
            Text("Reward Besar", fontSize = 8.sp, color = GoldA, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text("🪙", fontSize = if (big) 20.sp else 17.sp)
        RewardChip("${reward.coins}", if (big) "coin" else "", highlight)
        if (reward.vipTier > 0) {
            Text("👑 VIP${reward.vipTier}", fontSize = 9.sp, color = Color(0xFF8A5A00), fontWeight = FontWeight.Bold, maxLines = 1)
        }
        val item = findItem(reward.itemId)
        if (item != null) {
            Text(
                "${item.emoji} ${reward.itemDays}d",
                fontSize = 9.sp, color = Color(0xFF5A5A5A), maxLines = 1,
            )
        }
    }
}

/** Ikon header kecil untuk tab profil (opsional). */
@Composable
fun SigninBadge(hasReward: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (hasReward) Brush.verticalGradient(listOf(GoldB, GoldA)) else Brush.verticalGradient(listOf(Color(0xFF4A3B1E), Color(0xFF2A2110))))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_invite_coins), null, Modifier.size(20.dp))
        if (hasReward) {
            Box(Modifier.size(8.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color(0xFFE53935)))
        }
    }
}
