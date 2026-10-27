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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.*
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

// =====================================================================
//  LEVEL 1-250
// =====================================================================

const val MAX_LEVEL = 250
private const val LV39_EXP = 3_932_550_000.0   // sesuai foto: Lv.39 butuh 3.932.550.000 exp
const val LEVEL_REWARD_PER_LV = 243_000L        // Lv.40 -> 9.720.000 koin (sesuai foto)

/** Total exp yang dibutuhkan untuk MENCAPAI level n. Ubah rumus ini kalau mau tabel sendiri. */
fun expForLevel(n: Int): Long =
    if (n <= 1) 0L else Math.round(LV39_EXP * (n / 39.0).pow(2.5) / 10000.0) * 10000L

fun levelFromExp(exp: Long): Int {
    var l = 1
    while (l < MAX_LEVEL && exp >= expForLevel(l + 1)) l++
    return l
}

private val LV_COLORS = listOf(
    0xFF78909C, 0xFF26C6DA, 0xFF9CCC65, 0xFF8E44E8, 0xFFC13BE0,
    0xFFFF9800, 0xFFEF5350, 0xFFFFC107, 0xFFFF4081, 0xFF7C4DFF,
)

fun levelBrush(level: Int): Brush {
    val c = Color(LV_COLORS[(level / 10) % LV_COLORS.size])
    return Brush.horizontalGradient(listOf(c, lerp(c, Color(0xFFB04CFF), 0.45f)))
}

// =====================================================================
//  VIP 1-9
// =====================================================================

const val VIP_DAYS = 30L
private val VIP_PRICES = listOf(
    500_000L, 1_500_000L, 3_000_000L, 6_000_000L, 12_000_000L,
    25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L,
)
private val VIP_COLORS = listOf(
    0xFFD2874A, 0xFFB0BEC5, 0xFFFFC107, 0xFF2ECC71, 0xFF3D8BFF,
    0xFF9B59F5, 0xFFE91E8C, 0xFFE53935, 0xFFFF9800,
)

fun vipPrice(n: Int): Long = VIP_PRICES[(n - 1).coerceIn(0, 8)]
fun vipColor(n: Int): Color = Color(VIP_COLORS[(n - 1).coerceIn(0, 8)])
fun vipActive(vip: Int, until: Long): Boolean = vip in 1..9 && until > System.currentTimeMillis()
fun vipBoostPercent(vip: Int): Int = 5 * vip

// =====================================================================
//  STORE / MY ITEM
// =====================================================================

data class StoreItem(
    val id: String,
    val slot: String,        // frame | bubble | badge | title
    val name: String,
    val emoji: String,
    val price: Long,
    val days: Long,
    val color: Color,
    val vip: Int = 0,        // > 0 = item eksklusif dari VIP (tidak dijual di store)
    val asset: VipAsset? = null,
)

val STORE_ITEMS = listOf(
    StoreItem("frame_gold", "frame", "Bingkai Emas", "⭕", 300_000, 7, Color(0xFFFFC107)),
    StoreItem("frame_aqua", "frame", "Bingkai Aqua", "⭕", 300_000, 7, Color(0xFF26C6DA)),
    StoreItem("frame_rose", "frame", "Bingkai Rose", "⭕", 500_000, 30, Color(0xFFFF4081)),
    StoreItem("frame_violet", "frame", "Bingkai Violet", "⭕", 500_000, 30, Color(0xFF9B59F5)),
    StoreItem("bubble_blue", "bubble", "Gelembung Biru", "💬", 200_000, 7, Color(0xFF3D8BFF)),
    StoreItem("bubble_green", "bubble", "Gelembung Hijau", "💬", 200_000, 7, Color(0xFF2ECC71)),
    StoreItem("bubble_pink", "bubble", "Gelembung Pink", "💬", 400_000, 30, Color(0xFFFF6FA5)),
    StoreItem("bubble_purple", "bubble", "Gelembung Ungu", "💬", 400_000, 30, Color(0xFF8E44E8)),
    StoreItem("badge_star", "badge", "Lencana Bintang", "⭐", 150_000, 30, Color(0xFFFFC107)),
    StoreItem("badge_fire", "badge", "Lencana Api", "🔥", 250_000, 30, Color(0xFFFF7043)),
    StoreItem("badge_diamond", "badge", "Lencana Berlian", "💎", 800_000, 30, Color(0xFF4DD0E1)),
    StoreItem("badge_heart", "badge", "Lencana Hati", "💖", 250_000, 30, Color(0xFFFF4081)),
)

private fun vipItems(n: Int) = listOf(
    StoreItem("vipt_$n", "title", "Gelar VIP$n", "🏅", 0, VIP_DAYS, vipColor(n), n),
    StoreItem("vipf_$n", "frame", "Bingkai eksklusif VIP$n", "⭕", 0, VIP_DAYS, vipColor(n), n),
    StoreItem("vipb_$n", "bubble", "Gelembung obrolan VIP$n", "💬", 0, VIP_DAYS, vipColor(n), n),
    StoreItem("vipe_$n", "entry", "Efek masuk VIP$n", "✨", 0, VIP_DAYS, vipColor(n), n),
    StoreItem("vipbn_$n", "banner", "Banner efek masuk VIP$n", "🚩", 0, VIP_DAYS, vipColor(n), n),
)

