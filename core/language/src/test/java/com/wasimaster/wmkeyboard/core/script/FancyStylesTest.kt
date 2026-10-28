package com.wasimaster.wmkeyboard.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fancy Text style table. The glyphs were lifted from the retired
 * per-style layouts, so the spot checks below pin a few of their exact
 * values — including the awkward ones: astral pairs, combining marks, the
 * capital-less small caps, and superscript's plain-q fallback.
 */
class FancyStylesTest {

    @Test
    fun `normal plus the 31 styles are present, in the shipped order, with a default`() {
        assertEquals(33, FancyStyles.all.size)
        assertEquals(FancyStyles.NORMAL_ID, FancyStyles.all.first().id)
        assertEquals("bold", FancyStyles.all[1].id)
        // The tail of the original 22, then the tail of the whole table: the
        // order is append-only, and both ends pin it.
        assertEquals("underline", FancyStyles.all[22].id)
        assertEquals("bypass", FancyStyles.all.last().id)
        assertNotNull(FancyStyles.byId(FancyStyles.DEFAULT_ID))
        assertEquals(FancyStyles.all.size, FancyStyles.all.distinctBy { it.id }.size)
    }

    @Test
    fun `normal leaves text exactly as typed`() {
        val normal = FancyStyles.byId(FancyStyles.NORMAL_ID)!!
        assertEquals("Hello World! 123", FancyStyles.transform("Hello World! 123", normal))
    }

    @Test
    fun `every style maps all 26 letters in both cases`() {
        for (style in FancyStyles.all) {
            assertEquals(style.id, 26, style.lower.size)
            assertEquals(style.id, 26, style.upper.size)
            for (c in 'a'..'z') assertTrue(style.id, style.lower.getValue(c).isNotEmpty())
            for (c in 'A'..'Z') assertTrue(style.id, style.upper.getValue(c).isNotEmpty())
        }
    }

    @Test
    fun `bold maps onto the mathematical bold block`() {
        val bold = FancyStyles.byId("bold")!!
        assertEquals("𝐚", bold.lower.getValue('a'))
        assertEquals("𝐀", bold.upper.getValue('A'))
        assertEquals("𝐇𝐞𝐥𝐥𝐨 𝐖𝐨𝐫𝐥𝐝!", FancyStyles.transform("Hello World!", bold))
    }

    @Test
    fun `small caps has no capitals of its own`() {
        val smallCaps = FancyStyles.byId("small_caps")!!
        for (c in 'a'..'z') {
            assertEquals(
                smallCaps.lower.getValue(c),
                smallCaps.upper.getValue(c.uppercaseChar()),
            )
        }
    }

    @Test
    fun `superscript falls back to the plain letter where no form exists`() {
        val superscript = FancyStyles.byId("superscript")!!
        assertEquals("q", superscript.lower.getValue('q'))
    }

    @Test
    fun `the combining styles append their mark to the plain letter`() {
        val underline = FancyStyles.byId("underline")!!
        val styled = underline.lower.getValue('a')
        assertEquals(2, styled.length)
        assertEquals('a', styled[0])
        assertEquals('̲', styled[1])
    }

    @Test
    fun `non-letters pass through the transform untouched`() {
        val fraktur = FancyStyles.byId("fraktur")!!
        assertEquals("123 ,.!? ৳", FancyStyles.transform("123 ,.!? ৳", fraktur))
        assertEquals("", FancyStyles.transform("", fraktur))
    }

    @Test
    fun `styles with digits of their own style numbers too`() {
        assertEquals("𝐀𝟏𝟐", FancyStyles.transform("A12", FancyStyles.byId("bold")!!))
        assertEquals("𝟘𝟡", FancyStyles.transform("09", FancyStyles.byId("double_struck")!!))
        assertEquals("𝟶𝟿", FancyStyles.transform("09", FancyStyles.byId("monospace")!!))
        assertEquals("⓪①⑨", FancyStyles.transform("019", FancyStyles.byId("circled")!!))
        assertEquals("⓿❶❾", FancyStyles.transform("019", FancyStyles.byId("circled_filled")!!))
        assertEquals("⁰¹²³⁴⁹", FancyStyles.transform("012349", FancyStyles.byId("superscript")!!))
        // Parenthesized has no zero: it stays plain rather than vanishing.
        assertEquals("0⑴⑼", FancyStyles.transform("019", FancyStyles.byId("parenthesized")!!))
        // No digits of its own: borrows the bold set rather than going plain.
        assertEquals("𝟕", FancyStyles.transform("7", FancyStyles.byId("bold_italic")!!))
    }

