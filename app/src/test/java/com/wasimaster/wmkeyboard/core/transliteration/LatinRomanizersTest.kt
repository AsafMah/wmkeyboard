package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

/** The romanizers dictation in Tanglish, Roman Sinhala and Translit writes with (#948). */
class LatinRomanizersTest {

    private fun dravidian(script: IndicScript, text: String) =
        DravidianRomanizer.romanize(script, Normalizer.normalize(text, Normalizer.Form.NFC))

    @Test
    fun `tamil stops are read by where they stand`() {
        assertEquals("naan unnai kaadhalikkiren", dravidian(IndicScript.TAMIL, "நான் உன்னை காதலிக்கிறேன்"))
        assertEquals("thamizh", dravidian(IndicScript.TAMIL, "தமிழ்"))
        assertEquals("pacchai sangam vandhu", dravidian(IndicScript.TAMIL, "பச்சை சங்கம் வந்து"))
        assertEquals("kaatru ondru", dravidian(IndicScript.TAMIL, "காற்று ஒன்று"))
    }

    @Test
    fun `telugu and kannada say the vowel at the end of the word`() {
        assertEquals("nuvvu ekkada unnaavu", dravidian(IndicScript.TELUGU, "నువ్వు ఎక్కడ ఉన్నావు"))
        assertEquals("kannada", dravidian(IndicScript.KANNADA, "ಕನ್ನಡ"))
        assertEquals("hegiddeeraa", dravidian(IndicScript.KANNADA, "ಹೇಗಿದ್ದೀರಾ"))
    }

    @Test
    fun `malayalam chillus, its closing u and its rra`() {
        assertEquals("njaan", dravidian(IndicScript.MALAYALAM, "ഞാൻ"))
        assertEquals("malayaalam nalla bhaashayaanu", dravidian(IndicScript.MALAYALAM, "മലയാളം നല്ല ഭാഷയാണ്"))
        assertEquals("ente ningal otta", dravidian(IndicScript.MALAYALAM, "എന്റെ നിങ്ങൾ ഒറ്റ"))
    }

    @Test
    fun `sinhala keeps its inherent vowels and doubles a doubled consonant once`() {
        assertEquals("kohomada", SinhalaRomanizer.romanize("කොහොමද"))
        assertEquals("mama yanne, akkaa.", SinhalaRomanizer.romanize("මම යන්නෙ, අක්කා."))
        assertEquals("shree", SinhalaRomanizer.romanize("ශ්‍රී"))
    }

    @Test
    fun `russian translit keeps capitals`() {
        assertEquals("Privet, kak dela?", CyrillicRomanizer.romanize("Привет, как дела?"))
        assertEquals("Moskva ZHKH zhizn schuka", CyrillicRomanizer.romanize("Москва ЖКХ жизнь щука"))
    }
}
