package com.denonmusic.data.media

import androidx.room.Entity
import com.denonmusic.smb.AudioContainer
import com.denonmusic.smb.AudioFormatInfo

/**
 * A parsed header's result, keyed by the file identity the plan specifies: path + mtime + size.
 * Any one of those three changing (a re-encode, a replaced file at the same path) is a cache miss by
 * construction - there's no separate invalidation path to keep in sync.
 */
@Entity(tableName = "media_info_cache", primaryKeys = ["path", "mtime", "size"])
data class MediaInfoCacheEntity(
    val path: String,
    val mtime: Long,
    val size: Long,
    val container: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val bitrateKbps: Int?,
    val isVbr: Boolean?,
    val cachedAt: Long,
)

fun AudioFormatInfo.toCacheEntity(path: String, mtime: Long, size: Long, cachedAt: Long): MediaInfoCacheEntity =
    MediaInfoCacheEntity(
        path = path,
        mtime = mtime,
        size = size,
        container = container.name,
        sampleRateHz = sampleRateHz,
        bitsPerSample = bitsPerSample,
        channels = channels,
        bitrateKbps = bitrateKbps,
        isVbr = isVbr,
        cachedAt = cachedAt,
    )

fun MediaInfoCacheEntity.toAudioFormatInfo(): AudioFormatInfo = AudioFormatInfo(
    container = AudioContainer.valueOf(container),
    sampleRateHz = sampleRateHz,
    bitsPerSample = bitsPerSample,
    channels = channels,
    bitrateKbps = bitrateKbps,
    isVbr = isVbr,
)
