package app.focal.feature.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.absoluteValue

class TabDeckPhysicsTest {

    private val stepPx = 500f

    @Test
    fun lightMediumAndStrongFlicksScaleAcrossMultipleTabs() {
        val maxFocus = 19f // 20 tabs in deck

        // Light flicks cleanly commit 1 tab
        val lightFromRest = TabDeckPhysics.projectFlingTargetFocus(
            anchorFocus = 2f,
            currentFocus = 2f,
            velocityXpxPerSec = -500f,
            stepPx = stepPx,
            maxFocus = maxFocus,
        )
        assertEquals(3, lightFromRest)

        val lightWithDrag = TabDeckPhysics.projectFlingTargetFocus(
            anchorFocus = 2f,
            currentFocus = 2.1f,
            velocityXpxPerSec = -750f,
            stepPx = stepPx,
            maxFocus = maxFocus,
        )
        assertEquals(3, lightWithDrag)

        // Medium flicks glide 2-4 tabs (no 1-tab HorizontalPager clamp)
        val mediumSlow = TabDeckPhysics.projectFlingTargetFocus(
            anchorFocus = 2f,
            currentFocus = 2.0f,
            velocityXpxPerSec = -3_500f,
            stepPx = stepPx,
            maxFocus = maxFocus,
        )
        assertTrue("Expected medium-slow flick to advance 2..4 tabs, got ${mediumSlow - 2}", (mediumSlow - 2) in 2..4)

        val mediumBrisk = TabDeckPhysics.projectFlingTargetFocus(
            anchorFocus = 2f,
            currentFocus = 2.15f,
            velocityXpxPerSec = -4_500f,
            stepPx = stepPx,
            maxFocus = maxFocus,
        )
        assertTrue("Expected brisk medium flick to advance 2..4 tabs, got ${mediumBrisk - 2}", (mediumBrisk - 2) in 2..4)

        val mediumFast = TabDeckPhysics.projectFlingTargetFocus(
            anchorFocus = 2f,
            currentFocus = 2.0f,
            velocityXpxPerSec = -6_500f,
            stepPx = stepPx,
            maxFocus = maxFocus,
        )
        assertTrue("Expected fast medium flick to advance 2..4 tabs, got ${mediumFast - 2}", (mediumFast - 2) in 2..4)

        // Strong flicks cruise across 5+ tabs
        val strongFlick = TabDeckPhysics.projectFlingTargetFocus(
            anchorFocus = 0f,
            currentFocus = 0.2f,
            velocityXpxPerSec = -11_000f,
            stepPx = stepPx,
            maxFocus = maxFocus,
        )
        assertTrue("Expected strong flick to advance >= 5 tabs, got $strongFlick", strongFlick >= 5)

        val whipFlick = TabDeckPhysics.projectFlingTargetFocus(
            anchorFocus = 0f,
            currentFocus = 0.2f,
            velocityXpxPerSec = -18_000f,
            stepPx = stepPx,
            maxFocus = maxFocus,
        )
        assertTrue("Expected whip flick to advance >= 9 tabs, got $whipFlick", whipFlick >= 9)
        assertTrue("Stronger flick must project strictly farther", whipFlick > strongFlick)
    }

