package com.example.impulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerPreferences
import com.example.impulse.ui.theme.*
import com.example.impulse.util.LogManager

@Composable
fun AppSettingsContent(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val serverPreferences = remember { ServerPreferences(context) }

    var selectedTheme by remember { mutableStateOf(ThemeSettings.themeMode) }
    var selectedPreset by remember { mutableStateOf(ThemeSettings.preset) }
    var fontScale by remember { mutableStateOf(ThemeSettings.fontScale) }

    var autoConnect by remember { mutableStateOf(serverPreferences.getAutoConnect()) }
    var autoReconnect by remember { mutableStateOf(serverPreferences.getAutoReconnect()) }
    var biometricEnabled by remember { mutableStateOf(serverPreferences.getBiometricEnabled()) }
    var loggingEnabled by remember { mutableStateOf(serverPreferences.getLoggingEnabled()) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Theme mode ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Тема оформления") {
                ThemeMode.entries.forEach { mode ->
                    ImpulseRadio(
                        title = when (mode) {
                            ThemeMode.LIGHT -> "Светлая"
                            ThemeMode.DARK -> "Тёмная"
                            ThemeMode.SYSTEM -> "Системная"
                            ThemeMode.OLED -> "OLED (чёрная)"
                        },
                        selected = selectedTheme == mode,
                        onClick = {
                            selectedTheme = mode
                            ThemeSettings.setThemeMode(mode)
                        }
                    )
                }
            }
        }

        // ── Theme presets ───────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Пресеты темы") {
                ThemePreset.entries.forEach { preset ->
                    ImpulseRadio(
                        title = preset.displayName,
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            ThemeSettings.setThemePreset(preset)
                        }
                    )
                }
            }
        }

        // ── Font scale ─────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Масштаб шрифта") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Пример текста для предпросмотра",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = (MaterialTheme.typography.bodyLarge.fontSize * fontScale)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Масштаб: ${"%.2f".format(fontScale)}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = fontScale,
                    onValueChange = {
                        fontScale = it
                        ThemeSettings.setFontScale(it)
                    },
                    valueRange = 0.8f..1.4f
                )
            }
        }

        // ── Connection ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Подключение") {
                ImpulseToggle(
                    title = "Автоподключение",
                    description = "Подключаться при запуске",
                    checked = autoConnect,
                    onCheckedChange = {
                        autoConnect = it
                        serverPreferences.saveAutoConnect(it)
                    }
                )
                ImpulseDivider()
                ImpulseToggle(
                    title = "Авто-переподключение",
                    description = "Повторять попытки при ошибке",
                    checked = autoReconnect,
                    onCheckedChange = {
                        autoReconnect = it
                        serverPreferences.saveAutoReconnect(it)
                    }
                )
                ImpulseDivider()
                ImpulseToggle(
                    title = "Биометрическая защита",
                    description = "Отпечаток/лицо при запуске",
                    checked = biometricEnabled,
                    onCheckedChange = {
                        biometricEnabled = it
                        serverPreferences.saveBiometricEnabled(it)
                    }
                )
                ImpulseDivider()
                ImpulseToggle(
                    title = "Вести логи (файл)",
                    description = "Сохранять записи на диск для экспорта",
                    checked = loggingEnabled,
                    onCheckedChange = {
                        loggingEnabled = it
                        serverPreferences.saveLoggingEnabled(it)
                        LogManager.setLoggingEnabled(it)
                    }
                )
            }
        }
    }
}
