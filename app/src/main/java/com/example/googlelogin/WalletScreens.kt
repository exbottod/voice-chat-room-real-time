package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// QR DANA milik admin (hasil baca QR terima uang). Tombol di dialog topup
// membuka tautan ini supaya aplikasi DANA langsung terbuka.
const val DANA_LINK = "https://link.dana.id/minta?full_url=https://qr.dana.id/v1/281012012023020637707267"
const val DANA_QR_IMG = "https://exbottod.github.io/voice-chat-room-real-time/dana-qr.png"

private fun openDana(ctx: android.content.Context) {
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(DANA_LINK))
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "Aplikasi DANA tidak bisa dibuka", android.widget.Toast.LENGTH_SHORT).show()
    }
}

const val DIAMOND_PER_COIN = 2L // 2 diamond = 1 coin
const val INVITE_REWARD_COINS = 10_000_000L
const val TOPUP_TEST_MODE = false // false = coin dibeli lewat payment gateway (Midtrans)

data class Wallet(
    val coins: Long = 0L,
    val diamonds: Long = 0L,
    val exp: Long = 0L,
    val vip: Int = 0,
    val vipUntil: Long = 0L,
    val inv: Map<String, Long> = emptyMap(),
    val equip: Map<String, String> = emptyMap(),
    val lvClaimed: Long = 0L,
)

data class Gift(
    val id: String,
    val name: String,
    val emoji: String,
    val price: Long,
    val mediaUrl: String = "",
    val mediaType: String = "",
    val special: Boolean = false,
    val jpRate: Int = 0,
    val jpMult: Long = 0L,
    val cbRate: Int = 0,
    /** Nilai dasar hitung cashback (kalau 0, pakai harga gift). */
    val cbPrice: Long = 0L,
    /** Gift khusus cashback: tidak tampil di tab Hadiah, hanya di tab Cashback. */
    val cashback: Boolean = false,
    /** Gift roket: efek terbang melintasi layar + suara, terlihat semua orang di room. */
    val rocket: Boolean = false,
)

val GIFTS = listOf(
    Gift("rose", "Mawar", "🌹", 10),
    Gift("heart", "Hati", "❤", 50),
    Gift("coffee", "Kopi", "☕", 100),
    Gift("lucky", "Lucky bag", "💰", 500),
    Gift("bear", "Bear hug", "🧸", 1200),
    Gift("crown", "King crown", "👑", 4800),
    Gift("raksha", "Raksha Bandhan", "🪔", 4800),
    Gift("ring", "Cincin", "💍", 20000),
    Gift("car", "Mobil sport", "🚗", 99000),
    Gift("plane", "Pesawat", "✈", 250000),
    Gift("dussehra", "Dussehra", "🏹", 900000),
    Gift("dragon", "Naga emas", "🐉", 1000000),
    Gift("hdussehra", "Happy Dussehra", "🔱", 1800000),
    Gift("flag", "Cium bendera", "💋", 1800000),
    Gift("ferris", "Sweet Ferris Wheel", "🎡", 2100000),
    Gift("castle", "Istana", "🏰", 5000000),
)

/** Salinan dompet terakhir di perangkat (SharedPreferences). */
object WalletCache {
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: android.content.Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences("wallet_cache", android.content.Context.MODE_PRIVATE)
        }
    }

    fun save(uid: String, w: Wallet) {
        try {
            val o = org.json.JSONObject()
                .put("coins", w.coins)
                .put("diamonds", w.diamonds)
                .put("exp", w.exp)
                .put("vip", w.vip)
                .put("vipUntil", w.vipUntil)
                .put("lvClaimed", w.lvClaimed)
                .put("inv", org.json.JSONObject(w.inv as Map<*, *>))
                .put("equip", org.json.JSONObject(w.equip as Map<*, *>))
            prefs?.edit()?.putString(uid, o.toString())?.apply()
        } catch (e: Exception) {
        }
    }

    fun load(uid: String): Wallet? = try {
        val o = org.json.JSONObject(prefs?.getString(uid, null) ?: return null)
        val inv = o.optJSONObject("inv")
            ?.let { j -> j.keys().asSequence().associateWith { j.getLong(it) } }
            ?: emptyMap()
        val eq = o.optJSONObject("equip")
            ?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
            ?: emptyMap()
        Wallet(
            coins = o.optLong("coins"),
            diamonds = o.optLong("diamonds"),
            exp = o.optLong("exp"),
            vip = o.optInt("vip"),
            vipUntil = o.optLong("vipUntil"),
            inv = inv,
            equip = eq,
            lvClaimed = o.optLong("lvClaimed"),
        )
    } catch (e: Exception) {
        null
    }
}

