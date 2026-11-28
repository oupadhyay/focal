package app.focal.feature.browser

import kotlin.math.abs

/**
 * Pure, zero-allocation scroll-speed-coupled physics controller for Focal's floating bottom bar.
 *
 * Unlike binary threshold toggles (`deltaY > 18dp -> snap 0f..1f`), this controller drives the
 * bar's continuous hide fraction (`0f` fully visible .. `1f` fully hidden) directly from WebView
 * scroll deltas and smoothed scroll velocity (`dp/s`):
 * - Slow reading drags glide the bar smoothly at the exact pace of the user's thumb (with gentle
 *   low-speed damping so accidental micro-twitches don't wobble the bar).
 * - Fast flicks accelerate hide responsiveness smoothly via a velocity gain curve (`0.78x -> 1.65x`),
 *   so fast scrolls whisk the search bar away proportionally faster than slow scrolls.
 * - Reversing scroll direction resets the velocity estimator on frame 1 and applies a `1.25x`
 *   reveal multiplier so peeking the search bar back up always feels immediate and effortless.
 * - Exposes a discrete [StepResult.settledHidden] flag that only transitions at the endpoints
 *   (`>= 0.995f` / `<= 0.005f`) so WebView layout padding never stutters mid-scroll.
 */
class ScrollVelocityBarController {

    data class StepResult(
        val fraction: Float,
        val settledHidden: Boolean,
        val velocityDpPerSec: Float,
    )

    data class SettlePlan(
        val targetFraction: Float,
        val targetHidden: Boolean,
        val initialVelocityFractionPerSec: Float,
        val springStiffness: Float,
    )

    companion object {
        const val BASE_HIDE_TRAVEL_DP = 104f
        const val TOP_PIN_ZONE_DP = 24f
        const val REVEAL_MULTIPLIER = 1.25f

        private const val MIN_DT_MS = 4L
        private const val MAX_DT_MS = 120L
        private const val BURST_RESET_GAP_MS = 150L
        private const val ASSUMED_FIRST_FRAME_DT_SEC = 0.016f
        private const val VELOCITY_EMA_ALPHA = 0.35f

        private const val LOW_SPEED_DP_PER_SEC = 140f
        private const val MED_SPEED_DP_PER_SEC = 700f
        private const val HIGH_SPEED_DP_PER_SEC = 2_200f

        private const val LOW_SPEED_GAIN = 0.78f
        private const val MED_SPEED_GAIN = 1.15f
        private const val HIGH_SPEED_GAIN = 1.65f

        private const val SETTLE_HIDE_VELOCITY_DP_PER_SEC = 260f
        private const val SETTLE_SHOW_VELOCITY_DP_PER_SEC = -200f
        private const val SETTLE_HIDE_MIN_FRACTION = 0.22f
        private const val SETTLE_SHOW_MAX_FRACTION = 0.78f

        private const val ENDPOINT_HIDDEN_EPSILON = 0.995f
        private const val ENDPOINT_VISIBLE_EPSILON = 0.005f

        /**
         * Piecewise-linear velocity gain curve mapping scroll speed (`|dp/s|`) to hide sensitivity:
         * - `<= 140 dp/s` -> `0.78x` (calm slow-reading tracking without jitter)
         * - `140..700 dp/s` -> `0.78x..1.15x` (1:1 finger-coupled glide)
         * - `700..2200 dp/s` -> `1.15x..1.65x` (brisk flick acceleration)
         */
        fun speedGain(absVelocityDpPerSec: Float): Float {
            val v = absVelocityDpPerSec.coerceAtLeast(0f)
            return when {
                v <= LOW_SPEED_DP_PER_SEC -> LOW_SPEED_GAIN
                v <= MED_SPEED_DP_PER_SEC -> {
                    val t = (v - LOW_SPEED_DP_PER_SEC) / (MED_SPEED_DP_PER_SEC - LOW_SPEED_DP_PER_SEC)
                    LOW_SPEED_GAIN + (MED_SPEED_GAIN - LOW_SPEED_GAIN) * t
                }
                v <= HIGH_SPEED_DP_PER_SEC -> {
                    val t = (v - MED_SPEED_DP_PER_SEC) / (HIGH_SPEED_DP_PER_SEC - MED_SPEED_DP_PER_SEC)
                    MED_SPEED_GAIN + (HIGH_SPEED_GAIN - MED_SPEED_GAIN) * t
                }
                else -> HIGH_SPEED_GAIN
            }
        }
    }

    var currentFraction: Float = 0f
        private set

    var settledHidden: Boolean = false
        private set

    var smoothedVelocityDpPerSec: Float = 0f
        private set

    var lastScrollY: Int = 0
        private set

    private var lastEventTimeMs: Long = 0L

