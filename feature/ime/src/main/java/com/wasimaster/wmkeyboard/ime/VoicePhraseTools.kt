package com.wasimaster.wmkeyboard.ime

/**
 * What the dictation is doing with the phrase in hand when one of the voice
 * surfaces' own tools is pressed: a rail key or Undo (#758). Read by the
 * service from its own fields, so the decisions below stay plain functions.
 *
 * Those tools were written for an engine that streams, where the words heard
 * so far are always there to act on. A clip engine (Whisper, a transcription
 * server, Parakeet, Moonshine, Whistle) has no words until the clip is
 * transcribed, so the same tools act on the clip instead (#921): it is ended
 * where the key was pressed, or thrown away, as a phrase that streams would be.
 */
data class VoicePhraseState(
    val status: VoiceStatus,
    /**
     * Words of the phrase in hand are on the surfaces: streamed as heard, or
     * whisper.cpp's as its clip decodes.
     */
    val livePartial: Boolean,
    /** A clip is recording and has heard speech in it. */
    val clipHeard: Boolean,
    /** A clip is off the microphone and its words are on the way. */
    val clipInFlight: Boolean,
    /** asr4all's live words sit in the field while its clip's last moment decodes. */
    val liveWordsComposed: Boolean,
    /** Typing while you speak, or a dictation into one of the keyboard's own boxes. */
    val sharesField: Boolean,
)

/** Where a key from a voice surface goes. */
enum class VoiceKeyRoute {
    /** Into the field now, the dictation carrying on. */
    TYPE,
    /** The streamed words land as heard so far, the key after them, and listening starts again. */
    SPLIT_LIVE,
    /** The clip ends here; the key waits for its words, then listening starts again. */
    SPLIT_CLIP,
    /** The key waits for the clip already being transcribed. */
    AFTER_CLIP,
    /** asr4all's composed words stay as heard, its finish is dropped, the key goes after them. */
    SETTLE_LIVE,
    /** Through the keys' own path, which ends a block dictation. */
    KEYBOARD,
}

fun VoicePhraseState.keyRoute(): VoiceKeyRoute = when {
    status == VoiceStatus.TRANSCRIBING && liveWordsComposed -> VoiceKeyRoute.SETTLE_LIVE
    // Everything below waits on words: a key pressed now goes after them.
    // Typing while you speak lands each phrase wherever the caret is by then,
    // so a key there needs no waiting.
    clipInFlight && !sharesField -> VoiceKeyRoute.AFTER_CLIP
    status == VoiceStatus.LISTENING && livePartial && !sharesField -> VoiceKeyRoute.SPLIT_LIVE
    status == VoiceStatus.LISTENING && clipHeard && !livePartial && !sharesField -> VoiceKeyRoute.SPLIT_CLIP
    livePartial -> VoiceKeyRoute.KEYBOARD
    else -> VoiceKeyRoute.TYPE
}

/**
 * A key on the keyboard itself ends a block dictation. Streamed words are kept
 * as heard when it does; a clip that has heard speech is finished rather than
 * dropped (#921), its words landing once transcribed. asr4all's live words are
 * already in the field and are kept the streamed way.
 */
fun VoicePhraseState.keyboardKeyFinishesClip(): Boolean =
    status == VoiceStatus.LISTENING && clipHeard && !liveWordsComposed && !sharesField

/**
 * Undo pressed while listening takes back the phrase being heard and listens
 * again: the streamed words, or the clip recorded so far. With nothing heard
 * yet it removes the last phrase that landed.
 */
fun VoicePhraseState.undoRelistens(): Boolean =
    status == VoiceStatus.LISTENING && (livePartial || clipHeard)

/**
 * Whether the voice surfaces offer Undo: once a phrase has landed, and also
 * while one is being heard (#758), where it takes that phrase back and listens
 * again. A clip counts as heard once it has picked up speech (#921), having no
 * words to show. Not in the moment a phrase is being finished, with its words
 * on the way and nothing yet to point at.
 */
fun VoiceUi.undoReady(): Boolean = when (status) {
    VoiceStatus.FINISHING -> false
    VoiceStatus.LISTENING -> canUndo || partial.isNotEmpty() || clipHeard
    else -> canUndo
}
