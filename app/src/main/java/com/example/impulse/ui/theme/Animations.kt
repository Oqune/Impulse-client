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

fun directionalSlideTransition(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(
            animationSpec = tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            initialOffsetX = { fullWidth -> sign * (fullWidth / 4) }
        ) + fadeIn(tween(240)),
        initialContentExit = slideOutHorizontally(
            animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            targetOffsetX = { fullWidth -> -sign * (fullWidth / 4) }
        ) + fadeOut(tween(200))
    )
}

fun subpageSlideTransition(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(
            animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            initialOffsetX = { fullWidth -> sign * fullWidth }
        ) + fadeIn(tween(260)),
        initialContentExit = slideOutHorizontally(
            animationSpec = tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            targetOffsetX = { fullWidth -> -sign * (fullWidth / 3) }
        ) + fadeOut(tween(200))
    )
}

