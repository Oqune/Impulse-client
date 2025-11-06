package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.impulse.data.ServerConfig

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun MainScreen() {
    var selectedItem by remember { mutableIntStateOf(0) }
    var selectedServer by remember { mutableStateOf(ServerConfig.defaultServer) }

    val items = listOf(
        TabItem("Главная", Icons.Default.Home),
        TabItem("Тест WS", Icons.Default.PlayArrow),
        TabItem("Настройки", Icons.Default.Settings)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedItem) {
            0 -> HomeScreen(
                selectedServer = selectedServer,
                modifier = Modifier.padding(innerPadding)
            )
            1 -> TestWebSocketScreen(
                selectedServer = selectedServer,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> SettingsScreen(
                selectedServer = selectedServer,
                onServerSelected = { newServer -> selectedServer = newServer },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}