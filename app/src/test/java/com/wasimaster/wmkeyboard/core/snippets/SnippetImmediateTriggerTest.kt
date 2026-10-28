package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index half of [Snippet.immediate]: what fires on a keystroke, and what no longer waits for the space. */
class SnippetImmediateTriggerTest {

    private fun snippet(
        id: Long,
        trigger: String,
        immediate: Boolean = true,
        insideWords: Boolean = false,
        confirm: Boolean = false,
    ) = Snippet(
        id = id,
        label = trigger,
        text = "text$id",
        trigger = trigger,
        immediate = immediate,
        insideWords = insideWords,
        confirm = confirm,
    )

    @Test
    fun `an immediate trigger matches the end of the field`() {
        val index = SnippetIndex.of(listOf(snippet(1, "btw")))
        assertEquals(1L, index.matchImmediate("ok btw")?.snippet?.id)
        assertEquals(1L, index.matchImmediate("BTW")?.snippet?.id)
        assertNull(index.matchImmediate("ok bt"))
    }

    @Test
    fun `a word-led trigger needs a boundary unless it allows inside words`() {
        assertNull(SnippetIndex.of(listOf(snippet(1, "btw"))).matchImmediate("abtw"))
        assertNotNull(SnippetIndex.of(listOf(snippet(1, "btw", insideWords = true))).matchImmediate("abtw"))
        // Punctuation is its own boundary.
        assertNotNull(SnippetIndex.of(listOf(snippet(1, ";;addr"))).matchImmediate("x;;addr"))
    }

    @Test
    fun `it no longer waits for the space`() {
        val index = SnippetIndex.of(listOf(snippet(1, "btw"), snippet(2, ":sig")))
        assertNull(index.matchTrigger("btw"))
        assertNull(index.matchPrefix("sig", "x :"))
        // A glide still lands on the one-word spelling.
        assertTrue("btw" in index.expandingTriggers)
        assertEquals(1L, index.matchImmediateWord("BTW")?.id)
        assertNull(index.matchImmediateWord("sig"))
    }

    @Test
    fun `a snippet that asks keeps the ordinary paths`() {
        val index = SnippetIndex.of(listOf(snippet(1, "btw", confirm = true)))
        assertFalse(index.hasImmediateTriggers)
        assertNotNull(index.matchTrigger("btw"))
    }

    @Test
    fun `the free gate looks at the last key and the buffer only`() {
        val index = SnippetIndex.of(listOf(snippet(1, "btw"), snippet(2, ":sig")))
        assertTrue(index.couldFinishImmediate('w', "btw"))
        assertTrue(index.couldFinishImmediate('g', "sig"))
        // Nothing composing: the field has to be asked.
        assertTrue(index.couldFinishImmediate('w', ""))
        assertFalse(index.couldFinishImmediate('w', "bow"))
        assertFalse(index.couldFinishImmediate('x', "btx"))
        assertEquals(4, index.immediateReach)
    }

    @Test
    fun `the longest trigger wins`() {
        val index = SnippetIndex.of(listOf(snippet(1, "addr"), snippet(2, ";;addr")))
        assertEquals(2L, index.matchImmediate("x ;;addr")?.snippet?.id)
        assertEquals(1L, index.matchImmediate("x addr")?.snippet?.id)
    }

    @Test
    fun `the flags round-trip through the store file and stay off the wire when unset`() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val plain = json.encodeToString(Snippet.serializer(), snippet(1, "omw", immediate = false))
        assertFalse(plain.contains("immediate"))
        assertFalse(plain.contains("insideWords"))
        val set = snippet(1, "btw", insideWords = true)
        assertEquals(set, json.decodeFromString(Snippet.serializer(), json.encodeToString(Snippet.serializer(), set)))
    }
}
