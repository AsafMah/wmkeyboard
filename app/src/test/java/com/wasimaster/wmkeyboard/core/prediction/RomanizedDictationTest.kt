package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.script.RomanizedSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException

/** Dictation in a romanized language, heard as the language and written in Latin letters (#948). */
class RomanizedDictationTest {

    private val assets = mapOf(
        "dictionaries/en_ta.tsv" to "meeting\tமீட்டிங்\n",
        "dictionaries/ta_rom.tsv" to "romba\tரொம்ப\n",
    )

    private val dictation = RomanizedDictation { name ->
        assets[name]?.byteInputStream(Charsets.UTF_8) ?: throw FileNotFoundException(name)
    }

    @Test
    fun `a romanized language is heard as the language itself`() {
        assertEquals("hi", RomanizedSpeech.spokenAs("hi_rom"))
        assertEquals("ta", RomanizedSpeech.spokenAs("ta_rom"))
        assertNull(RomanizedSpeech.spokenAs("hi"))
        assertNull(RomanizedSpeech.spokenAs("en"))
        // Romi is Konkani's own spelling, not a transliteration.
        assertNull(RomanizedSpeech.spokenAs("kok_rom"))
        assertEquals(listOf("hi", "en"), RomanizedSpeech.heard(listOf("hi_rom", "en", "hi")))
    }

    @Test
    fun `a language picked in both scripts is left as heard`() {
        assertEquals(listOf("hi_rom"), RomanizedSpeech.targets(listOf("hi_rom", "en")))
        assertEquals(emptyList<String>(), RomanizedSpeech.targets(listOf("hi_rom", "hi")))
    }

    @Test
    fun `the maps spell a loanword in english and a common word as people chat it`() {
        dictation.prepare(listOf("ta_rom"))
        assertEquals("Romba nalla meeting", dictation.romanize("ரொம்ப நல்ல மீட்டிங்", listOf("ta_rom")))
    }

    @Test
    fun `before the maps are loaded the romanizer still writes every word`() {
        assertEquals("Romba nalla", dictation.romanize("ரொம்ப நல்ல", listOf("ta_rom")))
    }

    @Test
    fun `a model spells a long word the maps do not know, and a short one stays with the rules`() {
        dictation.prepare(listOf("ta_rom"))
        val heard = "ரொம்ப நல்ல வணக்கம்"
        // ரொம்ப is in the map; நல்ல is four characters, too short to ask about.
        assertEquals(mapOf("ta" to listOf("வணக்கம்")), dictation.unmapped(heard, listOf("ta_rom")))
        val readings = mapOf("ta" to mapOf("வணக்கம்" to listOf("vanakam", "vanakkam")))
        assertEquals("Romba nalla vanakam", dictation.romanize(heard, listOf("ta_rom"), readings))
        assertEquals("Romba nalla vanakkam", dictation.romanize(heard, listOf("ta_rom")))
    }

    @Test
    fun `latin already in the phrase, and a language not picked, are left alone`() {
        assertEquals("meeting nalla", dictation.romanize("meeting நல்ல", listOf("ta_rom")))
        assertEquals("नमस्ते", dictation.romanize("नमस्ते", listOf("ta_rom")))
        assertEquals("ரொம்ப", dictation.romanize("ரொம்ப", emptyList()))
    }

    @Test
    fun `digits come out in ascii and a sinhala stop stays a stop`() {
        assertEquals("Mama 4 ta enawaa.", dictation.romanize("මම 4 ට එනවා.", listOf("si_rom")))
        assertEquals("Privet, 5", dictation.romanize("Привет, 5", listOf("ru_rom")))
    }
}
