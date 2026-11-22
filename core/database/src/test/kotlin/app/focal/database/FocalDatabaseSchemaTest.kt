package app.focal.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FocalDatabaseSchemaTest {

    @Test
    fun originHiddenSelectorsCodec_emptyBlankAndMalformedInputsReturnEmpty() {
        assertNull(OriginHiddenSelectorsCodec.encode(emptyList()))
        assertNull(OriginHiddenSelectorsCodec.encode(listOf("", "   ", "\t\n")))

        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode(null))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode(""))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode("   "))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode("[]"))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode(" [  ] "))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode("not-json"))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode("[\"unterminated"))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode("[\".ad\",]"))
        assertEquals(emptyList<String>(), OriginHiddenSelectorsCodec.decode("[123]"))
    }

    @Test
    fun originHiddenSelectorsCodec_roundTripsComplexCssSelectorsCleanly() {
        val selectors = listOf(
            ".ad-banner",
            "#cookie-consent-wall",
            """a[href="https://ads.example.com/click?a=1&b=2"]""",
            """section[data-label='promo, "featured"'], div > span:nth-child(2n + 1)""",
            """div[data-path="C:\\temp\\overlay"]""",
            """aside[aria-label="広告 — 关闭"]""",
            "main > article:has(> .sponsored-slot) + aside",
        )

        val encoded = OriginHiddenSelectorsCodec.encode(selectors)
        assertTrue("Encoded JSON array must start with '[' and end with ']'", encoded != null && encoded.startsWith("[") && encoded.endsWith("]"))
        assertEquals(selectors, OriginHiddenSelectorsCodec.decode(encoded))
    }

    @Test
    fun originHiddenSelectorsCodec_trimsDeduplicatesAndHandlesEscapes() {
        val encoded = OriginHiddenSelectorsCodec.encode(
            listOf("  .banner  ", "", ".banner", "   ", "#modal", "#modal"),
        )
        assertEquals(listOf(".banner", "#modal"), OriginHiddenSelectorsCodec.decode(encoded))

        val withControlChars = listOf("div[data-note=\"line1\nline2\tindented\r\"]")
        val encodedControl = OriginHiddenSelectorsCodec.encode(withControlChars)
        assertEquals(withControlChars, OriginHiddenSelectorsCodec.decode(encodedControl))

        val unicodeEscaped = """["div\u0020.promo", "span[title=\"a\/b\"]"]"""
        assertEquals(listOf("div .promo", """span[title="a/b"]"""), OriginHiddenSelectorsCodec.decode(unicodeEscaped))
    }

    @Test
    fun migrations_exposeExpectedVersionStepsAndNonDestructiveDdl() {
        assertEquals(1, FocalDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, FocalDatabase.MIGRATION_1_2.endVersion)
        assertEquals(2, FocalDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, FocalDatabase.MIGRATION_2_3.endVersion)

        val statementsV1ToV2 = captureMigrationSql { FocalDatabase.MIGRATION_1_2.migrate(it) }
        assertEquals(5, statementsV1ToV2.size)
        assertTrue(statementsV1ToV2.all { it.startsWith("ALTER TABLE origin_overrides ADD COLUMN ") })

        val statementsV2ToV3 = captureMigrationSql { FocalDatabase.MIGRATION_2_3.migrate(it) }
        assertTrue(
            "Expected CREATE TABLE for history_entries",
            statementsV2ToV3.any { it.contains("CREATE TABLE IF NOT EXISTS `history_entries`") },
        )
        assertTrue(
            "Expected index on history_entries.visitedAtEpochMs",
            statementsV2ToV3.any {
                it.contains("CREATE INDEX IF NOT EXISTS `index_history_entries_visitedAtEpochMs`") &&
                    it.contains("ON `history_entries` (`visitedAtEpochMs`)")
            },
        )
        assertTrue(
            "Expected unique index on history_entries.url",
            statementsV2ToV3.any {
                it.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entries_url`") &&
                    it.contains("ON `history_entries` (`url`)")
            },
        )
        assertTrue(
            "Expected CREATE TABLE for download_records",
            statementsV2ToV3.any { it.contains("CREATE TABLE IF NOT EXISTS `download_records`") },
        )
        assertTrue(
            "Expected index on download_records.createdAtEpochMs",
            statementsV2ToV3.any {
                it.contains("CREATE INDEX IF NOT EXISTS `index_download_records_createdAtEpochMs`") &&
                    it.contains("ON `download_records` (`createdAtEpochMs`)")
            },
        )
        assertTrue(
            "Expected ALTER TABLE adding hiddenCssSelectorsJson to origin_overrides",
            statementsV2ToV3.any {
                it.trim() == "ALTER TABLE origin_overrides ADD COLUMN hiddenCssSelectorsJson TEXT"
            },
        )
        assertTrue(
            "Migration v2->v3 must remain non-destructive (no DROP TABLE)",
            statementsV2ToV3.none { it.contains("DROP TABLE", ignoreCase = true) },
        )
    }

    @Test
    fun v3Entities_haveExpectedDefaults() {
        val history = HistoryEntryEntity(
            id = "h-1",
            url = "https://arstechnica.com/gadgets/",
            domain = "arstechnica.com",
            title = "Ars Technica",
            visitedAtEpochMs = 1_700_000_000_000L,
        )
        assertEquals(1, history.visitCount)

        val download = DownloadRecordEntity(
            id = "d-1",
            url = "https://example.com/report.pdf",
            fileName = "report.pdf",
            mimeType = null,
            originDomain = "example.com",
            createdAtEpochMs = 1_700_000_000_000L,
        )
        assertEquals(-1L, download.downloadManagerId)
        assertNull(download.mimeType)

        val override = OriginOverrideEntity(
            origin = "example.com",
            updatedAtEpochMs = 1_700_000_000_000L,
        )
        assertNull(override.hiddenCssSelectorsJson)
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
