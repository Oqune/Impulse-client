package com.example.impulse.ui.theme

import kotlinx.serialization.Serializable

@Serializable
data class EffectsConfig(
    val glow: Boolean = false,
    val glitches: Boolean = false,
    val glass: Boolean = false,
    val terminalCursor: Boolean = false,
)
