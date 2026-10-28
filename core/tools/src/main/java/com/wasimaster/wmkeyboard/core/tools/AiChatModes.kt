package com.wasimaster.wmkeyboard.core.tools

/**
 * What the AI chat is asked to do with one message, beyond answering it.
 *
 * Kept on the user's message by [id] (a string, like everything else the chat
 * store keeps), so a retry or a regenerate runs the same way the first answer
 * did, and a chat reopened later still knows which answer was a research
 * report.
 *
 * [CHAT] is the chat as it always was. [RESEARCH] is its own run: it plans
 * searches, reads pages and writes a cited report ([AiResearch]). [SUMMARIZE]
 * and [COMPARE] are the chat with a standing instruction and, for a summary,
 * the pages its links point to read first.
 */
enum class AiChatMode(val id: String, val defaultFormat: AiAnswerFormat) {
    CHAT("chat", AiAnswerFormat.AUTO),
    RESEARCH("research", AiAnswerFormat.MARKDOWN),
    SUMMARIZE("summarize", AiAnswerFormat.BULLETS),
    COMPARE("compare", AiAnswerFormat.TABLE),
    ;

    companion object {
        /** The mode stored as [id]; anything unknown, an old message included, is [CHAT]. */
        fun of(id: String): AiChatMode = entries.firstOrNull { it.id == id } ?: CHAT
    }
}

/**
 * The shape an answer is asked to take: the chat's format picker.
 *
 * [AUTO] asks nothing and leaves the mode to decide; every other one is an
 * instruction added to the message ([rule]), not to the system prompt. That
 * is on purpose: an on-device chat keeps one session per system prompt, and a
 * format picked for one message should not throw its cache away.
 */
enum class AiAnswerFormat(val id: String, val rule: String) {
    AUTO("auto", ""),
    PLAIN(
        "plain",
        "Write the answer as plain text with no Markdown at all: no headings, bullets, bold, tables or " +
            "code fences, since it will be pasted into a text field.",
    ),
    MARKDOWN(
        "markdown",
        "Format the answer in Markdown, with headings, lists, bold and tables where they help the reader.",
    ),
    BULLETS(
        "bullets",
        "Answer as a short bulleted summary in Markdown: one \"- \" bullet per point, at most about eight " +
            "bullets, with no introduction before them.",
    ),
    TABLE(
        "table",
        "Answer with a Markdown table: a header row, then one row per item, then at most two sentences " +
            "under it.",
    ),
    JSON(
        "json",
        "Answer with valid JSON only, inside one ```json code block, with no text outside it. Choose keys " +
            "that fit the question.",
    ),
    CODE(
        "code",
        "Answer with the code only, in one fenced code block that names its language, with no explanation " +
            "outside it.",
    ),
    ;

    /** Whether an answer in this format can be inserted into a field with its Markdown taken out. */
    val isStructured: Boolean get() = this == JSON || this == CODE

    companion object {
        fun of(id: String): AiAnswerFormat = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** The words a mode and a format add to the message the model reads. */
object AiChatModes {

    /**
     * What [mode] and [format] add to a user message, or "" for a plain chat
     * message. A format of [AiAnswerFormat.AUTO] takes the mode's own.
     *
     * Research adds only the format: its report is written by [AiResearch]
     * with its own prompts, and a later question in the same chat should read
     * the earlier one as the question it was.
     */
    fun instruction(mode: AiChatMode, format: AiAnswerFormat): String {
        val effective = effectiveFormat(mode, format)
        val task = when (mode) {
            AiChatMode.CHAT, AiChatMode.RESEARCH -> ""
            AiChatMode.SUMMARIZE -> SUMMARIZE_TASK
            AiChatMode.COMPARE -> COMPARE_TASK
        }
        val parts = listOf(task, effective.rule).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        // Said to override the chat's own "plain text" line: the last word on
        // formatting is the one the user just picked.
        return "<instructions>\n" + parts.joinToString(" ") + " These instructions override any earlier " +
            "ones about how to format the answer.\n</instructions>"
    }

    /** The format a message is answered in: the one picked, or the mode's own when it is Auto. */
    fun effectiveFormat(mode: AiChatMode, format: AiAnswerFormat): AiAnswerFormat =
        if (format == AiAnswerFormat.AUTO) mode.defaultFormat else format

    private const val SUMMARIZE_TASK =
        "Summarise the material the user gives: the pages in <page> tags, the text in <text> or <file> tags, " +
            "or else what they wrote. Keep the main points, names, numbers and conclusions, leave out the rest, " +
            "and do not add facts the material does not contain. When a page could not be read, say so."

    private const val COMPARE_TASK =
        "The user wants the things they name compared. Compare them point by point on what matters for the " +
            "question, say where they are the same and where they differ, and end with a one-sentence verdict " +
            "when there is one to give. Look up facts that may have changed when you can."

    /**
     * The web addresses in [text], in order and without repeats: what a
     * summary reads before it answers. Trailing punctuation a sentence put
     * after a link is not part of it.
     */
    fun links(text: String, max: Int = MAX_LINKS): List<String> =
        LINK.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"', '»', '”') }
            .filter { it.length > "https://x.y".length }
            .distinct()
            .take(max)
            .toList()

    /** The pages a summary read, as the block added under the user's message. */
    fun pagesBlock(pages: List<Pair<String, String>>): String = pages.joinToString("\n\n") { (url, text) ->
        "<page url=\"" + url.replace("\"", "%22") + "\">\n" + text.trim() + "\n</page>"
    }

    /** Links one summary reads at most; past this a message is a list of links, not a page to sum up. */
    const val MAX_LINKS = 3

    private val LINK = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
}
