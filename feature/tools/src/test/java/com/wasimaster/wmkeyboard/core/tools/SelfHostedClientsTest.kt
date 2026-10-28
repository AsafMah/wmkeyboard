package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two free-software clients that live in this module: SearXNG and a
 * MediaWiki. LibreTranslate sits in :core:tools and is tested beside it, since
 * `internal` does not cross a module boundary.
 *
 * Everything here is URL building and parsing, which is where these break. The
 * URLs matter because all three endpoints are typed by a person, out of a
 * README, and a field that only accepts one of the four reasonable spellings
 * fails as a 404 that reads like the server being down.
 */
class SelfHostedClientsTest {

    // ---- SearXNG -----------------------------------------------------------

    @Test
    fun `searx accepts every reasonable spelling of an instance`() {
        val expected = "https://searx.example.org/search"
        assertEquals(expected, SearxClient.searchBase("searx.example.org"))
        assertEquals(expected, SearxClient.searchBase("https://searx.example.org"))
        assertEquals(expected, SearxClient.searchBase("https://searx.example.org/"))
        assertEquals(expected, SearxClient.searchBase("https://searx.example.org/search"))
    }

    @Test
    fun `searx asks for json and the right safesearch level`() {
        val web = SearxClient.searchUrl("searx.example.org", "kotlin flows", "general", safe = true)
        assertTrue(web.contains("format=json"))
        assertTrue(web.contains("categories=general"))
        assertTrue(web.contains("safesearch=1"))
        assertTrue(web.contains("q=kotlin+flows") || web.contains("q=kotlin%20flows"))

        // Images take the strict end, matching the Brave path.
        val images = SearxClient.searchUrl("searx.example.org", "cat", "images", safe = true)
        assertTrue(images.contains("safesearch=2"))

        val unsafe = SearxClient.searchUrl("searx.example.org", "cat", "images", safe = false)
        assertTrue(unsafe.contains("safesearch=0"))
    }

    @Test
    fun `searx passes the limiter's sec-fetch check for a chrome agent`() {
        // SearXNG's http_sec_fetch sends a Chrome 80+ agent without these back
        // to the home page, which arrives as a 200 HTML page (#592).
        val headers = SearxClient.requestHeaders()
        assertEquals("navigate", headers["Sec-Fetch-Mode"])
        assertEquals("none", headers["Sec-Fetch-Site"])
        assertEquals("document", headers["Sec-Fetch-Dest"])
        assertTrue(headers.getValue("Accept").contains("text/html"))
    }

    @Test
    fun `searx tells a web page from json`() {
        assertTrue(SearxClient.isWebPage("<!DOCTYPE html>\n<html class=\"no-js\">"))
        assertTrue(SearxClient.isWebPage("\n  <html>"))
        assertEquals(false, SearxClient.isWebPage("""{"results":[]}"""))
    }

    @Test
    fun `searx reads web results and shortens the host`() {
        val body = """
            {"results":[
              {"url":"https://en.wikipedia.org/wiki/Kotlin","title":"Kotlin","content":"A language."},
              {"url":"https://example.com/x","title":"X","content":""}
            ]}
        """.trimIndent()
        val results = SearxClient.parseWeb(body)
        assertEquals(2, results.size)
        assertEquals("Kotlin", results[0].title)
        assertEquals("A language.", results[0].snippet)
        assertEquals("en.wikipedia.org", results[0].displayUrl)
    }

    @Test
    fun `searx names the engines that found each result`() {
        val body = """
            {"results":[
              {"url":"https://a.example/","title":"A","engine":"google","engines":["google","bing"]},
              {"url":"https://b.example/","title":"B","engine":"duckduckgo"},
              {"url":"https://c.example/","title":"C"}
            ]}
        """.trimIndent()
        val results = SearxClient.parseWeb(body)
        assertEquals(listOf("google", "bing"), results[0].engines)
        assertEquals(listOf("duckduckgo"), results[1].engines)
        assertEquals(emptyList<String>(), results[2].engines)
    }

    @Test
    fun `searx drops a web result with no url or title`() {
        val body = """
            {"results":[{"content":"orphan"},{"url":"https://a.example/","title":"Kept"}]}
        """.trimIndent()
        val results = SearxClient.parseWeb(body)
        assertEquals(1, results.size)
        assertEquals("Kept", results[0].title)
    }