    @Test
    fun `a digit's long press lists every styled set, astral ones whole`() {
        val forms = FancyStyles.digitForms('1')
        assertEquals(
            listOf("𝟏", "𝟙", "𝟣", "𝟭", "𝟷", "１", "①", "❶", "➀", "⑴", "⒈", "¹", "₁", "🯱"),
            forms,
        )
        // Zero: parenthesized has none, the rest keep it out of their run.
        val zeros = FancyStyles.digitForms('0')
        assertEquals(13, zeros.size)
        assertTrue("🄋" in zeros && "🄀" in zeros && "⓪" in zeros && "⓿" in zeros)
        assertTrue(FancyStyles.digitForms('a').isEmpty())
    }

    @Test
    fun `upside down text is laid out back to front`() {
        val upsideDown = FancyStyles.byId("upside_down")!!
        // Rotated glyphs alone would spell it backwards to anyone who turns
        // the phone round; reversing the order is what makes it read.
        assertEquals("uʍop", FancyStyles.transform("down", upsideDown))
        // Reversal is by mapped unit, not by char: B maps to an astral pair,
        // and reversing chars would split it into two orphaned surrogates.
        val b = upsideDown.upper.getValue('B')
        assertEquals(2, b.length)
        assertEquals(b, FancyStyles.transform("B", upsideDown))
        assertEquals(upsideDown.lower.getValue('a') + b, FancyStyles.transform("Ba", upsideDown))
    }

    @Test
    fun `bypass separates visible characters and leaves whitespace alone`() {
        val bypass = FancyStyles.byId("bypass")!!
        assertEquals("a\u200Bb\u200Bc", FancyStyles.transform("abc", bypass))
        // No separator either side of a space: the text already breaks there.
        assertEquals("a\u200Bb c\u200Bd", FancyStyles.transform("ab cd", bypass))
        // Digits and punctuation map to themselves and are still held apart —
        // a filter matching "a1!" must not see three adjacent characters.
        assertEquals("a\u200B1\u200B!", FancyStyles.transform("a1!", bypass))
        assertEquals("a", FancyStyles.transform("a", bypass))
        assertEquals("", FancyStyles.transform("", bypass))
    }

    /**
     * Fancy Text styles everything committed to the field (#950), emoji and
     * text that is styled already included, so neither may change on a
     * second pass.
     */
    @Test
    fun `bypass keeps an emoji whole and leaves styled text alone`() {
        val bypass = FancyStyles.byId("bypass")!!
        assertEquals("👍🏽", FancyStyles.transform("👍🏽", bypass))
        assertEquals("❤️", FancyStyles.transform("❤️", bypass))
        val once = FancyStyles.transform("ab cd", bypass)
        assertEquals(once, FancyStyles.transform(once, bypass))
    }

    @Test
    fun `unstyle reads every style but upside down back to plain`() {
        for (style in FancyStyles.all) {
            if (style.assembly == FancyAssembly.REVERSED) continue
            val plain = FancyStyles.unstyle(FancyStyles.transform("hello world 42", style))
            // Small caps and superscript have no capitals to read back.
            assertEquals(style.id, "hello world 42", plain.lowercase())
        }
        // Plain text, including letters other styles borrow, is left alone.
        assertEquals("unusual café", FancyStyles.unstyle("unusual café"))
    }

    @Test
    fun `samples are written in their own style`() {
        for (style in FancyStyles.all) {
            assertEquals(
                style.id,
                FancyStyles.transform(style.name, style),
                style.sample,
            )
        }
    }
}
