package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A glided pronoun contraction leads where the word before starts a clause (#949). */
class ApostrophesLeadTest {

    @Test
    fun `leads at a sentence start and after a clause opener`() {
        assertEquals("he'll", Apostrophes.leadsAfter("hell", WordContext.SENTENCE_START))
        assertEquals("He'll", Apostrophes.leadsAfter("Hell", WordContext.SENTENCE_START))
        assertEquals("he'll", Apostrophes.leadsAfter("hell", "think"))
        assertEquals("we'd", Apostrophes.leadsAfter("wed", "and"))
        assertEquals("she'll", Apostrophes.leadsAfter("shell", "said"))
    }

    @Test
    fun `stays out of the way elsewhere`() {
        assertNull(Apostrophes.leadsAfter("hell", "the"))
        assertNull(Apostrophes.leadsAfter("hell", "to"))
        assertNull(Apostrophes.leadsAfter("hell", null))
        assertNull(Apostrophes.leadsAfter("well", WordContext.SENTENCE_START))
        assertNull(Apostrophes.leadsAfter("were", WordContext.SENTENCE_START))
        assertNull(Apostrophes.leadsAfter("its", WordContext.SENTENCE_START))
    }
}
