package com.wasimaster.wmkeyboard.core.tools

/** One web hit from the search tool's backend. */
data class WebResult(
    val title: String,
    val snippet: String,
    val url: String,
    /** Short host shown under the title ("en.wikipedia.org"). */
    val displayUrl: String,
    /**
     * The engines a meta search found it with (#932): a SearXNG instance's
     * `google`, `duckduckgo`. Empty from a backend that is the engine itself.
     */
    val engines: List<String> = emptyList(),
    /** A small picture beside the result (#934): a video's still on a SearXNG Videos search. */
    val thumbnail: String? = null,
)

/**
 * One page of web results, and the short answer the backend wrote for the
 * query when it writes one (#470): Tavily's `answer`, a SearXNG instance's
 * `answers`. Brave gives none on the plain search API.
 */
data class WebSearchPage(
    val results: List<WebResult>,
    val answer: String? = null,
    /** Pictures for the query, shown under the answer (#879); Tavily's, which come free with it. */
    val images: List<ImageResult> = emptyList(),
)

/**
 * A further page of a search already on screen: the panel's More results
 * (#879). [page] is 1 for the first page after the one shown. [excludeDomains]
 * are the sites already shown, for Tavily, which has no paging of its own and
 * instead takes sites to leave out; Brave and SearXNG page by number.
 */
data class SearchMore(
    val page: Int,
    val excludeDomains: List<String> = emptyList(),
)

/** One image hit from the image search tool. */
data class ImageResult(
    val title: String,
    /** Small thumbnail rendered in the picker grid. */
    val thumbUrl: String,
    /** Full image downloaded and inserted on tap. */
    val imageUrl: String,
    val mime: String,
    /** Page the image came from, for the link-insert action. */
    val contextUrl: String,
)
