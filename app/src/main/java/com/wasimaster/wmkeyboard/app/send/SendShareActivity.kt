package com.wasimaster.wmkeyboard.app.send

import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.AppTheme
import com.wasimaster.wmkeyboard.app.SharedUris
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.send.SendClient
import com.wasimaster.wmkeyboard.core.send.SendError
import com.wasimaster.wmkeyboard.core.send.SendException
import com.wasimaster.wmkeyboard.core.send.SendFile
import com.wasimaster.wmkeyboard.core.send.SendInstances
import com.wasimaster.wmkeyboard.core.send.SendServerConfig
import com.wasimaster.wmkeyboard.core.send.SendStore
import com.wasimaster.wmkeyboard.core.send.SendUpload
import com.wasimaster.wmkeyboard.core.send.SendUploadOptions
import com.wasimaster.wmkeyboard.core.settings.SendPasswordMode
import com.wasimaster.wmkeyboard.core.settings.SendSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.QrCodeGen
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * "Send with WM Keyboard" in another app's share sheet (#729): a small card
 * over the app with the same choices the panel offers (expiry, downloads, a
 * password, the instance), the upload's progress, and the link with its QR
 * code to copy or share on. Or, in Nearby, a paired device to send to.
 *
 * Reached only through the manifest's `SendShareAlias`, which the keyboard
 * enables while the tool and its share sheet setting are on. Stays alive until
 * the upload is done, because the `content://` grants die with it.
 */
class SendShareActivity : ComponentActivity() {

