package app.focal.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleSpaceSiteDetectorTest {

    @Test
    fun `GitHub GH git and natural aliases convert on double space at any cadence and unwrap exact prefix`() {
        val detector = DoubleSpaceSiteDetector()
        val cases = listOf(
            "GitHub" to "GitHub",
            "GH" to "GitHub",
            "git" to "GitHub",
            "YouTube" to "YouTube",
            "yt" to "YouTube",
            "Wiki" to "Wikipedia",
            "wi" to "Wikipedia",
            "Reddit" to "Reddit",
            "rd" to "Reddit",
            "IMDb" to "IMDb",
            "im" to "IMDb",
            "hn" to "Hacker News",
            "so" to "Stack Overflow",
            "crates" to "crates.io",
            "docsrs" to "Docs.rs",
        )

        for ((typedCommand, expectedLabel) in cases) {
            val step1 = detector.onQueryChanged(
                previousQuery = typedCommand,
                newQuery = "$typedCommand ",
                currentSite = null,
                eventTimeMs = 1_000L,
            )
            assertNull("Single space after '$typedCommand' must not activate chip", step1.activeSite)

            // Even a relaxed > 600ms second space on mobile keyboards activates cleanly
            val step2 = detector.onQueryChanged(
                previousQuery = "$typedCommand ",
                newQuery = "$typedCommand  ",
                currentSite = null,
                eventTimeMs = 1_650L,
            )
            assertTrue("Expected '$typedCommand  ' to activate chip", step2.chipJustActivated)
            assertEquals("", step2.updatedQuery)
            assertNotNull(step2.activeSite)
            assertEquals(expectedLabel, step2.activeSite?.label)

            val unwrapped = detector.onBackspacePressed(
                currentQuery = "",
                currentSite = step2.activeSite,
            )
            assertNotNull(unwrapped)
            assertTrue(unwrapped!!.chipJustUnwrapped)
            assertNull(unwrapped.activeSite)
            assertEquals(typedCommand, unwrapped.updatedQuery)
        }
    }

    @Test
    fun `single space natural queries like git rebase never hijack into site chip`() {
        val detector = DoubleSpaceSiteDetector()
        val s1 = detector.onQueryChanged("git", "git ", null, 1_000L)
        assertFalse(s1.chipJustActivated)
        assertNull(s1.activeSite)

        val s2 = detector.onQueryChanged("git ", "git rebase", null, 1_150L)
        assertFalse(s2.chipJustActivated)
        assertNull(s2.activeSite)
        assertEquals("git rebase", s2.updatedQuery)
    }

    @Test
    fun `Gboard auto-period activates on command space to dot-space but ignores literal dot-space`() {
        val detector = DoubleSpaceSiteDetector()

        // Gboard double-space auto-period: "GH" -> "GH " -> "GH. "
        detector.onQueryChanged("GH", "GH ", null, 2_000L)
        val gboard = detector.onQueryChanged("GH ", "GH. ", null, 2_280L)
        assertTrue(gboard.chipJustActivated)
        assertEquals("GitHub", gboard.activeSite?.label)
        assertEquals(
            "https://github.com/search?q=compose",
            detector.resolveDestinationUrl("compose", activeSite = gboard.activeSite),
        )

        // Literal period typed by user ("git." -> "git. ") must NOT trigger site chip
        val literalDot = detector.onQueryChanged("git.", "git. ", null, 3_000L)
        assertFalse(literalDot.chipJustActivated)
        assertNull(literalDot.activeSite)
    }

    @Test
    fun `resolveDestinationUrl and enginesFromPins support custom user pins without clobbering built-ins`() {
        val pins = listOf(
            QuickSquirclePin("p1", "GH", "GitHub", "https://github.com"),
            QuickSquirclePin("p2", "LV", "Lobsters", "https://lobste.rs"),
        )
        val detector = DoubleSpaceSiteDetector().apply {
            for (pinEngine in DoubleSpaceSiteDetector.enginesFromPins(pins)) {
                registerShortcut(pinEngine, overwrite = false)
            }
        }

        // Built-in GitHub retains native /search?q= template
        val gh = detector.onQueryChanged("git ", "git  ", null)
        assertEquals("GitHub", gh.activeSite?.label)
        assertEquals("https://github.com", detector.resolveDestinationUrl("", activeSite = gh.activeSite))
        assertEquals(
            "https://github.com/search?q=focal",
            detector.resolveDestinationUrl("focal", activeSite = gh.activeSite),
        )

        // Custom pin ("Lobsters" / "lv") gets site-scoped search automatically
        val custom = detector.onQueryChanged("lobsters ", "lobsters  ", null)
        assertTrue(custom.chipJustActivated)
        assertEquals("Lobsters", custom.activeSite?.label)
        assertEquals("https://lobste.rs", detector.resolveDestinationUrl("", activeSite = custom.activeSite))
        assertTrue(
            detector.resolveDestinationUrl("rust", activeSite = custom.activeSite)
                .contains("lobste.rs"),
        )

        // Bare domains & explicit URLs resolve cleanly
        assertEquals("https://google.com", detector.resolveDestinationUrl("google.com", activeSite = null))
        assertEquals("https://google.com", detector.resolveDestinationUrl("  google.com  ", activeSite = null))
        assertEquals("http://localhost:3000", detector.resolveDestinationUrl("localhost:3000", activeSite = null))
    }
}
