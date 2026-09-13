package com.denonmusic.app.bridge

import com.denonmusic.app.media.MediaInfoRepository
import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.smb.SmbBridgeServer
import com.denonmusic.smb.SmbCredentials
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Owns the phase-6 bridge fallback end to end: given an SMB-relative file path and the saved SMB
 * credentials, produces a `http://<phone-ip>:<port>/<token>` URL that [SmbBridgeServer] will answer
 * with that file's bytes (Range-capable), suitable for `browse/play_stream?url=`.
 *
 * One [SmbBridgeServer] for the process lifetime, started lazily on first use - there is no reason
 * to hold a bound socket open before this degraded path is ever exercised.
 */
@Singleton
class SmbBridgeService @Inject constructor(
    private val mediaInfoRepository: MediaInfoRepository,
    private val settings: SettingsRepository,
) {
    private val resources = ConcurrentHashMap<String, com.denonmusic.smb.BridgeResource>()
    private val server: SmbBridgeServer by lazy { SmbBridgeServer { token -> resources[token] } }

    /** Null when SMB credentials aren't configured yet, or the path doesn't resolve to a real file. */
    suspend fun urlFor(path: String): String? {
        val saved = settings.settings.first()
        val host = saved.smbHost?.takeIf { it.isNotBlank() } ?: return null
        val share = saved.smbShare?.takeIf { it.isNotBlank() } ?: return null
        val credentials = SmbCredentials(host, share, saved.smbUsername.orEmpty(), saved.smbPassword.orEmpty())

        val overlay = mediaInfoRepository.overlayFor(credentials)
        // jcifs-ng blocks on real socket I/O; this must never run on the caller's dispatcher (Main,
        // via viewModelScope) or Android throws NetworkOnMainThreadException.
        val resource = withContext(Dispatchers.IO) {
            runCatching { overlay.openBridgeResource(path) }.getOrNull()
        } ?: return null

        if (!server.isRunning) server.start()
        val token = UUID.randomUUID().toString()
        resources[token] = resource

        val ip = localLanAddress() ?: return null
        return "http://$ip:${server.port}/$token"
    }

    /** First non-loopback IPv4 address on an "up" interface - the address the AVR can reach us at. */
    private fun localLanAddress(): String? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
}
