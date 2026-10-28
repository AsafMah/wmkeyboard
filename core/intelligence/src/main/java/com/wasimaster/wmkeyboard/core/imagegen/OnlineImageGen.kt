package com.wasimaster.wmkeyboard.core.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import java.io.File
import java.io.IOException
import java.net.URL
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What an online engine is asked to draw: the same request the phone's model gets, less what only a diffusion loop reads. */
data class OnlineImageRequest(
    /** English, with the style's words: what the phone's model would read. */
    val prompt: String,
    val negative: String = "",
    val seed: Long = 0L,
    val count: Int = 1,
    /** Ask the service for its own filter where it has a switch for one. */
    val safety: Boolean = true,
)

/**
 * The sticker generator's online engines: one REST call (or a submit and a
 * poll) per kind on the [com.wasimaster.wmkeyboard.core.providers.ProviderTool.IMAGE_GEN]
 * list, all through [ToolHttp], so every request is in the network activity
 * log as [NetSource.IMAGE_GEN] and no service's SDK is in the app.
 *
 * Every picture is saved where the phone's model saves its own (a PNG in
 * [ImageGenEngine.OUTPUT_DIR]), at most [MAX_SIDE] pixels a side, and
 * reported with the same [ImageGenEvent]s, so the screen draws, picks and
 * cuts out an online sticker the way it does one from the phone. A service
 * that drew on a transparent background keeps it; the screen then skips the
 * background remover.
 */
object OnlineImageGen {

    /** Big enough for the sticker editor and WhatsApp's 512, small enough to hold four in memory. */
    const val MAX_SIDE = 1024

    /** What every service but the self-hosted ones is asked for: square, at a size they all take. */
    private const val SIZE = 1024

    private const val POLL_MS = 1_500L
    private const val HORDE_POLL_MS = 4_000L
    private const val GIVE_UP_MS = 10 * 60_000L
    private const val TIMEOUT_MS = 180_000

    private val json = Json { ignoreUnknownKeys = true }

    /** A picture as a service handed it back. */
    private sealed interface Drawn {
        class Bytes(val bytes: ByteArray) : Drawn
        class Link(val url: String, val headers: Map<String, String> = emptyMap()) : Drawn

        /** The service's own filter held it back. */
        data object Blocked : Drawn
    }

    /** What a draw function reports as it goes: a picture, or a place in a queue. */
    private class Sink(
        val picture: suspend (Drawn) -> Unit,
        val waiting: suspend (position: Int, seconds: Int) -> Unit,
    )

    /**
     * Draws [request] with [row]: [ImageGenEvent.Waiting] while a queue holds
     * it, one [ImageGenEvent.Image] or [ImageGenEvent.Blocked] per picture as
     * each arrives, then [ImageGenEvent.Done], or [ImageGenEvent.Failed] when
     * nothing could be drawn. Cancelling the collection stops it between
     * requests.
     */
    fun generate(context: Context, row: ResolvedProvider, request: OnlineImageRequest): Flow<ImageGenEvent> = flow {
        val out = File(context.cacheDir, ImageGenEngine.OUTPUT_DIR).apply { mkdirs() }
        var variant = 0
        val started = System.currentTimeMillis()
        val sink = Sink(
            picture = { drawn ->
                val bytes = when (drawn) {
                    is Drawn.Bytes -> drawn.bytes
                    is Drawn.Link -> fetch(drawn)
                    Drawn.Blocked -> null
                }
                val index = variant++
                if (bytes == null) {
                    emit(ImageGenEvent.Blocked(index, request.seed + index))
                } else {
                    val file = File(out, "sticker_${System.currentTimeMillis()}_$index.png")
                    save(bytes, file)
                    emit(ImageGenEvent.Image(index, file, request.seed + index, System.currentTimeMillis() - started))
                }
            },
            waiting = { position, seconds -> emit(ImageGenEvent.Waiting(position, seconds)) },
        )
        try {
            draw(row, request.copy(count = request.count.coerceIn(1, ImageGenEngine.MAX_VARIANTS)), sink)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Pictures that arrived before the failure stay; only a run with none fails.
            if (variant == 0) {
                emit(ImageGenEvent.Failed(ToolHttp.friendlyMessage(context, e)))
                return@flow
            }
        }
        if (variant == 0) {
            emit(ImageGenEvent.Failed("no picture came back"))
            return@flow
        }
        emit(ImageGenEvent.Done)
    }.flowOn(Dispatchers.IO)

    /** The model a blank model field means for [typeId]; null where the service picks. */
    fun defaultModel(typeId: String): String? = when (typeId) {
        ProviderTypes.OPENAI_IMAGE.id -> "gpt-image-1-mini"
        ProviderTypes.GEMINI_IMAGE.id -> "gemini-2.5-flash-image"
        ProviderTypes.XAI_IMAGE.id -> "grok-imagine-image-2.0"
        ProviderTypes.STABILITY.id -> "core"
        ProviderTypes.BFL.id -> "flux-2-klein-4b"
        ProviderTypes.REPLICATE.id -> "black-forest-labs/flux-schnell"
        ProviderTypes.FAL.id -> "fal-ai/flux/schnell"
        ProviderTypes.TOGETHER.id -> "black-forest-labs/FLUX.1-schnell"
        ProviderTypes.HUGGING_FACE.id -> "stabilityai/stable-diffusion-3-medium-diffusers"
        ProviderTypes.CLOUDFLARE_AI.id -> "@cf/black-forest-labs/flux-1-schnell"
        else -> null
    }

