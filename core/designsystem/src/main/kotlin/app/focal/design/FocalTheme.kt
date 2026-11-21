package app.focal.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

val LocalFocalColors = staticCompositionLocalOf { FocalPalettes.DarkSmokedOled }
val LocalFocalTypography = staticCompositionLocalOf { DefaultFocalTypeScale }

/**
 * Consumes unconsumed tap-up pointer events inside modal Smoked Glass sheets without adding a
 * semantics-merging `clickable` wrapper, so taps on empty glass never leak through to the
 * underlying scrim dismiss handler while descendant buttons, text fields, and sheet drags keep
 * their own pointer handling and exact accessibility bounds.
 */
fun Modifier.consumeClicks(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        waitForUpOrCancellation()?.consume()
    }
}

/**
 * Critically-damped (`DampingRatioNoBouncy`) 120Hz optical motion physics.
 *
 * Every spring settles smoothly with zero overshoot or extra rebound bounce across
 * search docking, floating sheets, switches, contextual pills, and tab gestures.
 */
object FocalMotion {
    /** Bottom Pill <-> Search Sheet Morph (`stiffness = 520f`, critically damped) */
    fun <T> searchMorphSpring(): SpringSpec<T> =
        spring(stiffness = 520f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Double-Space Site Pill (`im␣␣ -> [IMDb]`: `stiffness = 560f`, critically damped) */
    fun <T> siteChipSpring(): SpringSpec<T> =
        spring(stiffness = 560f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Bottom-Bar Horizontal Tab Scrub & Swipe-Close cancel settle (`stiffness = 480f`, critically damped) */
    fun <T> tabScrubSnapSpring(): SpringSpec<T> =
        spring(stiffness = 480f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Floating Sheet Enter/Exit & Dismiss-Cancel settle (`stiffness = 460f`, critically damped) */
    fun <T> spacesSheetSpring(): SpringSpec<T> =
        spring(stiffness = 460f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Pinch-to-Summarize Snap / Cancel at `0.68f` (`stiffness = 400f`, critically damped) */
    fun <T> pinchSnapSpring(): SpringSpec<T> =
        spring(stiffness = 400f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Long-Press Link Peek Reveal & Dismiss-Cancel settle (`stiffness = 480f`, critically damped) */
    fun <T> linkPeekSpring(): SpringSpec<T> =
        spring(stiffness = 480f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Smart Contextual Pills, Switches, Dock Hide & Overlay Dim (`stiffness = 520f`, critically damped) */
    fun <T> smartPillSpring(): SpringSpec<T> =
        spring(stiffness = 520f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Shared-axis Menu <-> Site Overrides transition (`stiffness = 500f`, critically damped) */
    fun <T> menuSubtabSpring(): SpringSpec<T> =
        spring(stiffness = 500f, dampingRatio = Spring.DampingRatioNoBouncy)

    val ZeroVisibilityIntOffset = IntOffset(1, 1)
    val ZeroVisibilityOffset = Offset(0.5f, 0.5f)
}

/**
 * Critically-damped GPU-only vertical rise (`0` layout passes, `0` overshoot) applied directly
 * to floating Smoked Glass sheet cards while their parent backdrop scrim crossfades edge-to-edge.
 */
@Composable
fun Modifier.focalSheetEntrance(
    riseDp: Dp = 18.dp,
    key: Any = Unit,
): Modifier {
    val riseFraction = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        riseFraction.animateTo(
            targetValue = 0f,
            animationSpec = FocalMotion.spacesSheetSpring(),
        )
    }
    return this.graphicsLayer {
        translationY = riseFraction.value * riseDp.toPx()
    }
}

object FocalTheme {
    val colors: FocalColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalFocalColors.current

    val typography: FocalTypeScale
        @Composable
        @ReadOnlyComposable
        get() = LocalFocalTypography.current
}

private val AchromaticDarkMaterialScheme = darkColorScheme(
    primary = FocalPalettes.DarkSmokedOled.surfacePillActive,
    onPrimary = FocalPalettes.DarkSmokedOled.inkOnPillActive,
    secondary = FocalPalettes.DarkSmokedOled.inkSecondary,
    onSecondary = FocalPalettes.DarkSmokedOled.canvasBackground,
    background = FocalPalettes.DarkSmokedOled.canvasBackground,
    onBackground = FocalPalettes.DarkSmokedOled.inkPrimary,
    surface = FocalPalettes.DarkSmokedOled.surfaceGlassSheet,
    onSurface = FocalPalettes.DarkSmokedOled.inkPrimary,
    outline = FocalPalettes.DarkSmokedOled.borderSpecular,
)

private val AchromaticLightMaterialScheme = lightColorScheme(
    primary = FocalPalettes.LightAlabaster.surfacePillActive,
    onPrimary = FocalPalettes.LightAlabaster.inkOnPillActive,
    secondary = FocalPalettes.LightAlabaster.inkSecondary,
    onSecondary = FocalPalettes.LightAlabaster.canvasBackground,
    background = FocalPalettes.LightAlabaster.canvasBackground,
    onBackground = FocalPalettes.LightAlabaster.inkPrimary,
    surface = FocalPalettes.LightAlabaster.surfaceGlassSheet,
    onSurface = FocalPalettes.LightAlabaster.inkPrimary,
    outline = FocalPalettes.LightAlabaster.borderSpecular,
)

@Composable
fun FocalTheme(
    darkGlass: Boolean = true,
    isPrivateAperture: Boolean = false,
    content: @Composable () -> Unit,
) {
    val targetTokens = when {
        isPrivateAperture -> FocalPalettes.PrivateObsidian
        darkGlass -> FocalPalettes.DarkSmokedOled
        else -> FocalPalettes.LightAlabaster
    }

    // Smooth 180ms glass luminance transition matching AdaptiveGlassController spec
    val canvasBackground by animateColorAsState(
        targetValue = targetTokens.canvasBackground,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "FocalCanvasBackground",
    )
    val glassBar by animateColorAsState(
        targetValue = targetTokens.surfaceGlassBar,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "FocalGlassBarTint",
    )
    val glassSheet by animateColorAsState(
        targetValue = targetTokens.surfaceGlassSheet,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "FocalGlassSheetTint",
    )
    val inkPrimary by animateColorAsState(
        targetValue = targetTokens.inkPrimary,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "FocalInkPrimary",
    )

    val animatedTokens = targetTokens.copy(
        canvasBackground = canvasBackground,
        surfaceGlassBar = glassBar,
        surfaceGlassSheet = glassSheet,
        inkPrimary = inkPrimary,
    )

    val materialScheme = if (darkGlass || isPrivateAperture) {
        AchromaticDarkMaterialScheme
    } else {
        AchromaticLightMaterialScheme
    }

    CompositionLocalProvider(
        LocalFocalColors provides animatedTokens,
        LocalFocalTypography provides DefaultFocalTypeScale,
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = FocalMaterialTypography,
            content = content,
        )
    }
}