private val ALL_ITEMS: List<StoreItem> by lazy { STORE_ITEMS + (1..9).flatMap { vipItems(it) } }

fun findItem(id: String?): StoreItem? {
    if (id.isNullOrBlank()) return null
    if (id.startsWith("sx_")) return StoreCatalog.find(id)?.toStoreItem()
    return ALL_ITEMS.firstOrNull { it.id == id }
}

fun activeEquip(w: Wallet, slot: String): String {
    val id = w.equip[slot] ?: return ""
    return if ((w.inv[id] ?: 0L) > System.currentTimeMillis()) id else ""
}


fun frameBrush(id: String?): Brush {
    val c = findItem(id)?.color
    return if (c == null) Brush.sweepGradient(listOf(GoldLight, Gold, GoldDark, Gold, GoldLight))
    else Brush.sweepGradient(listOf(c, Color.White, c, c.copy(alpha = 0.6f), c))
}

fun bubbleColor(id: String?): Color =
    findItem(id)?.color?.copy(alpha = 0.6f) ?: Color(0x66000000)

// =====================================================================
//  DATA: parse wallet, repo, sinkron ke profil publik
// =====================================================================

fun parseWallet(s: DocumentSnapshot?): Wallet {
    if (s == null) return Wallet()
    val inv = (s.get("inv") as? Map<*, *>)?.mapNotNull { (k, v) ->
        val n = (v as? Number)?.toLong()
        if (k is String && n != null) k to n else null
    }?.toMap() ?: emptyMap()
    val eq = (s.get("equip") as? Map<*, *>)?.mapNotNull { (k, v) ->
        if (k is String && v is String) k to v else null
    }?.toMap() ?: emptyMap()
    return Wallet(
        coins = s.getLong("coins") ?: 0L,
        diamonds = s.getLong("diamonds") ?: 0L,
        exp = s.getLong("exp") ?: 0L,
        vip = (s.getLong("vip") ?: 0L).toInt(),
        vipUntil = s.getLong("vipUntil") ?: 0L,
        inv = inv,
        equip = eq,
        lvClaimed = s.getLong("lvClaimed") ?: 0L,
    )
}

object LvRepo {
    /**
     * Semua operasi store (beli VIP, beli item, pasang, klaim level) kini
     * dieksekusi Supabase lewat Edge Function wallet-api: saldo dicek dan
     * dipotong secara atomik di server, Firestore tidak lagi disentuh.
     */

    /** Beli / perpanjang / upgrade VIP. Harga VIP diverifikasi di server. */
    suspend fun buyVip(uid: String, tier: Int): String? {
        val r = SupaRepo.buyVip(uid, tier)
        r.wallet?.let { WalletRepo.emitBackup(uid, it) }
        return r.error
    }

    /** Beli / perpanjang item store (lencana, frame, gelembung, dsb). */
    suspend fun buyItem(uid: String, item: StoreItem): String? {
        val r = SupaRepo.buyItem(uid, item)
        r.wallet?.let { WalletRepo.emitBackup(uid, it) }
        return r.error
    }

    /** Pasang / lepas item ke slot (frame, bubble, badge, entry, banner, medals, ...). */
    suspend fun equip(uid: String, slot: String, id: String): String? {
        val r = SupaRepo.equip(uid, slot, id)
        r.wallet?.let { WalletRepo.emitBackup(uid, it) }
        return r.error
    }

    /** Klaim hadiah level (server menghitung level dari exp, reward tak bisa dimanipulasi). */
    suspend fun claimLevel(uid: String): String? {
        val r = SupaRepo.claimLevel(uid)
        r.wallet?.let { WalletRepo.emitBackup(uid, it) }
        return r.error
    }

    /** Lengkapi item VIP yang belum masuk My Item kalau ada yang hilang. */
    suspend fun backfillVip(uid: String, w: Wallet) {
        if (!vipActive(w.vip, w.vipUntil)) return
        val r = SupaRepo.backfillVip(uid)
        r.wallet?.let { WalletRepo.emitBackup(uid, it) }
    }
}

/** Salin exp, VIP & item yang dipakai ke userIds/{id} supaya pemain lain bisa melihatnya. */

// =====================================================================
//  KOMPONEN KECIL (dipakai profil, chat room, sheet profil)
// =====================================================================

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))

private fun daysLeft(ms: Long): Long =
    ((ms - System.currentTimeMillis()) / 86_400_000L + 1).coerceAtLeast(1L)

@Composable
fun VipTagText(n: Int, modifier: Modifier = Modifier) {
    val c = vipColor(n)
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(c, lerp(c, Color.Black, 0.35f))))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) { Text("👑 VIP$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
}

/** Lencana di bawah level & nama: VIP otomatis + lencana store yang dipakai. */
@Composable
fun BadgeRow(vip: Int, until: Long, badge: String, modifier: Modifier = Modifier) {
    val on = vipActive(vip, until)
    val b = findItem(badge)
    if (!on && b == null) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (on) VipTag(vip)
        if (b != null) StoreBadgeIcon(b, 24.dp)
    }
}

