package com.bearinmind.launcher314.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import com.bearinmind.launcher314.helpers.ProfileType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Cross-open + cross-process cache of the drawer's app list.
 *
 * The drawer composable (AppDrawerScreen) is disposed every time the drawer
 * closes, so its `allApps` state used to reset to empty on every open — forcing
 * a full LauncherApps re-enumeration (+ getPackageInfo + APK size per app) and
 * a loading spinner each time. On a cold start after the process was memory-
 * killed (e.g. returning from a fullscreen YouTube video on a 6GB phone) the
 * spinner lasted ~1s.
 *
 * This cache lets the drawer paint instantly:
 *  - `memory`  survives the drawer being disposed on close (lost on process death).
 *  - the JSON file survives process death (the YouTube-kill case).
 *
 * The cached list is only a HINT — getInstalledApps() reruns in the background whenever [isFresh] says it may be stale.
 */
object DrawerAppCache {
    @Volatile
    private var memory: List<AppInfo>? = null

    private const val FILE = "drawer_app_cache.json"

    @Serializable
    private data class CachedApp(
        val name: String,
        val packageName: String,
        val iconPath: String,
        val installTime: Long,
        val lastUpdateTime: Long,
        val sizeBytes: Long,
        val userSerial: Long?,
        // ProfileType is re-derived on the next live enumeration; stored by
        // name so a cold start still shows the right profile grouping.
        val profileType: String
    )

    /** In-memory cache, if populated (survives drawer close, not process death). */
    fun memoryApps(): List<AppInfo>? = memory

    /**
     * Read the on-disk cache (cold start) and seed `memory`. Returns null when
     * there's no usable cache yet. Cheap JSON read — call off the main thread.
     */
    fun diskApps(context: Context): List<AppInfo>? {
        memory?.let { return it }
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return null
            val cached = Json.decodeFromString<List<CachedApp>>(f.readText())
            val apps = cached.map {
                AppInfo(
                    name = it.name,
                    packageName = it.packageName,
                    iconPath = it.iconPath,
                    installTime = it.installTime,
                    lastUpdateTime = it.lastUpdateTime,
                    sizeBytes = it.sizeBytes,
                    userSerial = it.userSerial,
                    profileType = runCatching { ProfileType.valueOf(it.profileType) }
                        .getOrDefault(ProfileType.PERSONAL)
                )
            }
            memory = apps
            apps.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** Warm `memory` from disk at process start so the first open is instant. */
    fun warm(context: Context) {
        if (memory == null) diskApps(context)
    }

    // Issue #115: lets the drawer rebuilt after each close skip the full rescan when nothing it depends on changed.
    @Volatile private var scanSeq = -1
    @Volatile private var scanEnv: String? = null
    @Volatile private var scanAt = 0L

    /** Call right before a live scan; hand the result to [markScanned] afterwards. */
    fun beginScan(context: Context): Int {
        if (Build.VERSION.SDK_INT < 26) return -1
        return try {
            val since = scanSeq.coerceAtLeast(0)
            context.packageManager.getChangedPackages(since)?.sequenceNumber ?: since
        } catch (_: Exception) { -1 }
    }

    fun markScanned(context: Context, seq: Int) {
        scanEnv = try { scanEnvironment(context) } catch (_: Exception) { null }
        scanAt = SystemClock.elapsedRealtime()
        scanSeq = seq
    }

    /** True when no package changed since the last scan and its inputs look the same. */
    fun isFresh(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        if (memory.isNullOrEmpty() || scanSeq < 0) return false
        if (SystemClock.elapsedRealtime() - scanAt > 5 * 60_000L) return false
        return try {
            context.packageManager.getChangedPackages(scanSeq) == null &&
                scanEnvironment(context) == scanEnv
        } catch (_: Exception) { false }
    }

    /** Labels (locale), profiles (work/private, paused), icon-pack picks and purged icon files. */
    private fun scanEnvironment(context: Context): String {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val profiles = launcherApps.profiles.joinToString(",") { user ->
            "${user.hashCode()}${if (userManager.isQuietModeEnabled(user)) "q" else ""}"
        }
        val packDir = File(context.cacheDir, "icon_pack_cache")
        val iconCount = File(context.cacheDir, "app_icons").list()?.size ?: 0
        return "${context.resources.configuration.locales.toLanguageTags()}|$profiles|" +
            "${packDir.list()?.size}:${packDir.lastModified()}|$iconCount"
    }

    /** Update both tiers after a fresh enumeration. No write if unchanged. */
    fun update(context: Context, apps: List<AppInfo>) {
        if (memory == apps) return
        memory = apps
        try {
            val dto = apps.map {
                CachedApp(
                    it.name, it.packageName, it.iconPath, it.installTime,
                    it.lastUpdateTime, it.sizeBytes, it.userSerial, it.profileType.name
                )
            }
            File(context.filesDir, FILE).writeText(Json.encodeToString(dto))
        } catch (_: Exception) {
        }
    }
}
