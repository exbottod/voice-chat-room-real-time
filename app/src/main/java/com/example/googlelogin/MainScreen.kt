package com.example.googlelogin

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

private data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun MainScreen(user: UserProfile, onSignOutClick: () -> Unit) {
    val items = listOf(
        NavItem("Party", Icons.Default.Home),
        NavItem("Temukan", Icons.Default.Search),
        NavItem("Family", Icons.Default.Favorite),
        NavItem("Pesan", Icons.Default.Email),
        NavItem("Saya", Icons.Default.Person),
    )
    var selected by rememberSaveable { mutableStateOf(0) }

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
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selected) {
                    0 -> PartyTab()
                    1 -> DiscoverTab()
                    2 -> FamilyTab()
                    3 -> MessagesTab()
                    else -> ProfileTab(user, onSignOutClick)
                }
            }
        }
    }
}