object WalletRepo {
    private val backupCbs = java.util.concurrent.CopyOnWriteArrayList<(String, Wallet) -> Unit>()
    private fun db() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db().collection("wallets").document(uid)

    /** Pemanggil sudah tahu dompet terbaru (misal hasil transaksi): perbarui cache + tampilan. */
    fun emitBackup(uid: String, w: Wallet) {
        try { WalletCache.save(uid, w) } catch (e: Exception) { }
        // Item SVIP dari Realtime Database digabung untuk tampilan/logika.
        val merged = SvipInv.apply(uid, w)
        backupCbs.forEach { try { it(uid, merged) } catch (e: Exception) { } }
    }

    fun emit(uid: String, w: Wallet) = emitBackup(uid, w)

    /** Baca dompet dari Firestore SEKALI (untuk migrasi awal ke Supabase). */
    private suspend fun readLegacy(uid: String): Wallet? = try {
        kotlinx.coroutines.withTimeoutOrNull(6000L) { ref(uid).get().await() }?.let { if (it.exists()) parseWallet(it) else null }
    } catch (e: Exception) { null }

    /**
     * Pantau dompet lewat Supabase. Cache lokal tampil lebih dulu supaya
     * lencana/frame/VIP muncul instan saat app dibuka, lalu saldo terbaru
     * menyusul (dan otomatis migrasi dari Firestore kalau belum ada).
     * Firestore tidak lagi dibaca terus-menerus: kuota hemat total.
     */
    fun observe(uid: String, onChange: (Wallet) -> Unit): ListenerRegistration {
        SvipInv.observe(uid)
        WalletCache.load(uid)?.let { onChange(SvipInv.apply(uid, it)) }
        val cb: (String, Wallet) -> Unit = { u, w -> if (u == uid) onChange(w) }
        backupCbs.add(cb)
        val job = kotlinx.coroutines.GlobalScope.launch {
            while (true) {
                var res = SupaRepo.get(uid)
                if (res.needMigrate) {
                    // Pertama kali di Supabase: pindahkan saldo lama dari Firestore.
                    val legacy = readLegacy(uid) ?: Wallet()
                    res = SupaRepo.migrate(uid, legacy)
                }
                res.wallet?.let { emitBackup(uid, it) }
                kotlinx.coroutines.delay(15_000L)
            }
        }
        return object : ListenerRegistration {
            override fun remove() {
                job.cancel()
                backupCbs.remove(cb)
            }
        }
    }

    /** Isi coin (mode uji gratis; saat pembayaran nyata, ini jadi hasil pembayaran). */
    suspend fun topUp(uid: String, coins: Long): String? {
        val r = SupaRepo.topUp(uid, coins)
        r.wallet?.let { emitBackup(uid, it) }
        return r.error
    }

    /** Kirim gift: coin berkurang, diamond pengirim bertambah sebanyak harga gift. */
    suspend fun sendGift(
        uid: String,
        price: Long,
        itemId: String? = null,
        roomId: String? = null,
        to: List<String> = emptyList(),
        each: Long = 0L,
    ): String? {
        val r = SupaRepo.sendGift(uid, price, itemId, roomId, to, each)
        r.wallet?.let { emitBackup(uid, it) }
        return r.error
    }

    /** Tukar diamond ke coin. */
    suspend fun exchange(uid: String, diamonds: Long): String? {
        val r = SupaRepo.exchange(uid, diamonds)
        r.wallet?.let { emitBackup(uid, it) }
        return r.error
    }
}

@Composable
private fun BalanceCard(emoji: String, label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(CardBlack.copy(alpha = 0.9f))
            .border(1.dp, Gold.copy(alpha = 0.4f), shape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 30.sp)
        Text(value, color = GoldLight, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = Color(0xFFBDBDBD), fontSize = 13.sp)
    }
}

// ---- Warna tema dompet (turquoise, kartu emas / ungu) ----
private val WalTealTop = Color(0xFF6FEFE0)
private val WalTealMid = Color(0xFFBFF6EE)
private val WalNavy = Color(0xFF1B1049)
private val WalGrey = Color(0xFF8A8FA0)
private val WalRowBg = Color(0xFFF4F4F6)
private val WalCoinOrange = Color(0xFFD98A00)

