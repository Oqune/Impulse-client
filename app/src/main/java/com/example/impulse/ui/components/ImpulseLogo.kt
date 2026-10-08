package com.example.impulse.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.impulse.R

/** Shared optical mark. Its material stays intact while UI accent colours vary. */
@Composable
fun ImpulseLogo(modifier: Modifier = Modifier) {
    val artwork = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        R.drawable.impulse_logo_frame
    } else {
        R.drawable.impulse_mark
    }
    Image(
        painter = painterResource(artwork),
        contentDescription = stringResource(R.string.impulse_logo_description),
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
