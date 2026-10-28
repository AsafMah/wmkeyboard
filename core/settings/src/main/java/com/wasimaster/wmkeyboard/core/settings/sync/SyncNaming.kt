package com.wasimaster.wmkeyboard.core.settings.sync

import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import java.util.Locale

/**
 * What a sync file is called.
 *
 * One file per installation at each location it syncs to, overwritten in
 * place: `wmkeyboard-sync_<installId>_<device>.wmsync.json`, or `.wmsync.enc`
 * under a passphrase. A different prefix from automatic backups, so backup
 * rotation can never count a sync file as a generation to delete, and the
 * restore list never offers one.
 *
 * A device in a sync group writes `wmkeyboard-sync-<group>_<installId>_…`
 * instead, and reads only files of its own group, so several people can share
 * one location without merging each other's keyboards. A device with no group
 * keeps the name every file had before groups, which is how old files and old
 * builds stay in the ungrouped set. The hyphen in place of the underscore is
 * deliberate: a build from before groups matches `wmkeyboard-sync_` exactly,
 * so it never reads a grouped file and never merges it into its own.
 */
object SyncNaming {

    const val PREFIX = "wmkeyboard-sync_"

    /** The start of a grouped file's name, before the group. */
    const val GROUP_PREFIX = "wmkeyboard-sync-"

    const val EXTENSION = "wmsync.json"
    const val ENCRYPTED_EXTENSION = "wmsync.enc"

    /**
     * Longest group a name carries. Short because the backup names carry it
     * too, inside a device part older builds cap at 32 characters; see
     * [AutoBackupNaming.name].
     */
    const val GROUP_MAX = 12

    private const val GROUP_SHAPE = "[a-z0-9]+(?:-[a-z0-9]+)*"

    private val OWNER = Regex("^wmkeyboard-sync(?:-($GROUP_SHAPE))?_([0-9a-f]{8})")

    /**
     * The endings a sync file can have, including a provider's `" (1)"`
     * wedged before the last dot: `x.wmsync (1).json`. A folder provider puts
     * it there when a name clashes, and a file that nothing recognises is one
     * nothing ever cleans up.
     */
    private val ENDING = Regex("\\.wmsync( \\(\\d+\\))?\\.(json|enc)$")

    fun name(installId: String, device: String, encrypted: Boolean, group: String = ""): String {
        val slug = AutoBackupNaming.deviceSlug(device)
        val owner = if (slug.isEmpty()) installId else "${installId}_$slug"
        val slugged = groupSlug(group)
        val start = if (slugged.isEmpty()) PREFIX else "$GROUP_PREFIX${slugged}_"
        return "$start$owner.${if (encrypted) ENCRYPTED_EXTENSION else EXTENSION}"
    }

    /**
     * What the user typed, as a group name: lowercase ASCII letters, digits
     * and single hyphens, at most [GROUP_MAX]. "Mum's phone" becomes
     * `mum-s-phone`. Lowercase because a group is matched by name, and a
     * case-blind share (SMB, a FAT card) would otherwise hold `Family` and
     * `family` as one file but sync them as two groups. Empty is no group.
     */
    fun groupSlug(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(GROUP_MAX).trimEnd('-')

    /**
     * Whether [displayName] is a sync file of ours, in any group. Tolerates
     * what a folder provider does to a clashing name, `… (1).wmsync.json`:
     * the sync pass deletes its own older copies after each write, whatever
     * they ended up called.
     */
    fun isOurs(displayName: String): Boolean =
        OWNER.containsMatchIn(displayName) &&
            (ENDING.containsMatchIn(displayName) || displayName.endsWith(".$EXTENSION") ||
                displayName.endsWith(".$ENCRYPTED_EXTENSION"))

    /** The installation that wrote [displayName], or null. */
    fun installId(displayName: String): String? =
        if (isOurs(displayName)) OWNER.find(displayName)?.groupValues?.get(2) else null

    /** The group [displayName] belongs to: empty for none, null when it is not a sync file. */
    fun group(displayName: String): String? =
        if (isOurs(displayName)) OWNER.find(displayName)?.groupValues?.get(1).orEmpty() else null

    /**
     * Every group with a file among [names], sorted, the empty one included
     * when an ungrouped file is there. [except] leaves one installation out,
     * so a device does not count itself as a reason a group exists.
     */
    fun groups(names: Iterable<String>, except: String? = null): List<String> =
        names.filter { installId(it) != null && installId(it) != except }
            .mapNotNullTo(sortedSetOf<String>()) { group(it) }
            .toList()

    /**
     * The files a sync pass reads from [entries]: every other device's newest
     * in [inGroup], one each. Not this device's own ([me]), and nothing from
     * another group, however new.
     */
    fun newestOfOthers(entries: List<SinkEntry>, me: String, inGroup: String): List<SinkEntry> =
        entries
            .filter { group(it.name) == inGroup }
            .groupBy { installId(it.name) }
            .filterKeys { it != null && it != me }
            .map { (_, files) -> files.maxBy { it.modifiedAtMs } }

    /** The device slug in [displayName], hyphens and all, or null. */
    fun device(displayName: String): String? {
        val match = if (isOurs(displayName)) OWNER.find(displayName) else null
        val rest = displayName.removePrefix(match?.value ?: return null)
        if (!rest.startsWith("_")) return null
        return rest.drop(1).substringBefore('.').substringBefore(' ').ifEmpty { null }
    }

    fun isEncrypted(displayName: String): Boolean = displayName.endsWith(".enc")
}
