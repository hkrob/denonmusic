package com.denonmusic.avr

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One receiver found on the LAN: [host] to store as `avrHost`, [label] to tell devices apart in a UI. */
data class DiscoveredAvr(val host: String, val label: String)

/**
 * SSDP (UPnP) discovery for the Denon control point - per the plan: M-SEARCH for
 * `urn:schemas-denon-com:device:ACT-Denon:1` on the standard multicast group, manual IP entry
 * (`AppSettings.avrHost`) staying the fallback for a receiver that doesn't answer.
 *
 * Deliberately a single plain [DatagramSocket], not a `MulticastSocket`: the request goes out
 * multicast, but SSDP replies come back as ordinary *unicast* UDP to the port the request was sent
 * from - no multicast group join, and so no Android `MulticastLock`/extra permission needed to
 * receive them, unlike a service that listens for multicast traffic continuously.
 */
object SsdpDiscovery {
    private const val MULTICAST_ADDRESS = "239.255.255.250"
    private const val MULTICAST_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-denon-com:device:ACT-Denon:1"
    private const val RECEIVE_POLL_TIMEOUT_MILLIS = 500

    /** Broadcasts one M-SEARCH and collects replies for [timeoutMillis], de-duplicated by host. */
    suspend fun discover(timeoutMillis: Long = 4_000): List<DiscoveredAvr> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, DiscoveredAvr>()
        DatagramSocket().use { socket ->
            socket.soTimeout = RECEIVE_POLL_TIMEOUT_MILLIS
            runCatching { sendSearch(socket) }.onFailure { return@withContext emptyList() }

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parseReply(packet)?.let { results.putIfAbsent(it.host, it) }
            }
        }
        results.values.toList()
    }

    private fun sendSearch(socket: DatagramSocket) {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $SEARCH_TARGET\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val destination = InetSocketAddress(InetAddress.getByName(MULTICAST_ADDRESS), MULTICAST_PORT)
        socket.send(DatagramPacket(request, request.size, destination))
    }

    private fun parseReply(packet: DatagramPacket): DiscoveredAvr? {
        val host = packet.address?.hostAddress ?: return null
        val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
        return parseReply(host, text)
    }

    /** Split out from the packet-based overload above so the parsing itself is unit-testable without a real socket. */
    internal fun parseReply(host: String, text: String): DiscoveredAvr? {
        if (!text.startsWith("HTTP/1.1 200")) return null
        val server = Regex("(?im)^SERVER:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
        return DiscoveredAvr(host = host, label = server?.takeIf { it.isNotBlank() } ?: host)
    }
}
