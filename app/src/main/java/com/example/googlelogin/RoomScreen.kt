package com.example.googlelogin

import com.google.firebase.auth.FirebaseAuth
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.roundToInt

private const val P_NONE = 0

private const val P_POWER = 1

private const val P_TOOLS = 2

private const val P_MODE = 3

private const val P_SETTINGS = 4

private const val P_LOCK = 5

private const val P_CLEAR = 6

private const val P_MUSIC = 7

private const val P_BLACKLIST = 8

private val Teal = Color(0xFF2BD9C4)

@Composable
fun RoomScreen(
    roomId: String,
    myUid: String,
    myName: String,
    myAvatarB64: String?,
    onExit: () -> Unit,
    onMinimize: () -> Unit,
) {
    val context = LocalContext.current
    var myFam by remember(myUid) { mutableStateOf("") }
    LaunchedEffect(myUid) {
        myFam = try { UserDirectory.findByUid(myUid)?.fam ?: "" } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }
    }
    val scope = rememberCoroutineScope()
    val appActions = LocalActions.current

    var room by remember { mutableStateOf<RoomInfo?>(null) }
    var members by remember { mutableStateOf<Set<String>>(emptySet()) }
    var locks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var rawSeats by remember { mutableStateOf<Map<Int, Seat>>(emptyMap()) }
    var micPoints by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var followed by remember { mutableStateOf(false) }
    var showRank by remember { mutableStateOf(false) }
    val seats = rawSeats.filterValues { it.uid in members }
    var messages by remember { mutableStateOf<List<RoomMsg>>(emptyList()) }
    var panel by remember { mutableStateOf(P_NONE) }
    var seatSheet by remember { mutableStateOf<Int?>(null) }
    var waveOpen by remember { mutableStateOf(false) }
    var seatInvite by remember { mutableStateOf<Int?>(null) }
    var blacklist by remember { mutableStateOf<Set<String>>(emptySet()) }
    var roomMuted by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var emoteOpen by remember { mutableStateOf(false) }
    var giftOpen by remember { mutableStateOf(false) }
    var giftTarget by remember { mutableStateOf<String?>(null) }
    var emotes by remember { mutableStateOf<Map<String, SeatEmote>>(emptyMap()) }
    var customEmotes by remember { mutableStateOf<List<RoomEmote>>(emptyList()) }
    LaunchedEffect(roomId) { customEmotes = RoomEmotes.load() }
    var draft by remember { mutableStateOf("") }
    var profileUid by remember { mutableStateOf<String?>(null) }
    var clearedAt by remember { mutableStateOf(0L) }
    var sendTick by remember { mutableStateOf(0) }
    val toast = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }

    // Hapus semua draft/state sementara (ketikan chat, panel gift, emot, dll)
    fun resetDrafts() {
        draft = ""
        typing = false
        emoteOpen = false
        giftOpen = false
        giftTarget = null
        seatSheet = null
        seatInvite = null
        profileUid = null
        panel = P_NONE
    }

    DisposableEffect(roomId) {
        resetDrafts()
        // Riwayat chat/gift lama tidak ditampilkan lagi saat masuk room (hanya pesan setelah masuk)
        clearedAt = System.currentTimeMillis()
        // Room dimuat dari Realtime Database (bebas kuota Firestore).
        val a = RoomRepo.observeRoom(roomId) { got -> if (got != null) room = got }
        val b = RoomRepo.observeSeats(roomId) { rawSeats = it }
        // Status follow room (RTDB, hemat kuota Firestore).
        val freg = Rtdb.listen(Rtdb.ref("followRooms/" + myUid + "/" + roomId)) { snap ->
            followed = snap.exists()
        }
        // Poin mic (RTDB): tampil real-time di bawah tiap kursi.
        val mp = Rtdb.listen(Rtdb.ref("micPoints/" + roomId)) { snap ->
            micPoints = snap.children.associate { c ->
                (c.key?.toIntOrNull() ?: -1) to (c.getValue(Long::class.java) ?: 0L)
            }
        }
        val c = RoomRepo.observeMessages(roomId) { messages = it }
        val d = RoomExtras.observeEmotes(roomId) { emotes = it }
        val e1 = RoomRepo.observeMembers(roomId) { members = it }
        val e2 = RoomRepo.observeLocks(roomId) { locks = it }
        val f = RoomRepo.observeBlacklist(roomId) { set ->
            blacklist = set
            if (myUid in set) {
                toast("Kamu dikeluarkan dari room ini")
                onExit()
            }
        }
        onDispose {
            resetDrafts()
            MusicEngine.reset()
            a.remove()
            b.remove()
            c.remove()
            d.remove()
            e1.remove()
            e2.remove()
            mp.remove()
            freg.remove()
            f.remove()
        }
    }

    // Penjaga spinner: kalau 2 detik room belum termuat, coba lagi berkali-kali lewat
    // getRoom (Realtime Database dulu, lalu Firestore) sampai dapat.
    LaunchedEffect(roomId) {
        var tries = 0
        while (room == null && tries < 8) {
            kotlinx.coroutines.delay(if (tries == 0) 2000L else 3000L)
            if (room != null) break
            val got = try { RoomRepo.getRoom(roomId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (got != null && room == null) room = got
            tries++
        }
    }

    BackHandler {
        when {
            giftOpen -> giftOpen = false
            typing -> typing = false
            emoteOpen -> emoteOpen = false
            seatInvite != null -> seatInvite = null
            seatSheet != null -> seatSheet = null
            panel != P_NONE -> panel = P_NONE
            else -> onMinimize()
        }
    }
    val r = room?.copy(online = members.size, lockedSeats = locks.toList())

    Box(Modifier.fillMaxSize().blockTouches()) {
        RoyalBackground()
        if (r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Gold) }
            Text(
                "Keluar",
                color = GoldLight,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).clickable { onExit() },
            )
            return@Box
        }
        val bg = remember(r.backgroundB64) { ProfileRepo.decodeB64(r.backgroundB64) }
        if (bg != null) {
            Image(bitmap = bg, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        }
        val isOwner = r.ownerUid == myUid
        // Admin aplikasi (developer) juga boleh mengelola semua room.
        val isDevAdmin = FirebaseAuth.getInstance().currentUser?.email.equals("developerex100@gmail.com", ignoreCase = true)
        val canManage = isOwner || isDevAdmin
        var ownerUserId by remember(r.ownerUid) {
            mutableStateOf(
                if (r.ownerUid == myUid) UserDirectory.cachedId(context, myUid) else UserCache.peek(r.ownerUid)?.userId,
            )
        }
        LaunchedEffect(r.ownerUid) {
            if (ownerUserId == null) {
                ownerUserId = (if (r.ownerUid == myUid) UserDirectory.cachedId(context, myUid) else UserCache.byUid(r.ownerUid)?.userId)
                // FIX code room dihapus: ID user TIDAK boleh diganti kode room.
                // Kalau cache ID kosong, ambil ID asli dari profil publik RTDB.
                if (ownerUserId == null) {
                    try {
                        val pid = UserDirectory.officialIdOf(r.ownerUid)
                        if (!pid.isNullOrBlank()) ownerUserId = pid
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) { }
                }
            }
        }
        val mySeat = seats.values.firstOrNull { it.uid == myUid }
        val roomBmp = remember(r.photoB64) { ProfileRepo.decodeB64(r.photoB64) }
        val cols = micColumns(r.micCount)
        val rows = (r.micCount + cols - 1) / cols
        val seatSize = if (cols == 4) 54.dp else 48.dp
        val listState = rememberLazyListState()
        val visible = messages.filter { clearedAt == 0L || it.createdAt > clearedAt }
        val total = visible.size + (if (r.announcement.isNotBlank()) 1 else 0)
        val lastMsgId = visible.lastOrNull()?.id
        LaunchedEffect(lastMsgId, sendTick) {
            if (total > 0) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val nearBottom = lastVisible >= info.totalItemsCount - 3
                val mine = visible.lastOrNull()?.uid == myUid
                if (mine || nearBottom) listState.animateScrollToItem(total - 1)
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RmAvatar(roomBmp, r.name, 38.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        // Nama room tampil utuh: satu baris, kapsul melebar penuh.
                        Text(
                            r.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Visible, softWrap = false,
                        )
                        Text(
                            "ID:" + (ownerUserId ?: "..."),
                            color = Color(0xFFBDBDBD), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Visible, softWrap = false,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Spacer(Modifier.width(6.dp))
                // Follow room: simpan di RTDB.
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable {
                            val nf = !followed
                            followed = nf
                            scope.launch {
                                try {
                                    if (nf) Rtdb.ref("followRooms/" + myUid + "/" + roomId).setValue(true)
                                    else Rtdb.ref("followRooms/" + myUid + "/" + roomId).removeValue()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) { }
                            }
                            toast(if (nf) "Room diikuti" else "Batal mengikuti room")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (followed) "✓" else "+",
                        color = if (followed) Teal else Gold,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "👤 ${r.online}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(8.dp))
                // Bagikan room: link membuka app langsung ke room ini.
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable {
                            // Bagikan room lewat LINK room (rezo://room/<id>): penerima
                            // tinggal buka linknya, app otomatis masuk room ini.
                            // Fitur kode room sudah dihapus.
                            val shareText =
                                "Ayo ngobrol bareng di room " + r.name + " di REZO CHAT VOICE! \uD83D\uDD25\n" +
                                "Masuk room ini lewat link: rezo://room/" + r.id
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(send, "Bagikan room"))
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("🔗", fontSize = 16.sp) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(0x66000000)).clickable { panel = P_POWER },
                    contentAlignment = Alignment.Center,
                ) { PowerIcon(Modifier.size(20.dp)) }
            }

            // Peringkat room: baris kecil di bawah nama room.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x66000000))
                    .clickable { showRank = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painterResource(R.drawable.ic_glory_medal), null,
                    Modifier.size(16.dp), contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(8.dp))
                Text("Peringkat room", color = GoldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("🏆 Top room ›", color = Color(0xFFBDBDBD), fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))

            // Kursi mic
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until cols) {
                            val i = row * cols + col
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (i < r.micCount) {
                                    RmSeatView(
                                        i,
                                        seats[i],
                                        seatSize,
                                        locked = r.lockedSeats.contains(i),
                                        emote = seats[i]?.uid?.let { emotes[it] },
                                        points = micPoints[i] ?: 0L,
                                    ) { seatSheet = i }
                                }
                            }
                        }
                    }
                }
            }

            // Pengumuman dan notifikasi masuk
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (r.announcement.isNotBlank()) {
                    item { RmMsgBox("Pengumuman kamar: ${r.announcement}", Gold) }
                }
                items(visible, key = { it.id }) { m ->
                    if (m.uid.isNotEmpty()) {
                        RmChatRow(m, myUid) { uid -> profileUid = uid }
                    } else {
                        RmMsgBox(m.text, if (m.text.startsWith("🎁")) Gold else Color.White)
                    }
                }
            }

            // Bar bawah
            if (emoteOpen) {
                RoomEmoteBar(emotes = customEmotes, onPick = { e ->
                        emoteOpen = false
                        if (mySeat == null) {
                            toast("Naik mic dulu untuk kirim emot")
                        } else {
                            scope.launch { RoomExtras.sendEmote(r.id, myUid, e)?.let { toast(it) } }
                        }
                })
            }
            if (typing) {
                RoomTypingBar(
                    text = draft,
                    myFam = myFam,
                    onTextChange = { draft = it },
                    onSend = { t ->
                        sendTick++
                        scope.launch { RoomExtras.sendChat(r.id, myUid, myName, t)?.let { toast(it) } }
                    },
                    onClose = { typing = false },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RmRoundBtn({ typing = true; emoteOpen = false }) { Text("💬", fontSize = 20.sp) }
                    Spacer(Modifier.width(10.dp))
                    RmRoundBtn({ emoteOpen = !emoteOpen }) { Text("😊", fontSize = 20.sp) }

                    Spacer(Modifier.weight(1f))
                    RmRoundBtn({ giftTarget = null; giftOpen = true; emoteOpen = false }) {
                        Image(painterResource(R.drawable.ic_room_gift), null, Modifier.size(24.dp), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.weight(1f))
                    if (mySeat != null) {
                        RmRoundBtn({ scope.launch { RoomRepo.setSeatMuted(r.id, mySeat.index, !mySeat.muted) } }) {
                            Image(
                                painterResource(if (mySeat.muted) R.drawable.ic_mute_mic else R.drawable.ic_open_mic),
                                null, Modifier.size(24.dp), contentScale = ContentScale.Fit,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    RmRoundBtn({ panel = P_TOOLS }) { GridIcon(Modifier.size(24.dp)) }
                }
            }
        }

        // Game milik sendiri (digambar di dalam app, aman untuk Android 8+)
        if (waveOpen) {
            GamesHub(myUid = myUid, roomId = roomId, onClose = { waveOpen = false })
        }

        // Dialog seat mic
        seatSheet?.let { idx ->
            val s = seats[idx]
            if (s != null) {
                // Mic terisi: langsung tampilkan sheet profil (tap -> profil lengkap)
                UserProfileSheet(
                    uid = s.uid,
                    myUid = myUid,
                    onDismiss = { seatSheet = null },
                    onChat = { u -> seatSheet = null; appActions.openChat(u) },
                    onFollowChanged = { },
                    onOpenFull = { seatSheet = null; appActions.openFullProfile(s.uid) },
                    topAction = if (canManage && s.uid != myUid) {
                        {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                Text("⋮", color = GoldLight, fontSize = 26.sp, modifier = Modifier.padding(6.dp).clickable { menu = true })
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF1E1A16)) {
                                    DropdownMenuItem(
                                        text = { Text(tr("Turunkan dari mic"), color = Color.White) },
                                        onClick = {
                                            menu = false
                                            seatSheet = null
                                            scope.launch {
                                                val err = RoomRepo.removeFromSeat(r.id, idx)
                                                if (err != null) toast(err) else toast("${s.name} diturunkan dari mic")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("Kick dari room"), color = Color(0xFFFF6B6B)) },
                                        onClick = {
                                            menu = false
                                            seatSheet = null
                                            scope.launch {
                                                val err = RoomRepo.kickFromRoom(r.id, s.uid)
                                                if (err != null) {
                                                    toast(err)
                                                } else {
                                                    toast("${s.name} dikeluarkan dari room")
                                                    RoomExtras.sendChat(r.id, myUid, myName, "🦾 ${s.name} dikeluarkan dari room")
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("Kunci mic"), color = Color.White) },
                                        onClick = {
                                            menu = false
                                            seatSheet = null
                                            scope.launch { RoomExtras.lockSeat(r.id, idx)?.let { toast(it) } }
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                    extra = {
                        if (s.uid == myUid) {
                            ProfileSheetAction(tr("Turun Mic"), "⬇") {
                                seatSheet = null
                                scope.launch { RoomRepo.leaveSeat(r.id, idx) }
                            }
                            ProfileSheetAction(if (s.muted) "Buka mic" else "Matikan mic", if (s.muted) "🎤" else "🔇") {
                                scope.launch { RoomRepo.setSeatMuted(r.id, idx, !s.muted) }
                            }
                        }
                        ProfileSheetAction(tr("Hadiah"), "🎁") {
                            seatSheet = null
                            giftTarget = s.uid
                            giftOpen = true
                        }
                    },
                )
            } else {
                RmPanel(onDismiss = { seatSheet = null }) {
                    Text(tr("Seat mic"), color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    val seatLocked = r.lockedSeats.contains(idx)
                    Text(if (idx == 0) "Kursi MC masih kosong" else "Kursi No.${idx + 1} masih kosong", color = Color(0xFFBDBDBD))
                    if (seatLocked) {
                        Spacer(Modifier.height(8.dp))
                        Text(tr("🔒 Mic ini dikunci"), color = Gold)
                    }
                    Spacer(Modifier.height(16.dp))
                    if (seatLocked) {
                        if (canManage) {
                            GoldButton(tr("Buka kunci mic")) {
                                seatSheet = null
                                scope.launch { RoomExtras.unlockSeat(r.id, idx)?.let { toast(it) } }
                            }
                        }
                    } else {
                        GoldButton(if (mySeat == null) "Naik kursi mic" else "Pindah ke kursi ini") {
                            seatSheet = null
                            scope.launch { RoomRepo.takeSeat(r.id, idx, myUid, myName, myAvatarB64)?.let { toast(it) } }
                        }
                        if (canManage) {
                            Spacer(Modifier.height(10.dp))
                            OutlineButton(tr("Undang ke mic")) {
                                seatSheet = null
                                seatInvite = idx
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlineButton(tr("Kunci mic")) {
                                seatSheet = null
                                scope.launch { RoomExtras.lockSeat(r.id, idx)?.let { toast(it) } }
                            }
                        }
                    }
                }
            }
        }

        seatInvite?.let { idx ->
            SeatInviteDialog(
                members = members,
                seatedUids = seats.values.map { it.uid }.toSet(),
                index = idx,
                myUid = myUid,
                onDismiss = { seatInvite = null },
                onInvite = { u ->
                    seatInvite = null
                    scope.launch {
                        toast(RoomRepo.inviteSeat(r.id, idx, u.uid, u.name, u.avatarB64) ?: "${u.name} naik ke mic")
                    }
                },
            )
        }

        if (panel == P_MUSIC) {
            MusicSheet(
                roomId = r.id,
                myUid = myUid,
                myName = myName,
                canPlay = mySeat != null,
                onUnmute = { mySeat?.let { s2 -> scope.launch { RoomRepo.setSeatMuted(r.id, s2.index, false) } } },
                onDismiss = { panel = P_NONE },
            )
        }

        profileUid?.let { pu ->
            UserProfileSheet(
                uid = pu,
                myUid = myUid,
                onDismiss = { profileUid = null },
                onChat = { u -> profileUid = null; appActions.openChat(u) },
                onFollowChanged = { },
                onOpenFull = { profileUid = null; appActions.openFullProfile(pu) },
            )
        }

        if (giftOpen) {
            GiftPanel(
                roomId = r.id,
                myUid = myUid,
                myName = myName,
                onDismiss = { giftOpen = false },
                seats = seats.values.sortedBy { it.index },
                initialTarget = giftTarget,
                myAvatarB64 = myAvatarB64,
            )
        }
        GiftBanner(r.id)
        VipEntryEffect(r.id)
        GiftFxOverlay(r.id)

        // Tombol power: keluar / minimalkan
        if (showRank) {
            TopRankDialog(onDismiss = { showRank = false }, initialTab = 0, onOpenRoom = {})
        }

        if (panel == P_POWER) {
            Dialog(onDismissRequest = { panel = P_NONE }) {
                DialogCard {
                    Text(tr("Keluar dari room?"), color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    GoldButton(tr("Keluar")) { resetDrafts(); onExit() }
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(tr("Minimalkan")) { panel = P_NONE; onMinimize() }
                }
            }
        }

        // Kotak 4: alat dasar
        if (panel == P_TOOLS) {
            RmPanel(onDismiss = { panel = P_NONE }) {
                Text(tr("Alat dasar"), color = Color.White, fontSize = 22.sp)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RmTool(
                        if (roomMuted) "Aktifkan suara" else "Matikan suara",
                        onClick = {
                            roomMuted = !roomMuted
                            AgoraVoice.muteRoom(roomMuted)
                            toast(if (roomMuted) "Suara room dimatikan" else "Suara room aktif")
                            panel = P_NONE
                        },
                    ) { Text(if (roomMuted) "🔇" else "🔊", fontSize = 24.sp) }
                    if (isOwner) {
                        RmTool("Mode kamar", onClick = { panel = P_MODE }) { GridIcon(Modifier.size(26.dp)) }
                        RmTool("Pengaturan", onClick = { panel = P_SETTINGS }) {
                            Image(painterResource(R.drawable.ic_settings), null, Modifier.size(24.dp), contentScale = ContentScale.Fit)
                        }
                        RmTool(if (r.locked) "Kamar terkunci" else "Kamar umum", onClick = { panel = P_LOCK }) {
                            Icon(Icons.Default.Lock, null, tint = GoldLight)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isOwner) {
                        RmTool(
                            "Clear poin mic",
                            onClick = {
                                panel = P_NONE
                                scope.launch {
                                    Rtdb.ref("micPoints/" + r.id).removeValue()
                                    toast("Poin mic dihapus")
                                }
                            },
                        ) { Text("💗", fontSize = 24.sp) }
                    }
                    RmTool("Musik", onClick = { panel = P_MUSIC }) { Text("🎵", fontSize = 24.sp) }
                    RmTool("Game", onClick = { panel = P_NONE; waveOpen = true }) {
                        Image(painterResource(R.drawable.ic_room_game), null, Modifier.size(26.dp), contentScale = ContentScale.Fit)
                    }
                    RmTool(
                        "Bersihkan chat",
                        onClick = {
                            clearedAt = messages.maxOfOrNull { it.createdAt } ?: System.currentTimeMillis()
                            panel = P_NONE
                            toast("Chat dibersihkan di layar kamu")
                        },
                    ) { Text("🧹", fontSize = 24.sp) }
                    if (isOwner) {
                        RmTool("Hapus chat semua", onClick = { panel = P_CLEAR }) { Text("🗑", fontSize = 24.sp) }
                    }
                    if (isOwner) {
                        RmTool("Daftar hitam", onClick = { panel = P_BLACKLIST }) { Text("🚫", fontSize = 24.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (panel == P_CLEAR) {
            Dialog(onDismissRequest = { panel = P_NONE }) {
                DialogCard {
                    Text(tr("Hapus semua chat publik?"), color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Chat akan terhapus untuk semua orang di room ini."), color = Color(0xFFBDBDBD), fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    GoldButton(tr("Hapus")) {
                        panel = P_NONE
                        scope.launch { toast(RoomRepo.clearMessages(r.id) ?: "Chat publik dihapus") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(tr("Batal")) { panel = P_NONE }
                }
            }
        }

        if (panel == P_BLACKLIST) {
            var bUsers by remember(blacklist) { mutableStateOf<Map<String, PublicUser?>>(emptyMap()) }
            LaunchedEffect(blacklist) {
                val m = HashMap<String, PublicUser?>()
                for (uid in blacklist) {
                    m[uid] = try {
                        UserCache.byUid(uid)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                bUsers = m
            }
            RmPanel(onDismiss = { panel = P_NONE }) {
                Text(tr("Daftar hitam"), color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text(tr("User di daftar ini tidak bisa masuk room sampai kick-nya dibuka."), color = Color(0xFFBDBDBD), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                if (blacklist.isEmpty()) {
                    Text(tr("Belum ada user yang di-kick"), color = Color(0xFFBDBDBD))
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(blacklist.size) { i ->
                            val uid = blacklist.elementAt(i)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                InvAvatar(bUsers[uid], uid)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(bUsers[uid]?.name ?: "UID $uid", color = Color.White, fontSize = 15.sp, maxLines = 1)
                                    Text(bUsers[uid]?.userId ?: "", color = Color(0xFF9A9A9A), fontSize = 12.sp)
                                }
                                OutlineButton(tr("Buka kick")) {
                                    scope.launch { toast(RoomRepo.unbanFromRoom(r.id, uid) ?: "User boleh masuk room lagi") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (panel == P_MODE) RmPanel(onDismiss = { panel = P_NONE }) { RmMode(r) { panel = P_NONE } }
        if (panel == P_SETTINGS) RmPanel(onDismiss = { panel = P_NONE }) { RmSettings(r) { panel = P_NONE } }
        if (panel == P_LOCK) RmPanel(onDismiss = { panel = P_NONE }) { RmLock(r) { panel = P_NONE } }
    }
}

@Composable
private fun RmAvatar(bmp: ImageBitmap?, fallback: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF1E3A34)), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(fallback.take(1).uppercase(), color = GoldLight, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
        }
    }
}

@Composable
private fun RmSeatView(index: Int, seat: Seat?, size: Dp, locked: Boolean = false, emote: SeatEmote? = null, points: Long = 0L, onClick: () -> Unit) {
    val bmp = remember(seat?.avatarB64) { ProfileRepo.decodeB64(seat?.avatarB64) }
    var seatUser by remember(seat?.uid) { mutableStateOf(seat?.uid?.let { UserCache.peek(it) }) }
    LaunchedEffect(seat?.uid) {
        val id = seat?.uid
        if (id != null) {
            // Selalu segarkan (cache 5 menit, tidak menimpa data bagus dengan yang kosong),
            // supaya frame di kursi tidak hilang / telat.
            UserCache.byUid(id)?.let { seatUser = it }
        }
    }
    val seatFrame = if (seat != null) frameAssetOf(seatUser?.frame, seatUser?.vip ?: 0, seatUser?.vipUntil ?: 0L) else null
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(size).clip(CircleShape)
                .then(
                    if (seat != null) {
                        if (seatFrame != null) Modifier.background(Color(0xFF0F3D33))
                        else Modifier.background(Color(0xFF0F3D33)).border(2.dp, Teal, CircleShape)
                    } else Modifier
                ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    seat != null && bmp != null ->
                    Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    seat != null ->
                    Text(seat.name.take(1).uppercase(), color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    seat == null && locked ->
                        Image(painterResource(R.drawable.ic_lock_mic), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    index == 0 ->
                        Image(painterResource(R.drawable.ic_seat_mic), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else ->
                        Image(painterResource(R.drawable.ic_seat_mic), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                if (seat != null && seat.muted) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(Color(0xCC000000)),
                        contentAlignment = Alignment.Center,
                    ) { Image(painterResource(R.drawable.ic_mute_mic), null, Modifier.size(12.dp), contentScale = ContentScale.Fit) }
                }
            }
            seatFrame?.let { FrameOverlay(it, (size.value * 1.3f).dp) }
            // Emote digambar SETELAH bingkai supaya tampil di depan frame,
            // bukan di belakangnya.
            var emoteShow by remember { mutableStateOf(false) }
            LaunchedEffect(emote?.at) {
                if (emote != null && abs(System.currentTimeMillis() - emote.at) < 20000) {
                    emoteShow = true
                    delay(3000)
                    emoteShow = false
                }
            }
            if (emoteShow && emote != null) {
                if (emote.emoji.startsWith("http")) {
                    // Emote kustom (gif/png/jpg): seukuran foto profil di mic.
                    AsyncImage(
                        model = emote.emoji,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.Center).size(size),
                    )
                } else {
                    Text(emote.emoji, fontSize = (size.value * 0.6f).sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        VipNameByUid(
            seat?.name ?: (if (index == 0) "MC" else "No.${index + 1}"),
            seat?.uid,
            color = Color(0xFFBDBDBD),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(size + 8.dp),
        )
        Text("💗 " + fmtCompact(points), color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun RmMsgBox(text: String, color: Color) {
    Box(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, color = color, fontSize = 14.sp)
    }
}

@Composable
private fun RmRoundBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Color(0x66000000)).border(1.dp, Gold.copy(alpha = 0.4f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RmPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
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
            .background(Color(0xFF0B0B0B))
            .blockTouches()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun RmTool(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(Modifier.width(80.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF1C1C1C)), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xFFD0D0D0), fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun RmAction(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF1C1C1C)).border(1.dp, Gold.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xFFD0D0D0), fontSize = 12.sp)
    }
}

@Composable
private fun RmStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = Color(0xFF9A9A9A), fontSize = 12.sp)
    }
}

@Composable
private fun RmSeatProfile(
    seat: Seat,
    isMe: Boolean,
    isOwner: Boolean,
    onLeave: () -> Unit,
    onToggleMic: () -> Unit,
    onKick: () -> Unit,
) {
    val bmp = remember(seat.avatarB64) { ProfileRepo.decodeB64(seat.avatarB64) }
    val actions = LocalActions.current
    var counts by remember(seat.uid) { mutableStateOf(0L to 0L) }
    LaunchedEffect(seat.uid) {
        try {
            counts = FollowRepo.counts(seat.uid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.clickable { actions.openProfile(seat.uid) }, horizontalAlignment = Alignment.CenterHorizontally) {
            var sheetUser by remember(seat.uid) { mutableStateOf(UserCache.peek(seat.uid)) }
            LaunchedEffect(seat.uid) { if (sheetUser == null) sheetUser = UserCache.byUid(seat.uid) }
            val sheetFrame = frameAssetOf(sheetUser?.frame, sheetUser?.vip ?: 0, sheetUser?.vipUntil ?: 0L)
            Box(Modifier.padding(bottom = 8.dp).size(76.dp), contentAlignment = Alignment.Center) {
                RmAvatar(bmp, seat.name, 76.dp)
                sheetFrame?.let { FrameOverlay(it, 104.dp) }
            }
            Spacer(Modifier.height(8.dp))
            VipNameByUid(seat.name, seat.uid, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(tr("Lihat profil ›"), color = Gold, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            RmStat("${counts.second}", "Followers", Modifier.weight(1f))
            RmStat("${counts.first}", "Follow", Modifier.weight(1f))
            RmStat("0", "Level", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        if (isMe) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RmAction(tr("Turun mic"), onLeave) { Icon(Icons.Default.KeyboardArrowDown, null, tint = GoldLight) }
                RmAction(if (seat.muted) "Buka mic" else "Matikan mic", onToggleMic) { MicIcon(!seat.muted) }
            }
        } else if (isOwner) {
            GoldButton(tr("Turunkan dari kursi"), onClick = onKick)
        }
    }
}

@Composable
private fun RmMiniSeats(count: Int, selected: Boolean, modifier: Modifier) {
    val cols = micColumns(count)
    val rows = (count + cols - 1) / cols
    Canvas(modifier) {
        val cw = size.width / cols
        val ch = size.height / rows
        val rad = minOf(cw, ch) * 0.3f
        for (i in 0 until count) {
            drawCircle(
                if (selected) Teal else Color(0xFF6B6B6B),
                rad,
                Offset(cw * (i % cols + 0.5f), ch * (i / cols + 0.5f)),
            )
        }
    }
}

@Composable
private fun RmMode(r: RoomInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sel by remember { mutableStateOf(r.micCount) }
    Text(tr("Pilih mode party"), color = Color.White, fontSize = 22.sp)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(Color(0xFF1C1C1C)).border(2.dp, Gold, CircleShape), contentAlignment = Alignment.Center) {
                Text("😎", fontSize = 28.sp)
            }
            Text(tr("Obrolan"), color = Color.White, fontSize = 13.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(Color(0xFF1C1C1C)), contentAlignment = Alignment.Center) {
                Text("🛋", fontSize = 28.sp)
            }
            Text(tr("Mode MC acara"), color = Color(0xFF6B6B6B), fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(tr("Jumlah mic"), color = Color.White, fontSize = 18.sp)
    Text(tr("Posisi kursi host akan berubah jika mengganti jumlah mic"), color = Color(0xFF8A8A8A), fontSize = 12.sp)
    listOf(8, 10, 12, 15, 16, 20).chunked(3).forEach { rowItems ->
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowItems.forEach { n ->
                val on = sel == n
                Column(
                    Modifier.weight(1f).clickable {
                        sel = n
                        scope.launch {
                            RoomRepo.setMicCount(r.id, n)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RmMiniSeats(
                        n,
                        on,
                        Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1C))
                        .border(2.dp, if (on) Teal else Color(0xFF333333), RoundedCornerShape(10.dp)).padding(8.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$n",
                        color = if (on) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Teal else Color(0xFF222222)).padding(horizontal = 16.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(Color(0xFF00D1B2)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) { Text(tr("Konfirmasi"), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
private fun RmSettings(r: RoomInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(r.name) }
    var ann by remember { mutableStateOf(r.announcement) }
    var photo by remember { mutableStateOf<String?>(null) }
    var bg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val photoBmp = remember(photo, r.photoB64) { ProfileRepo.decodeB64(photo ?: r.photoB64) }
    val bgBmp = remember(bg, r.backgroundB64) { ProfileRepo.decodeB64(bg ?: r.backgroundB64) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val b = ProfileRepo.encodeImage(context, uri, 320, square = true, quality = 85)
                busy = false
                if (b != null) photo = b
            }
        }
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val b = ProfileRepo.encodeImage(context, uri, 900, square = false, quality = 72)
                busy = false
                if (b != null) bg = b
            }
        }
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    Text(tr("Pengaturan room"), color = Color.White, fontSize = 22.sp)
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF1E1E1E)).border(2.dp, Gold, CircleShape)
                .clickable(enabled = !busy) { photoPicker.launch(imageOnly) },
                contentAlignment = Alignment.Center,
            ) {
                if (photoBmp != null) {
                    Image(bitmap = photoBmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("📷", fontSize = 26.sp)
                }
            }
            Text(tr("Foto room"), color = Color(0xFF9A9A9A), fontSize = 12.sp)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1E1E))
                .border(1.dp, Gold, RoundedCornerShape(12.dp)).clickable(enabled = !busy) { bgPicker.launch(imageOnly) },
                contentAlignment = Alignment.Center,
            ) {
                if (bgBmp != null) {
                    Image(bitmap = bgBmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("🖼", fontSize = 26.sp)
                }
            }
            Text(tr("Background room"), color = Color(0xFF9A9A9A), fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= 30) name = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(tr("Nama room")) },
        shape = RoundedCornerShape(12.dp),
        colors = goldFieldColors(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = ann,
        onValueChange = { if (it.length <= 200) ann = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        placeholder = { Text(tr("Pengumuman room")) },
        shape = RoundedCornerShape(12.dp),
        colors = goldFieldColors(),
    )
    Spacer(Modifier.height(16.dp))
    GoldButton(tr("Konfirmasi"), enabled = name.isNotBlank() && !busy) {
        scope.launch {
            busy = true
            val err = RoomRepo.updateSettings(r.id, name.trim(), photo, bg, ann.trim())
            busy = false
            Toast.makeText(context, err ?: "Tersimpan", Toast.LENGTH_SHORT).show()
            if (err == null) onClose()
        }
    }
}

@Composable
private fun RmLock(r: RoomInfo, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    Text(if (r.locked) "Kamar terkunci" else "Kunci kamar dengan password", color = Color.White, fontSize = 20.sp)
    Spacer(Modifier.height(12.dp))
    if (r.locked) {
        Text(tr("Pengguna lain harus memasukkan password untuk masuk."), color = Color(0xFF9A9A9A), fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        GoldButton(tr("Jadikan publik")) {
            scope.launch {
                val e = RoomRepo.setLock(r.id, null)
                Toast.makeText(context, e ?: "Kamar sekarang publik", Toast.LENGTH_SHORT).show()
                if (e == null) onClose()
            }
        }
    } else {
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(tr("Password (min. 4 karakter)")) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp),
            colors = goldFieldColors(),
        )
        Spacer(Modifier.height(16.dp))
        GoldButton(tr("Kunci kamar"), enabled = pw.length >= 4) {
            scope.launch {
                val e = RoomRepo.setLock(r.id, pw)
                Toast.makeText(context, e ?: "Kamar dikunci", Toast.LENGTH_SHORT).show()
                if (e == null) onClose()
            }
        }
    }
}

/** Bulatan kecil mengambang saat room diminimalkan. Bisa digeser, klik untuk masuk lagi. */
@Composable
fun FloatingRoomBubble(room: RoomInfo, onClick: () -> Unit) {
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val sizePx = with(density) { 60.dp.toPx() }
    val maxX = with(density) { cfg.screenWidthDp.dp.toPx() } - sizePx
    val maxY = with(density) { cfg.screenHeightDp.dp.toPx() } - sizePx
    var x by remember { mutableStateOf(maxX - 24f) }
    var y by remember { mutableStateOf(maxY * 0.6f) }
    val bmp = remember(room.photoB64) { ProfileRepo.decodeB64(room.photoB64) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(60.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E1E1E))
            .border(2.dp, Gold, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    x = (x + drag.x).coerceIn(0f, maxX)
                    y = (y + drag.y).coerceIn(0f, maxY)
                }
            }
            .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("🎙", fontSize = 26.sp)
            }
        }
    }
}

/** Baris chat publik: avatar, nama, level, lalu isi pesan. Klik avatar/nama untuk buka profil. */
@Composable
private fun RmChatRow(m: RoomMsg, myUid: String, onProfile: (String) -> Unit) {
    // FIX avatar kosong di chat room: pesan SENDIRI langsung pakai profil lokal
    // (foto, nama, frame, level) tanpa nunggu jaringan; orang lain dari cache.
    var u by remember(m.uid) {
        mutableStateOf(UserCache.peek(m.uid) ?: if (m.uid == myUid) UserCache.meLocal(m.uid) else null)
    }
    LaunchedEffect(m.uid) { if (u == null || u?.avatarB64 == null) u = UserCache.byUid(m.uid) ?: u }
    val shownName = u?.name?.takeIf { it.isNotBlank() } ?: m.name
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PublicAvatarFramed(u, 38.dp, fallback = shownName, modifier = Modifier.clickable { onProfile(m.uid) })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Row(Modifier.clickable { onProfile(m.uid) }, verticalAlignment = Alignment.CenterVertically) {
                VipNameText(
                    shownName,
                    u?.vip ?: 0,
                    u?.vipUntil ?: 0L,
                    color = GoldLight,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Spacer(Modifier.width(6.dp))
                LevelBadge(userLevel(u))
                u?.let {
                    if (vipActive(it.vip, it.vipUntil)) {
                        Spacer(Modifier.width(4.dp))
                        VipTag(it.vip)
                    }
                }
                findItem(u?.badge)?.let {
                    Spacer(Modifier.width(4.dp))
                    StoreBadgeIcon(it, 24.dp)
                }
            }
            u?.let {
                if (it.medals.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    UserMedals(it.medals, 18.dp)
                }
            }
            Spacer(Modifier.height(3.dp))
            val bTier = vipBubbleTier(u?.bubble, u?.vip ?: 0, u?.vipUntil ?: 0L)
            val bAsset = if (bTier in 1..9) VipAssets.get(bTier, "bubble") else null
            Box(
                Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(12.dp))
                .then(if (bAsset == null) Modifier.background(bubbleColor(u?.bubble)) else Modifier),
            ) {
                if (bAsset != null) {
                    androidx.compose.runtime.key(bTier) {
                        VipMedia(bAsset, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                    }
                }
                Text(m.text, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}
