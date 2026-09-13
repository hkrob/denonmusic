package com.denonmusic.app.media

import com.denonmusic.data.media.MediaInfoCacheDao
import com.denonmusic.data.media.toAudioFormatInfo
import com.denonmusic.data.media.toCacheEntity
import com.denonmusic.smb.AudioFormatInfo
import com.denonmusic.smb.SmbCredentials
import com.denonmusic.smb.SmbOverlay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the network-facing [SmbOverlay] and the Room cache in `:core:data`, keyed by
 * path+mtime+size per the plan - a `stat` call is cheap next to opening and parsing the file itself,
 * so it's always done first even on a cache hit, which is what makes the cache key trustworthy (a
 * replaced file at the same path changes its mtime or size, and therefore its cache key).
 */
@Singleton
class MediaInfoRepository @Inject constructor(
    private val cacheDao: MediaInfoCacheDao,
) {
    fun overlayFor(credentials: SmbCredentials): SmbOverlay = SmbOverlay(credentials)

    suspend fun getFormatInfo(overlay: SmbOverlay, path: String): AudioFormatInfo? {
        val stat = runCatching { overlay.stat(path) }.getOrNull() ?: return null
        cacheDao.get(path, stat.mtime, stat.size)?.let { return it.toAudioFormatInfo() }

        val parsed = runCatching { overlay.parseFormat(path) }.getOrNull() ?: return null
        cacheDao.upsert(parsed.toCacheEntity(path = path, mtime = stat.mtime, size = stat.size, cachedAt = System.currentTimeMillis()))
        return parsed
    }
}
