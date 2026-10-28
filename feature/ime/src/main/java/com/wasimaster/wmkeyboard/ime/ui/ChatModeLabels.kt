package com.wasimaster.wmkeyboard.ime.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Article
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Chat
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.CompareArrows
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ShortText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataObject
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListBulleted
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TableRows
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TravelExplore
import com.wasimaster.wmkeyboard.core.tools.AiAnswerFormat
import com.wasimaster.wmkeyboard.core.tools.AiChatMode
import com.wasimaster.wmkeyboard.ime.R

/*
 * The chat's modes and answer formats as both message boxes name and mark
 * them: the keyboard's and the app's draw the same words and the same icons.
 */

@get:StringRes
val AiChatMode.labelRes: Int
    get() = when (this) {
        AiChatMode.CHAT -> R.string.ime_ai_chat_mode_chat
        AiChatMode.RESEARCH -> R.string.ime_ai_chat_mode_research
        AiChatMode.SUMMARIZE -> R.string.ime_ai_chat_mode_summarize
        AiChatMode.COMPARE -> R.string.ime_ai_chat_mode_compare
    }

val AiChatMode.icon: ImageVector
    get() = when (this) {
        AiChatMode.CHAT -> Icons.AutoMirrored.Outlined.Chat
        AiChatMode.RESEARCH -> Icons.Outlined.TravelExplore
        AiChatMode.SUMMARIZE -> Icons.AutoMirrored.Outlined.ShortText
        AiChatMode.COMPARE -> Icons.AutoMirrored.Outlined.CompareArrows
    }

/** What the message box says while it is empty in this mode. */
@get:StringRes
val AiChatMode.hintRes: Int
    get() = when (this) {
        AiChatMode.CHAT -> R.string.ime_ai_chat_composer_hint
        AiChatMode.RESEARCH -> R.string.ime_ai_chat_composer_hint_research
        AiChatMode.SUMMARIZE -> R.string.ime_ai_chat_composer_hint_summarize
        AiChatMode.COMPARE -> R.string.ime_ai_chat_composer_hint_compare
    }

@get:StringRes
val AiAnswerFormat.labelRes: Int
    get() = when (this) {
        AiAnswerFormat.AUTO -> R.string.ime_ai_chat_format_auto
        AiAnswerFormat.PLAIN -> R.string.ime_ai_chat_format_plain
        AiAnswerFormat.MARKDOWN -> R.string.ime_ai_chat_format_markdown
        AiAnswerFormat.BULLETS -> R.string.ime_ai_chat_format_bullets
        AiAnswerFormat.TABLE -> R.string.ime_ai_chat_format_table
        AiAnswerFormat.JSON -> R.string.ime_ai_chat_format_json
        AiAnswerFormat.CODE -> R.string.ime_ai_chat_format_code
    }

val AiAnswerFormat.icon: ImageVector
    get() = when (this) {
        AiAnswerFormat.AUTO -> Icons.Outlined.AutoAwesome
        AiAnswerFormat.PLAIN -> Icons.Outlined.TextFields
        AiAnswerFormat.MARKDOWN -> Icons.AutoMirrored.Outlined.Article
        AiAnswerFormat.BULLETS -> Icons.Outlined.FormatListBulleted
        AiAnswerFormat.TABLE -> Icons.Outlined.TableRows
        AiAnswerFormat.JSON -> Icons.Outlined.DataObject
        AiAnswerFormat.CODE -> Icons.Outlined.Code
    }