    @Test
    fun `searx falls back through the thumbnail names engines disagree on`() {
        val body = """
            {"results":[
              {"img_src":"https://a.example/1.png","thumbnail_src":"https://a.example/t1.png",
               "title":"one","url":"https://a.example/p1","img_format":"png"},
              {"img_src":"https://a.example/2.jpg","thumbnail":"https://a.example/t2.jpg",
               "title":"two","url":"https://a.example/p2"},
              {"img_src":"https://a.example/3.gif","title":"three","url":"https://a.example/p3"}
            ]}
        """.trimIndent()
        val results = SearxClient.parseImages(body)
        assertEquals(3, results.size)
        assertEquals("https://a.example/t1.png", results[0].thumbUrl)
        assertEquals("image/png", results[0].mime)
        assertEquals("https://a.example/t2.jpg", results[1].thumbUrl)
        // Neither name present: the full image stands in for its own thumbnail.
        assertEquals("https://a.example/3.gif", results[2].thumbUrl)
        assertEquals("image/gif", results[2].mime)
    }

    @Test
    fun `searx drops an image result carrying only a thumbnail`() {
        // Nothing to insert, so showing it would be a tile that cannot be used.
        val body = """{"results":[{"thumbnail_src":"https://a.example/t.png","title":"t"}]}"""
        assertTrue(SearxClient.parseImages(body).isEmpty())
    }

    @Test
    fun `searx reads a mime from the format field before the extension`() {
        assertEquals("image/png", SearxClient.mimeOf("PNG", "https://a.example/x"))
        assertEquals("image/jpeg", SearxClient.mimeOf("Image/jpeg", "https://a.example/x"))
        // Unknown format: the extension decides.
        assertEquals("image/gif", SearxClient.mimeOf(null, "https://a.example/x.gif"))
    }

    @Test
    fun `searx finding nothing is an empty list rather than a failure`() {
        assertTrue(SearxClient.parseWeb("""{"results":[]}""").isEmpty())
        assertTrue(SearxClient.parseImages("""{"query":"x"}""").isEmpty())
    }

    @Test
    fun `searx searches in the category asked for`() {
        val news = SearxClient.searchUrl("searx.example.org", "rust", "news", safe = true)
        assertTrue(news.contains("&categories=news&"))
        // Categories with a space go encoded, as SearXNG splits the list on commas only.
        val social = SearxClient.searchUrl("searx.example.org", "rust", "social media", safe = true, page = 2)
        assertTrue(social.contains("&categories=social+media&"))
        assertTrue(social.contains("&pageno=3"))
        // Only images take the strict end of SafeSearch.
        assertTrue(SearxClient.searchUrl("searx.example.org", "x", "videos", safe = true).contains("safesearch=1"))
    }

    @Test
    fun `searx finds the config beside the search endpoint`() {
        val expected = "https://searx.example.org/config"
        assertEquals(expected, SearxClient.configUrl("searx.example.org"))
        assertEquals(expected, SearxClient.configUrl("https://searx.example.org/"))
        assertEquals(expected, SearxClient.configUrl("https://searx.example.org/search"))
        assertEquals("http://10.0.0.2:8888/searxng/config", SearxClient.configUrl("http://10.0.0.2:8888/searxng"))
    }

    @Test
    fun `searx lists the categories an enabled engine answers for, in the config's order`() {
        val body = """
            {"categories":["general","images","videos","news","science","music"],
             "engines":[
               {"name":"google","categories":["general"],"enabled":true},
               {"name":"bing images","categories":["images"],"enabled":true},
               {"name":"youtube","categories":["videos","music"],"enabled":true},
               {"name":"arxiv","categories":["science"],"enabled":false}
             ]}
        """.trimIndent()
        assertEquals(listOf("general", "images", "videos", "music"), SearxClient.parseCategories(body))
    }

    @Test
    fun `searx takes a config without engines at its word`() {
        val body = """{"categories":["general","news","news"," ","social media"]}"""
        assertEquals(listOf("general", "news", "social media"), SearxClient.parseCategories(body))
        assertTrue(SearxClient.parseCategories("""{"engines":[]}""").isEmpty())
    }

