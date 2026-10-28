package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TravelExplore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Handyman
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.core.tools.AiToolApproval
import com.wasimaster.wmkeyboard.core.tools.AiTools
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.serialization.json.Json

/*
 * How a chat answer came to be, over the answer (#882): what the model
 * reasoned, then each tool it called, as steps on one line that runs down
 * into the answer. Each step is folded to its header by default, since the
 * answer is what the user came for; a tap opens it.
 *
 * Shared by the keyboard's chat and the chat screen in the settings app, like
 * [ChatMarkdown] and [ChatToolUses], so colours and links come in rather than
 * off a theme. [ChatToolUses] stays the one-line record the AI panel's actions
 * draw over a result; this is the chat's longer form of it.
 */

/**
 * The steps before an answer. [reasoning] is every passage the model thought,
 * drawn as Markdown, maths included, when opened; [reasoningLive] says it is
 * still thinking. [uses] are its tool calls, oldest first, each showing the
 * arguments the model wrote and what came back once opened, with the pages a
 * search found. A call asking to be allowed shows its question open; a save
 * to the AI memory shows its line with [onUndoMemory]'s Undo.
 */
@Composable
fun ChatChain(
    reasoning: String,
    reasoningLive: Boolean,
    uses: List<AiToolActivity>,
    colors: ChatMarkdownColors,
    errorColor: Color,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    /** The size the reasoning's Markdown is set in, the answer's own. */
    textSize: TextUnit = 13.sp,
    onApproval: ((AiToolApproval) -> Unit)? = null,
    animate: Boolean = true,
    onUndoMemory: ((Int) -> Unit)? = null,
) {
    val hasReasoning = reasoning.isNotBlank() || reasoningLive
    if (!hasReasoning && uses.isEmpty()) return
    val steps = (if (hasReasoning) 1 else 0) + uses.size
    Column(modifier.fillMaxWidth()) {
        if (hasReasoning) {
            ChainStep(colors, first = true) {
                ReasoningStep(reasoning, reasoningLive, colors, fontSize, textSize, animate)
            }
        }
        uses.forEachIndexed { index, use ->
            key(index) {
                ChainStep(colors, first = !hasReasoning && index == 0) {
                    ToolStep(use, colors, errorColor, fontSize, onOpen, onCopy, animate)
                    if (use.awaitingApproval) ApprovalCard(use, colors, fontSize, onApproval)
                    val note = use.memory
                    if (note != null && !use.running && !use.failed) {
                        MemorySaveLine(note, colors, fontSize, onUndoMemory?.let { undo -> { undo(index) } })
                    }
                }
            }
        }
        // The line runs on a little past the last step, down to the answer.
        if (steps > 0) Spacer(Modifier.size(STEP_GAP))
    }
}

/**
 * One step: its content with the line down the left, and a dot where the step
 * branches off it. The line runs from the step above, past the dot, down to
 * the next step or the answer.
 */
@Composable
private fun ChainStep(colors: ChatMarkdownColors, first: Boolean, content: @Composable () -> Unit) {
    val rail = colors.dim.copy(alpha = colors.dim.alpha * RAIL_ALPHA)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = RAIL_X.toPx()
                val dotY = DOT_Y.toPx()
                val radius = DOT_RADIUS.toPx()
                val stroke = RAIL_WIDTH.toPx()
                if (!first) drawLine(rail, Offset(x, 0f), Offset(x, dotY - radius), stroke)
                drawLine(rail, Offset(x, dotY + radius), Offset(x, size.height), stroke)
                drawCircle(colors.dim, radius, Offset(x, dotY))
            }
            .padding(start = RAIL_INDENT, bottom = STEP_GAP),
    ) { content() }
}

