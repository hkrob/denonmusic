package com.denonmusic.server

import java.io.File

/**
 * Everything the server needs to know about where it is running, read from the environment and
 * nowhere else - the repository and the image carry no receiver address and no token.
 */
data class Config(
    val receiverHost: String,
    val apiToken: String,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val dataDir: File = File(DEFAULT_DATA_DIR),
    val maxVolume: Int = DEFAULT_MAX_VOLUME,
    val volumeStep: Int = DEFAULT_VOLUME_STEP,
    val heosPort: Int = com.denonmusic.heos.HeosConnection.DEFAULT_PORT,
    val avrPort: Int = com.denonmusic.avr.AvrConnection.DEFAULT_PORT,
) {
    companion object {
        /** The same port the Android app's own LAN control API uses, so one habit covers both. */
        const val DEFAULT_HTTP_PORT = 8901
        const val DEFAULT_DATA_DIR = "/data"

        /**
         * A ceiling on what this API will set the HEOS volume (0-100) to. The caller at the far end
         * of this is a speech recogniser, and "volume ninety" is one mishearing away from "nineteen".
         */
        const val DEFAULT_MAX_VOLUME = 60
        const val DEFAULT_VOLUME_STEP = 5

        /** Refuses to produce a config without a token, as the app's LAN server refuses to start. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) =
                env[name]?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("$name is not set")

            fun int(name: String, default: Int) =
                env[name]?.takeIf { it.isNotBlank() }?.let {
                    it.toIntOrNull() ?: throw IllegalStateException("$name must be a number, got '$it'")
                } ?: default

            return Config(
                receiverHost = required("DENON_HOST"),
                apiToken = required("DENON_API_TOKEN"),
                httpPort = int("DENON_HTTP_PORT", DEFAULT_HTTP_PORT),
                dataDir = File(env["DENON_DATA_DIR"]?.takeIf { it.isNotBlank() } ?: DEFAULT_DATA_DIR),
                maxVolume = int("DENON_MAX_VOLUME", DEFAULT_MAX_VOLUME).coerceIn(0, 100),
                volumeStep = int("DENON_VOLUME_STEP", DEFAULT_VOLUME_STEP).coerceIn(1, 20),
            )
        }
    }
}