    @Test
    fun reverseDirectionFlicksAreSymmetric() {
        val anchor = 10f
        val maxFocus = 20f
        val speeds = listOf(500f, 1_200f, 3_500f, 5_000f, 9_500f, 15_000f)

        for (speed in speeds) {
            val forwardTarget = TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = anchor,
                currentFocus = anchor,
                velocityXpxPerSec = -speed,
                stepPx = stepPx,
                maxFocus = maxFocus,
            )
            val reverseTarget = TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = anchor,
                currentFocus = anchor,
                velocityXpxPerSec = speed,
                stepPx = stepPx,
                maxFocus = maxFocus,
            )
            val forwardDelta = forwardTarget - anchor.toInt()
            val reverseDelta = anchor.toInt() - reverseTarget
            assertTrue("Forward flick at $speed px/s must advance at least 1 tab", forwardDelta >= 1)
            assertEquals("Flicks at $speed px/s must be symmetric", forwardDelta, reverseDelta)
        }
    }

    @Test
    fun clampsTargetsAtDeckBoundsAndSingleTabDeck() {
        assertEquals(
            12,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 10f,
                currentFocus = 10.4f,
                velocityXpxPerSec = -25_000f,
                stepPx = stepPx,
                maxFocus = 12f,
            ),
        )
        assertEquals(
            0,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 2f,
                currentFocus = 1.6f,
                velocityXpxPerSec = 25_000f,
                stepPx = stepPx,
                maxFocus = 12f,
            ),
        )
        assertEquals(
            0,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 0f,
                currentFocus = 0.25f,
                velocityXpxPerSec = -8_000f,
                stepPx = stepPx,
                maxFocus = 0f,
            ),
        )
    }

    @Test
    fun microDragWithoutFlickVelocitySnapsBackToAnchor() {
        assertEquals(
            4,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 4f,
                currentFocus = 4.20f,
                velocityXpxPerSec = 0f,
                stepPx = stepPx,
                maxFocus = 10f,
            ),
        )
        assertEquals(
            4,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 4f,
                currentFocus = 3.80f,
                velocityXpxPerSec = 0f,
                stepPx = stepPx,
                maxFocus = 10f,
            ),
        )
        assertEquals(
            4,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 4f,
                currentFocus = 4.10f,
                velocityXpxPerSec = -150f,
                stepPx = stepPx,
                maxFocus = 10f,
            ),
        )

        // Deliberate slow drag past 25% threshold commits 1 tab cleanly
        assertEquals(
            5,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 4f,
                currentFocus = 4.30f,
                velocityXpxPerSec = 0f,
                stepPx = stepPx,
                maxFocus = 10f,
            ),
        )
        assertEquals(
            3,
            TabDeckPhysics.projectFlingTargetFocus(
                anchorFocus = 4f,
                currentFocus = 3.70f,
                velocityXpxPerSec = 0f,
                stepPx = stepPx,
                maxFocus = 10f,
            ),
        )
    }

    @Test
    fun rubberBandAndResistedFocusAreMonotonicSymmetricAndAsymptoticallyBounded() {
        val maxRange = 200f
        assertEquals(0f, TabDeckPhysics.rubberBand(0f, maxRange), 0.0001f)

        val samples = listOf(-10_000f, -600f, -200f, -50f, -5f, 0f, 5f, 50f, 200f, 600f, 10_000f)
        val outputs = samples.map { TabDeckPhysics.rubberBand(it, maxRange) }
        for (i in 1 until outputs.size) {
            assertTrue(
                "rubberBand must be strictly increasing: ${outputs[i - 1]} < ${outputs[i]}",
                outputs[i] > outputs[i - 1],
            )
        }
        for (out in outputs) {
            assertTrue("rubberBand must stay strictly inside (-maxRange, maxRange)", out.absoluteValue < maxRange)
        }
        assertEquals(
            -TabDeckPhysics.rubberBand(175f, maxRange),
            TabDeckPhysics.rubberBand(-175f, maxRange),
            0.0001f,
        )

        val maxFocus = 5f
        val focusSamples = listOf(-5f, -1.5f, -0.4f, -0.05f, 0f, 1.5f, 5f, 5.05f, 5.4f, 6.5f, 10f)
        val resisted = focusSamples.map { TabDeckPhysics.resistedFocus(it, maxFocus, stepPx) }
        for (i in 1 until resisted.size) {
            assertTrue(
                "resistedFocus must be strictly monotonic: ${resisted[i - 1]} < ${resisted[i]}",
                resisted[i] > resisted[i - 1],
            )
        }
        assertEquals(0f, TabDeckPhysics.resistedFocus(0f, maxFocus, stepPx), 0.0001f)
        assertEquals(2.5f, TabDeckPhysics.resistedFocus(2.5f, maxFocus, stepPx), 0.0001f)
        assertEquals(5f, TabDeckPhysics.resistedFocus(5f, maxFocus, stepPx), 0.0001f)
        assertTrue(resisted.first() > -TabDeckPhysics.MAX_EDGE_OVERSHOOT_CARDS)
        assertTrue(resisted.last() < maxFocus + TabDeckPhysics.MAX_EDGE_OVERSHOOT_CARDS)
    }

    @Test
    fun computeCardVisualsMatchesFrontRestAndSymmetricDepthCurve() {
        val cardWidthPx = 900f
        val density = 3f

        val front = TabDeckPhysics.computeCardVisuals(depth = 0f, cardWidthPx = cardWidthPx, density = density)
        assertEquals(0f, front.translationX, 0.0001f)
        assertEquals(0f, front.translationY, 0.0001f)
        assertEquals(1f, front.scale, 0.0001f)
        assertEquals(0f, front.rotationY, 0.0001f)
        assertEquals(0f, front.veilAlpha, 0.0001f)
        assertEquals(1f, front.alpha, 0.0001f)
        assertEquals(1f, front.titleAlpha, 0.0001f)
        assertEquals(100f, front.zIndex, 0.0001f)

        val leftNeighbor = TabDeckPhysics.computeCardVisuals(depth = 1f, cardWidthPx = cardWidthPx, density = density)
        val rightNeighbor = TabDeckPhysics.computeCardVisuals(depth = -1f, cardWidthPx = cardWidthPx, density = density)
        assertTrue("Left neighbor (depth > 0) translates left", leftNeighbor.translationX < 0f)
        assertEquals(-leftNeighbor.translationX, rightNeighbor.translationX, 0.001f)
        assertEquals(-leftNeighbor.rotationY, rightNeighbor.rotationY, 0.001f)
        assertEquals(leftNeighbor.scale, rightNeighbor.scale, 0.0001f)
        assertEquals(leftNeighbor.veilAlpha, rightNeighbor.veilAlpha, 0.0001f)
        assertTrue("Neighbor scales down cleanly", leftNeighbor.scale in 0.85f..0.96f)
        assertTrue("Neighbor dimming veil is visible", leftNeighbor.veilAlpha in 0.15f..0.35f)
        assertTrue(
            "Rotation stays strictly within +/-3.5 degrees",
            leftNeighbor.rotationY.absoluteValue <= TabDeckPhysics.MAX_ROTATION_Y_DEGREES,
        )

        val nearFadeEdge = TabDeckPhysics.computeCardVisuals(depth = 2.2f, cardWidthPx = cardWidthPx, density = density)
        val fadingOut = TabDeckPhysics.computeCardVisuals(depth = 2.7f, cardWidthPx = cardWidthPx, density = density)
        val farCard = TabDeckPhysics.computeCardVisuals(depth = 3.5f, cardWidthPx = cardWidthPx, density = density)
        assertEquals(1f, nearFadeEdge.alpha, 0.0001f)
        assertTrue("Alpha fades smoothly past |depth| > 2.2f", fadingOut.alpha in 0.1f..0.9f)
        assertEquals(0f, farCard.alpha, 0.0001f)
        assertTrue(
            "Far card rotation never exceeds +/-3.5 degrees",
            farCard.rotationY.absoluteValue <= TabDeckPhysics.MAX_ROTATION_Y_DEGREES,
        )
    }
}
