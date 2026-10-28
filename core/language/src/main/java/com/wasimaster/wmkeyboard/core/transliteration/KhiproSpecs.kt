package com.wasimaster.wmkeyboard.core.transliteration

import android.content.Context
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Khipro's specs, kept up to date without an app release (#909).
 *
 * The four `.mim` files are compiled into the engine, but Khipro is updated
 * on a rolling basis, and a layout fix used to wait for the next release to
 * carry a new engine. So the data repository publishes the current set under
 * `data/bn/khipro/`: the four specs, the conformance table each spec's own
 * repository keeps beside it, and [MANIFEST_NAME], which names the set's
 * [Manifest.revision] and every file's size and SHA-256.
 *
 * A set is installed only when all of it holds up: newer than the specs in
 * use, every file the size and hash the manifest gives, and every spec typing
 * every case of its table the way upstream says it should (less the cases
 * upstream left stale, which the manifest lists). Then it goes to
 * device-protected storage, so a locked boot types the same, and into the
 * engine in place of the bundled specs. Anything less and nothing changes.
 * At the next start the installed set is checked against its manifest again
 * and loaded; an app update whose bundled specs are as new or newer drops it.
 *
 * The keyboard asks [refreshIfDue] while a Khipro layout is in use, which
 * fetches the manifest at most once a day; Settings asks [refresh] for its
 * Check now. A copy fetched elsewhere comes in through [install] (offline
 * import), with the same checks.
 *
 * An engine library older than this code has none of the calls; every one is
 * guarded, and such a build simply keeps typing its bundled specs.
 */
object KhiproSpecs {

    /** The manifest's name in the data repository and in an offline import. */
    const val MANIFEST_NAME = "bn_khipro_specs.json"

    /** The manifest format this reads; a set published in a later one waits for an app that reads it. */
    const val FORMAT = 1

    private const val REPO_DIR = "data/bn/khipro"
    private const val DIR = "khipro"
    private const val PREFS = "khipro_specs"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_DECLINED = "declined"
    private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    /** A failed check is tried again after this, rather than on every word. */
    private const val RETRY_AFTER_MS = 60L * 60 * 1000

    /** Far above the real files: a manifest is about 2 KB, a spec 60 KB, a table 10 KB. */
    private const val MAX_MANIFEST_BYTES = 64L * 1024
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024

    /** Where the variants are named in a manifest. */
    private val KEYS = mapOf(
        Khipro.Variant.TOUCHSCREEN to "touchscreen",
        Khipro.Variant.DESKTOP to "desktop",
        Khipro.Variant.DARK_TOUCHSCREEN to "dark_touchscreen",
        Khipro.Variant.DARK_DESKTOP to "dark_desktop",
    )

    class FileRef(val name: String, val size: Long, val sha256: String)

    class Manifest(
        /** RFC 3339 UTC, as the engine's bundled revision is written, so the two sort as text. */
        val revision: String,
        val specs: Map<Khipro.Variant, FileRef>,
        val cases: Map<Khipro.Variant, FileRef>,
        /** Cases upstream's tables still have from before a spec change overturned them: (input, stale expectation). */
        val stale: List<Pair<String, String>>,
    ) {
        val files: List<FileRef> get() = specs.values + cases.values
    }

    /** Why a set was not installed, or that it was. */
    sealed interface Outcome {
        data class Installed(val revision: String) : Outcome
        /** The specs in use are as new as the published ones. */
        data object UpToDate : Outcome
        data class Refused(val reason: String) : Outcome
        /** This build's engine cannot load specs at run time. */
        data object Unsupported : Outcome
    }

