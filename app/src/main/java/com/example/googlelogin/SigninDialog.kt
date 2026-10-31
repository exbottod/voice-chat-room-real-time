package com.example.googlelogin

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private val SinTeal = Color(0xFF0E8C8C)
private val SinTealDark = Color(0xFF075E5E)
private val SinGold = Color(0xFFD4AF37)
private val SinGoldLight = Color(0xFFFFD966)
private val SinCream = Color(0xFFFFFDF0)
private val SinInk = Color(0xFF5A4A20)

/** Hadiah satu hari: jumlah coin + item (bingkai custom) + VIP. */
@Composable
private fun DayCell(
    day: Int,
    reward: SigninReward,
    claimed: Boolean,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = remember(reward.itemId) { findItem(reward.itemId) }
    val big = day == 7
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(
                if (highlight) 2.dp else 1.dp,
                if (highlight) SinTeal else Color(0xFFE6DFC8),
                RoundedCornerShape(10.dp),
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Box(
                Modifier.size(17.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (big) SinGold else SinTeal),
                contentAlignment = Alignment.Center,
            ) { Text("$day", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            if (claimed) Text("✓", color = Color(0xFF9E9E9E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(3.dp))
        // ikon coin + jumlah
        Text("🪙", fontSize = 15.sp)
        Text(
            "x " + fmtShort(reward.coins),
            fontSize = 9.sp, color = SinInk, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        // ikon item custom (bingkai hasil upload panel)
        if (item != null) {
            Spacer(Modifier.height(2.dp))
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                val a = item.asset
                if (a != null) {
                    if (item.slot == "frame") {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape).background(Color(0xFF3B5A58)),
                            contentAlignment = Alignment.Center,
                        ) { Text("👤", fontSize = 8.sp) }
                        FrameOverlay(a, 30.dp)
                    } else {
                        key(a.updatedAt) { VipMedia(a, Modifier.fillMaxSize(), loop = true) }
                    }
                } else {
                    Text(item.emoji, fontSize = 16.sp)
                }
            }
            Text(
                if (reward.itemDays > 0) "x ${reward.itemDays}d" else item.name,
                fontSize = 8.sp, color = Color(0xFF6B6B6B), maxLines = 1,
            )
        }
        if (reward.vipTier > 0) {
            Text("👑 VIP${reward.vipTier}", fontSize = 8.sp, color = Color(0xFF8A5A00), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun SigninHeader() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFB8860B), SinGold, Color(0xFF8A6400))))
            .border(2.dp, Color(0xFFFFE9A0), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Terima hadiah dengan sign-in",
            color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Dialog cek in harian 1-7 hari. Muncul otomatis sekali sehari setelah tanggal berganti.
 * Hadiah (coin, bingkai custom, VIP) diatur dari panel developer.
 */
@Composable
fun SigninDialog(
    uid: String,
    balance: Long = 0L,
    vipTier: Int = 0,
    onClose: () -> Unit,
    onClaimed: (Long) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(SigninCfg()) }
    var st by remember { mutableStateOf(SigninState()) }
    var claiming by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(uid) {
        cfg = SigninRepo.loadCfg()
        st = SigninRepo.loadState(uid)
    }

    val next = SigninRepo.nextDay(st)
    val canClaim = SigninRepo.canClaim(st)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(20.dp))
                .background(SinCream)
                .border(4.dp, SinGold, RoundedCornerShape(20.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SigninHeader()

            // ---- progress ke peti
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).height(10.dp).clip(CircleShape).background(Color(0xFFE4DDC6)),
                    ) {
                        val frac = (st.total.toFloat() / cfg.target.coerceAtLeast(1)).coerceIn(0.04f, 1f)
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(frac).clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(SinTeal, Color(0xFF20B2AA)))),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("🧰", fontSize = 18.sp)
                }
                Spacer(Modifier.height(3.dp))
                Text("(${st.total} hari/${cfg.target} hari)", fontSize = 11.sp, color = Color(0xFF6B6250))
                Text(
                    if (canClaim) "Sign-in hari ke-$next minggu ini" else "Hari ke-${st.day} sudah sign-in",
                    fontSize = 11.sp, color = SinTealDark, fontWeight = FontWeight.Bold,
                )
            }

            // ---- 7 hari
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 1..4) {
                        val r = cfg.days.getOrNull(d - 1) ?: SigninReward()
                        DayCell(
                            day = d, reward = r,
                            claimed = st.total > 0 && st.day >= d,
                            highlight = d == next && canClaim,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 5..7) {
                        val r = cfg.days.getOrNull(d - 1) ?: SigninReward()
                        DayCell(
                            day = d, reward = r,
                            claimed = st.total > 0 && st.day >= d,
                            highlight = d == next && canClaim,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            // ---- saldo + VIP
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("🪙", fontSize = 16.sp)
                Spacer(Modifier.width(4.dp))
                Text(fmtFmt(balance), color = SinInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (vipTier > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Brush.horizontalGradient(listOf(SinGold, SinGoldLight)))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) { Text("VIP", color = Color(0xFF3B2A00), fontSize = 9.sp, fontWeight = FontWeight.Black) }
                }
            }

            // ---- tombol sign in
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            if (canClaim) listOf(SinGoldLight, SinGold, Color(0xFFB8860B))
                            else listOf(Color(0xFFC9C4B2), Color(0xFF9E9A8C)),
                        ),
                    )
                    .border(2.dp, Color(0xFFFFF0B8), RoundedCornerShape(26.dp))
                    .clickable(enabled = canClaim && !claiming) {
                        claiming = true
                        msg = ""
                        scope.launch {
                            val (day, err) = SigninRepo.claim(uid)
                            claiming = false
                            if (err != null) {
                                msg = err
                            } else {
                                cfg.days.getOrNull(day - 1)?.let { if (it.coins > 0) onClaimed(it.coins) }
                                st = SigninRepo.loadState(uid)
                                msg = if (day == 7) "Hadiah besar hari ke-7 diterima!" else "Hadiah hari ke-$day diterima!"
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (claiming) "Memproses…" else if (canClaim) "Sign in" else "Sudah sign-in hari ini",
                    color = Color(0xFF3B2A00), fontWeight = FontWeight.Black, fontSize = 17.sp,
                )
            }

            if (msg.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(msg, fontSize = 12.sp, color = SinTealDark, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Text(
                "Tutup", fontSize = 11.sp, color = Color(0xFF8A8472),
                modifier = Modifier.clickable { onClose() }.padding(8.dp),
            )
        }
    }
}

private fun fmtFmt(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
        out.append(c)
    }
    return out.toString()
}

private fun fmtShort(n: Long): String = when {
    n >= 1_000_000_000L -> (n / 100_000_000L / 10.0).toString() + "B"
    n >= 1_000_000L -> (n / 100_000L / 10.0).toString() + "M"
    n >= 1_000L -> (n / 100L / 10.0).toString() + "K"
    else -> n.toString()
}
