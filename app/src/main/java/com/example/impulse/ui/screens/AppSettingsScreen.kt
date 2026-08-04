package com.example.impulse.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.impulse.R
import com.example.impulse.locale.LocaleSettings
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.impulse.data.ServerPreferences
import com.example.impulse.ui.theme.*
import com.example.impulse.util.FileLogger
import java.io.File

@Composable
fun AppSettingsContent(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val serverPreferences = remember { ServerPreferences(context) }
    val scope = rememberCoroutineScope()

    var selectedTheme by remember { mutableStateOf(ThemeSettings.themeMode) }
    var hue by remember { mutableFloatStateOf(ThemeSettings.hue) }
    var fontScale by remember { mutableStateOf(ThemeSettings.fontScale) }
    var themeVariant by remember { mutableStateOf(ThemeSettings.themeVariant) }

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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Theme mode ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.app_settings_display_mode)) {
                val modeEntries = ThemeModeData.entries
                val modeIndex = modeEntries.indexOfFirst { it.mode == selectedTheme }.coerceAtLeast(0)
                val modePager = rememberPagerState(
                    initialPage = modeIndex,
                    pageCount = { modeEntries.size }
                )
                // Apply the theme only once the swipe settles — avoids the
                // jarring mid-drag theme switch.
                LaunchedEffect(modePager) {
                    snapshotFlow { modePager.currentPage }
                        .drop(1)
                        .collect { page ->
                            modePager.animateScrollToPage(page)
                            val entry = modeEntries[page]
                            selectedTheme = entry.mode
                            ThemeSettings.setThemeMode(entry.mode)
                        }
                }
                HorizontalPager(
                    state = modePager,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(86.dp),
                    beyondViewportPageCount = 1,
                ) { page ->
                    val entry = modeEntries[page]
                    val isSelected = selectedTheme == entry.mode
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceContainer,
                        label = "mode_bg"
                    )
                    val borderColor by animateColorAsState(
                        targetValue = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        label = "mode_border"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .fillMaxHeight()
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
                                    scope.launch { modePager.animateScrollToPage(page) }
                                }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // Preview square, same visual language as the
                            // variant carousel below.
                            val preview = when (entry.mode) {
                                ThemeMode.LIGHT -> Color(0xFFFFFFFF)
                                ThemeMode.DARK -> Color(0xFF121212)
                                ThemeMode.SYSTEM -> Color(0xFF888888)
                            }
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(preview)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = entry.icon,
                                    contentDescription = stringResource(entry.labelRes),
                                    tint = if (entry.mode == ThemeMode.LIGHT) Color.Black else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(entry.labelRes),
                                style = MaterialTheme.typography.labelMedium,
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
                // Dots indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    modeEntries.indices.forEach { i ->
                        val selected = i == modePager.currentPage
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(if (selected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                // Theme variant carousel — swipe to change: Classic / Material
                // You / OLED / Ultra Contrast. OLED is disabled in light mode.
                val variants = buildList {
                    add(ThemeVariant.CLASSIC to R.string.app_settings_variant_classic)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(ThemeVariant.MATERIAL_YOU to R.string.app_settings_variant_material_you)
                    }
                    add(ThemeVariant.ULTRA_CONTRAST to R.string.app_settings_variant_ultra_contrast)
                    add(ThemeVariant.OLED to R.string.app_settings_oled)
                }
                val variantIndex = variants.indexOfFirst { it.first == themeVariant }
                val pagerState = rememberPagerState(
                    initialPage = variantIndex.coerceAtLeast(0),
                    pageCount = { variants.size }
                )
                // Apply the variant once the swipe settles (no jarring switch
                // mid-drag) and ease the pager to the final page.
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }
                        .drop(1)
                        .collect { page ->
                            pagerState.animateScrollToPage(page)
                            val (v, _) = variants[page]
                            themeVariant = v
                            ThemeSettings.setThemeVariant(v)
                        }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(118.dp),
                    // Peek: keep the neighbouring variants partially visible so
                    // the carousel reads as swipeable, not as cramped options.
                    beyondViewportPageCount = 1,
                ) { page ->
                    val (variant, labelRes) = variants[page]
                    val isOled = variant == ThemeVariant.OLED
                    val enabled = !isOled || isDarkActive
                    val isSelected = variant == themeVariant
                    val preview = when (variant) {
                        ThemeVariant.CLASSIC -> if (isDarkActive) Color(0xFF121212) else Color(0xFFFFFFFF)
                        ThemeVariant.MATERIAL_YOU -> MaterialTheme.colorScheme.primary
                        ThemeVariant.OLED -> Color(0xFF000000)
                        ThemeVariant.ULTRA_CONTRAST -> if (isDarkActive) Color(0xFF000000) else Color(0xFFFFFFFF)
                    }
                    val desc = when (variant) {
                        ThemeVariant.CLASSIC -> stringResource(R.string.app_settings_variant_classic_desc)
                        ThemeVariant.MATERIAL_YOU -> stringResource(R.string.app_settings_variant_material_you_desc)
                        ThemeVariant.ULTRA_CONTRAST -> stringResource(R.string.app_settings_variant_ultra_contrast_desc)
                        ThemeVariant.OLED -> stringResource(R.string.app_settings_variant_oled_desc)
                    }
                    // Center the card inside the page so neighbours peek on
                    // both edges (fillMaxWidth alone pinned the card left).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else if (enabled)
                                MaterialTheme.colorScheme.surfaceContainer
                            else
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(enabled = enabled) {
                                    // Tap a visible neighbour: glide the pager
                                    // to it; the settled-sync applies the theme.
                                    scope.launch { pagerState.animateScrollToPage(page) }
                                }
                                .padding(vertical = 12.dp, horizontal = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(preview)
                                    .then(
                                        if (variant == ThemeVariant.CLASSIC)
                                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(labelRes).take(2).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when (variant) {
                                        ThemeVariant.CLASSIC -> if (isDarkActive) Color.White else Color.Black
                                        ThemeVariant.MATERIAL_YOU -> MaterialTheme.colorScheme.onPrimary
                                        ThemeVariant.OLED -> Color.White
                                        ThemeVariant.ULTRA_CONTRAST -> if (isDarkActive) Color.White else Color.Black
                                    },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                maxLines = 1,
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                    }
                }
                // Dots indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    variants.indices.forEach { i ->
                        val selected = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(if (selected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
            }
        }

        // ── Language selector ──────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.app_settings_language)) {
                var expanded by remember { mutableStateOf(false) }
                val languages = listOf(
                    "en" to R.string.lang_english,
                    "ru" to R.string.lang_russian,
                )
                val currentLang = LocaleSettings.languageCode
                val currentLabel = stringResource(
                    languages.firstOrNull { it.first == currentLang }?.second
                        ?: R.string.lang_english
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = currentLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        languages.forEach { (code, labelRes) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(labelRes),
                                        color = if (code == currentLang)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    LocaleSettings.setLanguage(code)
                                    (context as? Activity)?.recreate()
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Hue slider (hidden for Material You — the wallpaper drives colour) ──
        if (themeVariant != ThemeVariant.MATERIAL_YOU) {
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.app_settings_hue)) {
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
                            // Local state only while dragging — do NOT write
                            // SharedPreferences + trigger a whole-tree recompose
                            // on every frame (Bug: "slider recomposes the app
                            // per frame").
                            hue = it
                        },
                        onValueChangeFinished = {
                            ThemeSettings.setHue(hue)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
        }

        // ── Font scale ─────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.app_settings_font_scale)) {
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
                        text = stringResource(R.string.app_settings_scale),
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
                        // Local state while dragging; commit once on release
                        // (Bug: "slider recomposes the app per frame").
                        fontScale = it
                    },
                    onValueChangeFinished = {
                        ThemeSettings.setFontScale(fontScale)
                    },
                    valueRange = 0.8f..1.4f
                )
            }
        }

        // ── Security ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.app_settings_security)) {
                ImpulseToggle(
                    title = stringResource(R.string.app_settings_biometric),
                    description = stringResource(R.string.app_settings_biometric_desc),
                    checked = biometricEnabled,
                    onCheckedChange = {
                        biometricEnabled = it
                        serverPreferences.saveBiometricEnabled(it)
                    }
                )
            }
        }

        // ── Debug ──────────────────────────────────────────────
        ImpulseCard {
            ImpulseSection(title = stringResource(R.string.app_settings_debug)) {
                val logPath = FileLogger.getLogPath()
                val logSize = FileLogger.getLogSize()
                val sizeKB = "%.1f".format(logSize / 1024.0)
                ImpulseClickableRow(
                    title = stringResource(R.string.app_settings_send_logs),
                    description = stringResource(R.string.app_settings_session_log, sizeKB),
                    onClick = {
                        val logFile = logPath?.let { File(it) }
                        if (logFile == null || !logFile.exists()) {
                            Toast.makeText(context, context.getString(R.string.app_settings_log_empty), Toast.LENGTH_SHORT).show()
                            return@ImpulseClickableRow
                        }
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Impulse session log")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, context.getString(R.string.app_settings_share_logs)))
                        } catch (e: Exception) {
                            Toast.makeText(context, "${context.getString(R.string.common_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
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
    val labelRes: Int,
) {
    LIGHT(ThemeMode.LIGHT, Icons.Default.LightMode, R.string.app_settings_theme_light),
    DARK(ThemeMode.DARK, Icons.Default.DarkMode, R.string.app_settings_theme_dark),
    SYSTEM(ThemeMode.SYSTEM, Icons.Default.PhoneAndroid, R.string.app_settings_theme_system),
}