    private data class Shared(val uri: Uri, val name: String, val size: Long, val type: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = SharedUris.foreign(
            this,
            when (intent?.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.stream())
                Intent.ACTION_SEND_MULTIPLE -> intent.streams()
                else -> emptyList()
            },
        )
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val shared = uris.mapNotNull { describe(it) }.toMutableList()
        if (shared.isEmpty() && !text.isNullOrBlank()) {
            val dir = File(cacheDir, "send").apply { mkdirs() }
            val file = File(dir, "Shared text ${System.currentTimeMillis()}.txt")
            if (runCatching { file.writeText(text) }.isSuccess) shared += Shared(Uri.fromFile(file), file.name, file.length(), "text/plain")
        }
        if (shared.isEmpty()) {
            finish()
            return
        }
        KdeConnectHub.attach(this)
        setContent {
            val repository = remember { SettingsRepository(applicationContext) }
            val settings by repository.settings.collectAsState(initial = null)
            val s = settings ?: return@setContent
            AppTheme(s) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        if (ToolbarTool.SEND_FILES !in s.enabledTools) {
                            Column(Modifier.padding(20.dp)) {
                                Text(stringResource(R.string.send_share_off), style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.send_share_close)) }
                                }
                            }
                        } else {
                            ShareCard(repository, s.send, shared, onDone = { finish() })
                        }
                    }
                }
            }
        }
    }

    private enum class Stage { COMPOSE, UPLOADING, DONE, FAILED, NEARBY_WAIT, NEARBY_SENT }

    @Composable
    private fun ShareCard(repository: SettingsRepository, send: SendSettings, shared: List<Shared>, onDone: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val client = remember { sendClient() }
        var nearby by remember { mutableStateOf(false) }
        var expire by remember { mutableStateOf(send.expireSeconds) }
        var downloads by remember { mutableStateOf(send.downloads) }
        var password by remember { mutableStateOf("") }
        var stage by remember { mutableStateOf(Stage.COMPOSE) }
        var progress by remember { mutableStateOf(0f) }
        var result by remember { mutableStateOf<SendUpload?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var sentTo by remember { mutableStateOf("") }
        val hub by KdeConnectHub.state.collectAsState()
        val total = shared.sumOf { it.size.coerceAtLeast(0) }
        val config = SendServerConfig.DEFAULT

        LaunchedEffect(nearby) {
            if (nearby) {
                KdeConnectHub.hold(KdeConnectHub.Reason.SHARE)
            } else {
                KdeConnectHub.release(KdeConnectHub.Reason.SHARE)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.send_share_title), style = MaterialTheme.typography.titleLarge)
            Text(
                pluralStringResource(R.plurals.send_share_files, shared.size, shared.size, Formatter.formatShortFileSize(context, total)) +
                    if (shared.size == 1) " · " + shared[0].name else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            when (stage) {
                Stage.COMPOSE -> {
                    if (send.nearby) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(selected = !nearby, onClick = { nearby = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                                Text(stringResource(R.string.send_share_mode_link))
                            }
                            SegmentedButton(selected = nearby, onClick = { nearby = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                                Text(stringResource(R.string.send_share_mode_nearby))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (!nearby) {
                        Text(stringResource(R.string.send_share_to, SendInstances.displayHost(send.instanceUrl)), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            config.expireChoices.forEach { seconds ->
                                FilterChip(selected = seconds == expire, onClick = { expire = seconds }, label = { Text(shortDuration(context, seconds)) })
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            config.downloadChoices.forEach { count ->
                                FilterChip(
                                    selected = count == downloads,
                                    onClick = { downloads = count },
                                    label = { Text(pluralStringResource(R.plurals.send_downloads_n, count, count)) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it.take(128) },
                            label = { Text(stringResource(R.string.send_share_password_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDone) { Text(stringResource(R.string.send_share_cancel)) }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                stage = Stage.UPLOADING
                                scope.launch {
                                    try {
                                        val files = shared.map { f -> SendFile(f.name, f.size, f.type) { contentResolver.openInputStream(f.uri) ?: throw FileNotFoundException(f.name) } }
                                        val generated = if (password.isBlank() && send.password == SendPasswordMode.GENERATE) generatedPassword() else null
                                        val upload = withContext(Dispatchers.IO) {
                                            client.upload(
                                                send.instanceUrl, files,
                                                SendUploadOptions(expire, downloads, password.ifBlank { generated }),
                                                progress = { sent, tot -> progress = if (tot > 0) sent.toFloat() / tot else 0f },
                                            )
                                        }
                                        if (send.keepHistory) withContext(Dispatchers.IO) { SendStore(File(filesDir, SendStore.DIR)).add(upload) }
                                        result = upload
                                        copyText(context, upload.url)
                                        stage = Stage.DONE
                                    } catch (e: SendException) {
                                        error = getString(
                                            R.string.send_share_failed,
                                            getString(
                                                when (e.error) {
                                                    SendError.OFFLINE -> R.string.send_share_error_offline
                                                    SendError.TOO_BIG -> R.string.send_share_error_too_big
                                                    else -> R.string.send_share_error_other
                                                },
                                            ),
                                        )
                                        stage = Stage.FAILED
                                    }
                                }
                            }) { Text(stringResource(R.string.send_share_upload)) }
                        }
                    } else {
                        val devices = hub.paired
                        if (devices.isEmpty()) {
                            Text(stringResource(R.string.send_share_nearby_none), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        devices.forEach { device ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                    if (!device.reachable) Text(stringResource(R.string.send_share_nearby_waiting, device.name), style = MaterialTheme.typography.bodySmall)
                                }
                                Button(enabled = device.reachable, onClick = {
                                    val queued = KdeConnectHub.send(this@SendShareActivity, device.id, shared.map { it.uri }, null)
                                    if (queued == 0) {
                                        error = getString(R.string.send_share_unreadable)
                                        stage = Stage.FAILED
                                        return@Button
                                    }
                                    sentTo = device.name
                                    stage = Stage.NEARBY_WAIT
                                    scope.launch {
                                        delay(300)
                                        while (KdeConnectHub.activeOutgoing(device.id) > 0) delay(250)
                                        stage = Stage.NEARBY_SENT
                                    }
                                }) { Text(stringResource(R.string.send_share_upload)) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDone) { Text(stringResource(R.string.send_share_cancel)) }
                        }
                    }
                }
                Stage.UPLOADING -> {
                    Text(stringResource(R.string.send_share_uploading), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDone) { Text(stringResource(R.string.send_share_cancel)) }
                    }
                }
                Stage.NEARBY_WAIT -> {
                    Text(stringResource(R.string.send_share_sending, sentTo), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Stage.NEARBY_SENT -> {
                    Text(stringResource(R.string.send_share_sent, sentTo), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onDone) { Text(stringResource(R.string.send_share_close)) }
                    }
                }
                Stage.DONE -> {
                    val upload = result ?: return@Column
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val qr = remember(upload.url) { QrCodeGen.bitmap(upload.url, sizePx = 384) }
                        if (qr != null) {
                            Image(
                                qr.asImageBitmap(), contentDescription = upload.url, contentScale = ContentScale.Fit,
                                modifier = Modifier.size(120.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(4.dp),
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.send_share_done), style = MaterialTheme.typography.titleMedium)
                            Text(upload.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = { copyText(context, upload.url) }) { Text(stringResource(R.string.send_share_copy)) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, upload.url)
                            AppShareSheet.start(context, share, upload.name)
                        }) { Text(stringResource(R.string.send_share_share)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onDone) { Text(stringResource(R.string.send_share_close)) }
                    }
                }
                Stage.FAILED -> {
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { stage = Stage.COMPOSE }) { Text(stringResource(R.string.send_share_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onDone) { Text(stringResource(R.string.send_share_close)) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        KdeConnectHub.release(KdeConnectHub.Reason.SHARE)
        super.onDestroy()
    }

    private fun describe(uri: Uri): Shared? {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) c.getString(n)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        if (size < 0) {
            size = runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        }
        if (size < 0) return null
        // Opened once up front: a grant that lapsed should fail here, not mid-upload.
        runCatching { contentResolver.openInputStream(uri)?.close() }.getOrElse { return null }
        val type = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        return Shared(uri, name, size, type)
    }

    private fun generatedPassword(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        return (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun sendClient(): SendClient = SendClient(
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.SEND))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build(),
    )

    private fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Send link", text)) }
    }

    @Suppress("DEPRECATION")
    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streams(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
