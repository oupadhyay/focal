package app.focal.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Query("SELECT * FROM spaces ORDER BY sortOrder ASC")
    fun observeSpaces(): Flow<List<SpaceEntity>>

    @Query("SELECT COUNT(*) FROM spaces")
    suspend fun countSpaces(): Int

    @Upsert
    suspend fun upsertSpace(space: SpaceEntity)

    @Upsert
    suspend fun upsertAll(spaces: List<SpaceEntity>)

    @Query("DELETE FROM spaces WHERE id = :spaceId")
    suspend fun deleteSpace(spaceId: String)
}

@Dao
interface BookmarkDao {
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE spaceId = :spaceId AND isPinnedSquircle = 1
        ORDER BY sortOrder ASC
        """,
    )
    fun observePinnedBySpace(spaceId: String): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE spaceId = :spaceId AND isPinnedSquircle = 0
        ORDER BY isFolder DESC, sortOrder ASC, updatedAtEpochMs DESC
        """,
    )
    fun observeItemsBySpace(spaceId: String): Flow<List<BookmarkEntity>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE isPinnedSquircle = 1 AND url IS NOT NULL
        ORDER BY sortOrder ASC
        LIMIT :limit
        """,
    )
    fun observeQuickSearchPins(limit: Int = 5): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY sortOrder ASC, updatedAtEpochMs DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Upsert
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Upsert
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: String)
}

@Dao
interface TabMetadataDao {
    @Query(
        """
        SELECT * FROM tab_metadata
        WHERE spaceId = :spaceId AND isArchived = 0
        ORDER BY lastActiveEpochMs DESC
        """,
    )
    fun observeActiveTabsForSpace(spaceId: String): Flow<List<TabMetadataEntity>>

    @Query(
        """
        SELECT * FROM tab_metadata
        WHERE isArchived = 0
        ORDER BY lastActiveEpochMs DESC
        """,
    )
    fun observeAllActiveTabs(): Flow<List<TabMetadataEntity>>

    @Query(
        """
        SELECT * FROM tab_metadata
        WHERE isArchived = 1
          AND (archivedAtEpochMs IS NULL OR archivedAtEpochMs >= :sinceEpochMs)
        ORDER BY archivedAtEpochMs DESC
        """,
    )
    fun observeArchivedTabs(sinceEpochMs: Long): Flow<List<TabMetadataEntity>>

    /**
     * Finds tabs idle past [cutoffEpochMs] (`now - 24h`) while strictly excluding any tab
     * where `hasUnsentFormDraft == 1` (`true`).
     */
    @Query(
        """
        SELECT * FROM tab_metadata
        WHERE isArchived = 0
          AND hasUnsentFormDraft = 0
          AND lastActiveEpochMs <= :cutoffEpochMs
        ORDER BY lastActiveEpochMs ASC
        """,
    )
    suspend fun findTabsEligibleForAutoArchive(cutoffEpochMs: Long): List<TabMetadataEntity>

    /**
     * Atomically sweeps idle tabs (`lastActiveEpochMs <= :cutoffEpochMs`) into the 24h Tidied Tray
     * while **never** auto-closing tabs with unsent `<input>/<textarea>/[contenteditable]` drafts
     * (`hasUnsentFormDraft == 0`).
     */
    @Query(
        """
        UPDATE tab_metadata
        SET isArchived = 1,
            archivedAtEpochMs = :nowEpochMs
        WHERE isArchived = 0
          AND hasUnsentFormDraft = 0
          AND lastActiveEpochMs <= :cutoffEpochMs
        """,
    )
    suspend fun autoArchiveIdleTabsExcludingDrafts(
        cutoffEpochMs: Long,
        nowEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE tab_metadata
        SET isArchived = 0,
            archivedAtEpochMs = NULL,
            lastActiveEpochMs = :nowEpochMs
        WHERE id = :tabId
        """,
    )
    suspend fun restoreArchivedTab(tabId: String, nowEpochMs: Long)

    @Query(
        """
        UPDATE tab_metadata
        SET hasUnsentFormDraft = :hasUnsentFormDraft
        WHERE id = :tabId
        """,
    )
    suspend fun updateUnsentFormDraft(tabId: String, hasUnsentFormDraft: Boolean)

    @Query(
        """
        UPDATE tab_metadata
        SET lastActiveEpochMs = :nowEpochMs
        WHERE id = :tabId
        """,
    )
    suspend fun touchTab(tabId: String, nowEpochMs: Long)

    @Query(
        """
        UPDATE tab_metadata
        SET spaceId = :targetSpaceId,
            lastActiveEpochMs = :nowEpochMs
        WHERE id = :tabId
        """,
    )
    suspend fun moveTabToSpace(tabId: String, targetSpaceId: String, nowEpochMs: Long)

    @Query(
        """
        DELETE FROM tab_metadata
        WHERE isArchived = 1
          AND archivedAtEpochMs IS NOT NULL
          AND archivedAtEpochMs < :olderThanEpochMs
        """,
    )
    suspend fun purgeExpiredArchives(olderThanEpochMs: Long): Int

    @Upsert
    suspend fun upsertTab(tab: TabMetadataEntity)

    @Upsert
    suspend fun upsertAll(tabs: List<TabMetadataEntity>)

    @Query("DELETE FROM tab_metadata WHERE id = :tabId")
    suspend fun deleteTab(tabId: String)
}

@Dao
interface OriginOverrideDao {
    @Query("SELECT * FROM origin_overrides WHERE origin = :origin LIMIT 1")
    fun observeOverrideForOrigin(origin: String): Flow<OriginOverrideEntity?>

    @Query("SELECT * FROM origin_overrides WHERE origin = :origin LIMIT 1")
    suspend fun getOverrideForOrigin(origin: String): OriginOverrideEntity?

    @Upsert
    suspend fun upsertOverride(overrideEntity: OriginOverrideEntity)

    @Query("DELETE FROM origin_overrides WHERE origin = :origin")
    suspend fun deleteOverride(origin: String)
}
