package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.tips.Heart
import com.wasimaster.wmkeyboard.app.tips.SupportKoriCard
import com.wasimaster.wmkeyboard.app.tips.TipJarCard
import com.wasimaster.wmkeyboard.app.tips.TipPurchase
import com.wasimaster.wmkeyboard.app.tips.heartPath
import com.wasimaster.wmkeyboard.app.tips.rememberTipJar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlin.math.absoluteValue
import kotlin.math.sin

/**
 * About › Supporters: the people who tipped or joined on SupportKori, and the
 * people who tipped through Google Play in the Play build.
 *
 * The list the APK was built with (`assets/supporters.json`, refreshed by
 * `docs/scripts/sync-supporters.mjs` before each release build) shows
 * straight away and is all an offline phone, or the build with no internet
 * permission, ever sees. A snapshot goes stale within days of a release, so
 * the GitHub and Play builds also fetch the live list from the docs site as
 * the screen opens; the F-Droid build keeps to its rule that nothing leaves
 * the phone unasked and fetches only when the button is pressed. The Play
 * build's tip credit (see [com.wasimaster.wmkeyboard.app.tips.TipCredits])
 * exists because the user tipped and typed a name to be listed.
 *
 * How the screen asks for support depends on the channel. Play's Payments
 * policy forbids linking out to SupportKori from the Play build, so that build
 * gets the Play billing tip jar instead ([ChannelFeatures.SUPPORTKORI_LINK]).
 *
 * The list here is names only. Amounts and messages come back from the API
 * too, for the website's list, and are deliberately not parsed.
 */
private const val SUPPORTERS_URL = "$DOCS_URL/api/supporters"
private const val SUPPORTERS_ASSET = "supporters.json"

internal data class Supporter(val name: String, val member: Boolean, val at: String)

private sealed interface RefreshState {
    data object Idle : RefreshState
    data object Checking : RefreshState
    data class Added(val count: Int) : RefreshState
    data object Failed : RefreshState
}

/** `{ supporters: [{ name, kind, at }] }`, the shape both the asset and the API use. */
internal fun parseSupporters(json: String): List<Supporter> {
    val array = JSONObject(json).optJSONArray("supporters") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val name = item.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        Supporter(name, member = item.optString("kind") == "member", at = item.optString("at"))
    }
}

private fun bundledSupporters(context: Context): List<Supporter> = runCatching {
    context.assets.open(SUPPORTERS_ASSET).use { parseSupporters(it.readBytes().decodeToString()) }
}.getOrDefault(emptyList())

private fun dateOf(iso: String): String = runCatching {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(iso)))
}.getOrDefault("")

@Composable
internal fun SupportersScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jar = rememberTipJar()
    var supporters by remember { mutableStateOf<List<Supporter>?>(null) }
    // Names tipped from this screen, shown at once rather than after the
    // server has listed them and someone has pressed Check.
    var justAdded by remember { mutableStateOf<List<Supporter>>(emptyList()) }
    var state by remember { mutableStateOf<RefreshState>(RefreshState.Idle) }

    val check: suspend () -> Unit = {
        state = RefreshState.Checking
        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                parseSupporters(
                    ToolHttp.get(SUPPORTERS_URL, source = NetSource.SUPPORTERS, route = "/api/supporters"),
                )
            }.getOrNull()
        }
        if (fresh == null) {
            state = RefreshState.Failed
        } else {
            val known = (supporters.orEmpty() + justAdded).map { it.name }.toSet()
            state = RefreshState.Added(fresh.count { it.name !in known })
            supporters = fresh
        }
    }
    LaunchedEffect(Unit) {
        supporters = withContext(Dispatchers.IO) { bundledSupporters(context) }
        // Checked as the screen opens, in the builds that may (see the file
        // note): a bundled list is only as fresh as the last release build.
        if (InternetPermission.granted && !BuildConfig.ENABLE_FDROID) check()
    }
    val purchase by jar.purchase.collectAsStateWithLifecycle()
    LaunchedEffect(purchase) {
        val thanked = purchase as? TipPurchase.Thanked ?: return@LaunchedEffect
        val name = thanked.name ?: return@LaunchedEffect
        justAdded = listOf(Supporter(name, member = false, at = Instant.now().toString())) + justAdded
    }

    val list = supporters
    val shown = list?.let { known -> justAdded.filterNot { new -> known.any { it.name == new.name } } + known }
    SupportersHero(count = shown?.size)

    if (ChannelFeatures.SUPPORTKORI_LINK) SupportKoriCard() else TipJarCard(jar)

    shown ?: return
    SettingsGroup(stringResource(R.string.supporters_list_title), info = stringResource(R.string.supporters_intro)) {
        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.supporters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        shown.forEachIndexed { index, supporter ->
            item(key = supporter.at + supporter.name) {
                SupporterRow(
                    supporter = supporter,
                    fresh = supporter in justAdded,
                    entranceDelay = (index.coerceAtMost(ENTRANCE_ROWS) * ROW_STAGGER_MS),
                )
            }
        }
    }

    // A build without the internet permission has nothing to fetch with, so
    // it shows the list it shipped with and no button that can only fail.
    if (!InternetPermission.granted) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        val checking = state == RefreshState.Checking
        OutlinedButton(
            enabled = !checking,
            shape = RoundedCornerShape(16.dp),
            onClick = { scope.launch { check() } },
        ) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(if (checking) spin() else 0f),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.supporters_check_button))
        }
        Spacer(Modifier.height(8.dp))
        val message = when (val s = state) {
            RefreshState.Idle -> stringResource(R.string.supporters_check_hint)
            RefreshState.Checking -> stringResource(R.string.supporters_checking)
            RefreshState.Failed -> stringResource(R.string.supporters_check_failed)
            is RefreshState.Added ->
                if (s.count == 0) stringResource(R.string.supporters_none_new)
                else pluralStringResource(R.plurals.supporters_added, s.count, s.count)
        }
        AnimatedContent(
            targetState = message,
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) },
            label = "check-message",
        ) {
            CaptionText(it)
        }
    }
}

