package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import com.wasimaster.wmkeyboard.core.debug.EnterIncidentRecorder
import java.io.File

/**
 * Saving an Enter incident: freezing the recorder's trace into a file on the
 * phone, and putting a copy where a computer over USB can pull it,
 * `Download/WMKeyboard/` (Android 10 and later).
 */
internal object EnterIncidentExport {

    const val DOWNLOAD_DIR = "WMKeyboard"
    private const val SHORTCUT_ID = "save_enter_incident"
    private const val MIN_GAP_MILLIS = 3_000L

    @Volatile
    private var lastSave = 0L

    /** Saves and returns the message to show. A second save within a few seconds is the same incident. */
    fun saveWithMessage(context: Context, trigger: String): String {
        val now = SystemClock.elapsedRealtime()
        if (lastSave != 0L && now - lastSave < MIN_GAP_MILLIS) {
            return context.getString(R.string.shell_enter_incident_saved_recently)
        }
        lastSave = now
        EnterIncidentRecorder.attach(context)
        val recording = EnterIncidentRecorder.isRecording()
        val saved = EnterIncidentRecorder.saveIncident(context, trigger)
        val file = saved.file
        if (file == null) {
            lastSave = 0L
            return context.getString(R.string.shell_enter_incident_save_failed, saved.error ?: "unknown")
        }
        val publishError = publish(context, file.name, saved.text)
        return when {
            !recording -> context.getString(R.string.shell_enter_incident_saved_off)
            publishError == null -> context.getString(R.string.shell_enter_incident_saved_download, DOWNLOAD_DIR)
            publishError == UNSUPPORTED -> context.getString(R.string.shell_enter_incident_saved)
            else -> context.getString(R.string.shell_enter_incident_saved_no_download, publishError)
        }
    }

    /** The newest saved incident, for the settings screen's Share row. */
    fun latest(context: Context): File? {
        EnterIncidentRecorder.attach(context)
        return EnterIncidentRecorder.savedIncidents().firstOrNull()
    }

    private const val UNSUPPORTED = "unsupported"

    /**
     * Copies the incident into the shared Download folder and trims this
     * install's copies there to the same count kept privately. Null on
     * success, [UNSUPPORTED] below Android 10, otherwise what failed.
     */
    private fun publish(context: Context, name: String, text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UNSUPPORTED
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_DIR")
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Download refused the file"
            val stream = resolver.openOutputStream(uri) ?: throw java.io.IOException("no output stream")
            stream.use { it.write(text.toByteArray()) }
            publishedCopies(context).drop(EnterIncidentRecorder.INCIDENTS_KEPT).forEach { (_, old) ->
                runCatching { resolver.delete(old, null, null) }
            }
            null
        } catch (e: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            Log.w(EnterIncidentRecorder.LOG_TAG, "could not copy the incident to Download", e)
            e.javaClass.simpleName
        }
    }

    /**
     * This install's incident copies in Download/WMKeyboard, newest first.
     * Only rows this package owns and named like an incident: never someone
     * else's file that happens to sit in the same folder.
     */
    private fun publishedCopies(context: Context): List<Pair<String, android.net.Uri>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'enter-incident-%.txt'"
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_DIR/", context.packageName)
        val found = mutableListOf<Pair<String, android.net.Uri>>()
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0),
                    )
                    found += cursor.getString(1) to uri
                }
            }
        return found.sortedByDescending { it.first }
    }

    /** Deletes the trace, the private incidents and this install's Download copies; false if anything stayed. */
    fun clearAll(context: Context): Boolean {
        EnterIncidentRecorder.attach(context)
        val privateCleared = EnterIncidentRecorder.clear()
        val publicCleared = runCatching {
            publishedCopies(context).all { (_, uri) -> context.contentResolver.delete(uri, null, null) > 0 }
        }.getOrDefault(false)
        return privateCleared && publicCleared
    }

    fun share(context: Context, file: File) {
        val uri = runCatching {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val copy = File(dir, file.name)
            file.copyTo(copy, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.clipboard", copy)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        AppShareSheet.start(context, intent, context.getString(R.string.shell_enter_incident_share_title))
    }

    /** Asks the launcher for a home-screen icon that saves an incident; false if it cannot. */
    fun pinShortcut(context: Context): Boolean {
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.shell_enter_incident_shortcut_short))
            .setLongLabel(context.getString(R.string.shell_enter_incident_save_title))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_tools))
            .setIntent(
                Intent(context, EnterIncidentActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EnterIncidentActivity.EXTRA_TRIGGER, "homeShortcut"),
            )
            .build()
        return ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
            runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
    }
}

/**
 * Saves an Enter incident and goes away: the home-screen icon's target, and
 * what `adb shell am start` reaches through the SaveEnterIncident alias. It
 * never shows a window of its own.
 */
class EnterIncidentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER) ?: "activity"
        val message = EnterIncidentExport.saveWithMessage(this, trigger)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_TRIGGER = "trigger"
    }
}

/**
 * "Save Enter incident" as a Quick Settings tile: the one place reachable from
 * inside the chat where Enter just misbehaved, without leaving it. Pulling the
 * shade down takes focus from the app, which is why the tile only freezes what
 * was already recorded rather than recording anything itself.
 */
class EnterIncidentTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        draw()
    }

    override fun onClick() {
        super.onClick()
        val message = EnterIncidentExport.saveWithMessage(applicationContext, "quickSettings")
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        draw()
    }

    private fun draw() {
        val tile = qsTile ?: return
        EnterIncidentRecorder.attach(applicationContext)
        tile.state = if (EnterIncidentRecorder.isRecording()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shortcut_tools)
        tile.label = getString(R.string.shell_enter_incident_shortcut_short)
        tile.updateTile()
    }
}
