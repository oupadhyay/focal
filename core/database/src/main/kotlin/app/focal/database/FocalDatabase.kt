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
    ],
    version = 2,
    exportSchema = false,
)
abstract class FocalDatabase : RoomDatabase() {
    abstract fun spaceDao(): SpaceDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tabMetadataDao(): TabMetadataDao
    abstract fun originOverrideDao(): OriginOverrideDao

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

        @Volatile
        private var instance: FocalDatabase? = null

        fun getInstance(context: Context): FocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FocalDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
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
