package com.wasimaster.wmkeyboard.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every settings row with a fixed name draws an icon.
 *
 * A row's glyph lives in [SettingsRowIcons], keyed by its title resource, and
 * a resource missing from the map draws no tile at all. Nothing fails when a
 * new row forgets its entry: the page just grows one bare row among tiled
 * ones. This test reads the sources, so it stays a plain JVM test.
 *
 * Which functions are rows is worked out, not listed. A row helper is any
 * function with a `@StringRes title: Int` parameter that looks the title up in
 * [SettingsRowIcons] (usually as its `icon` default), or hands `title` on to another row helper (a screen's
 * private `SourceToggle` over `ToggleSetting`, say). Every call to one of those
 * whose title is a plain `R.string.…` must name a resource the registry has,
 * unless the call passes its own `icon =`.
 *
 * Rows named by the user's things (a language, a pack, a word) pass a
 * `String` title and are not this test's business.
 */
class SettingsRowIconsTest {

    private val appDir = File("src/main/java/com/wasimaster/wmkeyboard/app")

    private val sources: Map<File, String> by lazy {
        assertTrue("app sources not found at ${appDir.absolutePath}", appDir.isDirectory)
        appDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.associateWith { it.readText() }
    }

    private val registered: Set<String> by lazy {
        val file = File(appDir, "SettingsRowIcons.kt")
        Regex("""put\(\w+\.string\.(\w+)\)""").findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    /** A function declaration: its name and the text of its parameters and body. */
    private class Declaration(val name: String, val params: String, val body: String)

    private val declarations: List<Declaration> by lazy {
        val head = Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(""")
        sources.values.flatMap { text ->
            head.findAll(text).mapNotNull { match ->
                val open = match.range.last
                val close = matching(text, open) ?: return@mapNotNull null
                Declaration(match.groupValues[1], text.substring(open + 1, close), bodyAfter(text, close + 1))
            }.toList()
        }
    }

    private val rowHelpers: Set<String> by lazy {
        val titled = declarations.filter { Regex("""@StringRes\s+title\s*:\s*Int\b""").containsMatchIn(it.params) }
        val helpers = titled.filter { "SettingsRowIcons[title]" in it.params + it.body }.map { it.name }.toMutableSet()
        // Forwarders: a titled function whose body calls a known helper with
        // its own `title`, positionally or by name. Run to a fixed point, since
        // a wrapper can sit over a wrapper.
        do {
            val before = helpers.size
            for (declaration in titled) {
                if (declaration.name in helpers) continue
                val forwards = calls(declaration.body, helpers).any { call -> titleOf(call.args) == "title" }
                if (forwards) helpers += declaration.name
            }
        } while (helpers.size != before)
        helpers
    }

    @Test
    fun `the row helpers are found`() {
        for (name in listOf("NavRow", "ToggleSetting", "SliderSetting", "ChoiceSetting", "ControlSetting", "ActionRow")) {
            assertTrue("$name was not recognised as a row helper; the scan is broken", name in rowHelpers)
        }
    }

    @Test
    fun `every resource-titled row has an icon`() {
        val missing = mutableListOf<String>()
        var checked = 0
        for ((file, text) in sources) {
            for (call in calls(text, rowHelpers)) {
                if (call.args.any { Regex("""^icon\s*=""").containsMatchIn(it) }) continue
                val title = titleOf(call.args) ?: continue
                val resource = Regex("""\w+\.string\.(\w+)""").matchEntire(title)?.groupValues?.get(1) ?: continue
                checked++
                if (resource !in registered) missing += "${file.name}:${call.line} ${call.name}($resource)"
            }
        }
        assertTrue("only $checked rows checked; the scan is broken", checked > 500)
        assertTrue(
            "These rows draw no icon. Give each resource an entry in SettingsRowIcons.kt:\n" +
                missing.sorted().joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun `no row hides a fixed resource behind the String overload`() {
        // `ToggleSetting(title = stringResource(R.string.x), …)` takes the
        // String overload, which never looks an icon up.
        val hidden = mutableListOf<String>()
        for ((file, text) in sources) {
            for (call in calls(text, rowHelpers)) {
                if (call.args.any { Regex("""^icon\s*=""").containsMatchIn(it) }) continue
                val title = titleOf(call.args) ?: continue
                val inner = Regex("""stringResource\(\s*(\w+\.string\.\w+)\s*\)""").matchEntire(title) ?: continue
                hidden += "${file.name}:${call.line} ${call.name}: pass ${inner.groupValues[1]} itself"
            }
        }
        assertTrue(
            "These rows pass stringResource(…) as a title and so get no icon:\n" + hidden.sorted().joinToString("\n"),
            hidden.isEmpty(),
        )
    }

    private class Call(val name: String, val line: Int, val args: List<String>)

    /** Calls to any of [names] in [text], declarations excluded. */
    private fun calls(text: String, names: Set<String>): List<Call> {
        if (names.isEmpty()) return emptyList()
        val pattern = Regex("""(?<![\w.])(${names.joinToString("|")})\s*(?:<[^>()]*>)?\s*\(""")
        return pattern.findAll(text).mapNotNull { match ->
            val lineStart = text.lastIndexOf('\n', match.range.first) + 1
            if (Regex("""\bfun\s+(?:<[^>]*>\s*)?$""").containsMatchIn(text.substring(lineStart, match.range.first))) {
                return@mapNotNull null
            }
            val open = match.range.last
            val close = matching(text, open) ?: return@mapNotNull null
            Call(match.groupValues[1], text.lineAt(match.range.first), splitArgs(text.substring(open + 1, close)))
        }.toList()
    }

    /** The title argument: `title = …`, else the first positional one. */
    private fun titleOf(args: List<String>): String? {
        args.firstNotNullOfOrNull { Regex("""^title\s*=\s*(.*)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(it) }
            ?.let { return it.groupValues[1].trim() }
        val first = args.firstOrNull() ?: return null
        return first.takeUnless { Regex("""^\w+\s*=[^=]""").containsMatchIn(it) }
    }

    /** Top-level comma-separated arguments, trimmed, string literals respected. */
    private fun splitArgs(inside: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < inside.length) {
            val c = inside[i]
            when {
                c == '"' -> {
                    val end = stringEnd(inside, i)
                    current.append(inside, i, end)
                    i = end
                    continue
                }
                c in "([{" -> depth++
                c in ")]}" -> depth--
                c == ',' && depth == 0 -> {
                    out += current.toString().trim()
                    current.clear()
                    i++
                    continue
                }
            }
            current.append(c)
            i++
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }

    /** The index of the bracket closing the one at [open], skipping string literals. */
    private fun matching(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    i = stringEnd(text, i)
                    continue
                }
                c in "([{" -> depth++
                c in ")]}" -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** One past the end of the string literal starting at [start]. */
    private fun stringEnd(text: String, start: Int): Int {
        if (text.startsWith("\"\"\"", start)) {
            val end = text.indexOf("\"\"\"", start + 3)
            return if (end < 0) text.length else end + 3
        }
        var i = start + 1
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i++
                c == '$' && text.getOrNull(i + 1) == '{' -> {
                    depth++
                    i++
                }
                c == '}' && depth > 0 -> depth--
                c == '"' && depth == 0 -> return i + 1
            }
            i++
        }
        return text.length
    }

    /** The body after a parameter list ending at [from]: a `{ … }` block or an `= …(…)` expression. */
    private fun bodyAfter(text: String, from: Int): String {
        var i = from
        while (i < text.length && text[i] != '{' && text[i] != '=') {
            if (text[i] == '\n' && text.getOrNull(i + 1)?.let { it != ' ' && it != ')' && it != ':' } == true) return ""
            i++
        }
        if (i >= text.length) return ""
        if (text[i] == '{') return matching(text, i)?.let { text.substring(i, it + 1) }.orEmpty()
        val paren = text.indexOf('(', i)
        if (paren < 0) return ""
        return matching(text, paren)?.let { text.substring(i, it + 1) }.orEmpty()
    }

    private fun String.lineAt(index: Int): Int = 1 + (0 until index).count { this[it] == '\n' }
}