    data class State(
        /** The revision typed with: the installed set's, or the bundled one's. Null with an engine too old to say. */
        val revision: String? = null,
        /** Whether that is a downloaded set rather than the bundled one. */
        val downloaded: Boolean = false,
        val checking: Boolean = false,
        /** When the published set was last looked at, in wall-clock ms; 0 for never. */
        val checkedAt: Long = 0L,
        /** Why the last check or import installed nothing, when it failed rather than found nothing newer. */
        val failure: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _installs = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The revision now in use, each time a set is installed or dropped: what is read off the specs (glide) is stale. */
    val installs: SharedFlow<String> = _installs.asSharedFlow()

    private val mutex = Mutex()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var lastAttempt = 0L

    /** Called from the application once per process, before anything types. Reads nothing yet. */
    fun attach(context: Context) {
        this.context = DirectBoot.deviceContext(context.applicationContext)
    }

    /**
     * The bundled specs' revision, or null when the engine library predates
     * run-time specs.
     */
    fun bundledRevision(): String? = native { EngineNative.khiproBundledRevision() }

    /** The revision in use (see [State.revision]). */
    fun activeRevision(): String? {
        ensureLoaded()
        return _state.value.revision
    }

    /**
     * Loads the installed set into the engine the first time Khipro is used
     * in this process. Cheap after that: one volatile read.
     */
    internal fun ensureLoaded() {
        if (loaded) return
        val context = context ?: return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val bundled = bundledRevision()
            val checkedAt = prefs()?.getLong(KEY_CHECKED_AT, 0L) ?: 0L
            _state.update { it.copy(revision = bundled, downloaded = false, checkedAt = checkedAt) }
            if (bundled == null) return
            val dir = dir(context)
            val manifest = runCatching { parse(File(dir, MANIFEST_NAME).readText()) }.getOrNull() ?: return
            // An update brought specs as new as these, or newer: the bundled ones win.
            if (manifest.revision <= bundled) {
                dir.deleteRecursively()
                return
            }
            val texts = manifest.specs.mapValues { (_, ref) ->
                val file = File(dir, ref.name)
                file.takeIf { it.length() == ref.size && FileDownloader.sha256(it).equals(ref.sha256, ignoreCase = true) }
                    ?.readText()
            }
            if (texts.values.any { it == null } || activate(texts.mapValues { it.value!! }) != null) {
                dir.deleteRecursively()
                return
            }
            _state.update { it.copy(revision = manifest.revision, downloaded = true) }
        }
    }

    /**
     * Whether [refreshIfDue] would look now: a cheap read for the keyboard to
     * ask as a word starts, before it starts anything.
     */
    fun isDue(): Boolean {
        val now = System.currentTimeMillis()
        val state = _state.value
        return context != null && !state.checking && now - lastAttempt >= RETRY_AFTER_MS &&
            (!loaded || now - state.checkedAt >= CHECK_EVERY_MS)
    }

    /**
     * [refresh], unless the published set was looked at in the last day (or
     * a check failed in the last hour). [allowed] is the caller's word that
     * data saving lets a background download through right now.
     */
    suspend fun refreshIfDue(allowed: Boolean): Outcome? {
        if (!allowed) return null
        ensureLoaded()
        val now = System.currentTimeMillis()
        if (now - _state.value.checkedAt < CHECK_EVERY_MS || now - lastAttempt < RETRY_AFTER_MS) return null
        if (mutex.isLocked) return null
        return refresh(background = true)
    }

    /**
     * Fetches the published manifest and, when it names a newer set, the set,
     * and installs it. [background] marks the requests as the keyboard's own
     * rather than the user's.
     */
    suspend fun refresh(background: Boolean = false): Outcome {
        val context = context ?: return Outcome.Unsupported
        ensureLoaded()
        if (bundledRevision() == null) return Outcome.Unsupported
        return mutex.withLock {
            lastAttempt = System.currentTimeMillis()
            _state.update { it.copy(checking = true) }
            val staging = File(context.cacheDir, "khipro-update").apply { deleteRecursively(); mkdirs() }
            val outcome = try {
                val manifestFile = File(staging, MANIFEST_NAME)
                fetch(MANIFEST_NAME, manifestFile, size = null, maxBytes = MAX_MANIFEST_BYTES, background = background)
                val manifest = parse(manifestFile.readText())
                // A set the user went back to the bundled specs from is not
                // brought back on its own; Check now asks for it again.
                val declined = prefs()?.getString(KEY_DECLINED, null)
                if (!newer(manifest.revision) || (background && declined != null && manifest.revision <= declined)) {
                    Outcome.UpToDate
                } else {
                    for (ref in manifest.files) {
                        fetch(ref.name, File(staging, ref.name), size = ref.size, maxBytes = MAX_FILE_BYTES, background = background)
                    }
                    installLocked(context, manifestFile) { File(staging, it).takeIf(File::isFile) }
                }
            } catch (e: IllegalArgumentException) {
                Outcome.Refused(e.message ?: "unreadable manifest")
            } catch (e: IOException) {
                Outcome.Refused(e.message ?: e.javaClass.simpleName)
            } finally {
                staging.deleteRecursively()
            }
            finish(outcome)
        }
    }

