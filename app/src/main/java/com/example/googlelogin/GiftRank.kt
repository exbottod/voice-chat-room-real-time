package com.example.googlelogin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class RankEntry(val id: String, val name: String, val total: Long)

/** Periode ranking memakai zona waktu UTC+7 (Asia/Jakarta). */
object RankPeriod {
    private val tz: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    private fun cal(now: Long): Calendar =
        Calendar.getInstance(tz, Locale.US).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = now
        }

    /** p: 0 = harian, 1 = mingguan, 2 = bulanan */
    fun key(p: Int, now: Long): String {
        val c = cal(now)
        return when (p) {
            0 -> String.format(Locale.US, "d%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
            1 -> String.format(Locale.US, "w%04d%02d", c.weekYear, c.get(Calendar.WEEK_OF_YEAR))
            else -> String.format(Locale.US, "m%04d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }
    }

    fun endMillis(p: Int, now: Long): Long {
        val c = cal(now)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        when (p) {
            0 -> c.add(Calendar.DAY_OF_MONTH, 1)
            1 -> {
                c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                c.add(Calendar.WEEK_OF_YEAR, 1)
            }
            else -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, 1)
            }
        }
        return c.timeInMillis
    }
}

object GiftRankRepo {
    const val COL_SAWER = "rank_sawer"
    const val COL_ROOM = "rank_room"
    const val COL_CHARM = "rank_charm"

    private fun db() = FirebaseFirestore.getInstance()

    private fun bump(b: WriteBatch, col: String, subject: String, name: String, amount: Long, now: Long) {
        for (p in 0..2) {
            val key = RankPeriod.key(p, now)
            val ref = db().collection(col).document("${key}_$subject")
            b.set(
                ref,
                hashMapOf<String, Any?>(
                    "period" to key,
                    "subject" to subject,
                    "name" to name,
                    "total" to FieldValue.increment(amount),
                    "updatedAt" to now,
                ),
                SetOptions.merge(),
            )
        }
    }

    /** Dipanggil setelah gift berhasil dikirim. Return pesan error (atau null kalau sukses). */
    suspend fun record(
        senderUid: String,
        senderName: String,
        roomId: String,
        perReceiver: Long,
        receivers: List<Seat>,
    ): String? {
        return try {
            val now = System.currentTimeMillis()
            val total = perReceiver * receivers.size
            val roomName = try {
                db().collection("rooms").document(roomId).get().await().getString("name") ?: "Room"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                "Room"
            }
            val b = db().batch()
            bump(b, COL_SAWER, senderUid, senderName, total, now)
            bump(b, COL_ROOM, roomId, roomName, total, now)
            receivers.forEach { bump(b, COL_CHARM, it.uid, it.name, perReceiver, now) }
            b.commit().await()
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            e.message ?: "Gagal mencatat ranking"
        }
    }

    suspend fun top(col: String, periodKey: String, limit: Int = 30): List<RankEntry> =
        db().collection(col).whereEqualTo("period", periodKey).limit(300).get().await()
            .documents
            .map { RankEntry(it.getString("subject") ?: "", it.getString("name") ?: "", it.getLong("total") ?: 0L) }
            .filter { it.id.isNotEmpty() && it.total > 0 }
            .sortedByDescending { it.total }
            .take(limit)
}

fun fmtRank(n: Long): String {
    val loc = Locale.forLanguageTag("id-ID")
    fun f(v: Double, s: String) = String.format(loc, "%.1f", v) + s
    return when {
        n >= 1_000_000_000L -> f(n / 1e9, "B")
        n >= 1_000_000L -> f(n / 1e6, "M")
        n >= 1_000L -> f(n / 1e3, "K")
        else -> n.toString()
    }
}

private object RankAvatarCache {
    val map = HashMap<String, String?>()
}

private suspend fun loadRankAvatar(col: String, id: String): String? {
    val k = "$col:$id"
    if (RankAvatarCache.map.containsKey(k)) return RankAvatarCache.map[k]
    val v = try {
        if (col == GiftRankRepo.COL_ROOM) {
            FirebaseFirestore.getInstance().collection("rooms").document(id).get().await().getString("photoB64")
        } else {
            UserDirectory.findByUid(id)?.avatarB64
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    RankAvatarCache.map[k] = v
    return v
}

@Composable
fun TopRankDialog(onDismiss: () -> Unit, initialTab: Int = 1) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        TopRankContent(onDismiss, initialTab)
    }
}

@Composable
private fun RkAvatar(col: String, e: RankEntry, size: Dp, ring: Color) {
    var b64 by remember(col, e.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(col, e.id) { b64 = loadRankAvatar(col, e.id) }
    val bmp = remember(b64) { ProfileRepo.decodeB64(b64) }
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF2A1010)).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                if (col == GiftRankRepo.COL_ROOM) "🎙️" else e.name.take(1).uppercase(),
                color = GoldLight, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}

