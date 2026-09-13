package com.denonmusic.data.browse

import com.denonmusic.heos.BrowseItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A [BrowseItem] flattened for JSON storage in [BrowseCacheEntity.itemsJson]. */
@Serializable
data class CachedBrowseItem(
    val name: String,
    val imageUrl: String?,
    val mediaType: String?,
    val cid: String?,
    val mid: String?,
    val isContainer: Boolean,
    val isPlayable: Boolean,
    val artist: String?,
    val album: String?,
    val sid: String? = null,
) {
    fun toBrowseItem(): BrowseItem =
        BrowseItem(name, imageUrl, mediaType, cid, mid, isContainer, isPlayable, artist, album, sid)

    companion object {
        fun from(item: BrowseItem): CachedBrowseItem = CachedBrowseItem(
            name = item.name,
            imageUrl = item.imageUrl,
            mediaType = item.mediaType,
            cid = item.cid,
            mid = item.mid,
            isContainer = item.isContainer,
            isPlayable = item.isPlayable,
            artist = item.artist,
            album = item.album,
            sid = item.sid,
        )
    }
}

private val json = Json { ignoreUnknownKeys = true }

fun List<BrowseItem>.toCacheJson(): String = json.encodeToString(map { CachedBrowseItem.from(it) })

fun String.fromCacheJson(): List<BrowseItem> =
    json.decodeFromString<List<CachedBrowseItem>>(this).map { it.toBrowseItem() }