    @Test
    fun `searx names categories as its own pages do`() {
        assertEquals("IT", SearxClient.categoryLabel("it"))
        assertEquals("Q&A", SearxClient.categoryLabel("q&a"))
        assertEquals("Social media", SearxClient.categoryLabel("social media"))
        assertEquals("Videos", SearxClient.categoryLabel("videos"))
    }

    @Test
    fun `searx keeps a video's thumbnail`() {
        val body = """
            {"results":[
              {"url":"https://v.example/1","title":"One","thumbnail":"https://i.example/1.jpg"},
              {"url":"https://v.example/2","title":"Two","thumbnail_src":"https://i.example/2.jpg"},
              {"url":"https://v.example/3","title":"Three","thumbnail":""}
            ]}
        """.trimIndent()
        val results = SearxClient.parseWeb(body)
        assertEquals("https://i.example/1.jpg", results[0].thumbnail)
        assertEquals("https://i.example/2.jpg", results[1].thumbnail)
        assertNull(results[2].thumbnail)
    }

    // ---- Wikimedia Commons -------------------------------------------------

    @Test
    fun `commons defaults to wikimedia and accepts another wiki`() {
        assertEquals(CommonsClient.DEFAULT_API, CommonsClient.apiUrl(""))
        assertEquals(CommonsClient.DEFAULT_API, CommonsClient.apiUrl("   "))
        assertEquals("https://wiki.example.org/w/api.php", CommonsClient.apiUrl("wiki.example.org"))
        assertEquals("https://wiki.example.org/w/api.php", CommonsClient.apiUrl("https://wiki.example.org/"))
        // A wiki that put api.php somewhere else is taken exactly as given.
        assertEquals("https://wiki.example.org/api.php", CommonsClient.apiUrl("https://wiki.example.org/api.php"))
    }

    @Test
    fun `commons restricts the search to one file type`() {
        assertTrue(CommonsClient.gifTerms("cat").contains("filemime:image/gif"))
        assertTrue(CommonsClient.photoTerms("sunset").contains("filemime:image/jpeg"))
        // A blank photo query browses something rather than everything.
        assertTrue(CommonsClient.photoTerms("").contains("Featured_pictures"))
    }

    @Test
    fun `commons asks the file namespace, without which nothing parses`() {
        val url = CommonsClient.searchUrl(CommonsClient.DEFAULT_API, "cat", 10, 20, 320)
        assertTrue(url.contains("gsrnamespace=6"))
        assertTrue(url.contains("formatversion=2"))
        assertTrue(url.contains("gsrlimit=10"))
        assertTrue(url.contains("gsroffset=20"))
        assertTrue(url.contains("iiurlwidth=320"))
    }

    @Test
    fun `commons rewrites a thumbnail to another width`() {
        val thumb = "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/A.jpg/400px-A.jpg"
        assertEquals(
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/A.jpg/1280px-A.jpg",
            CommonsClient.thumbAt(thumb, 1280),
        )
        // Not a thumbnail URL: no substitution to make.
        assertNull(CommonsClient.thumbAt("https://upload.wikimedia.org/wikipedia/commons/b/b3/A.jpg", 1280))
    }

    @Test
    fun `commons never offers a variant wider than the original`() {
        val thumb = "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/A.jpg/400px-A.jpg"
        val original = "https://upload.wikimedia.org/wikipedia/commons/b/b3/A.jpg"
        val variants = CommonsClient.variantsOf(thumb, original, width = 1500, height = 1000)
        assertTrue(variants.all { it.width <= 1500 })
        // MediaWiki will not upscale, so 1920 and 2560 must be absent.
        assertTrue(variants.none { it.width == 1920 || it.width == 2560 })
        // The original is always the last resort, and on its own host.
        assertEquals(original, variants.last().url)
        assertEquals(1500, variants.last().width)
    }

