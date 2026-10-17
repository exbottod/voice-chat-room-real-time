package com.example.googlelogin

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

private data class NavItem(val label: String, val icon: ImageVector)

private const val OV_NONE = 0
private const val OV_EDIT = 1
private const val OV_SETTINGS = 2
private const val OV_INVITE = 3
private const val OV_SEARCH = 4

@Composable
fun MainScreen(user: UserProfile, onSignOutClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = listOf(
        NavItem("Party", Icons.Default.Home),
        NavItem("Temukan", Icons.Default.Search),
        NavItem("Family", Icons.Default.Favorite),
        NavItem("Pesan", Icons.Default.Email),
        NavItem("Saya", Icons.Default.Person),
    )
    val uid = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    var selected by rememberSaveable { mutableStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf(OV_NONE) }
    var profile by remember { mutableStateOf(ProfileRepo.loadLocal(context, user.email)) }
    var userId by remember { mutableStateOf(UserDirectory.cachedId(context, uid) ?: "...") }

    var activeRoom by remember { mutableStateOf<RoomInfo?>(null) }
    var minimized by remember { mutableStateOf(false) }
    val myName = profile.nickname.ifBlank { user.name }

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
            RoomRepo.join(room.id, myName)
        }
    }

    fun exitRoom() {
        val r = activeRoom ?: return
        activeRoom = null
        minimized = false
        scope.launch { RoomRepo.leave(r.id, uid) }
    }

    // Muat profil dari Firestore, pastikan ID pengguna (mulai 100000) ada, lalu publikasikan.
    LaunchedEffect(uid) {
        if (uid.isEmpty()) return@LaunchedEffect
        ProfileRepo.loadRemote()?.let {
            profile = it
            ProfileRepo.saveLocal(context, user.email, it)
        }
        try {
            userId = UserDirectory.ensureUserId(context, uid)
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal membuat ID: ${e.message}", Toast.LENGTH_LONG).show()
        }
        UserDirectory.publish(context, user, profile)
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

    Box(Modifier.fillMaxSize()) {
        RoyalBackground()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B0B0B)) {
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
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
            } else padding
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                when (selected) {
                    0 -> PartyTab(
                        myUid = uid,
                        myName = myName,
                        onSearch = { overlay = OV_SEARCH },
                        onEnterRoom = ::enterRoom,
                    )
                    1 -> DiscoverTab()
                    2 -> FamilyTab()
                    3 -> MessagesTab()
                    else -> ProfileTab(
                        user = user,
                        profile = profile,
                        userId = userId,
                        onEditClick = { overlay = OV_EDIT },
                        onInviteClick = { overlay = OV_INVITE },
                        onSettingsClick = { overlay = OV_SETTINGS },
                        onSignOutClick = onSignOutClick,
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
        }
        val room = activeRoom
        if (room != null) {
            if (!minimized) {
                RoomScreen(
                    roomId = room.id,
                    myUid = uid,
                    myName = myName,
                    myAvatarB64 = profile.avatarB64,
                    onExit = ::exitRoom,
                    onMinimize = { minimized = true },
                )
            } else {
                FloatingRoomBubble(room = room, onClick = { minimized = false })
            }
        }
    }
}
