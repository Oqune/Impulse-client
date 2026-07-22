package com.example.impulse.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

val fadeThroughTransition = ContentTransform(
    fadeIn(tween(200)) + slideInHorizontally(
        tween(200, delayMillis = 100),
        { it / 8 }
    ),
    fadeOut(tween(200, delayMillis = 100)) + slideOutHorizontally(
        tween(200),
        { -it / 8 }
    ),
)

val slideInTransition = ContentTransform(
    fadeIn(tween(200)) + slideInHorizontally(tween(250)),
    fadeOut(tween(150)) + slideOutHorizontally(tween(250)),
)
