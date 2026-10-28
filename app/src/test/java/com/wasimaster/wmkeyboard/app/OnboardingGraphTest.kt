package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGraphTest {

    private fun pages(
        persona: OnboardingSettings = OnboardingSettings(),
        enabledTools: Collection<ToolbarTool> = emptyList(),
        enabledLanguageCount: Int = 2,
        replay: Boolean = false,
        imeReady: Boolean = false,
    ) = onboardingPages(persona, enabledTools, enabledLanguageCount, replay, imeReady)

    @Test
    fun `fresh run with unanswered quiz takes the short path`() {
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.PERSONA),
            pages(enabledLanguageCount = 1),
        )
    }

    @Test
    fun `a second enabled language brings the languages page back unasked`() {
        assertTrue(OnboardingPage.LANGUAGES in pages(enabledLanguageCount = 2))
    }

    @Test
    fun `answering only ever adds pages`() {
        val unanswered = pages(enabledLanguageCount = 1)
        for (depth in PersonaDepth.entries) {
            val answered = pages(
                persona = OnboardingSettings(personaDepth = depth),
                enabledLanguageCount = 1,
            )
            assertTrue(
                "$depth dropped a page the unanswered quiz shows",
                answered.containsAll(unanswered),
            )
        }
        assertTrue(
            pages(
                persona = OnboardingSettings(personaDepth = PersonaDepth.BALANCED),
                enabledLanguageCount = 1,
            ).size > unanswered.size,
        )
        // The language answer is the other half of the same rule.
        assertTrue(
            pages(
                persona = OnboardingSettings(personaLanguages = PersonaLanguages.MANY),
                enabledLanguageCount = 1,
            ).containsAll(unanswered + OnboardingPage.LANGUAGES),
        )
    }

    @Test
    fun `minimal persona skips the fine-tuning pages`() {
        val result = pages(
            persona = OnboardingSettings(personaDepth = PersonaDepth.MINIMAL),
            enabledLanguageCount = 1,
        )
        assertEquals(listOf(OnboardingPage.WELCOME, OnboardingPage.PERSONA), result)
    }

    @Test
    fun `balanced persona gets feedback, gestures and the typing test only`() {
        val result = pages(
            persona = OnboardingSettings(personaDepth = PersonaDepth.BALANCED),
            enabledLanguageCount = 1,
        )
        assertEquals(
            listOf(
                OnboardingPage.WELCOME, OnboardingPage.PERSONA, OnboardingPage.FEEDBACK,
                OnboardingPage.GESTURES, OnboardingPage.TRY,
            ),
            result,
        )
    }

    @Test
    fun `power persona gets the theme, the tour and the tools page`() {
        val result = pages(persona = OnboardingSettings(personaDepth = PersonaDepth.POWER))
        assertTrue(OnboardingPage.LOOK in result)
        assertTrue(OnboardingPage.DISCOVER in result)
        assertTrue(OnboardingPage.TOOLS in result)
        assertTrue(OnboardingPage.FEEDBACK in result)
        assertTrue(OnboardingPage.TRY in result)
    }

    @Test
    fun `tool setup needs power and a tool with choices`() {
        val power = OnboardingSettings(personaDepth = PersonaDepth.POWER)
        assertTrue(
            OnboardingPage.TOOL_SETUP in
                pages(persona = power, enabledTools = listOf(ToolbarTool.WEATHER)),
        )
        assertFalse(
            OnboardingPage.TOOL_SETUP in
                pages(persona = power, enabledTools = listOf(ToolbarTool.EMOJI)),
        )
        // Balanced users with the calendar tool on still skip it: the page is
        // power-user territory now.
        assertFalse(
            OnboardingPage.TOOL_SETUP in
                pages(
                    persona = OnboardingSettings(personaDepth = PersonaDepth.BALANCED),
                    enabledTools = listOf(ToolbarTool.CALENDAR),
                ),
        )
    }

    @Test
    fun `single-language persona hides the languages page until a second language exists`() {
        val one = OnboardingSettings(personaLanguages = PersonaLanguages.ONE)
        assertFalse(OnboardingPage.LANGUAGES in pages(persona = one, enabledLanguageCount = 1))
        assertTrue(OnboardingPage.LANGUAGES in pages(persona = one, enabledLanguageCount = 2))
    }

    @Test
    fun `replay hides welcome only while the keyboard is ready`() {
        assertFalse(OnboardingPage.WELCOME in pages(replay = true, imeReady = true))
        assertTrue(OnboardingPage.WELCOME in pages(replay = true, imeReady = false))
        assertTrue(OnboardingPage.WELCOME in pages(replay = false, imeReady = true))
    }

    @Test
    fun `a restored backup leaves only the welcome page`() {
        val power = OnboardingSettings(personaDepth = PersonaDepth.POWER, personaLanguages = PersonaLanguages.MANY)
        val restored = onboardingPages(
            power, ToolbarTool.entries, 3, replay = false, imeReady = false, restored = true,
        )
        assertEquals(listOf(OnboardingPage.WELCOME), restored)
    }

    @Test
    fun `resolvePageIndex finds the current page`() {
        val list = pages()
        assertEquals(2, resolvePageIndex(list, OnboardingPage.LANGUAGES))
    }

    @Test
    fun `resolvePageIndex falls back to the nearest earlier survivor`() {
        val list = listOf(
            OnboardingPage.WELCOME, OnboardingPage.PERSONA,
            OnboardingPage.LOOK, OnboardingPage.TRY,
        )
        // GESTURES vanished; LOOK is the nearest earlier page still present.
        assertEquals(2, resolvePageIndex(list, OnboardingPage.GESTURES))
        // Nothing earlier survives: land on the first page.
        val tail = listOf(OnboardingPage.LOOK, OnboardingPage.TRY)
        assertEquals(0, resolvePageIndex(tail, OnboardingPage.WELCOME))
    }

    @Test
    fun `every page has an accent`() {
        for (page in OnboardingPage.entries) {
            assertTrue("$page has no accent", OnboardingPageAccents.containsKey(page))
        }
    }
}
