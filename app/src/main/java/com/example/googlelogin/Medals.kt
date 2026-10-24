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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

const val MAX_MEDALS = 999

/** Daftar id medal dari string "vipt_3,sx_abc,badge_star". */
fun medalIdList(csv: String?): List<String> =
    (csv ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Medal yang sedang dipasang dan masih berlaku (dari wallet sendiri). */
fun activeMedalIds(w: Wallet): List<String> {
    val now = System.currentTimeMillis()
    return medalIdList(w.equip["medals"]).filter { (w.inv[it] ?: 0L) > now }.take(MAX_MEDALS)
}

/** Semua medal yang dimiliki: medal VIP (vipt_), medal/lencana dari store. */
private fun ownedMedalIds(w: Wallet): List<String> {
    val now = System.currentTimeMillis()
    return w.inv.filter { (id, exp) ->
        exp > now && (id.startsWith("vipt_") || findItem(id)?.slot.let { it == "medal" || it == "badge" })
    }.keys.sortedWith(compareBy<String>({ !it.startsWith("vipt_") }, { it }))
}

/** Satu ikon medal: medal VIP pakai VipTag, lainnya ikon store. */
@Composable
fun MedalIcon(id: String, size: Dp, tagModifier: Modifier = Modifier) {
    if (id.startsWith("vipt_")) {
        val n = id.removePrefix("vipt_").toIntOrNull() ?: 0
        if (n in 1..9) VipTag(n, tagModifier)
        return
    }
    findItem(id)?.let { StoreBadgeIcon(it, size) }
}

/** Deretan medal kecil (di bawah nama, di chat room, di profil). Kosong = tidak menampilkan apa-apa. */
@Composable
fun UserMedals(csv: String?, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { StoreCatalog.start(); VipAssets.load() }
    val ids = medalIdList(csv).take(MAX_MEDALS).filter { it.startsWith("vipt_") || findItem(it) != null }
    if (ids.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        ids.forEach { MedalIcon(it, size) }
    }
}

private data class MedalEntry(val id: String, val name: String, val owned: Boolean, val ci: CatalogItem? = null)

@Composable
private fun MedalSlot(id: String?, onClick: () -> Unit) {
    Box(
        Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF2B190C))
            .border(1.5.dp, Gold.copy(alpha = 0.75f), CircleShape)
            .clickable(enabled = id != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (id != null) MedalIcon(id, 46.dp, Modifier.scale(0.8f))
        else Text("?", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MedalGrid(list: List<MedalEntry>, worn: List<String>, onTap: (MedalEntry) -> Unit) {
    list.chunked(2).forEach { pair ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair.forEach { e ->
                val on = e.id in worn
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF3A2212))
                        .border(
                            if (on) 2.dp else 1.dp,
                            if (on) Gold else Gold.copy(alpha = 0.3f),
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { onTap(e) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.alpha(if (e.owned) 1f else 0.45f), contentAlignment = Alignment.Center) {
                            MedalIcon(e.id, 100.dp, Modifier.scale(1.6f))
                        }
                        if (!e.owned) Text("\uD83D\uDD12", fontSize = 34.sp)
                        if (on) {
                            Text(
                                "\u2713", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                            )
                        }
                    }
                    Text(
                        e.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Text(
                        when {
                            !e.owned -> "\uD83E\uDE99 ${fmtNum(e.ci?.price ?: 0L)} \u00B7 ${e.ci?.days ?: 0L} hari"
                            on -> "Lepas"
                            else -> "Pasang"
                        },
                        color = if (e.owned) Gold else Color(0xFFBFA77F), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun GloryMedalScreen(uid: String, wallet: Wallet, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var buying by remember { mutableStateOf<CatalogItem?>(null) }
    LaunchedEffect(Unit) { StoreCatalog.start(); VipAssets.load() }
    val worn = activeMedalIds(wallet)
    val ownedIds = ownedMedalIds(wallet)
    val mine = ownedIds.map { MedalEntry(it, findItem(it)?.name ?: it, true) }
    val shop = StoreCatalog.bySlot("medal")
        .filter { "sx_${it.id}" !in ownedIds }
        .map { MedalEntry("sx_${it.id}", it.name, false, it) }

    fun save(list: List<String>) {
        AppScope.scope.launch {
            val err = LvRepo.equip(uid, "medals", list.joinToString(","))
            if (err == null) PublicSync.push(uid)
            else Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
        }
    }

    fun tapEntry(e: MedalEntry) {
        when {
            !e.owned -> buying = e.ci
            e.id in worn -> save(worn - e.id)
            else -> save(worn + e.id)
        }
    }

    Box(
        Modifier.fillMaxSize().blockTouches()
            .background(Brush.verticalGradient(listOf(Color(0xFF4A2C14), Color(0xFF1E1109), Color(0xFF120B06)))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    "\u2039", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 8.dp),
                )
                Text(
                    "Medali kehormatan", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 14.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33000000))
                        .border(2.dp, Gold.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                        .padding(vertical = 16.dp),
                ) {
                    for (r in 0 until 2) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (c in 0 until 5) {
                                val id = worn.getOrNull(r * 5 + c)
                                MedalSlot(id) { if (id != null) save(worn - id) }
                            }
                        }
                        if (r == 0) Spacer(Modifier.height(14.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Terpasang ${worn.size}/$MAX_MEDALS \u00B7 ketuk medal untuk memasang / melepas",
                    color = Color(0xFFE9D3B0), fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))

                val tabs = listOf("PENCAPAIAN", "KUSTOM MEDALI", "AKTIVITAS")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color(0xFF4A2F1B))
                        .border(1.dp, Gold.copy(alpha = 0.5f), RoundedCornerShape(50)),
                ) {
                    tabs.forEachIndexed { i, t ->
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50))
                                .background(if (tab == i) GoldBrush else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                                .clickable { tab = i }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                t, color = if (tab == i) Color(0xFF3B2A00) else Color(0xFFE9D3B0),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (tab != 0) {
                    Text(
                        "Segera hadir", color = Color(0xFFE9D3B0), fontSize = 15.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    )
                } else {
                    Text("Medali saya", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (mine.isEmpty()) {
                        Text(
                            "Belum punya medal. Beli di Store atau aktifkan VIP untuk mendapat medal VIP.",
                            color = Color(0xFFE9D3B0), fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                    } else {
                        MedalGrid(mine, worn) { tapEntry(it) }
                    }
                    if (shop.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Belum dimiliki", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        MedalGrid(shop, worn) { tapEntry(it) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    buying?.let { ci ->
        AlertDialog(
            onDismissRequest = { buying = null },
            title = { Text(ci.name) },
            text = { Text("Beli seharga ${fmtNum(ci.price)} koin untuk ${ci.days} hari? Setelah dibeli, ketuk lagi untuk memasangnya.") },
            confirmButton = {
                TextButton(onClick = {
                    buying = null
                    AppScope.scope.launch {
                        val err = LvRepo.buyItem(uid, ci.toStoreItem())
                        Toast.makeText(context, err ?: "Berhasil dibeli", Toast.LENGTH_SHORT).show()
                        if (err == null) PublicSync.push(uid)
                    }
                }) { Text("Beli") }
            },
            dismissButton = { TextButton(onClick = { buying = null }) { Text("Batal") } },
        )
    }
}
