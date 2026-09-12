package com.denonmusic.heos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Decodes the newline-delimited JSON frames the receiver writes on the CLI port. */
object HeosFrameParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns null for anything that is not a well-formed HEOS frame, including the blank lines and
     * banner text that can appear on a freshly opened socket.
     */
    fun parse(line: String): HeosFrame? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        val heos = root["heos"]?.asObjectOrNull() ?: return null
        // The receiver pads command names with spaces in some responses ("browse/add_to_queue ").
        val command = heos.str("command")?.trim() ?: return null
        return HeosFrame(
            command = command,
            result = heos.str("result")?.trim(),
            attributes = HeosProtocol.parseMessage(heos.str("message").orEmpty()),
            payload = root["payload"],
            options = root["options"],
        )
    }
}
