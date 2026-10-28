package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.ContextField
import com.wasimaster.wmkeyboard.core.prediction.Register

/**
 * Maps where the user is typing to a suggestion register (see
 * [Register] and the register-priors setting): messaging apps read as
 * CASUAL, email clients and email fields as FORMAL, everything else —
 * browsers, notes, documents, unknown apps — as NEUTRAL, which changes
 * nothing. Deliberately a small allowlist of unmistakable packages: a
 * wrong NEUTRAL costs nothing, a wrong FORMAL fights the user.
 */
object FieldRegister {

    /**
     * Where the text is going, for the context model's conditioning prefix:
     * the same allowlists, plus a search box, which the Enter key names.
     */
    fun field(packageName: String?, fieldKind: FieldKind, enterAction: EnterAction): ContextField = when {
        fieldKind == FieldKind.EMAIL || packageName in EMAIL -> ContextField.EMAIL
        enterAction == EnterAction.SEARCH -> ContextField.SEARCH
        packageName in MESSAGING -> ContextField.CHAT
        else -> ContextField.OTHER
    }

    fun resolve(packageName: String?, fieldKind: FieldKind): Register = when {
        packageName in MESSAGING -> Register.CASUAL
        // An email address box, wherever it appears, is formal ground —
        // and so is any field inside a mail client.
        fieldKind == FieldKind.EMAIL || packageName in EMAIL -> Register.FORMAL
        else -> Register.NEUTRAL
    }

    private val MESSAGING = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.facebook.mlite",
        "com.discord",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.instagram.android",
        "com.snapchat.android",
        "jp.naver.line.android",
        "com.viber.voip",
        "com.kakao.talk",
        "com.tencent.mm",
        "org.matrix.android.sdk.sample",
        "im.vector.app",
        "com.beeper.android",
    )

    private val EMAIL = setOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.samsung.android.email.provider",
        "ch.protonmail.android",
        "com.fsck.k9",
        "net.thunderbird.android",
        "com.yahoo.mobile.client.android.mail",
        "me.bluemail.mail",
        "com.fastmail.app",
        "de.tutao.tutanota",
    )
}
