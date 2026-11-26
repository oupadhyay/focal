package app.focal.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGlassControllerTest {

    @Test
    fun unobstructedSampleBounds_sitsStrictlyAboveFloatingBottomBarAndDock() {
        // Pixel 6 portrait: 1080x2400, status=128px, nav=63px, dockClearance=368px (140dp), strip=126px (48dp)
        val bounds = AdaptiveGlassController.computeUnobstructedSampleBounds(
            viewportWidthPx = 1080,
            viewportHeightPx = 2400,
            statusBarTopPx = 128,
            navigationBarBottomPx = 63,
            dockClearancePx = 368,
            stripHeightPx = 126,
            horizontalInsetPx = 42,
        )
        assertNotNull(bounds)
        bounds!!
        assertEquals(42, bounds.left)
        assertEquals(1080 - 42, bounds.right)
        // Must sit strictly above floating bottom bar + optional smart-pill dock
        assertEquals(2400 - 63 - 368, bounds.bottom)
        assertEquals(bounds.bottom - 126, bounds.top)
        assertTrue("Sample strip must stay below status bar", bounds.top >= 128 + 8)
    }

    @Test
    fun unobstructedSampleBounds_handlesCompactViewportAndZeroBoundsSafely() {
        assertNull(
            AdaptiveGlassController.computeUnobstructedSampleBounds(
                viewportWidthPx = 0,
                viewportHeightPx = 2400,
                statusBarTopPx = 0,
                navigationBarBottomPx = 0,
                dockClearancePx = 300,
                stripHeightPx = 100,
                horizontalInsetPx = 32,
            ),
        )

        val compact = AdaptiveGlassController.computeUnobstructedSampleBounds(
            viewportWidthPx = 600,
            viewportHeightPx = 320,
            statusBarTopPx = 48,
            navigationBarBottomPx = 48,
            dockClearancePx = 360,
            stripHeightPx = 80,
            horizontalInsetPx = 24,
        )
        assertNotNull(compact)
        compact!!
        assertTrue(compact.top >= 0)
        assertTrue(compact.bottom <= 320)
        assertTrue(compact.height >= 4)
    }

    @Test
    fun hysteresisAndMedianLuminance_classifyDarkAndLightPagesWithoutFlicker() {
        // Dark page (#000000) with ~20% bright white text pixels -> median remains 0.0 (dark)
        val pixels = IntArray(256) { idx ->
            if (idx < 50) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val scratch = FloatArray(256)
        val darkMedian = AdaptiveGlassController.computeBt709MedianLuminance(pixels, pixels.size, scratch)
        assertTrue("Expected median near 0 on dark page with white text, got $darkMedian", darkMedian < 0.05f)

        // Switching from Light Alabaster back to Dark Smoked OLED on dark page
        assertTrue(
            AdaptiveGlassController.evaluateDarkGlassWithHysteresis(
                currentlyDarkGlass = false,
                medianLuminance = darkMedian,
            ),
        )

        // Hysteresis deadband (0.45..0.51) preserves previous state
        assertTrue(
            AdaptiveGlassController.evaluateDarkGlassWithHysteresis(
                currentlyDarkGlass = true,
                medianLuminance = 0.48f,
            ),
        )
        assertFalse(
            AdaptiveGlassController.evaluateDarkGlassWithHysteresis(
                currentlyDarkGlass = false,
                medianLuminance = 0.48f,
            ),
        )

        // Bright white page (#FFFFFF) flips Dark -> Light cleanly
        assertFalse(
            AdaptiveGlassController.evaluateDarkGlassWithHysteresis(
                currentlyDarkGlass = true,
                medianLuminance = 0.96f,
            ),
        )
    }

    @Test
    fun medianSurfaceArgb_recoversTruePageBackgroundIgnoringTextOutliers() {
        // Dark Google AI Mode canvas (#1F1F23) with 20% light text (#E8EAED)
        val darkCanvas = 0xFF1F1F23.toInt()
        val textPixel = 0xFFE8EAED.toInt()
        val pixels = IntArray(256) { idx -> if (idx < 50) textPixel else darkCanvas }
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        assertEquals(
            darkCanvas,
            AdaptiveGlassController.computeMedianSurfaceArgb(pixels, pixels.size, r, g, b),
        )
    }

    @Test
    fun emissionCoalescing_suppressesSteadyColorScrollChurnAndEmitsOnMeaningfulChange() {
        val initial = GlassLuminanceState(
            isDarkGlass = true,
            medianLuminance = 0.12f,
            lightStatusBarIcons = true,
            sampledSurfaceArgb = null,
        )

        // 1. First non-null surface color sample must emit even with minimal luminance change
        val firstSampled = AdaptiveGlassController.resolveNextState(
            previous = initial,
            medianLuminance = 0.125f,
            sampledSurfaceArgb = 0xFF1F1F23.toInt(),
        )
        assertTrue("null -> non-null sampledSurfaceArgb must emit", firstSampled !== initial)
        assertEquals(0xFF1F1F23.toInt(), firstSampled.sampledSurfaceArgb)

        // 2. Steady-color scroll jitter (RGB delta <= 5 per channel, luma delta < 0.04f, same polarity) coalesces
        val jittered = AdaptiveGlassController.resolveNextState(
            previous = firstSampled,
            medianLuminance = 0.15f, // delta 0.025f < 0.04f
            sampledSurfaceArgb = 0xFF241B28.toInt(), // R:+5, G:-4, B:+5 (all < 6)
        )
        assertTrue("Steady-color scroll jitter must coalesce to identical state instance", jittered === firstSampled)

        // 3. Per-channel RGB delta >= 6 emits a new state
        val colorShifted = AdaptiveGlassController.resolveNextState(
            previous = firstSampled,
            medianLuminance = 0.13f,
            sampledSurfaceArgb = 0xFF251F23.toInt(), // R:+6 >= 6
        )
        assertTrue("RGB channel delta >= 6 must emit", colorShifted !== firstSampled)
        assertEquals(0xFF251F23.toInt(), colorShifted.sampledSurfaceArgb)

        // 4. Median luminance delta >= 0.04f emits even with identical surface color
        val lumaShifted = AdaptiveGlassController.resolveNextState(
            previous = colorShifted,
            medianLuminance = 0.18f, // delta 0.05f >= 0.04f
            sampledSurfaceArgb = colorShifted.sampledSurfaceArgb,
        )
        assertTrue("Luminance delta >= 0.04f must emit", lumaShifted !== colorShifted)
        assertEquals(0.18f, lumaShifted.medianLuminance, 0.0001f)

        // 5. Glass polarity flip across hysteresis threshold emits even when luma delta < 0.04f and RGB delta < 6
        val nearThresholdDark = GlassLuminanceState(
            isDarkGlass = true,
            medianLuminance = 0.50f,
            lightStatusBarIcons = true,
            sampledSurfaceArgb = 0xFF808080.toInt(),
        )
        val flippedToLight = AdaptiveGlassController.resolveNextState(
            previous = nearThresholdDark,
            medianLuminance = 0.53f, // delta 0.03f < 0.04f, but crosses 0.52f DARK_TO_LIGHT_THRESHOLD
            sampledSurfaceArgb = 0xFF828282.toInt(), // RGB delta +2 < 6
        )
        assertTrue("Polarity flip must emit immediately", flippedToLight !== nearThresholdDark)
        assertFalse(flippedToLight.isDarkGlass)
        assertFalse(flippedToLight.lightStatusBarIcons)
    }
}