    /** Draws on a transparent background itself, so the background remover has nothing to do. */
    fun transparent(row: ResolvedProvider): Boolean =
        row.typeId == ProviderTypes.OPENAI_IMAGE.id && openAiTransparent(model(row))

    /** Where [row]'s prompt goes, for "Sends your prompt to …". */
    fun host(row: ResolvedProvider): String = runCatching { URL(endpointBase(row)).host }.getOrDefault("")

    private fun model(row: ResolvedProvider): String = row.model.ifBlank { defaultModel(row.typeId).orEmpty() }

    private fun endpointBase(row: ResolvedProvider): String = when (row.typeId) {
        ProviderTypes.POLLINATIONS.id -> if (row.apiKey.isBlank()) POLLINATIONS_FREE else POLLINATIONS
        ProviderTypes.AI_HORDE.id -> HORDE
        ProviderTypes.OPENAI_IMAGE.id -> OPENAI
        ProviderTypes.GEMINI_IMAGE.id -> GEMINI
        ProviderTypes.XAI_IMAGE.id -> XAI
        ProviderTypes.STABILITY.id -> STABILITY
        ProviderTypes.BFL.id -> BFL
        ProviderTypes.RECRAFT.id -> RECRAFT
        ProviderTypes.REPLICATE.id -> REPLICATE
        ProviderTypes.FAL.id -> FAL
        ProviderTypes.TOGETHER.id -> TOGETHER
        ProviderTypes.HUGGING_FACE.id -> HUGGING_FACE
        ProviderTypes.CLOUDFLARE_AI.id -> CLOUDFLARE
        else -> row.url
    }

    /** Whether [probe] has a request for [typeId] that draws nothing: fal has none. */
    fun probes(typeId: String): Boolean = typeId != ProviderTypes.FAL.id && typeId != ProviderTypes.ON_DEVICE_IMAGE.id

    /**
     * The cheapest request [row] answers with its key: a list of models, the
     * account. Nothing is drawn, so nothing is paid for. Throws on a refusal.
     */
    fun probe(row: ResolvedProvider) {
        val key = row.apiKey
        val bearer = mapOf("Authorization" to "Bearer $key")
        fun get(url: String, headers: Map<String, String>, route: String) =
            ToolHttp.get(url, 20_000, headers, source = NetSource.IMAGE_GEN, route = route)
        when (row.typeId) {
            ProviderTypes.POLLINATIONS.id -> get(
                if (key.isBlank()) "$POLLINATIONS_FREE/models" else "$POLLINATIONS/image/models",
                if (key.isBlank()) emptyMap() else bearer,
                "/models",
            )
            ProviderTypes.AI_HORDE.id -> if (key.isBlank()) {
                get("$HORDE/v2/status/heartbeat", hordeHeaders(""), "/v2/status/heartbeat")
            } else {
                get("$HORDE/v2/find_user", hordeHeaders(key), "/v2/find_user")
            }
            ProviderTypes.OPENAI_IMAGE.id -> get("$OPENAI/models", bearer, "/v1/models")
            ProviderTypes.XAI_IMAGE.id -> get("$XAI/models", bearer, "/v1/models")
            ProviderTypes.GEMINI_IMAGE.id -> get("$GEMINI/models?pageSize=1", mapOf("x-goog-api-key" to key), "/v1/models")
            ProviderTypes.STABILITY.id -> get("$STABILITY/v1/user/balance", bearer, "/v1/user/balance")
            ProviderTypes.BFL.id -> get("$BFL/v1/credits", mapOf("x-key" to key), "/v1/credits")
            ProviderTypes.RECRAFT.id -> get("$RECRAFT/v1/users/me", bearer, "/v1/users/me")
            ProviderTypes.REPLICATE.id -> get("$REPLICATE/v1/account", bearer, "/v1/account")
            ProviderTypes.TOGETHER.id -> get("$TOGETHER/v1/models", bearer, "/v1/models")
            ProviderTypes.HUGGING_FACE.id -> get("https://huggingface.co/api/whoami-v2", bearer, "/api/whoami-v2")
            ProviderTypes.CLOUDFLARE_AI.id -> get(
                "$CLOUDFLARE/accounts/${account(row)}/ai/models/search?per_page=1",
                bearer,
                "/accounts/{account}/ai/models/search",
            )
            ProviderTypes.AUTOMATIC1111.id -> get("${trimmed(row.url)}/sdapi/v1/sd-models", a1111Headers(key), "/sdapi/v1/sd-models")
            ProviderTypes.COMFYUI.id -> get("${trimmed(row.url)}/system_stats", bearerOrNone(key), "/system_stats")
            ProviderTypes.OPENAI_COMPATIBLE_IMAGE.id -> get(
                compatibleBase(row.url) + "/models",
                bearerOrNone(key),
                "/models",
            )
            else -> throw IOException("nothing to test")
        }
    }

