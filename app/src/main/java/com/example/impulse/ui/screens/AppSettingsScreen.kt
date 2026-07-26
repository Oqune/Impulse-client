package com.example.impulse.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    var selectedPreset by remember { mutableStateOf(ThemeSettings.preset) }
    var fontScale by remember { mutableStateOf(ThemeSettings.fontScale) }
    var oledEnabled by remember { mutableStateOf(ThemeSettings.oledEnabled) }
    var ultraContrastEnabled by remember { mutableStateOf(ThemeSettings.ultraContrastEnabled) }

    val isDarkActive = when (selectedTheme) {
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
    }

    var biometricEnabled by remember { mutableStateOf(serverPreferences.getBiometricEnabled()) }

    val presets = ThemePreset.entries

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

        // ── Theme presets — Grid ────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = "Тема") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((presets.size + 1) / 2 * 118).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = false,
                ) {
                    itemsIndexed(presets) { _, preset ->
                        ThemePresetCard(
                            preset = preset,
                            isSelected = preset == selectedPreset,
                            isDark = isDarkActive,
                            onClick = {
                                selectedPreset = preset
                                ThemeSettings.setThemePreset(preset)
                            }
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

// ── Theme preset card (2-column grid) ───────────────────────────────

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val previewPrimary = if (isDark) preset.previewPrimary else preset.previewPrimaryLight
    val previewSecondary = if (isDark) preset.previewSecondary else preset.previewSecondaryLight
    val previewBg = if (isDark) preset.previewBg else preset.previewBgLight

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) previewPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        label = "card_border"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                previewPrimary.copy(alpha = 0.10f)
            else
                MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Color swatches: bg, secondary, primary
            Row(
                horizontalArrangement = Arrangement.spacedBy((-4).dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(previewBg)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(previewSecondary)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(previewPrimary)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
            }

            Column {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) previewPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSelected) {
                    Text(
                        text = "Выбрана",
                        style = MaterialTheme.typography.labelSmall,
                        color = previewPrimary.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