/**
 * The top of the screen: a heart that beats, a few small ones drifting up
 * from behind it, and how many people the list holds. The count rolls up
 * from zero when the list first loads.
 */
@Composable
private fun SupportersHero(count: Int?) {
    val still = LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    val warm = listOf(Color(0xFFFF8A80), Color(0xFFF06292), Color(0xFFBA68C8))
    val beat = if (still) 1f else heartbeat()
    val drift = if (still) 0f else drift()
    val shown by animateIntAsState(count ?: 0, tween(if (still) 0 else 900, easing = FastOutSlowInEasing), label = "count")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)))
            .padding(vertical = 24.dp, horizontal = 20.dp),
    ) {
        // Drifting hearts, drawn behind the text. Each has its own lane,
        // size and phase, so they never move as one sheet.
        Canvas(Modifier.matchParentSize()) {
            if (still) return@Canvas
            DRIFTERS.forEach { d ->
                val t = (drift + d.phase) % 1f
                val heart = d.size.dp.toPx()
                val x = size.width * d.lane + sin(t * 6.28f * d.sway) * 14.dp.toPx()
                val y = size.height * (1f - t) - heart
                translate(x - heart / 2, y) {
                    drawPath(
                        heartPath(heart, heart),
                        color = warm[d.colour],
                        alpha = (sin(t * 3.14f) * 0.35f).absoluteValue,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Heart(
                brush = Brush.linearGradient(warm),
                modifier = Modifier.size(56.dp).scale(beat),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    count == null -> " "
                    count == 0 -> stringResource(R.string.supporters_hero_none)
                    else -> pluralStringResource(R.plurals.supporters_hero_count, shown, shown)
                },
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.supporters_hero_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

/** Lub-dub, then a rest: two quick swells and a long pause, once every 1.4 s. */
@Composable
private fun heartbeat(): Float {
    val transition = rememberInfiniteTransition(label = "heartbeat")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1400
                1f at 0
                1.12f at 120
                1f at 260
                1.07f at 380
                1f at 560
            },
        ),
        label = "heartbeat-scale",
    )
    return scale
}

/** 0 to 1 over nine seconds, forever; the drifting hearts' shared clock. */
@Composable
private fun drift(): Float {
    val transition = rememberInfiniteTransition(label = "drift")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift-t",
    )
    return t
}

/** The refresh arrow's turn while a check is running. */
@Composable
private fun spin(): Float {
    val transition = rememberInfiniteTransition(label = "spin")
    val degrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "spin-degrees",
    )
    return degrees
}

private class Drifter(val lane: Float, val phase: Float, val size: Int, val sway: Float, val colour: Int)

private val DRIFTERS = listOf(
    Drifter(lane = 0.12f, phase = 0.0f, size = 14, sway = 1.2f, colour = 0),
    Drifter(lane = 0.3f, phase = 0.55f, size = 10, sway = 0.8f, colour = 1),
    Drifter(lane = 0.72f, phase = 0.25f, size = 16, sway = 1.0f, colour = 2),
    Drifter(lane = 0.88f, phase = 0.75f, size = 11, sway = 1.4f, colour = 1),
    Drifter(lane = 0.5f, phase = 0.4f, size = 9, sway = 0.6f, colour = 0),
)

/**
 * One supporter: a round initial in a colour picked from their name (so the
 * same person always gets the same one), the name, and what and when.
 * A row that was just tipped from this screen glows once in the warm colour.
 * Rows rise in one after another on first draw, the first dozen only.
 */
@Composable
private fun SupporterRow(supporter: Supporter, fresh: Boolean, entranceDelay: Int) {
    val still = LocalReduceMotion.current
    val entrance = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!still) entrance.animateTo(1f, tween(360, delayMillis = entranceDelay, easing = FastOutSlowInEasing))
    }
    val glow = remember { Animatable(if (fresh && !still) 1f else 0f) }
    LaunchedEffect(fresh) {
        if (fresh && !still) glow.animateTo(0f, tween(2600, delayMillis = 400))
    }
    val kind = stringResource(if (supporter.member) R.string.supporters_kind_member else R.string.supporters_kind_tip)
    val date = if (fresh) stringResource(R.string.supporters_just_now) else dateOf(supporter.at)
    val (from, to) = AVATAR_WASHES[(supporter.name.hashCode().absoluteValue) % AVATAR_WASHES.size]

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 16.dp.toPx()
            }
            .fillMaxWidth()
            .background(Color(0xFFF06292).copy(alpha = 0.22f * glow.value))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(from, to))),
        ) {
            Text(
                supporter.name.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(supporter.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                if (date.isEmpty()) kind else stringResource(R.string.supporters_row_subtitle, kind, date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Avatar washes, theme-independent like the tier tiles: white initials read on each. */
private val AVATAR_WASHES = listOf(
    Color(0xFF7986CB) to Color(0xFF9575CD),
    Color(0xFF4DB6AC) to Color(0xFF4FC3F7),
    Color(0xFFFFB74D) to Color(0xFFF06292),
    Color(0xFF81C784) to Color(0xFF26A69A),
    Color(0xFFBA68C8) to Color(0xFFF06292),
    Color(0xFF64B5F6) to Color(0xFF7986CB),
)

private const val ENTRANCE_ROWS = 12
private const val ROW_STAGGER_MS = 40
