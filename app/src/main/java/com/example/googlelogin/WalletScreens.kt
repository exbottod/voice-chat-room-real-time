package com.example.googlelogin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.foundation.text.KeyboardOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

const val DIAMOND_PER_COIN = 2L          // 2 diamond = 1 coin
const val INVITE_REWARD_COINS = 10_000_000L
const val TOPUP_TEST_MODE = true         // true = isi coin gratis (mode uji, belum ada pembayaran)

data class Wallet(val coins: Long = 0L, val diamonds: Long = 0L)
data class Gift(val id: String, val name: String, val emoji: String, val price: Long)

val GIFTS = listOf(
    Gift("rose", "Mawar", "🌹", 10),
    Gift("heart", "Hati", "❤️", 50),
    Gift("coffee", "Kopi", "☕", 100),
    Gift("lucky", "Lucky bag", "💰", 500),
    Gift("bear", "Bear hug", "🧸", 1200),
    Gift("crown", "King crown", "👑", 4800),
    Gift("raksha", "Raksha Bandhan", "🪔", 4800),
    Gift("ring", "Cincin", "💍", 20000),
    Gift("car", "Mobil sport", "🚗", 99000),
    Gift("plane", "Pesawat", "✈️", 250000),
    Gift("dussehra", "Dussehra", "🏹", 900000),
    Gift("dragon", "Naga emas", "🐉", 1000000),
    Gift("hdussehra", "Happy Dussehra", "🔱", 1800000),
    Gift("flag", "Cium bendera", "💋", 1800000),
    Gift("ferris", "Sweet Ferris Wheel", "🎡", 2100000),
    Gift("castle", "Istana", "🏰", 5000000),
)

object WalletRepo {
    private fun db() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db().collection("wallets").document(uid)

    fun observe(uid: String, onChange: (Wallet) -> Unit): ListenerRegistration =
        ref(uid).addSnapshotListener { s, _ ->
            onChange(Wallet(s?.getLong("coins") ?: 0L, s?.getLong("diamonds") ?: 0L))
        }

    suspend fun topUp(uid: String, coins: Long): String? = try {
        ref(uid).set(hashMapOf<String, Any?>("coins" to FieldValue.increment(coins)), SetOptions.merge()).await()
        null
    } catch (e: Exception) {
        e.message ?: "Gagal isi coin"
    }

    /** Kirim gift: coin berkurang, diamond pengirim bertambah sebanyak harga gift. */
    suspend fun sendGift(uid: String, price: Long): String? = try {
        val r = ref(uid)
        db().runTransaction { tx ->
            val s = tx.get(r)
            val coins = s.getLong("coins") ?: 0L
            val dia = s.getLong("diamonds") ?: 0L
            if (coins < price) throw IllegalStateException("Coin tidak cukup. Tekan Topup")
            tx.set(r, hashMapOf<String, Any?>("coins" to coins - price, "diamonds" to dia + price), SetOptions.merge())
            true
        }.await()
        null
    } catch (e: Exception) {
        e.message ?: "Gagal mengirim gift"
    }

    suspend fun exchange(uid: String, diamonds: Long): String? = try {
        val r = ref(uid)
        db().runTransaction { tx ->
            val s = tx.get(r)
            val coins = s.getLong("coins") ?: 0L
            val dia = s.getLong("diamonds") ?: 0L
            val gain = diamonds / DIAMOND_PER_COIN
            if (diamonds > dia) throw IllegalStateException("Diamond tidak cukup")
            if (gain < 1) throw IllegalStateException("Minimal $DIAMOND_PER_COIN diamond")
            tx.set(r, hashMapOf<String, Any?>("coins" to coins + gain, "diamonds" to dia - gain * DIAMOND_PER_COIN), SetOptions.merge())
            true
        }.await()
        null
    } catch (e: Exception) {
        e.message ?: "Gagal menukar"
    }
}

