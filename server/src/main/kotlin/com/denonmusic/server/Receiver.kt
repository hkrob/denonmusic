package com.denonmusic.server

import com.denonmusic.avr.AvrClient
import com.denonmusic.avr.AvrSession
import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.HeosSession

/** A failure with an HTTP status of its own, so a route can throw rather than thread a result type. */
class ApiException(val status: Int, message: String) : RuntimeException(message)

/** A command connection plus the player to address on it - the two things every HEOS command needs. */
data class Player(val client: HeosClient, val pid: String)

/**
 * The server's one set of connections to the receiver: the same [HeosSession] and [AvrSession] the
 * Android app runs, so reconnect, heartbeat and the never-cached pid lookup are theirs, not restated.
 */
class Receiver(private val config: Config) {

    val heos = HeosSession(config.heosPort)
    val avr = AvrSession(config.avrPort)

    @Volatile
    private var cachedSourceSid: String? = null

    fun start() {
        heos.start(config.receiverHost)
        avr.start(config.receiverHost)
    }

    fun stop() {
        heos.stop()
        avr.stop()
    }

    fun heosClient(): HeosClient = heos.heosClient ?: throw ApiException(503, "HEOS not connected")

    fun avrClient(): AvrClient = avr.avrClient ?: throw ApiException(503, "AVR not connected")

    /** Resolved per request - see [HeosSession.resolvePid] for why a latched pid is a trap. */
    suspend fun player(): Player {
        val client = heosClient()
        val pid = heos.resolvePid() ?: throw ApiException(503, "no player found")
        return Player(client, pid)
    }

    /**
     * The queueable source: the remembered sid if it still answers, re-detected if it does not. The
     * same validate-then-fall-back rule as the app's `SourceRepository.resolveCachedSid`, kept in
     * memory because the server has no settings store and one `get_music_sources` per restart is
     * nothing.
     */
    suspend fun sourceSid(client: HeosClient): String {
        val cached = cachedSourceSid
        if (cached != null && runCatching { client.browse(cached, null, 0, 0) }.isSuccess) return cached
        val detected = client.getMusicSources().firstOrNull { it.available && it.isLocalMedia }?.sid
            ?: throw ApiException(503, "no queueable music source found")
        cachedSourceSid = detected
        return detected
    }
}
