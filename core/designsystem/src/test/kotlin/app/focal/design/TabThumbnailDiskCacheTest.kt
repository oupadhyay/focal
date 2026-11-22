package app.focal.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TabThumbnailDiskCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun safeKey_preservesCleanIdsAndSanitizesTraversalBlankAndLongInputs() {
        val cacheDir = File(tempFolder.root, "thumbs")
        val cache = TabThumbnailDiskCache(cacheDir)
        val safePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

        assertEquals("tab-1", cache.safeKey("tab-1"))
        assertEquals("tab_ABC_42", cache.safeKey("tab_ABC_42"))
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(uuid, cache.safeKey(uuid))

        val maliciousInputs = listOf(
            "",
            "   ",
            ".",
            "..",
            "../secret",
            "../../etc/passwd",
            "/etc/passwd",
            "nested/tab",
            "windows\\path\\tab",
            "tab\u0000null",
            "a".repeat(128),
        )

        for (input in maliciousInputs) {
            val key = cache.safeKey(input)
            assertTrue("Expected safe key for '$input', got '$key'", safePattern.matches(key))
            assertEquals(
                "Deterministic hash required for '$input'",
                key,
                cache.safeKey(input),
            )
            assertFalse("Key must not contain dots or slashes: '$key'", key.contains('.') || key.contains('/') || key.contains('\\'))

            val resolvedFile = cache.fileForTab(input)
            assertEquals(
                "Resolved file must stay directly inside cacheDir for '$input'",
                cacheDir.canonicalFile,
                resolvedFile.canonicalFile.parentFile,
            )
        }

        // Path traversal input must not collide with clean sibling tab ID
        assertNotEquals(cache.safeKey("../secret"), cache.safeKey("secret"))
    }

    @Test
    fun privateModeGuard_neverCreatesDirectoryOrWritesFile() {
        val cacheDir = File(tempFolder.root, "private_guard_thumbs")
        val cache = TabThumbnailDiskCache(cacheDir)
        var writerInvoked = false

        val wrote = cache.saveEncoded("private-tab-1", isPrivate = true) { out ->
            writerInvoked = true
            out.write(byteArrayOf(1, 2, 3, 4))
            true
        }

        assertFalse(wrote)
        assertFalse("Private tab write must be a strict no-op", writerInvoked)
        assertFalse("Private tab write must not create cacheDir", cacheDir.exists())
        assertNull(cache.load("private-tab-1"))
    }

    @Test
    fun atomicSaveDeletePruneAndClearAll_manageFilesCleanly() {
        val cacheDir = File(tempFolder.root, "managed_thumbs")
        val cache = TabThumbnailDiskCache(cacheDir)

        val payloadV1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val payloadV2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x09, 0x0A, 0x0B)
        val traversalTabId = "../weird/tab"

        assertTrue(cache.saveEncoded("tab-1", isPrivate = false) { it.write(payloadV1); true })
        assertTrue(cache.saveEncoded("tab-2", isPrivate = false) { it.write(payloadV1); true })
        assertTrue(cache.saveEncoded("tab-3", isPrivate = false) { it.write(payloadV1); true })
        assertTrue(cache.saveEncoded(traversalTabId, isPrivate = false) { it.write(payloadV1); true })

        // Overwrite atomically and confirm no lingering .tmp files
        assertTrue(cache.saveEncoded("tab-1", isPrivate = false) { it.write(payloadV2); true })
        assertArrayEquals(payloadV2, cache.fileForTab("tab-1").readBytes())
        assertTrue(
            "No temporary files should remain after atomic save",
            cacheDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )

        // Failed writer leaves existing file intact and deletes temp file
        assertFalse(cache.saveEncoded("tab-1", isPrivate = false) { it.write(byteArrayOf(0)); false })
        assertArrayEquals(payloadV2, cache.fileForTab("tab-1").readBytes())
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

        // Orphaned .tmp file is also cleaned up by pruneExcept
        val orphanTmp = File(cacheDir, "stale.jpg.1234.tmp").apply { writeText("partial") }
        assertTrue(orphanTmp.exists())

        // Delete one specific tab
        cache.delete("tab-3")
        assertFalse(cache.fileForTab("tab-3").exists())
        assertTrue(cache.fileForTab("tab-1").exists())

        // Prune everything except tab-1 and traversalTabId
        cache.pruneExcept(setOf("tab-1", traversalTabId))
        assertTrue(cache.fileForTab("tab-1").exists())
        assertTrue(cache.fileForTab(traversalTabId).exists())
        assertFalse(cache.fileForTab("tab-2").exists())
        assertFalse(orphanTmp.exists())

        // clearAll empties cache directory completely
        cache.clearAll()
        assertEquals(0, cacheDir.listFiles().orEmpty().size)

        // Missing entries return null / empty map cleanly
        assertNull(cache.load("tab-1"))
        assertEquals(emptyMap<String, Any>(), cache.loadAllForTabs(emptyList()))
        assertEquals(emptyMap<String, Any>(), cache.loadAllForTabs(listOf("tab-1", "tab-2")))
    }
}
