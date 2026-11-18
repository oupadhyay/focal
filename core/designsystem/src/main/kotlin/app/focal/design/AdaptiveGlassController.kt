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

@Immutable
data class GlassLuminanceState(
    val isDarkGlass: Boolean = true,
    val medianLuminance: Float = 0.08f,
    val lightStatusBarIcons: Boolean = true,
)

/**
 * Off-thread `16x16` `PixelCopy` luminance sampler running on a dedicated background
 * [HandlerThread] (`Process.THREAD_PRIORITY_BACKGROUND`) for zero UI-thread stalls.
 *
 * Samples a tiny `16x16` `ARGB_8888` downsample of the bottom `64.dp` region every `250ms`
 * while scrolling (and immediately on navigation commit), computes BT.709 median luminance,
 * and applies **`0.08f` hysteresis** (`dark -> light` at `0.52f`, `light -> dark` at `0.44f`)
 * to prevent visual flicker near mid-gray web surfaces.
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
    }

    private val _state = MutableStateFlow(
        GlassLuminanceState(
            isDarkGlass = initialDarkGlass,
            medianLuminance = if (initialDarkGlass) 0.08f else 0.92f,
            lightStatusBarIcons = initialDarkGlass,
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
    private val inFlight = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var lastSampleElapsedMs: Long = 0L

    /**
     * Throttled `250ms` sample trigger invoked during 120Hz page scrolling.
     */
    fun onViewportScrolled(
        window: Window?,
        bottomSampleRectPx: Rect,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleElapsedMs < SAMPLE_THROTTLE_MS) return
        requestSampleInternal(window, bottomSampleRectPx, now)
    }

    /**
     * Immediate sample trigger invoked on page navigation commit or theme toggle.
     */
    fun onNavigationCommitted(
        window: Window?,
        bottomSampleRectPx: Rect,
    ) {
        requestSampleInternal(window, bottomSampleRectPx, SystemClock.elapsedRealtime())
    }

    /**
     * Direct luminance update (used by unit tests or synthetic reader surfaces).
     */
    fun updateFromMeasuredLuminance(medianLuminance: Float) {
        val prev = _state.value
        val nextDark = evaluateDarkGlassWithHysteresis(prev.isDarkGlass, medianLuminance)
        _state.value = GlassLuminanceState(
            isDarkGlass = nextDark,
            medianLuminance = medianLuminance.coerceIn(0f, 1f),
            lightStatusBarIcons = nextDark,
        )
    }

    private fun requestSampleInternal(
        window: Window?,
        sampleRect: Rect,
        timestampMs: Long,
    ) {
        if (closed.get() || window == null) return
        if (sampleRect.width() <= 0 || sampleRect.height() <= 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!inFlight.compareAndSet(false, true)) return

        lastSampleElapsedMs = timestampMs
        val rectCopy = Rect(sampleRect)

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
                            updateFromMeasuredLuminance(median)
                        }
                    } finally {
                        inFlight.set(false)
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
            samplerHandler.post {
                if (!sampleBitmap.isRecycled) {
                    sampleBitmap.recycle()
                }
                samplerThread.quitSafely()
            }
        }
    }
}
