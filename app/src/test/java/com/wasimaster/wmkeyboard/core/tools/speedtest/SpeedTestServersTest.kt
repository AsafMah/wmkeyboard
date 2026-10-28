package com.wasimaster.wmkeyboard.core.tools.speedtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two servers' answers, parsed as they really come back. */
class SpeedTestServersTest {

    private val publicList = """
        [{"name":"Amsterdam, Netherlands (Clouvider)","server":"https:\/\/ams.speedtest.clouvider.net\/backend","id":51,
          "dlURL":"garbage.php","ulURL":"empty.php","pingURL":"empty.php","getIpURL":"getIP.php",
          "sponsorName":"Clouvider","sponsorURL":"https:\/\/www.clouvider.co.uk\/"},
         {"name":"Chicago, USA (Sharktech)","server":"\/\/chispeed.sharktech.net\/","id":93,
          "dlURL":"backend\/garbage.php","ulURL":"backend\/empty.php","pingURL":"backend\/empty.php","getIpURL":"backend\/getIP.php"},
         {"name":"Nameless","server":""},
         {"name":"Go backend","server":"https:\/\/go.example.org","dlURL":"garbage","ulURL":"empty","pingURL":"empty","getIpURL":"getIP","extra":1}]
    """.trimIndent()

    @Test
    fun publicListParsesAndSkipsTheBroken() {
        val list = LibreSpeedServers.parse(publicList)
        assertEquals(listOf("Amsterdam, Netherlands (Clouvider)", "Chicago, USA (Sharktech)", "Go backend"), list.map { it.name })
        val ams = list[0]
        assertEquals("Netherlands", ams.country)
        assertEquals("Amsterdam", ams.city)
        assertEquals("Clouvider", ams.sponsorName)
        val go = list[2]
        assertEquals("Go backend", go.country)
        assertEquals("garbage", go.dlURL)
        assertTrue(LibreSpeedServers.parse("{}").isEmpty())
        assertTrue(LibreSpeedServers.parse("<html>").isEmpty())
    }

    @Test
    fun schemelessHostsAndPathsResolve() {
        val chicago = LibreSpeedServers.parse(publicList)[1].toServer()
        assertEquals("https://chispeed.sharktech.net", chicago.base)
        assertEquals("https://chispeed.sharktech.net/backend/garbage.php", chicago.url(chicago.dlPath))
        assertEquals("chispeed.sharktech.net", LibreSpeedServers.hostOf(chicago.base))
        // An absolute path stays itself; a bare host gains https.
        assertEquals("https://cdn.example/empty.php", LibreSpeedServers.resolve("https://a.example", "https://cdn.example/empty.php"))
        assertEquals("https://a.example/x/empty.php", LibreSpeedServers.resolve("a.example/x/", "/empty.php"))
    }

    @Test
    fun credentialExtrasRoundTrip() {
        val entry = LibreSpeedServers.parse(publicList)[1]
        val extras = LibreSpeedServers.extrasOf(entry)
        val server = LibreSpeedServers.fromCredential(entry.server, entry.name, extras)
        assertEquals(entry.toServer().copy(sponsor = ""), server)
        // A row typed by hand, with nothing else: the PHP defaults.
        val plain = LibreSpeedServers.fromCredential("https://mine.example/", "", emptyMap())
        assertEquals("https://mine.example/garbage.php", plain.url(plain.dlPath))
        assertEquals("mine.example", plain.label)
    }

    @Test
    fun ipLineSplits() {
        assertEquals("103.31.178.116" to "Vision Technologies Ltd.", LibreSpeedServers.parseIpLine("103.31.178.116 - Vision Technologies Ltd."))
        assertEquals("1.2.3.4" to "", LibreSpeedServers.parseIpLine("1.2.3.4"))
    }

    @Test
    fun cloudflareMetaParses() {
        val meta = CloudflareMeta.parse(
            """{"hostname":"speed.cloudflare.com","clientIp":"103.31.178.116","asn":137959,"asOrganization":"Vision Technologies Ltd.",
               "country":"BD","city":"Pābna","colo":{"iata":"DAC","city":"Dhaka"}}""",
        )!!
        assertEquals("103.31.178.116", meta.ip)
        assertEquals("Vision Technologies Ltd.", meta.isp)
        assertEquals("Pābna", meta.city)
        assertEquals("DAC", meta.colo)
        assertEquals("Dhaka", meta.coloCity)
        // The empty object an anonymous request gets is "nothing", not a blank meta.
        assertNull(CloudflareMeta.parse("{}"))
        assertNull(CloudflareMeta.parse("nope"))
    }

    @Test
    fun cloudflareHeadersFallBack() {
        val meta = CloudflareMeta.fromHeaders(
            mapOf("cf-meta-ip" to "103.31.178.116", "City" to "P%C4%81bna", "country" to "BD", "colo" to "DAC", "asn" to "137959"),
        )
        assertEquals("103.31.178.116", meta.ip)
        assertEquals("Pābna", meta.city)
        assertEquals("DAC", meta.colo)
        assertEquals("AS137959", meta.isp)
        assertEquals("", meta.coloCity)
    }
}
