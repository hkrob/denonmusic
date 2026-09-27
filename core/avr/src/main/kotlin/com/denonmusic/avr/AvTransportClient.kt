package com.denonmusic.avr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Seeks within whatever HEOS is currently playing, over the standard UPnP `AVTransport` service this
 * receiver's HEOS module exposes on its own HTTP port - a control plane entirely separate from both
 * the Denon telnet port ([AvrConnection]) and the `heos://` CLI ([com.denonmusic.heos.HeosClient]).
 *
 * Neither of those two has a seek command: the HEOS CLI answers every plausible spelling of one with
 * `eid=1 Command not recognized` (confirmed live - see docs/local-setup.md), which is the "command
 * doesn't exist" error, not the "bad argument" one a real command gives for a bad position. Denon's
 * telnet protocol has never had one either. This is a genuinely different path, found by fetching the
 * receiver's own UPnP device description (`GET http://<host>:60006/upnp/desc/aios_device/aios_device.xml`),
 * which advertises a standard `urn:schemas-upnp-org:service:AVTransport:1` at
 * `/upnp/control/renderer_dvc/AVTransport` - confirmed end to end against a real AVR-X4500H: a `Seek`
 * there moves the position, and HEOS's own `player_now_playing_progress` push events report the new
 * position a few seconds later (that event's own cadence, not anything triggered by the seek itself -
 * see [com.denonmusic.app.player.PlayerViewModel.seekTo] for why callers update local state instead of
 * waiting for it).
 */
class AvTransportClient(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Int = 5_000,
) {

    /** Negative positions clamp to zero; there is nothing before the start of a track to seek to. */
    suspend fun seek(positionMillis: Long) = withContext(dispatcher) {
        val target = positionMillis.coerceAtLeast(0).toRelTime()
        soapRequest("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")
    }

    private fun soapRequest(action: String, argumentsXml: String) {
        val envelope = buildEnvelope(action, argumentsXml).toByteArray(Charsets.UTF_8)
        val connection = (URL("http://$host:$port$CONTROL_PATH").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPAction", "\"$SERVICE_TYPE#$action\"")
        }
        try {
            connection.outputStream.use { it.write(envelope) }
            // A UPnP fault (bad target, wrong instance id, ...) still answers with a 500 and a SOAP
            // body, not a connection-level failure - so the response code alone is enough to tell a
            // refusal from success without parsing the fault body.
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("AVTransport $action failed: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Fixed, not discovered - every HEOS Built-in Denon/Marantz unit serves this UPnP interface
         * on the same port, and this app already hardcodes the telnet (23) and HEOS CLI (1255) ports
         * the same way. */
        const val DEFAULT_PORT: Int = 60006
        private const val CONTROL_PATH = "/upnp/control/renderer_dvc/AVTransport"
        private const val SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"

        internal fun buildEnvelope(action: String, argumentsXml: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$SERVICE_TYPE\">$argumentsXml</u:$action></s:Body>" +
                "</s:Envelope>"

        /** UPnP `REL_TIME` wants `H:MM:SS`, not milliseconds - confirmed against the real receiver
         * with a bare `H` digit (no leading zero), e.g. `0:01:30`. */
        internal fun Long.toRelTime(): String {
            val totalSeconds = this / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return "%d:%02d:%02d".format(hours, minutes, seconds)
        }
    }
}
