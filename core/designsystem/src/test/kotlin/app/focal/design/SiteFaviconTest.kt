package app.focal.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteFaviconTest {

    @Test
    fun `normalizeHost extracts clean domain across URLs, search templates, and mobile subdomains`() {
        assertEquals("imdb.com", FaviconCache.normalizeHost("https://www.imdb.com/find/?q=%s"))
        assertEquals("en.wikipedia.org", FaviconCache.normalizeHost("https://en.wikipedia.org/w/index.php?search=%s"))
        assertEquals("youtube.com", FaviconCache.normalizeHost("https://m.youtube.com"))
        assertEquals("news.ycombinator.com", FaviconCache.normalizeHost("https://news.ycombinator.com"))
        assertEquals("developer.android.com", FaviconCache.normalizeHost("https://developer.android.com/jetpack"))
        assertEquals("google.com", FaviconCache.normalizeHost("google.com"))
        assertEquals("", FaviconCache.normalizeHost("about:blank"))
        assertEquals("", FaviconCache.normalizeHost("focal-pair://v1?secret=abc"))
    }

    @Test
    fun `resolveBuiltInMark maps seeded pins and double-space shortcuts accurately`() {
        assertEquals(BuiltInFaviconMark.IMDB, FaviconCache.resolveBuiltInMark("https://www.imdb.com"))
        assertEquals(BuiltInFaviconMark.GITHUB, FaviconCache.resolveBuiltInMark("https://github.com"))
        assertEquals(BuiltInFaviconMark.HACKER_NEWS, FaviconCache.resolveBuiltInMark("https://news.ycombinator.com"))
        assertEquals(BuiltInFaviconMark.HACKER_NEWS, FaviconCache.resolveBuiltInMark("https://hn.algolia.com/?q=%s"))
        assertEquals(BuiltInFaviconMark.WIKIPEDIA, FaviconCache.resolveBuiltInMark("https://en.wikipedia.org"))
        assertEquals(BuiltInFaviconMark.ANDROID_DEV, FaviconCache.resolveBuiltInMark("https://developer.android.com"))
        assertEquals(BuiltInFaviconMark.REDDIT, FaviconCache.resolveBuiltInMark("https://www.reddit.com"))
        assertEquals(BuiltInFaviconMark.YOUTUBE, FaviconCache.resolveBuiltInMark("https://m.youtube.com"))
        assertEquals(BuiltInFaviconMark.ARS_TECHNICA, FaviconCache.resolveBuiltInMark("https://arstechnica.com"))
        assertEquals(BuiltInFaviconMark.RUST_DOCS, FaviconCache.resolveBuiltInMark("https://docs.rs"))
        assertEquals(BuiltInFaviconMark.GOOGLE, FaviconCache.resolveBuiltInMark("https://www.google.com"))
        assertNull(FaviconCache.resolveBuiltInMark("https://linear.app"))
    }

    @Test
    fun `buildOriginIconCandidates prioritizes explicit iconUrl and apple-touch-icon before favicon_ico`() {
        val candidates = FaviconCache.buildOriginIconCandidates(
            host = "linear.app",
            explicitIconUrl = "https://linear.app/static/apple-touch-icon.png",
        )
        assertEquals("https://linear.app/static/apple-touch-icon.png", candidates[0])
        assertEquals("https://linear.app/apple-touch-icon.png", candidates[1])
        assertEquals("https://linear.app/favicon.ico", candidates[2])
        assertTrue(candidates.contains("https://www.linear.app/favicon.ico"))
    }

    @Test
    fun `findEmbeddedPngOffset locates PNG signature inside multi-entry ICO byte buffer`() {
        val fakeIcoHeader = ByteArray(22) { 0x00 }
        val pngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )
        val combined = fakeIcoHeader + pngMagic
        assertEquals(22, FaviconCache.findEmbeddedPngOffset(combined))
        assertEquals(-1, FaviconCache.findEmbeddedPngOffset(ByteArray(32) { 0x11 }))
    }
}
