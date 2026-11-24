package app.focal.design

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Immutable
data class GlassLuminanceState(
    val isDarkGlass: Boolean = true,
    val medianLuminance: Float = 0.08f,
    val lightStatusBarIcons: Boolean = true,
    val sampledSurfaceArgb: Int? = null,
)

/**
 * Off-thread `16x16` `PixelCopy` luminance sampler running on a dedicated background
 * [HandlerThread] (`Process.THREAD_PRIORITY_BACKGROUND`) for zero UI-thread stalls.
 *
 * Samples a tiny `16x16` `ARGB_8888` downsample of an unobstructed lower-viewport WebView strip
 * strictly **above** Focal's floating bottom bar and dock (preventing any self-sampling feedback
 * loop where light glass could lock itself light over dark web pages), computes BT.709 median
 * luminance, and applies **`0.08f` hysteresis** (`dark -> light` at `0.52f`, `light -> dark` at
 * `0.44f`) to prevent flicker near mid-gray web surfaces.
 */
class AdaptiveGlassController(
    initialDarkGlass: Boolean = true,
) : Closeable {

    companion object {
        const val SAMPLE_GRID_SIZE: Int = 16
        const val SAMPLE_THROTTLE_MS: Long = 250L

        /** Switch from Light Alabaster Glass -> Dark Smoked OLED Glass at or below `0.44f`. */
        const val LIGHT_TO_DARK_THRESHOLD: Float = 0.44f

        /** Switch from Dark Smoked OLED Glass -> Light Alabaster Glass at or above `0.52f` (`0.08f` hysteresis). */
        const val DARK_TO_LIGHT_THRESHOLD: Float = 0.52f

        /** Pure-JVM pixel rectangle for unit-testable sampling geometry. */
        data class SampleStripBounds(
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int,
        ) {
            val width: Int get() = right - left
            val height: Int get() = bottom - top
            fun toRect(): Rect = Rect(left, top, right, bottom)
        }

        /**
         * Pure-JVM computation of an unobstructed lower-viewport WebView sampling strip strictly
         * above Focal's floating bottom dock (`navigationBarBottomPx + dockClearancePx`) and below
         * the status bar, inset horizontally to skip edge scrollbars.
         */
        fun computeUnobstructedSampleBounds(
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            statusBarTopPx: Int,
            navigationBarBottomPx: Int,
            dockClearancePx: Int,
            stripHeightPx: Int,
            horizontalInsetPx: Int,
        ): SampleStripBounds? {
            if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || stripHeightPx <= 0) return null
            val safeInsetX = horizontalInsetPx.coerceIn(0, (viewportWidthPx - 4).coerceAtLeast(0) / 2)
            val left = safeInsetX
            val right = viewportWidthPx - safeInsetX
            if (right - left < 4) return null

            val minTop = (statusBarTopPx + 8).coerceIn(0, viewportHeightPx)
            val clearedBottom = (viewportHeightPx - navigationBarBottomPx - dockClearancePx)
                .coerceIn(minTop, viewportHeightPx)

            val bottom: Int
            val top: Int
            if (clearedBottom - minTop >= 8) {
                bottom = clearedBottom
                top = (bottom - stripHeightPx).coerceAtLeast(minTop)
            } else {
                // Ultra-compact split-screen/PiP fallback: sample center band of available viewport
                val centerY = viewportHeightPx / 2
                val halfStrip = (stripHeightPx / 2).coerceAtLeast(4)
                top = (centerY - halfStrip).coerceAtLeast(0)
                bottom = (centerY + halfStrip).coerceAtMost(viewportHeightPx)
            }

            if (bottom - top < 4) return null
            return SampleStripBounds(left = left, top = top, right = right, bottom = bottom)
        }

        /**
         * Computes a clean lower-viewport sampling [Rect] strictly above Focal's floating bottom
         * dock (`navigationBarBottomPx + dockClearancePx`) and below the status bar.
         */
        fun computeUnobstructedSampleRect(
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            statusBarTopPx: Int,
            navigationBarBottomPx: Int,
            dockClearancePx: Int,
            stripHeightPx: Int,
            horizontalInsetPx: Int,
        ): Rect? = computeUnobstructedSampleBounds(
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            statusBarTopPx = statusBarTopPx,
            navigationBarBottomPx = navigationBarBottomPx,
            dockClearancePx = dockClearancePx,
            stripHeightPx = stripHeightPx,
            horizontalInsetPx = horizontalInsetPx,
        )?.toRect()

        /**
         * Pure hysteresis transition function (`0.08f` deadband between `0.44f` and `0.52f`).
         */
        fun evaluateDarkGlassWithHysteresis(
            currentlyDarkGlass: Boolean,
            medianLuminance: Float,
        ): Boolean {
            val clamped = medianLuminance.coerceIn(0f, 1f)
            return if (currentlyDarkGlass) {
                // Stay dark until page luminance convincingly crosses 0.52f
                clamped < DARK_TO_LIGHT_THRESHOLD
            } else {
                // Stay light until page luminance drops to or below 0.44f
                clamped <= LIGHT_TO_DARK_THRESHOLD
            }
        }

        /**
         * Computes exact BT.709 relative luminance (`0.2126*R + 0.7152*G + 0.0722*B`) median
         * across [pixelCount] `ARGB_8888` pixels using pre-allocated [scratchLuma] for zero GC churn.
         */
        fun computeBt709MedianLuminance(
            pixels: IntArray,
            pixelCount: Int,
            scratchLuma: FloatArray,
        ): Float {
            if (pixelCount <= 0) return 0f
            val inv255 = 1f / 255f
            for (i in 0 until pixelCount) {
                val argb = pixels[i]
                val r = ((argb ushr 16) and 0xFF) * inv255
                val g = ((argb ushr 8) and 0xFF) * inv255
                val b = (argb and 0xFF) * inv255
                scratchLuma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }
            scratchLuma.sort(0, pixelCount)
            val mid = pixelCount ushr 1
            return if ((pixelCount and 1) == 1) {
                scratchLuma[mid]
            } else {
                0.5f * (scratchLuma[mid - 1] + scratchLuma[mid])
            }
        }

        /**
         * Computes component-wise median opaque `ARGB` across [pixelCount] pixels using
         * pre-allocated scratch buffers, ignoring thin text/link outliers to recover the page's
         * true background surface color for seamless bottom-dock seating.
         */
        fun computeMedianSurfaceArgb(
            pixels: IntArray,
            pixelCount: Int,
            scratchR: IntArray,
            scratchG: IntArray,
            scratchB: IntArray,
        ): Int {
            if (pixelCount <= 0) return 0xFF0A0A0A.toInt()
            for (i in 0 until pixelCount) {
                val argb = pixels[i]
                scratchR[i] = (argb ushr 16) and 0xFF
                scratchG[i] = (argb ushr 8) and 0xFF
                scratchB[i] = argb and 0xFF
            }
            scratchR.sort(0, pixelCount)
            scratchG.sort(0, pixelCount)
            scratchB.sort(0, pixelCount)
            val mid = pixelCount ushr 1
            val r = scratchR[mid]
            val g = scratchG[mid]
            val b = scratchB[mid]
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private data class PendingSampleRequest(
        val window: Window,
        val rect: Rect,
    )

    private val _state = MutableStateFlow(
        GlassLuminanceState(
            isDarkGlass = initialDarkGlass,
            medianLuminance = if (initialDarkGlass) 0.08f else 0.92f,
            lightStatusBarIcons = initialDarkGlass,
            sampledSurfaceArgb = null,
        ),
    )
    val state: StateFlow<GlassLuminanceState> = _state.asStateFlow()

    private val samplerThread = HandlerThread(
        "FocalLuminanceSampler",
        Process.THREAD_PRIORITY_BACKGROUND,
    ).apply { start() }

    private val samplerHandler = Handler(samplerThread.looper)

    // Pre-allocated 16x16 bitmap and scratch arrays used exclusively on samplerThread (zero per-frame allocs)
    private val sampleBitmap: Bitmap = Bitmap.createBitmap(
        SAMPLE_GRID_SIZE,
        SAMPLE_GRID_SIZE,
        Bitmap.Config.ARGB_8888,
    )
    private val pixelBuffer = IntArray(SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE)
    private val lumaScratch = FloatArray(SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE)
    private val redScratch = IntArray(SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE)
    private val greenScratch = IntArray(SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE)
    private val blueScratch = IntArray(SAMPLE_GRID_SIZE * SAMPLE_GRID_SIZE)
    private val inFlight = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val pendingPrioritySample = AtomicReference<PendingSampleRequest?>(null)

    @Volatile
    private var lastSampleElapsedMs: Long = 0L

    /**
     * Throttled `250ms` sample trigger invoked during 120Hz page scrolling.
     */
    fun onViewportScrolled(
        window: Window?,
        unobstructedSampleRectPx: Rect,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleElapsedMs < SAMPLE_THROTTLE_MS) return
        requestSampleInternal(
            window = window,
            sampleRect = unobstructedSampleRectPx,
            timestampMs = now,
            queueIfBusy = false,
        )
    }

    /**
     * Priority sample trigger invoked on page navigation commit, `onPageFinished` settle,
     * tab switch, theme override change, or modal overlay dismissal. If a previous `PixelCopy`
     * is still in flight, queues the latest request so it executes immediately upon completion.
     */
    fun onNavigationCommitted(
        window: Window?,
        unobstructedSampleRectPx: Rect,
    ) {
        requestSampleInternal(
            window = window,
            sampleRect = unobstructedSampleRectPx,
            timestampMs = SystemClock.elapsedRealtime(),
            queueIfBusy = true,
        )
    }

    /**
     * Immediately resets the glass theme to Dark Smoked OLED (used on the Calm Launch Canvas
     * where no web page is loaded).
     */
    fun resetToDarkOled() {
        pendingPrioritySample.set(null)
        _state.value = GlassLuminanceState(
            isDarkGlass = true,
            medianLuminance = 0.08f,
            lightStatusBarIcons = true,
            sampledSurfaceArgb = null,
        )
    }

    /**
     * Direct luminance update (used by unit tests or synthetic reader surfaces).
     */
    fun updateFromMeasuredLuminance(
        medianLuminance: Float,
        sampledSurfaceArgb: Int? = _state.value.sampledSurfaceArgb,
    ) {
        val prev = _state.value
        val nextDark = evaluateDarkGlassWithHysteresis(prev.isDarkGlass, medianLuminance)
        _state.value = GlassLuminanceState(
            isDarkGlass = nextDark,
            medianLuminance = medianLuminance.coerceIn(0f, 1f),
            lightStatusBarIcons = nextDark,
            sampledSurfaceArgb = sampledSurfaceArgb,
        )
    }

    private fun requestSampleInternal(
        window: Window?,
        sampleRect: Rect,
        timestampMs: Long,
        queueIfBusy: Boolean,
    ) {
        if (closed.get() || window == null) return
        if (sampleRect.width() <= 0 || sampleRect.height() <= 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val rectCopy = Rect(sampleRect)
        if (!inFlight.compareAndSet(false, true)) {
            if (queueIfBusy) {
                pendingPrioritySample.set(PendingSampleRequest(window, rectCopy))
            }
            return
        }

        lastSampleElapsedMs = timestampMs

        try {
            PixelCopy.request(
                window,
                rectCopy,
                sampleBitmap,
                { copyResult ->
                    try {
                        if (!closed.get() && copyResult == PixelCopy.SUCCESS) {
                            sampleBitmap.getPixels(
                                pixelBuffer,
                                0,
                                SAMPLE_GRID_SIZE,
                                0,
                                0,
                                SAMPLE_GRID_SIZE,
                                SAMPLE_GRID_SIZE,
                            )
                            val median = computeBt709MedianLuminance(
                                pixels = pixelBuffer,
                                pixelCount = pixelBuffer.size,
                                scratchLuma = lumaScratch,
                            )
                            val surfaceArgb = computeMedianSurfaceArgb(
                                pixels = pixelBuffer,
                                pixelCount = pixelBuffer.size,
                                scratchR = redScratch,
                                scratchG = greenScratch,
                                scratchB = blueScratch,
                            )
                            updateFromMeasuredLuminance(
                                medianLuminance = median,
                                sampledSurfaceArgb = surfaceArgb,
                            )
                        }
                    } finally {
                        inFlight.set(false)
                        val queued = pendingPrioritySample.getAndSet(null)
                        if (queued != null && !closed.get()) {
                            requestSampleInternal(
                                window = queued.window,
                                sampleRect = queued.rect,
                                timestampMs = SystemClock.elapsedRealtime(),
                                queueIfBusy = false,
                            )
                        }
                    }
                },
                samplerHandler,
            )
        } catch (_: RuntimeException) {
            // Surface may not yet be backed by hardware memory or may be mid-transition
            inFlight.set(false)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            pendingPrioritySample.set(null)
            samplerHandler.post {
                if (!sampleBitmap.isRecycled) {
                    sampleBitmap.recycle()
                }
                samplerThread.quitSafely()
            }
        }
    }
}
