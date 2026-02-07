package com.example.impulse.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerConfig
import java.util.*

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun MainScreen() {
    var selectedItem by remember { mutableIntStateOf(0) }
    var selectedServer by remember { mutableStateOf(ServerConfig.defaultServer) }
    var clientName by remember { mutableStateOf(generateRandomName()) }
    var encryptionKey by remember { mutableStateOf("") } // Добавляем состояние для ключа шифрования

    val items = listOf(
        TabItem("Главная", Icons.Default.Home),
        TabItem("Чат", Icons.Default.Email),
        TabItem("Настройки", Icons.Default.Settings)
    )

    Scaffold(
        topBar = {
            // Добавляем отступ для системной панели и делаем панель тоньше
            NavigationBar(
                modifier = Modifier.statusBarsPadding(),
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = {
                            Text(
                                text = item.title,
                                textAlign = TextAlign.Center,
                                fontSize = androidx.compose.material3.MaterialTheme.typography.labelMedium.fontSize
                            )
                        },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedItem) {
            0 -> HomeScreen(
                clientName = clientName,
                onClientNameChange = { newName -> clientName = newName },
                modifier = Modifier.padding(innerPadding)
            )
            1 -> ChatScreen(
                selectedServer = selectedServer,
                clientName = clientName,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> SettingsScreen(
                selectedServer = selectedServer,
                onServerSelected = { newServer -> selectedServer = newServer },
                clientName = clientName,
                onClientNameChange = { newName -> clientName = newName },
                encryptionKey = encryptionKey, // Передаем ключ шифрования
                onEncryptionKeyChange = { newKey -> encryptionKey = newKey }, // Обработчик изменения ключа
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

// Генерация случайного имени
private fun generateRandomName(): String {
    val adjectives = arrayOf(
        "Веселый", "Умный", "Быстрый", "Смелый", "Добрый",
        "Ловкий", "Внимательный", "Энергичный", "Креативный", "Надежный"
    )
    val nouns = arrayOf(
        "Пользователь", "Клиент", "Участник", "Чаттер", "Гость",
        "Посетитель", "Собеседник", "Диалогист"
    )
    val random = Random()

    val adjective = adjectives[random.nextInt(adjectives.size)]
    val noun = nouns[random.nextInt(nouns.size)]
    val number = random.nextInt(100)

    return "$adjective$noun$number"
}