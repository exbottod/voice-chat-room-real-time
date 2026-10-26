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

const val DIAMOND_PER_COIN = 2L // 2 diamond = 1 coin
const val INVITE_REWARD_COINS = 10_000_000L
const val TOPUP_TEST_MODE = true // true = isi coin gratis (mode uji, belum ada pembayaran)

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
    private fun db() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db().collection("wallets").document(uid)

    /**
     * Pantau dompet. Data terakhir yang berhasil dibaca disimpan di perangkat, jadi kalau
     * kuota Firestore habis / offline, frame, VIP, dan item tetap tampil (tidak dikosongkan).
     */
    // ---------- Server cadangan (Cloudflare Worker + KV) ----------
    private val backupCbs = java.util.concurrent.CopyOnWriteArrayList<(String, Wallet) -> Unit>()
    @Volatile private var lastBkCheck = 0L

    /** Dipanggil setelah transaksi berhasil di server cadangan: langsung perbarui tampilan. */
    fun emitBackup(uid: String, w: Wallet) {
        try { WalletCache.save(uid, w) } catch (e: Exception) { }
        backupCbs.forEach { try { it(uid, w) } catch (e: Exception) { } }
    }

    /** Saat Firestore sehat lagi: pindahkan transaksi era "kuota habis"
     *  dari server cadangan ke Firestore sekali per menit. */
    private fun syncBackup(uid: String, s: com.google.firebase.firestore.DocumentSnapshot) {
        val now = System.currentTimeMillis()
        if (now - lastBkCheck < 60_000L) return
        lastBkCheck = now
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val bk = BackupRepo.get() ?: return@launch
                val fsBkAt = s.getLong("bkSyncedAt") ?: 0L
                if (bk.ver <= 0L || bk.updatedAt <= fsBkAt) return@launch
                // Gabung inv: masa aktif paling lama menang.
                val invPatch = HashMap<String, Any?>()
                for ((k, v) in bk.inv) invPatch[k] = v
                (s.get("inv") as? Map<*, *>)?.forEach { (k, v) ->
                    if (k is String && v is Number) {
                        val old = (invPatch[k] as? Long) ?: 0L
                        if (v.toLong() > old) invPatch[k] = v.toLong()
                    }
                }
                ref(uid).set(
                    hashMapOf<String, Any?>(
                        "coins" to bk.coins, "diamonds" to bk.diamonds, "exp" to bk.exp,
                        "vip" to bk.vip, "vipUntil" to bk.vipUntil, "lvClaimed" to bk.lvClaimed,
                        "inv" to invPatch, "equip" to bk.equip, "bkSyncedAt" to bk.updatedAt,
                    ),
                    SetOptions.merge(),
                ).await()
            } catch (e: Exception) { }
        }
    }

    fun observe(uid: String, onChange: (Wallet) -> Unit): ListenerRegistration {
        WalletCache.load(uid)?.let(onChange)
        val cb: (String, Wallet) -> Unit = { u, w -> if (u == uid) onChange(w) }
        backupCbs.add(cb)
        val reg = ref(uid).addSnapshotListener { s, e ->
            if (e != null) {
                // Kuota Firestore habis: tampilkan wallet dari server cadangan.
                if (QuotaGuard.isExhausted(e)) {
                    QuotaGuard.markDown()
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            val bk = BackupRepo.base(uid) ?: return@launch
                            if (bk.coins > 0 || bk.vip > 0 || bk.inv.isNotEmpty()) {
                                emitBackup(uid, bk.toWallet())
                            }
                        } catch (e2: Exception) { }
                    }
                }
                return@addSnapshotListener // error/kuota habis: pertahankan data lama
            }
            if (s == null) return@addSnapshotListener
            QuotaGuard.markUp() // Firestore sehat: transaksi kembali normal
            if (!s.exists() && s.metadata.isFromCache) return@addSnapshotListener // belum ada di cache lokal
            val w = parseWallet(s)
            if (s.exists()) {
                WalletCache.save(uid, w)
                syncBackup(uid, s)
            }
            onChange(w)
        }
        return object : ListenerRegistration {
            override fun remove() {
                reg.remove()
                backupCbs.remove(cb)
            }
        }
    }

    suspend fun topUp(uid: String, coins: Long): String? {
        if (QuotaGuard.isDown()) return topUpBackup(uid, coins)
        return try {
            ref(uid).set(hashMapOf<String, Any?>("coins" to FieldValue.increment(coins)), SetOptions.merge()).await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (QuotaGuard.isExhausted(e)) topUpBackup(uid, coins)
            else (e.message ?: "Gagal isi coin")
        }
    }

    private suspend fun topUpBackup(uid: String, coins: Long): String? {
        QuotaGuard.markDown()
        val w = BackupRepo.base(uid) ?: return "Server cadangan tidak bisa dihubungi. Coba lagi nanti."
        val bk = BackupRepo.put(org.json.JSONObject().put("coins", w.coins + coins))
            ?: return "Server cadangan tidak bisa dihubungi. Coba lagi nanti."
        emitBackup(uid, bk.toWallet())
        return null
    }

    /** Kirim gift: coin berkurang, diamond pengirim bertambah sebanyak harga gift. */
    suspend fun sendGift(uid: String, price: Long): String? {
        if (QuotaGuard.isDown()) return sendGiftBackup(uid, price)
        return try {
        val r = ref(uid)
        db().runTransaction { tx ->
            val s = tx.get(r)
            val coins = s.getLong("coins") ?: 0L
            val dia = s.getLong("diamonds") ?: 0L
            if (coins < price) throw IllegalStateException("Coin tidak cukup. Tekan Topup")
            val exp = s.getLong("exp") ?: 0L
            val vipLv = s.getLong("vip") ?: 0L
            val vipUntil = s.getLong("vipUntil") ?: 0L
            val boost = if (vipUntil > System.currentTimeMillis()) 100L + 5L * vipLv else 100L
            tx.set(
                r,
                hashMapOf<String, Any?>(
                    "coins" to coins - price,
                    "diamonds" to dia + price,
                    "exp" to exp + price * boost / 100L,
                ),
                SetOptions.merge(),
            )
            true
        }.await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (QuotaGuard.isExhausted(e)) {
            // Kuota habis: gift tetap terkirim, coin dipotong di server cadangan.
            QuotaGuard.markDown()
            sendGiftBackup(uid, price)
        } else
        e.message ?: "Gagal mengirim gift"
    }
    }

    private suspend fun sendGiftBackup(uid: String, price: Long): String? {
        val w = BackupRepo.base(uid) ?: return "Server cadangan tidak bisa dihubungi. Coba lagi nanti."
        if (w.coins < price) return "Coin tidak cukup. Tekan Topup"
        val boost = if (w.vipUntil > System.currentTimeMillis()) 100L + 5L * w.vip else 100L
        val patch = org.json.JSONObject()
            .put("coins", w.coins - price)
            .put("diamonds", w.diamonds + price)
            .put("exp", w.exp + price * boost / 100L)
        val bk = BackupRepo.put(patch) ?: return "Server cadangan tidak bisa dihubungi. Coba lagi nanti."
        emitBackup(uid, bk.toWallet())
        return null
    }

    suspend fun exchange(uid: String, diamonds: Long): String? {
        if (QuotaGuard.isDown()) return exchangeBackup(uid, diamonds)
        return try {
        val r = ref(uid)
        db().runTransaction { tx ->
            val s = tx.get(r)
            val coins = s.getLong("coins") ?: 0L
            val dia = s.getLong("diamonds") ?: 0L
            val gain = diamonds / DIAMOND_PER_COIN
            if (diamonds > dia) throw IllegalStateException("Diamond tidak cukup")
            if (gain < 1) throw IllegalStateException("Minimal $DIAMOND_PER_COIN diamond")
            tx.set(
                r,
                hashMapOf<String, Any?>(
                    "coins" to coins + gain,
                    "diamonds" to dia - gain * DIAMOND_PER_COIN,
                ),
                SetOptions.merge(),
            )
            true
        }.await()
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (QuotaGuard.isExhausted(e)) exchangeBackup(uid, diamonds)
        else (e.message ?: "Gagal menukar")
    }
    }

    private suspend fun exchangeBackup(uid: String, diamonds: Long): String? {
        QuotaGuard.markDown()
        val w = BackupRepo.base(uid) ?: return "Server cadangan tidak bisa dihubungi. Coba lagi nanti."
        val gain = diamonds / DIAMOND_PER_COIN
        if (diamonds > w.diamonds) return "Diamond tidak cukup"
        if (gain < 1) return "Minimal $DIAMOND_PER_COIN diamond"
        val patch = org.json.JSONObject()
            .put("coins", w.coins + gain)
            .put("diamonds", w.diamonds - gain * DIAMOND_PER_COIN)
        val bk = BackupRepo.put(patch) ?: return "Server cadangan tidak bisa dihubungi. Coba lagi nanti."
        emitBackup(uid, bk.toWallet())
        return null
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

@Composable
fun WalletScreen(uid: String, wallet: Wallet, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val toast = { m: String -> Toast.makeText(context, m, Toast.LENGTH_SHORT).show() }

    OverlayBox {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            GoldTopBar(tr("Dompet"), onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BalanceCard("🪙", "Coin", fmtCompact(wallet.coins), Modifier.weight(1f))
                BalanceCard("💎", "Diamond", fmtCompact(wallet.diamonds), Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
                Text(tr("Isi coin"), color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (TOPUP_TEST_MODE) {
                        "Mode uji: coin ditambahkan gratis (pembayaran belum terhubung)."
                    } else {
                        "Pembayaran belum tersedia."
                    },
                    color = Color(0xFF9A9A9A),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                listOf(1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L).forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardBlack.copy(alpha = 0.9f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🪙 ${fmtNum(c)} coin", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        GoldButton(tr("Isi"), Modifier.width(80.dp), enabled = !busy) {
                            if (!TOPUP_TEST_MODE) {
                                toast("Pembayaran belum tersedia")
                            } else {
                                busy = true
                                scope.launch {
                                    val e = WalletRepo.topUp(uid, c)
                                    busy = false
                                    toast(e ?: "+${fmtNum(c)} coin")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(tr("Tukar diamond ke coin"), color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("$DIAMOND_PER_COIN diamond = 1 coin", color = Color(0xFF9A9A9A), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() }.take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(tr("Jumlah diamond")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = goldFieldColors(),
                )
                val n = amount.toLongOrNull() ?: 0L
                Text("Kamu dapat ${fmtNum(n / DIAMOND_PER_COIN)} coin", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton(tr("Tukar semua"), Modifier.weight(1f)) { amount = wallet.diamonds.toString() }
                    GoldButton(tr("Tukar"), Modifier.weight(1f), enabled = !busy && n > 0) {
                        busy = true
                        scope.launch {
                            val e = WalletRepo.exchange(uid, n)
                            busy = false
                            if (e == null) {
                                toast("Berhasil menukar ${fmtNum(n)} diamond")
                                amount = ""
                            } else {
                                toast(e)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
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
            if (g.mediaUrl.isNotBlank()) {
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

/** Tab Cashback: total koin cashback user, daftar gift khas dengan cashback, dan riwayat. */
@Composable
fun CashbackTab(myUid: String) {
    var total by remember { mutableStateOf(0L) }
    var logs by remember { mutableStateOf<List<Triple<Long, String, Long>>>(emptyList()) }
    val teal = Color(0xFF2BD9C4)

    DisposableEffect(myUid) {
        val reg = Rtdb.listen(Rtdb.ref("cashback/$myUid")) { s ->
            total = s.child("total").getValue(Long::class.java) ?: 0L
            logs = s.child("log").children
                .mapNotNull { c ->
                    val g = c.child("g").getValue(String::class.java) ?: return@mapNotNull null
                    val cb = c.child("cb").getValue(Long::class.java) ?: 0L
                    val ts = c.child("ts").getValue(Long::class.java) ?: 0L
                    Triple(ts, g, cb)
                }
                .sortedByDescending { it.first }
                .take(8)
        }
        onDispose { reg.remove() }
    }

    val cbGifts = remember(GiftCatalog.list) { GiftCatalog.list.filter { it.cbRate > 0 } }

    Column(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💵", fontSize = 26.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(tr("Total cashback kamu"), color = Color(0xFF8FA3A0), fontSize = 12.sp)
                Text("${fmtNum(total)} koin", color = teal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tr("Gift khas dengan cashback") + ":",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        if (cbGifts.isEmpty()) {
            Text(
                tr("Belum ada. Persen cashback diatur admin di Panel Developer"),
                color = Color(0xFF8FA3A0),
                fontSize = 12.sp,
            )
        } else {
            cbGifts.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (g.mediaUrl.isNotBlank()) {
                        AsyncImage(
                            model = g.mediaUrl,
                            contentDescription = g.name,
                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(g.emoji, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        g.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("+${g.cbRate}%", color = teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (logs.isNotEmpty()) {
            Text(
                tr("Riwayat terakhir") + ":",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            logs.forEach { (ts, gname, cb) ->
                val t = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(ts))
                Text(
                    "💵 +${fmtNum(cb)} 🪙 • $gname • $t",
                    color = Color(0xFFB8C8C5),
                    fontSize = 12.sp,
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
    var selected by remember { mutableStateOf(gifts.firstOrNull()?.id ?: "") }

    // Jaga selected tetap valid saat daftar gift berubah / masih kosong.
    LaunchedEffect(gifts) {
        if (gifts.none { it.id == selected }) selected = gifts.firstOrNull()?.id ?: ""
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
    val pages = remember(gifts) { gifts.chunked(8) }
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

            if (tab == 0) {
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
                        Text("Belum ada gift", color = Color(0xFF8FA3A0), fontSize = 14.sp)
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
            } else if (tab == 2) {
                CashbackTab(myUid)
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
                                    val err = WalletRepo.sendGift(myUid, total)
                                    if (err == null) {
                                        PublicSync.push(myUid)
                                        RoomExtras.sendGiftMessage(roomId, myName, g, count, toText)
                                        // Poin mic: gift ke kursi penerima menambah poin sesuai harga (RTDB, hemat kuota Firestore).
                                        val inc = g.price * count
                                        receivers.filter { it.index >= 0 }.forEach { sRec ->
                                            Rtdb.ref("micPoints/" + roomId + "/" + sRec.index)
                                                .setValue(ServerValue.increment(inc))
                                        }
                                        val (jp, cb) = GiftFx.sent(roomId, myUid, myName, g, count, receivers.size)
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
                                        val rerr = GiftRankRepo.record(myUid, myName, roomId, perReceiver, receivers)
                                        Toast.makeText(
                                            context,
                                            "Gift terkirim! +${fmtNum(total)} diamond",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        if (rerr != null) {
                                            Toast.makeText(context, "Ranking gagal: $rerr", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
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
