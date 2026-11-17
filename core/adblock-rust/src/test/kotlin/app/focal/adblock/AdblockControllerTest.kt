package app.focal.adblock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class AdblockControllerTest {

    @Test
    fun `BlockDecision defaults and singleton constants match public contract`() {
        val defaultAllow = BlockDecision(matched = false)
        assertEquals(BlockDecision.ALLOW, defaultAllow)
        assertFalse(defaultAllow.important)
        assertNull(defaultAllow.redirectBody)

        val defaultBlock = BlockDecision(matched = true)
        assertEquals(BlockDecision.BLOCK, defaultBlock)
        assertFalse(defaultBlock.important)
        assertNull(defaultBlock.redirectBody)
    }

    @Test
    fun `ZeroAllocationEmptyInputStream is reusable and lock-free across closes`() {
        val stream = AdblockWebResponses.ZeroAllocationEmptyInputStream
        assertEquals(0, stream.available())
        assertEquals(-1, stream.read())
        assertEquals(0, stream.read(ByteArray(8), 0, 0))
        assertEquals(-1, stream.read(ByteArray(8), 0, 8))
        stream.close()
        // Remains reusable after close() across concurrent WebView worker threads
        assertEquals(-1, stream.read())
        assertEquals(0, AdblockWebResponses.EMPTY_RESPONSE_BYTES.size)
    }

    @Test
    fun `parseBase64DataUri decodes surrogate redirect payloads accurately`() {
        val parsed = AdblockWebResponses.parseBase64DataUri(
            "data:application/javascript;base64,KGZ1bmN0aW9uKCl7fSkoKTs=",
        )
        assertNotNull(parsed)
        assertEquals("application/javascript", parsed!!.first)
        assertArrayEquals("(function(){})();".toByteArray(Charsets.UTF_8), parsed.second)
    }

    @Test
    fun `NativeAdblockController supports idempotent AutoCloseable lifecycle and URL canonicalization`() {
        val controller = NativeAdblockController(autoLoadNativeLibrary = false)
        assertEquals(0L, controller.totalBlockedCount.value)

        val cleaned = controller.cleanTrackingUrl(
            "https://focal.app:8443/blog/120hz?id=7&utm_source=x&fbclid=abc123#benchmarks",
        )
        assertEquals("https://focal.app:8443/blog/120hz?id=7#benchmarks", cleaned.cleanUrl)
        assertEquals(listOf("utm_source", "fbclid"), cleaned.strippedParams)

        assertEquals(
            "",
            controller.cosmeticCssForClassesAndIds(
                url = "https://focal.app/",
                classes = listOf("ad-slot", "sponsored-content"),
                ids = listOf("ad-leaderboard"),
            ),
        )

        // Idempotent close()
        controller.close()
        controller.close()

        assertEquals(
            BlockDecision.ALLOW,
            controller.checkNetworkRequest(
                "https://doubleclick.net/ad.js",
                "https://focal.app/",
                "script",
            ),
        )
        assertEquals("", controller.cosmeticCssForUrl("https://focal.app/"))
    }

    @Test
    fun `AdblockController default cosmeticCssForClassesAndIds keeps test doubles source compatible`() {
        val stub = object : AdblockController {
            override val totalBlockedCount: StateFlow<Long> = MutableStateFlow(0L)
            override fun checkNetworkRequest(
                requestUrl: String,
                sourceUrl: String,
                resourceType: String,
            ): BlockDecision = BlockDecision.ALLOW

            override fun cosmeticCssForUrl(url: String): String = ""
            override fun cleanTrackingUrl(rawUrl: String): CleanedUrlResult =
                CleanedUrlResult(rawUrl, emptyList())
        }

        assertEquals(
            "",
            stub.cosmeticCssForClassesAndIds(
                url = "https://example.org/",
                classes = listOf("ad-banner"),
                ids = listOf("google_ads_frame"),
            ),
        )
    }

    @Test
    fun `encodeTabSeparatedTokens and initializeOrLoadCache handle edge cases safely`() = runTest {
        assertEquals("", NativeAdblockController.encodeTabSeparatedTokens(emptyList()))
        assertEquals("", NativeAdblockController.encodeTabSeparatedTokens(listOf("", "   ")))
        assertEquals(
            "ad-slot\tsponsor-box\tpromo",
            NativeAdblockController.encodeTabSeparatedTokens(
                listOf("  ad-slot ", "", "sponsor-box", "   ", "promo"),
            ),
        )

        val tempDir = Files.createTempDirectory("focal-adblock-test").toFile()
        try {
            val cacheFile = tempDir.resolve("engine_snapshot.dat")
            val controller = NativeAdblockController(autoLoadNativeLibrary = false)
            controller.initializeOrLoadCache(cacheFile)
            controller.close()
            // Calling initializeOrLoadCache after close() is a safe no-op
            controller.initializeOrLoadCache(cacheFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
