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
}
