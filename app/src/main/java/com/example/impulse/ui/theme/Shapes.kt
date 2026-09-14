package com.example.impulse.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Impulse Design System — Standardized Geometric Hierarchy
 *
 * 4-Tier Scale:
 * - Pill: CircleShape (dockbar, active tab capsules, avatars, status dots)
 * - Large: 16.dp (all cards, modal surfaces, sheet containers)
 * - Medium: 12.dp (all buttons, text fields, menu rows, dialog controls)
 * - Small: 8.dp (chips, tech badges, status tags, preview squares)
 * - Micro: 4.dp (inline code pills, micro hash badges)
 */
val ImpulseShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Semantic standard tokens
val PillShape = CircleShape
val CardShape = RoundedCornerShape(16.dp)
val ButtonShape = RoundedCornerShape(12.dp)
val InputShape = RoundedCornerShape(12.dp)
val SwitchShape = RoundedCornerShape(12.dp)
val ChipShape = RoundedCornerShape(8.dp)
val MicroShape = RoundedCornerShape(4.dp)

// Standard border width
val StandardBorderWidth = 1.dp

@Composable
fun standardCardBorder(
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
): BorderStroke = BorderStroke(StandardBorderWidth, color)

@Composable
fun standardActiveCardBorder(
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
): BorderStroke = BorderStroke(StandardBorderWidth, color)
