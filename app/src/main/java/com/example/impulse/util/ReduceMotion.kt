package com.example.impulse.util

import android.content.Context
import android.provider.Settings

/**
 * True when the system animator-duration scale is 0 ("remove animations" in
 * accessibility settings). Used to disable infinite animations so the UI is
 * idle (and battery-friendly) for users who asked for reduced motion.
 */
fun isReduceMotionEnabled(context: Context): Boolean {
    return try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (_: Exception) {
        false
    }
}
