package com.wasimaster.wmkeyboard.core.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build

/**
 * One activity that takes a share (#970): an app's "Send", "Save to Drive",
 * "Message". Plain strings, not a ComponentName, so JVM tests can build one.
 */
data class ShareTarget(
    val packageName: String,
    val activityName: String,
    /** The activity's own label, which is what tells two targets of one app apart. */
    val label: String,
    /** The app's name; the same as [label] for most apps. */
    val appLabel: String = label,
) {
    /** What the pinned and recent lists store. Activity names never contain a slash. */
    val key: String get() = "$packageName/$activityName"
}

/** What the share sheet shows, in order: the pinned and recent row, then every target. */
data class ShareLayout(
    /** Pinned first in the user's order, then the recent ones, newest first, each once. */
    val shortcuts: List<ShareTarget>,
    /** Every target the search lets through, by name. */
    val all: List<ShareTarget>,
)

/** What is being shared, read off the send intent, for the sheet's preview line. */
data class SharePreview(
    val text: String? = null,
    val subject: String? = null,
    /** Files riding on the share; zero for a text share. */
    val files: Int = 0,
    val mimeType: String? = null,
    /** The first file, for the sheet's Copy and Open. */
    val firstUri: Uri? = null,
) {
    /** The text when it is a single web link and nothing else, for the sheet's Open. */
    val link: String? get() = ShareTargets.linkIn(text)
}

/**
 * The keyboard's own share sheet (#970), the half both processes share: the
 * keyboard's panel and the settings app's sheet ask the package manager the
 * same question and lay the answer out the same way.
 *
 * Package visibility: the manifest's `<queries>` lists SEND and SEND_MULTIPLE
 * for any type, which is all it takes for these answers to be complete.
 */
object ShareTargets {
    /** How many recent targets the row keeps. */
    const val MAX_RECENTS = 8

    /** Every activity that takes [send], deduplicated, in the package manager's order. */
    fun query(context: Context, send: Intent): List<ShareTarget> {
        val pm = context.packageManager
        // The probe is the share without anything pointing it at one app.
        val probe = Intent(send).setComponent(null).setPackage(null)
        val infos: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull().orEmpty()
        return infos.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            // Another app's private share screen throws on launch; the
            // system's chooser leaves those out too.
            if (!activity.exported && activity.packageName != context.packageName) return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: activity.packageName
            val appLabel = runCatching { activity.applicationInfo.loadLabel(pm).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: label
            ShareTarget(activity.packageName, activity.name, label, appLabel)
        }.distinctBy { it.key }
    }

    /**
     * [targets] as the sheet lays them out. A search drops the shortcut row
     * (the grid is the answer) and puts names that start with the query
     * ahead of names that only contain it.
     */
    fun arrange(
        targets: List<ShareTarget>,
        pinned: List<String>,
        recents: List<String>,
        query: String = "",
    ): ShareLayout {
        val q = query.trim()
        if (q.isNotEmpty()) return ShareLayout(emptyList(), filter(targets, q))
        val byKey = targets.associateBy { it.key }
        val shortcuts = (pinned + recents).distinct().mapNotNull { byKey[it] }
        return ShareLayout(shortcuts, targets.sortedWith(byName))
    }

    /** The targets whose name, app name or package holds [query], best matches first. */
    fun filter(targets: List<ShareTarget>, query: String): List<ShareTarget> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return targets.sortedWith(byName)
        fun ShareTarget.starts() = label.lowercase().startsWith(q) || appLabel.lowercase().startsWith(q)
        fun ShareTarget.holds() = starts() || label.lowercase().contains(q) ||
            appLabel.lowercase().contains(q) || packageName.lowercase().contains(q)
        val found = targets.filter { it.holds() }
        return found.filter { it.starts() }.sortedWith(byName) + found.filterNot { it.starts() }.sortedWith(byName)
    }

    /** [recents] with [key] at the head, each once, capped at [MAX_RECENTS]. */
    fun recordRecent(recents: List<String>, key: String): List<String> =
        (listOf(key) + (recents - key)).take(MAX_RECENTS)

    /** [pinned] with [key] added at the end, or taken out when it is there. */
    fun togglePin(pinned: List<String>, key: String): List<String> =
        if (key in pinned) pinned - key else pinned + key

    /** [text] when it is one http(s) link and nothing else, trimmed; otherwise null. */
    fun linkIn(text: String?): String? {
        val trimmed = text?.trim().orEmpty()
        return trimmed.takeIf { LINK.matches(it) }
    }

    /** [send] aimed at [target], ready to start from anywhere. */
    fun launchIntent(send: Intent, target: ShareTarget): Intent =
        Intent(send)
            .setPackage(null)
            .setClassName(target.packageName, target.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What [send] carries, for the preview line and the quick actions. */
    fun preview(send: Intent): SharePreview {
        val streams = streams(send)
        return SharePreview(
            text = send.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() },
            subject = send.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
            files = streams.size,
            mimeType = send.type,
            firstUri = streams.firstOrNull(),
        )
    }

    /** The files on [send]: the stream extra, one or a list, or failing that its clip. */
    @Suppress("DEPRECATION")
    fun streams(send: Intent): List<Uri> {
        val extra: List<Uri> = runCatching {
            when (send.action) {
                Intent.ACTION_SEND_MULTIPLE -> send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else -> listOfNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            }
        }.getOrNull().orEmpty()
        if (extra.isNotEmpty()) return extra
        val clip: ClipData = send.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private val byName = compareBy<ShareTarget>({ it.label.lowercase() }, { it.appLabel.lowercase() }, { it.key })

    private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
}
