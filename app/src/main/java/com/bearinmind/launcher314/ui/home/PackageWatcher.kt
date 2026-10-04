package com.bearinmind.launcher314.ui.home

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.bearinmind.launcher314.data.HomeAppScan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Issue #127: refresh the home screen when an app is installed, removed or disabled (any profile); waits for idle so a drag is never reset. */
@Composable
internal fun RefreshOnPackageChanges(isBusy: () -> Boolean, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val events = remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val callback = object : LauncherApps.Callback() {
            private fun changed() {
                HomeAppScan.dirty = true
                events.intValue++
            }
            override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { launcherApps.unregisterCallback(callback) }
    }
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(Unit) {
        snapshotFlow { events.intValue }.collectLatest { n ->
            if (n == 0) return@collectLatest
            delay(500) // one install fires several events
            while (isBusy()) delay(200) // a refresh reloads the layout, which would cut a drag short
            refresh()
        }
    }
}
