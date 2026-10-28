package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spaces the keyboard types for the user: which marks take one back, and
 * how much text the undo and the tap-to-replace have to remove to take a whole
 * glide commit with them.
 */
class GlideSpaceTest {

    // ---- glideStripOrder ----

    @Test
    fun `a picked word leads the strip and is not repeated`() {
        assertEquals(
            listOf("god", "good", "food"),
            glideStripOrder(listOf("good", "god", "food"), "god"),
        )
    }

    @Test
    fun `picking the leader changes nothing`() {
        assertEquals(
            listOf("good", "god", "food"),
            glideStripOrder(listOf("good", "god", "food"), "good"),
        )
    }

    @Test
    fun `a pick the decode no longer holds is still first`() {
        // The picker showed it, the finger lifted on it: it commits, and the
        // strip has to agree with the field.
        assertEquals(
            listOf("goof", "good", "god"),
            glideStripOrder(listOf("good", "god"), "goof"),
        )
    }

    // ---- swallowsAutoSpace ----

    @Test
    fun `sentence enders hug the word`() {
        for (mark in listOf(".", "!", "?", "।")) {
            assertTrue(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `clause separators hug the word`() {
        for (mark in listOf(",", ";", ":")) {
            assertTrue(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `closers hug the word`() {
        // "(hello)" — the bracket closes the word, so the space it was given
        // belongs after the bracket, not before it.
        for (mark in listOf(")", "]", "}", "\u201d")) {
            assertTrue(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `openers keep the space`() {
        // "hello (world)" is what anybody typing this means.
        for (mark in listOf("(", "[", "{")) {
            assertFalse(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `a straight quote opens on the first press and closes on the second`() {
        // Issue #34: taking the space back both times gave `he said"hello"`.
        assertFalse(swallowsAutoSpace("\"") { "he said " })
        assertTrue(swallowsAutoSpace("\"") { "he said \"hello " })
        assertFalse(swallowsAutoSpace("\"") { "he said \"hello\" and " })
    }

    @Test
    fun `a quote counts over its own line only`() {
        // An unclosed quote in the paragraph above must not flip every quote
        // under it.
        assertFalse(swallowsAutoSpace("\"") { "\"unfinished\nhello " })
        assertTrue(swallowsAutoSpace("\"") { "\"unfinished\n\"hello " })
    }

    @Test
    fun `a quote with no text behind it opens`() {
        // The default context is empty, which is an even count.
        assertFalse(swallowsAutoSpace("\""))
    }

    @Test
    fun `an apostrophe hugs the word without being counted`() {
        // It hugs for the possessive it almost always is (#123), but it stays
        // out of AMBIGUOUS_QUOTES: it is a letter inside a word ("don't"), so
        // counting its appearances says nothing about which end of a quotation
        // the next one is. The answer here does not depend on the text at all.
        assertTrue(swallowsAutoSpace("'") { "don't " })
        assertTrue(swallowsAutoSpace("'") { "he said 'hi' " })
    }

    @Test
    fun `letters and digits keep the space`() {
        assertFalse(swallowsAutoSpace("a"))
        assertFalse(swallowsAutoSpace("7"))
    }

    @Test
    fun `a dash hugs the word`() {
        // "well-known" is the common intent (#123). "hello - world" is still
        // one space press away, and that space is the user's own.
        assertTrue(swallowsAutoSpace("-"))
    }

    @Test
    fun `multi-character text keeps the space`() {
        // Symbol-layer inserts and emoji are not marks that end a word.
        assertFalse(swallowsAutoSpace("..."))
        assertFalse(swallowsAutoSpace("🙂"))
    }

    @Test
    fun `url separators hug the word in a url field`() {
        // "example/" and "my-site", never "example /" (#37).
        for (mark in listOf("/", "#", "&", "=", "@", "-", "_", "+", "~")) {
            assertTrue(mark, swallowsAutoSpace(mark, FieldKind.URI))
        }
    }

    @Test
    fun `url separators keep the space in a text field`() {
        for (mark in listOf("#", "&", "=", "@", "+", "~")) {
            assertFalse(mark, swallowsAutoSpace(mark, FieldKind.TEXT))
        }
    }

    @Test
    fun `a slash hugs the word in prose too`() {
        // "and/or" and "24/7" are prose, not addresses (#34 follow-up).
        assertTrue(swallowsAutoSpace("/", FieldKind.TEXT))
    }

    @Test
    fun `the word-building marks hug the word in prose`() {
        // "hello's", "well-known", "snake_case" (#123).
        for (mark in listOf("'", "\u2019", "-", "\u2013", "\u2014", "\\", "_")) {
            assertTrue(mark, swallowsAutoSpace(mark, FieldKind.TEXT))
        }
    }

    @Test
    fun `a hashtag and a mention keep the space in prose`() {
        // They attach to the word after them: "check this #cool" (#123).
        assertFalse(swallowsAutoSpace("#", FieldKind.TEXT))
        assertFalse(swallowsAutoSpace("@", FieldKind.TEXT))
        // Inside an address the same two are one more part of it.
        assertTrue(swallowsAutoSpace("#", FieldKind.URI))
        assertTrue(swallowsAutoSpace("@", FieldKind.URI))
    }

    @Test
    fun `prose marks still hug the word in a url field`() {
        assertTrue(swallowsAutoSpace(".", FieldKind.URI))
        assertTrue(swallowsAutoSpace("?", FieldKind.URI))
        assertFalse(swallowsAutoSpace("a", FieldKind.URI))
    }

    // ---- spacesBeforeGlidedWord ----

    @Test
    fun `a glided word is spaced from the word before it`() {
        assertTrue(spacesBeforeGlidedWord("hello"))
    }

    @Test
    fun `a glided word at the start of a field gets no space`() {
        assertFalse(spacesBeforeGlidedWord(""))
    }

    @Test
    fun `a glided word after a space gets no second one`() {
        assertFalse(spacesBeforeGlidedWord("hello "))
        assertFalse(spacesBeforeGlidedWord("hello\n"))
    }

    @Test
    fun `a glided word after an opener goes against it`() {
        // `(hello`, not `( hello` (#34 follow-up). The hashtag, the mention and
        // the apostrophes are openers too: they attach forward whatever stands
        // behind them, so `check this #` then a glide is `#cool` (#123).
        val openers =
            listOf("(", "[", "{", "\u201c", "\u2018", "\u00ab", "\u00bf", "\u00a1", "#", "@", "'", "\u2019")
        for (opener in openers) {
            assertFalse(opener, spacesBeforeGlidedWord("he said $opener"))
        }
    }

    @Test
    fun `a glided word after an opening quote goes against it`() {
        // The reported case: `he said " hello` instead of `he said "hello`.
        assertFalse(spacesBeforeGlidedWord("he said \""))
    }

    @Test
    fun `a glided word after a closing quote is spaced from it`() {
        assertTrue(spacesBeforeGlidedWord("he said \"hi\""))
    }

    @Test
    fun `an opening quote is counted over its own line`() {
        assertTrue(spacesBeforeGlidedWord("\"unfinished\nhe said \"hi\""))
        assertFalse(spacesBeforeGlidedWord("\"finished\"\nhe said \""))
    }

    @Test
    fun `a glided word after a closer is spaced from it`() {
        // The mirror of the openers: `(hi) hello`, never `(hi)hello`.
        assertTrue(spacesBeforeGlidedWord("(hi)"))
        assertTrue(spacesBeforeGlidedWord("hi."))
    }

    @Test
    fun `a glided word joins a mark that is joined to the word before it`() {
        // The reported case: `The/` then a glided "And" is `The/And`.
        assertFalse(spacesBeforeGlidedWord("The/"))
        for (joiner in listOf("/", "\\", "&", "=", "-", "\u2013", "\u2014", "_", "+", "~")) {
            assertFalse(joiner, spacesBeforeGlidedWord("well$joiner"))
        }
    }

    @Test
    fun `a glided word is spaced from a mark standing on its own`() {
        // "hello - world" is a sentence; the space in front of the dash is what
        // says so, where "well-known" has none.
        assertTrue(spacesBeforeGlidedWord("hello -"))
        assertTrue(spacesBeforeGlidedWord("option A /"))
    }

    @Test
    fun `a joiner with nothing at all in front of it takes no space`() {
        // Nothing to be separated from, and nothing it is attached to either.
        assertFalse(spacesBeforeGlidedWord("/"))
        // A line break in front of one reads as a mark standing on its own.
        assertTrue(spacesBeforeGlidedWord("hello\n-"))
    }

    @Test
    fun `a url field never spaces a glided word into an address`() {
        // An address has no spaces in it at all.
        assertFalse(spacesBeforeGlidedWord("example.", FieldKind.URI))
        assertFalse(spacesBeforeGlidedWord("github.com/wasi", FieldKind.URI))
        assertFalse(spacesBeforeGlidedWord("192", FieldKind.URI))
        assertFalse(spacesBeforeGlidedWord("", FieldKind.URI))
    }

    @Test
    fun `a url field spaces a glided word into a search`() {
        // The address bar is a search box first (#974): a typed word, then a
        // glided one, is two words of a search.
        assertTrue(spacesBeforeGlidedWord("weather", FieldKind.URI))
        assertTrue(spacesBeforeGlidedWord("weather in paris", FieldKind.URI))
        // A search still keeps its own space.
        assertFalse(spacesBeforeGlidedWord("weather ", FieldKind.URI))
    }

    // ---- glideCommitLength ----

    @Test
    fun `a word and its trailing space measure together`() {
        assertEquals(6, glideCommitLength("hello ", "hello"))
    }

    @Test
    fun `a word with no trailing space measures alone`() {
        // Handwriting arms the same undo but types no trailing space.
        assertEquals(5, glideCommitLength("hello", "hello"))
    }

    @Test
    fun `text before the word does not count`() {
        // The caller reads word-length-plus-one characters, so a longer field
        // arrives with its head already cut off — here the "y" of "hey hello".
        assertEquals(5, glideCommitLength("yhello", "hello"))
    }

    @Test
    fun `a word the field no longer ends in measures zero`() {
        // The app moved the text on under us — leave the field alone.
        assertEquals(0, glideCommitLength("hello!", "hello"))
        assertEquals(0, glideCommitLength("", "hello"))
    }

    @Test
    fun `a one-letter word measures with its space`() {
        assertEquals(2, glideCommitLength("a ", "a"))
    }

    @Test
    fun `an empty word measures zero`() {
        // Every string ends with "", so without this guard a blank word would
        // report a length and the caller would delete a character it never
        // committed.
        assertEquals(0, glideCommitLength("hello ", ""))
    }

    @Test
    fun `a second space past the word measures zero`() {
        // The caller's window ends one space past the word, so a field that
        // gained another one no longer matches and the undo stands down rather
        // than guessing which space was the keyboard's.
        assertEquals(0, glideCommitLength("ello  ", "hello"))
    }
}
