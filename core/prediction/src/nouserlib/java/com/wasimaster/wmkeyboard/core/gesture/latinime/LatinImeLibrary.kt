package com.wasimaster.wmkeyboard.core.gesture.latinime

import java.io.File
import java.io.InputStream

/**
 * Google Play's stand-in for the gesture library loader in `src/userlib`.
 * Play forbids an app to load executable code it did not get from Play, so
 * this build has no way to import, store or load the library: every answer
 * is no, and the settings hide the engine. The API matches `src/userlib`'s
 * so the code around it is the same in every build.
 */
object LatinImeLibrary {

    const val supported: Boolean = false

    sealed interface Status {
        data object Unsupported : Status
        data object Missing : Status
        data object Imported : Status
        data object Loaded : Status
        data object Removed : Status
        data class Failed(val reason: Failure, val detail: String = "") : Status
    }

    enum class Failure { CHANGED, CRASHED, LOAD_FAILED }

    sealed interface ImportResult {
        data object Installed : ImportResult
        data class OtherAbi(val abi: String) : ImportResult
        data object Unknown : ImportResult
        data object Unsupported : ImportResult
        data class Error(val message: String) : ImportResult
    }

    val isLoaded: Boolean get() = false

    fun processAbi(): String = ""

    fun expectedSha256(): String? = null

    fun file(noBackupDir: File): File = File(noBackupDir, "latinime/${LatinImePins.FILE_NAME}")

    fun dictionaryDir(noBackupDir: File): File = File(noBackupDir, "latinime/dicts")

    fun status(@Suppress("UNUSED_PARAMETER") noBackupDir: File): Status = Status.Unsupported

    fun isPinned(@Suppress("UNUSED_PARAMETER") file: File): Boolean = false

    fun import(@Suppress("UNUSED_PARAMETER") noBackupDir: File, @Suppress("UNUSED_PARAMETER") input: InputStream): ImportResult =
        ImportResult.Unsupported

    fun import(@Suppress("UNUSED_PARAMETER") noBackupDir: File, @Suppress("UNUSED_PARAMETER") source: File): ImportResult =
        ImportResult.Unsupported

    fun remove(@Suppress("UNUSED_PARAMETER") noBackupDir: File) = Unit

    fun retry(@Suppress("UNUSED_PARAMETER") noBackupDir: File) = Unit

    fun load(@Suppress("UNUSED_PARAMETER") noBackupDir: File): Boolean = false

    fun <T> probation(@Suppress("UNUSED_PARAMETER") noBackupDir: File, block: () -> T): T = block()
}
