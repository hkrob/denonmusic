package com.denonmusic.avr

import com.denonmusic.avr.AvTransportClient.Companion.buildEnvelope
import com.denonmusic.avr.AvTransportClient.Companion.toRelTime
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real loopback HTTP tests, same rationale as `AvrClientTest`/`HeosClientTest`: a fake server on an
 * ephemeral port, not a mocked [java.net.HttpURLConnection].
 */
@Timeout(30)
class AvTransportClientTest {

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private fun startServer(
        status: Int = 200,
        body: String = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Body><u:SeekResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/></s:Body>" +
            "</s:Envelope>",
    ): Triple<HttpServer, MutableMap<String, String>, MutableList<String>> {
        val headers = mutableMapOf<String, String>()
        val requestBodies = mutableListOf<String>()
        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        httpServer.createContext("/upnp/control/renderer_dvc/AVTransport") { exchange ->
            headers["SOAPAction"] = exchange.requestHeaders.getFirst("SOAPAction") ?: ""
            headers["Content-Type"] = exchange.requestHeaders.getFirst("Content-Type") ?: ""
            requestBodies += exchange.requestBody.readBytes().decodeToString()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        server = httpServer
        return Triple(httpServer, headers, requestBodies)
    }

    @Test
    fun `seek posts a REL_TIME SOAP envelope to the AVTransport control URL`() = runBlocking {
        val (httpServer, headers, bodies) = startServer()
        val client = AvTransportClient(httpServer.address.hostString, httpServer.address.port)

        client.seek(90_000)

        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Seek\"", headers["SOAPAction"])
        assertTrue(headers["Content-Type"]!!.startsWith("text/xml"))
        val sent = bodies.single()
        assertTrue(sent.contains("<Unit>REL_TIME</Unit>"), sent)
        assertTrue(sent.contains("<Target>0:01:30</Target>"), sent)
        assertTrue(sent.contains("<InstanceID>0</InstanceID>"), sent)
    }

    @Test
    fun `a negative position clamps to the start of the track`() = runBlocking {
        val (httpServer, _, bodies) = startServer()
        val client = AvTransportClient(httpServer.address.hostString, httpServer.address.port)

        client.seek(-5_000)

        assertTrue(bodies.single().contains("<Target>0:00:00</Target>"))
    }

    @Test
    fun `a UPnP fault response surfaces as a failure, not a silent no-op`() = runBlocking {
        val (httpServer, _, _) = startServer(status = 500, body = "<s:Envelope><s:Body><s:Fault/></s:Body></s:Envelope>")
        val client = AvTransportClient(httpServer.address.hostString, httpServer.address.port)

        assertFailsWith<Exception> { client.seek(1_000) }
    }

    @Test
    fun `toRelTime formats H MM SS with no leading-zero hour`() {
        assertEquals("0:00:00", 0L.toRelTime())
        assertEquals("0:01:30", 90_000L.toRelTime())
        assertEquals("1:01:01", 3_661_000L.toRelTime())
        // Sub-second remainders truncate rather than round - matching how the progress bar itself
        // already only ever shows whole seconds.
        assertEquals("0:00:01", 1_999L.toRelTime())
    }

    @Test
    fun `buildEnvelope wraps the action and arguments in the expected SOAP shape`() {
        val envelope = buildEnvelope("Seek", "<InstanceID>0</InstanceID>")
        assertTrue(envelope.startsWith("<?xml"))
        assertTrue(envelope.contains("<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(envelope.contains("<InstanceID>0</InstanceID>"))
        assertTrue(envelope.contains("</u:Seek>"))
    }
}
