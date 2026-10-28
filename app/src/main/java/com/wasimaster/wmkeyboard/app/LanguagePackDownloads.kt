package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.app.updates.GithubReleases
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.modules.LanguagePackResources
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fetching and checking interface language packs ([LanguagePackResources])
 * for the standard GitHub build: one resource-only APK per language, attached
 * to the GitHub release of exactly this version. The offline build's English
 * one takes the same packs, but only as imports.
 *
 * A pack is accepted only when it is signed with this app's own certificate
 * and was cut from this version's build, so a file from elsewhere, or from
 * another release, is never loaded.
 */
internal object LanguagePackDownloads {

    /** Whether this build fetches its interface languages (the standard GitHub build, Android 11+). */
    val active: Boolean get() = BuildConfig.LANGUAGE_PACKS && LanguagePackResources.supported
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val TAG = "LanguagePacks"

    /** English is the APK's own: no pack. */
    fun isEnglish(tag: String): Boolean = LanguagePackResources.qualifierOf(tag).substringBefore('-') == "en"

    /** The release asset holding [tag]'s pack. */
    fun url(tag: String): String =
        "https://github.com/${GithubReleases.REPO}/releases/download/v${com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_NAME}/${fileName(tag)}"

    /** [tag]'s pack as this version's release names it, which is also what an import is recognised by. */
    fun fileName(tag: String): String {
        val version = com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_NAME
        val code = com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_CODE
        return "wmkeyboard-$version-vc$code-lang-${LanguagePackResources.qualifierOf(tag)}.apk"
    }

    /** This version's release page, where every pack is listed: for fetching them on another device. */
    fun releasePage(): String =
        "https://github.com/${GithubReleases.REPO}/releases/tag/v${com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_NAME}"

    /** The published file name of a pack, which an import is recognised by. */
    private val PACK_NAME = Regex("""^wmkeyboard-.+-vc\d+-lang-([a-z]{2,3}(?:-r[A-Z]{2})?)\.apk$""")

    fun isPackName(name: String): Boolean = PACK_NAME.matches(name)

    /**
     * Makes sure [tag]'s pack is on the phone and loaded. True when it is
     * (or English, which needs none), false when it could not be fetched or
     * did not check out. Blocking network work runs on IO.
     */
    suspend fun ensure(context: Context, tag: String?): Boolean {
        if (!active || tag == null) return true
        if (isEnglish(tag) || LanguagePackResources.has(context, tag)) return true
        return withContext(Dispatchers.IO) {
            val target = LanguagePackResources.fileFor(context, tag)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, "${target.name}.part")
            val ok = runCatching { download(url(tag), part) }.isSuccess && install(context, part, target)
            FileDownloader.discard(part)
            ok
        }
    }

    /**
     * The language the app should be showing whose pack is not on the phone:
     * the one picked in the app or in the phone's per-app setting, else the
     * phone's own language, which is what "System default" means. Null when
     * that is English, a language this build cannot show, or already here.
     */
    fun wanted(context: Context): String? {
        if (!active) return null
        val tag = AppLanguage.selected(context)
            ?: matchAvailable(AppLanguage.systemLocale().toLanguageTag(), AppLanguage.available)
            ?: return null
        return tag.takeUnless { isEnglish(it) || LanguagePackResources.has(context, it) }
    }

    /**
     * Fetches [wanted]'s pack in the background at process start, so a phone
     * in German shows the app in German without anyone finding App language
     * first. Until 0.6.3's re-cut a pack came only from picking a language
     * there, and an app following the phone stayed English. [onInstalled] runs
     * on the main thread once the pack is loaded, to restart open screens in
     * it. Fails quietly: the next start tries again.
     */
    fun fetchWanted(context: Context, onInstalled: () -> Unit) {
        val app = context.applicationContext
        if (!InternetPermission.granted) return
        val user = app.getSystemService(android.os.UserManager::class.java)
        if (user != null && !user.isUserUnlocked) return
        val tag = wanted(app) ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (ensure(app, tag)) withContext(Dispatchers.Main) { onInstalled() }
        }
    }

    /** Installs an imported pack when it is one of this version's, signed by us. */
    fun import(context: Context, file: File): String? {
        if (!LanguagePackResources.supported) return null
        val qualifier = PACK_NAME.matchEntire(file.name)?.groupValues?.get(1) ?: return null
        val target = File(LanguagePackResources.dir(context), "$qualifier.apk")
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, "${target.name}.part")
        file.copyTo(staged, overwrite = true)
        return qualifier.takeIf { install(context, staged, target) }.also { staged.delete() }
    }

    private suspend fun download(url: String, part: File) {
        FileDownloader.fetch(url, part, null, NetSource.DOWNLOAD_LANGUAGE, maxBytes = MAX_BYTES)
    }

    /** Checks [staged] and moves it to [target], reloading the packs. */
    private fun install(context: Context, staged: File, target: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!trusted(context, staged)) {
            DebugLog.w(TAG, "${target.name} is not this version's pack, or not signed by us")
            return false
        }
        target.delete()
        if (!staged.renameTo(target)) return false
        LanguagePackResources.reload(context)
        return true
    }

    /**
     * Signed with this app's certificate, and cut from this version: the
     * pack's versionCode is the build's (with the ABI digit of the universal
     * APK it came from), the same one this install's carries.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun trusted(context: Context, file: File): Boolean {
        val pm = context.packageManager
        // Null for a split APK too ("Expected base APK"), which is why the
        // packs carry a base manifest (.github/scripts/language-pack-finish.sh).
        val pack = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (pack.packageName != context.packageName) return false
        if (pack.longVersionCode / VERSION_DIGIT != com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_CODE.toLong()) return false
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val ownSigners = own.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val packSigners = pack.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        return ownSigners.isNotEmpty() && packSigners == ownSigners
    }

    /** The manifest's versionCode is the release's times ten plus an ABI digit (abiDigits in app/build.gradle.kts). */
    private const val VERSION_DIGIT = 10L
}
