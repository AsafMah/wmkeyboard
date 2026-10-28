package com.wasimaster.wmkeyboard.core.tools

/**
 * Suggested next questions under a chat answer: the chips a tap puts in the
 * message box, the way the big chat apps offer them.
 *
 * The model writes them itself, at the end of the same answer, inside a
 * `<followups>` block: no second request, and they come from the model that
 * knows what it just left out. The block is taken out of the answer before it
 * is kept or shown, and held back while it streams.
 */
object AiFollowUps {

    /** What the system prompt says when suggestions are on. */
    const val RULE =
        " After your answer, on new lines, add up to three short follow-up questions the user might " +
            "want to ask you next, in the language they wrote in, one per line between <followups> and " +
            "</followups>. Write nothing after that block."

    private const val OPEN = "<followups>"
    private const val CLOSE = "</followups>"

    /** The answer without its block, and the questions the block held, at most [MAX]. */
    fun split(text: String): Pair<String, List<String>> {
        val open = text.indexOf(OPEN, ignoreCase = true)
        if (open < 0) return text to emptyList()
        val close = text.indexOf(CLOSE, open, ignoreCase = true)
        val inside = if (close < 0) text.substring(open + OPEN.length) else text.substring(open + OPEN.length, close)
        val after = if (close < 0) "" else text.substring(close + CLOSE.length)
        val questions = inside.lineSequence()
            .map { it.trim().replace(LIST_MARK, "").trim().trim('"', '“', '”') }
            .filter { it.isNotEmpty() && it.length <= MAX_CHARS }
            .distinct()
            .take(MAX)
            .toList()
        return (text.substring(0, open).trimEnd() + after).trimEnd() to questions
    }

    /**
     * A streaming answer as it may be shown: the block, and any half-written
     * opening tag at the very end, held back.
     */
    fun visible(partial: String): String {
        val open = partial.indexOf(OPEN, ignoreCase = true)
        if (open >= 0) return partial.substring(0, open).trimEnd()
        val lt = partial.lastIndexOf('<')
        if (lt >= 0 && lt > partial.length - OPEN.length && OPEN.startsWith(partial.substring(lt).lowercase())) {
            return partial.substring(0, lt).trimEnd()
        }
        return partial
    }

    const val MAX = 3
    private const val MAX_CHARS = 160
    private val LIST_MARK = Regex("""^(?:\d{1,2}[.)]|[-*•+])\s+""")
}
