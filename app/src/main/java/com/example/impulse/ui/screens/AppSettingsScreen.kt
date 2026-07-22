package com.example.impulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerPreferences
import com.example.impulse.ui.theme.DynamicColor
import com.example.impulse.ui.theme.ThemeMode
import com.example.impulse.ui.theme.ThemeSettings
import com.example.impulse.util.LogManager

/**
 * Minimalist app settings:
 *  - theme mode (radio)
 *  - accent color via 3 sliders (hue / saturation / lightness) with
 *    theme-aware constraints so the resulting primary stays readable
 *  - font scale via a single slider
 *  - auto-connect & biometric switches
 *
 * Base/preset colors and the 2D picker were removed to keep the menu simple.
 */

// Readability constraints per theme.
// Light theme: lightness kept in a mid band so primary isn't too pale on white.
// Dark theme: lightness kept higher so primary is visible on near-black.
private val LIGHT_L_RANGE = 0.35f..0.62f
private val DARK_L_RANGE = 0.50f..0.78f
private val SAT_RANGE = 0.35f..1.0f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val serverPreferences = remember { ServerPreferences(context) }

    var selectedTheme by remember { mutableStateOf(ThemeSettings.themeMode) }
    val isDark = selectedTheme == ThemeMode.DARK || selectedTheme == ThemeMode.OLED

    // Accent sliders (full range; constraints applied on commit).
    var hue by remember { mutableStateOf(ThemeSettings.accentColor.hue) }
    var saturation by remember { mutableStateOf(ThemeSettings.accentColor.saturation) }
    var lightness by remember { mutableStateOf(ThemeSettings.accentColor.lightness) }

    var fontScale by remember { mutableStateOf(ThemeSettings.fontScale) }

    var autoConnect by remember { mutableStateOf(serverPreferences.getAutoConnect()) }
    var autoReconnect by remember { mutableStateOf(serverPreferences.getAutoReconnect()) }
    var biometricEnabled by remember { mutableStateOf(serverPreferences.getBiometricEnabled()) }
    var loggingEnabled by remember { mutableStateOf(serverPreferences.getLoggingEnabled()) }

    // Current accent preview (opaque).
    val currentColor = remember(hue, saturation, lightness) {
        DynamicColor(hue, saturation, lightness, 1f).toColor()
    }

    // Hue gradient brush for the hue slider track.
    val hueTrackBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
            )
        )
    }

    // Saturation gradient (gray -> full hue).
    val satTrackBrush = remember(hue, lightness) {
        val base = DynamicColor(hue, 0f, lightness, 1f).toColor()
        val full = DynamicColor(hue, 1f, lightness, 1f).toColor()
        Brush.horizontalGradient(colors = listOf(base, full))
    }

    // Lightness gradient (black -> hue -> white).
    val lightTrackBrush = remember(hue, saturation) {
        val black = DynamicColor(hue, saturation, 0f, 1f).toColor()
        val mid = DynamicColor(hue, saturation, 0.5f, 1f).toColor()
        val white = DynamicColor(hue, saturation, 1f, 1f).toColor()
        Brush.horizontalGradient(colors = listOf(black, mid, white))
    }

    fun commitColor() {
        val lRange = if (isDark) DARK_L_RANGE else LIGHT_L_RANGE
        val safeL = lightness.coerceIn(lRange.start, lRange.endInclusive)
        val safeS = saturation.coerceIn(SAT_RANGE.start, SAT_RANGE.endInclusive)
        ThemeSettings.setAccentColor(DynamicColor(hue, safeS, safeL, 1f))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки приложения") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Theme ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Тема оформления",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTheme = mode
                                    ThemeSettings.setThemeMode(mode)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTheme == mode,
                                onClick = {
                                    selectedTheme = mode
                                    ThemeSettings.setThemeMode(mode)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (mode) {
                                    ThemeMode.LIGHT -> "Светлая"
                                    ThemeMode.DARK -> "Тёмная"
                                    ThemeMode.SYSTEM -> "Системная"
                                    ThemeMode.OLED -> "OLED (чёрная)"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            // ---- Accent color ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Акцентный цвет",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Live preview swatch.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(currentColor)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Предпросмотр",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (lightness > 0.6f) Color(0xFF1A1A1A) else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Hue
                    Text(
                        text = "Оттенок: ${"%.0f".format(hue)}°",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = hue,
                        onValueChange = {
                            hue = it
                            commitColor()
                        },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(thumbColor = currentColor),
                        track = { GradientTrack(hueTrackBrush) }
                    )

                    // Saturation
                    Text(
                        text = "Насыщенность: ${"%.0f".format(saturation * 100)}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = saturation,
                        onValueChange = {
                            saturation = it
                            commitColor()
                        },
                        valueRange = SAT_RANGE,
                        colors = SliderDefaults.colors(thumbColor = currentColor),
                        track = { GradientTrack(satTrackBrush) }
                    )

                    // Lightness
                    val lRange = if (isDark) DARK_L_RANGE else LIGHT_L_RANGE
                    Text(
                        text = "Яркость: ${"%.0f".format(lightness * 100)}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = lightness.coerceIn(lRange.start, lRange.endInclusive),
                        onValueChange = {
                            lightness = it
                            commitColor()
                        },
                        valueRange = lRange,
                        colors = SliderDefaults.colors(thumbColor = currentColor),
                        track = { GradientTrack(lightTrackBrush) }
                    )
                    Text(
                        text = "Диапазон ограничен для читаемости в выбранной теме",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Font scale ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Размер шрифта",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Preview text that scales live with the slider.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Пример текста для предпросмотра",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = (MaterialTheme.typography.bodyLarge.fontSize * fontScale)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Масштаб: ${"%.2f".format(fontScale)}x",
                        style = MaterialTheme.typography.bodyMedium
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

            // ---- Additional ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Дополнительно",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Автоподключение", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Подключаться при запуске",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = {
                                autoConnect = it
                                serverPreferences.saveAutoConnect(it)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Авто-переподключение", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Повторять попытки при ошибке",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoReconnect,
                            onCheckedChange = {
                                autoReconnect = it
                                serverPreferences.saveAutoReconnect(it)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Биометрическая защита", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Отпечаток/лицо при запуске",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = {
                                biometricEnabled = it
                                serverPreferences.saveBiometricEnabled(it)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Вести логи (файл)", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Сохранять последние записи на диск для экспорта",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = loggingEnabled,
                            onCheckedChange = {
                                loggingEnabled = it
                                serverPreferences.saveLoggingEnabled(it)
                                // Enable/disable the on-disk rotating log tree live.
                                LogManager.setLoggingEnabled(it)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Draws a full-width gradient as the Slider track (Material3 1.2 compatible). */
@Composable
private fun GradientTrack(brush: Brush) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(brush)
    )
}