    fun onScrollDelta(
        scrollY: Int,
        deltaY: Int,
        timestampMs: Long,
        density: Float,
    ): StepResult {
        val safeDensity = density.coerceAtLeast(0.5f)
        lastScrollY = scrollY.coerceAtLeast(0)

        if (scrollY <= 0 && deltaY <= 0) {
            currentFraction = 0f
            settledHidden = false
            smoothedVelocityDpPerSec = 0f
            lastEventTimeMs = timestampMs
            return StepResult(
                fraction = 0f,
                settledHidden = false,
                velocityDpPerSec = 0f,
            )
        }

        if (deltaY == 0) {
            return StepResult(
                fraction = currentFraction,
                settledHidden = settledHidden,
                velocityDpPerSec = smoothedVelocityDpPerSec,
            )
        }

        val deltaDp = deltaY / safeDensity
        val scrollYDp = scrollY.coerceAtLeast(0) / safeDensity

        val rawGapMs = if (lastEventTimeMs > 0L && timestampMs >= lastEventTimeMs) {
            timestampMs - lastEventTimeMs
        } else {
            BURST_RESET_GAP_MS + 1L
        }
        val isNewBurst = lastEventTimeMs <= 0L || rawGapMs > BURST_RESET_GAP_MS
        val instantVelocityDpPerSec = if (isNewBurst) {
            (deltaDp / ASSUMED_FIRST_FRAME_DT_SEC).coerceIn(-600f, 600f)
        } else {
            val dtSec = rawGapMs.coerceIn(MIN_DT_MS, MAX_DT_MS) / 1000f
            (deltaDp / dtSec).coerceIn(-4_000f, 4_000f)
        }

        val reversedDirection =
            (deltaDp > 0f && smoothedVelocityDpPerSec < -10f) ||
                (deltaDp < 0f && smoothedVelocityDpPerSec > 10f)
        smoothedVelocityDpPerSec = if (isNewBurst || reversedDirection) {
            instantVelocityDpPerSec
        } else {
            smoothedVelocityDpPerSec +
                VELOCITY_EMA_ALPHA * (instantVelocityDpPerSec - smoothedVelocityDpPerSec)
        }
        lastEventTimeMs = timestampMs

        val gain = speedGain(abs(smoothedVelocityDpPerSec))
        val directionMultiplier = if (deltaDp < 0f) REVEAL_MULTIPLIER else 1f
        val topAnchorMultiplier = if (deltaDp > 0f && scrollYDp < TOP_PIN_ZONE_DP) 0.5f else 1f
        val deltaFraction =
            (deltaDp / BASE_HIDE_TRAVEL_DP) * gain * directionMultiplier * topAnchorMultiplier

        currentFraction = (currentFraction + deltaFraction).coerceIn(0f, 1f)
        if (currentFraction >= ENDPOINT_HIDDEN_EPSILON) {
            currentFraction = 1f
            settledHidden = true
        } else if (currentFraction <= ENDPOINT_VISIBLE_EPSILON) {
            currentFraction = 0f
            settledHidden = false
        }

        return StepResult(
            fraction = currentFraction,
            settledHidden = settledHidden,
            velocityDpPerSec = smoothedVelocityDpPerSec,
        )
    }

    /**
     * Computes a velocity-continuous snap plan once touch drag & inertial fling settle while the
     * bar is partially hidden (`0.005f < currentFraction < 0.995f`). Returns `null` if already
     * resting at an endpoint.
     */
    fun computeSettlePlan(scrollY: Int = lastScrollY, density: Float): SettlePlan? {
        if (currentFraction <= ENDPOINT_VISIBLE_EPSILON || currentFraction >= ENDPOINT_HIDDEN_EPSILON) {
            return null
        }
        val safeDensity = density.coerceAtLeast(0.5f)
        val scrollYDp = scrollY.coerceAtLeast(0) / safeDensity

        val hideTarget = when {
            scrollYDp <= TOP_PIN_ZONE_DP -> false
            smoothedVelocityDpPerSec >= SETTLE_HIDE_VELOCITY_DP_PER_SEC &&
                currentFraction >= SETTLE_HIDE_MIN_FRACTION -> true
            smoothedVelocityDpPerSec <= SETTLE_SHOW_VELOCITY_DP_PER_SEC &&
                currentFraction <= SETTLE_SHOW_MAX_FRACTION -> false
            else -> currentFraction >= 0.5f
        }

        val absVel = abs(smoothedVelocityDpPerSec)
        val gain = speedGain(absVel)
        val initialFractionVel = ((smoothedVelocityDpPerSec / BASE_HIDE_TRAVEL_DP) * gain)
            .coerceIn(-6f, 6f)
        // Faster scroll gestures settle crisply; gentle slow drags settle lazily without jerking
        val springStiffness = 360f + absVel.coerceAtMost(1_800f) * 0.35f

        return SettlePlan(
            targetFraction = if (hideTarget) 1f else 0f,
            targetHidden = hideTarget,
            initialVelocityFractionPerSec = initialFractionVel,
            springStiffness = springStiffness,
        )
    }

    /** Synchronizes internal state when an external animation or touch-down handoff overrides fraction. */
    fun syncFraction(fraction: Float, hidden: Boolean = settledHidden) {
        currentFraction = fraction.coerceIn(0f, 1f)
        settledHidden = when {
            currentFraction >= ENDPOINT_HIDDEN_EPSILON -> true
            currentFraction <= ENDPOINT_VISIBLE_EPSILON -> false
            else -> hidden
        }
    }

    fun reset() {
        currentFraction = 0f
        settledHidden = false
        smoothedVelocityDpPerSec = 0f
        lastScrollY = 0
        lastEventTimeMs = 0L
    }
}
