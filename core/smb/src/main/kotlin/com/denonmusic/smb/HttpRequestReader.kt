package com.denonmusic.smb

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Reads the request line and headers of one HTTP request, with a ceiling on how much it will hold.
 *
 * Both of this app's servers ([SmbBridgeServer] and the app's LAN control server) listen on
 * `0.0.0.0` and read these lines before they know who is asking. `BufferedReader.readLine` has no
 * limit of its own, so anything on the LAN could feed either one a line that never ends and have
 * the phone buffer it until the process ran out of memory.
 *
 * A line longer than [maxLineChars] is read to its end and thrown away rather than abandoned
 * half-way, and [overflowed] records that it happened: the caller can then still read the headers
 * and answer with a proper 400, where closing a socket with unread bytes on it resets the
 * connection and the client never sees the status. [maxTotalChars] bounds that patience - past it
 * the request is not worth an answer.
 */
class HttpRequestReader(
    input: InputStream,
    private val maxLineChars: Int = MAX_LINE_CHARS,
    private val maxTotalChars: Int = MAX_TOTAL_CHARS,
) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
    private var consumed = 0

    /** True once any line had to be cut short at [maxLineChars]. */
    var overflowed: Boolean = false
        private set

    /** The next line without its terminator, or null at end of stream. */
    fun readLine(): String? {
        val line = StringBuilder()
        var sawAnything = false
        while (true) {
            val c = reader.read()
            if (c == -1) return if (sawAnything) line.toString() else null
            sawAnything = true
            if (++consumed > maxTotalChars) throw IOException("HTTP request too large")
            if (c == '\n'.code) return line.toString()
            if (c == '\r'.code) continue
            if (line.length < maxLineChars) line.append(c.toChar()) else overflowed = true
        }
    }

    /** Header names lower-cased, up to the blank line that ends them. */
    fun readHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon == -1) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return headers
    }

    companion object {
        /** Generous for a request line or header: this app's own longest is an SMB path in a query. */
        const val MAX_LINE_CHARS: Int = 8 * 1024

        const val MAX_TOTAL_CHARS: Int = 256 * 1024
    }
}
