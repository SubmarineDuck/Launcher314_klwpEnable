package com.bearinmind.launcher314.data

import android.content.Context
import java.io.File

/** Icon folders live in filesDir: Android empties cacheDir when storage runs low, which blanked icons and dropped per-app icon-pack picks (issue #35). */
object IconStore {
    const val APP_ICONS = "app_icons"
    const val ICON_PACK = "icon_pack_cache"
    const val ICON_PACK_APP_ICONS = "icon_pack_app_icons"
    private val DIRS = listOf(APP_ICONS, ICON_PACK, ICON_PACK_APP_ICONS)
    private val PATH_FILES = listOf("app_customizations.json", "drawer_app_cache.json", "home_screen_data.json", "drawer_data.json")

    fun dir(context: Context, name: String): File = File(context.filesDir, name).also { if (!it.exists()) it.mkdirs() }

    /** Moves old cacheDir icon folders over and repoints saved paths; a quick no-op once done (reruns fix restored backups). */
    fun migrate(context: Context) {
        for (name in DIRS) {
            val old = File(context.cacheDir, name)
            if (!old.exists()) continue
            val new = File(context.filesDir, name)
            if (!new.exists() && old.renameTo(new)) continue
            new.mkdirs()
            old.listFiles()?.forEach { f -> if (!File(new, f.name).exists()) f.renameTo(File(new, f.name)) }
            old.deleteRecursively()
        }
        for (fileName in PATH_FILES) {
            val f = File(context.filesDir, fileName)
            if (!f.exists()) continue
            try {
                val text = f.readText()
                if (DIRS.none { text.contains("/cache/$it/") }) continue
                var fixed = text
                DIRS.forEach { fixed = fixed.replace("/cache/$it/", "/files/$it/") }
                f.writeText(fixed)
            } catch (_: Exception) { }
        }
    }
}
