package com.denonmusic.avr

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SsdpDiscoveryTest {

    @Test
    fun `a 200 OK reply with a SERVER header is labelled by it`() {
        val reply = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.50:60006/description.xml\r\n" +
            "SERVER: Linux/3.14 UPnP/1.0 Denon-Heos/1.0\r\n" +
            "ST: urn:schemas-denon-com:device:ACT-Denon:1\r\n" +
            "\r\n"

        val result = SsdpDiscovery.parseReply("192.168.1.50", reply)

        assertEquals(DiscoveredAvr("192.168.1.50", "Linux/3.14 UPnP/1.0 Denon-Heos/1.0"), result)
    }

    @Test
    fun `a reply with no SERVER header falls back to the host as its own label`() {
        val reply = "HTTP/1.1 200 OK\r\nST: urn:schemas-denon-com:device:ACT-Denon:1\r\n\r\n"

        val result = SsdpDiscovery.parseReply("192.168.1.50", reply)

        assertEquals(DiscoveredAvr("192.168.1.50", "192.168.1.50"), result)
    }

    @Test
    fun `anything that is not a 200 OK is ignored`() {
        assertNull(SsdpDiscovery.parseReply("192.168.1.50", "HTTP/1.1 404 Not Found\r\n\r\n"))
        assertNull(SsdpDiscovery.parseReply("192.168.1.50", "NOTIFY * HTTP/1.1\r\n\r\n"))
        assertNull(SsdpDiscovery.parseReply("192.168.1.50", ""))
    }

    @Test
    fun `the SERVER header match is case insensitive and trims surrounding whitespace`() {
        val reply = "HTTP/1.1 200 OK\r\nserver:   Denon AVR-X4500H   \r\n\r\n"

        val result = SsdpDiscovery.parseReply("192.168.1.50", reply)

        assertEquals("Denon AVR-X4500H", result?.label)
    }
}