@Composable
fun UserBadges(u: PublicUser?, modifier: Modifier = Modifier) {
    if (u == null) return
    BadgeRow(u.vip, u.vipUntil, u.badge, modifier)
}

@Composable
fun VipBanner(u: PublicUser) {
    val on = vipActive(u.vip, u.vipUntil)
    val c = if (on) vipColor(u.vip) else Color(0xFF3B2A0A)
    Box(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(lerp(c, Color.Black, 0.55f), Color(0xFF1A1206))))
            .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "VIP${u.vip}" else "VIP", color = GoldLight, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (on) Text("👑 s/d ${fmtDate(u.vipUntil)}", color = Color(0xFFE0E0E0), fontSize = 12.sp)
            else Text("Belum VIP", color = Color(0xFF9A9A9A), fontSize = 12.sp)
        }
    }
}

@Composable
private fun LvTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp)) {
        Text(
            "‹", color = Color.White, fontSize = 38.sp,
            modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 18.dp),
        )
        Text(title, color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(30.dp).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("?", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun GoldPill(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) GoldBrush else Brush.horizontalGradient(listOf(Color(0xFF555555), Color(0xFF444444))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.Black else Color(0xFFBBBBBB), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

@Composable
private fun OrnTitle(text: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("❦", color = color, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Text("❦", color = color, fontSize = 22.sp)
    }
}


// =====================================================================
//  LAYAR LEVEL USER
// =====================================================================

@Composable
fun LevelScreen(user: UserProfile, profile: ProfileData, wallet: Wallet, uid: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val level = levelFromExp(wallet.exp)
    val maxed = level >= MAX_LEVEL
    val next = if (maxed) wallet.exp else expForLevel(level + 1)
    val need = (next - wallet.exp).coerceAtLeast(0L)
    val frac = if (maxed || next <= 0L) 1f else (wallet.exp.toDouble() / next.toDouble()).toFloat().coerceIn(0f, 1f)
    val claimable = level > wallet.lvClaimed
    val teal = Color(0xFF2FD6BC)
    val nick = profile.nickname.ifBlank { user.name }

    Box(
        Modifier.fillMaxSize().blockTouches()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E7C70), Color(0xFF015A55), Color(0xFF003A39)))),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            LvTopBar("Level user", onBack)
            // ---- kartu profil + progres ----
            Box(
                Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF4FE8D0), Color(0xFF22B7A3))))
                    .border(2.dp, Color(0x6642F5DC), RoundedCornerShape(26.dp)).padding(16.dp),
            ) {
                // Icon level besar (lencana kustom) pengganti perisai emoji.
                LevelBadge(level, Modifier.align(Alignment.TopEnd), height = 62.dp)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarImage(user, profile, 62.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(nick, color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                            Spacer(Modifier.height(4.dp))
                            LevelBadge(level)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).background(Color(0x44000000)).padding(horizontal = 14.dp, vertical = 3.dp)) {
                        Text("${wallet.exp}/$next", color = Color.White, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)).background(Color(0x55006B63))) {
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color(0xFF0E8F7F)))
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(if (maxed) "MAX" else "Lv.${level + 1}", color = Color(0xFF0B6B5F), fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        buildAnnotatedString {
                            append("Masih memerlukan ")
                            withStyle(SpanStyle(color = Color(0xFFFFF176))) { append(need.toString()) }
                            append(" nilai pengalaman untuk naik level.")
                        },
                        color = Color(0xFF0B3D38), fontSize = 15.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // ---- hadiah level berikutnya ----
            Column(
                Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF0B8F83)).padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            append("Mencapai ")
                            withStyle(SpanStyle(color = Color(0xFFFFF176))) { append("Lv.${(level + 1).coerceAtMost(MAX_LEVEL)}") }
                            append(" bisa mendapatkan")
                        },
                        color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Pergi naikkan level ➜", color = Color(0xFFFFF176), fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            Toast.makeText(context, "Kirim gift di room untuk menambah exp (1 koin = 1 exp)", Toast.LENGTH_LONG).show()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x3322D6C0)).padding(10.dp)) {
                    Text("Tingkat level paket hadiah", color = Color.White, fontSize = 15.sp)
                    Text("Bisa diambil ketika naik level.", color = Color(0xFFD7FFF8), fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val nl = (level + 1).coerceAtMost(MAX_LEVEL)
                        listOf(
                            "🪙" to "only coin\nx${nl * LEVEL_REWARD_PER_LV}",
                            "🚗" to "Pond cart\nx7d",
                            "🔥" to "Phoenix Yuvi\nx7d",
                            "🐉" to "Fiery dragon\nx7d",
                        ).forEach { (e, t) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(e, fontSize = 40.sp)
                                Text(t, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("🛡️" to "Ikon level", "✨" to "Approach show").forEach { (e, t) ->
                        Column(
                            Modifier.width(104.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x3322D6C0)).padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(e, fontSize = 40.sp)
                            Text(t, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                GoldPill(
                    if (claimable) "Klaim hadiah level (Lv.${wallet.lvClaimed + 1}–$level)" else "Hadiah level sudah diklaim",
                    enabled = claimable, modifier = Modifier.fillMaxWidth(),
                ) {
                    AppScope.scope.launch {
                        val err = LvRepo.claimLevel(uid)
                        Toast.makeText(context, err ?: "Hadiah level diklaim!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // ---- ikon level ----
            Spacer(Modifier.height(18.dp))
            OrnTitle("Ikon level", teal)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                for (g in 0..25) {
                    val lo = if (g == 0) 1 else g * 10
                    val label = when {
                        g == 0 -> "Lv.1~9"
                        g == 25 -> "Lv.250"
                        else -> "Lv.$lo~${lo + 9}"
                    }
                    val reached = level >= lo
                    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = if (reached) Color.White else Color(0xFF8FBDB8), fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (reached) Color(0xFF2FE6D0) else Color(0xFF5E8F8A)))
                        Spacer(Modifier.height(10.dp))
                        LevelBadge(lo.coerceAtMost(MAX_LEVEL), height = 38.dp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            OrnTitle("Tingkat level paket hadiah", teal)
            Text(
                "1. Setelah mencapai level yang ditentukan, hadiah koin bisa diambil lewat tombol Klaim.\n" +
                    "2. Exp didapat dari kirim gift di room: 1 koin = 1 exp. VIP aktif mendapat bonus exp.\n" +
                    "3. Level maksimum adalah $MAX_LEVEL.",
                color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

// =====================================================================
//  LAYAR VIP 1-9
// =====================================================================

@Composable
fun VipScreen(uid: String, wallet: Wallet, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val active = vipActive(wallet.vip, wallet.vipUntil)
    var sel by remember { mutableStateOf(if (active) wallet.vip else 1) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val c = vipColor(sel)
    val bgTop = lerp(Color(0xFF1C0A0C), c, 0.30f)
    val own = wallet.inv.keys.count { id -> vipItems(sel).any { it.id == id } }
    val higher = active && wallet.vip > sel
    val sameTier = active && wallet.vip == sel
    val price = vipPrice(sel)

    Box(
        Modifier.fillMaxSize().blockTouches()
            .background(Brush.verticalGradient(listOf(bgTop, Color(0xFF2A0F12), Color(0xFF14070A)))),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 130.dp)) {
            LvTopBar("VIP", onBack)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                for (n in 1..9) {
                    Column(
                        Modifier.clickable { sel = n }.padding(horizontal = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("VIP$n", color = if (n == sel) Color(0xFFFFEB3B) else Color(0x99FFFFFF), fontSize = if (n == sel) 30.sp else 24.sp, fontWeight = if (n == sel) FontWeight.Bold else FontWeight.Normal)
                        Box(Modifier.padding(top = 2.dp).width(34.dp).height(4.dp).clip(RoundedCornerShape(50)).background(if (n == sel) Color(0xFFFFEB3B) else Color.Transparent))
                    }
                }
            }
            // ---- hero ----
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(280.dp).background(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent))))
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🏮", fontSize = 40.sp)
                    Text("🏮", fontSize = 40.sp)
                }
                Text("🦌", fontSize = 130.sp, modifier = Modifier.offset(y = 14.dp))
                Text("👑", fontSize = 56.sp, modifier = Modifier.align(Alignment.TopCenter).offset(y = 22.dp))
            }
            val perks = listOf(
                "👑 Lencana VIP$sel otomatis tampil di bawah level & nama",
                "🎁 Gelar, bingkai, gelembung & efek masuk VIP$sel masuk ke My Item (Store)",
                "⚡ Bonus exp +${vipBoostPercent(sel)}% dari setiap gift",
                "🖼️ Bingkai avatar eksklusif VIP$sel",
                "💬 Gelembung chat eksklusif VIP$sel",
                "🕒 Aktif $VIP_DAYS hari, bisa diperpanjang",
            )
            Box(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).background(Color(0x55000000)).padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text("👑 Privileges ${perks.size}", color = Color(0xFFFFE9A8), fontSize = 22.sp)
            }
            Spacer(Modifier.height(22.dp))
            OrnTitle("Penampilan mewah $own/4", Color(0xFFE6C47A))
            VipAssets.lastError?.let { err ->
                Column(
                    Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)).background(Color(0x66B71C1C))
                        .clickable { VipAssets.start(force = true) }.padding(10.dp),
                ) {
                    Text("Aset VIP dari panel developer belum termuat", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(err, color = Color(0xFFFFCDD2), fontSize = 12.sp)
                    Text("Ketuk untuk coba lagi. Kalau 'permission denied', publish rules vipConfig.", color = Color(0xFFFFCDD2), fontSize = 12.sp)
                }
            }
            // ---- kartu penampilan: gelar, bingkai, gelembung, efek masuk (aset dari panel developer) ----
            LaunchedEffect(Unit) { VipAssets.load(force = true) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) {
                        key(sel) { VipTag(sel, Modifier.scale(1.6f)) }
                    }
                    Text("Gelar VIP", color = Color(0xFFE6C47A), fontSize = 17.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                        if (VipAssets.get(sel, "frame") == null) {
                            Box(Modifier.size(80.dp).border(6.dp, frameBrush("vipf_$sel"), CircleShape), contentAlignment = Alignment.Center) {
                                Text("VIP$sel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        } else {
                            Box(Modifier.size(86.dp).clip(CircleShape).background(Color(0xFF4A3030)), contentAlignment = Alignment.Center) {
                                Text("\uD83D\uDC64", fontSize = 34.sp)
                            }
                            key(sel) { VipFrame(sel, 120.dp) }
                        }
                    }
                    Text("Bingkai eksklusif", color = Color(0xFFE6C47A), fontSize = 17.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) {
                        val ba = VipAssets.get(sel, "bubble")
                        Box(
                            Modifier.width(150.dp).height(64.dp).clip(RoundedCornerShape(14.dp))
                                .then(
                                    if (ba == null) Modifier
                                        .background(Brush.verticalGradient(listOf(c, lerp(c, Color.Black, 0.5f))))
                                        .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                                    else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ba != null) {
                                key(sel) { VipMedia(ba, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) }
                            }
                            Text("Halo!", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Text("Gelembung obrolan", color = Color(0xFFE6C47A), fontSize = 17.sp, textAlign = TextAlign.Center)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val ea = VipAssets.get(sel, "entry")
                        if (ea != null) {
                            key(sel) { VipMedia(ea, Modifier.fillMaxSize(), loop = true) }
                        } else {
                            Text("\u2728", fontSize = 40.sp)
                        }
                    }
                    Text("Efek masuk", color = Color(0xFFE6C47A), fontSize = 17.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                perks.forEach { Text(it, color = Color(0xFFF3E3C3), fontSize = 15.sp) }
            }
        }
        // ---- bar bawah ----
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xF02E1316)).border(1.dp, Color(0x88E6C47A))
                .navigationBarsPadding().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Harga: ${fmtNum(price)} koin / $VIP_DAYS hari", color = Color(0xFFFFE9A8), fontSize = 16.sp)
                if (active) Text("VIP${wallet.vip} aktif · Exp +${vipBoostPercent(wallet.vip)}% sedang dipercepat. Berlaku sampai ${fmtDate(wallet.vipUntil)}", color = Color(0xFFE0C9C9), fontSize = 13.sp)
                else Text("Exp +${vipBoostPercent(sel)}% setelah VIP$sel aktif", color = Color(0xFFE0C9C9), fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            GoldPill(
                when {
                    higher -> "VIP${wallet.vip} aktif"
                    sameTier -> "Perpanjang"
                    active -> "Upgrade VIP$sel"
                    else -> "Beli VIP$sel"
                },
                enabled = !higher && !busy,
            ) { confirm = true }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Beli VIP$sel") },
        text = { Text("Harga ${fmtNum(price)} koin untuk $VIP_DAYS hari. Lencana, bingkai, gelembung, efek masuk & banner masuk VIP$sel otomatis masuk ke My Item.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                busy = true
                val tier = sel
                AppScope.scope.launch {
                    val err = LvRepo.buyVip(uid, tier)
                    if (err == null) {
                        PublicSync.push(uid)
                        Toast.makeText(context, "VIP$tier aktif!", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    busy = false
                }
            }) { Text("Beli") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } },
    )
}

// =====================================================================
//  LAYAR STORE + MY ITEM
// =====================================================================

@Composable
private fun LegacyItemPreview(item: StoreItem) {
    Box(Modifier.size(70.dp), contentAlignment = Alignment.Center) {
        val frameA = if (item.vip > 0 && item.slot == "frame") VipAssets.get(item.vip, "frame") else null
        val bubbleA = if (item.vip > 0 && item.slot == "bubble") VipAssets.get(item.vip, "bubble") else null
        val entryA = if (item.vip > 0 && item.slot == "entry") VipAssets.get(item.vip, "entry") else null
        when (item.slot) {
            "frame" -> if (frameA != null) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF4A3030)), contentAlignment = Alignment.Center) {
                    Text("\uD83D\uDC64", fontSize = 22.sp)
                }
                key(frameA.updatedAt) { VipFrame(item.vip, 70.dp) }
            } else {
                Box(Modifier.size(56.dp).border(5.dp, frameBrush(item.id), CircleShape))
            }
            "bubble" -> if (bubbleA != null) {
                Box(Modifier.width(62.dp).height(36.dp), contentAlignment = Alignment.Center) {
                    key(bubbleA.updatedAt) { VipMedia(bubbleA, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) }
                }
            } else {
                Box(Modifier.width(62.dp).height(36.dp).clip(RoundedCornerShape(10.dp)).background(item.color.copy(alpha = 0.75f)).border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp)))
            }
            "entry" -> if (entryA != null) {
                key(entryA.updatedAt) { VipMedia(entryA, Modifier.fillMaxSize(), loop = true) }
            } else {
                Text("\u2728", fontSize = 38.sp)
            }
            "title" -> VipTag(item.vip)
            else -> Text(item.emoji, fontSize = 38.sp)
        }
    }
}

