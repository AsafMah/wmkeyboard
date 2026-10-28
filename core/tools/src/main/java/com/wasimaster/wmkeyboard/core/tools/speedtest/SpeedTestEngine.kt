package com.wasimaster.wmkeyboard.core.tools.speedtest

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a speed test the way the browser tests do: a few latency probes, then
 * several connections pulling random bytes at once for a fixed time, then
 * the same pushing them. The headline number for each direction is the mean
 * of the 200 ms samples after the first fifth, so TCP's slow start and the
 * connections still being opened never count ([SpeedTestMath.trimmedThroughput]).
 *
 * Every request is one row in the network activity log, with the bytes it
 * really moved. Cancelling the coroutine stops the run at once: the open
 * connections are disconnected, which ends every blocked read and write.
 *
 * Plain [HttpURLConnection]: nothing here needs a client library, and
 * keeping the connections in a set lets a deadline cut them all off.
 */
class SpeedTestEngine(
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A megabyte of random bytes, written over and over as the upload body. */
    private val payload: ByteArray by lazy { ByteArray(CHUNK).also { Random(SEED).nextBytes(it) } }

    private class PhaseOutcome(val mbps: Double, val samples: List<Float>, val bytes: Long)

    /** A request the server refused with [status]. */
    class HttpStatusException(val status: Int) : IOException("HTTP $status")

    /**
     * A phase that moved nothing: every request failed, the last of them with
     * [cause]. [status] is the HTTP status when that is what refused it, as
     * Cloudflare does with 429 after several large runs from one address.
     */
    class PhaseFailedException(val phase: SpeedTestPhase, val status: Int?, cause: Throwable) :
        IOException("${phase.name.lowercase()} moved no bytes: ${cause.message}", cause)

    /**
     * One run against [server]. [onProgress] is called on the caller's
     * dispatcher about five times a second while bytes move, once at each
     * phase change before that. Its speed is the phase's running mean, not
     * the last sample, so the dial settles rather than flickers.
     */
    suspend fun run(
        server: SpeedTestServer,
        config: SpeedTestConfig,
        onProgress: suspend (SpeedTestProgress) -> Unit,
    ): SpeedTestResult {
        onProgress(SpeedTestProgress(SpeedTestPhase.META))
        val meta = withContext(Dispatchers.IO) { runCatching { fetchMeta(server) }.getOrDefault(SpeedTestMeta()) }

        onProgress(SpeedTestProgress(SpeedTestPhase.PING))
        val pings = withContext(Dispatchers.IO) { measurePings(server, PING_COUNT) }
        val ping = SpeedTestMath.median(pings)
        val jitter = SpeedTestMath.jitter(pings)
        onProgress(SpeedTestProgress(SpeedTestPhase.PING, pingMs = ping))

        val down = transfer(server, config, upload = false, onProgress)
        val up = if (config.upload) transfer(server, config, upload = true, onProgress) else null

        return SpeedTestResult(
            downMbps = down.mbps,
            upMbps = up?.mbps,
            pingMs = ping,
            jitterMs = jitter,
            downSamples = down.samples,
            upSamples = up?.samples.orEmpty(),
            isp = meta.isp,
            ip = meta.ip,
            serverName = server.label,
            serverCity = when (server) {
                is SpeedTestServer.Cloudflare -> meta.coloCity.ifBlank { meta.colo }
                is SpeedTestServer.LibreSpeed -> LibreSpeedServers.Entry(name = server.name, server = server.base).city
                    .takeIf { server.name.isNotBlank() }.orEmpty()
            },
            at = now(),
            bytesMoved = down.bytes + (up?.bytes ?: 0L),
        )
    }

    // ---- meta ----

    private fun fetchMeta(server: SpeedTestServer): SpeedTestMeta = when (server) {
        is SpeedTestServer.Cloudflare -> {
            val base = server.base.trimEnd('/')
            // /meta answers only the test page itself; say we are it.
            val body = runCatching {
                ToolHttp.get(
                    "$base/meta",
                    timeoutMs = 8_000,
                    headers = mapOf("Origin" to base, "Referer" to "$base/"),
                    source = NetSource.SPEED_TEST,
                    route = "/meta",
                )
            }.getOrNull()
            body?.let(CloudflareMeta::parse) ?: CloudflareMeta.fromHeaders(
                ToolHttp.getWithHeaders(
                    "$base/__down?bytes=0",
                    timeoutMs = 8_000,
                    wantHeaders = setOf("cf-meta-ip", "city", "country", "colo", "asn"),
                    source = NetSource.SPEED_TEST,
                    route = "/__down",
                ).headers,
            )
        }
        is SpeedTestServer.LibreSpeed -> {
            val url = server.url(server.ipPath) + (if ('?' in server.ipPath) "&" else "?") + "isp=true"
            val body = ToolHttp.get(url, timeoutMs = 8_000, source = NetSource.SPEED_TEST, route = routeOf(server.ipPath))
            val processed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(body)
                    .let { it as kotlinx.serialization.json.JsonObject }["processedString"]
                    ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            }.getOrNull() ?: body
            val (ip, isp) = LibreSpeedServers.parseIpLine(processed)
            SpeedTestMeta(ip = ip, isp = isp.takeUnless { it.equals("Unknown ISP", ignoreCase = true) }.orEmpty())
        }
    }

    // ---- ping ----

    /**
     * [count] round trips to an empty body, one after another. The first
     * also opens the connection, so it is longer than the rest and left out
     * once there are enough to spare it.
     */
    private fun measurePings(server: SpeedTestServer, count: Int): List<Double> {
        val times = ArrayList<Double>(count)
        repeat(count) { i ->
            val url = pingUrl(server, i)
            val route = when (server) {
                is SpeedTestServer.Cloudflare -> "/__down"
                is SpeedTestServer.LibreSpeed -> routeOf(server.pingPath)
            }
            val ms = runCatching { oneProbe(url, route) }.getOrNull() ?: return@repeat
            times += ms
        }
        return if (times.size > 3) times.drop(1) else times
    }

    private fun pingUrl(server: SpeedTestServer, i: Int): String = when (server) {
        is SpeedTestServer.Cloudflare -> server.base.trimEnd('/') + "/__down?bytes=0&r=$i"
        is SpeedTestServer.LibreSpeed -> server.url(server.pingPath) + (if ('?' in server.pingPath) "&" else "?") + "r=$i"
    }

    /** One request, timed from before it opens to after the (empty) body is read. */
    private fun oneProbe(url: String, route: String): Double {
        val connection = URL(url).openConnection() as HttpURLConnection
        val call = NetLog.call(NetSource.SPEED_TEST, "GET", url, route = route)
        val start = System.nanoTime()
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", ToolHttp.USER_AGENT)
            connection.setRequestProperty("Cache-Control", "no-cache")
            call.status = connection.responseCode
            call.countIn(connection.inputStream).use { it.readBytes() }
            return (System.nanoTime() - start) / 1_000_000.0
        } catch (t: Throwable) {
            call.fail(t)
            throw t
        } finally {
            connection.disconnect()
            call.end()
        }
    }

    // ---- transfer ----

    /**
     * One timed phase. [config.connections] workers each run requests back
     * to back and add what they move to one counter; a sampler reads the
     * counter every 200 ms. At the deadline every open connection is cut,
     * which is what ends a read or write that would otherwise run on.
     */
    private suspend fun transfer(
        server: SpeedTestServer,
        config: SpeedTestConfig,
        upload: Boolean,
        onProgress: suspend (SpeedTestProgress) -> Unit,
    ): PhaseOutcome = coroutineScope {
        val phase = if (upload) SpeedTestPhase.UPLOAD else SpeedTestPhase.DOWNLOAD
        onProgress(SpeedTestProgress(phase))
        val counter = AtomicLong()
        val open: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())
        val start = now()
        val deadline = start + config.durationS * 1000L
        val lastRequestMs = AtomicLong()
        val lastFailure = AtomicReference<Throwable?>()
        // Megabytes per download request, shared by the workers: stepped down
        // the ladder when the server answers 429, which Cloudflare does for
        // large requests once an address has pulled a lot in a short time
        // while still serving small ones.
        val downMb = AtomicInteger(DOWN_LADDER_MB.first())
        val workers = (1..config.connections.coerceIn(1, MAX_CONNECTIONS)).map {
            launch(Dispatchers.IO) {
                while (isActive && now() < deadline) {
                    val began = now()
                    try {
                        if (upload) {
                            uploadOnce(server, counter, open, bodyBytes(lastRequestMs.get()))
                        } else {
                            downloadOnce(server, counter, open, downMb.get())
                        }
                        lastRequestMs.set(now() - began)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HttpStatusException) {
                        lastFailure.set(e)
                        when {
                            e.status == HTTP_TOO_MANY && !upload && stepDown(downMb) -> Unit
                            // Refused outright: asking again only feeds the limit.
                            e.status in 400..499 -> return@launch
                            else -> if (now() < deadline) delay(RETRY_MS)
                        }
                    } catch (e: IOException) {
                        // A cut connection at the deadline lands here too; a
                        // real failure is retried after a pause, the deadline
                        // still bounding the phase.
                        lastFailure.set(e)
                        if (now() < deadline) delay(RETRY_MS)
                    } catch (e: RuntimeException) {
                        lastFailure.set(e)
                        if (now() < deadline) delay(RETRY_MS)
                    }
                }
            }
        }
        val samples = ArrayList<Float>()
        val expected = (config.durationS * 1000L / SAMPLE_MS).toInt()
        var lastBytes = 0L
        var lastAt = start
        try {
            while (currentCoroutineContext().isActive && now() < deadline) {
                delay(SAMPLE_MS)
                // Every worker gave up (a 4xx each): nothing more will come.
                if (workers.none { it.isActive }) break
                val bytes = counter.get()
                val at = now()
                samples += SpeedTestMath.mbps(bytes - lastBytes, at - lastAt).toFloat()
                lastBytes = bytes
                lastAt = at
                onProgress(SpeedTestProgress(phase, SpeedTestMath.running(samples, expected), samples.toList()))
            }
        } finally {
            synchronized(open) { open.toList() }.forEach { runCatching { it.disconnect() } }
            workers.forEach { it.cancel() }
        }
        workers.joinAll()
        if (counter.get() == 0L) {
            val cause = lastFailure.get() ?: IOException("no bytes moved")
            throw PhaseFailedException(phase, (cause as? HttpStatusException)?.status, cause)
        }
        PhaseOutcome(SpeedTestMath.trimmedThroughput(samples), samples, counter.get())
    }

    /** The next smaller download request; false when already at the smallest. */
    private fun stepDown(downMb: AtomicInteger): Boolean {
        while (true) {
            val current = downMb.get()
            val next = DOWN_LADDER_MB.firstOrNull { it < current } ?: return false
            if (downMb.compareAndSet(current, next)) return true
        }
    }

    /** The upload body grows while requests finish fast, so a quick line is not starved by small posts. */
    private fun bodyBytes(lastRequestMs: Long): Int = when {
        lastRequestMs == 0L -> CHUNK
        lastRequestMs < 500 -> CHUNK * 8
        lastRequestMs < 1500 -> CHUNK * 4
        lastRequestMs < 3000 -> CHUNK * 2
        else -> CHUNK / 4
    }

    private fun downloadOnce(server: SpeedTestServer, counter: AtomicLong, open: MutableSet<HttpURLConnection>, megabytes: Int) {
        val (url, route) = when (server) {
            is SpeedTestServer.Cloudflare -> server.base.trimEnd('/') + "/__down?bytes=${megabytes * 1_000_000}" to "/__down"
            is SpeedTestServer.LibreSpeed ->
                server.url(server.dlPath) + (if ('?' in server.dlPath) "&" else "?") +
                    "ckSize=$megabytes&r=${Random.nextInt()}" to routeOf(server.dlPath)
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        val call = NetLog.call(NetSource.SPEED_TEST, "GET", url, route = route)
        open += connection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", ToolHttp.USER_AGENT)
            connection.setRequestProperty("Cache-Control", "no-cache")
            call.status = connection.responseCode
            if (call.status !in 200..299) throw HttpStatusException(call.status)
            val buffer = ByteArray(READ_BUFFER)
            connection.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    counter.addAndGet(n.toLong())
                    call.received(n.toLong())
                }
            }
        } catch (t: Throwable) {
            call.fail(t)
            throw t
        } finally {
            open -= connection
            connection.disconnect()
            call.end()
        }
    }

    private fun uploadOnce(server: SpeedTestServer, counter: AtomicLong, open: MutableSet<HttpURLConnection>, bytes: Int) {
        val (url, route) = when (server) {
            is SpeedTestServer.Cloudflare -> server.base.trimEnd('/') + "/__up" to "/__up"
            is SpeedTestServer.LibreSpeed ->
                server.url(server.ulPath) + (if ('?' in server.ulPath) "&" else "?") + "r=${Random.nextInt()}" to
                    routeOf(server.ulPath)
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        val call = NetLog.call(NetSource.SPEED_TEST, "POST", url, route = route)
        open += connection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", ToolHttp.USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes)
            connection.outputStream.use { out ->
                // Written and counted in small pieces: a whole megabyte at a
                // time lands on the counter in lumps, and with four
                // connections a 200 ms sample would hold none or three of
                // them, which is what made the upload samples a saw.
                var left = bytes
                var offset = 0
                while (left > 0) {
                    val n = minOf(left, WRITE_CHUNK, payload.size - offset)
                    out.write(payload, offset, n)
                    counter.addAndGet(n.toLong())
                    call.sent(n.toLong())
                    left -= n
                    offset = (offset + n) % payload.size
                }
                out.flush()
            }
            call.status = connection.responseCode
            // Drain whatever the server answers so the connection can be reused.
            runCatching { connection.inputStream.use { it.readBytes() } }
            if (call.status !in 200..299) throw HttpStatusException(call.status)
        } catch (t: Throwable) {
            call.fail(t)
            throw t
        } finally {
            open -= connection
            connection.disconnect()
            call.end()
        }
    }

    /** A LibreSpeed path as a log route: the path alone, no query, with a leading slash. */
    private fun routeOf(path: String): String {
        val p = path.substringBefore('?')
        val bare = if (p.startsWith("http")) NetLog.pathOf(p) ?: "/" else p
        return "/" + bare.trimStart('/')
    }

    private companion object {
        const val CHUNK = 1 shl 20
        const val SEED = 7
        const val READ_BUFFER = 64 * 1024
        const val WRITE_CHUNK = 32 * 1024
        const val SAMPLE_MS = 200L
        const val RETRY_MS = 250L
        const val PING_COUNT = 10
        const val MAX_CONNECTIONS = 8

        const val HTTP_TOO_MANY = 429

        /**
         * Download request sizes in megabytes, largest first: big enough to
         * stream for the whole phase on most lines, re-requested on a fast
         * one; each step down is what a 429 falls back to. Cloudflare's
         * `bytes=` and LibreSpeed's `ckSize=` (1 MB chunks) both take these.
         */
        val DOWN_LADDER_MB = listOf(25, 10, 5, 2, 1)
    }
}
