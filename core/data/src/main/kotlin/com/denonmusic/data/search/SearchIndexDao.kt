package com.denonmusic.data.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SearchIndexDao {

    /**
     * [needle] is matched pre-lowercased against [SearchIndexEntity.nameLower] (plain `LIKE`, no
     * collation needed since both sides are already the same case) and against artist/album with
     * `COLLATE NOCASE` instead, since those two are stored as the receiver sent them.
     */
    @Query(
        "SELECT * FROM search_index WHERE " +
            "nameLower LIKE '%' || :needle || '%' " +
            "OR (artist IS NOT NULL AND artist LIKE '%' || :needle || '%' COLLATE NOCASE) " +
            "OR (album IS NOT NULL AND album LIKE '%' || :needle || '%' COLLATE NOCASE) " +
            "ORDER BY name COLLATE NOCASE LIMIT :limit",
    )
    suspend fun search(needle: String, limit: Int = 200): List<SearchIndexEntity>

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun count(): Int

    @Query("SELECT MAX(indexedAt) FROM search_index")
    suspend fun lastIndexedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SearchIndexEntity>)

    @Query("DELETE FROM search_index")
    suspend fun clear()

    /**
     * Swaps the whole index in one transaction, so a reader never sees a half-replaced table - the
     * old rows stay searchable right up until the new ones are all in.
     */
    @Transaction
    suspend fun replaceAll(rows: List<SearchIndexEntity>) {
        clear()
        insertAll(rows)
    }
}
