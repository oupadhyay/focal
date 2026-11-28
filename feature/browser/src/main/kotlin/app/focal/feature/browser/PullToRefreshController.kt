package app.focal.feature.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.focal.design.FocalGlyphs
import app.focal.design.FocalTheme
import app.focal.design.shaders.SmokedGlassBox

/**
 * Pure rubber-band pull-to-refresh physics curve for Focal's Smoked Glass optical refresh puck.
 */
object PullToRefreshPhysics {
    const val TRIGGER_RAW_PULL_DP: Float = 104f
    const val MAX_OVERSHOOT_PROGRESS: Float = 1.35f
    const val REFRESH_HOLD_PROGRESS: Float = 0.56f

    /**
     * Maps raw downward finger travel in `dp` into normalized progress:
     * - `0..104 dp` -> `0f..1f` (smooth direct response)
     * - `> 104 dp` -> asymptotic rubber-band overshoot capped at `1.35f`
     */
    fun progressForRawPullDp(rawPullDp: Float): Float {
        if (rawPullDp <= 0f) return 0f
        if (rawPullDp <= TRIGGER_RAW_PULL_DP) {
            return (rawPullDp / TRIGGER_RAW_PULL_DP).coerceIn(0f, 1f)
        }
        val overshootDp = rawPullDp - TRIGGER_RAW_PULL_DP
        val rubberOvershoot = 0.35f * (1f - 1f / (1f + overshootDp / 110f))
        return (1f + rubberOvershoot).coerceAtMost(MAX_OVERSHOOT_PROGRESS)
    }

    /** Visual downward offset below status bar (`0..72 dp`). */
    fun puckOffsetDp(progress: Float): Float {
        val p = progress.coerceIn(0f, MAX_OVERSHOOT_PROGRESS)
        return if (p <= 1f) {
            p * 56f
        } else {
            56f + ((p - 1f) / 0.35f) * 16f
        }
    }

    /** Rotation of the reload glyph in degrees (`0°..330°`). */
    fun iconRotationDeg(progress: Float): Float {
        val p = progress.coerceIn(0f, MAX_OVERSHOOT_PROGRESS)
        return if (p <= 1f) {
            p * 270f
        } else {
            270f + ((p - 1f) / 0.35f) * 60f
        }
    }

    fun shouldTriggerRefresh(rawPullDp: Float): Boolean =
        progressForRawPullDp(rawPullDp) >= 1f
}

/**
 * Optical Smoked Glass pull-to-refresh puck emerging cleanly beneath the status bar.
 * Reads [pullProgressState] strictly inside `Modifier.graphicsLayer` for 120Hz zero-recomposition drag.
 */
@Composable
fun FocalPullToRefreshPuck(
    pullProgressState: FloatState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val isVisible by remember(pullProgressState, isRefreshing) {
        derivedStateOf { isRefreshing || pullProgressState.floatValue > 0.01f }
    }
    if (!isVisible) return

    val colors = FocalTheme.colors
    val isArmed by remember(pullProgressState, isRefreshing) {
        derivedStateOf { !isRefreshing && pullProgressState.floatValue >= 1f }
    }

    val spinAngleState = if (isRefreshing) {
        val infinite = rememberInfiniteTransition(label = "FocalPullRefreshSpin")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 760, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "FocalPullRefreshSpinAngle",
        )
    } else {
        null
    }

    val containerBg by animateColorAsState(
        targetValue = if (isArmed) colors.surfacePillActive else Color.Transparent,
        animationSpec = tween(durationMillis = 120),
        label = "FocalPullRefreshBg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isArmed) colors.inkOnPillActive else colors.inkPrimary,
        animationSpec = tween(durationMillis = 120),
        label = "FocalPullRefreshTint",
    )
    val glassTint = rememberUpdatedState(colors.effectiveBarGlass)

    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = 6.dp)
            .graphicsLayer {
                val p = pullProgressState.floatValue.coerceIn(0f, PullToRefreshPhysics.MAX_OVERSHOOT_PROGRESS)
                val offsetDp = if (isRefreshing) {
                    maxOf(16f, PullToRefreshPhysics.puckOffsetDp(p))
                } else {
                    PullToRefreshPhysics.puckOffsetDp(p)
                }
                val entryShiftPx = -32.dp.toPx() * (1f - p.coerceIn(0f, 1f))
                translationY = offsetDp.dp.toPx() + entryShiftPx
                val scale = when {
                    isRefreshing -> 0.96f
                    p >= 1f -> 1.08f
                    else -> 0.68f + (p * 0.32f)
                }
                scaleX = scale
                scaleY = scale
                alpha = if (isRefreshing) 1f else (p * 1.45f).coerceIn(0f, 1f)
            }
            .testTag("pull_to_refresh_puck"),
        contentAlignment = Alignment.Center,
    ) {
        SmokedGlassBox(
            shape = CircleShape,
            glassTintState = glassTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .size(42.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                ),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(containerBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Reload,
                    contentDescription = if (isRefreshing) "Refreshing page" else "Pull to refresh",
                    tint = iconTint,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            rotationZ = spinAngleState?.value
                                ?: PullToRefreshPhysics.iconRotationDeg(pullProgressState.floatValue)
                        },
                )
            }
        }
    }
}
