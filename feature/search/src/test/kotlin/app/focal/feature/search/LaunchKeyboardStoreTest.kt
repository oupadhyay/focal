package app.focal.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchKeyboardStoreTest {

    @Test
    fun isValidImeHeightPx_acceptsRealisticDockedKeyboardAndRejectsOutliers() {
        val viewportPx = 2400
        val minKeyboardPx = 360

        assertTrue(LaunchKeyboardGeometry.isValidImeHeightPx(820, viewportPx, minKeyboardPx))
        assertTrue(LaunchKeyboardGeometry.isValidImeHeightPx(1560, viewportPx, minKeyboardPx))

        // Too small (toolbar/nav only) or too huge (>65% viewport) or invalid viewport
        assertFalse(LaunchKeyboardGeometry.isValidImeHeightPx(0, viewportPx, minKeyboardPx))
        assertFalse(LaunchKeyboardGeometry.isValidImeHeightPx(180, viewportPx, minKeyboardPx))
        assertFalse(LaunchKeyboardGeometry.isValidImeHeightPx(1800, viewportPx, minKeyboardPx))
        assertFalse(LaunchKeyboardGeometry.isValidImeHeightPx(820, 0, minKeyboardPx))
    }

    @Test
    fun frameZeroZeroJump_holdsCachedHeightUntilLiveImeCatchesUpThenTracksDismissal() {
        val cachedPx = 840
        val minKeyboardPx = 360

        // Frame 0: live IME is still 0 while animating open -> sheet mounts immediately at 840px
        assertEquals(
            840,
            LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                liveImeAboveNavPx = 0,
                cachedHeightPx = cachedPx,
                imeHasStabilized = false,
                holdCachedWindowActive = true,
            ),
        )

        // Mid-animation: live IME is 420px (< 85% of cached) -> stays pinned at 840px without jitter
        assertFalse(LaunchKeyboardGeometry.hasLiveImeCaughtUp(420, cachedPx, minKeyboardPx))
        assertEquals(
            840,
            LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                liveImeAboveNavPx = 420,
                cachedHeightPx = cachedPx,
                imeHasStabilized = false,
                holdCachedWindowActive = true,
            ),
        )

        // Live IME reaches 860px (slightly taller keyboard row) -> catches up and tracks 860px
        assertTrue(LaunchKeyboardGeometry.hasLiveImeCaughtUp(860, cachedPx, minKeyboardPx))
        assertEquals(
            860,
            LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                liveImeAboveNavPx = 860,
                cachedHeightPx = cachedPx,
                imeHasStabilized = true,
                holdCachedWindowActive = false,
            ),
        )

        // User presses Back to dismiss IME while sheet stays open -> lowers cleanly to 0px
        assertEquals(
            0,
            LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                liveImeAboveNavPx = 0,
                cachedHeightPx = cachedPx,
                imeHasStabilized = true,
                holdCachedWindowActive = false,
            ),
        )
    }

    @Test
    fun hardwareKeyboardFallback_releasesCachedHeightOnceHoldWindowExpires() {
        assertEquals(
            0,
            LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                liveImeAboveNavPx = 0,
                cachedHeightPx = 840,
                imeHasStabilized = false,
                holdCachedWindowActive = false,
            ),
        )
    }

    @Test
    fun coldFirstInstall_withZeroCachedHeight_tracksLiveImeDirectly() {
        assertEquals(
            0,
            LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                liveImeAboveNavPx = 0,
                cachedHeightPx = 0,
                imeHasStabilized = false,
                holdCachedWindowActive = false,
            ),
        )
        assertEquals(
            780,
            LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                liveImeAboveNavPx = 780,
                cachedHeightPx = 0,
                imeHasStabilized = true,
                holdCachedWindowActive = false,
            ),
        )
    }
}