    @Test
    fun `commons parses a photo page with its credit`() {
        val body = """
            {"batchcomplete":true,"continue":{"gsroffset":2},"query":{"pages":[
              {"pageid":7,"index":1,"title":"File:Sunset.jpg","imageinfo":[{
                "width":6000,"height":4000,"mime":"image/jpeg",
                "thumburl":"https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Sunset.jpg/480px-Sunset.jpg",
                "url":"https://upload.wikimedia.org/wikipedia/commons/a/ab/Sunset.jpg",
                "descriptionurl":"https://commons.wikimedia.org/wiki/File:Sunset.jpg",
                "extmetadata":{
                  "Artist":{"value":"<a href=\"https://commons.wikimedia.org/wiki/User:Someone\">Someone</a>"},
                  "LicenseShortName":{"value":"CC BY-SA 4.0"},
                  "ImageDescription":{"value":"A <b>sunset</b> over water"}
                }}]}
            ]}}
        """.trimIndent()
        val items = CommonsClient.parsePhotos(body)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(PhotoSource.COMMONS, item.source)
        assertEquals("7", item.id)
        assertEquals(6000, item.width)
        assertEquals("Someone", item.photographer)
        assertEquals("https://commons.wikimedia.org/wiki/User:Someone", item.photographerUrl)
        assertEquals("A sunset over water", item.altText)
        assertEquals("https://commons.wikimedia.org/wiki/File:Sunset.jpg", item.pageUrl)
        assertTrue(CommonsClient.hasContinue(body))
    }

    @Test
    fun `commons sends the original for a gif, not the still thumbnail`() {
        // MediaWiki renders a scaled thumbnail of a large GIF as one frame, so
        // inserting the thumbnail would post a still image of an animation.
        val body = """
            {"query":{"pages":[
              {"pageid":9,"index":1,"title":"File:Cat funny gif.gif","imageinfo":[{
                "width":1200,"height":994,"mime":"image/gif",
                "thumburl":"https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/Cat.gif/320px-Cat.gif",
                "url":"https://upload.wikimedia.org/wikipedia/commons/c/c1/Cat.gif"}]}
            ]}}
        """.trimIndent()
        val gifs = CommonsClient.parseGifs(body)
        assertEquals(1, gifs.size)
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/c/c1/Cat.gif", gifs[0].fullUrl)
        assertEquals("image/gif", gifs[0].mime)
        assertEquals(GifSource.COMMONS, gifs[0].source)
        assertEquals("Cat funny gif", gifs[0].title)
    }

    @Test
    fun `commons keeps the relevance order the generator reported`() {
        // The array order is not the ranking; `index` is.
        val body = """
            {"query":{"pages":[
              {"pageid":2,"index":2,"title":"File:B.gif","imageinfo":[{"width":1,"height":1,
               "mime":"image/gif","url":"https://u.example/B.gif"}]},
              {"pageid":1,"index":1,"title":"File:A.gif","imageinfo":[{"width":1,"height":1,
               "mime":"image/gif","url":"https://u.example/A.gif"}]}
            ]}}
        """.trimIndent()
        val gifs = CommonsClient.parseGifs(body)
        assertEquals(listOf("A", "B"), gifs.map { it.title })
    }

    @Test
    fun `commons filters shape locally because the api cannot`() {
        val wide = photo(width = 3000, height = 1000)
        val tall = photo(width = 1000, height = 3000)
        assertTrue(CommonsClient.matchesOrientation(wide, PhotoOrientation.LANDSCAPE))
        assertTrue(!CommonsClient.matchesOrientation(wide, PhotoOrientation.PORTRAIT))
        assertTrue(CommonsClient.matchesOrientation(tall, PhotoOrientation.PORTRAIT))
        assertTrue(CommonsClient.matchesOrientation(photo(1000, 1000), PhotoOrientation.SQUARE))
        assertTrue(CommonsClient.matchesOrientation(wide, PhotoOrientation.ANY))
    }

    @Test
    fun `commons strips the markup its metadata arrives wrapped in`() {
        assertEquals("Someone", CommonsClient.stripHtml("""<a href="x">Someone</a>"""))
        assertEquals("A & B", CommonsClient.stripHtml("A &amp; B"))
        assertEquals("", CommonsClient.firstHref("plain text"))
    }

    @Test
    fun `commons finding nothing is an empty list`() {
        assertTrue(CommonsClient.parsePhotos("""{"batchcomplete":true}""").isEmpty())
        assertTrue(CommonsClient.parseGifs("""{"query":{}}""").isEmpty())
    }

    private fun photo(width: Int, height: Int) = PhotoItem(
        id = "1",
        source = PhotoSource.COMMONS,
        thumbUrl = "",
        fullUrl = "",
        pageUrl = "",
        photographer = "",
        photographerUrl = "",
        width = width,
        height = height,
        avgColor = "#000000",
        altText = "",
        resizable = false,
    )
}
