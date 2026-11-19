package app.focal.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureLeaseControllerTest {

    @Test
    fun mutualExclusion_blocksCompetingGestureUntilReleased() {
        val controller = GestureLeaseController()

        val scrubToken = controller.tryAcquire(GestureLeaseOwner.HORIZONTAL_TAB_SCRUB)
        assertNotNull(scrubToken)
        assertTrue(controller.isValid(scrubToken))
        assertTrue(controller.isHeldBy(GestureLeaseOwner.HORIZONTAL_TAB_SCRUB))
        assertTrue(controller.isHeldByOther(GestureLeaseOwner.PINCH_SUMMARIZE))

        // Competing gesture is cleanly denied while horizontal tab scrub holds the lease
        assertNull(controller.tryAcquire(GestureLeaseOwner.PINCH_SUMMARIZE))
        assertNull(controller.tryAcquire(GestureLeaseOwner.SPACES_SWIPE_UP))
        assertNull(controller.tryAcquire(GestureLeaseOwner.SHEET_DRAG))

        // Releasing with wrong owner has no effect
        assertFalse(controller.releaseOwner(GestureLeaseOwner.PINCH_SUMMARIZE))
        assertTrue(controller.isValid(scrubToken))

        // Releasing with active token clears ownership and allows next gesture
        assertTrue(controller.release(scrubToken))
        assertNull(controller.activeOwner)
        assertFalse(controller.isValid(scrubToken))

        val pinchToken = controller.tryAcquire(GestureLeaseOwner.PINCH_SUMMARIZE)
        assertNotNull(pinchToken)
        assertEquals(GestureLeaseOwner.PINCH_SUMMARIZE, controller.activeOwner)
    }

    @Test
    fun preemptForSystem_invalidatesPriorTokenAndIgnoresStaleRelease() {
        val controller = GestureLeaseController()

        val spacesToken = controller.tryAcquire(GestureLeaseOwner.SPACES_SWIPE_UP)
        assertNotNull(spacesToken)

        val backToken = controller.preemptForSystem(GestureLeaseOwner.PREDICTIVE_BACK)
        assertEquals(GestureLeaseOwner.PREDICTIVE_BACK, controller.activeOwner)
        assertFalse(controller.isValid(spacesToken))
        assertTrue(controller.isValid(backToken))

        // Stale finally-block release from preempted gesture must NOT release Predictive Back
        assertFalse(controller.release(spacesToken))
        assertTrue(controller.isValid(backToken))
        assertEquals(GestureLeaseOwner.PREDICTIVE_BACK, controller.activeOwner)

        // System release cleans up cleanly
        assertTrue(controller.release(backToken))
        assertNull(controller.activeOwner)
    }

    @Test
    fun sameOwnerReacquire_rotatesGenerationAndInvalidatesOldToken() {
        val controller = GestureLeaseController()

        val first = controller.tryAcquire(GestureLeaseOwner.SHEET_DRAG)
        val second = controller.tryAcquire(GestureLeaseOwner.SHEET_DRAG)
        assertNotNull(first)
        assertNotNull(second)
        assertFalse(controller.isValid(first))
        assertTrue(controller.isValid(second))
        assertFalse(controller.release(first))
        assertTrue(controller.release(second))
    }
}
