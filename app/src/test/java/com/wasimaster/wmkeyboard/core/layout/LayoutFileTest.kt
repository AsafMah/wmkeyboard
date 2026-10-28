package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutFileTest {

    private fun usable(vararg extra: Key) = LayoutSpec(
        id = "custom_1",
        name = "Mine",
        layers = mapOf(
            LayoutLayer.LETTERS.key to LayerSpec(
                listOf(
                    listOf(Key("a"), Key("b")) + extra.toList(),
                    listOf(
                        Key("⇧", action = KeyAction.Shift),
                        Key(" ", action = KeyAction.Space),
                        Key("⌫", action = KeyAction.Delete),
                        Key("⏎", action = KeyAction.Enter),
                    ),
                ),
            ),
        ),
    )

    private fun encode(spec: LayoutSpec) = LayoutFile.encode(spec, 12, "1.2")

    @Test
    fun `a layout round trips through the envelope`() {
        val spec = usable()
        val imported = LayoutFile.decode(encode(spec))
        assertNotNull(imported)
        assertEquals(spec, imported!!.layout)
        assertEquals(12, imported.fromAppVersion)
        assertEquals(emptyList<LayoutMessage>(), imported.repairNotes)
    }

    /**
     * The whole reason for the envelope. The theme export writes a bare object
     * whose every field has a default, so decoding an unrelated JSON object
     * succeeds and silently yields an all-defaults theme.
     */
    @Test
    fun `an unrelated json object is rejected rather than half-read`() {
        assertNull(LayoutFile.decode("""{"shopping":["milk","eggs"]}"""))
        assertNull(LayoutFile.decode("{}"))
    }

    @Test
    fun `a file with the wrong format tag is rejected`() {
        val wrong = encode(usable()).replace("wmkeyboard-layout", "wmkeyboard-theme")
        assertNull(LayoutFile.decode(wrong))
    }

    @Test
    fun `a file with no layout at all is rejected`() {
        assertNull(LayoutFile.decode("""{"format":"wmkeyboard-layout","version":1}"""))
    }

    @Test
    fun `malformed json is rejected rather than throwing`() {
        assertNull(LayoutFile.decode("{not json"))
        assertNull(LayoutFile.decode(""))
    }

    /** Past the format tag it repairs rather than refuses, and says what it did. */
    @Test
    fun `a layout missing a delete key is imported repaired with a report`() {
        val broken = LayoutSpec(
            id = "custom_1",
            name = "Broken",
            layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))),
        )
        val imported = LayoutFile.decode(encode(broken))
        assertNotNull(imported)
        assertTrue(imported!!.repairNotes.isNotEmpty())
        assertTrue("and the result is safe to type on", imported.layout.canBeEnabled())
    }

    @Test
    fun `an unknown action from a newer build is dropped and reported`() {
        // Written out rather than patched into an encoded file: encodeDefaults
        // emits every field, so splicing an action in would leave two of them.
        val text = """
            {
              "format": "wmkeyboard-layout",
              "version": 1,
              "appVersion": 99,
              "layout": {
                "id": "custom_1",
                "name": "From the future",
                "layers": {
                  "letters": {
                    "rows": [
                      [
                        {"label": "a"},
                        {"label": "b", "action": {"type": "teleport", "to": "mars"}},
                        {"label": "c"}
                      ],
                      [
                        {"label": "⇧", "action": {"type": "shift"}},
                        {"label": " ", "action": {"type": "space"}},
                        {"label": "⌫", "action": {"type": "delete"}},
                        {"label": "⏎", "action": {"type": "enter"}}
                      ]
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val imported = LayoutFile.decode(text)
        assertNotNull("one strange key must not cost the whole file", imported)
        // The note is checked by identity, not by its wording: the resource it
        // names plus the arguments that carry the layer and the unknown tag.
        // Asserting on the English would break the next time it is reworded.
        val notes = imported!!.repairNotes
        assertTrue(
            "repair notes were $notes",
            LayoutMessage(
                R.string.core_lang_repair_unknown_key_deleted,
                args = listOf(LayoutLayer.LETTERS.key, "teleport"),
            ) in notes,
        )
        assertTrue(
            imported.layout.layer(LayoutLayer.LETTERS)!!.rows.flatten()
                .none { it.action is KeyAction.Unknown },
        )
    }

    @Test
    fun `the file name is derived from the layout name and stays a safe path`() {
        assertEquals("Mine.wmlayout.json", LayoutFile.fileName(usable()))
        assertEquals(
            "a name with slashes must not become a path",
            "etcpasswd.wmlayout.json",
            LayoutFile.fileName(usable().copy(name = "../etc/passwd")),
        )
        assertEquals("layout.wmlayout.json", LayoutFile.fileName(usable().copy(name = "   ")))
    }

    @Test
    fun `several layouts go in one file and come back with their ids`() {
        val a = usable()
        val b = usable().copy(id = "custom_2", name = "Other")
        val text = LayoutFile.encodeBundle(listOf(a, b), 12, "1.2")
        val back = LayoutFile.decodeBundle(text)!!
        assertEquals(listOf(a.id, "custom_2"), back.map { it.layout.id })
        assertEquals(listOf("Mine", "Other"), back.map { it.layout.name })
        // Neither reader takes the other's file.
        assertNull(LayoutFile.decode(text))
        assertNull(LayoutFile.decodeBundle(encode(a)))
        assertTrue(LayoutFile.bundleFileName(0L).endsWith(".wmlayouts.json"))
    }

    @Test
    fun `an export names the time after the layout and is indented`() {
        val name = LayoutFile.fileName(usable(), stampMs = 0L)
        assertTrue(name, Regex("Mine-19(69|70)\\d{4}-\\d{4}\\.wmlayout\\.json").matches(name))
        val text = encode(usable())
        assertTrue(text.contains("\n  \"format\": \"wmkeyboard-layout\""))
        assertNotNull(LayoutFile.decode(text))
    }

    @Test
    fun `the import picker accepts the types providers actually report`() {
        assertTrue("application/json" in LayoutFile.IMPORT_MIME_TYPES)
        assertTrue(
            "providers routinely mistype a .json file",
            "application/octet-stream" in LayoutFile.IMPORT_MIME_TYPES,
        )
    }

    @Test
    fun `the fn template is four rows so enabling it never grows the keyboard`() {
        assertEquals(4, BuiltInLayouts.FN_DEFAULT.rows.size)
        assertTrue(
            "and it has a way back to the letters",
            BuiltInLayouts.FN_DEFAULT.rows.flatten().any { it.action == KeyAction.Letters },
        )
        assertTrue(
            "its function keys send real key codes",
            BuiltInLayouts.FN_DEFAULT.rows.flatten().any { it.action is KeyAction.SendKey },
        )
    }

    @Test
    fun `a layout with an fn layer still validates clean`() {
        val withFn = usable().let {
            it.copy(layers = it.layers + (LayoutLayer.FN.key to BuiltInLayouts.FN_DEFAULT))
        }
        assertFalse(validateLayout(withFn).any { it.severity == LayoutSeverity.BLOCKING })
    }
}
