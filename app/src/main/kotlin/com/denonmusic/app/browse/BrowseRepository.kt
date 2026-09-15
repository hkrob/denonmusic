package com.denonmusic.app.browse

import com.denonmusic.data.browse.BrowseCacheDao
import com.denonmusic.data.browse.BrowseCacheDao.Companion.ROOT_CID
import com.denonmusic.data.browse.BrowseCacheEntity
import com.denonmusic.data.browse.BrowseStackDao
import com.denonmusic.data.browse.BrowseStackEntity
import com.denonmusic.data.browse.fromCacheJson
import com.denonmusic.data.browse.toCacheJson
import com.denonmusic.heos.BrowseItem
import com.denonmusic.heos.BrowseOption
import com.denonmusic.heos.HeosClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class BrowseLevel(
    val sid: String,
    val cid: String?,
    val displayName: String,
)

data class BrowseListing(
    val items: List<BrowseItem>,
    val isPlayableContainer: Boolean,
    val fromCache: Boolean,
)

/**
 * Combines the HEOS `browse` calls with the Room-backed cache and browse stack.
 *
 * Restore paints from [BrowseCacheDao] instantly (works with the AVR off), then a fresh fetch runs
 * in the background and both updates the cache and re-emits so the screen catches up.
 */
@Singleton
class BrowseRepository @Inject constructor(
    private val stackDao: BrowseStackDao,
    private val cacheDao: BrowseCacheDao,
) {

    fun observeStack(): Flow<List<BrowseStackEntity>> = stackDao.observeStack()

    suspend fun currentStack(): List<BrowseStackEntity> = stackDao.getStack()

    suspend fun replaceStack(levels: List<BrowseLevel>) {
        stackDao.replaceStack(
            levels.mapIndexed { index, level ->
                BrowseStackEntity(position = index, sid = level.sid, cid = level.cid, displayName = level.displayName)
            },
        )
    }

    suspend fun truncateAfter(position: Int) = stackDao.truncateAfter(position)

    suspend fun updateScroll(position: Int, index: Int, offset: Int) =
        stackDao.updateScroll(position, index, offset)

    /** Cache paints first when present, then a fresh network listing follows once it lands. */
    fun listing(client: HeosClient, sid: String, cid: String?): Flow<BrowseListing> = flow {
        val cacheKey = cid ?: ROOT_CID
        val cached = cacheDao.getAllPages(sid, cacheKey)
        if (cached.isNotEmpty()) {
            emit(
                BrowseListing(
                    items = cached.sortedBy { it.rangeStart }.flatMap { it.itemsJson.fromCacheJson() },
                    isPlayableContainer = cached.any { it.optionIdsCsv.split(",").contains(BrowseOption.PLAYABLE_CONTAINER.toString()) },
                    fromCache = true,
                ),
            )
        }

        val all = mutableListOf<BrowseItem>()
        var isPlayableContainer = false
        var start = 0
        client.browseAll(sid, cid).collect { page ->
            all += page.items
            isPlayableContainer = isPlayableContainer || page.isPlayableContainer
            cacheDao.upsert(
                BrowseCacheEntity(
                    sid = sid,
                    cid = cacheKey,
                    rangeStart = start,
                    itemsJson = page.items.toCacheJson(),
                    count = page.count,
                    returned = page.returned,
                    optionIdsCsv = page.optionIds.joinToString(","),
                    cachedAt = System.currentTimeMillis(),
                ),
            )
            start += page.items.size
            emit(BrowseListing(items = all.toList(), isPlayableContainer = isPlayableContainer, fromCache = false))
        }
        // An empty container (no pages at all, e.g. no share configured yet) never enters the
        // collect block above, so nothing marks loading finished without this fallback emission.
        if (start == 0) {
            emit(BrowseListing(items = emptyList(), isPlayableContainer = false, fromCache = false))
        }
    }

    /**
     * Tries the deepest cached level first; on *any* failure - a missing-entity error (server
     * reindexed, share renamed), a busy/system HEOS error, a timeout, whatever - pops one level and
     * retries, so a stale restore lands you somewhere real instead of at an error screen.
     *
     * This must never let an exception escape: it runs unconditionally on every reconnect (see
     * [BrowseViewModel]'s bootstrap), so anything thrown here previously became a permanent
     * crash-on-launch loop the moment one persisted level started failing for any reason - the app
     * would crash before the user ever got a chance to back out of that folder, and only clearing
     * app data (which wipes this very stack) could recover it. Confirmed against a real crash report
     * matching exactly that shape.
     */
    suspend fun restoreResolving(client: HeosClient): List<BrowseStackEntity> {
        var stack = stackDao.getStack()
        while (stack.isNotEmpty()) {
            val deepest = stack.last()
            val resolved = runCatching { client.browse(deepest.sid, deepest.cid?.takeIf { it.isNotEmpty() }, 0, 0) }
            if (resolved.isSuccess) return stack
            stack = stack.dropLast(1)
            stackDao.truncateAfter(stack.lastIndex)
        }
        return stack
    }
}
