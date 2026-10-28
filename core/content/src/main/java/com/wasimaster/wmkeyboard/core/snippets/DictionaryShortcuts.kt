package com.wasimaster.wmkeyboard.core.snippets

/**
 * A shortcut given to a word added to the personal dictionary (#931): `asaik`
 * offers "as far as I know", as Android's own dictionary does.
 *
 * Stored as a snippet in a folder of their own, the shape a Gboard
 * dictionary's shortcuts take on import ([GboardDictionary.toSnippets]): the
 * word is offered as a suggestion when the shortcut is typed ([Snippet.confirm])
 * rather than typed over it, and the folder is one switch for the lot.
 */
object DictionaryShortcuts {

    /** The folder the shortcuts go in, made the first time one is added. */
    const val FOLDER_NAME = "Personal dictionary"

    private const val LABEL_MAX = 60

    /**
     * Adds [shortcut] for [word] to [store], or null when the shortcut cannot
     * be matched as one ([SnippetMatcher.isMatchableTrigger]). One already
     * there for the same word is left as it is.
     */
    fun add(store: SnippetStore, shortcut: String, word: String): Snippet? {
        val trigger = shortcut.trim().takeIf(SnippetMatcher::isMatchableTrigger) ?: return null
        val text = word.trim().takeIf { it.isNotEmpty() } ?: return null
        val folder = store.folders().firstOrNull { it.name == FOLDER_NAME } ?: store.addFolder(FOLDER_NAME)
        store.items().firstOrNull { it.folderId == folder.id && it.trigger == trigger && it.text == text }?.let { return it }
        return store.add(
            Snippet(
                id = 0,
                label = text.lineSequence().first().take(LABEL_MAX),
                text = text,
                trigger = trigger,
                confirm = true,
                folderId = folder.id,
            ),
        )
    }
}
