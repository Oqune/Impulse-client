package com.example.impulse.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.impulse.data.ServerPreferences
import com.example.impulse.ui.theme.*

@Composable
fun AppSettingsContent(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val serverPreferences = remember { ServerPreferences(context) }

    var selectedTheme by remember { mutableStateOf(ThemeSettings.themeMode) }
    var hue by remember { mutableFloatStateOf(ThemeSettings.hue) }
    var fontScale by remember { mutableStateOf(ThemeSettings.fontScale) }
    var oledEnabled by remember { mutableStateOf(ThemeSettings.oledEnabled) }
    var ultraContrastEnabled by remember { mutableStateOf(ThemeSettings.ultraContrastEnabled) }

    val isDarkActive = when (selectedTheme) {
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
    }

    var biometricEnabled by remember { mutableStateOf(serverPreferences.getBiometricEnabled()) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Theme mode ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Режим отображения") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeModeData.entries.forEach { entry ->
                        val isSelected = selectedTheme == entry.mode
                        val bgColor by animateColorAsState(
                            targetValue = if (isSelected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            label = "mode_bg"
                        )
                        val borderColor by animateColorAsState(
                            targetValue = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            label = "mode_border"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(bgColor)
                                .border(
                                    BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = borderColor
                                    ),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    selectedTheme = entry.mode
                                    ThemeSettings.setThemeMode(entry.mode)
                                }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = entry.label,
                                tint = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(10.dp))

                ImpulseToggle(
                    title = "OLED",
                    description = if (isDarkActive) "Чёрный фон для AMOLED-экранов" else "Только для тёмной темы",
                    checked = oledEnabled && isDarkActive,
                    enabled = isDarkActive,
                    onCheckedChange = {
                        oledEnabled = it
                        ThemeSettings.setOledEnabled(it)
                        if (it) {
                            ultraContrastEnabled = false
                        }
                    }
                )
                ImpulseToggle(
                    title = "Ультра контраст",
                    description = if (isDarkActive) "Максимальный контраст, белый на чёрном" else "Максимальный контраст, чёрный на белом",
                    checked = ultraContrastEnabled,
                    onCheckedChange = {
                        ultraContrastEnabled = it
                        ThemeSettings.setUltraContrastEnabled(it)
                        if (it) {
                            oledEnabled = false
                        }
                    }
                )
            }
        }

        // ── Hue slider ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Оттенок") {
                val previewColor = hslToColor(hue, 0.88f, 0.58f)
                val shiftedHue = if (isDarkActive) hue + 8f else hue - 6f
                val previewBg = hslToColor(shiftedHue, 0.35f, if (isDarkActive) 0.06f else 0.96f)

                // Preview row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Background swatch
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(previewBg)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    )
                    // Primary color swatch
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(previewColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    )
                    // Secondary / surface
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(previewColor.copy(alpha = 0.18f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${"%.0f".format(hue)}°",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Rainbow slider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = (0..360 step 10).map { hslToColor(it.toFloat(), 0.85f, 0.55f) }
                            )
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Slider(
                        value = hue,
                        onValueChange = {
                            hue = it
                            ThemeSettings.setHue(it)
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.fillMaxSize(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                        ),
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Hue labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf("0°" to "Красный", "60°" to "Жёлтый", "120°" to "Зелёный",
                        "180°" to "Голубой", "240°" to "Синий", "300°" to "Розовый"
                    ).forEach { (deg, _) ->
                        Text(
                            text = deg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
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
                        text = "impulse://secure-chat v2.1",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = JetBrainsMono,
                        fontSize = (MaterialTheme.typography.bodyLarge.fontSize * fontScale)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Масштаб",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${"%.0f".format(fontScale * 100)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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

        // ── Security ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Безопасность") {
                ImpulseToggle(
                    title = "Биометрическая защита",
                    description = "Отпечаток/лицо при запуске",
                    checked = biometricEnabled,
                    onCheckedChange = {
                        biometricEnabled = it
                        serverPreferences.saveBiometricEnabled(it)
                    }
                )
            }
        }
    }
}

// ── Theme mode entry ────────────────────────────────────────────────

private enum class ThemeModeData(
    val mode: ThemeMode,
    val icon: ImageVector,
    val label: String,
) {
    LIGHT(ThemeMode.LIGHT, Icons.Default.LightMode, "Светлая"),
    DARK(ThemeMode.DARK, Icons.Default.DarkMode, "Тёмная"),
    SYSTEM(ThemeMode.SYSTEM, Icons.Default.PhoneAndroid, "Система"),
}
