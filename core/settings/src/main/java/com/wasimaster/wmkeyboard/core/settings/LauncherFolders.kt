package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A named group of apps in the app-launcher tool (#919): "Banking" holding the
 * three bank apps, drawn as one cell ahead of the loose apps in the grid.
 *
 * [apps] are app keys, not bare package names: the package for the keyboard's
 * own profile and `package@serial` for another one, such as the work profile
 * (#924), the same keys the pinned, hidden and recent lists keep. An app is
 * in one folder at most, and [apps] is the order inside the folder.
 *
 * Android has no public API that hands an input method the home screen's own
 * folders, so these are the keyboard's: made by hand, or seeded once from
 * each app's Play category.
 */
data class LauncherFolder(
    val id: String,
    val name: String,
    val apps: List<String>,
)

/** The longest folder name the keyboard keeps. */
const val LAUNCHER_FOLDER_NAME_MAX = 40

/** The folder [app] is filed in, or null. */
fun List<LauncherFolder>.launcherFolderOf(app: String): LauncherFolder? = firstOrNull { app in it.apps }

/** The folder with this [id], or null. */
fun List<LauncherFolder>.launcherFolderById(id: String?): LauncherFolder? =
    if (id == null) null else firstOrNull { it.id == id }

/** An id no folder in this list uses yet: `a1`, `a2`, … */
fun List<LauncherFolder>.nextLauncherFolderId(): String {
    val taken = mapTo(HashSet()) { it.id }
    var n = 1
    while ("a$n" in taken) n++
    return "a$n"
}

private fun cleanFolderName(name: String): String =
    name.replace('\n', ' ').replace('\t', ' ').trim().take(LAUNCHER_FOLDER_NAME_MAX)

/**
 * A new folder [id] at the end, holding [app], which leaves any folder it was
 * in first. A blank [name] falls back to "…" so the cell never has no label.
 */
fun List<LauncherFolder>.withNewLauncherFolder(id: String, name: String, app: String): List<LauncherFolder> =
    withoutLauncherApp(app) + LauncherFolder(id, cleanFolderName(name).ifEmpty { "…" }, listOf(app))

/** [app] filed at the end of [folderId], out of whatever folder it was in. */
fun List<LauncherFolder>.withLauncherAppIn(app: String, folderId: String): List<LauncherFolder> {
    val folder = launcherFolderById(folderId) ?: return this
    if (app in folder.apps) return this
    return withoutLauncherApp(app).map { if (it.id == folderId) it.copy(apps = it.apps + app) else it }
}

/** [app] back to a cell of its own. A folder left empty goes with it. */
fun List<LauncherFolder>.withoutLauncherApp(app: String): List<LauncherFolder> =
    mapNotNull { folder ->
        if (app !in folder.apps) return@mapNotNull folder
        val rest = folder.apps - app
        if (rest.isEmpty()) null else folder.copy(apps = rest)
    }

/** The folder renamed; a blank name keeps the old one. */
fun List<LauncherFolder>.renamedLauncherFolder(id: String, name: String): List<LauncherFolder> {
    val clean = cleanFolderName(name)
    if (clean.isEmpty()) return this
    return map { if (it.id == id) it.copy(name = clean) else it }
}

/** The folder [by] places earlier (negative) or later in the grid, clamped to the ends. */
fun List<LauncherFolder>.movedLauncherFolder(id: String, by: Int): List<LauncherFolder> {
    val from = indexOfFirst { it.id == id }
    if (from < 0) return this
    val to = (from + by).coerceIn(0, lastIndex)
    if (to == from) return this
    val next = toMutableList()
    next.add(to, next.removeAt(from))
    return next
}

/** The folder gone; its apps go back to cells of their own. */
fun List<LauncherFolder>.withoutLauncherFolder(id: String): List<LauncherFolder> = filterNot { it.id == id }

/**
 * Files apps into folders by group, the one-tap fallback for the home
 * screen's folders that the keyboard cannot read: [groups] maps a folder name
 * (an app's Play category, say) to the apps in it.
 *
 * Only apps in no folder yet are moved, so what the user filed by hand stays
 * put. A group joins an existing folder of the same name (case aside);
 * otherwise it becomes a new folder, but only with at least [minApps] apps,
 * so one game does not earn a "Games" folder of one. Group order is kept.
 */
fun List<LauncherFolder>.withGroupedLauncherApps(
    groups: Map<String, List<String>>,
    minApps: Int = 2,
): List<LauncherFolder> {
    val filed = flatMapTo(HashSet()) { it.apps }
    var next = this
    for ((rawName, apps) in groups) {
        val name = cleanFolderName(rawName)
        if (name.isEmpty()) continue
        val loose = apps.distinct().filter { it !in filed }
        if (loose.isEmpty()) continue
        val home = next.firstOrNull { it.name.equals(name, ignoreCase = true) }
        next = if (home != null) {
            next.map { if (it.id == home.id) it.copy(apps = it.apps + loose) else it }
        } else {
            if (loose.size < minApps) continue
            next + LauncherFolder(next.nextLauncherFolderId(), name, loose)
        }
        filed += loose
    }
    return next
}

/**
 * Drops what cannot be a folder: a repeated id, apps filed twice (the first
 * folder keeps them) and folders left empty. Run on everything read back from
 * storage, so a hand-edited backup cannot break the grid.
 */
fun List<LauncherFolder>.normalizedLauncherFolders(): List<LauncherFolder> {
    val seen = HashSet<String>()
    val ids = HashSet<String>()
    return mapNotNull { folder ->
        if (!ids.add(folder.id)) return@mapNotNull null
        val apps = folder.apps.filter { it.isNotBlank() && seen.add(it) }
        if (apps.isEmpty()) null else folder.copy(apps = apps)
    }
}

/**
 * The stored form: a JSON array of `{"id", "name", "apps": [keys]}`. Empty
 * encodes as null, so the preference is removed rather than kept empty.
 */
fun encodeLauncherFolders(folders: List<LauncherFolder>): String? {
    if (folders.isEmpty()) return null
    return buildJsonArray {
        for (folder in folders) {
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(folder.id))
                    put("name", JsonPrimitive(folder.name))
                    put("apps", JsonArray(folder.apps.map { JsonPrimitive(it) }))
                },
            )
        }
    }.toString()
}

/** The inverse of [encodeLauncherFolders]; malformed entries are dropped. */
fun decodeLauncherFolders(raw: String?): List<LauncherFolder> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching {
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            LauncherFolder(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(LAUNCHER_FOLDER_NAME_MAX),
                apps = obj["apps"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            )
        }.getOrNull()
    }.normalizedLauncherFolders()
}
