package com.example.googlelogin

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Room(val name: String, val owner: String, val online: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyTab(
    myUid: String,
    myName: String,
    onSearch: () -> Unit = {},
    onEnterRoom: (RoomInfo) -> Unit = {},
) {
    val context = LocalContext.current
    var subTab by rememberSaveable { mutableStateOf(1) }
    var filter by rememberSaveable { mutableStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var lockedRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var showRank by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    val allRooms = rememberRooms(refreshKey)
    val soon = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    val open: (RoomInfo) -> Unit = { room ->
        if (room.locked && room.ownerUid != myUid) lockedRoom = room else onEnterRoom(room)
    }
    val shown = when {
        subTab == 0 -> allRooms.filter { it.ownerUid == myUid }
        filter == 0 -> allRooms.sortedByDescending { it.online }
        else -> allRooms
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            refreshKey++
            refreshScope.launch { delay(1000); refreshing = false }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            TopTabs(
                selected = subTab,
                onSelect = { subTab = it },
                onSearch = onSearch,
                onCreate = { showCreate = true },
            )
        }
        if (subTab == 2) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyBlock(tr("Belum ada aktivitas")) }
        } else {
            if (subTab == 1) {
                item(span = { GridItemSpan(maxLineSpan) }) { BannerPager() }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RankRow(onClick = { if (it == "Top Sawer") showRank = true else soon("$it segera hadir") })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PrizeBar(onClick = { soon("Undian hadiah segera hadir") })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FilterChips(selected = filter, onSelect = { filter = it })
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyBlock(if (subTab == 0) "Belum ada room milik Anda" else "Belum ada room.\nTekan + untuk membuat room")
                }
            } else {
                items(shown, key = { it.id }) { room ->
                    RoomCardView(room, onClick = { open(room) })
                }
            }
        }
    }
    }

    if (showCreate) {
        CreateRoomDialog(
            ownerUid = myUid,
            ownerName = myName,
            onDismiss = { showCreate = false },
            onCreated = { showCreate = false; onEnterRoom(it) },
        )
    }
    if (showRank) TopRankDialog(onDismiss = { showRank = false })
    lockedRoom?.let { r ->
        PasswordDialog(r, onDismiss = { lockedRoom = null }, onOk = { lockedRoom = null; onEnterRoom(r) })
    }
}

