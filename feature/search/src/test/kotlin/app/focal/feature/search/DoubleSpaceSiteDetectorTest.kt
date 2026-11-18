package app.focal.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleSpaceSiteDetectorTest {

    @Test
    fun `all five core shortcuts convert on double space within 320ms and unwrap on Backspace`() {
        val detector = DoubleSpaceSiteDetector()
        val shortcuts = listOf(
            "im" to "IMDb",
            "wi" to "Wikipedia",
            "rd" to "Reddit",
            "yt" to "YouTube",
            "gh" to "GitHub",
        )

        for ((prefix, expectedLabel) in shortcuts) {
            val step1 = detector.onQueryChanged(
                previousQuery = prefix,
                newQuery = "$prefix ",
                currentSite = null,
                eventTimeMs = 1_000L,
            )
            assertNull(step1.activeSite)

            val step2 = detector.onQueryChanged(
                previousQuery = "$prefix ",
                newQuery = "$prefix  ",
                currentSite = null,
                eventTimeMs = 1_210L, // 210ms <= 320ms
            )
            assertTrue(step2.chipJustActivated)
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
            assertEquals(prefix, unwrapped.updatedQuery)
        }
    }

    @Test
    fun `double space slower than 320ms does not trigger site chip`() {
        val detector = DoubleSpaceSiteDetector()
        detector.onQueryChanged("im", "im ", null, 1_000L)
        val slow = detector.onQueryChanged("im ", "im  ", null, 1_450L) // 450ms > 320ms
        assertNull(slow.activeSite)
        assertEquals("im  ", slow.updatedQuery)
    }

    @Test
    fun `resolveDestinationUrl handles google_com, Gboard auto-period, and explicit URLs cleanly`() {
        val detector = DoubleSpaceSiteDetector()
        assertEquals("https://google.com", detector.resolveDestinationUrl("google.com", activeSite = null))
        assertEquals("https://google.com", detector.resolveDestinationUrl("  google.com  ", activeSite = null))
        assertEquals("http://localhost:3000", detector.resolveDestinationUrl("localhost:3000", activeSite = null))

        // Gboard double-space period ("wi" -> "wi " -> "wi. ") activates Wikipedia chip
        detector.onQueryChanged("wi", "wi ", null, 2_000L)
        val gboard = detector.onQueryChanged("wi ", "wi. ", null, 2_180L)
        assertTrue(gboard.chipJustActivated)
        assertEquals("Wikipedia", gboard.activeSite?.label)

        // Explicit https:// URL navigates directly even when a site chip is active
        assertEquals(
            "https://google.com",
            detector.resolveDestinationUrl("https://google.com", activeSite = gboard.activeSite),
        )
    }
}
