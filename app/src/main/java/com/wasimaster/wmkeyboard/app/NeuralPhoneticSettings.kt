package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.storage.ConfirmDeleteDialog
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitDownloads
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Neural suggestions on a phonetic language's page: the switch, and under it
 * where IndicXlit's download stands ([NeuralPhoneticModelRow]). Turning the
 * switch on starts the download, through data saving's say like every model
 * download. One model serves every language, so the row reads the same on
 * each of their pages.
 */
@Composable
internal fun NeuralPhoneticToggle(
    langId: String,
    languageName: String,
    settings: LiveSettings,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val download = rememberXlitDownload(settings)
    // The size to quote is what a download fetches now, the runtime included
    // the first time, or the pack's own size once it is here.
    val size = IndicXlitDownloads.bytesToFetch(context).takeIf { it > 0 } ?: IndicXlitModels.BYTES
    ToggleSetting(
        R.string.languages_neural_phonetic_title,
        stringResource(R.string.languages_neural_phonetic_subtitle, formatBytes(size)),
        on,
        info = stringResource(R.string.languages_neural_phonetic_info, languageName),
        default = SettingsDefaults.suggestionStrip.neuralPhoneticFor(langId),
    ) { checked ->
        if (checked && !IndicXlitModels.isDownloaded(context.noBackupFilesDir)) download()
        onChange(checked)
    }
}

/**
 * Starts IndicXlit's download as data saving allows: at once, after the
 * metered-connection question, or not at all with the reason shown. The
 * dialogs are drawn where this is called.
 */
@Composable
private fun rememberXlitDownload(settings: LiveSettings): () -> Unit {
    val context = LocalContext.current
    val decide = rememberDownloadDecision(settings)
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.languages_neural_phonetic_metered_body,
                formatBytes(IndicXlitDownloads.bytesToFetch(context).takeIf { it > 0 } ?: IndicXlitModels.BYTES),
            ),
            onConfirm = {
                metered = false
                IndicXlitDownloads.start(context)
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
    return {
        when (decide()) {
            MeteredDecision.ALLOWED -> IndicXlitDownloads.start(context)
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }
}

/**
 * Where the download stands; a tap fetches it, or fetches it again after a
 * failure, and once it is here offers to delete it (so does a swipe).
 */
@Composable
internal fun NeuralPhoneticModelRow(settings: LiveSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val download = rememberXlitDownload(settings)
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.languages_neural_phonetic_delete_title),
            body = stringResource(R.string.languages_neural_phonetic_delete_body),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { deleteNeuralPhonetic(context, repository, settings.value) }
            },
        )
    }
    val status by IndicXlitDownloads.status.collectAsState()
    val module by FeatureModules.onnx.state.collectAsState()
    LaunchedEffect(Unit) { IndicXlitDownloads.refresh(context) }
    val size = formatBytes(IndicXlitModels.BYTES)
    val toFetch = formatBytes(IndicXlitDownloads.bytesToFetch(context).takeIf { it > 0 } ?: IndicXlitModels.BYTES)
    val value = when (val s = status) {
        is IndicXlitDownloads.Status.Downloaded -> stringResource(R.string.languages_neural_phonetic_model_ready, size)
        is IndicXlitDownloads.Status.Downloading ->
            if (s.total > 0) {
                stringResource(R.string.languages_neural_phonetic_model_downloading, formatBytes(s.bytes), formatBytes(s.total))
            } else {
                stringResource(CommonR.string.common_downloading)
            }
        is IndicXlitDownloads.Status.Failed ->
            if ((module as? ModuleState.Missing)?.consent != null) {
                nativeConsentInfo((module as ModuleState.Missing).consent.orEmpty())
            } else if (s.messageArg.isEmpty()) {
                stringResource(s.messageRes)
            } else {
                stringResource(s.messageRes, s.messageArg)
            }
        IndicXlitDownloads.Status.NotDownloaded -> stringResource(R.string.languages_neural_phonetic_model_missing, toFetch)
    }
    // No click while the pack downloads; no chevron ever, since a tap opens
    // nothing: it fetches, or asks to delete what is here.
    val missing = status is IndicXlitDownloads.Status.NotDownloaded || status is IndicXlitDownloads.Status.Failed
    val here = status is IndicXlitDownloads.Status.Downloaded
    val deleteLabel = stringResource(CommonR.string.common_delete)
    WmRow(
        title = stringResource(R.string.languages_neural_phonetic_model_title),
        subtitle = value,
        icon = SettingsRowIcons[R.string.languages_neural_phonetic_model_title],
        highlightKey = R.string.languages_neural_phonetic_model_title,
        swipeActions = if (here) listOf(deleteSwipeAction(deleteLabel) { confirmDelete = true }) else emptyList(),
        onClick = when {
            missing -> download
            here -> ({ confirmDelete = true })
            else -> null
        },
    )
}

/**
 * Deletes the pack and turns neural suggestions off on every language that
 * had them: a switch left on with no model behind it would sit there doing
 * nothing until it fetched 13 MB nobody asked for.
 */
internal suspend fun deleteNeuralPhonetic(context: Context, repository: SettingsRepository, settings: KeyboardSettings) {
    for (langId in settings.suggestionStrip.neuralPhoneticLangs) repository.setNeuralPhonetic(langId, false)
    withContext(Dispatchers.IO) { IndicXlitDownloads.delete(context) }
}
