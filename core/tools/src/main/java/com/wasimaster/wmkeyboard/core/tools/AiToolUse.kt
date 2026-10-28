package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One tool the AI tool may let a model call (#470).
 *
 * [parameters] is a JSON Schema object, the shape every provider's function
 * calling takes — OpenAI, Anthropic, Gemini and Ollama all spell the wrapper
 * differently but all read the same schema inside it, so it is stored once
 * here and re-wrapped per provider in `AiClient`.
 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * One call a model asked for.
 *
 * [id] is the provider's own handle for the call, which the result has to
 * quote back. Older Gemini models send none and match results by tool name,
 * and the prompt fallback has no ids at all, so it is blank for those.
 *
 * [arguments] stays the raw JSON object text rather than a parsed map: a model
 * that writes a number where a string belongs, or an extra field nobody asked
 * for, should reach the tool that can complain about it rather than fail to
 * parse on the way.
 */
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    /** One named argument, or null when it is absent or is not a string. */
    fun argument(key: String): String? =
        runCatching {
            (Json.parseToJsonElement(arguments).jsonObject[key] as? JsonPrimitive)
                ?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    /**
     * One numeric argument. Read through [argument], so a model that quotes
     * its number ("12.5") is understood as well as one that does not, and a
     * thousands separator it wrote out of habit does not sink the call.
     */
    fun number(key: String): Double? =
        argument(key)?.replace(",", "")?.replace("_", "")?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** One yes/no argument; anything but a recognisable answer is null. */
    fun flag(key: String): Boolean? = when (argument(key)?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
}

/**
 * What running one produced, ready to hand back to the model.
 *
 * [content] is the model's to read. [sources] and [error] are the user's: the
 * pages the model was handed and, when the call did not work, why — in words
 * meant for a person rather than the instructions [content] carries to the
 * model. Together they make the [activity] the chat shows under an answer.
 */
data class AiToolResult(
    val call: AiToolCall,
    val content: String,
    val sources: List<AiToolSource> = emptyList(),
    /** Not empty means the call failed, and says why. */
    val error: String = "",
    /**
     * How the call was shown while it ran, when that is more than its name:
     * an MCP call carries its server and the tool's own title (#573). Null
     * for our own tools, whose record is built from the call alone.
     */
    val display: AiToolActivity? = null,
) {
    val activity: AiToolActivity
        get() = (display ?: AiToolActivity.started(call)).copy(
            running = false,
            sources = sources,
            error = error,
            awaitingApproval = false,
            details = "",
            arguments = call.arguments.trim().take(AiToolActivity.KEPT_CHARS),
            output = content.trim().take(AiToolActivity.KEPT_CHARS),
        )
}

/** What the user answered when a call asked first (#573). */
enum class AiToolApproval {
    /** Run this call. */
    ONCE,

    /** Run this call, and every later call of the same tool without asking. */
    ALWAYS,

    /** Do not run it; the model is told so. */
    DENY,

    /** The run was stopped while the question was showing. */
    CANCELLED,
}

/**
 * Asks the user whether a call may run, and waits for the answer. [ask] is
 * the call as the user is to see it, with [AiToolActivity.awaitingApproval]
 * set and its arguments in [AiToolActivity.details].
 *
 * Blocking: it is called from the tool loop's own thread, which has nothing
 * to do until the user answers.
 */
fun interface AiToolApprover {
    fun approve(ask: AiToolActivity): AiToolApproval
}

/** One page a tool call put in front of the model: a search result, or the page it read. */
@Serializable
data class AiToolSource(
    val title: String = "",
    val url: String = "",
) {
    /** The site, for a line too short for the whole address: `example.com`. */
    val domain: String get() = AiToolActivity.domainOf(url)
}

/**
 * One tool call as the user is shown it: what the model asked for, what came
 * back, and whether it worked.
 *
 * Without this a run that searched the web looked exactly like one that did
 * not, and the user had no way to tell an answer built on a page from one made
 * up. It is what the chat draws while a tool runs ("Searching the web for …")
 * and keeps under the answer afterwards, and it is stored with the message, so
 * strings rather than enums for the same reason as the rest of the chat store:
 * a record has to outlive a tool being renamed.
 */
@Serializable
data class AiToolActivity(
    /** [AiTools.WEB_SEARCH], [AiTools.WEB_FETCH], or a name the model made up. */
    val tool: String,
    /** The query searched for, or the address read. Empty when the model left it out. */
    val target: String = "",
    /** Still running. Never true in a stored message. */
    val running: Boolean = false,
    /** Search results the model was given, or the one page it read. */
    val sources: List<AiToolSource> = emptyList(),
    /** Not empty means the call failed, and says why. */
    val error: String = "",
    /**
     * The MCP server the tool belongs to (#573), by the name the user gave it.
     * Empty for our own tools. When set, [tool] is the tool's title rather
     * than a name from [AiTools].
     */
    val server: String = "",
    /** The call is waiting for the user to allow it. Never true in a stored message. */
    val awaitingApproval: Boolean = false,
    /** The call's arguments in full, for the approval card. Empty once it has run. */
    val details: String = "",
    /**
     * What a [AiTools.REMEMBER] call kept, for the chat's "Saved to memory"
     * line and its Undo. Null for every other call, and for a save that
     * changed nothing. Defaulted, so a stored message from before memory
     * still decodes.
     */
    val memory: AiMemoryNote? = null,
    /**
     * The arguments the model wrote, as it wrote them, and what the call
     * handed back to it, each cut to [KEPT_CHARS] (#882): what the chat's
     * chain of steps shows when a call is tapped open. Empty while it runs,
     * and in a message stored before they were kept.
     */
    val arguments: String = "",
    val output: String = "",
) {
    val failed: Boolean get() = error.isNotEmpty()

    /** A tool from one of the user's MCP servers rather than one of ours. */
    val isMcp: Boolean get() = server.isNotEmpty()

    companion object {
        /** How much of [arguments] and [output] is kept with a message. */
        const val KEPT_CHARS = 4000

        /** A call the model just asked for, before it has run. */
        fun started(call: AiToolCall): AiToolActivity = AiToolActivity(
            tool = call.name,
            target = AiTools.targetOf(call),
            running = true,
        )

        /**
         * [trace] with [result] in place of the call it answers. Calls run one
         * at a time, so that is the newest one still running; a result with no
         * started call to replace is added at the end.
         */
        fun record(trace: List<AiToolActivity>, result: AiToolResult): List<AiToolActivity> {
            val at = trace.indexOfLast { it.running }
            if (at < 0) return trace + result.activity
            return trace.toMutableList().also { it[at] = result.activity }
        }

        /**
         * [trace] as it is kept once the run is over: a call the run ended in
         * the middle of (Stop) is marked with [reason] rather than left
         * looking as if it were still going, or as if it had worked.
         */
        fun settle(trace: List<AiToolActivity>, reason: String): List<AiToolActivity> =
            trace.map {
                if (it.running) it.copy(running = false, error = reason, awaitingApproval = false, details = "") else it
            }

        /**
         * [trace] with the newest running call replaced by [shown]: the same
         * call, now asking to be allowed, or done asking. Added at the end
         * when nothing is running, as [record] does.
         */
        fun replaceRunning(trace: List<AiToolActivity>, shown: AiToolActivity): List<AiToolActivity> {
            val at = trace.indexOfLast { it.running }
            if (at < 0) return trace + shown
            return trace.toMutableList().also { it[at] = shown }
        }

        /**
         * The host of [url] without a leading `www.`, or [url] itself when it
         * has none — what a one-line record has room for.
         */
        fun domainOf(url: String): String {
            val host = url.trim().substringAfter("://").substringBefore('/')
                .substringBefore('?').substringBefore('#').substringAfterLast('@')
            return host.removePrefix("www.").ifEmpty { url.trim() }
        }

        /**
         * [url] when a tap may open it: http and https only. The address came
         * from a model or a search result, so an `intent:` or `file:` one is
         * offered for copying and never handed to a browser.
         */
        fun openableUrl(url: String): String? {
            val trimmed = url.trim()
            val scheme = trimmed.substringBefore("://", "").lowercase()
            return trimmed.takeIf { (scheme == "http" || scheme == "https") && domainOf(it).isNotEmpty() }
        }
    }
}

/**
 * One memory a `remember` call added or changed, as the chat keeps it under
 * the answer: enough to show the fact and to take the save back.
 *
 * [before] and [beforeCategory] are the memory as it was when the call
 * changed one already kept; both empty, and [added] true, when the call made
 * a new one, which Undo then deletes. [undone] is set once the user pressed
 * Undo, so the line stops offering it.
 */
@Serializable
data class AiMemoryNote(
    val id: Long,
    val text: String,
    val added: Boolean = true,
    val before: String = "",
    val beforeCategory: String = "",
    val undone: Boolean = false,
)

/**
 * Where one of the keyboard's own tools sits on the settings screen, which is
 * also a rough measure of what it touches: [INFO] reads the outside world,
 * [DATA] reads the user's own, [PICTURE] works on a picture the user sent, and
 * [ACTION] changes something on the phone (#698).
 */
enum class AiToolGroup { INFO, DATA, PICTURE, ACTION }

/**
 * How `add_calendar_event` adds an event (#698). [OPEN_APP] fills in the
 * calendar app's own editor and leaves Save to the user, which needs no
 * permission; [DIRECT] writes the event itself, which needs WRITE_CALENDAR.
 */
enum class AiCalendarMode { OPEN_APP, DIRECT }

/**
 * One of the keyboard's own tools a model may call beyond search and fetch
 * (#698), with the Run / Ask / Off policy it starts on.
 *
 * The policy enum is the MCP one on purpose. To the user a tool is a tool,
 * wherever it runs, and "this one asks first" should look and behave the same
 * for a server's tool and for ours: one choice row, one approval card.
 */
data class AiBuiltInTool(
    val spec: AiToolSpec,
    val group: AiToolGroup,
    val defaultPolicy: McpToolPolicy,
) {
    val name: String get() = spec.name
}

/**
 * The tools the AI tool can offer, and the catalogue the settings rows switch
 * on.
 *
 * Every tool costs tokens in the system prompt of every single run, whether it
 * is ever called or not, and a small on-device model reading a long catalogue
 * answers worse than one reading a short one. So each has its own Off, and the
 * descriptions are one sentence where one sentence will do.
 */
object AiTools {

    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"
    const val WM_DOCS = "search_wm_docs"
    const val IMAGE_SEARCH = "image_search"
    const val WEATHER = "weather"
    const val WIKIPEDIA = "wikipedia"
    const val DEFINE_WORD = "define_word"
    const val TRANSLATE = "translate"
    const val CONVERT_CURRENCY = "convert_currency"
    const val CONVERT_UNITS = "convert_units"
    const val CALCULATE = "calculate"
    const val CALENDAR_EVENTS = "calendar_events"
    const val SEARCH_NOTES = "search_notes"
    const val SEARCH_CLIPBOARD = "search_clipboard"
    const val SEARCH_SNIPPETS = "search_snippets"
    const val CURRENT_LOCATION = "current_location"
    const val ADD_CALENDAR_EVENT = "add_calendar_event"
    const val ADD_NOTE = "add_note"
    const val SET_ALARM = "set_alarm"
    const val START_TIMER = "start_timer"
    const val OPEN_APP = "open_app"
    const val MEDIA_CONTROL = "media_control"
    const val SEND_TO_PC = "send_to_pc"
    const val HOME_ASSISTANT = "home_assistant"
    const val HOME_ASSISTANT_STATES = "home_assistant_states"
    const val READ_IMAGE_TEXT = "read_image_text"
    const val REVERSE_IMAGE_SEARCH = "reverse_image_search"
    const val REMEMBER = "remember"
    const val FORGET = "forget"

    /**
     * Renames the chat the model is in (#882). Offered only by a chat, and
     * always asks the user first, showing the name.
     */
    const val RENAME_CHAT = "rename_chat"

    /**
     * Two steps of a deep research run ([AiResearch]) that are not tools at
     * all: the plan of searches, and the look for what is still missing.
     * Named here because the chat records them as it records a tool call, so
     * they draw as steps beside the searches and pages they led to. Never
     * offered to a model.
     */
    const val RESEARCH_PLAN = "research_plan"
    const val RESEARCH_REVIEW = "research_review"

    /**
     * The tools whose description ends with today's date, since a model has
     * no clock of its own and "tomorrow at five" means nothing without one.
     * The day only: the description is part of the prompt an on-device chat
     * session is keyed by, and a clock in it would rebuild the session every
     * minute.
     */
    val DATED: Set<String> = setOf(CALENDAR_EVENTS, ADD_CALENDAR_EVENT, SET_ALARM)

    /** Rounds of tool calls one run may take before the model has to answer. */
    const val DEFAULT_MAX_ROUNDS = 3

    /**
     * Search the web. The query is the model's, not the user's: it rewrites
     * the request into something a search engine answers well, which is most
     * of the value of giving it the tool rather than searching for it.
     */
    val webSearch = AiToolSpec(
        name = WEB_SEARCH,
        description = "Search the web for current information. " +
            "Use it for anything you do not know or that may have changed since training.",
        parameters = schema(
            "query" to "What to search for, written as a search engine query.",
        ),
    )

    /**
     * Read one page. Kept independent of [webSearch] — the issue that asked
     * for these wanted a user who pastes a link to be able to say "summarise
     * this" without paying for a search backend as well.
     */
    val webFetch = AiToolSpec(
        name = WEB_FETCH,
        description = "Fetch one web page and read its text. " +
            "Use it on a link the user gave you, or on a result from $WEB_SEARCH.",
        parameters = schema(
            "url" to "The full address of the page, including https://.",
        ),
    )

    /**
     * Keep one fact about the user in the AI's memory. Not one of [builtIns]:
     * it has no Run / Ask / Off of its own, since the memory switches on
     * Settings › AI › Memory decide whether a chat has it at all, and a save
     * is shown in the chat with an Undo rather than asked about first.
     */
    val remember = AiToolSpec(
        name = REMEMBER,
        description = "Save one short fact about the user to your memory, which lasts between conversations. " +
            "Only for what will still be true and useful later. Never a password, code, card number or key.",
        parameters = schema(
            param(
                "fact",
                "The fact, about the user in the third person, such as 'Lives in Dhaka' or 'Prefers short answers'.",
            ),
            param("id", "The number of a saved memory this fact replaces, when that fact has changed.", type = "integer", required = false),
            param("category", "One word for the kind of fact, such as work, family, food or preference.", required = false),
        ),
    )

    /** Rename this chat; see [RENAME_CHAT]. */
    val renameChat = AiToolSpec(
        name = RENAME_CHAT,
        description = "Rename this chat, when the user asks you to. The user sees the name and accepts or declines it.",
        parameters = schema(
            param("title", "The new name: two to six words, in the language the user writes in."),
        ),
    )

    /** Delete memories the user wants gone; see [remember]. */
    val forget = AiToolSpec(
        name = FORGET,
        description = "Delete memories the user asks you to forget. Give the memory's number, or words from it.",
        parameters = schema(
            param("id", "The number of the memory to delete.", type = "integer", required = false),
            param("match", "Words from the memory to delete, when you do not have its number.", required = false),
        ),
    )

    /**
     * The keyboard's own tools beyond search and fetch, in the order the
     * settings screen lists them and a model reads them.
     *
     * The information tools start on Run: they read the outside world, and an
     * answer built on a real exchange rate beats one remembered from training.
     */
    val builtIns: List<AiBuiltInTool> = listOf(
        // First, because the question a user most often asks the keyboard's own
        // AI is about the keyboard, and a model's training data describes some
        // other one, or this one a year ago.
        info(
            WM_DOCS,
            "Search WM Keyboard's own documentation. Use it for any question about this keyboard: " +
                "how to do something in it, what a setting does, which files it can import.",
            param("query", "What to look for, in English, such as 'import Rime dictionary Chinese'."),
        ),
        info(
            IMAGE_SEARCH,
            "Search the web for images. Returns each image's address and the page it is on.",
            param("query", "What to search for."),
        ),
        info(
            WEATHER,
            "Current weather, and today's and tomorrow's forecast, for a place.",
            param("place", "A city or place name. Leave it out for the user's own place.", required = false),
        ),
        info(
            WIKIPEDIA,
            "Look a topic up on Wikipedia and read the summary of the best-matching article.",
            param("query", "The topic or article title."),
        ),
        info(
            DEFINE_WORD,
            "Look a word up in the dictionary: its meanings, part of speech and examples.",
            param("word", "The word to look up."),
        ),
        info(
            TRANSLATE,
            "Translate text with the keyboard's translator.",
            param("text", "The text to translate."),
            param(
                "to",
                "The language to translate into, as a code such as en, bn or fr. " +
                    "Leave it out for the user's usual target language.",
                required = false,
            ),
            param("from", "The language of the text, as a code. Leave it out to detect it.", required = false),
        ),
        info(
            CONVERT_CURRENCY,
            "Convert an amount of money between currencies at live exchange rates.",
            param("amount", "The amount to convert.", type = "number"),
            param("from", "The currency it is in, as a code such as USD, EUR or BTC."),
            param("to", "The currency to convert to, as a code."),
        ),
        info(
            CONVERT_UNITS,
            "Convert a measurement between units of length, mass, temperature, volume, area, speed, time or data.",
            param("value", "The number to convert.", type = "number"),
            param("from", "The unit it is in, such as km, lb, °F or GB."),
            param("to", "The unit to convert to."),
        ),
        info(
            CALCULATE,
            "Work out an arithmetic expression exactly, instead of in your head. " +
                "Supports + - * / ^ %, brackets, sqrt, sin, cos, tan, log, ln, pi and e.",
            param("expression", "The expression, such as (12.5 * 4) / 3 or 18% of 2450."),
        ),
        // What the user keeps on the phone. These ask first: a page the model
        // read could carry instructions, and the user should see a request
        // for their calendar before it is answered.
        data(
            CALENDAR_EVENTS,
            "List the events in the user's calendar from one date to another.",
            param("from", "The first day, as YYYY-MM-DD."),
            param("to", "The last day, as YYYY-MM-DD. Leave it out for one day.", required = false),
        ),
        data(
            SEARCH_NOTES,
            "Search the user's notes for words in their title, tags or text, and read the ones that match. " +
                "When search by meaning is on, notes that mean the same thing in other words follow.",
            param("query", "Words to look for. Leave it out for the most recent notes.", required = false),
        ),
        data(
            SEARCH_SNIPPETS,
            "Search the user's text snippets, the saved texts they insert by typing a short trigger, " +
                "for words in their name, trigger, tags or text. Each match gives its name, its trigger and its text. " +
                "When search by meaning is on, snippets that mean the same thing in other words follow.",
            param("query", "Words to look for. Leave it out for the newest snippets.", required = false),
        ),
        // Search by meaning over the clipboard history: offered only once
        // its model is on the phone and the feature is on (see AiToolRunner).
        data(
            SEARCH_CLIPBOARD,
            "Search the user's clipboard history, the text they copied, by what it means, not only by its words. " +
                "Returns the closest clips. Passwords and one-time codes are never among them.",
            param("query", "What to look for, in any language, such as 'the dentist's address' or 'the flight number'."),
        ),
        data(
            CURRENT_LOCATION,
            "Where the user's phone is now: its coordinates and, when it can be found, the address.",
        ),
        data(
            HOME_ASSISTANT_STATES,
            "Read the state of the user's smart-home devices and sensors, from every home they connected: " +
                "which lights are on, the temperature a sensor reads, whether a door is open. " +
                "Each line ends with the device's id in brackets, which $HOME_ASSISTANT takes as entity_id.",
            param("query", "A room, device or sensor name to look for. Leave it out for everything that is on or open.", required = false),
        ),
        // The pictures the user sent in the chat. Reading one's text happens on
        // the phone; searching by one uploads it to a search site, which is
        // why that one starts off.
        AiBuiltInTool(
            AiToolSpec(
                READ_IMAGE_TEXT,
                "Read the text in a picture the user sent, when you cannot see pictures yourself.",
                schema(param("image", "The picture's number, from its <image n=\"…\"/> mark.", type = "integer")),
            ),
            AiToolGroup.PICTURE,
            McpToolPolicy.ALLOW,
        ),
        AiBuiltInTool(
            AiToolSpec(
                REVERSE_IMAGE_SEARCH,
                "Search the web by a picture the user sent, to find where it comes from or what it shows. " +
                    "Returns a link to the results for the user to open.",
                schema(param("image", "The picture's number, from its <image n=\"…\"/> mark.", type = "integer")),
            ),
            AiToolGroup.PICTURE,
            McpToolPolicy.OFF,
        ),
        // Things that change something. None of them deletes anything, and
        // none edits what is already there: they only add or start.
        action(
            ADD_CALENDAR_EVENT,
            "Add an event to the user's calendar. Times are the user's local time.",
            param("title", "What the event is called."),
            param("start", "When it starts: YYYY-MM-DD for an all-day event, or YYYY-MM-DDTHH:MM."),
            param("end", "When it ends, in the same form. Leave it out for an hour, or one day.", required = false),
            param("location", "Where it is.", required = false),
            param("notes", "A description to keep with it.", required = false),
        ),
        action(
            ADD_NOTE,
            "Save a new note in the user's notes.",
            param("text", "What the note says."),
            param("title", "A short title.", required = false),
        ),
        action(
            SET_ALARM,
            "Set an alarm in the keyboard's clock.",
            param("time", "When it rings, as HH:MM on a 24-hour clock."),
            param("label", "A label to show when it rings.", required = false),
            param(
                "days",
                "The days it repeats on, such as mon,wed,fri, weekdays, weekends or every day. Leave it out to ring once.",
                required = false,
            ),
        ),
        action(
            START_TIMER,
            "Start a countdown timer in the keyboard's clock.",
            param("duration", "How long, such as 10m, 1h30m or 90s."),
            param("label", "A label to show when it ends.", required = false),
        ),
        action(
            OPEN_APP,
            "Open an app installed on the user's phone.",
            param("name", "The app's name, as the user calls it."),
        ),
        action(
            MEDIA_CONTROL,
            "Control the music or video playing on the phone, or read what is playing.",
            param(
                "action",
                "What to do.",
                choices = listOf("play", "pause", "next", "previous", "now_playing"),
            ),
        ),
        action(
            SEND_TO_PC,
            "Send text or a link to the user's computer through KDE Connect.",
            param("text", "The text or the link to send."),
        ),
        action(
            HOME_ASSISTANT,
            "Control the user's smart home: turn things on or off, set brightness, " +
                "temperature or position, run a scene, lock a door. Either name one device by entity_id with an action, " +
                "taking the id from $HOME_ASSISTANT_STATES, or say it in one plain sentence as request, " +
                "which only a Home Assistant with Assist understands. Prefer entity_id and action.",
            param("entity_id", "The device's id from $HOME_ASSISTANT_STATES, such as 'fan.bedroom'.", required = false),
            param(
                "action",
                "What to do to it: turn_on, turn_off or toggle, or a service such as set_temperature, " +
                    "set_percentage, open_cover, lock or unlock.",
                required = false,
            ),
            param(
                "data",
                "The service's settings as a JSON object, such as {\"brightness_pct\": 40} or {\"temperature\": 21}. " +
                    "For a fan the states list with speeds, set_percentage takes {\"speed\": 1} for its first speed. " +
                    "Leave it out for a plain on or off.",
                required = false,
            ),
            param("request", "What to do, as one sentence, such as 'turn off the kitchen lights'. Home Assistant only.", required = false),
        ),
    )

    /** One of [builtIns] by its name, or null for search, fetch or a name nobody gave out. */
    fun builtIn(name: String): AiBuiltInTool? = builtIns.firstOrNull { it.name == name }

    /** The policy in force for [tool]: what the user set, or what it starts on. */
    fun policyOf(tool: AiBuiltInTool, policies: Map<String, McpToolPolicy>): McpToolPolicy =
        policies[tool.name] ?: tool.defaultPolicy

    /** Every name the keyboard's own tools answer to, which no MCP tool may take. */
    val reservedNames: Set<String> by lazy {
        setOf(WEB_SEARCH, WEB_FETCH, REMEMBER, FORGET, RENAME_CHAT, RESEARCH_PLAN, RESEARCH_REVIEW) + builtIns.map { it.name }
    }

    /** The enabled tools, in the order a model sees them. */
    fun enabled(search: Boolean, fetch: Boolean): List<AiToolSpec> = buildList {
        if (search) add(webSearch)
        if (fetch) add(webFetch)
    }

    /**
     * What one call is about, in a few words: the query searched for, the page
     * read, the place whose weather was asked for. Blank when the model left
     * the argument out, or for a tool with nothing short to say.
     */
    fun targetOf(call: AiToolCall): String = when (call.name) {
        WEB_SEARCH, IMAGE_SEARCH, WIKIPEDIA, WM_DOCS -> call.argument("query")
        WEB_FETCH -> call.argument("url")
        WEATHER -> call.argument("place")
        DEFINE_WORD -> call.argument("word")
        TRANSLATE -> call.argument("to")
        CONVERT_CURRENCY -> conversion(call, "amount")
        CONVERT_UNITS -> conversion(call, "value")
        CALCULATE -> call.argument("expression")
        CALENDAR_EVENTS -> call.argument("from")?.let { from -> call.argument("to")?.let { "$from – $it" } ?: from }
        SEARCH_NOTES, SEARCH_SNIPPETS, SEARCH_CLIPBOARD -> call.argument("query")
        ADD_NOTE -> call.argument("title")
        ADD_CALENDAR_EVENT -> call.argument("title")
        SET_ALARM -> call.argument("time")
        START_TIMER -> call.argument("duration")
        OPEN_APP -> call.argument("name")
        MEDIA_CONTROL -> call.argument("action")
        READ_IMAGE_TEXT, REVERSE_IMAGE_SEARCH -> call.argument("image")
        SEND_TO_PC -> call.argument("text")?.let { if (it.length > TARGET_CHARS) it.take(TARGET_CHARS - 1) + "…" else it }
        HOME_ASSISTANT -> (call.argument("request") ?: listOfNotNull(call.argument("action"), call.argument("entity_id")).joinToString(" ").ifEmpty { null })
            ?.let { if (it.length > TARGET_CHARS) it.take(TARGET_CHARS - 1) + "…" else it }
        HOME_ASSISTANT_STATES -> call.argument("query")
        REMEMBER -> call.argument("fact")?.let { if (it.length > TARGET_CHARS) it.take(TARGET_CHARS - 1) + "…" else it }
        FORGET -> call.argument("match") ?: call.argument("id")
        RENAME_CHAT -> call.argument("title")
        else -> null
    }.orEmpty()

    /** Longest bit of free text a one-line record repeats. */
    private const val TARGET_CHARS = 48

    /** "12 USD → EUR", or null when the model left out either end. */
    private fun conversion(call: AiToolCall, amountKey: String): String? {
        val from = call.argument("from") ?: return null
        val to = call.argument("to") ?: return null
        val amount = call.argument(amountKey)
        return listOfNotNull(amount, from).joinToString(" ") + " → " + to
    }

    private fun info(name: String, description: String, vararg params: Param) =
        AiBuiltInTool(AiToolSpec(name, description, schema(*params)), AiToolGroup.INFO, McpToolPolicy.ALLOW)

    private fun data(name: String, description: String, vararg params: Param) =
        AiBuiltInTool(AiToolSpec(name, description, schema(*params)), AiToolGroup.DATA, McpToolPolicy.ASK)

    private fun action(name: String, description: String, vararg params: Param) =
        AiBuiltInTool(AiToolSpec(name, description, schema(*params)), AiToolGroup.ACTION, McpToolPolicy.ASK)

    /** One argument of a tool, as the schema describes it to a model. */
    private class Param(
        val name: String,
        val description: String,
        val type: String,
        val required: Boolean,
        val choices: List<String>,
    )

    private fun param(
        name: String,
        description: String,
        type: String = "string",
        required: Boolean = true,
        choices: List<String> = emptyList(),
    ) = Param(name, description, type, required, choices)

    /** A one-object schema whose every named property is a required string. */
    private fun schema(vararg properties: Pair<String, String>): JsonObject =
        schema(*properties.map { (name, description) -> param(name, description) }.toTypedArray())

    /**
     * A one-object schema. Optional arguments stay out of `required`, which is
     * how every provider tells a model it may leave one out; a fixed set of
     * answers becomes an `enum`.
     */
    private fun schema(vararg params: Param): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (param in params) {
                putJsonObject(param.name) {
                    put("type", param.type)
                    put("description", param.description)
                    if (param.choices.isNotEmpty()) {
                        put("enum", buildJsonArray { param.choices.forEach { add(JsonPrimitive(it)) } })
                    }
                }
            }
        }
        put("required", buildJsonArray { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
    }
}

/**
 * Tool calling for the providers that have no tool calling: a marker the model
 * writes into its answer, pulled back out of the stream before anyone sees it.
 *
 * This is what lets an on-device model and Brave's Answers API take part at
 * all — neither has a function-calling API, and Brave takes one user message
 * with no system role, so everything either of them will ever know about tools
 * has to travel inside the text. A model that supports native tool calling
 * should never be sent down this path.
 *
 * The reader here is deliberately loose. Asked for `<tool_call>{…}</tool_call>`,
 * a local model writes whatever its own fine-tune taught it, and the angle
 * brackets are the worst part of it: `tool_call` is a *special token* in
 * several chat templates, so the template mangles the very string we asked
 * for. Gemma 4 E2B alone produced `TOOL_CALL{{…}}<tool_call|>` and
 * `<|tool_call>call:tool_call{{…}}<tool_call|>` — no opener in the first,
 * pipes on the wrong side in both, doubled braces in both. Matching only the
 * exact string meant none of it parsed *and* none of it was hidden, so the
 * user got raw JSON in the answer bubble: the worst of both outcomes.
 *
 * So [Filter] looks for a marker in any of the shapes those templates produce,
 * takes the next balanced `{…}` after it however many layers of braces it is
 * wrapped in, and — this is the part that matters for what the user sees —
 * hides the whole span whether or not it ends up parsing. Machinery the model
 * wrote for us is never the user's to read, even when it is malformed.
 */
object AiToolProtocol {

    /** The marker the instructions ask for; readers accept far more. */
    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"

    /** How a tool's answer is introduced in the user turn that carries it. */
    const val RESULT_PREFIX = "TOOL RESULT"

    /**
     * What to append to the system prompt (or, for Brave, to the one user
     * message) so the model knows the marker.
     *
     * Short on purpose — every word is paid for on every run, including the
     * runs where nothing is ever called — but two instructions earn their
     * place. "Do not ask the user to narrow" is there because a small model's
     * first move, given a tool, is to ask which — Gemma 4 E2B asked twice
     * before calling anything for a request it could have searched as
     * written. "Exactly this and nothing else" is there because the reader
     * hides a whole marker span, so a model that wraps its call in
     * explanation loses the explanation too.
     */
    fun instructions(tools: List<AiToolSpec>): String {
        if (tools.isEmpty()) return ""
        val list = tools.joinToString("\n") { tool ->
            val args = tool.parameters["properties"]?.jsonObject?.keys.orEmpty().joinToString(", ")
            "- ${tool.name}($args): ${tool.description}"
        }
        return "\n\nYou can call tools. To call one, write exactly this and nothing else:\n" +
            OPEN + """{"name":"TOOL","arguments":{"ARG":"VALUE"}}""" + CLOSE + "\n" +
            "The answer comes back as a message starting $RESULT_PREFIX, and you then " +
            "reply to the user normally. Call a tool whenever you need something you " +
            "do not already know — do not ask the user to narrow a request you could " +
            "look up as they wrote it. Never mention the tools or this format to the " +
            "user.\nTools:\n" + list
    }

    /** The user message carrying one finished call's answer. */
    fun resultMessage(result: AiToolResult): String =
        "$RESULT_PREFIX ${result.call.name}\n${result.content}"

    /**
     * Pulls markers out of a stream whose chunks can cut one anywhere — `<tool`
     * in one event and `_call>{…}` in the next.
     *
     * Same hold-back shape as `AiClient.BraveTagFilter`: text that might still
     * turn into a marker is kept back until it either does or cannot, and
     * everything else passes straight through, so the answer still appears as
     * it streams. Here the hold can start on a bare `t` as well as on a `<`,
     * because a marker need not have brackets at all — which costs at most a
     * word of lag at a chunk boundary, and never shows a half-written marker.
     */
    class Filter {
        private val pending = StringBuilder()
        private val found = ArrayList<AiToolCall>()

        /** The calls seen so far, oldest first. */
        val calls: List<AiToolCall> get() = found

        /** Takes the next chunk, returns the text now known to be answer. */
        fun feed(chunk: String): String {
            pending.append(chunk)
            return drain(final = false)
        }

        /**
         * The end of the stream. A partial marker is released as the text it
         * turned out to be; a call that never closed is read anyway when what
         * there is of it parses, because a model that ran out of room
         * mid-marker still said what it wanted.
         */
        fun flush(): String = drain(final = true)

        private fun drain(final: Boolean): String {
            val out = StringBuilder()
            var i = 0
            while (i < pending.length) {
                val trigger = AiToolProtocol.nextTrigger(pending, i)
                if (trigger < 0) {
                    out.append(pending, i, pending.length)
                    i = pending.length
                    break
                }
                out.append(pending, i, trigger)
                i = trigger
                when (val scan = AiToolProtocol.readCall(pending, i, final)) {
                    Scan.NeedMore -> break
                    Scan.NotHere -> {
                        out.append(pending[i])
                        i++
                    }
                    is Scan.Found -> {
                        scan.call?.let(found::add)
                        i = scan.end
                    }
                }
            }
            pending.delete(0, i)
            return out.toString()
        }
    }

    /**
     * Every marker in one finished body. For the non-streaming path and for
     * reading a model's answer back in a test; the streaming path uses
     * [Filter], which has to do the same job a chunk at a time.
     */
    fun parseAll(text: String): List<AiToolCall> {
        val filter = Filter()
        filter.feed(text)
        filter.flush()
        return filter.calls
    }

    /** [text] with every marker taken out, which is what the user may see. */
    fun stripped(text: String): String {
        val filter = Filter()
        return (filter.feed(text) + filter.flush()).trim()
    }

    // ---- the reader -------------------------------------------------------

    private sealed interface Scan {
        /** More of the stream is needed before this can be decided. */
        object NeedMore : Scan

        /** Not a marker after all; the character is ordinary text. */
        object NotHere : Scan

        /** A marker ran to [end]; [call] is null when it did not parse. */
        data class Found(val call: AiToolCall?, val end: Int) : Scan
    }

    /**
     * The next place a marker could begin: an angle or square bracket, or the
     * word itself, since several templates drop the brackets entirely.
     */
    private fun nextTrigger(text: CharSequence, from: Int): Int {
        for (i in from until text.length) {
            val c = text[i]
            if (c == '<' || c == '[' || c == 't' || c == 'T') return i
        }
        return -1
    }

    /**
     * Reads one marker and the call it introduces, starting at [start].
     *
     * A bare marker with no object after it is still consumed rather than
     * shown: the model wrote it for us, and "<tool_call>" in the middle of an
     * answer helps nobody.
     */
    private fun readCall(text: CharSequence, start: Int, final: Boolean): Scan {
        val marker = matchMarker(text, start)
        if (marker < 0) {
            return if (!final && couldBecomeMarker(text, start)) Scan.NeedMore else Scan.NotHere
        }
        val body = skipFiller(text, marker, final) ?: return needMore(final, text.length)
        if (body >= text.length) return needMore(final, text.length)
        if (text[body] != '{') {
            // A marker and then prose. Swallow only the marker itself and let
            // the scan carry on through what follows as ordinary text.
            return Scan.Found(null, marker)
        }
        val close = matchBraces(text, body)
        if (close < 0) {
            if (!final) return Scan.NeedMore
            return Scan.Found(parse(text.substring(body)), text.length)
        }
        val call = parse(text.substring(body, close))
        val end = skipClosing(text, close, final) ?: return Scan.NeedMore
        return Scan.Found(call, end)
    }

    private fun needMore(final: Boolean, end: Int): Scan =
        if (final) Scan.Found(null, end) else Scan.NeedMore

    /**
     * The end of a marker at [start], or -1. Written as a regex over a bounded
     * window rather than a hand-rolled walk, because the shapes are many and
     * all of them are trivial.
     */
    private fun matchMarker(text: CharSequence, start: Int): Int {
        val window = text.subSequence(start, minOf(text.length, start + MARKER_WINDOW))
        val match = MARKER.find(window)?.takeIf { it.range.first == 0 } ?: return -1
        return start + match.range.last + 1
    }

    /**
     * Whether what is left of the buffer could still grow into a marker. A
     * prefix of any canonical spelling counts; so does a bracket on its own,
     * which is how every bracketed spelling starts.
     */
    private fun couldBecomeMarker(text: CharSequence, start: Int): Boolean {
        val tail = text.subSequence(start, minOf(text.length, start + MARKER_WINDOW)).toString()
        if (tail.length >= MARKER_WINDOW) return false
        return MARKER_SHAPES.any { it.startsWith(tail, ignoreCase = true) }
    }

    /**
     * Past whatever a template wedged between the marker and the object:
     * whitespace, a `call:` label, a code fence, and repeats of the marker
     * itself. Null means the buffer ran out mid-filler.
     */
    private fun skipFiller(text: CharSequence, from: Int, final: Boolean): Int? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace() || c == '`') {
                i++
                continue
            }
            val marker = matchMarker(text, i)
            if (marker > i) {
                i = marker
                continue
            }
            val label = FILLER.find(text.subSequence(i, minOf(text.length, i + MARKER_WINDOW)))
                ?.takeIf { it.range.first == 0 }
            if (label != null) {
                i += label.range.last + 1
                continue
            }
            return i
        }
        return if (final) i else null
    }

    /**
     * The index just past the `}` that closes the object opened at [from], or
     * -1 when it never closes. Quoted strings are skipped whole, so a brace
     * inside a search query cannot end the object early.
     */
    private fun matchBraces(text: CharSequence, from: Int): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
                else -> Unit
            }
            i++
        }
        return -1
    }

    /** Past a closing marker and any code fence after the object. */
    private fun skipClosing(text: CharSequence, from: Int, final: Boolean): Int? {
        var i = from
        while (i < text.length && (text[i] == '`' || text[i] == ' ')) i++
        val marker = matchMarker(text, i)
        if (marker > i) {
            i = marker
            while (i < text.length && text[i] == '`') i++
            return i
        }
        // The closer may simply not have arrived yet.
        return if (final || !couldBecomeMarker(text, i)) from else null
    }

    /**
     * One payload as a call, or null when it is not one.
     *
     * `arguments` is accepted both as an object and as a string holding one —
     * models trained against OpenAI's wire format write the string form about
     * as often as the object form — and `parameters` is read as an alias.
     * A payload wrapped in extra braces is unwrapped a layer at a time, which
     * is what `{{"name":…}}` needs.
     */
    private fun parse(payload: String): AiToolCall? {
        var body = payload.trim()
        repeat(MAX_BRACE_LAYERS) {
            read(body)?.let { parsed -> return parsed }
            if (!body.startsWith("{{") || !body.endsWith("}}")) return null
            body = body.substring(1, body.length - 1).trim()
        }
        return null
    }

    private fun read(payload: String): AiToolCall? = runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        val name = (root["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (name.isEmpty()) return null
        val args = root["arguments"] ?: root["parameters"]
        val arguments = when (args) {
            null -> "{}"
            is JsonPrimitive -> args.content.trim().ifEmpty { "{}" }
            else -> args.toString()
        }
        AiToolCall(id = "", name = name, arguments = arguments)
    }.getOrNull()

    /**
     * Every marker spelling seen in the wild, as one pattern: optional
     * bracket, optional pipes either side, optional slash for a closer, the
     * word with any separator, and the matching close bracket.
     *
     * Unbracketed, only `tool_call` counts, and the underscore is what makes
     * it count. The pattern ignores case, so allowing the spaced form here
     * would make the words "tool call" in an ordinary sentence disappear out
     * of the answer — a far worse failure than missing one odd spelling,
     * since the bracketed form already covers every template that writes it
     * that way.
     */
    private val MARKER = Regex(
        """^(?:[<\[]\s*\|?\s*/?\s*tool[_ ]?call\s*\|?\s*[>\]]?|/?tool_call)""",
        RegexOption.IGNORE_CASE,
    )

    /** `call:`, `json`, and the other labels templates put before the object. */
    private val FILLER = Regex("""^(?:call|json|arguments|args|function)\s*[:=]?""", RegexOption.IGNORE_CASE)

    /** Canonical spellings, for deciding whether a cut-off tail could grow into one. */
    private val MARKER_SHAPES = listOf(
        "<tool_call>", "</tool_call>", "<|tool_call|>", "<|tool_call>", "<tool_call|>",
        "[tool_call]", "[/tool_call]", "tool_call",
    )

    /** Longest marker worth looking at; keeps every scan bounded. */
    private const val MARKER_WINDOW = 20

    /** How many layers of stray braces a payload may be wrapped in. */
    private const val MAX_BRACE_LAYERS = 3
}
