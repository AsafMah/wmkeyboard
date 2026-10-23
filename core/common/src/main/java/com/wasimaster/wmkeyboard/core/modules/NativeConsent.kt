package com.wasimaster.wmkeyboard.core.modules

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user has agreed to before a native library may be downloaded.
 *
 * Two kinds of agreement:
 * - [DOWNLOADS]: F-Droid's inclusion policy lets an app download executable
 *   files only after an explicit opt-in that says the user is bypassing
 *   F-Droid's checks. Only the F-Droid build asks; everywhere else it is
 *   given.
 * - A licence, such as [QUALCOMM]: the vendor's terms, which the user
 *   accepts because the phone fetches the vendor's files itself and this
 *   project does not redistribute them. [FUTO] is the same kind of
 *   agreement for model weights rather than a library: FUTO Swipe's glide
 *   models, fetched from Hugging Face under the FUTO Model Weights License.
 *   [NLLB] likewise, for Meta's NLLB-200 translation model under CC BY-NC
 *   4.0 (non-commercial use only).
 *   [MMS] is Meta's MMS speech weights under CC-BY-NC-4.0: free to use, not
 *   for commercial purposes, which the user agrees to before the first voice
 *   downloads.
 *
 * Kept in its own small preferences file on device-protected storage: the
 * keyboard reads it before the user unlocks, and it is not a setting worth
 * backing up or syncing (a new phone should ask again).
 */
object NativeConsent {
    const val DOWNLOADS = "downloads"
    const val QUALCOMM = "qualcomm"
    const val FUTO = "futo"
    const val NLLB = "nllb"
    const val MMS = "mms"

    /**
     * Not an agreement: what a gate reports on a build without the internet
     * permission (the offline build). It cannot fetch a library at all, so
     * the only way one arrives is an import of the published file
     * ([NativeDelivery.import], [NativeDelivery.importArchive]), and the
     * screens say which file and where instead of offering a download.
     */
    const val IMPORT = "import"

    /**
     * The CreativeML OpenRAIL++-M licence of SDXS, the sticker generator's
     * default model, and [OPENRAIL_M] the OpenRAIL-M one of Stable Diffusion
     * 1.5 that Qualcomm's NPU builds keep. Both pass on the same use
     * restrictions (their Attachment A) to whoever runs the weights, so the
     * user agrees before the first download.
     */
    const val OPENRAIL_PP = "openrail++"
    const val OPENRAIL_M = "creativeml-openrail-m"

    /** The licence page each licence id links to. */
    val licenceUrls: Map<String, String> = mapOf(
        QUALCOMM to "https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf",
        // Pinned to the revision the app downloads (FutoSwipe in :core:voice).
        FUTO to "https://huggingface.co/futo-org/futo-swipe/blob/18328c3042b066952c0936b3771d492fe2ec289a/LICENSE.md",
        // The licence Meta released NLLB-200's weights under, which the
        // converted copy the app downloads (NllbTranslator) keeps.
        NLLB to "https://creativecommons.org/licenses/by-nc/4.0/legalcode",
        MMS to "https://creativecommons.org/licenses/by-nc/4.0/",
        OPENRAIL_PP to "https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/blob/main/LICENSE.md",
        OPENRAIL_M to "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
    )

    private const val PREFS = "native_delivery"

    @Volatile private var prefs: SharedPreferences? = null

    /** True on a build whose downloads need [DOWNLOADS] first (F-Droid). */
    @Volatile
    var downloadsNeedOptIn: Boolean = false
        private set

    private val given = MutableStateFlow<Set<String>>(emptySet())

    /** Every agreement given so far, for the settings screens to draw. */
    val agreements: StateFlow<Set<String>> = given.asStateFlow()

    fun attach(context: Context, needOptIn: Boolean) {
        downloadsNeedOptIn = needOptIn
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        given.value = store.all.filterValues { it == true }.keys
    }

    /** Whether [id] has been agreed to; [DOWNLOADS] is implied where it is not asked. */
    fun has(id: String): Boolean = when {
        id == DOWNLOADS && !downloadsNeedOptIn -> true
        else -> id in given.value
    }

    fun set(id: String, agreed: Boolean) {
        prefs?.edit()?.putBoolean(id, agreed)?.apply()
        given.value = if (agreed) given.value + id else given.value - id
    }

    /** The first agreement [pins] still need, or null when they may download. */
    fun missingFor(pins: List<NativePin>): String? {
        if (!has(DOWNLOADS)) return DOWNLOADS
        return pins.mapNotNull { it.licence }.distinct().firstOrNull { !has(it) }
    }
}
