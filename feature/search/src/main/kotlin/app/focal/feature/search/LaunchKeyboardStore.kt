package app.focal.feature.search

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Pure geometry & state-transition rules for zero-jump IME height memory.
 *
 * Kept pure JVM so bounds validation, frame-0 elevation, live handoff, and hardware-keyboard
 * fallback are exhaustively unit-testable without an Android runtime.
 */
object LaunchKeyboardGeometry {
    const val MIN_KEYBOARD_DP = 120
    const val MAX_VIEWPORT_FRACTION = 0.65f
    const val HANDOFF_TOLERANCE_PX = 8
    const val HARDWARE_KEYBOARD_TIMEOUT_MS = 550L
    const val SHORT_IME_SETTLE_DEBOUNCE_MS = 140L
    const val LAUNCH_HOLD_TIMEOUT_MS = HARDWARE_KEYBOARD_TIMEOUT_MS
    const val STABILIZE_PERSIST_DEBOUNCE_MS = 180L

    /**
     * Validates that a measured IME inset (above the navigation bar) is a realistic docked keyboard
     * height rather than a floating toolbar, split-screen artifact, or bogus inset.
     */
    fun isValidImeHeightPx(
        heightPx: Int,
        viewportHeightPx: Int,
        minKeyboardPx: Int,
    ): Boolean {
        if (heightPx < minKeyboardPx || viewportHeightPx <= 0) return false
        val maxAllowedPx = (viewportHeightPx * MAX_VIEWPORT_FRACTION).toInt()
        return heightPx <= maxAllowedPx
    }

    /**
     * Computes whether the live IME has reached resting cached height so control can hand off
     * directly to live `WindowInsets.ime` tracking with **zero downward dip or bounce**.
     *
     * Handing off early (e.g., at 85% of cached height while the keyboard is still rising) would
     * drop the search bar downward and immediately bounce it back up as the keyboard finishes
     * opening. Requiring `liveImeAboveNavPx >= cachedHeightPx - HANDOFF_TOLERANCE_PX` guarantees
     * a completely monotonic, bounce-free handoff.
     */
    fun hasLiveImeCaughtUp(
        liveImeAboveNavPx: Int,
        cachedHeightPx: Int,
        minKeyboardPx: Int,
    ): Boolean {
        if (liveImeAboveNavPx < minKeyboardPx) return false
        if (cachedHeightPx <= 0) return true
        val catchUpThreshold = (cachedHeightPx - HANDOFF_TOLERANCE_PX).coerceAtLeast(minKeyboardPx)
        return liveImeAboveNavPx >= catchUpThreshold
    }

    /**
     * Returns true if the software keyboard had risen to realistic height and is now actively
     * retracting (e.g. user pressed Back mid-rise), so control should hand off immediately.
     */
    fun isKeyboardClosing(
        liveImeAboveNavPx: Int,
        peakLiveImePx: Int,
        minKeyboardPx: Int,
    ): Boolean {
        return peakLiveImePx >= minKeyboardPx &&
            liveImeAboveNavPx < (peakLiveImePx - HANDOFF_TOLERANCE_PX)
    }

    /**
     * Resolves how long the cached hold should wait while `liveImeAboveNavPx` remains unchanged:
     * - While still `0px`, waits [HARDWARE_KEYBOARD_TIMEOUT_MS] in case IME startup is slow or a
     *   hardware keyboard suppresses the soft IME.
     * - Once `liveImeAboveNavPx > 0`, every frame the keyboard rises cancels and restarts the wait;
     *   only if it stops moving short of cached height for [SHORT_IME_SETTLE_DEBOUNCE_MS] does the
     *   sheet smoothly glide down once to match the new shorter keyboard height.
     */
    fun resolveHoldTimeoutMs(liveImeAboveNavPx: Int): Long {
        return if (liveImeAboveNavPx > 0) SHORT_IME_SETTLE_DEBOUNCE_MS else HARDWARE_KEYBOARD_TIMEOUT_MS
    }

    /**
     * Resolves the bottom padding (above navigation bars) for `SearchSheet`:
     * - During the initial mount window (`holdCachedWindowActive && !imeHasStabilized`), pins the
     *   sheet at `maxOf(liveImeAboveNavPx, cachedHeightPx)` so frame 0 renders at exact resting
     *   keyboard height with zero upward jump and zero mid-rise downward dip.
     * - Once the live IME reaches resting height (or if the launch window expires when the IME
     *   settled shorter or a hardware keyboard suppressed it), tracks `liveImeAboveNavPx` 1:1.
     */
    fun resolveEffectiveBottomPaddingPx(
        liveImeAboveNavPx: Int,
        cachedHeightPx: Int,
        imeHasStabilized: Boolean,
        holdCachedWindowActive: Boolean,
    ): Int {
        val safeLive = liveImeAboveNavPx.coerceAtLeast(0)
        val safeCached = cachedHeightPx.coerceAtLeast(0)
        return if (!imeHasStabilized && holdCachedWindowActive && safeCached > 0) {
            maxOf(safeLive, safeCached)
        } else {
            safeLive
        }
    }
}

/**
 * Bounded, anonymous local UI memory persisting last-measured portrait & landscape IME heights
 * so `SearchSheet` mounts at exact resting keyboard height on frame 0 without layout jump.
 *
 * Stores strictly two integer pixel heights (`ime_height_portrait_px`, `ime_height_landscape_px`).
 * Zero browsing URLs, zero Private Aperture records, zero network calls.
 */
class LaunchKeyboardStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readHeightPx(isLandscape: Boolean): Int {
        val key = if (isLandscape) KEY_LANDSCAPE_PX else KEY_PORTRAIT_PX
        return prefs.getInt(key, 0).coerceAtLeast(0)
    }

    fun writeHeightPx(isLandscape: Boolean, heightPx: Int) {
        if (heightPx <= 0) return
        val key = if (isLandscape) KEY_LANDSCAPE_PX else KEY_PORTRAIT_PX
        if (prefs.getInt(key, 0) != heightPx) {
            prefs.edit().putInt(key, heightPx).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "focal_launch_keyboard"
        private const val KEY_PORTRAIT_PX = "ime_height_portrait_px"
        private const val KEY_LANDSCAPE_PX = "ime_height_landscape_px"
    }
}

/**
 * Zero-jump, zero-bounce IME bottom padding for `SearchSheet` (must be placed **after** `navigationBarsPadding()`).
 *
 * On frame 0, elevates the sheet directly to the last persisted IME height for the active screen
 * orientation while the software keyboard animates up behind it, then seamlessly hands off to
 * live `WindowInsets.ime` tracking once the keyboard reaches resting height — or settles smoothly
 * with critically-damped motion if the keyboard settled at a different height or a hardware keyboard
 * is active.
 */
fun Modifier.zeroJumpImePadding(): Modifier = composed {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val viewportHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val minKeyboardPx = with(density) { LaunchKeyboardGeometry.MIN_KEYBOARD_DP.dp.roundToPx() }

    val store = remember(context) { LaunchKeyboardStore(context) }
    val cachedHeightPx = remember(store, isLandscape, viewportHeightPx, minKeyboardPx) {
        val stored = store.readHeightPx(isLandscape)
        if (LaunchKeyboardGeometry.isValidImeHeightPx(stored, viewportHeightPx, minKeyboardPx)) {
            stored
        } else {
            0
        }
    }

    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val liveImeAboveNavPx = (imeBottomPx - navBottomPx).coerceAtLeast(0)

    var imeHasStabilized by remember(isLandscape) { mutableStateOf(false) }
    var holdCachedWindowActive by remember(isLandscape, cachedHeightPx) {
        mutableStateOf(cachedHeightPx > 0)
    }
    var smoothSettlingAfterHoldExpiry by remember(isLandscape, cachedHeightPx) {
        mutableStateOf(false)
    }
    var peakLiveImePx by remember(isLandscape, cachedHeightPx) { mutableIntStateOf(0) }

    if (liveImeAboveNavPx > peakLiveImePx) {
        peakLiveImePx = liveImeAboveNavPx
    }

    // Hand off seamlessly once the live IME reaches resting cached height (0px dip, 0px bounce)
    // or if the user actively dismisses the keyboard mid-rise.
    if (
        !imeHasStabilized && (
            LaunchKeyboardGeometry.hasLiveImeCaughtUp(liveImeAboveNavPx, cachedHeightPx, minKeyboardPx) ||
                LaunchKeyboardGeometry.isKeyboardClosing(liveImeAboveNavPx, peakLiveImePx, minKeyboardPx)
            )
    ) {
        imeHasStabilized = true
        holdCachedWindowActive = false
        smoothSettlingAfterHoldExpiry = false
    }

    // Activity-aware fallback release: keyed on `liveImeAboveNavPx` so an actively rising keyboard
    // NEVER times out mid-animation and dips downward. Releases only once movement stops short of
    // cached height (140ms) or no soft keyboard appears at all (550ms hardware keyboard fallback).
    LaunchedEffect(isLandscape, cachedHeightPx, liveImeAboveNavPx, imeHasStabilized, holdCachedWindowActive) {
        if (!imeHasStabilized && holdCachedWindowActive && cachedHeightPx > 0) {
            delay(LaunchKeyboardGeometry.resolveHoldTimeoutMs(liveImeAboveNavPx))
            if (!imeHasStabilized && holdCachedWindowActive) {
                holdCachedWindowActive = false
                smoothSettlingAfterHoldExpiry = true
            }
        }
    }

    // Persist stabilized soft keyboard height for future zero-jump launches
    LaunchedEffect(liveImeAboveNavPx, isLandscape, viewportHeightPx, minKeyboardPx) {
        if (LaunchKeyboardGeometry.isValidImeHeightPx(liveImeAboveNavPx, viewportHeightPx, minKeyboardPx)) {
            delay(LaunchKeyboardGeometry.STABILIZE_PERSIST_DEBOUNCE_MS)
            store.writeHeightPx(isLandscape = isLandscape, heightPx = liveImeAboveNavPx)
        }
    }

    val targetBottomPx = LaunchKeyboardGeometry.resolveEffectiveBottomPaddingPx(
        liveImeAboveNavPx = liveImeAboveNavPx,
        cachedHeightPx = cachedHeightPx,
        imeHasStabilized = imeHasStabilized,
        holdCachedWindowActive = holdCachedWindowActive,
    )

    val animatedSettleBottomPx by animateIntAsState(
        targetValue = targetBottomPx,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioNoBouncy,
        ),
        label = "FocalZeroJumpImeSettle",
    )

    // Once the post-hold smooth settle finishes, switch permanently to direct 1:1 live IME tracking
    if (smoothSettlingAfterHoldExpiry && kotlin.math.abs(animatedSettleBottomPx - targetBottomPx) <= 2) {
        smoothSettlingAfterHoldExpiry = false
        imeHasStabilized = true
    }

    val resolvedBottomPx = (if (smoothSettlingAfterHoldExpiry) animatedSettleBottomPx else targetBottomPx)
        .coerceAtLeast(0)

    this.padding(bottom = with(density) { resolvedBottomPx.toDp() })
}
