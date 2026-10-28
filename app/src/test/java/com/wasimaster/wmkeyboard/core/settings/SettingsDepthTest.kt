package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** [KeyboardSettings.settingsDepthResolved]: the depth the settings app shows at. */
class SettingsDepthTest {

    private fun settings(depth: SettingsDepth, persona: PersonaDepth) = KeyboardSettings(
        appUi = AppUiSettings(settingsDepth = depth),
        onboarding = OnboardingSettings(personaDepth = persona),
    )

    @Test
    fun `auto follows the onboarding answer`() {
        assertEquals(
            SettingsDepth.SIMPLE,
            settings(SettingsDepth.AUTO, PersonaDepth.MINIMAL).settingsDepthResolved(),
        )
        assertEquals(
            SettingsDepth.STANDARD,
            settings(SettingsDepth.AUTO, PersonaDepth.BALANCED).settingsDepthResolved(),
        )
        assertEquals(
            SettingsDepth.EVERYTHING,
            settings(SettingsDepth.AUTO, PersonaDepth.POWER).settingsDepthResolved(),
        )
    }

    @Test
    fun `an install that never saw the quiz is standard`() {
        assertEquals(
            SettingsDepth.STANDARD,
            settings(SettingsDepth.AUTO, PersonaDepth.UNSET).settingsDepthResolved(),
        )
    }

    @Test
    fun `an explicit pick wins over the answer`() {
        assertEquals(
            SettingsDepth.SIMPLE,
            settings(SettingsDepth.SIMPLE, PersonaDepth.POWER).settingsDepthResolved(),
        )
        assertEquals(
            SettingsDepth.EVERYTHING,
            settings(SettingsDepth.EVERYTHING, PersonaDepth.MINIMAL).settingsDepthResolved(),
        )
    }

    @Test
    fun `the default is auto`() {
        assertEquals(SettingsDepth.AUTO, SettingsDefaults.appUi.settingsDepth)
    }
}