    private suspend fun draw(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) = when (row.typeId) {
        ProviderTypes.POLLINATIONS.id -> pollinations(row, r, sink)
        ProviderTypes.AI_HORDE.id -> horde(row, r, sink)
        ProviderTypes.OPENAI_IMAGE.id -> openAiShaped(row, r, sink, "$OPENAI/images/generations", official = true)
        ProviderTypes.XAI_IMAGE.id -> openAiShaped(row, r, sink, "$XAI/images/generations", official = false)
        ProviderTypes.OPENAI_COMPATIBLE_IMAGE.id ->
            openAiShaped(row, r, sink, compatibleBase(row.url) + "/images/generations", official = false)
        ProviderTypes.GEMINI_IMAGE.id -> gemini(row, r, sink)
        ProviderTypes.STABILITY.id -> stability(row, r, sink)
        ProviderTypes.BFL.id -> bfl(row, r, sink)
        ProviderTypes.RECRAFT.id -> recraft(row, r, sink)
        ProviderTypes.REPLICATE.id -> replicate(row, r, sink)
        ProviderTypes.FAL.id -> fal(row, r, sink)
        ProviderTypes.TOGETHER.id -> together(row, r, sink)
        ProviderTypes.HUGGING_FACE.id -> huggingFace(row, r, sink)
        ProviderTypes.CLOUDFLARE_AI.id -> cloudflare(row, r, sink)
        ProviderTypes.AUTOMATIC1111.id -> automatic1111(row, r, sink)
        ProviderTypes.COMFYUI.id -> comfy(row, r, sink)
        else -> throw IOException("${row.typeId} does not draw pictures")
    }

    // ---- Free services ---------------------------------------------------

    /**
     * GET, the prompt in the path. Keyless, the old host and the model it
     * picks; with a key, the newer API and the model named, if any. The
     * route stops before the prompt.
     */
    private suspend fun pollinations(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val keyed = row.apiKey.isNotBlank()
        val prompt = pathPart(r.prompt)
        repeat(r.count) { i ->
            currentCoroutineContext().ensureActive()
            val query = buildList {
                add("width=$SIZE")
                add("height=$SIZE")
                add("seed=${r.seed + i}")
                if (row.model.isNotBlank()) add("model=${ToolHttp.encode(row.model)}")
                if (r.safety) add("safe=true")
                if (keyed) {
                    // Only the GPT Image models take it; any other answers 400.
                    if (row.model.startsWith("gptimage") || row.model.contains("gpt-image")) add("transparent=true")
                } else {
                    add("nologo=true")
                    add("private=true")
                    if (r.negative.isNotBlank()) add("negative_prompt=${ToolHttp.encode(r.negative)}")
                }
            }.joinToString("&")
            val url = if (keyed) "$POLLINATIONS/image/$prompt?$query" else "$POLLINATIONS_FREE/prompt/$prompt?$query"
            val got = ToolHttp.bytes(
                url,
                headers = if (keyed) mapOf("Authorization" to "Bearer ${row.apiKey}") else emptyMap(),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.IMAGE_GEN,
                route = if (keyed) "/image" else "/prompt",
            )
            sink.picture(Drawn.Bytes(got.body))
        }
    }

