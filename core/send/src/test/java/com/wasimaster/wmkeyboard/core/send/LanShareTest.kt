package com.wasimaster.wmkeyboard.core.send

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.Collections

class LanShareTest {

    private val saved = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
    private val transfers = Collections.synchronizedList(mutableListOf<LanTransfer>())

    private val sink = object : SendSink {
        override fun create(name: String, size: Long, type: String): SendSinkFile {
            val bytes = ByteArrayOutputStream()
            return object : SendSinkFile {
                override val stream: OutputStream = bytes
                override fun finish(success: Boolean): String? {
                    if (!success) return null
                    saved[name] = bytes.toByteArray()
                    return "content://saved/$name"
                }
            }
        }
    }

    private val share = LanShare(
        sink,
        object : LanShare.Listener {
            override fun onTransfer(transfer: LanTransfer) {
                transfers += transfer
            }
        },
        deviceName = "Test \"phone\"",
    )
    private val http = OkHttpClient()
    private lateinit var base: String

    @Before
    fun start() {
        share.start(bind = InetAddress.getLoopbackAddress())
        base = share.url("127.0.0.1")
    }

    @After
    fun stop() = share.stop()

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `the page is behind the token`() {
        get(base).use { r ->
            assertEquals(200, r.code)
            assertTrue(r.body!!.string().contains("\"/${share.token}/\""))
            assertEquals("no-store", r.header("Cache-Control"))
        }
        get("http://127.0.0.1:${share.port}/").use { assertEquals(404, it.code) }
        get("http://127.0.0.1:${share.port}/wrongtoken/files.json").use { assertEquals(404, it.code) }
    }

    @Test
    fun `the list names the files and escapes them`() {
        share.files = listOf(SendFile("a\"b<c>.txt", 3, "text/plain") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        val json = get(base + "files.json").use { it.body!!.string() }
        assertEquals(
            """{"name":"Test \"phone\"","uploads":true,"files":[{"i":0,"name":"a\"b\u003cc>.txt","size":3,"type":"text/plain"}]}""",
            json,
        )
    }

    @Test
    fun `a file downloads under its own name only`() {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        share.files = listOf(SendFile("photo één.jpg", bytes.size.toLong(), "image/jpeg") { ByteArrayInputStream(bytes) })
        get(base + "f/0/photo%20%C3%A9%C3%A9n.jpg").use { r ->
            assertEquals(200, r.code)
            assertArrayEquals(bytes, r.body!!.bytes())
            assertTrue(r.header("Content-Disposition")!!.contains("filename*=UTF-8''photo%20%C3%A9%C3%A9n.jpg"))
        }
        get(base + "f/0/other.jpg").use { assertEquals(404, it.code) }
        get(base + "f/1/photo.jpg").use { assertEquals(404, it.code) }
        val last = transfers.last()
        assertEquals(LanTransfer.State.DONE, last.state)
        assertEquals(bytes.size.toLong(), last.done)
        assertEquals(false, last.incoming)
    }

    @Test
    fun `an upload is saved with a safe name`() {
        val bytes = ByteArray(300_000) { (it % 7).toByte() }
        val request = Request.Builder()
            .url(base + "upload?name=" + "..%2F..%2Fevil.png")
            .post(bytes.toRequestBody("image/png".toMediaType()))
            .build()
        http.newCall(request).execute().use { assertEquals(200, it.code) }
        assertArrayEquals(bytes, saved["evil.png"])
        val last = transfers.last()
        assertEquals(LanTransfer.State.DONE, last.state)
        assertEquals("image/png", last.type)
        assertEquals("content://saved/evil.png", last.location)
    }

    @Test
    fun `uploads can be turned off`() {
        share.uploadsAllowed = false
        val request = Request.Builder().url(base + "upload?name=a.txt").post(byteArrayOf(1).toRequestBody()).build()
        http.newCall(request).execute().use { assertEquals(403, it.code) }
        assertNull(saved["a.txt"])
    }

    @Test
    fun `helpers`() {
        assertEquals("WIFI:T:WPA;S:My\\;Net;P:pa\\:ss;;", LanShare.wifiCode("My;Net", "pa:ss"))
        assertEquals("WIFI:T:nopass;S:Open;;", LanShare.wifiCode("Open", ""))
        assertEquals("file", LanShare.safeName("../.."))
        assertEquals("a.txt", LanShare.safeName("C:\\x\\a.txt"))
        assertEquals(0, LanShare.rank("wlan0"))
        assertEquals(1, LanShare.rank("swlan0"))
        assertNull(LanShare.rank("rmnet_data0"))
        assertNull(LanShare.rank("tun0"))
    }
}
