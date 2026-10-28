package com.wasimaster.wmkeyboard.core.tools

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PINs from the password generator (#899). */
class PinGenTest {

    @Test
    fun `a PIN is digits of the asked length, clamped to the offered range`() {
        val random = SecureRandom()
        for (length in listOf(4, 6, 8, 12)) {
            val pin = PasswordGen.pin(length, random = random)
            assertEquals(length, pin.length)
            assertTrue(pin.all { it.isDigit() })
        }
        assertEquals(4, PasswordGen.pin(2).length)
        assertEquals(12, PasswordGen.pin(40).length)
    }

    @Test
    fun `the shapes rules refuse are weak`() {
        for (weak in listOf("1111", "1234", "9876", "1212", "123123", "1987", "2512", "1225", "250390", "19870614")) {
            assertTrue(weak, PasswordGen.isWeakPin(weak))
        }
        for (fine in listOf("4739", "8351", "730482", "93857261")) {
            assertFalse(fine, PasswordGen.isWeakPin(fine))
        }
    }

    @Test
    fun `avoiding weak PINs never hands one back`() {
        val random = SecureRandom()
        repeat(2000) { assertFalse(PasswordGen.isWeakPin(PasswordGen.pin(4, avoidWeak = true, random = random))) }
    }
}
