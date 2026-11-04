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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars

private const val P_NONE = 0

private const val P_POWER = 1

private const val P_TOOLS = 2

private const val P_MODE = 3

private const val P_SETTINGS = 4

private const val P_LOCK = 5

private const val P_CLEAR = 6

private const val P_MUSIC = 7

private const val P_BLACKLIST = 8

private const val P_MEMBERS = 9

private const val P_EFEK = 10

private val Teal = Color(0xFF2BD9C4)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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

    var room by remember { mutableStateOf<RoomInfo?>(null) }
    // --- Owner bisa mengeluarkan user: kalau keanggotaan kita hilang, keluar dari room.
    var joinedOnce by remember { mutableStateOf(false) }
    var kickedOut by remember { mutableStateOf(false) }
    LaunchedEffect(roomId, myUid) {
        val reg = Rtdb.listen(Rtdb.ref("members/" + roomId + "/" + myUid)) { snap ->
            if (snap.exists()) {
                joinedOnce = true
            } else if (joinedOnce && !kickedOut) {
                // Keanggotaan bisa hilang sendiri (jaringan putus-nyambung, login di dua
                // HP). Itu BUKAN kick. Kick hanya sah kalau kita benar-benar ada di daftar
                // hitam; kalau tidak, cukup tulis ulang keanggotaan kita.
                scope.launch {
                    val ban = try { RoomRepo.banOf(roomId, myUid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                    if (ban != null && room?.ownerUid != myUid) {
                        kickedOut = true
                    } else {
                        try { Rtdb.ref("members/" + roomId + "/" + myUid).setValue(true) } catch (e: Exception) { }
                    }
                }
            }
        }
        kotlinx.coroutines.delay(600000)
        reg.remove()
    }

    val appActions = LocalActions.current

    var members by remember { mutableStateOf<Set<String>>(emptySet()) }
    var locks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var rawSeats by remember { mutableStateOf<Map<Int, Seat>>(emptyMap()) }
    var micPoints by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var followed by remember { mutableStateOf(false) }
    var showRank by remember { mutableStateOf(false) }
    val seats = rawSeats.filterValues { it.uid in members }
    // Posisi avatar tiap kursi (untuk animasi gift terbang ke penerima).
    val seatPoints = remember { mutableStateMapOf<Int, Offset>() }
    var messages by remember { mutableStateOf<List<RoomMsg>>(emptyList()) }
    var panel by remember { mutableStateOf(P_NONE) }
    var seatSheet by remember { mutableStateOf<Int?>(null) }
    var waveOpen by remember { mutableStateOf(false) }
    var seatInvite by remember { mutableStateOf<Int?>(null) }
    var bans by remember { mutableStateOf<List<RoomRepo.RoomBan>>(emptyList()) }
    // Pilihan durasi saat menekan "Kick dari room": uid ke nama user.
    var kickPick by remember { mutableStateOf<Pair<String, String>?>(null) }
    var roomMuted by remember { mutableStateOf(false) }

    // Saklar efek (Kotak 4): matikan efek masuk room dan efek gift kalau ramai.
    // Disimpan per akun di Realtime Database supaya tetap sama saat buka room lain.
    val fxUid = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    // Pilihan dibaca dulu dari penyimpanan HP (langsung, tanpa menunggu jaringan),
    // lalu disamakan dengan Realtime Database. Jadi setelan yang dimatikan tidak
    // kembali menyala saat keluar room atau keluar aplikasi.
    val fxLocal = remember {
        context.getSharedPreferences("fx_prefs_" + fxUid, android.content.Context.MODE_PRIVATE)
    }
    var fxEntryOn by remember { mutableStateOf(fxLocal.getBoolean("entry", true)) }
    var fxGiftOn by remember { mutableStateOf(fxLocal.getBoolean("gift", true)) }
    // Banner efek gift di atas layar: bisa dimatikan dari room.
    var fxBannerOn by remember { mutableStateOf(fxLocal.getBoolean("banner", true)) }
    // Efek roket (peluncuran + ikon di sisi room) dan efek game (banner menang).
    var fxRocketOn by remember { mutableStateOf(fxLocal.getBoolean("rocket", true)) }
    var fxGameOn by remember { mutableStateOf(fxLocal.getBoolean("game", true)) }
    DisposableEffect(fxUid) {
        if (fxUid.isBlank()) {
            onDispose { }
        } else {
            val reg = Rtdb.listen(Rtdb.ref("fxPrefs/$fxUid")) { snap ->
                // Hanya timpa kalau server memang punya nilainya; kalau belum ada,
                // pakai pilihan terakhir di HP (jangan balik ke "nyala").
                if (snap.hasChild("entry")) snap.child("entry").getValue(Boolean::class.java)?.let { fxEntryOn = it; fxLocal.edit().putBoolean("entry", it).apply() }
                if (snap.hasChild("gift")) snap.child("gift").getValue(Boolean::class.java)?.let { fxGiftOn = it; fxLocal.edit().putBoolean("gift", it).apply() }
                if (snap.hasChild("banner")) snap.child("banner").getValue(Boolean::class.java)?.let { fxBannerOn = it; fxLocal.edit().putBoolean("banner", it).apply() }
                if (snap.hasChild("rocket")) snap.child("rocket").getValue(Boolean::class.java)?.let { fxRocketOn = it; fxLocal.edit().putBoolean("rocket", it).apply() }
                if (snap.hasChild("game")) snap.child("game").getValue(Boolean::class.java)?.let { fxGameOn = it; fxLocal.edit().putBoolean("game", it).apply() }
            }
            onDispose { reg.remove() }
        }
    }
    var typing by remember { mutableStateOf(false) }
    var emoteOpen by remember { mutableStateOf(false) }
    var giftOpen by remember { mutableStateOf(false) }
    var rocketOpen by remember { mutableStateOf(false) }
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
        val f = RoomRepo.observeBans(roomId) { list ->
            bans = list
            // Owner tidak pernah bisa ter-kick dari room miliknya sendiri.
            if (list.any { it.uid == myUid } && !kickedOut && room?.ownerUid != myUid) kickedOut = true
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
        // Pemilik room selalu boleh. Akun developer (pemilik aplikasi) tetap bisa
        // mengatur mic/kick di room mana pun; pengguna lain hanya di room miliknya.
        val isDevAdmin = FirebaseAuth.getInstance().currentUser?.email.equals("developerex100@gmail.com", ignoreCase = true)
        val canManage = isOwner || isDevAdmin
        val roomIdNow = r.id
        val myUidNow = myUid
        val myNameNow = myName

        // ---- kirim gambar dari galeri ----
    var imgBusy by remember { mutableStateOf(false) }
    var lihatGambar by remember { mutableStateOf<String?>(null) }
    val imgPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null || imgBusy) return@rememberLauncherForActivityResult
        imgBusy = true
        scope.launch {
            val (url, err) = RoomImages.upload(context, uri)
            imgBusy = false
            if (url == null) {
                toast(err ?: "Gagal mengunggah gambar")
            } else {
                val sisa = draft.trim()
                val e = RoomExtras.sendImage(roomIdNow, myUidNow, myNameNow, url, sisa)
                if (e == null) {
                    draft = ""
                    sendTick++
                } else {
                    toast(e)
                }
            }
        }
    }

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
        // Tab chat: false = Semua (user + gift + sistem), true = Obrolan (hanya ketikan user).
        var onlyChat by remember { mutableStateOf(false) }
        val visible = messages.filter {
            (clearedAt == 0L || it.createdAt > clearedAt) &&
                (!onlyChat || it.uid.isNotEmpty() || it.img.isNotBlank())
        }
        val total = visible.size + (if (r.announcement.isNotBlank() && !onlyChat) 1 else 0)
        val lastMsgId = visible.lastOrNull()?.id
        LaunchedEffect(onlyChat) {
            if (total > 0) listState.scrollToItem(total - 1)
        }
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
                            // Link https (bisa diklik di WhatsApp) yang meneruskan ke rezo://room/<id>
                            val shareText =
                                "Ayo ngobrol bareng di room " + r.name + " di REZO CHAT VOICE! \uD83D\uDD25\n" +
                                "Masuk room ini lewat link: https://exbottod.github.io/voice-chat-room-real-time/room.html?id=" + r.id
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
                            Box(
                                Modifier.weight(1f).onGloballyPositioned {
                                    // Titik tengah avatar kursi: tujuan gift terbang.
                                    val p = it.positionInRoot()
                                    seatPoints[i] = Offset(p.x + it.size.width / 2f, p.y + it.size.height / 2f)
                                },
                                contentAlignment = Alignment.Center,
                            ) {
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
            // Banner kemenangan game (terbang di atas daftar chat publik).
            if (fxGameOn) GameWinBanner()

            // Tab "Semua | Obrolan": bisa diklik atau digeser kiri/kanan di daftar chat.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((label, isChatTab) in listOf("Semua" to false, "Obrolan" to true)) {
                    val sel = onlyChat == isChatTab
                    Column(
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onlyChat = isChatTab }
                            .padding(end = 22.dp, top = 4.dp, bottom = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            tr(label),
                            color = if (sel) Color.White else Color(0xFF9A9A9A),
                            fontSize = 16.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        )
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier.width(22.dp).height(2.dp)
                                .background(if (sel) Color.White else Color.Transparent),
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .pointerInput(onlyChat) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = {
                                // Geser ke kiri -> Obrolan, geser ke kanan -> Semua.
                                if (dx < -80f) onlyChat = true
                                else if (dx > 80f) onlyChat = false
                            },
                            onDragCancel = { dx = 0f },
                            onHorizontalDrag = { _, amount -> dx += amount },
                        )
                    },
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (r.announcement.isNotBlank() && !onlyChat) {
                    item { RmMsgBox("Pengumuman kamar: ${r.announcement}", Gold) }
                }
                items(visible, key = { it.id }) { m ->
                    if (m.img.isNotBlank()) {
                        RmPhotoRow(m, myUid, onProfile = { uid -> profileUid = uid }, onOpen = { lihatGambar = it })
                    } else if (m.uid.isNotEmpty()) {
                        RmChatRow(m, myUid) { uid -> profileUid = uid }
                    } else {
                        RmMsgBox(m.text, if (m.text.startsWith("🎁")) Gold else if (m.text.startsWith("🚫")) Color(0xFFFF8A65) else Color.White)
                    }
                }
            }

                // Panel melayang sisi kanan: ikon roket + persen menuju level berikutnya.
                if (ROCKET_ROOM_UI && fxRocketOn) RocketRail(
                    roomId = r.id,
                    onOpen = { rocketOpen = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp, bottom = 10.dp),
                )
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
                    onPickImage = { if (!imgBusy) imgPicker.launch("image/*") },
                    imgBusy = imgBusy,
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

        // Lihat gambar ukuran penuh
        lihatGambar?.let { url ->
            Dialog(onDismissRequest = { lihatGambar = null }) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF101010)).clickable { lihatGambar = null },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = url,
                        contentDescription = "Gambar",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        // Game milik sendiri (digambar di dalam app, aman untuk Android 8+)
        if (waveOpen) {
            GamesHub(myUid = myUid, roomId = roomId, myName = myName, myAvatarB64 = myAvatarB64 ?: "", onClose = { waveOpen = false })
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
                                // Ikon pengaturan (gear) sebagai tombol menu aksi user:
                                // turunkan dari mic, mute, kick dari room.
                                Image(
                                    painterResource(R.drawable.ic_settings),
                                    contentDescription = "Aksi user",
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .clickable { menu = true }
                                        .padding(2.dp),
                                    contentScale = ContentScale.Fit,
                                )
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
                                            kickPick = s.uid to s.name
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
        // Tombol kombo bulat untuk gift cashback (klik terus untuk kirim ulang).
        GiftComboBar(myUid, myName)
        if (rocketOpen) RocketScreen(
            roomId = r.id,
            onDismiss = { rocketOpen = false },
            fxAktif = fxRocketOn,
            onNyalakanFx = {
                fxRocketOn = true
                try { fxLocal.edit().putBoolean("rocket", true).apply() } catch (e: Exception) { }
                try { if (fxUid.isNotBlank()) Rtdb.ref("fxPrefs/$fxUid/rocket").setValue(true) } catch (e: Exception) { }
            },
        )
        if (fxBannerOn) GiftBanner(r.id)
        // Banner cashback terpisah: hanya saat JP 10.000 ke atas.
        if (fxBannerOn) CashbackBanner(r.id)
        // Penanda status suara: kalau suara gagal, alasannya tampil di sini
        // (bukan hanya toast yang cepat hilang), jadi mudah dilaporkan.
        run {
            val st = AgoraVoice.lastStatus
            if (st.isNotBlank() && !st.startsWith("OK")) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(st, color = Color(0xFFFFD24D), fontSize = 9.sp, maxLines = 2)
                }
            }
        }
        if (fxEntryOn) VipEntryEffect(r.id)
        if (fxGiftOn) GiftFxOverlay(r.id)
        // Gift terbang ke avatar penerima lalu pecahan cashback muncul di titik itu.
        if (fxGiftOn) GiftFlyOverlay(r.id, seatPoints)
        // Peluncuran roket 1..5 berurutan, dengan dialog hadiah bertombol Okey.
        // Build uji: dimatikan sementara untuk mencari penyebab aplikasi macet di room.
        if (ROCKET_ROOM_UI && fxRocketOn) RocketLaunchOverlay(r.id, myUid)

        // Tombol power: keluar / minimalkan
        if (showRank) {
            TopRankDialog(onDismiss = { showRank = false }, initialTab = 0, onOpenRoom = {})
        }

        // Jalankan kick sesuai durasi yang dipilih owner.
        val doKick: (String, String, Long) -> Unit = { targetUid, targetName, durasiMs ->
            scope.launch {
                val err = RoomRepo.kickFromRoom(r.id, targetUid, targetName, durasiMs)
                if (err != null) {
                    toast(err)
                } else {
                    toast("$targetName dikeluarkan dari room")
                    val lama = when {
                        durasiMs <= 0L -> "permanen"
                        durasiMs < 3_600_000L -> "${durasiMs / 60_000L} menit"
                        durasiMs < 86_400_000L -> "${durasiMs / 3_600_000L} jam"
                        else -> "${durasiMs / 86_400_000L} hari"
                    }
                    RoomExtras.sendSystemNotice(r.id, "🚫 Sistem: $targetName telah di-kick dari room ($lama)")
                }
            }
        }

        // Pilih lama kick: 30 menit, 1 hari, atau permanen.
        kickPick?.let { pick ->
            val targetUid = pick.first
            val targetName = pick.second
            Dialog(onDismissRequest = { kickPick = null }) {
                DialogCard {
                    Text(tr("Kick") + " " + targetName, color = Color(0xFFFF8A65), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("User otomatis masuk daftar hitam. Kick sementara berakhir sendiri, kick permanen dibuka manual dari halaman Daftar hitam."),
                        color = Color(0xFFBDBDBD), fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    GoldButton(tr("Kick 30 menit")) {
                        kickPick = null
                        doKick(targetUid, targetName, 30L * 60_000L)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlineButton(tr("Kick 1 hari")) {
                        kickPick = null
                        doKick(targetUid, targetName, 24L * 60L * 60_000L)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlineButton(tr("Kick permanen")) {
                        kickPick = null
                        doKick(targetUid, targetName, 0L)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Batal"), color = Color(0xFFBDBDBD), fontSize = 13.sp,
                        modifier = Modifier.clickable { kickPick = null }.padding(8.dp),
                    )
                }
            }
        }

        // Pemberitahuan untuk user yang dikeluarkan dari room.
        if (kickedOut) {
            Dialog(onDismissRequest = { kickedOut = false; onExit() }) {
                DialogCard {
                    Text(
                        tr("Anda telah di kick dari room ⚠️"),
                        color = Color(0xFFFF7043), fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr("Owner membuka kick lewat Daftar hitam kalau Anda boleh masuk lagi."),
                        color = Color(0xFFBDBDBD), fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    GoldButton(tr("Oke")) {
                        kickedOut = false
                        onExit()
                    }
                }
            }
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
                        RmTool(if (r.locked) "Buka kunci room" else "Kunci room", onClick = { panel = P_LOCK }) {
                            if (r.locked) Text("🔓", fontSize = 24.sp)
                            else Icon(Icons.Default.Lock, null, tint = GoldLight)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(tr("Efek di layar"), color = Color(0xFF9A9A9A), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Satu pintu untuk semua efek: masuk, gift, banner, roket, game.
                    RmTool(tr("Blok Efek"), onClick = { panel = P_EFEK }) { Text("\u2728", fontSize = 24.sp) }
                }
                Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                    if (isOwner) {
                        RmTool("Anggota", onClick = { panel = P_MEMBERS }) { Text("👥", fontSize = 22.sp) }
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
            var bUsers by remember(bans) { mutableStateOf<Map<String, PublicUser?>>(emptyMap()) }
            LaunchedEffect(bans) {
                val m = HashMap<String, PublicUser?>()
                for (b in bans) {
                    m[b.uid] = try {
                        UserCache.byUid(b.uid)
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
                Text(
                    tr("User di daftar ini tidak bisa masuk room sampai kick-nya dibuka. Kick sementara berakhir sendiri."),
                    color = Color(0xFFBDBDBD), fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                if (bans.isEmpty()) {
                    Text(tr("Belum ada user yang di-kick"), color = Color(0xFFBDBDBD))
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(bans.size) { i ->
                            val b = bans[i]
                            val pu = bUsers[b.uid]
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                InvAvatar(pu, b.uid)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pu?.name ?: b.name.ifBlank { "UID ${b.uid}" },
                                        color = Color.White, fontSize = 15.sp, maxLines = 1,
                                    )
                                    Text(
                                        if (b.until <= 0L) tr("Kick permanen") else tr("Kick ") + b.sisa(),
                                        color = if (b.until <= 0L) Color(0xFFFF8A65) else Color(0xFF9A9A9A),
                                        fontSize = 12.sp,
                                    )
                                }
                                OutlineButton(tr("Buka kick")) {
                                    scope.launch {
                                        toast(RoomRepo.unbanFromRoom(r.id, b.uid) ?: (tr("Kick dibuka, user boleh masuk lagi")))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlineButton(tr("Tutup")) { panel = P_NONE }
            }
        }
        if (panel == P_MEMBERS && isOwner) {
            val others = members.filter { it != myUid }
            var info by remember(others) { mutableStateOf<Map<String, PublicUser?>>(emptyMap()) }
            LaunchedEffect(others) {
                val m = HashMap<String, PublicUser?>()
                for (u in others) {
                    m[u] = try { UserCache.byUid(u) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                }
                info = m
            }
            RmPanel(onDismiss = { panel = P_NONE }) {
                Text(tr("Anggota room"), color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(tr("Hanya owner yang bisa menurunkan dari mic atau mengeluarkan user."), color = Color(0xFFBDBDBD), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                if (others.isEmpty()) {
                    Text(tr("Belum ada anggota lain"), color = Color(0xFFBDBDBD))
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(others.size) { i ->
                            val uid = others.elementAt(i)
                            val pu = info[uid]
                            val seatIdx = seats.entries.firstOrNull { it.value.uid == uid }?.key
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                InvAvatar(pu, uid)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pu?.name ?: ("UID " + uid), color = Color.White, fontSize = 14.sp, maxLines = 1)
                                    Text(if (seatIdx != null) "Mic " + (seatIdx + 1) else "Penonton", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                                }
                                if (seatIdx != null) {
                                    OutlineButton(tr("Turunkan")) {
                                        scope.launch {
                                            val err = RoomRepo.removeFromSeat(r.id, seatIdx)
                                            toast(err ?: ((pu?.name ?: "User") + " diturunkan dari mic"))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                OutlineButton(tr("Kick")) {
                                    kickPick = uid to (pu?.name ?: "User")
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
        if (panel == P_EFEK) RmPanel(onDismiss = { panel = P_NONE }) {
            RmBlokEfek(
                masuk = fxEntryOn, gift = fxGiftOn, banner = fxBannerOn, roket = fxRocketOn, game = fxGameOn,
                onChange = { kunci, nilai ->
                    when (kunci) {
                        "entry" -> fxEntryOn = nilai
                        "gift" -> fxGiftOn = nilai
                        "banner" -> fxBannerOn = nilai
                        "rocket" -> fxRocketOn = nilai
                        "game" -> fxGameOn = nilai
                    }
                    fxLocal.edit().putBoolean(kunci, nilai).apply()
                    if (fxUid.isNotBlank()) Rtdb.ref("fxPrefs/$fxUid/$kunci").setValue(nilai)
                },
            )
        }
    }
}

/** Satu baris saklar: judul, keterangan, dan tombol geser. */
@Composable
private fun RmEfekBaris(judul: String, ket: String, peringatan: String? = null, on: Boolean, onToggle: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(judul, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Teal,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF3A3A3A),
                    uncheckedThumbColor = Color.White,
                    uncheckedBorderColor = Color(0xFF3A3A3A),
                ),
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF161616)).padding(12.dp)) {
            Text(ket, color = Color(0xFF9A9A9A), fontSize = 13.sp)
            if (peringatan != null) {
                Spacer(Modifier.height(4.dp))
                Text(peringatan, color = Color(0xFFE06A6A), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Panel "Blok Efek": satu tempat untuk mematikan tiap jenis efek di layar.
 * Saklar ON = efek tampil, OFF = efek disembunyikan. Disimpan per akun.
 */
@Composable
private fun RmBlokEfek(
    masuk: Boolean, gift: Boolean, banner: Boolean, roket: Boolean, game: Boolean,
    onChange: (String, Boolean) -> Unit,
) {
    Text(tr("Blok Efek"), color = Color.White, fontSize = 20.sp)
    Spacer(Modifier.height(4.dp))
    Text(tr("Hijau berarti efek tampil. Matikan untuk menyembunyikan."), color = Color(0xFF9A9A9A), fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    RmEfekBaris(
        tr("Efek masuk room"),
        tr("Jika dimatikan, animasi pemain VIP yang masuk room tidak diputar."),
        on = masuk,
    ) { onChange("entry", it) }
    RmEfekBaris(
        tr("Efek gift"),
        tr("Jika dimatikan, animasi gift dan pecahan cashback di layar tidak diputar."),
        on = gift,
    ) { onChange("gift", it) }
    RmEfekBaris(
        tr("Banner gift"),
        tr("Jika dimatikan, banner gift dan banner cashback di atas layar tidak muncul."),
        on = banner,
    ) { onChange("banner", it) }
    RmEfekBaris(
        tr("Efek roket"),
        tr("Jika dimatikan, ikon roket di sisi room dan animasi peluncuran roket tidak ditampilkan."),
        on = roket,
    ) { onChange("rocket", it) }
    RmEfekBaris(
        tr("Efek game"),
        tr("Jika dimatikan, banner kemenangan game tidak ditampilkan."),
        peringatan = tr("Ini tidak mengubah hasil game, hanya sembunyikan banner."),
        on = game,
    ) { onChange("game", it) }
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

/** Tekan lama pada teks chat room: salin isi pesan ke papan klip (v1.0.808). */
@Composable
private fun IngatSalin(text: String): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return {
        try {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("REZO", text))
            android.widget.Toast.makeText(ctx, "Teks disalin", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Gagal menyalin", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun RmMsgBox(text: String, color: Color) {
    val salin = IngatSalin(text)
    Box(
        Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x66000000))
            .pointerInput(text) {
                androidx.compose.foundation.gestures.detectTapGestures(onLongPress = { salin() })
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
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

/** Tombol saklar di Kotak 4: jelas menunjukkan ON atau OFF. */
@Composable
private fun RmToolToggle(label: String, on: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(Modifier.width(80.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(58.dp).clip(CircleShape)
                .background(if (on) Color(0xFF1C1C1C) else Color(0xFF241414))
                .border(1.5.dp, if (on) Gold.copy(alpha = 0.8f) else Color(0xFF7A3A3A), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.alpha(if (on) 1f else 0.35f)) { icon() }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (on) Color(0xFFD0D0D0) else Color(0xFF8A8A8A), fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
        Text(
            if (on) "ON" else "OFF",
            color = if (on) Color(0xFF5BD27A) else Color(0xFFE06A6A),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
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
                val b = ProfileRepo.encodeImage(context, uri, 720, square = false, quality = 55)
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
        // Owner bisa membuka kunci room kapan saja dari dalam room.
        GoldButton(tr("Buka kunci room")) {
            scope.launch {
                val e = RoomRepo.setLock(r.id, null)
                Toast.makeText(context, e ?: "Kunci room dibuka, room kembali publik", Toast.LENGTH_SHORT).show()
                if (e == null) onClose()
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(tr("Ganti password"), color = Color.White, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(tr("Password baru (min. 4 karakter)")) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp),
            colors = goldFieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(tr("Simpan password baru"), enabled = pw.length >= 4) {
            scope.launch {
                val e = RoomRepo.setLock(r.id, pw)
                Toast.makeText(context, e ?: "Password room diperbarui", Toast.LENGTH_SHORT).show()
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
    // Batas geser dihitung dari UKURAN CONTAINER sebenarnya (BoxWithConstraints),
    // bukan screenWidthDp dari Configuration yang bisa beda dengan ruang nyata.
    // Ukuran ikon ikut mengecil di HP sempit dan selalu diberi jarak tepi,
    // jadi tidak ada bagian yang terpotong di device mana pun.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layarSempit = maxWidth < 340.dp
        val ukuran = if (layarSempit) 46.dp else 52.dp
        val tepi = 10.dp

        val ukuranPx = with(density) { ukuran.toPx() }
        val tepiPx = with(density) { tepi.toPx() }
        val lebarPx = with(density) { maxWidth.toPx() }
        val tinggiPx = with(density) { maxHeight.toPx() }
        val atasPx = WindowInsets.statusBars.getTop(density).toFloat()
        val bawahPx = WindowInsets.navigationBars.getBottom(density).toFloat()

        val batasKanan = (lebarPx - ukuranPx - tepiPx).coerceAtLeast(tepiPx)
        val batasBawah = (tinggiPx - bawahPx - ukuranPx - tepiPx).coerceAtLeast(atasPx + tepiPx)
        val batasAtas = atasPx + tepiPx

        // null = belum digeser, jadi posisi awal selalu mengikuti layar terkini.
        var geserX by remember { mutableStateOf<Float?>(null) }
        var geserY by remember { mutableStateOf<Float?>(null) }
        val x = (geserX ?: (batasKanan - tepiPx * 2f)).coerceIn(tepiPx, batasKanan)
        val y = (geserY ?: (batasAtas + (batasBawah - batasAtas) * 0.55f)).coerceIn(batasAtas, batasBawah)

        val bmp = remember(room.photoB64) { ProfileRepo.decodeB64(room.photoB64) }
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(ukuran)
                .clip(CircleShape)
                .background(Color(0xFF1E1E1E))
                .border(2.dp, Gold, CircleShape)
                .pointerInput(batasKanan, batasAtas, batasBawah) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        geserX = (x + drag.x).coerceIn(tepiPx, batasKanan)
                        geserY = (y + drag.y).coerceIn(batasAtas, batasBawah)
                    }
                }
                .clickable(onClick = onClick),
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
                Text("🎙", fontSize = if (layarSempit) 20.sp else 24.sp)
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
                val salinTeks = IngatSalin(m.text)
                Text(
                    m.text, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.pointerInput(m.text) {
                        androidx.compose.foundation.gestures.detectTapGestures(
                            onLongPress = { salinTeks() },
                        )
                    }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Pesan chat berisi gambar. Ketuk gambar untuk melihat ukuran penuh. */
@Composable
private fun RmPhotoRow(m: RoomMsg, myUid: String, onProfile: (String) -> Unit, onOpen: (String) -> Unit) {
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
            }
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable { onOpen(m.img) },
            ) {
                AsyncImage(
                    model = m.img,
                    contentDescription = "Gambar dari $shownName",
                    modifier = Modifier.widthIn(max = 220.dp).heightIn(max = 260.dp),
                    contentScale = ContentScale.FillWidth,
                )
            }
            if (m.text.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(m.text, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
