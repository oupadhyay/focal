package app.focal.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/**
 * Custom [WebView] subclass that hooks Chromium's native [overScrollBy] callback to arm
 * top-of-page pull-to-refresh cleanly without interfering with:
 * - Inner scrollable containers (SPAs like ChatGPT, Gmail, Reddit, Google AI Mode)
 * - JS touch handlers that call `event.preventDefault()` (Google Maps, canvases, sliders)
 * - CSS `overscroll-behavior-y: contain / none`
 * - Continuous upward scrolls from mid-page to top (`startedAtTop` must be `true` on `ACTION_DOWN`)
 * - Two-finger pinch-to-zoom / Pinch-to-Summarize (`pointerCount > 1` aborts immediately)
 * - Android 14–16 Predictive Back `24.dp` screen-edge zones
 */
open class FocalWebView(context: Context) : WebView(context) {

    var pullToRefreshEnabled: Boolean = true
        set(value) {
            field = value
            if (!value && pullLatched) {
                cancelActivePull()
            }
        }

    var onPullToRefreshGesture: ((rawPullDp: Float, isActive: Boolean, released: Boolean) -> Unit)? = null

    private val touchSlopPx: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(12f)
    private val density: Float = resources.displayMetrics.density.coerceAtLeast(1f)
    private val edgeGuardPx: Float = 24f * density

    private var isFingerDown: Boolean = false
    private var startedAtTop: Boolean = false
    private var armedByOverscroll: Boolean = false
    private var pullLatched: Boolean = false
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var downRawX: Float = 0f
    private var downRawY: Float = 0f
    private var lastMoveDx: Float = 0f
    private var lastMoveDy: Float = 0f
    private var pullAnchorRawY: Float = 0f

    /**
     * Chromium's `AwContents` invokes `WebView.PrivateAccess.super_overScrollBy`, which calls
     * `View.overScrollBy` directly (bypassing subclass `overScrollBy` overrides) and then dispatches
     * the virtual [onOverScrolled] callback with `clampedY = true` when the root viewport hits top.
     */
    override fun onOverScrolled(
        scrollX: Int,
        scrollY: Int,
        clampedX: Boolean,
        clampedY: Boolean,
    ) {
        if (
            pullToRefreshEnabled &&
            isFingerDown &&
            startedAtTop &&
            !pullLatched &&
            clampedY &&
            scrollY <= 0 &&
            lastMoveDy > 0f &&
            lastMoveDy >= abs(lastMoveDx)
        ) {
            armedByOverscroll = true
        }
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean,
    ): Boolean {
        if (
            pullToRefreshEnabled &&
            (isTouchEvent || isFingerDown) &&
            startedAtTop &&
            !pullLatched &&
            scrollY <= 0 &&
            deltaY < 0 &&
            abs(deltaY) >= abs(deltaX)
        ) {
            armedByOverscroll = true
            return false
        }
        return super.overScrollBy(
            deltaX,
            deltaY,
            scrollX,
            scrollY,
            scrollRangeX,
            scrollRangeY,
            maxOverScrollX,
            maxOverScrollY,
            isTouchEvent,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetGestureFlags()
                isFingerDown = true
                if (pullToRefreshEnabled && event.pointerCount == 1) {
                    val effectiveWidthPx = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val inBackEdge = event.x < edgeGuardPx ||
                        (effectiveWidthPx > 0 && event.x > (effectiveWidthPx - edgeGuardPx))
                    startedAtTop = !inBackEdge && scrollY <= 0 && !canScrollVertically(-1)
                    activePointerId = event.getPointerId(0)
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch (pinch-to-zoom / Pinch-to-Summarize) always takes priority over pull-to-refresh
                if (pullLatched) {
                    cancelActivePull()
                } else {
                    resetGestureFlags()
                }
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    if (pullLatched) {
                        cancelActivePull()
                    } else {
                        resetGestureFlags()
                    }
                    return super.onTouchEvent(event)
                }

                val pointerIndex = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    event.findPointerIndex(activePointerId)
                } else {
                    0
                }
                val currentRawX = if (pointerIndex >= 0) event.rawX else downRawX
                val currentRawY = if (pointerIndex >= 0) event.rawY else downRawY
                lastMoveDx = currentRawX - downRawX
                lastMoveDy = currentRawY - downRawY

                if (pullLatched) {
                    val rawPullDp = ((currentRawY - pullAnchorRawY).coerceAtLeast(0f)) / density
                    onPullToRefreshGesture?.invoke(rawPullDp, true, false)
                    return true
                }

                // Let Chromium process the move first so inner <div> scrollers, maps (preventDefault),
                // and CSS overscroll-behavior-y can consume it before onOverScrolled arms pull-to-refresh.
                val handled = super.onTouchEvent(event)

                if (pullToRefreshEnabled && startedAtTop && !pullLatched) {
                    val dy = lastMoveDy
                    val dx = abs(lastMoveDx)
                    val stalledLoadPull = progress < 100 &&
                        dy > touchSlopPx * 2f &&
                        dy > dx * 1.8f &&
                        scrollY <= 0 &&
                        !canScrollVertically(-1)
                    val overscrollPull = armedByOverscroll &&
                        dy > touchSlopPx &&
                        dy > dx * 1.35f &&
                        scrollY <= 0 &&
                        !canScrollVertically(-1)
                    if (overscrollPull || stalledLoadPull) {
                        pullLatched = true
                        pullAnchorRawY = downRawY + touchSlopPx * 0.5f
                        dispatchSyntheticCancelToSuper(event)
                        val rawPullDp = ((currentRawY - pullAnchorRawY).coerceAtLeast(0f)) / density
                        onPullToRefreshGesture?.invoke(rawPullDp, true, false)
                        return true
                    }
                }
                return handled
            }

            MotionEvent.ACTION_UP -> {
                if (pullLatched) {
                    val rawPullDp = ((event.rawY - pullAnchorRawY).coerceAtLeast(0f)) / density
                    resetGestureFlags()
                    onPullToRefreshGesture?.invoke(rawPullDp, false, true)
                    return true
                }
                resetGestureFlags()
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_CANCEL -> {
                if (pullLatched) {
                    cancelActivePull()
                    return true
                }
                resetGestureFlags()
                return super.onTouchEvent(event)
            }

            else -> return super.onTouchEvent(event)
        }
    }

    override fun onDetachedFromWindow() {
        if (pullLatched) {
            cancelActivePull()
        } else {
            resetGestureFlags()
        }
        super.onDetachedFromWindow()
    }

    private fun dispatchSyntheticCancelToSuper(source: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        val cancelEvent = MotionEvent.obtain(
            source.downTime,
            now,
            MotionEvent.ACTION_CANCEL,
            source.x,
            source.y,
            0,
        )
        try {
            super.onTouchEvent(cancelEvent)
        } finally {
            cancelEvent.recycle()
        }
    }

    private fun cancelActivePull() {
        resetGestureFlags()
        onPullToRefreshGesture?.invoke(0f, false, false)
    }

    private fun resetGestureFlags() {
        isFingerDown = false
        startedAtTop = false
        armedByOverscroll = false
        pullLatched = false
        lastMoveDx = 0f
        lastMoveDy = 0f
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }
}