/** Paket tukar berlian ke coin: (coin yang didapat, berlian yang dibayar). 1 coin = 2 berlian. */
private val DIAMOND_PACKS = listOf(
    162_000L to 324_000L,
    1_004_400L to 2_008_800L,
    2_126_250L to 4_252_500L,
    10_368_000L to 20_736_000L,
    21_937_500L to 43_875_000L,
)

/** Paket topup coin: jumlah coin dan label harga. */
private val TOPUP_PACKS = listOf(
    1_000_000L to "Rp 6.000",
    3_000_000L to "Rp 18.000",
    10_000_000L to "Rp 58.000",
    30_000_000L to "Rp 170.000",
)

@Composable
private fun CoinDot(size: Int = 28) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFFE27A), Color(0xFFF2A100))))
            .border(1.5.dp, Color(0xFFFFF1B0), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("★", color = Color(0xFFFFF6D0), fontSize = (size * 0.5f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GemDot(size: Int = 22) {
    Text("💎", fontSize = size.sp)
}

@Composable
private fun WalletBalanceCard(
    gem: Boolean,
    balance: Long,
    action: String,
    onAction: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val bg = if (gem) {
        Brush.linearGradient(listOf(Color(0xFF6A2BD0), Color(0xFFB98BF2), Color(0xFF7A3FDB)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFF1A21A), Color(0xFFFFD84A), Color(0xFFEFA11B)))
    }
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(shape)
            .background(bg)
            .border(2.dp, if (gem) Color(0xFFD7BFFF) else Color(0xFFFFE08A), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (gem) GemDot(24) else CoinDot(26)
                Spacer(Modifier.width(8.dp))
                Text(tr("Saldo") + ":", color = Color.White, fontSize = 17.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                fmtNum(balance),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 28.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.verticalGradient(listOf(Color(0xFFFFE27A), Color(0xFFF0A800))))
                .border(1.dp, Color(0xFFB57A00), RoundedCornerShape(50))
                .clickable(onClick = onAction)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(action, color = Color(0xFF4A2E00), fontSize = 14.sp)
        }
    }
}

@Composable
private fun WalletTabs(tab: Int, onTab: (Int) -> Unit) {
    val names = listOf(tr("Koin"), tr("Berlian"), "Cash")
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        names.forEachIndexed { i, n ->
            val on = tab == i
            Column(
                Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onTab(i) }.padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    n,
                    color = if (on) WalNavy else WalGrey,
                    fontSize = 20.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier.width(34.dp).height(4.dp).clip(RoundedCornerShape(50))
                        .background(if (on) WalNavy else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun TopupMethodRow(
    title: String,
    sub: String = "",
    badge: String = "",
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WalRowBg)
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(14.dp))
            Text(title, color = WalNavy, fontSize = 17.sp, fontWeight = if (sub.isNotEmpty()) FontWeight.ExtraBold else FontWeight.Normal)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(sub, color = WalGrey, fontSize = 15.sp)
            }
        }
        if (badge.isNotEmpty()) {
            Text(
                badge,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(topEnd = 16.dp, bottomStart = 16.dp))
                    .background(Color(0xFFFF6B7A))
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MethodChip(label: String, iconRes: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (active) WalNavy else Color.White)
            .border(1.dp, if (active) WalNavy else Color(0xFFD9D9E3), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (active) Color.White else WalNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RezoMark(size: Int = 52) {
    Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_logo),
        contentDescription = "REZO",
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun PayLogo(resId: Int) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = androidx.compose.ui.res.painterResource(resId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PayDot(text: String, color: Color) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun WalletScreen(uid: String, wallet: Wallet, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showPacks by remember { mutableStateOf(false) }
    var payOrder by remember { mutableStateOf<String?>(null) }
    var manualCoins by remember { mutableStateOf<Long?>(null) }
    var manualPayer by remember { mutableStateOf("") }
    var payDana by remember { mutableStateOf("") }
    var payName by remember { mutableStateOf("") }
    var payGopay by remember { mutableStateOf("") }
    var payQris by remember { mutableStateOf("") }
    var payMethod by remember { mutableStateOf("dana") }
    var payUrl by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = { if (showPacks) showPacks = false else onBack() })
    val toast = { m: String -> Toast.makeText(context, m, Toast.LENGTH_SHORT).show() }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(WalTealTop, WalTealMid, Color.White), endY = 900f)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---- bar judul ----
            Box(Modifier.fillMaxWidth().height(54.dp)) {
                Text(
                    "‹",
                    color = WalNavy,
                    fontSize = 40.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { if (showPacks) showPacks = false else onBack() },
                )
                Text(
                    tr("Dompet"),
                    color = WalNavy,
                    fontSize = 21.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            WalletTabs(tab) { tab = it; showPacks = false }
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                when (tab) {
                    // ===================== KOIN =====================
                    0 -> {
                        WalletBalanceCard(gem = false, balance = wallet.coins, action = tr("Catatan topup")) {
                            toast("Belum ada catatan topup")
                        }
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF3B2504), Color(0xFF8A5A0B), Color(0xFF3B2504)),
                                    ),
                                )
                                .border(2.dp, Color(0xFFE2B33C), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Manfaat Top Up",
                                color = Color(0xFFFFD45A),
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr("Metode topup"),
                                color = WalNavy,
                                fontSize = 20.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                                    .border(1.dp, Color(0xFFD9D9E3), RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("📍", fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("🇮🇩", fontSize = 18.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        if (!showPacks) {
                            TopupMethodRow(
                                title = "REZO CHAT VOICE",
                                sub = "Agen Resmi",
                                badge = "Penawaran terbaik",
                                icon = { RezoMark() },
                            ) { showPacks = true }
                            TopupMethodRow("Google Play", icon = { PayDot("GP", Color(0xFF34A853)) }) { showPacks = true }
                            TopupMethodRow("DANA", icon = { PayLogo(R.drawable.logo_dana) }) { showPacks = true }
                            TopupMethodRow("GoPay", icon = { PayLogo(R.drawable.logo_gopay) }) { showPacks = true }
                            TopupMethodRow("OVO", icon = { PayLogo(R.drawable.logo_ovo) }) { showPacks = true }
                            TopupMethodRow("ShopeePay", icon = { PayDot("SPay", Color(0xFFEE4D2D)) }) { showPacks = true }
                            TopupMethodRow("LINKAJA", icon = { PayLogo(R.drawable.logo_linkaja) }) { showPacks = true }
                            TopupMethodRow("QRIS", icon = { PayLogo(R.drawable.logo_qris) }) { showPacks = true }
                            TopupMethodRow("PayPal", icon = { PayLogo(R.drawable.logo_paypal) }) {
                                toast("PayPal belum tersedia untuk topup")
                            }
                        } else {
                            Text(
                                if (TOPUP_TEST_MODE) "Mode uji: coin ditambahkan gratis (pembayaran belum terhubung)."
                                else "Pilih paket coin",
                                color = WalGrey,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            TOPUP_PACKS.forEach { (coins, price) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                        .clip(RoundedCornerShape(16.dp)).background(WalRowBg)
                                        .clickable(enabled = !busy && payOrder == null) {
                                            if (TOPUP_TEST_MODE) {
                                                busy = true
                                                scope.launch {
                                                    val e = WalletRepo.topUp(uid, coins)
                                                    busy = false
                                                    toast(e ?: "+${fmtNum(coins)} coin")
                                                }
                                            } else {
                                                busy = true
                                                scope.launch {
                                                    val info = SupaRepo.payInfo()
                                                    if (info != null && (info.dana.isNotBlank() || info.gopay.isNotBlank() || info.qris.isNotBlank())) {
                                                        busy = false
                                                        payDana = info.dana
                                                        payGopay = info.gopay
                                                        payQris = info.qris
                                                        payName = info.name
                                                        payMethod = when {
                                                            info.dana.isNotBlank() -> "dana"
                                                            info.gopay.isNotBlank() -> "gopay"
                                                            else -> "qris"
                                                        }
                                                        manualPayer = ""
                                                        manualCoins = coins
                                                        return@launch
                                                    }
                                                    val start = SupaRepo.topupCreate(coins)
                                                    busy = false
                                                    if (start.url == null) {
                                                        toast(start.error ?: "Gagal memulai pembayaran")
                                                    } else {
                                                        payOrder = start.order
                                                        payUrl = start.url
                                                        try {
                                                            context.startActivity(
                                                                android.content.Intent(
                                                                    android.content.Intent.ACTION_VIEW,
                                                                    android.net.Uri.parse(start.url),
                                                                ),
                                                            )
                                                        } catch (e: Exception) {
                                                            toast("Tidak bisa membuka halaman pembayaran")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CoinDot(30)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        coins.toString(),
                                        color = WalCoinOrange,
                                        fontSize = 20.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        price,
                                        color = Color(0xFF6B3A00),
                                        fontSize = 15.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(50))
                                            .background(Color(0xFFDADADF))
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }

                    // ===================== BERLIAN =====================
                    1 -> {
                        WalletBalanceCard(gem = true, balance = wallet.diamonds, action = tr("Catatan Berlian")) {
                            toast("Belum ada catatan berlian")
                        }
                        Spacer(Modifier.height(18.dp))
                        DIAMOND_PACKS.forEach { (coins, gems) ->
                            val enough = wallet.diamonds >= gems
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)
                                    .clickable(enabled = !busy) {
                                        if (!enough) {
                                            toast("Berlian tidak cukup")
                                        } else {
                                            busy = true
                                            scope.launch {
                                                val e = WalletRepo.exchange(uid, gems)
                                                busy = false
                                                toast(e ?: "Berhasil menukar ${fmtNum(gems)} berlian")
                                            }
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoinDot(34)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    coins.toString(),
                                    color = WalCoinOrange,
                                    fontSize = 21.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(if (enough) Color(0xFFE3D6F7) else Color(0xFFDADADF))
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GemDot(20)
                                    Spacer(Modifier.width(6.dp))
                                    Text(gems.toString(), color = Color(0xFF6B3A00), fontSize = 16.sp)
                                }
                            }
                        }
                    }

                    // ===================== CASH =====================
                    else -> {
                        Spacer(Modifier.height(80.dp))
                        Text(
                            "Fitur Cash belum tersedia",
                            color = WalGrey,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }

        // ---- topup manual (transfer DANA) ----
        val mCoins = manualCoins
        if (mCoins != null) {
            val harga = TOPUP_PACKS.firstOrNull { it.first == mCoins }?.second ?: ""
            AlertDialog(
                onDismissRequest = { manualCoins = null },
                title = { Text("Topup ${fmtNum(mCoins)} coin") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Topup $harga untuk ${fmtNum(mCoins)} coin", fontSize = 13.sp, color = WalGrey)
                        Spacer(Modifier.height(10.dp))
                        // pilihan metode pembayaran
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (payDana.isNotBlank()) {
                                MethodChip("DANA", R.drawable.logo_dana, payMethod == "dana") { payMethod = "dana" }
                            }
                            if (payGopay.isNotBlank()) {
                                MethodChip("GoPay", R.drawable.logo_gopay, payMethod == "gopay") { payMethod = "gopay" }
                            }
                            if (payQris.isNotBlank()) {
                                MethodChip("QRIS", R.drawable.logo_qris, payMethod == "qris") { payMethod = "qris" }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (payMethod) {
                            "gopay" -> {
                                Text("Transfer ke GoPay:")
                                Spacer(Modifier.height(6.dp))
                                Text(payGopay, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00A66A))
                            }
                            "qris" -> {
                                Text("Scan QRIS ini lalu bayar $harga:")
                                Spacer(Modifier.height(8.dp))
                                AsyncImage(
                                    model = payQris,
                                    contentDescription = "QRIS",
                                    modifier = Modifier.fillMaxWidth().height(220.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            else -> {
                                Text("Transfer ke DANA:")
                                Spacer(Modifier.height(6.dp))
                                Text(payDana, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF118EEA))
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Tekan tombol ini, aplikasi DANA terbuka sendiri, lalu masukkan nominal $harga.",
                                    fontSize = 12.sp,
                                    color = WalGrey,
                                )
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF118EEA))
                                        .clickable { openDana(context) }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Buka DANA", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(10.dp))
                                Text("Atau pindai QR ini dari HP lain:", fontSize = 12.sp, color = WalGrey)
                                Spacer(Modifier.height(6.dp))
                                AsyncImage(
                                    model = DANA_QR_IMG,
                                    contentDescription = "QR DANA",
                                    modifier = Modifier.fillMaxWidth().height(200.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                        if (payName.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("a.n $payName", fontSize = 14.sp, color = WalGrey)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Setelah transfer, isi nama pemilik DANA yang mengirim:", fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = manualPayer,
                            onValueChange = { manualPayer = it.take(40) },
                            singleLine = true,
                            placeholder = { Text("Nama pengirim") },
                            colors = goldFieldColors(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Coin masuk setelah pembayaran dicek admin.", fontSize = 12.sp, color = WalGrey)
                    }
                },
                confirmButton = {
                    TextButton(enabled = manualPayer.trim().length >= 3 && !busy, onClick = {
                        busy = true
                        scope.launch {
                            val r = SupaRepo.manualCreate(mCoins, manualPayer.trim(), payMethod)
                            busy = false
                            if (r.order != null) {
                                val metode = payMethod
                                manualCoins = null
                                manualPayer = ""
                                payUrl = if (metode == "dana") DANA_LINK else null
                                payOrder = r.order
                                toast("Pengajuan dikirim. Coin masuk otomatis setelah admin menyetujui.")
                            } else {
                                toast(r.error ?: "Gagal mengajukan topup")
                            }
                        }
                    }) { Text("Saya sudah transfer") }
                },
                dismissButton = {
                    TextButton(onClick = { manualCoins = null }) { Text("Batal") }
                },
            )
        }

        // ---- menunggu pembayaran ----
        val order = payOrder
        if (order != null) {
            LaunchedEffect(order) {
                var tries = 0
                while (tries < 90) {
                    kotlinx.coroutines.delay(4000)
                    tries++
                    val st = SupaRepo.topupStatus(order)
                    if (st == "paid") {
                        SupaRepo.get(uid).wallet?.let { WalletRepo.emitBackup(uid, it) }
                        payOrder = null
                        payUrl = null
                        toast("Pembayaran diterima, coin sudah ditambahkan")
                        return@LaunchedEffect
                    }
                    if (st == "expired" || st == "failed" || st == "rejected") {
                        payOrder = null
                        payUrl = null
                        toast(
                            when (st) {
                                "expired" -> "Waktu pembayaran habis"
                                "rejected" -> "Pengajuan ditolak admin"
                                else -> "Pembayaran gagal"
                            },
                        )
                        return@LaunchedEffect
                    }
                }
                payOrder = null
                payUrl = null
                toast("Pembayaran belum terdeteksi")
            }
            AlertDialog(
                onDismissRequest = { payOrder = null; payUrl = null },
                title = { Text("Selesaikan pembayaran") },
                text = {
                    Column {
                        Text(
                            if (payUrl == null) "Pengajuan sudah masuk. Coin otomatis masuk begitu admin menyetujui, layar ini menutup sendiri."
                            else "Halaman pembayaran sudah dibuka di browser. Bayar dengan DANA, OVO, ShopeePay, atau QRIS.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (payUrl == null) "Anda boleh menutup layar ini, coin tetap masuk." else "Coin masuk otomatis setelah pembayaran terkonfirmasi.",
                            fontSize = 12.sp,
                            color = WalGrey,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val u = payUrl
                        if (u != null) {
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(u),
                                    ),
                                )
                            } catch (e: Exception) {
                                toast("Tidak bisa membuka halaman pembayaran")
                            }
                        }
                    }) { Text("Buka lagi") }
                },
                dismissButton = {
                    TextButton(onClick = { payOrder = null; payUrl = null }) { Text("Tutup") }
                },
            )
        }
    }
}

@Composable
private fun GiftCell(g: Gift, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .padding(3.dp)
            .clip(shape)
            .border(if (selected) 2.dp else 0.dp, if (selected) Color(0xFF2BD9C4) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            if (g.mediaUrl.isNotBlank() && !isVideoMedia(g.mediaUrl, g.mediaType) && !isSvgaMedia(g.mediaUrl, g.mediaType)) {
                AsyncImage(
                    model = g.mediaUrl,
                    contentDescription = g.name,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(g.emoji, fontSize = 38.sp)
            }
            if (g.special) {
                // Bulatan khas di kanan atas: tanda gift kombo + bisa JP random.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF7A3B10))
                        .border(1.dp, Color(0xFFFFC94D), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚡", fontSize = 10.sp)
                }
            }
        }
        Text(g.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${g.price} 🪙", color = Color(0xFF8FA3A0), fontSize = 12.sp, maxLines = 1)
            if (g.cbRate > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "+${g.cbRate}% CB",
                    color = Color(0xFF2BD9C4),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Panel hadiah di dalam room: kirim ke semua user di mic (All) atau pilih user tertentu. */
@Composable
fun GiftPanel(
    roomId: String,
    myUid: String,
    myName: String,
    onDismiss: () -> Unit,
    seats: List<Seat> = emptyList(),
    initialTarget: String? = null,
    myAvatarB64: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current
    var wallet by remember { mutableStateOf(Wallet()) }
    var tab by remember { mutableStateOf(0) }
    // Jaring pengaman: pastikan katalog gift sudah dimuat saat panel dibuka.
    LaunchedEffect(Unit) { GiftCatalog.start() }
    val gifts = GiftCatalog.list
    // Tab Hadiah hanya untuk gift biasa; gift cashback punya tempat sendiri.
    val normalGifts = remember(gifts) { gifts.filter { !it.cashback && it.cbRate <= 0 } }
    val cbGifts = remember(gifts) { gifts.filter { it.cashback || it.cbRate > 0 } }
    var selected by remember { mutableStateOf(normalGifts.firstOrNull()?.id ?: "") }

    // Jaga selected tetap valid saat daftar gift berubah / masih kosong.
    LaunchedEffect(gifts, tab) {
        val now = if (tab == 2) cbGifts else normalGifts
        if (now.none { it.id == selected }) selected = now.firstOrNull()?.id ?: ""
    }
    var count by remember { mutableStateOf(1) }
    var countMenu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var allSelected by remember { mutableStateOf(initialTarget == null) }
    var picked by remember {
        mutableStateOf<Set<String>>(if (initialTarget != null) setOf(initialTarget) else emptySet())
    }
    val targets = remember(seats, myUid, myName, myAvatarB64) {
        val me = seats.firstOrNull { it.uid == myUid } ?: Seat(-1, myUid, myName, myAvatarB64, false)
        listOf(me) + seats.filter { it.uid.isNotBlank() && it.uid != myUid }.sortedBy { it.index }
    }
    val shown = if (tab == 2) cbGifts else normalGifts
    val pages = remember(shown) { shown.chunked(8) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val teal = Color(0xFF2BD9C4)

    DisposableEffect(myUid) {
        val r = WalletRepo.observe(myUid) { wallet = it }
        onDispose { r.remove() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF07161A))
                .blockTouches()
                .navigationBarsPadding()
                .padding(top = 14.dp, bottom = 10.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tr("Hadiah"), tr("Aktivitas"), tr("Cashback")).forEachIndexed { i, label ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (tab == i) teal else Color(0xFF16302F))
                            .clickable { tab = i }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            label,
                            color = if (tab == i) Color.Black else Color(0xFF8FA3A0),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (tab != 1) {
                // ---- Penerima: All + foto profil user di mic ----
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val allShape = RoundedCornerShape(50)
                    Box(
                        Modifier
                            .clip(allShape)
                            .background(if (allSelected) teal else Color(0xFF16302F))
                            .clickable {
                                allSelected = true
                                picked = emptySet()
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            tr("Semua"),
                            color = if (allSelected) Color.Black else Color(0xFF8FA3A0),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (targets.isEmpty()) {
                        Text(tr("Belum ada user di mic"), color = Color(0xFF8FA3A0), fontSize = 12.sp)
                    } else {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            targets.forEach { s ->
                                val on = (allSelected && s.index >= 0) || s.uid in picked
                                val bmp = remember(s.avatarB64) { ProfileRepo.decodeB64(s.avatarB64) }
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF16302F))
                                        .border(
                                            if (on) 2.dp else 1.dp,
                                            if (on) teal else Color(0xFF3A4A48),
                                            CircleShape,
                                        )
                                        .clickable {
                                            if (allSelected) {
                                                allSelected = false
                                                picked = setOf(s.uid)
                                            } else {
                                                val n = if (s.uid in picked) picked - s.uid else picked + s.uid
                                                if (n.isEmpty()) {
                                                    allSelected = true
                                                    picked = emptySet()
                                                } else {
                                                    picked = n
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Text(
                                            s.name.take(1).uppercase(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (pages.isEmpty()) {
                    // Daftar gift kosong / belum termuat: jangan crash.
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (tab == 2) tr("Belum ada gift cashback") else "Belum ada gift",
                            color = Color(0xFF8FA3A0),
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(240.dp)) { p ->
                        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                            pages[p].chunked(4).forEach { row ->
                                Row(Modifier.fillMaxWidth().weight(1f)) {
                                    row.forEach { g ->
                                        GiftCell(g, selected == g.id, Modifier.weight(1f)) { selected = g.id }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        repeat(pages.size) { i ->
                            Box(
                                Modifier
                                    .padding(3.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (pager.currentPage == i) teal else Color(0xFF3A4A48)),
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text(tr("Segera hadir"), color = Color(0xFF8FA3A0))
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🪙", fontSize = 22.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    fmtCompact(wallet.coins),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("Topup") + " ◆",
                    color = teal,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        onDismiss()
                        actions.openWallet()
                    },
                )
                Spacer(Modifier.weight(1f))
                val pill = RoundedCornerShape(50)
                Row(
                    Modifier.clip(pill).border(1.5.dp, teal, pill),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Row(
                            Modifier.clickable { countMenu = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("$count", color = Color.White, fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("▲", color = Color(0xFFBDBDBD), fontSize = 11.sp)
                        }
                        DropdownMenu(expanded = countMenu, onDismissRequest = { countMenu = false }) {
                            listOf(1, 10, 66, 188, 520).forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("$c") },
                                    onClick = {
                                        count = c
                                        countMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .background(if (sending) Color(0xFF3A4A48) else teal)
                            .clickable(enabled = !sending) {
                                val receivers = targets.filter {
                                    if (allSelected) it.index >= 0 else it.uid in picked
                                }
                                if (receivers.isEmpty()) {
                                    Toast.makeText(context, tr("Pilih penerima dulu"), Toast.LENGTH_SHORT).show()
                                    return@clickable
                                }
                                val g = gifts.firstOrNull { it.id == selected } ?: return@clickable
                                val perReceiver = g.price * count
                                val total = perReceiver * receivers.size
                                val toText = if (allSelected) {
                                    "semua mic (${receivers.size})"
                                } else {
                                    receivers.joinToString(", ") { it.name }
                                }
                                sending = true
                                AppScope.scope.launch {
                                    var err: String? = null
                                    try {
                                        err = WalletRepo.sendGift(
                                            myUid,
                                            total,
                                            g.id,
                                            roomId,
                                            receivers.map { it.uid },
                                            perReceiver,
                                        )
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        err = "Gagal memproses gift, coba lagi"
                                    }
                                    if (err != null) {
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                        sending = false
                                        return@launch
                                    }
                                    // FIX: toast sukses tampil LANGSUNG setelah dompet terpotong,
                                    // jangan menunggu ranking/pesan/fx yang bisa lambat atau gagal.
                                    Toast.makeText(
                                        context,
                                        "Gift terkirim! +${fmtNum(total)} diamond",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    try { RoomExtras.sendGiftMessage(roomId, myName, g, count, toText) } catch (e: Exception) { }
                                    var jp = 0L
                                    var cb = 0L
                                    try {
                                        val firstSeat = receivers.firstOrNull()
                                        val (j, c) = GiftFx.sent(
                                            roomId, myUid, myName, g, count, receivers.size,
                                            firstSeat?.uid ?: "", firstSeat?.name ?: "", firstSeat?.index ?: -1,
                                            receivers.map { Triple(it.uid, it.name, it.index) },
                                        )
                                        jp = j; cb = c
                                    } catch (e: Exception) { }
                                    if (jp > 0) {
                                        Toast.makeText(
                                            context,
                                            "🎉 JP!! +${fmtNum(jp)} koin masuk dompet!",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    if (cb > 0) {
                                        Toast.makeText(
                                            context,
                                            "💵 Cashback +${fmtNum(cb)} koin!",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    // LANGKAH PENTING DULU (RTDB, cepat): poin mic + ranking.
                                    // FIX: dulu PublicSync (Firestore, bisa macet kuota) menggantung di sini
                                    // dan poin mic / ranking tidak pernah dicatat.
                                    val inc = g.price * count
                                    receivers.filter { it.index >= 0 }.forEach { sRec ->
                                        try {
                                            Rtdb.ref("micPoints/" + roomId + "/" + sRec.index)
                                                .setValue(ServerValue.increment(inc))
                                        } catch (e: Exception) { }
                                    }
                                    var rerr: String? = null
                                    try {
                                        kotlinx.coroutines.withTimeoutOrNull(8000L) {
                                            rerr = GiftRankRepo.record(myUid, myName, roomId, perReceiver, receivers)
                                        } ?: run { rerr = "ranking lambat" }
                                    } catch (e: Exception) { rerr = "gagal mencatat ranking" }
                                    if (rerr != null) {
                                        Toast.makeText(context, "Ranking gagal: $rerr", Toast.LENGTH_LONG).show()
                                    }
                                    // EXP FAMILY dari gift: nilai gift menambah exp family pengirim.
                                    AppScope.scope.launch {
                                        try {
                                            kotlinx.coroutines.withTimeoutOrNull(8000L) { FamilyRepo.addGiftExp(myUid, total) }
                                        } catch (e: Exception) { }
                                    }
                                    // Sinkron profil (Firestore, bisa lambat saat kuota tipis): di belakang,
                                    // dengan batas waktu, tidak boleh menghalangi apa pun.
                                    AppScope.scope.launch {
                                        try {
                                            kotlinx.coroutines.withTimeoutOrNull(4000L) { PublicSync.push(myUid) }
                                        } catch (e: Exception) { }
                                    }
                                    sending = false
                                }
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text(tr("Berikan"), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