    /**
     * Installs the set [manifest] describes, its files found by name through
     * [find] (an offline import's staged files). The same checks a download
     * gets, except that any set newer than the bundled one is taken, even one
     * older than a set installed since: the user picked this file.
     */
    suspend fun install(manifest: File, find: (String) -> File?): Outcome {
        val context = context ?: return Outcome.Unsupported
        ensureLoaded()
        if (bundledRevision() == null) return Outcome.Unsupported
        return mutex.withLock {
            val outcome = try {
                installLocked(context, manifest, overInstalled = true, find = find)
            } catch (e: IllegalArgumentException) {
                Outcome.Refused(e.message ?: "unreadable manifest")
            } catch (e: IOException) {
                Outcome.Refused(e.message ?: e.javaClass.simpleName)
            }
            finish(outcome)
        }
    }

    /**
     * Drops a downloaded set: the bundled specs type again, and the daily
     * check passes that set over until a newer one is published.
     */
    suspend fun useBundled() {
        val context = context ?: return
        mutex.withLock {
            _state.value.revision?.takeIf { _state.value.downloaded }?.let { dropped ->
                prefs()?.edit()?.putString(KEY_DECLINED, dropped)?.apply()
            }
            dir(context).deleteRecursively()
            for (variant in Khipro.Variant.entries) native { EngineNative.khiproReset(variant.ordinal) }
            val bundled = bundledRevision()
            _state.update { it.copy(revision = bundled, downloaded = false, failure = null) }
            _installs.tryEmit(bundled.orEmpty())
        }
    }

    /** Whether [name] is a file of a published set, for an offline import to route here. */
    fun isSetFile(name: String): Boolean =
        name == MANIFEST_NAME || (name.startsWith("bn-khipro") && name.endsWith(".mim")) ||
            (name.startsWith("khipro-") && name.endsWith("testcases.tsv")) ||
            (name.startsWith("khipro-") && name.endsWith("testcases-touch.tsv"))

    /**
     * Reads a manifest. Throws [IllegalArgumentException] for one that is not
     * a manifest this reads.
     */
    fun parse(text: String): Manifest {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("not a Khipro manifest")
        val format = runCatching { root.getValue("format").jsonPrimitive.int }.getOrNull()
            ?: throw IllegalArgumentException("not a Khipro manifest")
        require(format <= FORMAT) { "manifest format $format is newer than this app reads" }
        val revision = runCatching { root.getValue("revision").jsonPrimitive.content }.getOrNull()
        require(revision != null && REVISION.matches(revision)) { "no revision" }
        val files = root["files"] as? JsonObject ?: throw IllegalArgumentException("no files")
        val specs = LinkedHashMap<Khipro.Variant, FileRef>()
        val cases = LinkedHashMap<Khipro.Variant, FileRef>()
        for ((variant, key) in KEYS) {
            val entry = files[key] as? JsonObject ?: throw IllegalArgumentException("no $key spec")
            specs[variant] = fileRef(entry["spec"]) ?: throw IllegalArgumentException("no $key spec")
            cases[variant] = fileRef(entry["cases"]) ?: throw IllegalArgumentException("no $key cases")
        }
        val stale = (root["stale"] as? JsonArray).orEmpty().mapNotNull { pair ->
            val parts = (pair as? JsonArray)?.takeIf { it.size == 2 } ?: return@mapNotNull null
            parts[0].jsonPrimitive.content to parts[1].jsonPrimitive.content
        }
        return Manifest(revision, specs, cases, stale)
    }

    /** RFC 3339 UTC to the second, the one form that sorts as text. */
    private val REVISION = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")

    /** A file name with no path in it, which a manifest from anywhere may not escape the folder with. */
    private val FILE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

    private fun fileRef(element: JsonElement?): FileRef? {
        val o = element as? JsonObject ?: return null
        return runCatching {
            val name = o.getValue("name").jsonPrimitive.content
            val size = o.getValue("size").jsonPrimitive.long
            val sha = o.getValue("sha256").jsonPrimitive.content
            FileRef(name, size, sha).takeIf {
                FILE_NAME.matches(name) && name != MANIFEST_NAME && size in 1..MAX_FILE_BYTES && sha.length == 64
            }
        }.getOrNull()
    }

