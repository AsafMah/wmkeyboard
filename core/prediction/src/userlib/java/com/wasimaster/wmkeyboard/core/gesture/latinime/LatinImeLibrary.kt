package com.wasimaster.wmkeyboard.core.gesture.latinime

import android.os.Build
import android.os.Process
import android.util.Log
import com.wasimaster.wmkeyboard.core.modules.NativeStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Google's gesture typing library on this phone: the one file the user
 * imported, checked against [LatinImePins], kept read-only in
 * `noBackupFilesDir/latinime`, and loaded into the process the first time the
 * keyboard needs it.
 *
 * Every build but Google Play's has this; Play forbids an app to load
 * executable code it did not get from Play, so `src/nouserlib` stands in
 * there and refuses everything.
 *
 * The hash is checked on import and again before every load, and the app's
 * integrity-check switch does not reach it: these are someone else's
 * machine-code bytes, and only the exact builds the JNI interface was
 * written against may run. A library cannot be unloaded, so a new import
 * takes over at the next keyboard start, and a removed one stays in memory
 * until then. A removed library is retired on the spot all the same: nothing
 * decodes with it again ([isLoaded] is false), and [status] says it is
 * [Status.Removed] rather than loaded.
 *
 * A library that crashes the process would crash it again at every start, so
 * the risky steps (the load, the first dictionary build, the first stroke of
 * each process) run on [probation]: a marker file is written before and
 * removed after, and a marker still there at the next start means that step
 * died. The library is then not loaded again until the user says to try
 * ([retry]) or imports again.
 */
object LatinImeLibrary {

    private const val TAG = "LatinImeLibrary"
    private const val DIR = "latinime"
    private const val PROBATION = ".probation"
    private const val STAGING = ".import.tmp"

    /** Whether this build can use the library at all. */
    const val supported: Boolean = true

    /** What [status] says about the library. */
    sealed interface Status {
        /** This build cannot load it (Google Play). */
        data object Unsupported : Status

        /** No library imported. */
        data object Missing : Status

        /** Imported, loads the next time the keyboard needs it. */
        data object Imported : Status

        /** Loaded into this process. */
        data object Loaded : Status

        /**
         * Removed while loaded: the file is gone and nothing uses the library,
         * but Android cannot unload it, so it stays in memory until the
         * keyboard restarts.
         */
        data object Removed : Status

        /** Imported but not loadable: [reason], with [detail] for the log line. */
        data class Failed(val reason: Failure, val detail: String = "") : Status
    }

    enum class Failure {
        /** The file on disk is no longer the pinned build. */
        CHANGED,

        /** The process died during a step on probation. */
        CRASHED,

        /** `System.load` refused it (`JNI_OnLoad` failed, a missing symbol, the wrong ABI). */
        LOAD_FAILED,
    }

    /** What [import] made of a file. */
    sealed interface ImportResult {
        data object Installed : ImportResult

        /** Pinned, but for [abi], not this phone's ([processAbi]). */
        data class OtherAbi(val abi: String) : ImportResult

        /** Not a build this app knows. */
        data object Unknown : ImportResult

        data object Unsupported : ImportResult

        data class Error(val message: String) : ImportResult
    }

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailure: Status.Failed? = null

    /**
     * The library was removed after it loaded. It cannot leave the process,
     * so this is what stops its use; importing again lifts it, since the one
     * pinned build for this ABI is the library already in memory.
     */
    @Volatile
    private var retired = false

    /** True once the library is in this process and has not been removed since. */
    val isLoaded: Boolean get() = loaded && !retired

