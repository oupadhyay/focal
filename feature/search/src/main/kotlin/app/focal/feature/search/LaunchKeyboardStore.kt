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
    const val HANDOFF_RATIO = 0.85f
    const val LAUNCH_HOLD_TIMEOUT_MS = 480L
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
     * Computes whether the live IME has caught up closely enough to hand off control directly
     * to live `WindowInsets.ime` tracking (so subsequent IME dismissal lowers the sheet 1:1).
     */
    fun hasLiveImeCaughtUp(
        liveImeAboveNavPx: Int,
        cachedHeightPx: Int,
        minKeyboardPx: Int,
    ): Boolean {
        if (liveImeAboveNavPx < minKeyboardPx) return false
        if (cachedHeightPx <= 0) return true
        return liveImeAboveNavPx >= (cachedHeightPx * HANDOFF_RATIO).toInt()
    }

    /**
     * Resolves the bottom padding (above navigation bars) for `SearchSheet`:
     * - During the initial mount window (`holdCachedWindowActive && !imeHasStabilized`), pins the
     *   sheet at `maxOf(liveImeAboveNavPx, cachedHeightPx)` so frame 0 renders at exact resting
     *   keyboard height with zero upward jump.
     * - Once the live IME catches up (or if the launch window expires with a hardware keyboard),
     *   tracks `liveImeAboveNavPx` 1:1 so dismissing the IME smoothly lowers the sheet to `0`.
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
 * Zero-jump IME bottom padding for `SearchSheet` (must be placed **after** `navigationBarsPadding()`).
 *
 * On frame 0, elevates the sheet directly to the last persisted IME height for the active screen
 * orientation while the software keyboard animates up behind it, then seamlessly hands off to
 * live `WindowInsets.ime` tracking once the keyboard reaches resting height (or glides down cleanly
 * if a hardware keyboard suppresses the soft IME).
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

    // Hand off seamlessly once the live IME reaches resting height
    if (!imeHasStabilized && LaunchKeyboardGeometry.hasLiveImeCaughtUp(liveImeAboveNavPx, cachedHeightPx, minKeyboardPx)) {
        imeHasStabilized = true
        holdCachedWindowActive = false
    }

    // Fallback release if no soft IME opens (e.g., hardware keyboard connected)
    LaunchedEffect(isLandscape, cachedHeightPx) {
        if (cachedHeightPx > 0) {
            delay(LaunchKeyboardGeometry.LAUNCH_HOLD_TIMEOUT_MS)
            holdCachedWindowActive = false
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

    val animatedFallbackBottomPx by animateIntAsState(
        targetValue = targetBottomPx,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy,
        ),
        label = "FocalZeroJumpImeFallback",
    )

    // Use direct 1:1 tracking whenever live IME is moving or frame-0 cached height is held;
    // only spring-settle smoothly if the cached hold window expires with liveIme == 0.
    val expiredWithoutSoftIme = !imeHasStabilized && !holdCachedWindowActive && cachedHeightPx > 0 && liveImeAboveNavPx == 0
    val resolvedBottomPx = (if (expiredWithoutSoftIme) animatedFallbackBottomPx else targetBottomPx)
        .coerceAtLeast(0)

    this.padding(bottom = with(density) { resolvedBottomPx.toDp() })
}