@Composable
private fun RkCoin(total: Long, size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🪙", fontSize = size)
        Spacer(Modifier.width(4.dp))
        Text(fmtRank(total), color = GoldLight, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun RkTopOne(col: String, e: RankEntry) {
    val plate = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("👑", fontSize = 44.sp)
        RkAvatar(col, e, 104.dp, Gold)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .clip(plate)
                .background(Brush.verticalGradient(listOf(Color(0xFF8E1B1B), Color(0xFF4A0D0D))))
                .border(1.dp, Gold.copy(alpha = 0.6f), plate)
                .padding(horizontal = 28.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(e.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RkCoin(e.total, 26.sp)
        }
    }
}

@Composable
private fun RkShield(col: String, e: RankEntry, rank: Int, c1: Color, c2: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 36.dp, bottomEnd = 36.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c1, c2)))
            .border(2.dp, GoldDark, shape)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            RkAvatar(col, e, 72.dp, GoldLight)
            Box(
                Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(GoldBrush),
                contentAlignment = Alignment.Center,
            ) { Text("$rank", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text(e.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        RkCoin(e.total, 18.sp)
    }
}

@Composable
private fun RkRow(col: String, e: RankEntry, rank: Int) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(Color(0xCC2A0A0A))
            .border(1.dp, Gold.copy(alpha = 0.35f), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", color = GoldLight, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp))
        RkAvatar(col, e, 52.dp, GoldDark)
        Spacer(Modifier.width(12.dp))
        Text(e.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        RkCoin(e.total, 16.sp)
    }
}

@Composable
private fun RkMine(col: String, e: RankEntry?, index: Int, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF8E1B1B), Color(0xFF4A0D0D))))
            .border(1.dp, Gold, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (index >= 0) "#${index + 1}" else "-",
            color = GoldLight, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(64.dp),
        )
        if (e != null) {
            RkAvatar(col, e, 44.dp, Gold)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(tr("Peringkat kamu"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                if (e != null) e.name else tr("Belum ada di ranking periode ini"),
                color = Color(0xFFD9C7A8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (e != null) RkCoin(e.total, 16.sp)
    }
}

@Composable
private fun TopRankContent(onBack: () -> Unit, initialTab: Int) {
    var tab by remember { mutableStateOf(initialTab) }   // 0 Kamar, 1 Sawer, 2 Pesona
    var period by remember { mutableStateOf(0) }          // 0 Harian, 1 Mingguan, 2 Bulanan
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var entries by remember { mutableStateOf<List<RankEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val myUid = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    val col = when (tab) {
        0 -> GiftRankRepo.COL_ROOM
        1 -> GiftRankRepo.COL_SAWER
        else -> GiftRankRepo.COL_CHARM
    }
    val periodKey = RankPeriod.key(period, now)

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(col, periodKey) {
        loading = true
        error = null
        entries = emptyList()
        try {
            entries = GiftRankRepo.top(col, periodKey, 100)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "error"
        }
        loading = false
    }

    val remain = ((RankPeriod.endMillis(period, now) - now) / 1000).coerceAtLeast(0)
    val d = remain / 86400
    val h = (remain % 86400) / 3600
    val m = (remain % 3600) / 60
    val s = remain % 60
    val countdown = (if (d > 0) "${d}d " else "") + String.format(Locale.US, "%02d:%02d:%02d", h, m, s)

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF3A0B0B), Color(0xFF140404), Color.Black)),
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // tab atas: Kamar | Sawer | Pesona
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 36.sp, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 14.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Kamar", "Sawer", "Pesona").forEachIndexed { i, label ->
                        val on = tab == i
                        Column(Modifier.clickable { tab = i }.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                tr(label),
                                color = if (on) Color.White else Color(0xFFB8A58A),
                                fontSize = if (on) 22.sp else 18.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            )
                            Spacer(Modifier.height(3.dp))
                            Box(Modifier.width(if (on) 22.dp else 0.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(GoldBrush))
                        }
                    }
                }
                Spacer(Modifier.width(50.dp))
            }
            Spacer(Modifier.height(12.dp))
            // periode
            val pill = RoundedCornerShape(50)
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(pill).border(2.dp, Gold, pill).background(Color(0x66000000)),
            ) {
                listOf("Harian", "Mingguan", "Bulanan").forEachIndexed { i, label ->
                    val on = period == i
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(pill)
                            .background(if (on) GoldBrush else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                            .clickable { period = i }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tr(label), color = if (on) Color.Black else GoldLight, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${tr("Hitung mundur")}(UTC+7): $countdown",
                color = GoldLight, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                if (loading || entries.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 80.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (loading) tr("Memuat...") else tr("Belum ada data"),
                                color = Color(0xFFB8A58A), fontSize = 15.sp,
                            )
                            if (!loading && error != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(error ?: "", color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    item { RkTopOne(col, entries[0]) }
                    if (entries.size > 1) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RkShield(col, entries[1], 2, Color(0xFF2B5FA8), Color(0xFF10264A), Modifier.weight(1f))
                                if (entries.size > 2) {
                                    RkShield(col, entries[2], 3, Color(0xFF8E4A2B), Color(0xFF3A160A), Modifier.weight(1f))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    itemsIndexed(entries.drop(3)) { i, e -> RkRow(col, e, i + 4) }
                }
            }
        }
        if (!loading && col != GiftRankRepo.COL_ROOM) {
            val mine = entries.indexOfFirst { it.id == myUid }
            RkMine(col, entries.getOrNull(mine), mine, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
