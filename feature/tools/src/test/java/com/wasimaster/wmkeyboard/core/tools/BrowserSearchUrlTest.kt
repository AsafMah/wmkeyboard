package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** The web search panel's "Search in the browser" (#915). */
class BrowserSearchUrlTest {

    @Test
    fun `with no search provider the query goes to DuckDuckGo, encoded`() {
        assertEquals(
            "https://duckduckgo.com/?q=kotlin+coroutines",
            SearchProviders.browserUrl(KeyboardSettings(), "  kotlin coroutines "),
        )
    }
}