@Composable
private fun BalanceCard(emoji: String, label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(CardBlack.copy(alpha = 0.9f)).border(1.dp, Gold.copy(alpha = 0.4f), shape).padding(16.dp),
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
            GoldTopBar("Dompet", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BalanceCard("🪙", "Coin", fmtNum(wallet.coins), Modifier.weight(1f))
                    BalanceCard("💎", "Diamond", fmtNum(wallet.diamonds), Modifier.weight(1f))
                }

                Spacer(Modifier.height(20.dp))
                Text("Isi coin", color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (TOPUP_TEST_MODE) "Mode uji: coin ditambahkan gratis (pembayaran belum terhubung)."
                    else "Pembayaran belum tersedia.",
                    color = Color(0xFF9A9A9A), fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                listOf(1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L).forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                            .background(CardBlack.copy(alpha = 0.9f)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🪙 ${fmtNum(c)} coin", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        GoldButton("Isi", Modifier.width(80.dp), enabled = !busy) {
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
                Text("Tukar diamond ke coin", color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("$DIAMOND_PER_COIN diamond = 1 coin", color = Color(0xFF9A9A9A), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() }.take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Jumlah diamond") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = goldFieldColors(),
                )
                val n = amount.toLongOrNull() ?: 0L
                Text("Kamu dapat ${fmtNum(n / DIAMOND_PER_COIN)} coin", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton("Tukar semua", Modifier.weight(1f)) { amount = wallet.diamonds.toString() }
                    GoldButton("Tukar", Modifier.weight(1f), enabled = !busy && n > 0) {
                        busy = true
                        scope.launch {
                            val e = WalletRepo.exchange(uid, n)
                            busy = false
                            if (e == null) {
                                toast("Berhasil menukar ${fmtNum(n)} diamond")
                                amount = ""
                            } else toast(e)
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
        Text(g.emoji, fontSize = 38.sp)
        Text(g.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${g.price} 🪙", color = Color(0xFF8FA3A0), fontSize = 12.sp, maxLines = 1)
    }
}

/** Panel hadiah di dalam room (gaya seperti contoh). */
@Composable
fun GiftPanel(roomId: String, myUid: String, myName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current
    var wallet by remember { mutableStateOf(Wallet()) }
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(GIFTS.first().id) }
    var count by remember { mutableStateOf(1) }
    var countMenu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val pages = remember { GIFTS.chunked(8) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val teal = Color(0xFF2BD9C4)

    DisposableEffect(myUid) {
        val r = WalletRepo.observe(myUid) { wallet = it }
        onDispose { r.remove() }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).clickable(
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
                listOf("Hadiah", "Aktivitas", "Cashback").forEachIndexed { i, label ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (tab == i) teal else Color(0xFF16302F))
                            .clickable { tab = i }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) { Text(label, color = if (tab == i) Color.Black else Color(0xFF8FA3A0), fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (tab == 0) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(250.dp)) { p ->
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
                            Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                                .background(if (pager.currentPage == i) teal else Color(0xFF3A4A48)),
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    Text("Segera hadir", color = Color(0xFF8FA3A0))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🪙", fontSize = 22.sp)
                Spacer(Modifier.width(6.dp))
                Text(fmtNum(wallet.coins), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Topup ◆", color = teal, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss(); actions.openWallet() },
                )
                Spacer(Modifier.weight(1f))
                val pill = RoundedCornerShape(50)
                Row(Modifier.clip(pill).border(1.5.dp, teal, pill), verticalAlignment = Alignment.CenterVertically) {
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
                                DropdownMenuItem(text = { Text("$c") }, onClick = { count = c; countMenu = false })
                            }
                        }
                    }
                    Box(
                        Modifier
                            .background(if (sending) Color(0xFF3A4A48) else teal)
                            .clickable(enabled = !sending) {
                                val g = GIFTS.first { it.id == selected }
                                val total = g.price * count
                                sending = true
                                scope.launch {
                                    val err = WalletRepo.sendGift(myUid, total)
                                    if (err == null) {
                                        RoomExtras.sendGiftMessage(roomId, myName, g, count)
                                        Toast.makeText(context, "Gift terkirim! +${fmtNum(total)} diamond", Toast.LENGTH_SHORT).show()
                                    } else Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    sending = false
                                }
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) { Text("Berikan", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
