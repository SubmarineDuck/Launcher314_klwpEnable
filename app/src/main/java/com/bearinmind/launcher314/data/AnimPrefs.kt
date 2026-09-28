package com.bearinmind.launcher314.data

import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap

/** Issue #111: process-wide mirror of "Reduce animations" — a pref read per icon per composition would cost more than the animations it drops. */
object AnimPrefs {
    @Volatile
    var reduce: Boolean = false

    /** Issue #120: depth blur behind the drawer; off drops the wallpaper and home screen blur. */
    @Volatile
    var blur: Boolean = true

    fun refresh(context: Context) {
        reduce = getReduceAnimations(context)
        blur = getBlurEffects(context)
    }
}

/** [spec] normally, an instant snap when Reduce animations is on. */
fun <T> lessAnim(spec: AnimationSpec<T>): AnimationSpec<T> = if (AnimPrefs.reduce) snap() else spec
