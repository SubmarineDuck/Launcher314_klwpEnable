package com.bearinmind.launcher314.data

import android.content.Context

/** One-time cleanup of layouts damaged by the old folder bugs (issues #129, #130), run before the home screen loads. */
object HomeLayoutRepair {
    private const val KEY_DONE = "layout_repair_129_130_done"

    fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        try {
            val data = loadHomeScreenData(context)
            repair(data, freeHomeCells(context, data))?.let { saveHomeScreenData(context, it) }
        } catch (e: Exception) {
            android.util.Log.w("HomeLayoutRepair", "skipped", e)
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    /** Empty (page, position) home cells on existing pages, in fill order; apps, folders and widgets take cells. */
    private fun freeHomeCells(context: Context, data: HomeScreenData): List<Pair<Int, Int>> {
        val cols = getHomeGridSize(context)
        val cells = cols * getHomeGridRows(context)
        val pages = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            .getInt("launcher_total_pages", 1).coerceAtLeast(1)
        val widgets = com.bearinmind.launcher314.ui.widgets.WidgetManager.loadPlacedWidgets(context)
        return (0 until pages).flatMap { page ->
            val taken = HashSet<Int>()
            data.apps.filter { it.page == page }.forEach { taken += it.position }
            data.folders.filter { it.page == page }.forEach { taken += it.position }
            widgets.filter { it.page == page }.forEach { w ->
                for (r in w.startRow until w.startRow + w.rowSpan) for (c in w.startColumn until w.startColumn + w.columnSpan) taken += r * cols + c
            }
            (0 until cells).filter { it !in taken }.map { page to it }
        }
    }

    /** Folder entries are "pkg" or "pkg|serial"; grid and dock rows keep the serial apart. */
    private fun entryOf(pkg: String, serial: Long?) = if (serial == null) pkg else "$pkg|$serial"
    private fun gridApp(entry: String, position: Int, page: Int) =
        HomeScreenApp(entry.substringBefore('|'), position, page, entry.substringAfter('|', "").toLongOrNull())

    /** Pure; null when nothing needed fixing. */
    internal fun repair(data: HomeScreenData, freeCells: List<Pair<Int, Int>>): HomeScreenData? {
        // No folder holds the same entry twice (old #130 drops duplicated some).
        fun dedupe(e: List<String>): List<String> {
            val seen = HashSet<String>()
            return e.map { if (it.isEmpty() || seen.add(it)) it else "" }.dropLastWhile { it.isEmpty() }
        }
        var home = data.folders.map { it.copy(appPackageNames = dedupe(it.appPackageNames)) }
        var dock = data.dockFolders.map { it.copy(appPackageNames = dedupe(it.appPackageNames)) }
        var apps = data.apps.filter { it.page >= 0 }
        var dockApps = data.dockApps

        // Sub-folders not linked from the grid or dock are orphans (#129).
        val byId = home.associateBy { it.id }
        val reachable = HashSet<String>()
        val queue = ArrayDeque((home.filter { it.page >= 0 }.flatMap { it.appPackageNames } + dock.flatMap { it.appPackageNames })
            .filter { isFolderEntry(it) }.map { folderEntryId(it) })
        while (queue.isNotEmpty()) {
            val f = byId[queue.removeFirst()] ?: continue
            if (f.page < 0 && reachable.add(f.id)) f.appPackageNames.filter { isFolderEntry(it) }.forEach { queue += folderEntryId(it) }
        }
        val orphanIds = home.filter { it.page < 0 && it.id !in reachable }.map { it.id }.toSet()
        val visible = HashSet<String>()
        apps.forEach { visible += entryOf(it.packageName, it.userSerial) }
        dockApps.forEach { visible += entryOf(it.packageName, it.userSerial) }
        home.filter { it.id !in orphanIds }.forEach { visible += it.appPackageNames }
        dock.forEach { visible += it.appPackageNames }

        // Apps stranded on hidden page -2 (#129), then orphans' apps, unless visible elsewhere.
        val stranded = data.apps.filter { it.page < 0 }
        val pending = ArrayDeque((stranded.map { entryOf(it.packageName, it.userSerial) } +
            home.filter { it.id in orphanIds }.flatMap { it.appPackageNames }.filter { !isFolderEntry(it) })
            .filter { it.isNotEmpty() && it !in visible }.distinct())

        // Old sub-folder dissolves left dangling links: refill them in order; spare links empty out.
        fun refill(e: List<String>) = e.map { if (isFolderEntry(it) && folderEntryId(it) !in byId) pending.removeFirstOrNull() ?: "" else it }
            .dropLastWhile { it.isEmpty() }
        home = home.map { if (it.id in orphanIds) it else it.copy(appPackageNames = refill(it.appPackageNames)) }
        dock = dock.map { it.copy(appPackageNames = refill(it.appPackageNames)) }

        // The rest go onto empty home cells; what doesn't fit stays put (the drawer still lists it).
        val cells = freeCells.iterator()
        val unplaced = HashSet<String>()
        for (entry in pending) if (cells.hasNext()) cells.next().let { (pg, pos) -> apps = apps + gridApp(entry, pos, pg) } else unplaced += entry
        apps = apps + stranded.filter { entryOf(it.packageName, it.userSerial) in unplaced }
        home = home.filter { it.id !in orphanIds || it.appPackageNames.any { e -> e in unplaced } }

        // Folders this left empty or with one app dissolve, like after a normal remove.
        val before = (data.folders.map { it.id to it.appPackageNames } + data.dockFolders.map { it.id to it.appPackageNames }).toMap()
        val touched = (home.filter { it.id !in orphanIds && it.appPackageNames != before[it.id] }.map { it.id } +
            dock.filter { it.appPackageNames != before[it.id] }.map { it.id }).toSet()
        fun single(e: List<String>) = e.count { it.isNotEmpty() } <= 1 && e.none { isFolderEntry(it) }
        while (true) {
            val f = home.firstOrNull { it.id in touched && single(it.appPackageNames) }
            if (f != null) {
                val last = f.appPackageNames.firstOrNull { it.isNotEmpty() }
                if (f.page >= 0) {
                    home = home.filter { it.id != f.id }
                    if (last != null) apps = apps + gridApp(last, f.position, f.page)
                } else {
                    val (h, d) = dissolveSubFolder(home, dock, f.id, last)
                    home = h
                    dock = d
                }
                continue
            }
            val df = dock.firstOrNull { it.id in touched && single(it.appPackageNames) } ?: break
            dock = dock.filter { it.id != df.id }
            df.appPackageNames.firstOrNull { it.isNotEmpty() }?.let { last ->
                dockApps = dockApps + DockApp(last.substringBefore('|'), df.position, df.page, last.substringAfter('|', "").toLongOrNull())
            }
        }

        val out = HomeScreenData(apps = apps, dockApps = dockApps, folders = home, dockFolders = dock)
        return if (out == data) null else out
    }
}
