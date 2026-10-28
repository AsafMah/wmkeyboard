package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.content.FileProvider
import com.wasimaster.wmkeyboard.core.tools.AiMarkdown
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Block
import com.wasimaster.wmkeyboard.ime.R
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * One chat answer handed to another app as a file or as text: a Markdown
 * file, a PDF laid out from the same Markdown, or the plain text a field
 * would get. A research report travels best as a file, sources and all.
 *
 * The file is written to the cache and shared through the app's FileProvider,
 * like a note shared from the Notes screen; nothing stays once the cache is
 * cleared. Works from the keyboard as well as the app: the chooser is started
 * as a new task.
 */
object AiChatExport {

    enum class Kind { MARKDOWN, PDF, TEXT }

    /**
     * Shares [answer] (Markdown, as the model wrote it) under [title], with
     * [question] over it so the file says what it answers. False when the
     * file could not be made. [open] puts the share in front of the user: the
     * keyboard's share sheet or the settings app's (#970); Android's chooser
     * when the caller has neither.
     */
    fun share(
        context: Context,
        kind: Kind,
        title: String,
        question: String,
        answer: String,
        open: ((Intent) -> Boolean)? = null,
    ): Boolean = runCatching {
        val name = fileName(title.ifBlank { question })
        val intent = when (kind) {
            Kind.TEXT -> Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, AiMarkdown.strip(answer))
            Kind.MARKDOWN -> fileIntent(context, write(context, "$name.md") { it.writeText(markdown(title, question, answer)) }, "text/markdown")
            Kind.PDF -> fileIntent(context, write(context, "$name.pdf") { pdf(context, it, title, question, answer) }, "application/pdf")
        }.putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { question.lineSequence().firstOrNull().orEmpty() })
        if (open != null) {
            check(open(intent))
        } else {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.ime_ai_chat_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }.isSuccess

    /** The Markdown file: a title, the question quoted, then the answer as it was written. */
    internal fun markdown(title: String, question: String, answer: String): String = buildString {
        if (title.isNotBlank()) append("# ").append(title.trim()).append("\n\n")
        if (question.isNotBlank()) {
            question.trim().lineSequence().forEach { append("> ").append(it).append('\n') }
            append('\n')
        }
        append(answer.trim()).append('\n')
    }

    /** A file name from [text]: its first words, nothing a file system minds. */
    internal fun fileName(text: String): String =
        text.lineSequence().firstOrNull().orEmpty()
            .replace(UNSAFE, " ")
            .trim()
            .replace(SPACES, " ")
            .take(MAX_NAME)
            .trim()
            .ifEmpty { "answer" }

    private fun write(context: Context, name: String, fill: (File) -> Unit): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // One answer at a time is shared; the last one's files go.
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name).also(fill)
    }

    private fun fileIntent(context: Context, file: File, mime: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, context.packageName + ".clipboard", file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * The answer laid out on A4 pages: headings larger and bold, lists
     * indented, code and tables in a fixed-width face, the question and the
     * date in grey under the title. Links keep their text; the address of each
     * is in the Markdown it came from, and a research report lists them at the
     * end anyway.
     */
    private fun pdf(context: Context, out: File, title: String, question: String, answer: String) {
        val document = PdfDocument()
        val pen = PdfPen(document)
        if (title.isNotBlank()) pen.text(SpannableStringBuilder(title.trim()), TITLE_SIZE, bold = true)
        val meta = DateFormat.getDateInstance(DateFormat.LONG).format(Date())
        pen.text(SpannableStringBuilder(question.trim().take(MAX_QUESTION) + "\n" + meta), BODY_SIZE, color = GREY)
        pen.gap(BLOCK_GAP)
        for (block in AiMarkdownBlocks.parse(answer)) {
            when (block) {
                is Block.Heading -> {
                    pen.gap(BLOCK_GAP)
                    pen.text(styled(block.inline), HEADING_SIZES.getOrElse(block.level - 1) { BODY_SIZE }, bold = true)
                }
                is Block.Paragraph -> pen.text(styled(block.inline), BODY_SIZE)
                is Block.Bullet -> pen.text(
                    SpannableStringBuilder(block.marker + "  ").append(styled(block.inline)),
                    BODY_SIZE,
                    indent = LIST_INDENT * (block.indent + 1),
                )
                is Block.Task -> pen.text(
                    SpannableStringBuilder(if (block.checked) "☑  " else "☐  ").append(styled(block.inline)),
                    BODY_SIZE,
                    indent = LIST_INDENT * (block.indent + 1),
                )
                is Block.Quote -> pen.text(styled(block.inline), BODY_SIZE, indent = LIST_INDENT * block.depth, color = GREY)
                is Block.Code -> pen.text(SpannableStringBuilder(block.code), CODE_SIZE, mono = true, indent = LIST_INDENT)
                is Block.Math -> pen.text(SpannableStringBuilder(block.latex), CODE_SIZE, mono = true, indent = LIST_INDENT)
                is Block.Table -> {
                    val rows = listOf(block.header) + block.rows
                    rows.forEachIndexed { index, row ->
                        pen.text(SpannableStringBuilder(row.joinToString("  |  ") { it.text }), CODE_SIZE, mono = true, bold = index == 0)
                    }
                }
                is Block.Image -> pen.text(SpannableStringBuilder(block.alt.ifBlank { block.url }), BODY_SIZE, color = GREY)
                is Block.Footnote -> pen.text(SpannableStringBuilder("${block.number}. ").append(styled(block.inline)), CODE_SIZE, color = GREY)
                is Block.Rule -> pen.gap(BLOCK_GAP)
            }
            pen.gap(BLOCK_GAP)
        }
        pen.finish()
        out.outputStream().use { document.writeTo(it) }
        document.close()
    }

    /** One inline run with its bold, italic, code and struck-out spans as Android spans. */
    private fun styled(inline: AiMarkdownBlocks.Inline): SpannableStringBuilder {
        val text = SpannableStringBuilder(inline.text)
        for (span in inline.spans) {
            val what: Any = when (span.style) {
                AiMarkdownBlocks.Style.BOLD -> StyleSpan(Typeface.BOLD)
                AiMarkdownBlocks.Style.ITALIC -> StyleSpan(Typeface.ITALIC)
                AiMarkdownBlocks.Style.CODE -> TypefaceSpan("monospace")
                AiMarkdownBlocks.Style.STRIKE -> StrikethroughSpan()
                AiMarkdownBlocks.Style.UNDERLINE, AiMarkdownBlocks.Style.LINK -> UnderlineSpan()
                else -> continue
            }
            val end = span.end.coerceAtMost(text.length)
            if (span.start < end) text.setSpan(what, span.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    /** Lays text down a page at a time, starting a new page when a line would not fit. */
    private class PdfPen(private val document: PdfDocument) {
        private var number = 0
        private var page: PdfDocument.Page? = null
        private var y = 0f

        private fun canvas(): Canvas = (page ?: next()).canvas

        private fun next(): PdfDocument.Page {
            page?.let { document.finishPage(it) }
            number++
            return document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()).also {
                page = it
                y = MARGIN.toFloat()
            }
        }

        fun gap(points: Float) {
            y += points
        }

        fun text(
            text: CharSequence,
            size: Float,
            bold: Boolean = false,
            mono: Boolean = false,
            indent: Float = 0f,
            color: Int = Color.BLACK,
        ) {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            if (color != Color.BLACK) {
                (text as? SpannableStringBuilder)?.setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val width = (PAGE_WIDTH - 2 * MARGIN - indent).toInt()
            val layout = staticLayout(text, paint, width)
            canvas()
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(line)
                val height = layout.getLineBottom(line) - top
                if (y + height > PAGE_HEIGHT - MARGIN) next()
                val target = canvas()
                target.save()
                target.translate(MARGIN + indent, y - top)
                target.clipRect(0, top, width, top + height)
                layout.draw(target)
                target.restore()
                y += height
            }
        }

        fun finish() {
            if (page == null) next()
            page?.let { document.finishPage(it) }
            page = null
        }

        private fun staticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setLineSpacing(0f, LINE_SPACING).build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, LINE_SPACING, 0f, false)
            }
    }

    /** Where the shared files are written, under the cache; named in the FileProvider's paths. */
    private const val DIR = "ai_exports"
    private const val MAX_NAME = 60
    private const val MAX_QUESTION = 600
    private val UNSAFE = Regex("""[\\/:*?"<>|\p{Cntrl}#%{}^~\[\]`]""")
    private val SPACES = Regex("""\s+""")

    // A4 in PostScript points, which is what PdfDocument measures in.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 54
    private const val TITLE_SIZE = 20f
    private val HEADING_SIZES = listOf(17f, 15f, 13f)
    private const val BODY_SIZE = 11f
    private const val CODE_SIZE = 9.5f
    private const val LIST_INDENT = 14f
    private const val BLOCK_GAP = 5f
    private const val LINE_SPACING = 1.2f
    private const val GREY = 0xFF666666.toInt()
}
