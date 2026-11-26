package app.focal.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SpaceEntity::class,
        BookmarkEntity::class,
        TabMetadataEntity::class,
        OriginOverrideEntity::class,
        HistoryEntryEntity::class,
        DownloadRecordEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class FocalDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tabMetadataDao(): TabMetadataDao
    abstract fun originOverrideDao(): OriginOverrideDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadRecordDao(): DownloadRecordDao

    companion object {
        private const val DB_NAME = "focal_browser.db"

        /**
         * Non-destructive migration adding per-domain sticky Reader Mode settings to
         * `origin_overrides` without losing existing spaces, bookmarks, tabs, or overrides.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE origin_overrides ADD COLUMN autoReaderMode INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE origin_overrides ADD COLUMN readerThemeId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE origin_overrides ADD COLUMN readerUseSerif INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE origin_overrides ADD COLUMN readerFontSizeSp INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE origin_overrides ADD COLUMN readerLineHeight REAL",
                )
            }
        }

        /**
         * Non-destructive migration adding browsing history (`history_entries`), download records
         * (`download_records`), and per-origin Element Zapper selectors
         * (`origin_overrides.hiddenCssSelectorsJson`) without losing existing user data.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `history_entries` (
                        `id` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `visitedAtEpochMs` INTEGER NOT NULL,
                        `visitCount` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_history_entries_visitedAtEpochMs` ON `history_entries` (`visitedAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entries_url` ON `history_entries` (`url`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_records` (
                        `id` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `mimeType` TEXT,
                        `originDomain` TEXT NOT NULL,
                        `downloadManagerId` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_records_createdAtEpochMs` ON `download_records` (`createdAtEpochMs`)",
                )
                db.execSQL(
                    "ALTER TABLE origin_overrides ADD COLUMN hiddenCssSelectorsJson TEXT",
                )
            }
        }

        /**
         * Non-destructive migration adding composite indices on `tab_metadata` and `bookmarks`
         * to eliminate full-table scans and temp B-tree sorts on active tabs, archived tabs, and
         * quick-search pinned squircles.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tab_metadata_isArchived_lastActiveEpochMs` ON `tab_metadata` (`isArchived`, `lastActiveEpochMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tab_metadata_isArchived_archivedAtEpochMs` ON `tab_metadata` (`isArchived`, `archivedAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_isPinnedSquircle_sortOrder` ON `bookmarks` (`isPinnedSquircle`, `sortOrder`)",
                )
            }
        }

        @Volatile
        private var instance: FocalDatabase? = null

        fun getInstance(context: Context): FocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FocalDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}

object FocalDatabaseDefaults {
    suspend fun seedIfEmpty(db: FocalDatabase, nowEpochMs: Long = System.currentTimeMillis()) {
        if (db.spaceDao().countSpaces() > 0) return

        db.spaceDao().upsertAll(
            listOf(
                SpaceEntity(
                    id = "personal",
                    name = "Personal",
                    ambientHex = "#F4725E",
                    sortOrder = 0,
                    updatedAtEpochMs = nowEpochMs,
                ),
                SpaceEntity(
                    id = "work",
                    name = "Work",
                    ambientHex = "#94A3B8",
                    sortOrder = 1,
                    updatedAtEpochMs = nowEpochMs,
                ),
                SpaceEntity(
                    id = "reading",
                    name = "Reading",
                    ambientHex = "#4ADE80",
                    sortOrder = 2,
                    updatedAtEpochMs = nowEpochMs,
                ),
            ),
        )

        db.bookmarkDao().upsertAll(
            listOf(
                BookmarkEntity(
                    id = "pin-imdb",
                    spaceId = "personal",
                    isPinnedSquircle = true,
                    shortCode = "IM",
                    title = "IMDb",
                    url = "https://www.imdb.com",
                    sortOrder = 0,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-wiki",
                    spaceId = "personal",
                    isPinnedSquircle = true,
                    shortCode = "WI",
                    title = "Wikipedia",
                    url = "https://en.wikipedia.org",
                    sortOrder = 1,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-youtube",
                    spaceId = "personal",
                    isPinnedSquircle = true,
                    shortCode = "YT",
                    title = "Watch",
                    url = "https://m.youtube.com",
                    sortOrder = 2,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-reddit",
                    spaceId = "personal",
                    isPinnedSquircle = true,
                    shortCode = "RD",
                    title = "Reddit",
                    url = "https://www.reddit.com",
                    sortOrder = 3,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-github",
                    spaceId = "work",
                    isPinnedSquircle = true,
                    shortCode = "GH",
                    title = "GitHub",
                    url = "https://github.com",
                    sortOrder = 0,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-android",
                    spaceId = "work",
                    isPinnedSquircle = true,
                    shortCode = "DV",
                    title = "Android",
                    url = "https://developer.android.com",
                    sortOrder = 1,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-docsrs",
                    spaceId = "work",
                    isPinnedSquircle = true,
                    shortCode = "RS",
                    title = "Docs.rs",
                    url = "https://docs.rs",
                    sortOrder = 2,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-hn",
                    spaceId = "reading",
                    isPinnedSquircle = true,
                    shortCode = "HN",
                    title = "HN",
                    url = "https://news.ycombinator.com",
                    sortOrder = 0,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "pin-ars",
                    spaceId = "reading",
                    isPinnedSquircle = true,
                    shortCode = "AR",
                    title = "Ars",
                    url = "https://arstechnica.com",
                    sortOrder = 1,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "folder-watchlist",
                    spaceId = "personal",
                    isFolder = true,
                    title = "Watchlist",
                    subtitle = "Dune Messiah · Severance · Nosferatu",
                    sortOrder = 10,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "folder-shaders",
                    spaceId = "work",
                    isFolder = true,
                    title = "AGSL & 120Hz Shaders",
                    subtitle = "RuntimeShader · RenderEffect · PixelCopy",
                    sortOrder = 10,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
                BookmarkEntity(
                    id = "folder-longform",
                    spaceId = "reading",
                    isFolder = true,
                    title = "Longform Queue",
                    subtitle = "Browser engines · Optical typography",
                    sortOrder = 10,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            ),
        )
    }
}
