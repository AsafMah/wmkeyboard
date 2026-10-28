package com.wasimaster.wmkeyboard.core.settings

import android.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A backup passphrase that arrives by import or sync comes without the salt it
 * needs to write a file (#940): one has to be minted on arrival.
 */
@RunWith(RobolectricTestRunner::class)
class ImportedPassphraseSaltTest {

    private fun repository() = SettingsRepository(RuntimeEnvironment.getApplication())

    private suspend fun SettingsRepository.assertUsableSalt() {
        val auto = settings.first().autoBackup
        assertEquals("secret", auto.passphrase)
        assertEquals(16, Base64.decode(auto.kdfSalt, Base64.NO_WRAP).size)
    }

    @Test
    fun `an imported passphrase gets a salt`() = runBlocking {
        val repository = repository()
        repository.setAutoBackupEncrypt(true)
        repository.setAutoBackupPassphrase("secret")
        val bundle = repository.exportConfig(
            sections = setOf(ConfigBackup.Section.SETTINGS),
            includeSecrets = true,
            appVersion = 1,
            appVersionName = "test",
        )
        repository.setAutoBackupPassphrase("")
        assertTrue(repository.settings.first().autoBackup.kdfSalt.isEmpty())

        repository.importConfig(bundle)

        repository.assertUsableSalt()
    }

    @Test
    fun `a synced passphrase gets a salt`() = runBlocking {
        val repository = repository()
        val put = JsonObject(
            mapOf(
                SettingsBackup.AUTO_BACKUP_PASSPHRASE to JsonObject(
                    mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("secret")),
                ),
            ),
        )

        repository.applySyncedSettings(put, emptySet())

        repository.assertUsableSalt()
    }
}
