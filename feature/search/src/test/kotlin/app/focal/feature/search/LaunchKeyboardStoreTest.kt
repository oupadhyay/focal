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

        // Mid-animation (including 85% = 714px and 95% = 800px): must NEVER hand off early and dip downward!
        for (midRisePx in listOf(420, 714, 800)) {
            assertFalse(
                "IME at ${midRisePx}px must not trigger early handoff while rising toward ${cachedPx}px",
                LaunchKeyboardGeometry.hasLiveImeCaughtUp(midRisePx, cachedPx, minKeyboardPx),
            )
            assertEquals(
                840,
                LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
                    liveImeAboveNavPx = midRisePx,
                    cachedHeightPx = cachedPx,
                    imeHasStabilized = false,
                    holdCachedWindowActive = true,
                ),
            )
        }

        // Once live IME reaches within 8px of resting cached height (834px / 860px), hand off cleanly
        assertTrue(LaunchKeyboardGeometry.hasLiveImeCaughtUp(834, cachedPx, minKeyboardPx))
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

    @Test
    fun activityAwareHoldTimeout_distinguishesHardwareKeyboardFromShortSettledIme() {
        assertEquals(
            LaunchKeyboardGeometry.HARDWARE_KEYBOARD_TIMEOUT_MS,
            LaunchKeyboardGeometry.resolveHoldTimeoutMs(liveImeAboveNavPx = 0),
        )
        assertEquals(
            LaunchKeyboardGeometry.SHORT_IME_SETTLE_DEBOUNCE_MS,
            LaunchKeyboardGeometry.resolveHoldTimeoutMs(liveImeAboveNavPx = 680),
        )
    }

    @Test
    fun isKeyboardClosing_detectsRetractionWithoutTriggeringDuringRise() {
        val minKeyboardPx = 360
        // Still rising (peak equals live) -> not closing
        assertFalse(LaunchKeyboardGeometry.isKeyboardClosing(liveImeAboveNavPx = 600, peakLiveImePx = 600, minKeyboardPx = minKeyboardPx))
        // Tiny sub-tolerance jitter (4px) -> not closing
        assertFalse(LaunchKeyboardGeometry.isKeyboardClosing(liveImeAboveNavPx = 596, peakLiveImePx = 600, minKeyboardPx = minKeyboardPx))
        // Genuine retraction after reaching keyboard height -> closing
        assertTrue(LaunchKeyboardGeometry.isKeyboardClosing(liveImeAboveNavPx = 540, peakLiveImePx = 600, minKeyboardPx = minKeyboardPx))
        // Noise below minimum keyboard height -> not closing
        assertFalse(LaunchKeyboardGeometry.isKeyboardClosing(liveImeAboveNavPx = 0, peakLiveImePx = 120, minKeyboardPx = minKeyboardPx))
    }
}