@Composable
private fun ItemPreview(item: StoreItem, size: Dp = 70.dp) {
    // Banner VIP1-9: gambar langsung dari drawable di dalam APK.
    if (item.slot == "banner" && item.vip in 1..9 && item.asset == null) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                painterResource(vipBannerRes(item.vip)),
                null,
                Modifier.fillMaxWidth().height(size),
                contentScale = ContentScale.Fit,
            )
        }
        return
    }
    val dyn = item.asset
    if (dyn == null) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { LegacyItemPreview(item) }
        return
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (item.slot) {
            "frame" -> {
                Box(
                    Modifier.size((size.value * 0.62f).dp).clip(CircleShape).background(Color(0xFF3B5A58)),
                    contentAlignment = Alignment.Center,
                ) { Text("\uD83D\uDC64", fontSize = (size.value * 0.28f).sp) }
                FrameOverlay(dyn, size)
            }
            "badge" -> key(dyn.updatedAt) { VipMedia(dyn, Modifier.size((size.value * 0.8f).dp), loop = true) }
            "banner" -> key(dyn.updatedAt) { VipMedia(dyn, Modifier.fillMaxWidth().height(size), loop = true, contentScale = ContentScale.Fit) }
            else -> key(dyn.updatedAt) { VipMedia(dyn, Modifier.fillMaxSize(), loop = true) }
        }
    }
}

