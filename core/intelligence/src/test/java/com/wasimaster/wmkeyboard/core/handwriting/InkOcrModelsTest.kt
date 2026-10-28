package com.wasimaster.wmkeyboard.core.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PaddleOCR handwriting packs' table: tags, pins, file names, languages. */
class InkOcrModelsTest {

    @Test
    fun `every pack has its own tag and files`() {
        val packs = InkOcrModels.Pack.entries
        assertEquals(packs.size, packs.map { it.tag }.toSet().size)
        assertTrue(packs.all { InkOcrModels.isTag(it.tag) && InkOcrModels.packFor(it.tag) == it })
        val parts = packs.flatMap { it.parts }
        assertEquals(parts.size, parts.map { it.fileName }.toSet().size)
        assertTrue(parts.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.bytes > 0 })
    }

    @Test
    fun `a language belongs to at most one pack`() {
        val all = InkOcrModels.Pack.entries.flatMap { pack -> pack.languages.map { it to pack.script } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `a file is found by its published name with or without the digest`() {
        val part = InkOcrModels.Pack.EAST_SLAVIC.model
        assertEquals(part, InkOcrModels.partOf(part.fileName))
        assertEquals(part, InkOcrModels.partOf("${part.sha256}-${part.fileName}"))
        assertEquals(part, InkOcrModels.partOf("Download/${part.fileName}"))
        assertNull(InkOcrModels.partOf("ppocrv5-rec.mnn"))
        assertEquals(InkOcrModels.Pack.EAST_SLAVIC, InkOcrModels.packOf(part))
    }

    @Test
    fun `ML Kit and zinnia tags are not ink tags`() {
        assertTrue(!InkOcrModels.isTag("en-US") && !InkOcrModels.isTag(ZinniaModels.JAPANESE))
        assertNull(InkOcrModels.packFor("en-US"))
    }
}
