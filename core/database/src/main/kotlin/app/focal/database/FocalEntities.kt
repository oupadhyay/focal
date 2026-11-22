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
    @androidx.room.ColumnInfo(defaultValue = "0")
    val autoReaderMode: Boolean = false,
    val readerThemeId: String? = null,
    val readerUseSerif: Boolean? = null,
    val readerFontSizeSp: Int? = null,
    val readerLineHeight: Float? = null,
    val updatedAtEpochMs: Long,
    val hiddenCssSelectorsJson: String? = null,
)

@Entity(
    tableName = "history_entries",
    indices = [
        Index(value = ["visitedAtEpochMs"]),
        Index(value = ["url"], unique = true),
    ],
)
data class HistoryEntryEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val domain: String,
    val title: String,
    val visitedAtEpochMs: Long,
    val visitCount: Int = 1,
)

@Entity(
    tableName = "download_records",
    indices = [
        Index(value = ["createdAtEpochMs"]),
    ],
)
data class DownloadRecordEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val originDomain: String,
    val downloadManagerId: Long = -1L,
    val createdAtEpochMs: Long,
)

/**
 * Dependency-free RFC 8259 JSON string-array codec for per-origin Element Zapper CSS selectors
 * stored in [OriginOverrideEntity.hiddenCssSelectorsJson].
 *
 * Pure Kotlin implementation so both Android runtime and JVM unit tests work identically without
 * Android `org.json` stubs or third-party JSON dependencies.
 */
object OriginHiddenSelectorsCodec {

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val input = raw.trim()
        if (input.length < 2 || input.first() != '[' || input.last() != ']') {
            return emptyList()
        }

        val result = LinkedHashSet<String>()
        var i = 1
        val end = input.length - 1

        fun skipWhitespace() {
            while (i < end && input[i].isWhitespace()) i++
        }

        skipWhitespace()
        if (i == end) return emptyList()

        while (i < end) {
            skipWhitespace()
            if (i >= end || input[i] != '"') return emptyList()
            i++ // consume opening '"'

            val sb = StringBuilder()
            var closed = false
            while (i < end) {
                val ch = input[i++]
                if (ch == '"') {
                    closed = true
                    break
                }
                if (ch == '\\') {
                    if (i >= end) return emptyList()
                    when (input[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > end) return emptyList()
                            val codePoint = input.substring(i, i + 4).toIntOrNull(16)
                                ?: return emptyList()
                            sb.append(codePoint.toChar())
                            i += 4
                        }
                        else -> return emptyList()
                    }
                } else {
                    sb.append(ch)
                }
            }
            if (!closed) return emptyList()

            val selector = sb.toString().trim()
            if (selector.isNotEmpty()) {
                result.add(selector)
            }

            skipWhitespace()
            if (i == end) break
            if (input[i] != ',') return emptyList()
            i++ // consume ','
            skipWhitespace()
            if (i == end) return emptyList() // reject trailing comma
        }

        return result.toList()
    }

    fun encode(selectors: List<String>): String? {
        val normalized = selectors
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (normalized.isEmpty()) return null

        val sb = StringBuilder()
        sb.append('[')
        normalized.forEachIndexed { index, selector ->
            if (index > 0) sb.append(',')
            sb.append('"')
            for (ch in selector) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u")
                            sb.append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            sb.append('"')
        }
        sb.append(']')
        return sb.toString()
    }
}
