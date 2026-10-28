package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikipedia client for the encyclopedia tool: search, page summaries, a
 * page's outgoing links and the full plain-text article, all via the free
 * MediaWiki APIs (no key). [lang] is the wiki subdomain (en, bn, de …).
 */
object WikipediaClient {

    data class SearchResult(val title: String, val description: String)

    data class Summary(
        val title: String,
        val description: String,
        val extract: String,
        val url: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The wiki for [lang]: `<lang>.wikipedia.org`, or the MediaWiki set for `ServiceEndpoint.WIKIPEDIA`. */
    private fun apiBase(lang: String) = ServiceEndpoints.wikipedia(lang)

    /** Canonical article URL for inserting/sharing. */
    fun articleUrl(title: String, lang: String): String =
        "${apiBase(lang)}/wiki/${ToolHttp.encode(title.replace(' ', '_'))}"

    fun search(query: String, lang: String, limit: Int = 12): List<SearchResult> {
        val url = "${apiBase(lang)}/w/api.php?action=query&list=search&format=json" +
            "&srlimit=$limit&srprop=snippet&srsearch=${ToolHttp.encode(query)}"
        return parseSearch(ToolHttp.get(url, source = NetSource.WIKIPEDIA, route = "/w/api.php"))
    }

    fun parseSearch(body: String): List<SearchResult> {
        val hits = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject
            ?.get("search")?.jsonArray ?: return emptyList()
        return hits.mapNotNull { hit ->
            val obj = hit.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SearchResult(title, stripHtml(obj["snippet"]?.jsonPrimitive?.content.orEmpty()))
        }
    }

    fun summary(title: String, lang: String): Summary {
        val url = "${apiBase(lang)}/api/rest_v1/page/summary/" +
            ToolHttp.encode(title.replace(' ', '_'))
        return parseSummary(
            // The title is a path segment here, so the route stops before it.
            ToolHttp.get(url, source = NetSource.WIKIPEDIA, route = "/api/rest_v1/page/summary"),
            fallbackUrl = articleUrl(title, lang))
    }

    fun parseSummary(body: String, fallbackUrl: String): Summary {
        val obj = json.parseToJsonElement(body).jsonObject
        return Summary(
            title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
            description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
            extract = obj["extract"]?.jsonPrimitive?.content.orEmpty(),
            url = obj["content_urls"]?.jsonObject?.get("desktop")?.jsonObject
                ?.get("page")?.jsonPrimitive?.content ?: fallbackUrl,
        )
    }

    /** Outgoing article links (namespace 0 only — no Talk:/File: noise). */
    fun links(title: String, lang: String, limit: Int = 200): List<String> {
        val url = "${apiBase(lang)}/w/api.php?action=query&prop=links&format=json" +
            "&plnamespace=0&pllimit=${limit.coerceAtMost(500)}" +
            "&titles=${ToolHttp.encode(title)}"
        return parseLinks(ToolHttp.get(url, source = NetSource.WIKIPEDIA, route = "/w/api.php"))
    }

    fun parseLinks(body: String): List<String> {
        val pages = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject
            ?.get("pages")?.jsonObject ?: return emptyList()
        return pages.values.flatMap { page ->
            page.jsonObject["links"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["title"]?.jsonPrimitive?.content
            }
        }
    }

    /** Full article as plain text (intro + sections, capped server-side). */
    fun fullText(title: String, lang: String): String {
        val url = "${apiBase(lang)}/w/api.php?action=query&prop=extracts&format=json" +
            "&explaintext=1&redirects=1&titles=${ToolHttp.encode(title)}"
        return parseFullText(ToolHttp.get(url, source = NetSource.WIKIPEDIA, route = "/w/api.php"))
    }

    fun parseFullText(body: String): String {
        val pages = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject
            ?.get("pages")?.jsonObject ?: return ""
        return pages.values.firstNotNullOfOrNull {
            it.jsonObject["extract"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        }.orEmpty().let(::foldMath)
    }

    /**
     * Formulas folded back into the sentence they belong to, as `\(TeX\)`
     * the panel typesets (#937).
     *
     * The plain-text extract flattens each formula's MathML one token per
     * indented line, then prints its TeX source as well, `{\displaystyle …}`:
     * a set of three letters took a screen. The TeX is the exact formula, so
     * it is what is kept, inline with the text around it; the token lines go.
     * `\(` rather than `$`, so a price in an article is never read as maths.
     */
    internal fun foldMath(text: String): String {
        if (!text.contains(DISPLAY_STYLE)) return text
        val lines = text.split('\n')
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (!line.trimStart().startsWith(DISPLAY_STYLE)) {
                out += line
                continue
            }
            var from = out.size
            while (from > 0 && (out[from - 1].isBlank() || out[from - 1].startsWith(' '))) from--
            out.subList(from, out.size).clear()
            val tex = line.trim().removePrefix(DISPLAY_STYLE).removeSuffix("}").trim()
            out += "$MATH_MARK$tex$MATH_MARK"
        }
        return out.joinToString("\n")
            // Running on into the same sentence: a mark hugs the formula, a
            // lower-case word follows it after a space; anything else is a
            // new paragraph.
            .replace(Regex("\\s*$MATH_MARK(.*?)$MATH_MARK\\s*(?=[,.;:)])")) { " \\(${it.groupValues[1]}\\)" }
            .replace(Regex("\\s*$MATH_MARK(.*?)$MATH_MARK\\s*(?=\\p{Ll})")) { " \\(${it.groupValues[1]}\\) " }
            .replace(Regex("\\s*$MATH_MARK(.*?)$MATH_MARK\\s*")) { " \\(${it.groupValues[1]}\\)\n\n" }
            .trim()
    }

    /** Whether [text] holds a formula [foldMath] kept. */
    fun hasMath(text: String): Boolean = "\\(" in text

    /** [text] with each kept formula written out in Unicode, for a text field: `\(x^2\)` is `x²`. */
    fun plainMath(text: String): String =
        if (!hasMath(text)) text else INLINE_MATH.replace(text) { LatexMath.plain(it.groupValues[1]) }

    private val INLINE_MATH = Regex("\\\\\\((.*?)\\\\\\)")
    private const val DISPLAY_STYLE = "{\\displaystyle"
    private const val MATH_MARK = '\u0001'

    /** Search snippets arrive with `<span class="searchmatch">` markup. */
    internal fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
}
