package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkdownTest {

    @Test
    fun `plain prose is not markdown`() {
        assertFalse(AiMarkdown.hasMarkdown("Hello there, this is a plain sentence."))
        assertFalse(AiMarkdown.hasMarkdown("Multiply 2 * 3 * 4 to get 24."))
        assertFalse(AiMarkdown.hasMarkdown("The file_name_here has underscores."))
    }

    @Test
    fun `markers are detected`() {
        assertTrue(AiMarkdown.hasMarkdown("## Heading"))
        assertTrue(AiMarkdown.hasMarkdown("- first\n- second"))
        assertTrue(AiMarkdown.hasMarkdown("that is **bold**"))
        assertTrue(AiMarkdown.hasMarkdown("run `ls -l` first"))
        assertTrue(AiMarkdown.hasMarkdown("see [docs](https://example.com)"))
    }

    @Test
    fun `inline emphasis and code lose their markers`() {
        assertEquals("bold and italic and code", AiMarkdown.strip("**bold** and *italic* and `code`"))
        assertEquals("underscored bold", AiMarkdown.strip("__underscored bold__"))
    }

    @Test
    fun `headings and rules go away, bullets become dots`() {
        assertEquals(
            // The rule line goes; the blank line that separated it stays, so
            // the paragraph spacing the model intended survives.
            "Title\n\n• one\n• two",
            AiMarkdown.strip("## Title ##\n\n---\n- one\n- two"),
        )
    }

    @Test
    fun `fenced code keeps its lines but loses the fences`() {
        assertEquals("val x = 1", AiMarkdown.strip("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun `links keep both label and url`() {
        assertEquals(
            "See docs (https://example.com) for more",
            AiMarkdown.strip("See [docs](https://example.com) for more"),
        )
    }

    @Test
    fun `blockquotes lose their prefix`() {
        assertEquals("quoted line", AiMarkdown.strip("> quoted line"))
    }

    @Test
    fun `a table keeps a line per row and loses its divider`() {
        val table = "| Phone | Price |\n|---|:---:|\n| **A1** | \$200 |\n| B2 | 300 \\| 350 |"
        assertTrue(AiMarkdown.hasMarkdown(table))
        assertEquals("Phone | Price\nA1 | \$200\nB2 | 300 | 350", AiMarkdown.strip(table))
    }

    @Test
    fun `stripping plain text changes nothing but trailing space`() {
        assertEquals("Just a sentence.", AiMarkdown.strip("Just a sentence.\n"))
    }
}
