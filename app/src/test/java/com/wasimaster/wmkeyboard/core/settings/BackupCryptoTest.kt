package com.wasimaster.wmkeyboard.core.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }
    private val bundle = """{"format":"wmkeyboard-config","version":1,"sections":{}}"""

    /** Test rounds. The shipped default is far higher and far too slow for a test. */
    private val rounds = 1_000

    private fun seal(
        text: String = bundle,
        key: CharArray = passphrase,
        iterations: Int = rounds,
    ): ByteArray = ByteArrayOutputStream()
        .also { BackupCrypto.encrypt(it, key, salt, text, iterations) }
        .toByteArray()

    private fun open(bytes: ByteArray, key: CharArray = passphrase) =
        BackupCrypto.decrypt(ByteArrayInputStream(bytes), key)

    // ---- the KDF ----

    @Test
    fun `pbkdf2 matches the platform implementation`() {
        // The hand-rolled loop exists because PBKDF2WithHmacSHA256 is missing
        // from the Android provider below API 26. The JDK running these tests
        // does have it, so it is the reference to check against.
        for (iterations in listOf(1, 2, 977)) {
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase, salt, iterations, 32 * 8))
                .encoded
            assertArrayEquals(
                "iterations=$iterations",
                expected,
                BackupCrypto.pbkdf2(passphrase, salt, iterations, 32),
            )
        }
    }

    @Test
    fun `pbkdf2 spans more than one hash block`() {
        // 32 bytes is one SHA-256 block exactly, so the block loop never runs
        // twice in normal use and a bug in it would go unseen.
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, 64, 100 * 8))
            .encoded
        assertArrayEquals(expected, BackupCrypto.pbkdf2(passphrase, salt, 64, 100))
    }

    @Test
    fun `a non-ascii passphrase is encoded as utf-8`() {
        val unicode = "পাসওয়ার্ড🔑".toCharArray()
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(unicode, salt, 16, 32 * 8))
            .encoded
        assertArrayEquals(expected, BackupCrypto.pbkdf2(unicode, salt, 16, 32))
    }

    @Test
    fun `pbkdf2 over sha-512 matches the platform implementation`() {
        // What new files use. One SHA-512 block is 64 bytes, so the 32-byte
        // key is a prefix of it, and 100 bytes takes the block loop twice.
        for ((iterations, bytes) in listOf(1 to 32, 977 to 32, 64 to 100)) {
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(PBEKeySpec(passphrase, salt, iterations, bytes * 8))
                .encoded
            assertArrayEquals(
                "iterations=$iterations bytes=$bytes",
                expected,
                BackupCrypto.pbkdf2(passphrase, salt, iterations, bytes, "HmacSHA512"),
            )
        }
    }

    // ---- round trip ----

    @Test
    fun `a sealed bundle comes back`() {
        assertEquals(BackupCrypto.DecryptResult.Ok(bundle), open(seal()))
    }

    @Test
    fun `a new file says sha-512 in its header`() {
        assertEquals(2.toByte(), seal()[KDF_OFFSET])
    }

    @Test
    fun `a file from before sha-512 still opens`() {
        // Backups already sitting in people's folders were keyed over
        // HMAC-SHA256 under kdf id 1. They must restore after the switch.
        val header = seal().copyOf(HEADER_SIZE).also { it[KDF_OFFSET] = 1 }
        val nonce = header.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + 12)
        val key = BackupCrypto.pbkdf2(passphrase, salt, rounds, 32, "HmacSHA256")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
        }
        val legacy = header + cipher.doFinal(bundle.toByteArray(Charsets.UTF_8))
        assertEquals(BackupCrypto.DecryptResult.Ok(bundle), open(legacy))
    }

    @Test
    fun `an unknown kdf is reported as newer rather than as corrupt`() {
        val sealed = seal()
        sealed[KDF_OFFSET] = 9
        assertEquals(BackupCrypto.DecryptResult.UnsupportedVersion, open(sealed))
    }

    @Test
    fun `a large bundle comes back`() {
        // The encode path streams through the cipher rather than making one
        // more full-size copy, so it has to survive crossing a buffer boundary.
        val big = bundle.repeat(20_000)
        assertEquals(BackupCrypto.DecryptResult.Ok(big), open(seal(text = big)))
    }

    @Test
    fun `the file announces itself in its first four bytes`() {
        val sealed = seal()
        assertTrue(BackupCrypto.looksEncrypted(sealed))
        assertTrue(BackupCrypto.looksEncrypted(sealed.copyOf(BackupCrypto.MAGIC_SIZE)))
        assertFalse(BackupCrypto.looksEncrypted(bundle.toByteArray()))
        assertFalse(BackupCrypto.looksEncrypted(byteArrayOf(1, 2)))
    }

    @Test
    fun `every file gets its own nonce`() {
        // Two generations under one passphrase share a derived key, so a
        // repeated GCM nonce would break both of them at once.
        val first = seal()
        val second = seal()
        assertNotEquals(first.toList(), second.toList())
    }

    // ---- rejection ----

    @Test
    fun `the wrong passphrase is refused`() {
        assertEquals(
            BackupCrypto.DecryptResult.BadPassphraseOrCorrupt,
            open(seal(), key = "correct horse battery stapla".toCharArray()),
        )
    }

    @Test
    fun `a flipped ciphertext byte is refused`() {
        val sealed = seal()
        sealed[sealed.size - 8] = (sealed[sealed.size - 8].toInt() xor 1).toByte()
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(sealed))
    }

    @Test
    fun `a truncated file is refused`() {
        // The regression this format exists to avoid: CipherInputStream drops
        // AEADBadTagException on Android, so a half-written backup would come
        // back as half a bundle and report success. Truncation must be a
        // failure, at every length.
        val sealed = seal()
        for (cut in listOf(1, 8, HEADER_SIZE, HEADER_SIZE + 1, sealed.size - 1)) {
            val result = open(sealed.copyOf(cut))
            assertTrue(
                "cut=$cut gave $result",
                result == BackupCrypto.DecryptResult.BadPassphraseOrCorrupt ||
                    result == BackupCrypto.DecryptResult.NotEncrypted,
            )
        }
    }

    @Test
    fun `an edited header is refused`() {
        // The iteration count has to be read before anything is authenticated,
        // so it is covered as associated data instead. Rewriting it to 1 would
        // otherwise turn the file into a cheap offline cracking target.
        val sealed = seal()
        sealed[ITERATIONS_OFFSET] = 0
        sealed[ITERATIONS_OFFSET + 1] = 0
        sealed[ITERATIONS_OFFSET + 2] = 0
        sealed[ITERATIONS_OFFSET + 3] = 1
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(sealed))
    }

    @Test
    fun `an edited salt is refused`() {
        val sealed = seal()
        sealed[SALT_OFFSET] = (sealed[SALT_OFFSET].toInt() xor 0xFF).toByte()
        assertEquals(BackupCrypto.DecryptResult.BadPassphraseOrCorrupt, open(sealed))
    }

    @Test
    fun `a plain bundle is not mistaken for an encrypted one`() {
        assertEquals(
            BackupCrypto.DecryptResult.NotEncrypted,
            open(bundle.toByteArray()),
        )
        assertEquals(BackupCrypto.DecryptResult.NotEncrypted, open(ByteArray(0)))
    }

    @Test
    fun `a newer container is reported as such rather than as corrupt`() {
        val sealed = seal()
        sealed[VERSION_OFFSET] = 9
        assertEquals(BackupCrypto.DecryptResult.UnsupportedVersion, open(sealed))
    }

    @Test
    fun `an absurd iteration count is refused before it is run`() {
        // Otherwise a hostile file names two billion rounds and the restore
        // screen hangs for an hour.
        val sealed = seal()
        sealed[ITERATIONS_OFFSET] = 0x7F
        assertEquals(BackupCrypto.DecryptResult.UnsupportedVersion, open(sealed))
    }

    private companion object {
        const val HEADER_SIZE = 44
        const val VERSION_OFFSET = 7
        const val KDF_OFFSET = 8
        const val ITERATIONS_OFFSET = 10
        const val SALT_OFFSET = 15
        const val NONCE_OFFSET = 32
    }
}