    /** The ABI this process runs, which is the build of the library it can load. */
    fun processAbi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }

    /** The SHA-256 a library for this phone must have, or null for an ABI nothing is pinned for. */
    fun expectedSha256(): String? = LatinImePins.byAbi[processAbi()]

    private fun dir(noBackupDir: File) = File(noBackupDir, DIR)

    /** Where the imported library lives. */
    fun file(noBackupDir: File): File = File(dir(noBackupDir), LatinImePins.FILE_NAME)

    /** Where the library's dictionaries are built. */
    fun dictionaryDir(noBackupDir: File): File = File(dir(noBackupDir), "dicts")

    private fun marker(noBackupDir: File) = File(dir(noBackupDir), PROBATION)

    fun status(noBackupDir: File): Status {
        if (loaded) return if (retired) Status.Removed else Status.Loaded
        loadFailure?.let { return it }
        if (!file(noBackupDir).isFile) return Status.Missing
        if (marker(noBackupDir).exists()) return Status.Failed(Failure.CRASHED)
        return Status.Imported
    }

    /** Whether [file] has the hash of a pinned build, for any ABI: what lets an offline import recognise it. */
    fun isPinned(file: File): Boolean =
        runCatching { LatinImePins.abiOf(NativeStore.sha256(file)) != null }.getOrDefault(false)

    /**
     * Installs the library from [input] when its bytes are the pinned build
     * for this phone. The bytes are staged into a file made read-only before
     * the first is written (Android 14's rule for loaded code, followed here
     * for a `.so` as HeliBoard does), hashed there, and renamed into place
     * only on a match.
     */
    fun import(noBackupDir: File, input: InputStream): ImportResult {
        val dir = dir(noBackupDir)
        val staged = File(dir, STAGING)
        return try {
            dir.mkdirs()
            staged.setWritable(true)
            staged.delete()
            FileOutputStream(staged).use { out ->
                staged.setReadOnly()
                input.copyTo(out)
                out.fd.sync()
            }
            val abi = LatinImePins.abiOf(NativeStore.sha256(staged))
            when {
                abi == null -> ImportResult.Unknown
                abi != processAbi() -> ImportResult.OtherAbi(abi)
                else -> {
                    val target = file(noBackupDir)
                    target.setWritable(true)
                    target.delete()
                    if (!staged.renameTo(target)) {
                        ImportResult.Error("rename failed")
                    } else {
                        marker(noBackupDir).delete()
                        loadFailure = null
                        retired = false
                        ImportResult.Installed
                    }
                }
            }
        } catch (e: IOException) {
            ImportResult.Error(e.message.orEmpty())
        } finally {
            if (staged.exists()) {
                staged.setWritable(true)
                staged.delete()
            }
        }
    }

    /** [import] from a file on disk. */
    fun import(noBackupDir: File, source: File): ImportResult = source.inputStream().use { import(noBackupDir, it) }

    /**
     * Deletes the library and its dictionaries. A loaded one stays in memory
     * until the process ends, but is retired now: [isLoaded] turns false, so
     * glides go back to the built-in decoder at once.
     */
    fun remove(noBackupDir: File) {
        val dir = dir(noBackupDir)
        dir.walkBottomUp().forEach { it.setWritable(true) }
        dir.deleteRecursively()
        loadFailure = null
        if (loaded) retired = true
    }

    /** Clears a crash marker so the next start tries the library again. */
    fun retry(noBackupDir: File) {
        marker(noBackupDir).delete()
        if (loadFailure?.reason == Failure.CRASHED) loadFailure = null
    }

    /**
     * Loads the library if it is imported, still the pinned build, and did
     * not crash the last process; true when it is in this process. Once per
     * process, whatever the outcome: a failed load is not retried until the
     * next start.
     */
    @Synchronized
    fun load(noBackupDir: File): Boolean {
        if (loaded) return !retired
        if (loadFailure != null) return false
        val file = file(noBackupDir)
        if (!file.isFile) return false
        if (marker(noBackupDir).exists()) {
            loadFailure = Status.Failed(Failure.CRASHED)
            return false
        }
        val expected = expectedSha256()
        val actual = runCatching { NativeStore.sha256(file) }.getOrNull()
        if (expected == null || actual != expected) {
            loadFailure = Status.Failed(Failure.CHANGED, actual.orEmpty())
            return false
        }
        return try {
            probation(noBackupDir) { System.load(file.absolutePath) }
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Could not load the gesture library", e)
            loadFailure = Status.Failed(Failure.LOAD_FAILED, e.message.orEmpty())
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not load the gesture library", e)
            loadFailure = Status.Failed(Failure.LOAD_FAILED, e.message.orEmpty())
            false
        }
    }

    /**
     * Runs [block] with the crash marker down: if the process dies inside
     * it, the next start finds the marker and leaves the library alone.
     */
    fun <T> probation(noBackupDir: File, block: () -> T): T {
        val marker = marker(noBackupDir)
        runCatching { marker.parentFile?.mkdirs(); marker.createNewFile() }
        try {
            return block()
        } finally {
            marker.delete()
        }
    }
}
