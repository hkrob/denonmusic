package com.denonmusic.heos

/**
 * Wire-level encoding for the HEOS CLI protocol.
 *
 * Reference: HEOS CLI Protocol Specification v1.17, sections 3.1 and 3.2.
 */
object HeosProtocol {

    /** Commands are terminated with CRLF on the wire. */
    const val TERMINATOR: String = "\r\n"

    /**
     * Attribute name carrying our correlation id.
     *
     * The spec explicitly sanctions a custom `SEQUENCE` argument for matching a response to the
     * command that produced it, because the response `message` field echoes every argument sent.
     */
    const val SEQUENCE: String = "SEQUENCE"

    /**
     * Attribute that the spec requires to appear last in a command.
     *
     * A stream URL may legitimately contain `&` and `=`, which would otherwise be indistinguishable
     * from the command's own attribute delimiters. Putting it last means everything after `url=` can
     * be taken verbatim.
     */
    const val URL: String = "url"

    /**
     * Escapes a single attribute value.
     *
     * The spec calls for `&` -> `%26`, `=` -> `%3D`, `%` -> `%25`. The order matters: `%` must be
     * escaped first, otherwise the `%` introduced by escaping `&` or `=` would itself be escaped a
     * second time and the receiver would decode `%2526` back to the literal text `%26`.
     */
    fun escape(value: String): String =
        value
            .replace("%", "%25")
            .replace("&", "%26")
            .replace("=", "%3D")

    /**
     * Reverses [escape].
     *
     * Mirror-image ordering, for the same reason: `%25` has to be decoded last or it would
     * manufacture a `%` that then gets consumed as the prefix of a neighbouring escape.
     *
     * Only ever apply this to text bound for display. Identifiers (`cid`, `mid`) arrive from
     * `browse` already escaped and must be handed back to the receiver exactly as received.
     */
    fun unescape(value: String): String =
        value
            .replace("%26", "&")
            .replace("%3D", "=")
            .replace("%25", "%")

    /**
     * Renders a command string, without the trailing [TERMINATOR].
     *
     * Attribute order is preserved as given, except that [URL] is forced last per the spec.
     */
    fun buildCommand(
        group: String,
        command: String,
        attributes: List<Pair<String, String>> = emptyList(),
    ): String {
        val (urlAttrs, rest) = attributes.partition { it.first == URL }
        val ordered = rest + urlAttrs
        val query =
            ordered.joinToString("&") { (name, value) -> "$name=${escape(value)}" }
        return if (query.isEmpty()) "heos://$group/$command" else "heos://$group/$command?$query"
    }

    /**
     * Parses the `message` field of a response, which is an `&`-delimited list of `key=value` pairs
     * echoing the command's arguments (plus result metadata such as `count` and `returned`).
     *
     * Values are unescaped, so this must not be used to recover identifiers destined to be sent
     * back to the receiver. Bare keys with no `=` (the spec uses these for flags) map to an empty
     * string. A `url` key consumes the remainder of the message verbatim, since a URL may contain
     * unescaped delimiters and is guaranteed by the spec to be last.
     */
    fun parseMessage(message: String): Map<String, String> {
        if (message.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        var remaining = message
        while (remaining.isNotEmpty()) {
            if (remaining.startsWith("$URL=")) {
                result[URL] = remaining.removePrefix("$URL=")
                break
            }
            val separator = remaining.indexOf('&')
            val pair = if (separator == -1) remaining else remaining.substring(0, separator)
            remaining = if (separator == -1) "" else remaining.substring(separator + 1)
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            if (equals == -1) {
                result[pair] = ""
            } else {
                result[pair.substring(0, equals)] = unescape(pair.substring(equals + 1))
            }
        }
        return result
    }
}
