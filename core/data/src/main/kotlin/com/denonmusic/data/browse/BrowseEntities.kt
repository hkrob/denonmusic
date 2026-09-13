package com.denonmusic.data.browse

import androidx.room.Entity

/**
 * One level of "where you were" in the browse tree, ordered by [position] with 0 at the root.
 *
 * The whole stack is replaced as a unit on every navigation (see [BrowseStackDao.replaceStack]) so a
 * pop or push never leaves stale rows below the new depth.
 */
@Entity(tableName = "browse_stack", primaryKeys = ["position"])
data class BrowseStackEntity(
    val position: Int,
    val sid: String,
    val cid: String?,
    val displayName: String,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
)

/**
 * One cached page of a container, keyed by where it came from and which page.
 *
 * [itemsJson] is a serialized `List<CachedBrowseItem>` rather than a Room-relational table: browse
 * pages are read wholesale and never queried by field, so paying for a JSON blob is cheaper than a
 * join, and it keeps this table a faithful mirror of the wire response.
 */
@Entity(tableName = "browse_cache", primaryKeys = ["sid", "cid", "rangeStart"])
data class BrowseCacheEntity(
    val sid: String,
    val cid: String,
    val rangeStart: Int,
    val itemsJson: String,
    val count: Int,
    val returned: Int,
    val optionIdsCsv: String,
    val cachedAt: Long,
)
