package app.focal

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.engine.webview.FocalWebView
import app.focal.feature.browser.PullToRefreshPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PullToRefreshPhysicsTest {

    @Test
    fun physics_scalesLinearlyToTriggerAndRubberBandsAsymptoticallyBeyond() {
        assertEquals(0f, PullToRefreshPhysics.progressForRawPullDp(-10f), 0.0001f)
        assertEquals(0f, PullToRefreshPhysics.progressForRawPullDp(0f), 0.0001f)
        assertEquals(0.5f, PullToRefreshPhysics.progressForRawPullDp(52f), 0.001f)
        assertFalse(PullToRefreshPhysics.shouldTriggerRefresh(96f))

        assertEquals(1f, PullToRefreshPhysics.progressForRawPullDp(104f), 0.001f)
        assertTrue(PullToRefreshPhysics.shouldTriggerRefresh(104f))

        val hugePull = PullToRefreshPhysics.progressForRawPullDp(1_000f)
        assertTrue(
            "Rubber-band overshoot must exceed 1.0f but stay capped at <= 1.35f (got $hugePull)",
            hugePull in 1.15f..PullToRefreshPhysics.MAX_OVERSHOOT_PROGRESS,
        )
        assertEquals(270f, PullToRefreshPhysics.iconRotationDeg(1f), 0.01f)
        assertEquals(56f, PullToRefreshPhysics.puckOffsetDp(1f), 0.01f)
        assertTrue(PullToRefreshPhysics.puckOffsetDp(1.35f) <= 72.1f)
    }

    @Test
    fun focalWebView_armsOnTopOverscrollAndIgnoresMapsOrMidPageScrolls() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val events = mutableListOf<Triple<Float, Boolean, Boolean>>()

        class TestableFocalWebView : FocalWebView(app) {
            fun triggerOverscroll(deltaX: Int, deltaY: Int, scrollY: Int, isTouchEvent: Boolean): Boolean =
                overScrollBy(deltaX, deltaY, 0, scrollY, 0, 1000, 0, 0, isTouchEvent)
        }

        val webView = TestableFocalWebView().apply {
            layout(0, 0, 1080, 2400)
            onPullToRefreshGesture = { dp, active, released ->
                events += Triple(dp, active, released)
            }
        }

        val t0 = SystemClock.uptimeMillis()

        // 1. Without overScrollBy (simulating Google Maps preventDefault() or inner <div> scroll),
        // pulling down must NEVER trigger pull-to-refresh.
        val mapDown = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 540f, 300f, 0)
        val mapMove = MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, 540f, 700f, 0)
        val mapUp = MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_UP, 540f, 700f, 0)
        webView.onTouchEvent(mapDown)
        webView.onTouchEvent(mapMove)
        webView.onTouchEvent(mapUp)
        mapDown.recycle(); mapMove.recycle(); mapUp.recycle()
        assertTrue("Non-overscrolling pages (maps/inner divs) must not fire pull-to-refresh", events.isEmpty())

        // 2. Starting inside left 24dp Predictive Back edge must NOT arm pull-to-refresh.
        val edgeDown = MotionEvent.obtain(t0 + 100, t0 + 100, MotionEvent.ACTION_DOWN, 10f, 300f, 0)
        webView.onTouchEvent(edgeDown)
        webView.triggerOverscroll(deltaX = 0, deltaY = -40, scrollY = 0, isTouchEvent = true)
        val edgeMove = MotionEvent.obtain(t0 + 100, t0 + 116, MotionEvent.ACTION_MOVE, 10f, 700f, 0)
        webView.onTouchEvent(edgeMove)
        edgeDown.recycle(); edgeMove.recycle()
        assertTrue("Edge-back touches must never trigger pull-to-refresh", events.isEmpty())

        // 3. Real top-of-page overscroll (including async VSync isTouchEvent=false while finger is down)
        // arms, tracks pull smoothly, and releases cleanly.
        val t1 = t0 + 200
        val down = MotionEvent.obtain(t1, t1, MotionEvent.ACTION_DOWN, 540f, 300f, 0)
        webView.onTouchEvent(down)
        val overscrollRet = webView.triggerOverscroll(deltaX = 0, deltaY = -20, scrollY = 0, isTouchEvent = false)
        assertFalse("Top overscroll during active touch should be intercepted", overscrollRet)

        val move1 = MotionEvent.obtain(t1, t1 + 16, MotionEvent.ACTION_MOVE, 540f, 450f, 0)
        val move2 = MotionEvent.obtain(t1, t1 + 32, MotionEvent.ACTION_MOVE, 540f, 720f, 0)
        val up = MotionEvent.obtain(t1, t1 + 48, MotionEvent.ACTION_UP, 540f, 720f, 0)
        assertTrue(webView.onTouchEvent(move1))
        assertTrue(webView.onTouchEvent(move2))
        assertTrue(webView.onTouchEvent(up))
        down.recycle(); move1.recycle(); move2.recycle(); up.recycle()

        assertTrue("Pull-to-refresh should emit active drag updates", events.any { it.second && it.first > 0f })
        val releaseEvent = events.last()
        assertFalse(releaseEvent.second)
        assertTrue("Final release event must signal released=true", releaseEvent.third)
        assertTrue(
            "Pulling 420px down should exceed refresh threshold (rawPullDp=${releaseEvent.first})",
            PullToRefreshPhysics.shouldTriggerRefresh(releaseEvent.first),
        )

        // 4. Post-release fling bounce (finger lifted -> isTouchEvent=false & isFingerDown=false) must NOT arm.
        val countBeforeFling = events.size
        webView.triggerOverscroll(deltaX = 0, deltaY = -60, scrollY = 0, isTouchEvent = false)
        assertEquals("Fling overscroll after finger lift must not emit pull-to-refresh", countBeforeFling, events.size)
    }
}