    /**
     * Submit, poll the queue, then fetch. Anonymous is the documented
     * all-zeros key. The negative prompt rides after `###`, which is how
     * the Horde takes one.
     */
    private suspend fun horde(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val headers = hordeHeaders(row.apiKey)
        val body = buildJsonObject {
            put("prompt", if (r.negative.isBlank()) r.prompt else "${r.prompt} ### ${r.negative}")
            putJsonObject("params") {
                put("width", HORDE_SIZE)
                put("height", HORDE_SIZE)
                put("n", r.count)
                put("seed", r.seed.toString())
                put("sampler_name", "k_euler_a")
                put("cfg_scale", 7)
                put("steps", 25)
            }
            put("nsfw", !r.safety)
            put("censor_nsfw", r.safety)
            put("r2", true)
            put("shared", false)
            if (row.model.isNotBlank()) putJsonArray("models") { add(JsonPrimitive(row.model)) }
        }
        val submitted = obj(
            ToolHttp.postJson("$HORDE/v2/generate/async", body.toString(), TIMEOUT_MS, headers, NetSource.IMAGE_GEN, "/v2/generate/async"),
        )
        val id = submitted.string("id") ?: throw IOException(submitted.string("message") ?: "the Horde took no job")
        val deadline = System.currentTimeMillis() + GIVE_UP_MS
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val check = obj(ToolHttp.get("$HORDE/v2/generate/check/$id", 20_000, headers, source = NetSource.IMAGE_GEN, route = "/v2/generate/check"))
                if (check.bool("faulted") == true) throw IOException("the Horde could not draw it")
                if (check.bool("is_possible") == false) throw IOException("no Horde worker can draw this model now")
                if (check.bool("done") == true) break
                sink.waiting(check.int("queue_position") ?: 0, check.int("wait_time") ?: 0)
                if (System.currentTimeMillis() > deadline) throw IOException("the Horde queue is too long right now")
                delay(HORDE_POLL_MS)
            }
        } catch (e: Throwable) {
            // Gives the place back rather than leaving volunteers drawing for nobody.
            runCatching {
                ToolHttp.exchange("$HORDE/v2/generate/status/$id", "DELETE", headers = headers, source = NetSource.IMAGE_GEN, route = "/v2/generate/status") {}
            }
            throw e
        }
        val status = obj(ToolHttp.get("$HORDE/v2/generate/status/$id", 30_000, headers, source = NetSource.IMAGE_GEN, route = "/v2/generate/status"))
        for (g in status.array("generations")) {
            val o = g as? JsonObject ?: continue
            if (o.bool("censored") == true) {
                sink.picture(Drawn.Blocked)
                continue
            }
            val img = o.string("img") ?: continue
            sink.picture(if (img.startsWith("http")) Drawn.Link(img) else Drawn.Bytes(Base64.decode(img, Base64.DEFAULT)))
        }
    }

    private fun hordeHeaders(key: String) = mapOf(
        "apikey" to key.ifBlank { HORDE_ANONYMOUS },
        "Client-Agent" to "WMKeyboard:${BuildConfig.VERSION_NAME}:github.com/WasiMaster/WMKeyboard",
    )

    // ---- OpenAI's shape: OpenAI, xAI, and anything compatible -----------

    private suspend fun openAiShaped(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink, url: String, official: Boolean) {
        val model = model(row)
        val gpt = model.startsWith("gpt-image") || model.startsWith("chatgpt-image")
        val body = buildJsonObject {
            if (model.isNotBlank()) put("model", model)
            put("prompt", r.prompt)
            put("n", r.count)
            when {
                gpt -> {
                    put("size", "${SIZE}x$SIZE")
                    put("output_format", "png")
                    if (openAiTransparent(model)) put("background", "transparent")
                }
                // xAI takes neither a size nor a format, only how the picture comes back.
                row.typeId == ProviderTypes.XAI_IMAGE.id -> put("response_format", "b64_json")
                else -> {
                    put("size", "${SIZE}x$SIZE")
                    put("response_format", "b64_json")
                }
            }
        }
        val answer = obj(
            ToolHttp.postJson(url, body.toString(), TIMEOUT_MS, bearerOrNone(row.apiKey), NetSource.IMAGE_GEN, route(url, official)),
        )
        val data = answer.array("data")
        if (data.isEmpty()) throw IOException("the service sent no picture")
        for (item in data) {
            val o = item as? JsonObject ?: continue
            val b64 = o.string("b64_json")
            val link = o.string("url")
            when {
                b64 != null -> sink.picture(Drawn.Bytes(Base64.decode(b64, Base64.DEFAULT)))
                link != null -> sink.picture(Drawn.Link(link))
            }
        }
    }

    /** GPT Image models that take a transparent background; the 2.x previews are left to draw on their own. */
    private fun openAiTransparent(model: String): Boolean =
        model.startsWith("gpt-image-1") || model.startsWith("gpt-image-2.5")

    // ---- Google ------------------------------------------------------------

    /** One picture per call: the reply's parts carry it as inline data. */
    private suspend fun gemini(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val model = model(row)
        repeat(r.count) {
            currentCoroutineContext().ensureActive()
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", r.prompt) }) } })
                }
                putJsonObject("generationConfig") { putJsonObject("imageConfig") { put("aspectRatio", "1:1") } }
            }
            val answer = obj(
                ToolHttp.postJson(
                    "$GEMINI/models/${model}:generateContent",
                    body.toString(),
                    TIMEOUT_MS,
                    mapOf("x-goog-api-key" to row.apiKey),
                    NetSource.IMAGE_GEN,
                    "/v1/models/{model}:generateContent",
                ),
            )
            val candidate = answer.array("candidates").firstOrNull() as? JsonObject
            val parts = (candidate?.get("content") as? JsonObject)?.array("parts").orEmpty()
            val data = parts.firstNotNullOfOrNull { part ->
                val o = part as? JsonObject
                ((o?.get("inlineData") ?: o?.get("inline_data")) as? JsonObject)?.string("data")
            }
            when {
                data != null -> sink.picture(Drawn.Bytes(Base64.decode(data, Base64.DEFAULT)))
                // A refusal comes back as a reason, not an error.
                candidate?.string("finishReason")?.let { it.contains("SAFETY") || it.contains("PROHIBITED") } == true ||
                    (answer["promptFeedback"] as? JsonObject)?.string("blockReason") != null -> sink.picture(Drawn.Blocked)
                else -> throw IOException("Gemini sent no picture")
            }
        }
    }

    // ---- Image companies ---------------------------------------------------

    /**
     * Multipart, the picture back as the body. The model field picks the
     * service: `core`, `ultra`, or an SD 3.5 model (`sd3.5-large`), which
     * goes to the `sd3` service by name.
     */
    private suspend fun stability(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val model = model(row)
        val sd3 = model.startsWith("sd3")
        val service = if (sd3) "sd3" else model
        repeat(r.count) { i ->
            currentCoroutineContext().ensureActive()
            val fields = buildList {
                add("prompt" to r.prompt)
                add("aspect_ratio" to "1:1")
                add("output_format" to "png")
                add("seed" to ((r.seed + i) % STABILITY_SEEDS).toString())
                if (r.negative.isNotBlank()) add("negative_prompt" to r.negative)
                if (sd3 && model != "sd3") add("model" to model)
            }
            val got = ToolHttp.multipartBytes(
                "$STABILITY/v2beta/stable-image/generate/$service",
                fields,
                headers = mapOf("Authorization" to "Bearer ${row.apiKey}", "Accept" to "image/*"),
                wantHeaders = setOf("finish-reason"),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.IMAGE_GEN,
                route = "/v2beta/stable-image/generate/$service",
            )
            // A filtered picture comes back blurred, which is no sticker.
            sink.picture(if (got.headers["finish-reason"] == "CONTENT_FILTERED") Drawn.Blocked else Drawn.Bytes(got.body))
        }
    }

    /** Submit, poll the `polling_url` it gives, fetch the signed sample (valid ten minutes). */
    private suspend fun bfl(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val model = model(row)
        val headers = mapOf("x-key" to row.apiKey, "Accept" to "application/json")
        repeat(r.count) { i ->
            currentCoroutineContext().ensureActive()
            val body = buildJsonObject {
                put("prompt", r.prompt)
                put("width", SIZE)
                put("height", SIZE)
                put("seed", r.seed + i)
                put("output_format", "png")
                // 0 strictest, 6 least; 2 is the service's own default.
                put("safety_tolerance", if (r.safety) 2 else 5)
            }
            val submitted = obj(ToolHttp.postJson("$BFL/v1/$model", body.toString(), TIMEOUT_MS, headers, NetSource.IMAGE_GEN, "/v1/{model}"))
            val polling = submitted.string("polling_url") ?: "$BFL/v1/get_result?id=${submitted.string("id")}"
            val deadline = System.currentTimeMillis() + GIVE_UP_MS
            while (true) {
                currentCoroutineContext().ensureActive()
                val result = obj(ToolHttp.get(polling, 20_000, headers, source = NetSource.IMAGE_GEN, route = "/v1/get_result"))
                when (val status = result.string("status")) {
                    "Ready" -> {
                        val sample = (result["result"] as? JsonObject)?.string("sample") ?: throw IOException("FLUX sent no picture")
                        sink.picture(Drawn.Link(sample))
                        break
                    }
                    "Request Moderated", "Content Moderated" -> {
                        sink.picture(Drawn.Blocked)
                        break
                    }
                    "Error", "Failed", "Task not found" -> throw IOException("FLUX: $status")
                }
                if (System.currentTimeMillis() > deadline) throw IOException("FLUX took too long")
                delay(POLL_MS)
            }
        }
    }

    private suspend fun recraft(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val body = buildJsonObject {
            put("prompt", r.prompt)
            if (row.model.isNotBlank()) put("model", row.model)
            put("n", r.count)
            put("size", "${SIZE}x$SIZE")
            put("response_format", "b64_json")
            put("image_format", "png")
            put("random_seed", r.seed % Int.MAX_VALUE)
            if (r.negative.isNotBlank()) put("negative_prompt", r.negative)
        }
        val answer = obj(
            ToolHttp.postJson(
                "$RECRAFT/v1/images/generations", body.toString(), TIMEOUT_MS,
                mapOf("Authorization" to "Bearer ${row.apiKey}"), NetSource.IMAGE_GEN, "/v1/images/generations",
            ),
        )
        for (item in answer.array("data")) {
            val o = item as? JsonObject ?: continue
            o.string("b64_json")?.let { sink.picture(Drawn.Bytes(Base64.decode(it, Base64.DEFAULT))) }
                ?: o.string("url")?.let { sink.picture(Drawn.Link(it)) }
        }
    }

    // ---- Hosts of many models ---------------------------------------------

    /**
     * An official model by `owner/name`, any other by `owner/name:version`.
     * Waits up to a minute on the request itself, then polls. Output files
     * need the key to be fetched.
     */
    private suspend fun replicate(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val model = model(row)
        val auth = mapOf("Authorization" to "Bearer ${row.apiKey}")
        val input = buildJsonObject {
            put("prompt", r.prompt)
            put("num_outputs", r.count)
            put("aspect_ratio", "1:1")
            put("output_format", "png")
            put("seed", r.seed % Int.MAX_VALUE)
            if (r.negative.isNotBlank()) put("negative_prompt", r.negative)
            if (!r.safety) put("disable_safety_checker", true)
        }
        val versioned = model.contains(':')
        val url = if (versioned) "$REPLICATE/v1/predictions" else "$REPLICATE/v1/models/$model/predictions"
        val body = buildJsonObject {
            if (versioned) put("version", model.substringAfter(':'))
            put("input", input)
        }
        var prediction = obj(
            ToolHttp.postJson(url, body.toString(), 70_000, auth + ("Prefer" to "wait=60"), NetSource.IMAGE_GEN, "/v1/predictions"),
        )
        val deadline = System.currentTimeMillis() + GIVE_UP_MS
        while (prediction.string("status") in setOf<String?>("starting", "processing")) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) throw IOException("Replicate took too long")
            delay(POLL_MS)
            val get = (prediction["urls"] as? JsonObject)?.string("get") ?: "$REPLICATE/v1/predictions/${prediction.string("id")}"
            prediction = obj(ToolHttp.get(get, 30_000, auth, source = NetSource.IMAGE_GEN, route = "/v1/predictions/{id}"))
        }
        if (prediction.string("status") != "succeeded") {
            val error = prediction.string("error").orEmpty()
            if (error.contains("NSFW", ignoreCase = true)) {
                sink.picture(Drawn.Blocked)
                return
            }
            throw IOException(error.ifBlank { "Replicate: ${prediction.string("status")}" })
        }
        val links = when (val output = prediction["output"]) {
            is JsonArray -> output.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOf(output.content)
            else -> emptyList()
        }
        for (link in links) sink.picture(Drawn.Link(link, auth))
    }

    /** Synchronous `fal.run`, the pictures inline as data URIs. */
    private suspend fun fal(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val body = buildJsonObject {
            put("prompt", r.prompt)
            put("image_size", "square_hd")
            put("num_images", r.count)
            put("seed", r.seed % Int.MAX_VALUE)
            put("enable_safety_checker", r.safety)
            put("output_format", "png")
            put("sync_mode", true)
            if (r.negative.isNotBlank()) put("negative_prompt", r.negative)
        }
        val answer = obj(
            ToolHttp.postJson(
                "$FAL/${model(row)}", body.toString(), TIMEOUT_MS,
                mapOf("Authorization" to "Key ${row.apiKey}"), NetSource.IMAGE_GEN, "/{model}",
            ),
        )
        val flagged = answer.array("has_nsfw_concepts").map { (it as? JsonPrimitive)?.booleanOrNull == true }
        answer.array("images").forEachIndexed { i, item ->
            val link = (item as? JsonObject)?.string("url") ?: return@forEachIndexed
            sink.picture(
                when {
                    flagged.getOrNull(i) == true -> Drawn.Blocked
                    link.startsWith("data:") -> Drawn.Bytes(dataUri(link))
                    else -> Drawn.Link(link)
                },
            )
        }
    }

    private suspend fun together(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val model = model(row)
        val body = buildJsonObject {
            put("model", model)
            put("prompt", r.prompt)
            put("width", SIZE)
            put("height", SIZE)
            // Schnell is a four-step model; the rest keep the service's default.
            if (model.contains("schnell", ignoreCase = true)) put("steps", 4)
            put("n", r.count)
            put("seed", r.seed % Int.MAX_VALUE)
            put("response_format", "base64")
            put("output_format", "png")
            if (r.negative.isNotBlank()) put("negative_prompt", r.negative)
            if (!r.safety) put("disable_safety_checker", true)
        }
        val answer = obj(
            ToolHttp.postJson(
                "$TOGETHER/v1/images/generations", body.toString(), TIMEOUT_MS,
                mapOf("Authorization" to "Bearer ${row.apiKey}"), NetSource.IMAGE_GEN, "/v1/images/generations",
            ),
        )
        for (item in answer.array("data")) {
            val o = item as? JsonObject ?: continue
            o.string("b64_json")?.let { sink.picture(Drawn.Bytes(Base64.decode(it, Base64.DEFAULT))) }
                ?: o.string("url")?.let { sink.picture(Drawn.Link(it)) }
        }
    }

    /** Hugging Face's own inference servers: the picture is the body. */
    private suspend fun huggingFace(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val model = model(row)
        repeat(r.count) { i ->
            currentCoroutineContext().ensureActive()
            val body = buildJsonObject {
                put("inputs", r.prompt)
                putJsonObject("parameters") {
                    put("width", SIZE)
                    put("height", SIZE)
                    put("seed", (r.seed + i) % Int.MAX_VALUE)
                    if (r.negative.isNotBlank()) put("negative_prompt", r.negative)
                }
            }
            val got = ToolHttp.bytes(
                "$HUGGING_FACE/models/$model",
                "POST",
                body.toString().toByteArray(),
                "application/json",
                mapOf("Authorization" to "Bearer ${row.apiKey}", "Accept" to "image/png"),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.IMAGE_GEN,
                route = "/hf-inference/models/{model}",
            )
            sink.picture(Drawn.Bytes(imageOrError(got)))
        }
    }

    /** Workers AI: FLUX answers JSON with the picture in base64, the SDXL models the picture itself. */
    private suspend fun cloudflare(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val model = model(row)
        repeat(r.count) { i ->
            currentCoroutineContext().ensureActive()
            val body = buildJsonObject {
                put("prompt", r.prompt)
                if (model.contains("schnell")) put("steps", 4)
                put("seed", (r.seed + i) % Int.MAX_VALUE)
                if (!model.contains("flux")) {
                    put("width", SIZE)
                    put("height", SIZE)
                    if (r.negative.isNotBlank()) put("negative_prompt", r.negative)
                }
            }
            val got = ToolHttp.bytes(
                "$CLOUDFLARE/accounts/${account(row)}/ai/run/$model",
                "POST",
                body.toString().toByteArray(),
                "application/json",
                mapOf("Authorization" to "Bearer ${row.apiKey}"),
                timeoutMs = TIMEOUT_MS,
                source = NetSource.IMAGE_GEN,
                route = "/accounts/{account}/ai/run/{model}",
            )
            if (got.contentType.startsWith("image/")) {
                sink.picture(Drawn.Bytes(got.body))
            } else {
                val answer = obj(String(got.body))
                val image = (answer["result"] as? JsonObject)?.string("image") ?: answer.string("image")
                    ?: throw IOException(ToolHttp.apiErrorText(String(got.body)) ?: "Workers AI sent no picture")
                sink.picture(Drawn.Bytes(Base64.decode(image, Base64.DEFAULT)))
            }
        }
    }

    private fun account(row: ResolvedProvider): String {
        val id = row.credential?.extra?.get(CredentialSpec.ACCOUNT)?.trim().orEmpty()
        if (id.isBlank()) throw IOException("the Cloudflare account id is missing")
        return id
    }

    // ---- Servers of your own -----------------------------------------------

    /** `/sdapi/v1/txt2img`, the pictures in base64. A model name switches the server's checkpoint for this call. */
    private suspend fun automatic1111(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val size = if (row.model.contains("xl", ignoreCase = true)) SIZE else SD15_SIZE
        val body = buildJsonObject {
            put("prompt", r.prompt)
            put("negative_prompt", r.negative)
            put("width", size)
            put("height", size)
            put("seed", r.seed % Int.MAX_VALUE)
            put("batch_size", r.count)
            put("n_iter", 1)
            if (row.model.isNotBlank()) {
                putJsonObject("override_settings") { put("sd_model_checkpoint", row.model) }
                put("override_settings_restore_afterwards", true)
            }
        }
        val answer = obj(
            ToolHttp.postJson(
                "${trimmed(row.url)}/sdapi/v1/txt2img", body.toString(), GIVE_UP_MS.toInt(),
                a1111Headers(row.apiKey), NetSource.IMAGE_GEN, "/sdapi/v1/txt2img",
            ),
        )
        for (item in answer.array("images").take(r.count)) {
            val b64 = (item as? JsonPrimitive)?.content ?: continue
            sink.picture(Drawn.Bytes(Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)))
        }
    }

    private fun a1111Headers(key: String): Map<String, String> = when {
        key.isBlank() -> emptyMap()
        // --api-auth user:password is HTTP basic; anything else is for a proxy in front.
        ':' in key -> mapOf("Authorization" to "Basic " + Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
        else -> mapOf("Authorization" to "Bearer $key")
    }

    /**
     * ComfyUI's API: queue the stock text-to-image graph, poll its history,
     * fetch each output through `/view`. A blank checkpoint is the first the
     * server lists.
     */
    private suspend fun comfy(row: ResolvedProvider, r: OnlineImageRequest, sink: Sink) {
        val base = trimmed(row.url)
        val headers = bearerOrNone(row.apiKey)
        val checkpoint = row.model.ifBlank {
            val info = obj(ToolHttp.get("$base/object_info/CheckpointLoaderSimple", 20_000, headers, source = NetSource.IMAGE_GEN, route = "/object_info"))
            val spec = (((info["CheckpointLoaderSimple"] as? JsonObject)?.get("input") as? JsonObject)
                ?.get("required") as? JsonObject)?.array("ckpt_name").orEmpty()
            // Older servers list the names as the first item; newer ones say COMBO and list them as its options.
            val names = (spec.firstOrNull() as? JsonArray)
                ?: ((spec.getOrNull(1) as? JsonObject)?.get("options") as? JsonArray)
            (names?.firstOrNull() as? JsonPrimitive)?.content ?: throw IOException("the server has no checkpoint")
        }
        val size = if (checkpoint.contains("xl", ignoreCase = true) || checkpoint.contains("flux", ignoreCase = true)) SIZE else SD15_SIZE
        val graph = comfyGraph(checkpoint, r.prompt, r.negative, r.seed, size, r.count)
        val queued = obj(
            ToolHttp.postJson(
                "$base/prompt",
                buildJsonObject {
                    put("prompt", graph)
                    put("client_id", "wmkeyboard")
                }.toString(),
                30_000, headers, NetSource.IMAGE_GEN, "/prompt",
            ),
        )
        val id = queued.string("prompt_id") ?: throw IOException(ToolHttp.apiErrorText(queued.toString()) ?: "ComfyUI took no job")
        val deadline = System.currentTimeMillis() + GIVE_UP_MS
        var outputs: JsonObject? = null
        while (outputs == null) {
            currentCoroutineContext().ensureActive()
            val history = obj(ToolHttp.get("$base/history/$id", 20_000, headers, source = NetSource.IMAGE_GEN, route = "/history/{id}"))
            val entry = history[id] as? JsonObject
            if (entry != null) {
                val status = entry["status"] as? JsonObject
                if (status?.string("status_str") == "error") throw IOException("ComfyUI could not run the workflow")
                val found = entry["outputs"] as? JsonObject
                if (found != null && found.isNotEmpty()) {
                    outputs = found
                    continue
                }
            }
            if (System.currentTimeMillis() > deadline) throw IOException("ComfyUI took too long")
            delay(POLL_MS)
        }
        for (node in checkNotNull(outputs).values) {
            for (image in (node as? JsonObject)?.array("images").orEmpty()) {
                val o = image as? JsonObject ?: continue
                val query = listOf(
                    "filename=" + ToolHttp.encode(o.string("filename").orEmpty()),
                    "subfolder=" + ToolHttp.encode(o.string("subfolder").orEmpty()),
                    "type=" + ToolHttp.encode(o.string("type") ?: "output"),
                ).joinToString("&")
                sink.picture(Drawn.Link("$base/view?$query", headers))
            }
        }
    }

    /** The graph ComfyUI's own API example queues, with this request's values in it. */
    internal fun comfyGraph(checkpoint: String, prompt: String, negative: String, seed: Long, size: Int, count: Int): JsonObject =
        buildJsonObject {
            fun link(node: String, slot: Int) = buildJsonArray {
                add(JsonPrimitive(node))
                add(JsonPrimitive(slot))
            }
            putJsonObject("3") {
                put("class_type", "KSampler")
                putJsonObject("inputs") {
                    put("seed", seed)
                    put("steps", 20)
                    put("cfg", 7)
                    put("sampler_name", "euler_ancestral")
                    put("scheduler", "normal")
                    put("denoise", 1)
                    put("model", link("4", 0))
                    put("positive", link("6", 0))
                    put("negative", link("7", 0))
                    put("latent_image", link("5", 0))
                }
            }
            putJsonObject("4") {
                put("class_type", "CheckpointLoaderSimple")
                putJsonObject("inputs") { put("ckpt_name", checkpoint) }
            }
            putJsonObject("5") {
                put("class_type", "EmptyLatentImage")
                putJsonObject("inputs") {
                    put("width", size)
                    put("height", size)
                    put("batch_size", count)
                }
            }
            putJsonObject("6") {
                put("class_type", "CLIPTextEncode")
                putJsonObject("inputs") {
                    put("text", prompt)
                    put("clip", link("4", 1))
                }
            }
            putJsonObject("7") {
                put("class_type", "CLIPTextEncode")
                putJsonObject("inputs") {
                    put("text", negative)
                    put("clip", link("4", 1))
                }
            }
            putJsonObject("8") {
                put("class_type", "VAEDecode")
                putJsonObject("inputs") {
                    put("samples", link("3", 0))
                    put("vae", link("4", 2))
                }
            }
            putJsonObject("9") {
                put("class_type", "SaveImage")
                putJsonObject("inputs") {
                    put("filename_prefix", "wmkeyboard")
                    put("images", link("8", 0))
                }
            }
        }

    // ---- Plumbing -------------------------------------------------------------

    /** An OpenAI-style base: the address up to the version, `/v1` added when it has none. */
    internal fun compatibleBase(url: String): String {
        val base = trimmed(url).removeSuffix("/images/generations")
        return if (Regex("/v\\d+[a-z]*(/|$)").containsMatchIn(URL(base).path + "/")) base else "$base/v1"
    }

    private fun trimmed(url: String): String {
        val u = url.trim().trimEnd('/')
        if (u.isEmpty()) throw IOException("no server address")
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
    }

    private fun bearerOrNone(key: String) = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")

    /** The route a log row shows: the path of an address we know, nothing of one the user typed. */
    private fun route(url: String, official: Boolean): String? =
        if (official) runCatching { URL(url).path }.getOrNull() else "/images/generations"

    /** A prompt as one path segment: URL-encoded, with spaces as %20 rather than a plus. */
    private fun pathPart(text: String): String = ToolHttp.encode(text).replace("+", "%20")

    private fun fetch(link: Drawn.Link): ByteArray {
        if (link.url.startsWith("data:")) return dataUri(link.url)
        // The picture's host is the service's storage, not a host the user named; no route.
        return imageOrError(ToolHttp.bytes(link.url, headers = link.headers, timeoutMs = 60_000, source = NetSource.IMAGE_GEN))
    }

    private fun imageOrError(got: ToolHttp.BytesResponse): ByteArray {
        if (got.contentType.startsWith("application/json")) {
            throw IOException(ToolHttp.apiErrorText(String(got.body)) ?: "the service sent no picture")
        }
        return got.body
    }

    private fun dataUri(uri: String): ByteArray = Base64.decode(uri.substringAfter(','), Base64.DEFAULT)

    /** Any format the phone reads, as a PNG at most [MAX_SIDE] a side; transparency survives. */
    private fun save(bytes: ByteArray, file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException("the service sent something that is not a picture")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("the picture could not be read")
        val longest = max(decoded.width, decoded.height)
        val bitmap = if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not write ${file.name}")
        }
    }

    private fun obj(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("the service sent an answer this version cannot read") }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private const val POLLINATIONS_FREE = "https://image.pollinations.ai"
    private const val POLLINATIONS = "https://gen.pollinations.ai"
    private const val HORDE = "https://aihorde.net/api"
    private const val HORDE_ANONYMOUS = "0000000000"
    private const val HORDE_SIZE = 512
    private const val OPENAI = "https://api.openai.com/v1"
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1"
    private const val XAI = "https://api.x.ai/v1"
    private const val STABILITY = "https://api.stability.ai"
    private const val STABILITY_SEEDS = 4_294_967_294L
    private const val BFL = "https://api.bfl.ai"
    private const val RECRAFT = "https://external.api.recraft.ai"
    private const val REPLICATE = "https://api.replicate.com"
    private const val FAL = "https://fal.run"
    private const val TOGETHER = "https://api.together.ai"
    private const val HUGGING_FACE = "https://router.huggingface.co/hf-inference"
    private const val CLOUDFLARE = "https://api.cloudflare.com/client/v4"

    /** Stable Diffusion 1.5 checkpoints draw at 512; asked for more they repeat the subject. */
    private const val SD15_SIZE = 512
}