    private fun installLocked(
        context: Context,
        manifestFile: File,
        overInstalled: Boolean = false,
        find: (String) -> File?,
    ): Outcome {
        require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "not a Khipro manifest" }
        val manifest = parse(manifestFile.readText())
        val bundled = bundledRevision() ?: return Outcome.Unsupported
        if (manifest.revision <= bundled) return Outcome.UpToDate
        if (!overInstalled && !newer(manifest.revision)) return Outcome.UpToDate
        // Every file, the size and hash the manifest gives.
        val found = HashMap<String, File>()
        for (ref in manifest.files) {
            val file = find(ref.name) ?: return Outcome.Refused("${ref.name} is missing")
            val matches = file.length() == ref.size && FileDownloader.sha256(file).equals(ref.sha256, ignoreCase = true)
            if (!IntegrityChecks.accept(matches, ref.name)) return Outcome.Refused("${ref.name} does not match the manifest")
            found[ref.name] = file
        }
        // Every spec, every case of its own table.
        val skip = buildString {
            append("input\texpected\n")
            for ((input, expected) in manifest.stale) append(input).append('\t').append(expected).append('\n')
        }
        val texts = manifest.specs.mapValues { (_, ref) -> found.getValue(ref.name).readText() }
        for ((variant, ref) in manifest.cases) {
            val why = native { EngineNative.khiproCheck(texts.getValue(variant), found.getValue(ref.name).readText(), skip) }
                ?: return Outcome.Unsupported
            if (why.isNotEmpty()) return Outcome.Refused("${manifest.specs.getValue(variant).name}: $why")
        }
        // Onto disk beside the old set, then swapped in, so a set is never half on disk.
        val dir = dir(context)
        val next = File(dir.parentFile, "$DIR.next").apply { deleteRecursively(); mkdirs() }
        val old = File(dir.parentFile, "$DIR.old").apply { deleteRecursively() }
        try {
            manifestFile.copyTo(File(next, MANIFEST_NAME))
            for (ref in manifest.specs.values) found.getValue(ref.name).copyTo(File(next, ref.name))
            if (dir.exists() && !dir.renameTo(old)) throw IOException("could not move the old Khipro specs aside")
            if (!next.renameTo(dir)) {
                old.renameTo(dir)
                throw IOException("could not move the Khipro specs into place")
            }
        } finally {
            next.deleteRecursively()
            old.deleteRecursively()
        }
        activate(texts)?.let { why ->
            dir.deleteRecursively()
            return Outcome.Refused(why)
        }
        return Outcome.Installed(manifest.revision)
    }

    /**
     * Puts [texts] into the engine, every variant or none: null when done,
     * else why the engine refused one (and every variant is back on the
     * bundled spec).
     */
    private fun activate(texts: Map<Khipro.Variant, String>): String? {
        for ((variant, text) in texts) {
            val why = native { EngineNative.khiproInstall(variant.ordinal, text) } ?: "engine has no run-time specs"
            if (why.isNotEmpty()) {
                for (other in Khipro.Variant.entries) native { EngineNative.khiproReset(other.ordinal) }
                return why
            }
        }
        return null
    }

    private fun finish(outcome: Outcome): Outcome {
        val now = System.currentTimeMillis()
        when (outcome) {
            is Outcome.Installed -> {
                prefs()?.edit()?.putLong(KEY_CHECKED_AT, now)?.remove(KEY_DECLINED)?.apply()
                _state.update {
                    it.copy(revision = outcome.revision, downloaded = true, checking = false, checkedAt = now, failure = null)
                }
                _installs.tryEmit(outcome.revision)
            }
            Outcome.UpToDate -> {
                prefs()?.edit()?.putLong(KEY_CHECKED_AT, now)?.apply()
                _state.update { it.copy(checking = false, checkedAt = now, failure = null) }
            }
            is Outcome.Refused -> _state.update { it.copy(checking = false, failure = outcome.reason) }
            Outcome.Unsupported -> _state.update { it.copy(checking = false) }
        }
        return outcome
    }

    /** Whether [revision] is newer than the specs in use. */
    private fun newer(revision: String): Boolean = revision > (_state.value.revision ?: return false)

    private suspend fun fetch(name: String, target: File, size: Long?, maxBytes: Long, background: Boolean) {
        val url = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("$REPO_DIR/$name")
        val part = File(target.parentFile, "${target.name}.part")
        FileDownloader.fetch(
            url,
            part,
            size,
            NetSource.DOWNLOAD_WORDLIST,
            connections = 1,
            background = background,
            maxBytes = maxBytes,
        )
        if (!part.renameTo(target)) throw IOException("could not move $name into place")
        FileDownloader.rangesOf(part).delete()
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [block], or null when the engine library predates the call. */
    private inline fun <T> native(block: () -> T): T? = try {
        block()
    } catch (e: LinkageError) {
        null
    }
}
