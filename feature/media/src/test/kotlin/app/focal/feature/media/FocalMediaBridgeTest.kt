package app.focal.feature.media

import app.focal.engine.api.ActiveMediaSessionInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FocalMediaBridgeTest {

    @Before
    fun setUp() {
        FocalMediaBridge.clearAll()
    }

    @After
    fun tearDown() {
        FocalMediaBridge.clearAll()
    }

    private fun sampleMedia(
        title: String = "Deep Dive Podcast",
        domain: String = "youtube.com",
        isPlaying: Boolean = true,
        currentTimeSec: Float = 12f,
    ) = ActiveMediaSessionInfo(
        title = title,
        domain = domain,
        isPlaying = isPlaying,
        playbackRate = 1.0f,
        hasVideoElement = true,
        durationSec = 1800f,
        currentTimeSec = currentTimeSec,
    )

    @Test
    fun dismissBackgroundPlayingTab_hidesDockWithoutKillingSystemMediaOrResurrectingOnTimeUpdate() {
        FocalMediaBridge.onSelectedTabChanged("tab-article")
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(currentTimeSec = 10f))

        assertEquals("tab-audio", FocalMediaBridge.activeMedia.value?.sessionTabId)
        assertEquals("tab-audio", FocalMediaBridge.dockMedia.value?.sessionTabId)

        // User swipes away the floating mini-player while reading another tab
        FocalMediaBridge.dismissMiniPlayer()

        // Dock pill is hidden immediately, while Media3 system session stays live
        assertNull(FocalMediaBridge.dockMedia.value)
        assertEquals("tab-audio", FocalMediaBridge.activeMedia.value?.sessionTabId)

        // Subsequent 1-second timeupdate ticks MUST NOT resurrect the dismissed mini-player
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(currentTimeSec = 11f))
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(currentTimeSec = 12f))
        assertNull(FocalMediaBridge.dockMedia.value)
        assertEquals(12f, FocalMediaBridge.activeMedia.value?.media?.currentTimeSec)

        // Switching to a third tab also keeps the dismissed audio hidden
        FocalMediaBridge.onSelectedTabChanged("tab-third")
        assertNull(FocalMediaBridge.dockMedia.value)
    }

    @Test
    fun dismissedTab_reappearsWhenNewTrackStartsOrMediaEndsAndRestarts() {
        FocalMediaBridge.onSelectedTabChanged("tab-article")
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(title = "Episode 1"))
        FocalMediaBridge.dismissMiniPlayer()
        assertNull(FocalMediaBridge.dockMedia.value)

        // Next episode / track starts in the same tab -> mini-player reappears cleanly
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(title = "Episode 2"))
        assertEquals("Episode 2", FocalMediaBridge.dockMedia.value?.media?.title)

        // Dismiss again, then end media and start fresh -> reappears cleanly
        FocalMediaBridge.dismissMiniPlayer()
        assertNull(FocalMediaBridge.dockMedia.value)
        FocalMediaBridge.updateActiveMedia("tab-audio", null)
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(title = "Episode 2"))
        assertNotNull(FocalMediaBridge.dockMedia.value)
    }

    @Test
    fun dismissedPausedMedia_reappearsWhenExplicitlyResumed() {
        FocalMediaBridge.onSelectedTabChanged("tab-article")
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(isPlaying = false))
        FocalMediaBridge.dismissMiniPlayer()
        assertNull(FocalMediaBridge.dockMedia.value)

        // Still paused -> stays hidden
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(isPlaying = false, currentTimeSec = 25f))
        assertNull(FocalMediaBridge.dockMedia.value)

        // User explicitly hits Play again -> mini-player returns
        FocalMediaBridge.updateActiveMedia("tab-audio", sampleMedia(isPlaying = true, currentTimeSec = 26f))
        assertEquals("tab-audio", FocalMediaBridge.dockMedia.value?.sessionTabId)
    }

    @Test
    fun dismissingOneTab_stillAllowsDifferentPlayingTabToAppear() {
        FocalMediaBridge.onSelectedTabChanged("tab-article")
        FocalMediaBridge.updateActiveMedia("tab-audio-1", sampleMedia(title = "Music Stream"))
        FocalMediaBridge.dismissMiniPlayer()
        assertNull(FocalMediaBridge.dockMedia.value)

        FocalMediaBridge.updateActiveMedia("tab-audio-2", sampleMedia(title = "Keynote Video", domain = "vimeo.com"))
        assertEquals("tab-audio-2", FocalMediaBridge.dockMedia.value?.sessionTabId)
        assertEquals("Keynote Video", FocalMediaBridge.dockMedia.value?.media?.title)
    }

    @Test
    fun swipePhysics_supportsLeftAndRightThresholdsAndRejectsShortOrOppositeCancels() {
        val widthPx = 720
        val minThresholdPx = 216f // 72dp @ 3x
        val flingVelPx = 1950f // 650dp/s @ 3x
        val minFlingOffsetPx = 48f // 16dp @ 3x

        // Past 35% width (252px) left or right -> dismiss even at 0 velocity
        assertTrue(MediaMiniPlayerSwipePhysics.shouldDismiss(-260f, 0f, widthPx, minThresholdPx, flingVelPx, minFlingOffsetPx))
        assertTrue(MediaMiniPlayerSwipePhysics.shouldDismiss(260f, 0f, widthPx, minThresholdPx, flingVelPx, minFlingOffsetPx))

        // Quick aligned flick past 16dp -> dismiss
        assertTrue(MediaMiniPlayerSwipePhysics.shouldDismiss(-60f, -2200f, widthPx, minThresholdPx, flingVelPx, minFlingOffsetPx))
        assertTrue(MediaMiniPlayerSwipePhysics.shouldDismiss(60f, 2200f, widthPx, minThresholdPx, flingVelPx, minFlingOffsetPx))

        // Tiny drag without fling -> snap back
        assertFalse(MediaMiniPlayerSwipePhysics.shouldDismiss(-80f, -200f, widthPx, minThresholdPx, flingVelPx, minFlingOffsetPx))

        // Opposite-direction flick (user changed mind and pulled back) -> snap back
        assertFalse(MediaMiniPlayerSwipePhysics.shouldDismiss(-90f, 2400f, widthPx, minThresholdPx, flingVelPx, minFlingOffsetPx))

        // Exit target follows swipe direction past pill width
        assertTrue(MediaMiniPlayerSwipePhysics.dismissExitTargetPx(-120f, -500f, widthPx, 288f) <= -1000f)
        assertTrue(MediaMiniPlayerSwipePhysics.dismissExitTargetPx(120f, 500f, widthPx, 288f) >= 1000f)
    }
}
