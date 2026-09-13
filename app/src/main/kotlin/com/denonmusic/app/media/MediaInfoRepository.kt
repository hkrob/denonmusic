package com.denonmusic.app.media

import com.denonmusic.data.media.MediaInfoCacheDao
import com.denonmusic.data.media.toAudioFormatInfo
import com.denonmusic.data.media.toCacheEntity
import com.denonmusic.smb.AudioFormatInfo
import com.denonmusic.smb.SmbCredentials
import com.denonmusic.smb.SmbOverlay
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /**
     * jcifs-ng blocks on real socket I/O, so the whole body runs on [Dispatchers.IO] regardless of
     * the caller's own dispatcher - Android throws `NetworkOnMainThreadException` otherwise, and that
     * exception is easy to miss when it lands inside a `runCatching` several calls up the stack.
     */
    suspend fun getFormatInfo(overlay: SmbOverlay, path: String): AudioFormatInfo? = withContext(Dispatchers.IO) {
        val stat = runCatching { overlay.stat(path) }.getOrNull() ?: return@withContext null
        cacheDao.get(path, stat.mtime, stat.size)?.let { return@withContext it.toAudioFormatInfo() }

        val parsed = runCatching { overlay.parseFormat(path) }.getOrNull() ?: return@withContext null
        cacheDao.upsert(parsed.toCacheEntity(path = path, mtime = stat.mtime, size = stat.size, cachedAt = System.currentTimeMillis()))
        parsed
    }
}
