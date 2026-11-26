package app.focal.database

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FocalDatabaseTest {

    @Test
    fun migration3To4_createsAllThreeCompositeIndicesWithoutDataLoss() {
        assertEquals(3, FocalDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, FocalDatabase.MIGRATION_3_4.endVersion)

        val statements = captureMigrationSql { FocalDatabase.MIGRATION_3_4.migrate(it) }
        assertEquals(3, statements.size)

        assertTrue(
            "Expected composite index on tab_metadata(isArchived, lastActiveEpochMs)",
            statements.any {
                it.trim() ==
                    "CREATE INDEX IF NOT EXISTS `index_tab_metadata_isArchived_lastActiveEpochMs` ON `tab_metadata` (`isArchived`, `lastActiveEpochMs`)"
            },
        )
        assertTrue(
            "Expected composite index on tab_metadata(isArchived, archivedAtEpochMs)",
            statements.any {
                it.trim() ==
                    "CREATE INDEX IF NOT EXISTS `index_tab_metadata_isArchived_archivedAtEpochMs` ON `tab_metadata` (`isArchived`, `archivedAtEpochMs`)"
            },
        )
        assertTrue(
            "Expected composite index on bookmarks(isPinnedSquircle, sortOrder)",
            statements.any {
                it.trim() ==
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_isPinnedSquircle_sortOrder` ON `bookmarks` (`isPinnedSquircle`, `sortOrder`)"
            },
        )
        assertTrue(
            "MIGRATION_3_4 must be strictly non-destructive (no DROP/DELETE/ALTER)",
            statements.none { sql ->
                sql.contains("DROP TABLE", ignoreCase = true) ||
                    sql.contains("DROP INDEX", ignoreCase = true) ||
                    sql.contains("DELETE FROM", ignoreCase = true) ||
                    sql.contains("ALTER TABLE", ignoreCase = true)
            },
        )
    }

    @Test
    fun historyDao_recordHistoryVisit_atomicallyInsertsAndIncrementsVisitCount() = runBlocking {
        val entriesByUrl = LinkedHashMap<String, HistoryEntryEntity>()
        val fakeDao = object : HistoryDao {
            override fun observeRecentHistory(limit: Int): Flow<List<HistoryEntryEntity>> =
                flowOf(entriesByUrl.values.sortedByDescending { it.visitedAtEpochMs }.take(limit))

            override suspend fun getByUrl(url: String): HistoryEntryEntity? = entriesByUrl[url]

            override suspend fun upsert(entry: HistoryEntryEntity) {
                entriesByUrl[entry.url] = entry
            }

            override suspend fun deleteById(id: String) {
                entriesByUrl.entries.removeIf { it.value.id == id }
            }

            override suspend fun clearAll() {
                entriesByUrl.clear()
            }
        }

        fakeDao.recordHistoryVisit(
            url = "https://arstechnica.com/gadgets/",
            domain = "arstechnica.com",
            title = "Ars Technica",
            visitedAtEpochMs = 1_700_000_000_000L,
        )
        val first = fakeDao.getByUrl("https://arstechnica.com/gadgets/")
        assertNotNull(first)
        assertEquals(1, first!!.visitCount)
        assertEquals("Ars Technica", first.title)

        // Repeat visit with blank title preserves prior ID and title while incrementing visitCount
        fakeDao.recordHistoryVisit(
            url = "https://arstechnica.com/gadgets/",
            domain = "arstechnica.com",
            title = "   ",
            visitedAtEpochMs = 1_700_000_005_000L,
        )
        val second = fakeDao.getByUrl("https://arstechnica.com/gadgets/")
        assertNotNull(second)
        assertEquals(first.id, second!!.id)
        assertEquals(2, second.visitCount)
        assertEquals("Ars Technica", second.title)
        assertEquals(1_700_000_005_000L, second.visitedAtEpochMs)
    }

    private fun captureMigrationSql(block: (SupportSQLiteDatabase) -> Unit): List<String> {
        val executed = mutableListOf<String>()
        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executed += args[0] as String
            }
            null
        } as SupportSQLiteDatabase
        block(proxyDb)
        return executed
    }
}