private fun slotLabel(slot: String) = when (slot) {
    "frame" -> "Bingkai"
    "bubble" -> "Gelembung"
    "badge" -> "Lencana"
    "entry" -> "Efek masuk"
    "banner" -> "Banner masuk"
    "medal" -> "Medali"
    else -> "Gelar"
}

private fun fmtShort(n: Long): String {
    fun f(x: Double, suffix: String): String {
        val t = Math.round(x * 10) / 10.0
        return (if (t == Math.floor(t)) t.toLong().toString() else t.toString()) + suffix
    }
    return when {
        n >= 1_000_000_000L -> f(n / 1e9, "B")
        n >= 1_000_000L -> f(n / 1e6, "M")
        n >= 1_000L -> f(n / 1e3, "K")
        else -> n.toString()
    }
}

private val STORE_CATS = listOf(
    Triple("frame", "Bingkai avatar", "\u2B55"),
    Triple("entry", "Efek masuk", "\u2728"),
    Triple("banner", "Banner masuk", "\uD83D\uDEA9"),
    Triple("badge", "Lencana", "\uD83C\uDF96\uFE0F"),
    Triple("bubble", "Gelembung", "\uD83D\uDCAC"),
    Triple("medal", "Medali", "\uD83C\uDFC5"),
)

@Composable
fun StoreScreen(uid: String, wallet: Wallet, onOpenVip: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var cat by remember { mutableStateOf("frame") }
    var sel by remember { mutableStateOf<StoreItem?>(null) }
    var buying by remember { mutableStateOf<StoreItem?>(null) }
    val now = System.currentTimeMillis()
    LaunchedEffect(Unit) { StoreCatalog.start() }
    val shopItems = StoreCatalog.bySlot(cat).map { it.toStoreItem() } + STORE_ITEMS.filter { it.slot == cat }

    Box(
        Modifier.fillMaxSize().blockTouches()
            .background(Brush.verticalGradient(listOf(Color(0xFF22C1AE), Color(0xFF0E8A82)))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    "\u2039", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 8.dp),
                )
                Text("Store", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                Text(
                    if (tab == 0) "\uD83C\uDF92 Saya" else "\uD83D\uDECD\uFE0F Store",
                    color = Color(0xFFFFE08A), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(50))
                        .background(Color(0x55000000)).border(1.dp, Color(0xFFE8B923), RoundedCornerShape(50))
                        .clickable { tab = if (tab == 0) 1 else 0 }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            if (tab == 0) {
                Row(Modifier.weight(1f)) {
                    Column(
                        Modifier.width(96.dp).fillMaxHeight().background(Color(0x3300574F)).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        STORE_CATS.forEach { (k, label, emoji) ->
                            val on = cat == k
                            Column(
                                Modifier.fillMaxWidth().background(if (on) Color(0xFFFFC107) else Color.Transparent)
                                    .clickable { cat = k; sel = null }.padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF0B5D63)).border(2.dp, Color(0xFFE8B923), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Text(emoji, fontSize = 26.sp) }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    label, color = if (on) Color(0xFF4A3300) else Color.White, fontSize = 13.sp,
                                    textAlign = TextAlign.Center, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))))
                                .border(1.5.dp, Color(0xFFE8B923), RoundedCornerShape(14.dp))
                                .clickable(onClick = onOpenVip).padding(12.dp),
                        ) {
                            Column {
                                Text("\uD83D\uDC51 VIP1 \u2013 VIP9", color = Color(0xFFFFD54F), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Gelar, bingkai, gelembung, efek masuk & banner masuk eksklusif", color = Color.White, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (shopItems.isEmpty()) {
                            Text("Belum ada item di kategori ini.", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                        }
                        shopItems.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { item ->
                                    val chosen = sel?.id == item.id
                                    Column(
                                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0C7F7B))
                                            .border(if (chosen) 3.dp else 2.dp, if (chosen) Color(0xFFFFC107) else Color(0xFF39D3C3), RoundedCornerShape(16.dp))
                                            .clickable { sel = item },
                                    ) {
                                        Box(Modifier.fillMaxWidth().height(150.dp)) {
                                            Text(
                                                "${item.days} hari", color = Color.White, fontSize = 15.sp,
                                                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                                            )
                                            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) { ItemPreview(item, 110.dp) }
                                        }
                                        Box(
                                            Modifier.fillMaxWidth().background(if (chosen) Color(0xFFFFC107) else Color(0xFF1FC8B5)).padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "\uD83E\uDE99 ${fmtShort(item.price)}",
                                                color = if (chosen) Color(0xFF3B2A00) else Color.White,
                                                fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(Color(0x66000000)).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("\uD83E\uDE99 ${fmtCompact(wallet.coins)}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    val cur = sel
                    val owned = cur != null && (wallet.inv[cur.id] ?: 0L) > now
                    Text(
                        if (owned) "Perpanjang" else "Membeli", color = Color(0xFF3B2A00), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFD54F))
                            .clickable {
                                if (cur == null) Toast.makeText(context, "Pilih item dulu", Toast.LENGTH_SHORT).show()
                                else buying = cur
                            }
                            .padding(horizontal = 34.dp, vertical = 12.dp),
                    )
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).navigationBarsPadding()) {
                    val mine = wallet.inv.filter { it.value > now }.mapNotNull { (id, exp) -> findItem(id)?.let { it to exp } }
                        .sortedWith(compareBy<Pair<StoreItem, Long>>({ it.first.slot }, { it.first.id }))
                    if (mine.isEmpty()) {
                        Spacer(Modifier.height(60.dp))
                        Text("Belum ada item.\nBeli di Store atau beli VIP untuk mendapatkan item.", color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    mine.forEach { (item, exp) ->
                        val used = if (item.slot == "badge") item.id in activeMedalIds(wallet) else wallet.equip[item.slot] == item.id
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x55000000)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ItemPreview(item)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("${slotLabel(item.slot)} \u00B7 sisa ${daysLeft(exp)} hari", color = Color(0xFFD0F5EF), fontSize = 12.sp)
                                if (item.vip > 0) Text("Dari VIP${item.vip}", color = vipColor(item.vip), fontSize = 12.sp)
                            }
                            if (item.slot == "medal") Text("Atur di Lencana glory", color = Color(0xFFD0F5EF), fontSize = 12.sp)
                            else if (item.slot == "title") Text("Otomatis", color = Color(0xFFD0F5EF), fontSize = 13.sp)
                            else GoldPill(if (used) "Lepas" else "Pakai") {
                                AppScope.scope.launch {
                                    val err = LvRepo.equip(uid, item.slot, if (used) "" else item.id)
                                    if (err == null) PublicSync.push(uid)
                                    Toast.makeText(context, err ?: if (used) "Dilepas" else "Dipakai", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    buying?.let { item ->
        AlertDialog(
            onDismissRequest = { buying = null },
            title = { Text(item.name) },
            text = { Text("Beli seharga ${fmtNum(item.price)} koin untuk ${item.days} hari? Item masuk ke My Item.") },
            confirmButton = {
                TextButton(onClick = {
                    buying = null
                    AppScope.scope.launch {
                        val err = LvRepo.buyItem(uid, item)
                        Toast.makeText(context, err ?: "Berhasil! Cek di Saya", Toast.LENGTH_SHORT).show()
                        if (err == null) PublicSync.push(uid)
                    }
                }) { Text("Beli") }
            },
            dismissButton = { TextButton(onClick = { buying = null }) { Text("Batal") } },
        )
    }
}

/** Salin exp, VIP & item ke userIds/{id}. Hemat kuota: cache id dokumen & lewati kalau tidak berubah. */
object PublicSync {
    private val docIds = HashMap<String, String>()
    private val lastKey = HashMap<String, String>()

    /** Kuota Firestore habis: dorong frame/lencana ke RTDB dari wallet cache / server cadangan,
     *  supaya profil publik (room, kursi) tetap lengkap dan tidak kosong. */
    private suspend fun pushFromCache(uid: String) {
        try {
            val w = WalletCache.load(uid) ?: BackupRepo.base(uid)?.toWallet() ?: return
            val frame = activeEquip(w, "frame")
            val badge = activeEquip(w, "badge")
            // PROFIL UTAMA DI RTDB: nama/ID/bio/negara/gender/foto ikut disinkronkan
            // supaya profil tampil instan tanpa menunggu Firestore.
            val me = UserCache.meLocal(uid)
            if (me == null && frame.isBlank() && badge.isBlank() && w.vip == 0) return // jangan timpa dengan data kosong
            Rtdb.ref("pub/$uid").updateChildren(
                hashMapOf<String, Any?>(
                    "exp" to w.exp, "vip" to w.vip.toLong(), "vipUntil" to w.vipUntil,
                    "frame" to frame, "bubble" to activeEquip(w, "bubble"), "badge" to badge,
                    // FIX lencana/family hilang saat kuota habis: medals, fam, ad, svip ikut
                    // tersimpan di RTDB pub supaya profil tetap tampil lengkap.
                    "medals" to activeMedalIds(w).joinToString(","),
                    "fam" to (UserCache.peek(uid)?.fam ?: ""),
                    "ad" to SvipLocal.ad(uid),
                    "svip" to SvipLocal.csv(uid),
                    // FIX avatar: foto ikut ke RTDB pub supaya chat room tetap tampil saat kuota habis.
                    "av" to (me?.avatarB64 ?: ""),
                    // PROFIL UTAMA DI RTDB: data identitas ikut ke pub.
                    "name" to (me?.name ?: ""),
                    "id" to officialId(uid, me?.userId),
                    "cc" to (me?.countryCode ?: "ID"),
                    "bio" to (me?.bio ?: ""),
                    "gender" to (me?.gender ?: ""),
                    "photo" to (me?.photoUrl ?: ""),
                ),
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }

    /** ID RESMI akun (cache ensureUserId). Tidak pernah memakai kode room / hash email. */
    private fun officialId(uid: String, fallback: String?): String {
        val ctx = try { com.google.firebase.FirebaseApp.getInstance().applicationContext } catch (e: Exception) { null }
        val official = ctx?.let { UserDirectory.cachedId(it, uid) }
        return official ?: (fallback ?: "")
    }

    suspend fun push(uid: String) {
        if (QuotaGuard.isDown()) { pushFromCache(uid); return }
        try {
            val db = FirebaseFirestore.getInstance()
            // Dompet kini di Supabase (cache dulu, lalu server); Firestore tidak dibaca lagi.
            val w = WalletCache.load(uid) ?: SupaRepo.get(uid).wallet
            if (w == null) { pushFromCache(uid); return }
            val me = UserCache.meLocal(uid)
            // SVIP dari RTDB: segarkan cache lokal dari Realtime Database dulu
            // (sumber utama panel SVIP) supaya sinkronisasi tidak menimpa
            // toggle/iklan terbaru dengan nilai lama dari Firestore.
            SvipRepo.refreshLocal(uid)
            val svipCsv = SvipLocal.csv(uid)
            val ad = SvipLocal.ad(uid)
            // FIX family: isi ulang label family dari data family asli setiap sinkronisasi,
            // supaya owner/anggota lama juga tampil lencana & "family saya"-nya.
            var famName = ""
            try {
                val fid = db.collection("familyMembers").document(uid).get().await().getString("familyId") ?: ""
                if (fid.isNotBlank()) {
                    famName = db.collection("families").document(fid).get().await().getString("name") ?: ""
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
            val data = hashMapOf<String, Any?>(
                "uid" to uid,
                "exp" to w.exp,
                "vip" to w.vip.toLong(),
                "vipUntil" to w.vipUntil,
                "frame" to activeEquip(w, "frame"),
                "bubble" to activeEquip(w, "bubble"),
                "badge" to activeEquip(w, "badge"),
                "medals" to activeMedalIds(w).joinToString(","),
                "svip" to svipCsv,
                "ad" to ad,
                "fam" to famName,
                // kalau profil (nama/bio) berubah, pub RTDB harus ikut diperbarui
                "name" to (me?.name ?: ""),
                "bio" to (me?.bio ?: ""),
            )
            val key = data.toString()
            if (lastKey[uid] == key) return
            try {
                Rtdb.ref("pub/$uid").setValue(
                    hashMapOf<String, Any?>(
                        "exp" to w.exp, "vip" to w.vip.toLong(), "vipUntil" to w.vipUntil,
                        "frame" to activeEquip(w, "frame"), "bubble" to activeEquip(w, "bubble"),
                        "badge" to activeEquip(w, "badge"),
                        "svip" to svipCsv,
                        // FIX lencana: medals + fam + ad ikut ke RTDB pub.
                        "medals" to activeMedalIds(w).joinToString(","),
                        "fam" to famName,
                        "ad" to ad,
                        "av" to (me?.avatarB64 ?: ""),
                        // PROFIL UTAMA DI RTDB: data identitas ikut ke pub
                        // supaya pembacaan profil tidak bergantung Firestore.
                        "name" to (me?.name ?: ""),
                        "id" to officialId(uid, me?.userId),
                        "cc" to (me?.countryCode ?: "ID"),
                        "bio" to (me?.bio ?: ""),
                        "gender" to (me?.gender ?: ""),
                        "photo" to (me?.photoUrl ?: ""),
                    ),
                ).await()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
            var id = docIds[uid]
            if (id == null) {
                id = db.collection("userIds").whereEqualTo("uid", uid).limit(1).get().await()
                    .documents.firstOrNull()?.id ?: return
                docIds[uid] = id
            }
            db.collection("userIds").document(id).set(data, SetOptions.merge()).await()
            lastKey[uid] = key
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (QuotaGuard.isExhausted(e)) { QuotaGuard.markDown(); pushFromCache(uid) }
        }
    }
}
