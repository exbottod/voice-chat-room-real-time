package com.example.googlelogin

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Icon tab memakai drawable: ganti file PNG di res/drawable untuk ubah tampilannya.
private data class NavItem(val label: String, val icon: Int)

private const val OV_NONE = 0
private const val OV_EDIT = 1
private const val OV_SETTINGS = 2
private const val OV_INVITE = 3
private const val OV_SEARCH = 4
private const val OV_POST = 5

@Composable
fun MainScreen(user: UserProfile, onSignOutClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = listOf(
        NavItem("Party", R.drawable.ic_tab_party),
        NavItem("Moment", R.drawable.ic_tab_moment),
        NavItem("Family", R.drawable.ic_tab_family),
        NavItem("Pesan", R.drawable.ic_tab_pesan),
        NavItem("Saya", R.drawable.ic_tab_saya),
    )
    val uid = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    var selected by rememberSaveable { mutableStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf(OV_NONE) }
    var profile by remember { mutableStateOf(ProfileRepo.loadLocal(context, user.email)) }
    var userId by remember { mutableStateOf(UserDirectory.cachedId(context, uid) ?: "...") }
    var activeRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var minimized by remember { mutableStateOf(false) }
    val myName = profile.nickname.ifBlank { user.name }
    var showWallet by remember { mutableStateOf(false) }
    var showLevel by remember { mutableStateOf(false) }
    var showVip by remember { mutableStateOf(false) }
    var showStore by remember { mutableStateOf(false) }
    var showGlory by remember { mutableStateOf(false) }
    var sheetUid by remember { mutableStateOf<String?>(null) }
    var fullProfileUid by remember { mutableStateOf<String?>(null) }
    var followList by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chatWith by remember { mutableStateOf<PublicUser?>(null) }
    var commentMoment by remember { mutableStateOf<Moment?>(null) }
    var wallet by remember { mutableStateOf(Wallet()) }
    var following by remember { mutableStateOf(0L) }
    var fans by remember { mutableStateOf(0L) }
    var followTick by remember { mutableStateOf(0) }
    val actions = remember {
        AppActions(
            openProfile = { sheetUid = it },
            openChat = {
                chatWith = it
                sheetUid = null
                fullProfileUid = null
            },
            openWallet = { showWallet = true },
            openFullProfile = {
                fullProfileUid = it
                sheetUid = null
            },
        )
    }

    DisposableEffect(uid) {
        val reg = if (uid.isNotEmpty()) WalletRepo.observe(uid) { wallet = it } else null
        onDispose { reg?.remove() }
    }

    LaunchedEffect(uid, selected, followTick) {
        if (uid.isNotEmpty()) {
            try {
                val c = FollowRepo.counts(uid)
                following = c.first
                fans = c.second
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    fun enterRoom(room: RoomInfo) {
        if (activeRoom?.id == room.id) {
            minimized = false
            return
        }
        val old = activeRoom
        activeRoom = room
        minimized = false
        scope.launch {
            if (old != null) RoomRepo.leave(old.id, uid)
            RoomRepo.join(room.id, myName, if (vipActive(wallet.vip, wallet.vipUntil)) wallet.vip else 0, activeEquip(wallet, "entry"))
        }
    }

    fun exitRoom() {
        val r = activeRoom ?: return
        activeRoom = null
        minimized = false
        scope.launch { RoomRepo.leave(r.id, uid) }
    }

    // Deep link "bagikan room" (rezo://room/<id>): otomatis masuk room
    // saat link dibuka dan user sudah login.
    LaunchedEffect(RoomDeepLink.pendingRoomId) {
        val rid = RoomDeepLink.pendingRoomId ?: return@LaunchedEffect
        RoomDeepLink.pendingRoomId = null
        if (activeRoom == null) {
            RoomRepo.getRoom(rid)?.let { enterRoom(it) }
        }
    }

    // Muat profil dari Firestore, pastikan ID pengguna (mulai 100000) ada, lalu publikasikan.
    LaunchedEffect(uid) {
        // Katalog (gift/store/VIP) dimuat PALING AWAL, tidak bergantung proses lain,
        // supaya gift bawaan selalu tampil walau Firestore limit/koneksi bermasalah.
        VipAssets.load()
        StoreCatalog.start()
        GiftCatalog.start()
        if (uid.isEmpty()) return@LaunchedEffect
        ProfileRepo.loadRemote()?.let {
            profile = it
            ProfileRepo.saveLocal(context, user.email, it)
        }
        try {
            userId = UserDirectory.ensureUserId(context, uid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuat ID: ${e.message}", Toast.LENGTH_LONG).show()
        }
        try {
            UserDirectory.publish(context, user, profile)
            PublicSync.push(uid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { }
        // SVIP offline: sinkronkan toggle yang tersimpan lokal saat kuota/koneksi kembali.
        try { SvipRepo.resync(uid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        // Status online (SVIP5 Hide Online Status): online = true selama app hidup,
        // otomatis false saat app mati. Hide Online = tidak menulis status sama sekali.
        try {
            val s = Rtdb.ref("status/$uid")
            if (SvipRepo.myOn(uid, "hideOnline", 5)) {
                s.removeValue()
            } else {
                s.onDisconnect().setValue(false)
                s.setValue(true).await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
    }

    // Item VIP otomatis masuk My Item, termasuk VIP yang dibeli sebelumnya
    LaunchedEffect(wallet.vip, wallet.vipUntil, wallet.inv.size) {
        if (uid.isNotEmpty()) LvRepo.backfillVip(uid, wallet)
    }

    fun updateProfile(new: ProfileData) {
        profile = new
        ProfileRepo.saveLocal(context, user.email, new)
        scope.launch {
            ProfileRepo.saveRemote(new)?.let {
                Toast.makeText(context, "Tersimpan di perangkat, gagal sinkron: ${it.message}", Toast.LENGTH_LONG).show()
            }
            UserDirectory.publish(context, user, new)
        }
    }

    // Muat config background tab: cache lokal dulu (instan), lalu refresh RTDB.
    LaunchedEffect(Unit) {
        TabBgs.loadLocal(context)
        TabBgs.refresh()
    }

    CompositionLocalProvider(LocalActions provides actions) {
        Box(Modifier.fillMaxSize()) {
            // Background mengikuti tab yang sedang aktif (diatur dari Panel Developer).
            TabBackground(items[selected].label.lowercase())
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF0B0B0B)) {
                        items.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(painterResource(item.icon), contentDescription = item.label, Modifier.size(45.dp), tint = Color.Unspecified) },
                                label = { Text(tr(item.label)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = Gold,
                                    indicatorColor = Gold,
                                    unselectedIconColor = Color(0xFF9A9A9A),
                                    unselectedTextColor = Color(0xFF9A9A9A),
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                // Tab profil memakai header sampai ke bawah status bar, jadi tanpa padding atas.
                val contentPadding = if (selected == 4) {
                    PaddingValues(bottom = padding.calculateBottomPadding())
                } else {
                    padding
                }
                Box(Modifier.fillMaxSize().padding(contentPadding)) {
                    when (selected) {
                        0 -> PartyTab(
                            myUid = uid,
                            myName = myName,
                            onSearch = { overlay = OV_SEARCH },
                            onEnterRoom = ::enterRoom,
                        )
                        1 -> MomentTab(
                            myUid = uid,
                            onPost = { overlay = OV_POST },
                            onComments = { commentMoment = it },
                        )
                        2 -> FamilyTabScreen(myUid = uid, myName = myName, myAvatar = profile.avatarB64, myId = userId)
                        3 -> ChatListTab(myUid = uid)
                        else -> ProfileTab(
                            user = user,
                            profile = profile,
                            userId = userId,
                            onEditClick = { overlay = OV_EDIT },
                            onInviteClick = { overlay = OV_INVITE },
                            onSettingsClick = { overlay = OV_SETTINGS },
                            onSignOutClick = onSignOutClick,
                            coins = wallet.coins,
                            following = following,
                            fans = fans,
                            onWalletClick = { showWallet = true },
                            onProfileClick = { fullProfileUid = uid },
                            wallet = wallet,
                            onVipClick = { showVip = true },
                            onLevelClick = { showLevel = true },
                            onStoreClick = { showStore = true },
                            onGloryClick = { showGlory = true },
                            onFamilyClick = { selected = 2 },
                            onFansClick = { followList = uid to FOLLOW_MODE_FANS },
                            onFollowingClick = { followList = uid to FOLLOW_MODE_FOLLOWING },
                        )
                    }
                }
            }

            when (overlay) {
                OV_EDIT -> EditProfileScreen(
                    user = user,
                    userId = userId,
                    profile = profile,
                    onChange = ::updateProfile,
                    onBack = { overlay = OV_NONE },
                )
                OV_SETTINGS -> SettingsScreen(
                    user = user,
                    userId = userId,
                    onSignOut = onSignOutClick,
                    onBack = { overlay = OV_NONE },
                )
                OV_INVITE -> InviteScreen(
                    uid = uid,
                    myUserId = userId,
                    onBack = { overlay = OV_NONE },
                )
                OV_SEARCH -> UserSearchScreen(
                    myUserId = userId,
                    onBack = { overlay = OV_NONE },
                )
                OV_POST -> PostMomentScreen(
                    uid = uid,
                    name = myName,
                    onBack = { overlay = OV_NONE },
                )
            }

            val room = activeRoom
            if (room != null) {
                key(room.id) { AgoraRoomBridge(roomId = room.id, myUid = uid) }
                if (!minimized) {
                    // key(room.id): pindah/masuk ulang room selalu mulai dari state bersih (tanpa draft)
                    key(room.id) {
                        RoomScreen(
                            roomId = room.id,
                            myUid = uid,
                            myName = myName,
                            myAvatarB64 = profile.avatarB64,
                            onExit = ::exitRoom,
                            onMinimize = { minimized = true },
                        )
                    }
                } else {
                    FloatingRoomBubble(room = room, onClick = { minimized = false })
                }
            }

            chatWith?.let {
                ChatScreen(
                    myUid = uid,
                    myName = myName,
                    myId = userId,
                    other = it,
                    onBack = { chatWith = null },
                )
            }
            if (showWallet) WalletScreen(uid = uid, wallet = wallet, onBack = { showWallet = false })
            if (showLevel) LevelScreen(user = user, profile = profile, wallet = wallet, uid = uid, onBack = { showLevel = false })
            if (showVip) VipScreen(uid = uid, wallet = wallet, onBack = { showVip = false })
            if (showGlory) GloryMedalScreen(uid = uid, wallet = wallet, onBack = { showGlory = false })
            if (showStore) {
                StoreScreen(
                    uid = uid,
                    wallet = wallet,
                    onOpenVip = {
                        showStore = false
                        showVip = true
                    },
                    onBack = { showStore = false },
                )
            }
            commentMoment?.let {
                MomentCommentsPanel(m = it, myUid = uid, myName = myName, onDismiss = { commentMoment = null })
            }
            sheetUid?.let {
                UserProfileSheet(
                    uid = it,
                    myUid = uid,
                    onDismiss = { sheetUid = null },
                    onChat = { u ->
                        chatWith = u
                        sheetUid = null
                    },
                    onFollowChanged = { followTick++ },
                    onOpenFull = {
                        fullProfileUid = it
                        sheetUid = null
                    },
                )
            }
            fullProfileUid?.let {
                OtherProfileScreen(
                    uid = it,
                    myUid = uid,
                    onBack = { fullProfileUid = null },
                    onChat = { u ->
                        chatWith = u
                        fullProfileUid = null
                    },
                    onFansClick = { followList = it to FOLLOW_MODE_FANS },
                    onFollowingClick = { followList = it to FOLLOW_MODE_FOLLOWING },
                    onEnterLiveRoom = { rid ->
                        fullProfileUid = null
                        scope.launch { RoomRepo.getRoom(rid)?.let { enterRoom(it) } }
                    },
                )
            }
            followList?.let { (fUid, fMode) ->
                FollowListScreen(
                    uid = fUid,
                    mode = fMode,
                    myUid = uid,
                    onBack = { followList = null },
                    onOpenUser = { fullProfileUid = it },
                )
            }
            GlobalGiftBanner(currentRoomId = activeRoom?.id) { rid ->
                if (rid.isNotBlank()) {
                    scope.launch { RoomRepo.getRoom(rid)?.let { enterRoom(it) } }
                }
            }
            BanGate(uid = uid, onSignOut = onSignOutClick, onBanned = { if (activeRoom != null) exitRoom() })
        }
    }
}
