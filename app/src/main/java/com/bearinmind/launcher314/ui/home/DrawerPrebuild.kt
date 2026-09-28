package com.bearinmind.launcher314.ui.home

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Issue #115: the next drawer is built while idle and parked off-screen (a swipe-up only slides it in); the used one is dropped on close. */
@Stable
internal class DrawerPrebuild {
    /** A fresh drawer is composed and parked off-screen. */
    var ready by mutableStateOf(false)
    var fingerDown = false
    var lastTouchUp = 0L
}

/** Drops the drawer on close and builds the next one at the first idle moment. */
@Composable
internal fun rememberDrawerPrebuild(isOpen: () -> Boolean, isHomeBusy: () -> Boolean): DrawerPrebuild {
    val prebuild = remember { DrawerPrebuild() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(prebuild, lifecycle) {
        snapshotFlow { isOpen() }.collectLatest { open ->
            if (open) return@collectLatest
            prebuild.ready = false
            // Idle: launcher in front, home pages at rest, a beat after the last touch.
            lifecycle.withResumed { }
            delay(500)
            while (prebuild.fingerDown || isHomeBusy() ||
                SystemClock.uptimeMillis() - prebuild.lastTouchUp < 300) delay(100)
            prebuild.ready = true
        }
    }
    return prebuild
}

/** Composes [content] while the drawer is open or a pre-built one is waiting. */
@Composable
internal fun PrebuiltDrawer(prebuild: DrawerPrebuild, isOpen: () -> Boolean, content: @Composable () -> Unit) {
    if (isOpen() || prebuild.ready) content()
}

/** Composes [content] only while [visible]; reading it here keeps a flip from rebuilding the caller. */
@Composable
internal fun ShownWhen(visible: () -> Boolean, content: @Composable () -> Unit) {
    if (visible()) content()
}

/** Watches (never consumes) touches so the build waits for a pause between gestures. */
internal fun Modifier.watchTouches(prebuild: DrawerPrebuild): Modifier = pointerInput(prebuild) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        prebuild.fingerDown = true
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            prebuild.fingerDown = false
            prebuild.lastTouchUp = SystemClock.uptimeMillis()
        }
    }
}
