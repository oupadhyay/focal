package app.focal.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val ambientHex: String,
    val sortOrder: Int,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["spaceId", "sortOrder"]),
        Index(value = ["parentFolderId"]),
        Index(value = ["isPinnedSquircle"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String,
    val spaceId: String,
    val parentFolderId: String? = null,
    val isFolder: Boolean = false,
    val isPinnedSquircle: Boolean = false,
    val shortCode: String? = null,
    val title: String,
    val url: String? = null,
    val subtitle: String? = null,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "tab_metadata",
    indices = [
        Index(value = ["spaceId", "isArchived", "lastActiveEpochMs"]),
        Index(value = ["isArchived", "hasUnsentFormDraft", "lastActiveEpochMs"]),
    ],
)
data class TabMetadataEntity(
    @PrimaryKey
    val id: String,
    val spaceId: String,
    val url: String,
    val domain: String,
    val title: String,
    @ColumnInfo(name = "lastActiveEpochMs")
    val lastActiveEpochMs: Long,
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "hasUnsentFormDraft")
    val hasUnsentFormDraft: Boolean = false,
    @ColumnInfo(name = "isArchived")
    val isArchived: Boolean = false,
    val archivedAtEpochMs: Long? = null,
    val scrollYRatio: Float = 0f,
    val blockedTrackersCount: Int = 0,
) {
    /** Remaining milliseconds before 24h auto-archive (`Long.MAX_VALUE` if draft-protected). */
    fun remainingTtlMs(nowEpochMs: Long, ttlWindowMs: Long = AUTO_ARCHIVE_WINDOW_MS): Long {
        if (hasUnsentFormDraft) return Long.MAX_VALUE
        val expiresAt = lastActiveEpochMs + ttlWindowMs
        return (expiresAt - nowEpochMs).coerceAtLeast(0L)
    }

    companion object {
        const val AUTO_ARCHIVE_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
        const val ARCHIVE_RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}

@Entity(tableName = "origin_overrides")
data class OriginOverrideEntity(
    /** Normalized `eTLD+1` or origin host (e.g., `"arstechnica.com"`). */
    @PrimaryKey
    val origin: String,
    val blockAdsAndTrackers: Boolean = true,
    val forceDarkWebSurface: Boolean = false,
    val overridePublisherFonts: Boolean = false,
    val pageZoomPercent: Int = 100,
    val javascriptEnabled: Boolean = true,
    val desktopSite: Boolean = false,
    val updatedAtEpochMs: Long,
)