/** A step's header: its mark, its line, a caption under it, and a chevron that turns as it opens. */
@Composable
private fun StepHeader(
    mark: ImageVector?,
    title: String,
    caption: String,
    ink: Color,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    expanded: Boolean,
    animate: Boolean,
    onToggle: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (animate) tween(EXPAND_MS, easing = FastOutSlowInEasing) else snap(),
        label = "chainChevron",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                onClickLabel = stringResource(
                    if (expanded) R.string.ime_ai_chat_tool_hide_desc else R.string.ime_ai_chat_tool_show_desc,
                ),
                onClick = onToggle,
            )
            .padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark == null) {
            CircularProgressIndicator(color = colors.dim, strokeWidth = 1.5.dp, modifier = Modifier.size(11.dp))
        } else {
            Icon(mark, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                title,
                color = ink,
                fontSize = fontSize,
                lineHeight = fontSize * LINE,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption.isNotEmpty()) {
                Text(
                    caption,
                    color = colors.dim,
                    fontSize = fontSize * SMALLER,
                    lineHeight = fontSize * SMALLER * LINE,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = ink,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { rotationZ = chevron },
        )
    }
}

/** What opens under a header, unrolling when [animate] says the keyboard's motion is on. */
@Composable
private fun StepBody(expanded: Boolean, animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) {
        if (expanded) content()
        return
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(EXPAND_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(EXPAND_MS)),
        exit = shrinkVertically(tween(COLLAPSE_MS), shrinkTowards = Alignment.Top) + fadeOut(tween(COLLAPSE_MS)),
        label = "chainStep",
    ) { content() }
}

/**
 * The model's reasoning, folded to "Reasoning" (or "Reasoning…" with a
 * spinner while it goes on) and drawn as Markdown, maths and all, when opened.
 * Dimmed, so it never reads as the answer.
 */
@Composable
private fun ReasoningStep(
    reasoning: String,
    live: Boolean,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    textSize: TextUnit,
    animate: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    StepHeader(
        mark = if (live) null else Icons.Outlined.Psychology,
        title = stringResource(if (live) R.string.ime_ai_chat_thinking else R.string.ime_ai_chat_reasoning),
        caption = "",
        ink = colors.dim,
        colors = colors,
        fontSize = fontSize,
        expanded = expanded,
        animate = animate,
    ) { expanded = !expanded }
    StepBody(expanded, animate) {
        if (reasoning.isNotBlank()) {
            ChatMarkdown(
                reasoning,
                ChatMarkdownColors(
                    text = colors.dim,
                    dim = colors.dim.copy(alpha = colors.dim.alpha * 0.8f),
                    codeBackground = colors.codeBackground,
                    link = colors.link,
                ),
                modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp),
                fontSize = textSize,
            )
        }
    }
}

/**
 * One tool call: what it did, in words, over the tool's own name ("web_search
 * call"), folded. Opened, the arguments the model wrote, then what came back:
 * the pages for a search or a read, the text the model was handed for any
 * other tool, or why the call failed.
 */
