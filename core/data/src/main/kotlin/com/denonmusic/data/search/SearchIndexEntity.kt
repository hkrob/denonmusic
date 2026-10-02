package com.denonmusic.data.search

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.denonmusic.heos.LibraryPathEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One row of the whole-library search index, rebuilt wholesale by each reindex (see the app's
 * `SearchIndexRepository`) rather than diffed - a crawl is already a full read of everything, and
 * there is no cheap way to know what changed on the DLNA/SMB side without one.
 *
 * [pathJson] is a serialized `List<LibraryPathEntry>`, same JSON-blob-over-relational-table choice as
 * [com.denonmusic.data.browse.BrowseCacheEntity.itemsJson] - it is read back wholesale (to push onto
 * the browse stack) and never queried by field, and it's empty for a track row, which is queued
 * directly rather than navigated to.
 */
@Entity(tableName = "search_index", indices = [Index("nameLower")])
data class SearchIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameLower: String,
    val artist: String?,
    val album: String?,
    val isContainer: Boolean,
    val isTrack: Boolean,
    val pathJson: String,
    val queueSid: String,
    val queueCid: String,
    val mid: String?,
    val indexedAt: Long,
)

private val json = Json { ignoreUnknownKeys = true }

fun List<LibraryPathEntry>.toPathJson(): String = json.encodeToString(this)

fun String.fromPathJson(): List<LibraryPathEntry> =
    if (isEmpty()) emptyList() else json.decodeFromString(this)
