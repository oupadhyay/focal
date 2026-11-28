package app.focal

import app.focal.feature.browser.ScrollVelocityBarController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollVelocityBarControllerTest {

    private val density = 3.0f // xxhdpi (1 dp = 3 px)

    @Test
    fun slowContinuousScroll_glidesFractionSmoothlyFromZeroToHiddenWithoutPerFrameThreshold() {
        val controller = ScrollVelocityBarController()
        var scrollY = 120 // 40dp down the page
        var t = 1_000L

        // 120Hz slow reading scroll: only 2dp (6px) per 16ms frame (~125 dp/s) — previously ignored by 18dp threshold!
        val fractions = mutableListOf<Float>()
        repeat(80) {
            scrollY += 6
            t += 16L
            val step = controller.onScrollDelta(
                scrollY = scrollY,
                deltaY = 6,
                timestampMs = t,
                density = density,
            )
            fractions += step.fraction
        }

        // Must progress smoothly through intermediate fractions before settling hidden
        assertTrue(
            "Slow scroll should produce intermediate glide values between 0.1 and 0.9",
            fractions.any { it in 0.1f..0.9f },
        )
        assertEquals(1f, controller.currentFraction, 0.001f)
        assertTrue(controller.settledHidden)
    }

    @Test
    fun fasterScroll_hidesBarProportionallyFasterThanSlowScrollForSameDistance() {
        val slow = ScrollVelocityBarController()
        val fast = ScrollVelocityBarController()

        // Both scroll the exact same 48dp (144px) starting well below top pin zone (100dp = 300px)
        var slowY = 300
        var slowT = 1_000L
        repeat(24) {
            // 2dp per 24ms (~83 dp/s)
            slowY += 6
            slowT += 24L
            slow.onScrollDelta(slowY, deltaY = 6, timestampMs = slowT, density = density)
        }

        var fastY = 300
        var fastT = 1_000L
        repeat(4) {
            // 12dp per 8ms (~1500 dp/s)
            fastY += 36
            fastT += 8L
            fast.onScrollDelta(fastY, deltaY = 36, timestampMs = fastT, density = density)
        }

        assertTrue(
            "Fast flick (${fast.currentFraction}) must advance hide fraction further than slow scroll (${slow.currentFraction}) for the same distance",
            fast.currentFraction > slow.currentFraction + 0.12f,
        )
    }

    @Test
    fun settledHiddenFlag_staysStableMidScrollAndOnlyFlipsAtEndpoints() {
        val controller = ScrollVelocityBarController()

        // Scroll partway down (~40dp at medium speed)
        var y = 300
        var t = 1_000L
        repeat(5) {
            y += 24 // 8dp per 16ms (~500 dp/s)
            t += 16L
            val step = controller.onScrollDelta(y, deltaY = 24, timestampMs = t, density = density)
            if (step.fraction < 0.995f) {
                assertFalse("WebView layout must not toggle hidden mid-scroll", step.settledHidden)
            }
        }
        assertTrue(controller.currentFraction in 0.2f..0.85f)
        assertFalse(controller.settledHidden)

        // Finish hiding completely
        repeat(15) {
            y += 24
            t += 16L
            controller.onScrollDelta(y, deltaY = 24, timestampMs = t, density = density)
        }
        assertEquals(1f, controller.currentFraction, 0.001f)
        assertTrue(controller.settledHidden)

        // Reverse partway up: fraction immediately reveals, while settledHidden stays true until fully visible
        y -= 24
        t += 16L
        val partialReveal = controller.onScrollDelta(y, deltaY = -24, timestampMs = t, density = density)
        assertTrue("Reversing scroll up should immediately begin revealing bar", partialReveal.fraction < 0.98f)
        assertTrue("settledHidden should hold until bar reaches visible endpoint", partialReveal.settledHidden)

        // Complete upward scroll to reveal fully
        repeat(15) {
            y = (y - 24).coerceAtLeast(0)
            t += 16L
            controller.onScrollDelta(y, deltaY = -24, timestampMs = t, density = density)
        }
        assertEquals(0f, controller.currentFraction, 0.001f)
        assertFalse(controller.settledHidden)
    }

    @Test
    fun computeSettlePlan_usesVelocityContinuityAndTopPinBias() {
        // 1. Brisk downward release at ~0.35 fraction -> snaps hidden with high spring stiffness
        val briskDown = ScrollVelocityBarController()
        var y = 360
        var t = 1_000L
        repeat(3) {
            y += 30 // 10dp per 16ms (~625 dp/s)
            t += 16L
            briskDown.onScrollDelta(y, deltaY = 30, timestampMs = t, density = density)
        }
        assertTrue(briskDown.currentFraction in 0.22f..0.75f)
        val briskPlan = briskDown.computeSettlePlan(scrollY = y, density = density)
        assertNotNull(briskPlan)
        assertEquals(1f, briskPlan!!.targetFraction, 0.001f)
        assertTrue(briskPlan.targetHidden)
        assertTrue(briskPlan.initialVelocityFractionPerSec > 0f)

        // 2. Slow stop at ~0.35 fraction -> snaps back open with calmer stiffness than brisk flick
        val slowStop = ScrollVelocityBarController()
        var sy = 360
        var st = 1_000L
        repeat(15) {
            sy += 9 // 3dp per 30ms (~100 dp/s)
            st += 30L
            slowStop.onScrollDelta(sy, deltaY = 9, timestampMs = st, density = density)
        }
        assertTrue(slowStop.currentFraction in 0.2f..0.49f)
        val slowPlan = slowStop.computeSettlePlan(scrollY = sy, density = density)
        assertNotNull(slowPlan)
        assertEquals(0f, slowPlan!!.targetFraction, 0.001f)
        assertFalse(slowPlan.targetHidden)
        assertTrue(
            "Brisk scroll stiffness (${briskPlan.springStiffness}) should exceed slow scroll (${slowPlan.springStiffness})",
            briskPlan.springStiffness > slowPlan.springStiffness,
        )

        // 3. Near top of page (scrollY <= 24dp) ALWAYS snaps visible even when > 0.5 fraction
        briskDown.syncFraction(0.65f, hidden = false)
        val topPlan = briskDown.computeSettlePlan(scrollY = 30, density = density) // 10dp
        assertNotNull(topPlan)
        assertEquals(0f, topPlan!!.targetFraction, 0.001f)
        assertFalse(topPlan.targetHidden)

        // 4. Already at endpoint returns null (no redundant snap animation)
        briskDown.syncFraction(1f, hidden = true)
        assertNull(briskDown.computeSettlePlan(scrollY = 500, density = density))
    }

    @Test
    fun scrollingBackToTopZero_immediatelyResetsVisible() {
        val controller = ScrollVelocityBarController()
        controller.syncFraction(0.8f, hidden = true)
        val step = controller.onScrollDelta(scrollY = 0, deltaY = -12, timestampMs = 2_000L, density = density)
        assertEquals(0f, step.fraction, 0.001f)
        assertFalse(step.settledHidden)
    }
}
