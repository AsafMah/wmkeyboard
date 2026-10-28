package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Sogou `.scel` cell files (Telegram group). */
class SogouScelTest {

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.text(s: String) {
        val b = s.toByteArray(Charsets.UTF_16LE)
        u16(b.size)
        write(b)
    }

    /** A cell file holding 你好 (ni hao, count 9) and 拟好 (ni hao, count 2). */
    private fun cell(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x40, 0x15, 0, 0, 0x44, 0x43, 0x53, 0x01, 0x01, 0, 0, 0))
        while (out.size() < 0x1540) out.write(0)
        out.u16(2); out.u16(0)
        out.u16(0); out.text("ni")
        out.u16(1); out.text("hao")
        while (out.size() < 0x2628) out.write(0)
        out.u16(2); out.u16(4); out.u16(0); out.u16(1)
        out.text("你好"); out.u16(10); out.u16(9); repeat(8) { out.write(0) }
        out.text("拟好"); out.u16(10); out.u16(2); repeat(8) { out.write(0) }
        return out.toByteArray()
    }

    @Test fun readsTheWordsWithTheirSyllables() {
        val bytes = cell()
        assertTrue(SogouScel.looksLikeScel(bytes))
        assertEquals(
            listOf(SogouScel.Entry("你好", listOf("ni", "hao"), 9), SogouScel.Entry("拟好", listOf("ni", "hao"), 2)),
            SogouScel.read(bytes),
        )
        assertEquals("你好\tni'hao\t9\n拟好\tni'hao\t2\n", SogouScel.toText(bytes))
    }

    @Test fun anythingElseIsNotOne() {
        assertFalse(SogouScel.looksLikeScel("word\tfreq".toByteArray()))
        assertEquals(emptyList<SogouScel.Entry>(), SogouScel.read(ByteArray(16)))
    }
}
