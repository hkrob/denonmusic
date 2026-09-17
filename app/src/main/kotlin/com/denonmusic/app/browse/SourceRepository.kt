package com.denonmusic.app.browse

import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.MusicSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Ranks `browse/get_music_sources` per the plan: sid 1024 ("Local USB Media / Local DLNA servers",
 * which is also where a HEOS-native SMB Network Share surfaces) first, since it is the only source
 * that supports `add_to_queue` and therefore gapless playback and DSD. Everything else is the bridge
 * fallback's job, not this one's.
 */
@Singleton
class SourceRepository @Inject constructor(private val settings: SettingsRepository) {

    suspend fun detectAndPersist(client: HeosClient): MusicSource? {
        val sources = client.getMusicSources()
        val chosen = sources.firstOrNull { it.available && it.isLocalMedia }
        if (chosen != null) settings.setSelectedSourceSid(chosen.sid)
        return chosen
    }

    /**
     * The persisted sid if it still resolves, re-detecting from scratch if it doesn't.
     *
     * Same validate-then-fall-back shape as [BrowseRepository.restoreResolving], and for the same
     * reason: a sid is only as good as the receiver's current source registry, and a renamed share
     * or a HEOS reindex silently invalidates it. A caller that latches one for the life of the
     * process gets a `/browse` that is broken until the app is killed, while the in-app browser
     * sitting next to it recovers on its own. Validation is one range-0-to-0 browse, so the cached
     * path stays a single cheap round trip.
     */
    suspend fun resolveCachedSid(client: HeosClient): String? {
        val cached = settings.settings.first().selectedSourceSid
        if (cached != null && runCatching { client.browse(cached, null, 0, 0) }.isSuccess) return cached
        return runCatching { detectAndPersist(client) }.getOrNull()?.sid
    }
}
