package com.example.impulse.locale

import android.content.Context
import androidx.annotation.StringRes

fun Context.str(@StringRes resId: Int): String = getString(resId)
