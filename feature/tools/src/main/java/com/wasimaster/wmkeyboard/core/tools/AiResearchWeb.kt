package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings

/**
 * Deep research's searches and page reads ([AiResearch]), through the same
 * search list and page reader the model's own `web_search` and `web_fetch`
 * use: the user's SearXNG, Tavily or Brave in their order, and a page fetched
 * as text with no scripts run. Logged as AI tool use, like those two.
 *
 * Blocking; [AiResearch] runs on the chat's IO thread.
 */
class AiResearchWeb(private val settings: KeyboardSettings) : AiResearch.Web {

    override fun search(query: String): List<AiResearch.Hit> =
        SearchProviders.web(
            settings,
            query,
            RESULTS,
            settings.webSearch.safe,
            // The report is written from pages, not from a provider's summary.
            answer = false,
            source = NetSource.AI_TOOLS,
        ).results.map { AiResearch.Hit(it.title.trim(), it.url, it.snippet) }

    override fun fetch(url: String, maxChars: Int): AiResearch.Page =
        WebFetchClient.fetch(url, maxChars).let { AiResearch.Page(it.title, it.text) }

    companion object {
        /** Results per search: the ones a page is chosen from, and the snippets the rest are known by. */
        private const val RESULTS = 6
    }
}
