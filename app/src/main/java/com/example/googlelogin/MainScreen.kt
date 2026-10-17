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
import kotlinx.coroutines.launch

private data class NavItem(val label: String, val icon: ImageVector)

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
    var selected by rememberSaveable { mutableStateOf(0) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var profile by remember { mutableStateOf(ProfileRepo.loadLocal(context, user.email)) }

    // Ambil profil terbaru dari Firestore (jika sudah pernah disimpan)
    LaunchedEffect(user.email) {
        ProfileRepo.loadRemote()?.let {
            profile = it
            ProfileRepo.saveLocal(context, user.email, it)
        }
    }

    fun updateProfile(new: ProfileData) {
        profile = new
        ProfileRepo.saveLocal(context, user.email, new)
        scope.launch {
            ProfileRepo.saveRemote(new)?.let {
                Toast.makeText(context, "Tersimpan di perangkat, gagal sinkron: ${it.message}", Toast.LENGTH_LONG).show()
            }
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
                    0 -> PartyTab()
                    1 -> DiscoverTab()
                    2 -> FamilyTab()
                    3 -> MessagesTab()
                    else -> ProfileTab(
                        user = user,
                        profile = profile,
                        onEditClick = { showEdit = true },
                        onSignOutClick = onSignOutClick,
                    )
                }
            }
        }

        if (showEdit) {
            EditProfileScreen(
                user = user,
                profile = profile,
                onChange = ::updateProfile,
                onBack = { showEdit = false },
            )
        }
    }
}