@Composable
private fun ToolStep(
    use: AiToolActivity,
    colors: ChatMarkdownColors,
    errorColor: Color,
    fontSize: TextUnit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    animate: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val mark = when {
        use.running -> null
        use.failed -> Icons.Outlined.ErrorOutline
        use.tool == AiTools.WEB_FETCH && !use.isMcp -> Icons.Outlined.Language
        use.tool == AiTools.RESEARCH_PLAN && !use.isMcp -> Icons.Outlined.Checklist
        use.tool == AiTools.RESEARCH_REVIEW && !use.isMcp -> Icons.Outlined.TravelExplore
        !use.isMcp && use.tool in SEARCH_TOOLS -> Icons.Outlined.Search
        else -> Icons.Outlined.Handyman
    }
    StepHeader(
        mark = mark,
        title = toolLine(use),
        caption = stringResource(R.string.ime_ai_chat_tool_call, use.tool),
        ink = if (use.failed) errorColor else colors.dim,
        colors = colors,
        fontSize = fontSize,
        expanded = expanded,
        animate = animate,
    ) { expanded = !expanded }
    // A research plan is the run's to-do list: shown open, ticked as it goes,
    // rather than folded away like the steps it leads to. While it asks to be
    // approved the card under it shows it instead.
    val plan = use.tool == AiTools.RESEARCH_PLAN && !use.isMcp
    if (plan && !use.awaitingApproval && use.output.isNotEmpty()) {
        ResearchChecklist(use.output, colors, fontSize * SMALLER, Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp))
        return
    }
    StepBody(expanded, animate) {
        Column(
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val arguments = remember(use.arguments) { prettyJson(use.arguments) }
            if (arguments.isNotEmpty()) {
                DetailBlock(stringResource(R.string.ime_ai_chat_tool_arguments), arguments, colors, fontSize, onCopy)
            }
            when {
                use.failed -> Text(
                    use.error,
                    color = errorColor.copy(alpha = errorColor.alpha * 0.85f),
                    fontSize = fontSize * SMALLER,
                    lineHeight = fontSize * SMALLER * LINE,
                )
                use.running -> Unit
                use.sources.isNotEmpty() -> {
                    DetailLabel(stringResource(R.string.ime_ai_chat_tool_output), colors, fontSize)
                    for (source in use.sources) {
                        SourceLine(source, use.tool == AiTools.WEB_FETCH, colors, fontSize, onOpen, onCopy)
                    }
                }
                use.output.isNotEmpty() ->
                    DetailBlock(stringResource(R.string.ime_ai_chat_tool_output), use.output, colors, fontSize, onCopy)
                // A record kept before outputs were, or a search that found nothing.
                use.tool == AiTools.WEB_SEARCH || use.tool == AiTools.IMAGE_SEARCH -> Text(
                    stringResource(R.string.ime_ai_chat_tool_no_results),
                    color = colors.dim,
                    fontSize = fontSize * SMALLER,
                    lineHeight = fontSize * SMALLER * LINE,
                )
            }
        }
    }
}

@Composable
private fun DetailLabel(label: String, colors: ChatMarkdownColors, fontSize: TextUnit) {
    Text(
        label,
        color = colors.dim,
        fontSize = fontSize * SMALLER,
        lineHeight = fontSize * SMALLER * LINE,
        fontWeight = FontWeight.Medium,
    )
}

/** A labelled block of monospaced text, the way code is shown: arguments, or a tool's output. A tap copies it. */
@Composable
private fun DetailBlock(label: String, text: String, colors: ChatMarkdownColors, fontSize: TextUnit, onCopy: (String) -> Unit) {
    DetailLabel(label, colors, fontSize)
    Text(
        text,
        color = colors.text,
        fontSize = fontSize * SMALLER,
        lineHeight = fontSize * SMALLER * LINE,
        fontFamily = FontFamily.Monospace,
        maxLines = DETAIL_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.codeBackground)
            .clickable(onClickLabel = stringResource(R.string.ime_ai_chat_tool_copy_desc)) { onCopy(text) }
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

private val prettyPrinter = Json { prettyPrint = true }

/** A model's arguments laid out one field to a line; as written when they are not JSON. */
private fun prettyJson(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty() || text == "{}") return ""
    return runCatching { prettyPrinter.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(text)) }
        .getOrDefault(text)
}

private val SEARCH_TOOLS = setOf(AiTools.WEB_SEARCH, AiTools.IMAGE_SEARCH)

/** Where the line runs, how far a step sits in from it, and the dot where a step branches off. */
private val RAIL_X = 6.dp
private val RAIL_INDENT = 18.dp
private val RAIL_WIDTH = 1.5.dp
private val DOT_Y = 10.dp
private val DOT_RADIUS = 3.dp
private const val RAIL_ALPHA = 0.55f
private val STEP_GAP = 4.dp

private const val EXPAND_MS = 200
private const val COLLAPSE_MS = 150
private const val LINE = 1.35f
private const val SMALLER = 0.92f

/** Lines of arguments or output an opened step shows before it cuts them. */
private const val DETAIL_LINES = 14
