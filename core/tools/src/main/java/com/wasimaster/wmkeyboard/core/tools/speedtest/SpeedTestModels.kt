package com.wasimaster.wmkeyboard.core.tools.speedtest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The four steps of a run, in the order they go. */
enum class SpeedTestPhase { META, PING, DOWNLOAD, UPLOAD }

/** How a speed is written: megabits per second, or megabytes per second. */
enum class SpeedUnit { MBPS, MBYTES }

/**
 * What one run does. [durationS] is per transfer phase, so a full run takes
 * about twice that plus a second of pings; [connections] is how many requests
 * pull or push at once, which is what lets a fast line fill up.
 */
data class SpeedTestConfig(
    val durationS: Int = 10,
    val connections: Int = 4,
    val upload: Boolean = true,
)

/** A server a run talks to. */
sealed interface SpeedTestServer {
    /** The name the panel and the card show. */
    val label: String

    /**
     * Cloudflare's test endpoints under [base]: `/__down?bytes=N` streams N
     * random bytes, `/__up` takes any body, `/meta` names the network.
     */
    data class Cloudflare(val base: String) : SpeedTestServer {
        override val label: String get() = "Cloudflare"
    }

    /**
     * A LibreSpeed backend: PHP or Go, the user's own or one from the public
     * list. The paths are relative to [base] unless they are addresses
     * themselves, which is how the public list writes a few of them.
     */
    data class LibreSpeed(
        val base: String,
        val dlPath: String = LibreSpeedServers.DEFAULT_DL,
        val ulPath: String = LibreSpeedServers.DEFAULT_UL,
        val pingPath: String = LibreSpeedServers.DEFAULT_PING,
        val ipPath: String = LibreSpeedServers.DEFAULT_IP,
        val name: String = "",
        val sponsor: String = "",
    ) : SpeedTestServer {
        override val label: String
            get() = name.ifBlank { LibreSpeedServers.hostOf(base) }

        fun url(path: String): String = LibreSpeedServers.resolve(base, path)
    }
}

/** What the server knew about the connection before any bytes moved. */
data class SpeedTestMeta(
    /** The connection's public address, as the server saw it. */
    val ip: String = "",
    /** The network's name (the organisation behind the ASN), or blank. */
    val isp: String = "",
    val city: String = "",
    val country: String = "",
    /** The data centre that answered: an IATA code for Cloudflare, blank otherwise. */
    val colo: String = "",
    val coloCity: String = "",
)

/** What the panel hears while a run is going. */
data class SpeedTestProgress(
    val phase: SpeedTestPhase,
    /** The phase's speed so far ([SpeedTestMath.running]); 0 while nothing moved. */
    val mbps: Double = 0.0,
    /** Every 200 ms sample of the phase so far, oldest first. */
    val samples: List<Float> = emptyList(),
    /** The median ping once the ping phase is done, else null. */
    val pingMs: Double? = null,
)

/**
 * One finished run, as stored (the last one is kept in the settings) and as
 * drawn on the card. Speeds are megabits per second; times are milliseconds.
 */
@Serializable
data class SpeedTestResult(
    val downMbps: Double,
    /** Null when the run skipped the upload phase. */
    val upMbps: Double? = null,
    val pingMs: Double,
    val jitterMs: Double,
    /** The download phase's 200 ms samples, for the card's sparkline. */
    val downSamples: List<Float> = emptyList(),
    val upSamples: List<Float> = emptyList(),
    val isp: String = "",
    /** Never drawn on the card unless the user turned that on. */
    val ip: String = "",
    val serverName: String = "",
    /** The server's city, or Cloudflare's data-centre code. */
    val serverCity: String = "",
    /** When the run finished, epoch milliseconds. */
    val at: Long = 0L,
    /** Bytes moved in both directions together, for the data note. */
    val bytesMoved: Long = 0L,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(text: String?): SpeedTestResult? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}
