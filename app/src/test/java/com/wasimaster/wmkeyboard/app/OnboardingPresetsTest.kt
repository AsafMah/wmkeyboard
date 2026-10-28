package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.DefaultToolOrder
import com.wasimaster.wmkeyboard.core.settings.EverythingTools
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MinimalTools
import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import com.wasimaster.wmkeyboard.core.settings.PowerTools
import com.wasimaster.wmkeyboard.core.settings.RecommendedTools
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.core.settings.ToolTopUps
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPresetsTest {

    @Test
    fun `many languages puts both swipes on language switching`() {
        assertEquals(
            listOf(
                PresetWrite.SpaceSwipes(SpaceSwipeAction.LANGUAGE, SpaceSwipeAction.LANGUAGE),
                PresetWrite.GlobeAsEmoji(false),
            ),
            presetsFor(PersonaLanguages.MANY),
        )
    }

    @Test
    fun `one language frees the swipes and the globe key`() {
        assertEquals(
            listOf(
                PresetWrite.SpaceSwipes(SpaceSwipeAction.CURSOR, SpaceSwipeAction.CURSOR),
                PresetWrite.GlobeAsEmoji(true),
            ),
            presetsFor(PersonaLanguages.ONE),
        )
    }

    @Test
    fun `depth answers land their tool sets`() {
        assertEquals(MinimalTools, tools(PersonaDepth.MINIMAL))
        assertEquals(RecommendedTools, tools(PersonaDepth.BALANCED))
        assertEquals(PowerTools, tools(PersonaDepth.POWER))
        assertNull(tools(PersonaDepth.UNSET))
    }

    @Test
    fun `a set short of an unsupported tool is topped back up`() {
        val short = tools(PersonaDepth.BALANCED) { it != ToolbarTool.HANDWRITING }.orEmpty()
        assertTrue(ToolbarTool.HANDWRITING !in short)
        assertTrue(short.containsAll(ToolTopUps))
        // Same on a device with no Google services, even where everything is
        // otherwise supported.
        val gmsFree = starterTools(
            OnboardingSettings(personaDepth = PersonaDepth.BALANCED),
            playServices = false,
        ) { true }.orEmpty()
        assertTrue(gmsFree.containsAll(ToolTopUps))
    }

    @Test
    fun `the toolbox order starts with the starter sets`() {
        assertEquals(PowerTools.toList(), DefaultToolOrder.take(PowerTools.size))
        assertEquals(MinimalTools.toList(), DefaultToolOrder.take(MinimalTools.size))
        // Nothing is dropped by the rewrite: every tool still has a place.
        assertEquals(ToolbarTool.entries.toSet(), DefaultToolOrder.toSet())
        assertEquals(ToolbarTool.entries.size, DefaultToolOrder.size)
    }

    private fun tools(
        depth: PersonaDepth,
        isSupported: (ToolbarTool) -> Boolean = { true },
    ): Set<ToolbarTool>? = starterTools(
        OnboardingSettings(personaDepth = depth),
        playServices = true,
        isSupported = isSupported,
    )

    @Test
    fun `an unset language answer writes nothing`() {
        assertTrue(presetsFor(PersonaLanguages.UNSET).isEmpty())
    }

    @Test
    fun `recommended spacebar choice follows the language answer`() {
        assertEquals(
            SpacebarChoice.LANGUAGE_ONLY,
            recommendedSpacebarChoice(
                OnboardingSettings(personaLanguages = PersonaLanguages.MANY),
            ),
        )
        assertEquals(
            SpacebarChoice.CURSOR_ONLY,
            recommendedSpacebarChoice(
                OnboardingSettings(personaLanguages = PersonaLanguages.ONE),
            ),
        )
        assertEquals(
            SpacebarChoice.LANGUAGE_THEN_CURSOR,
            recommendedSpacebarChoice(OnboardingSettings()),
        )
    }

    @Test
    fun `finishing over the untouched all-on default lands the persona's set`() {
        val allOn = KeyboardSettings(enabledTools = EverythingTools)
        assertEquals(RecommendedTools, afterFinish(allOn))
        assertEquals(
            MinimalTools,
            afterFinish(
                allOn.copy(onboarding = OnboardingSettings(personaDepth = PersonaDepth.MINIMAL)),
            ),
        )
    }

    @Test
    fun `a touched tool selection is never overwritten on finish`() {
        assertNull(afterFinish(KeyboardSettings(enabledTools = listOf(ToolbarTool.EMOJI))))
    }

    private fun afterFinish(settings: KeyboardSettings): Set<ToolbarTool>? =
        toolsAfterFinish(settings, playServices = true) { true }
}
