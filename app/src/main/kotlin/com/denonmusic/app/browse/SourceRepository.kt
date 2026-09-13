package com.denonmusic.app.browse

import com.denonmusic.data.settings.SettingsRepository
import com.denonmusic.heos.HeosClient
import com.denonmusic.heos.MusicSource
import javax.inject.Inject
import javax.inject.Singleton

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
}