@Composable
private fun TopTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    onSearch: () -> Unit,
    onCreate: () -> Unit,
) {
    val labels = listOf(tr("Saya"), tr("Party"), tr("Aktivitas"))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            Column(
                modifier = Modifier
                    .clickable { onSelect(i) }
                    .padding(end = 20.dp, top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    color = if (active) Gold else Color(0xFFBDBDBD),
                    fontSize = if (active) 22.sp else 17.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .width(if (active) 24.dp else 0.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GoldBrush)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Cari", tint = GoldLight) }
        IconButton(onClick = onCreate) { Icon(Icons.Default.Add, "Buat room", tint = GoldLight) }
    }
}

private data class AppBanner(val title: String, val subtitle: String, val imageB64: String?, val link: String = "")

@Composable
private fun BannerPager() {
    val defaults = listOf(
        Triple("Liga Peringkat Fam", "Raih hadiah terbesar minggu ini", listOf(Color(0xFF5B1E1E), Color(0xFF1A0A0A))),
        Triple("Event Spesial", "Kumpulkan poin dan menangkan hadiah", listOf(Color(0xFF3B1E5B), Color(0xFF120A1A))),
        Triple("Top Family", "Family terbaik pekan ini", listOf(Color(0xFF1E3B5B), Color(0xFF0A121A))),
    )
    var remote by remember { mutableStateOf<List<AppBanner>>(emptyList()) }
    val context = LocalContext.current
    val openLink = { url: String ->
        try {
            val u = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Tidak bisa membuka link", Toast.LENGTH_SHORT).show()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val reg = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("banners")
            .addSnapshotListener { snap, err ->
                if (err != null) android.util.Log.w("Banner", err)
                if (snap != null) {
                    remote = snap.documents
                        .filter { it.getBoolean("active") != false && !it.getString("imageB64").isNullOrBlank() }
                        .sortedBy { it.getLong("order") ?: 0L }
                        .map { AppBanner(it.getString("title") ?: "", it.getString("subtitle") ?: "", it.getString("imageB64"), it.getString("link") ?: "") }
                }
            }
        onDispose { reg.remove() }
    }
    val count = if (remote.isNotEmpty()) remote.size else defaults.size
    val state = rememberPagerState(pageCount = { count })
    val frame = RoundedCornerShape(18.dp)
    val goldBorder = Brush.linearGradient(listOf(GoldDark, GoldLight, GoldDark))
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(state = state, modifier = Modifier.fillMaxWidth(), pageSpacing = 12.dp) { page ->
            if (remote.isNotEmpty()) {
                val b = remote[page.coerceAtMost(remote.lastIndex)]
                val bmp = remember(b.imageB64) { ProfileRepo.decodeB64(b.imageB64) }
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f).clip(frame)
                        .background(Color(0xFF1A1A1A)).border(1.dp, goldBorder, frame)
                        .clickable(enabled = b.link.isNotBlank()) { openLink(b.link) },
                ) {
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp, contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (b.title.isNotBlank() || b.subtitle.isNotBlank()) {
                        Column(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                                .padding(12.dp),
                        ) {
                            if (b.title.isNotBlank()) Text(b.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (b.subtitle.isNotBlank()) Text(b.subtitle, color = Color(0xFFE0E0E0), fontSize = 12.sp)
                        }
                    }
                }
            } else {
                val (title, subtitle, colors) = defaults[page.coerceAtMost(defaults.lastIndex)]
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f).clip(frame)
                        .background(Brush.linearGradient(colors)).border(1.dp, goldBorder, frame),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            title,
                            textAlign = TextAlign.Center,
                            style = TextStyle(
                                brush = Brush.verticalGradient(listOf(GoldLight, Gold, GoldDark)),
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(subtitle, color = Color(0xFFE0E0E0), fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(count) { i ->
                val on = state.currentPage == i
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(if (on) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (on) Gold else Color(0xFF555555))
                )
            }
        }
    }
}

@Composable
private fun RankRow(onClick: (String) -> Unit) {
    val cards = listOf(
        "Top Sawer" to Color(0xFF8E1B1B),
        "Top CP" to Color(0xFF6A1B9A),
        "Family" to Color(0xFF1B4F8E),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.forEach { (title, color) ->
            RankCard(title, color, Modifier.weight(1f)) { onClick(title) }
        }
    }
}

@Composable
private fun RankCard(title: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .height(150.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color, Color(0xFF120A0A))))
            .border(1.dp, Brush.linearGradient(listOf(GoldDark, GoldLight, GoldDark)), shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Podium("2", 34.dp)
            Podium("1", 50.dp)
            Podium("3", 26.dp)
        }
    }
}

@Composable
private fun Podium(rank: String, height: Dp) {
    Box(
        modifier = Modifier
            .width(26.dp)
            .height(height)
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            .background(Brush.verticalGradient(listOf(GoldLight, GoldDark))),
        contentAlignment = Alignment.Center,
    ) {
        Text(rank, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun PrizeBar(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF14110A))
            .border(1.dp, Gold.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(GoldLight, GoldDark))),
            contentAlignment = Alignment.Center,
        ) { Text("★", color = Color.Black, fontSize = 16.sp) }
        Spacer(Modifier.width(10.dp))
        Text("0", color = GoldLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            "total hadiah undian\nberhadiah  ›",
            color = Color(0xFFD9D9D9),
            fontSize = 12.sp,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun FilterChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Hot", "New").forEachIndexed { i, label ->
            val active = i == selected
            val shape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) GoldBrush else Brush.horizontalGradient(listOf(Color(0xFF1B1B1B), Color(0xFF1B1B1B))))
                    .border(1.dp, Gold.copy(alpha = 0.6f), shape)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) Color.Black else GoldLight, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RoomCard(room: Room, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1E1E1E), Color(0xFF0E0E0E))))
                .border(1.dp, Brush.linearGradient(listOf(GoldDark, GoldLight, GoldDark)), shape),
        ) {
            // Area foto room dikosongkan dulu.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("● ${room.online}", color = Color(0xFF4CAF50), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            room.name,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            room.owner,
            color = Color(0xFF9A9A9A),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyBlock(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF8A8A8A), fontSize = 14.sp)
    }
}
