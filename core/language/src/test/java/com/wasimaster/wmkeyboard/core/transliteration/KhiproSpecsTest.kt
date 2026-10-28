package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manifest of a published Khipro spec set (#909), as the data repository writes it. */
class KhiproSpecsTest {

    private fun file(name: String) = """{"name": "$name", "size": 1000, "sha256": "${"a".repeat(64)}"}"""

    private fun manifest(
        format: Int = 1,
        revision: String = "2026-10-25T13:25:34Z",
        desktopSpec: String = "bn-khipro.mim",
    ) = """
        {
          "format": $format,
          "revision": "$revision",
          "upstream": {"rank-coder/khipro-m17n": "f4ef2771e391"},
          "files": {
            "desktop": {"spec": ${file(desktopSpec)}, "cases": ${file("khipro-testcases.tsv")}},
            "touchscreen": {"spec": ${file("bn-khipro-touchscreen.mim")}, "cases": ${file("khipro-testcases-touch.tsv")}},
            "dark_desktop": {"spec": ${file("bn-khipro-dark.mim")}, "cases": ${file("khipro-dark-testcases.tsv")}},
            "dark_touchscreen": {"spec": ${file("bn-khipro-dark-touchscreen.mim")}, "cases": ${file("khipro-dark-testcases-touch.tsv")}}
          },
          "stale": [["kso/", "কসঁ"], ["........", "......."]]
        }
    """.trimIndent()

    @Test fun readsEveryVariantAndTheStaleCases() {
        val m = KhiproSpecs.parse(manifest())
        assertEquals("2026-10-25T13:25:34Z", m.revision)
        assertEquals(Khipro.Variant.entries.toSet(), m.specs.keys)
        assertEquals("bn-khipro-dark-touchscreen.mim", m.specs.getValue(Khipro.Variant.DARK_TOUCHSCREEN).name)
        assertEquals("khipro-testcases-touch.tsv", m.cases.getValue(Khipro.Variant.TOUCHSCREEN).name)
        assertEquals(listOf("kso/" to "কসঁ", "........" to "......."), m.stale)
        assertEquals(8, m.files.size)
    }

    @Test fun refusesWhatItCannotTrust() {
        assertThrows(IllegalArgumentException::class.java) { KhiproSpecs.parse("<html>404</html>") }
        assertThrows(IllegalArgumentException::class.java) { KhiproSpecs.parse(manifest(format = 2)) }
        // A revision that does not sort as text against the bundled one.
        assertThrows(IllegalArgumentException::class.java) { KhiproSpecs.parse(manifest(revision = "25 Oct 2026")) }
        // A file name with a path in it would escape the folder the set goes to.
        assertThrows(IllegalArgumentException::class.java) { KhiproSpecs.parse(manifest(desktopSpec = "../bn-khipro.mim")) }
        assertThrows(IllegalArgumentException::class.java) { KhiproSpecs.parse(manifest().replace("\"dark_desktop\"", "\"other\"")) }
    }

    @Test fun anOfflineImportKnowsTheSetsFiles() {
        assertTrue(KhiproSpecs.isSetFile(KhiproSpecs.MANIFEST_NAME))
        assertTrue(KhiproSpecs.isSetFile("bn-khipro-dark-touchscreen.mim"))
        assertTrue(KhiproSpecs.isSetFile("khipro-dark-testcases-touch.tsv"))
        assertTrue(KhiproSpecs.isSetFile("khipro-testcases.tsv"))
        assertFalse(KhiproSpecs.isSetFile("bn_khipro_glide.txt.gz"))
        assertFalse(KhiproSpecs.isSetFile("bn_full.txt.gz"))
    }
}
