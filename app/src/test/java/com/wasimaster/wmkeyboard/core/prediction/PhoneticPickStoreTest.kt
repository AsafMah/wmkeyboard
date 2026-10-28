package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Avro's remembered picks (Telegram group). */
class PhoneticPickStoreTest {

    @get:Rule val dir = TemporaryFolder()

    @Test fun aPickSurvivesASaveAndKeepsTheTypedCase() {
        val file = dir.newFile("phonetic_picks.tsv")
        val store = PhoneticPickStore(file)
        assertTrue(store.put("bn", "kotha", "কথা"))
        assertTrue(store.put("bn", "Tk", "টাকা"))
        assertFalse(store.put("bn", "kotha", "কথা"))
        store.save()
        val back = PhoneticPickStore(file).snapshot()
        assertEquals("কথা", back[phoneticPickKey("bn", "kotha")])
        assertEquals("টাকা", back[phoneticPickKey("bn", "Tk")])
        assertEquals(null, back[phoneticPickKey("bn", "tk")])
    }

    @Test fun aNewerPickReplacesTheOlder() {
        val store = PhoneticPickStore(null)
        store.put("bn", "kotha", "কথা")
        assertTrue(store.put("bn", "kotha", "কোথা"))
        assertEquals("কোথা", store.snapshot()[phoneticPickKey("bn", "kotha")])
    }
}
