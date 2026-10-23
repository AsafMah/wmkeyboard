package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineImportTest {

    @Test
    fun `an interface language pack is installed whole, not unzipped`() {
        assertTrue(OfflineImport.keptWhole("wmkeyboard-0.6.3-vc370-lang-de.apk"))
        assertTrue(OfflineImport.keptWhole("wmkeyboard-0.6.3-vc370-lang-zh-rCN.APK"))
        assertTrue(LanguagePackDownloads.isPackName("wmkeyboard-0.6.3-vc370-lang-zh-rCN.apk"))
    }

    @Test
    fun `a language pack zip is still opened`() {
        assertFalse(OfflineImport.keptWhole("wmkb-lang-de.zip"))
        assertFalse(OfflineImport.keptWhole("qnn-runtime-2.51.0.aar"))
    }
}
