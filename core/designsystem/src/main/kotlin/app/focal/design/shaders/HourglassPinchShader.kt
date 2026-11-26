package app.focal.design.shaders

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.intellij.lang.annotations.Language

/**
 * Production AGSL Fragment Shader (`Android 13+ / API 33+`) for Focal's two-finger
 * inward Pinch-to-Summarize transition.
 *
 * Warps the live webpage inward at the vertical waist (`pinchCenterY`) like a concave
 * optical lens with subtle radial RGB chromatic dispersion before cross-dissolving
 * into the Smoked Glass Summary Sheet.
 */
@Language("AGSL")
const val HOURGLASS_PINCH_AGSL: String = """
uniform shader content;
uniform float2 resolution;
uniform float pinchProgress;   // 0.0 (flat page) -> 1.0 (fully pinched)
uniform float pinchCenterY;    // Normalized Y centroid of 2-finger gesture (default 0.5)

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float2 centered = uv - float2(0.5, pinchCenterY);

    // Gaussian waist curve: peaks strongly at y == pinchCenterY, tapers toward top & bottom edges
    float waistProfile = exp(-(centered.y * centered.y) / 0.085);

    // Envelope peaks around pinchProgress = 0.55 while cross-dissolving to Summary sheet
    float strength = pinchProgress * (1.0 - 0.35 * pinchProgress);

    // Concave horizontal waist pull: sampling further outward pulls visible content inward!
    float horizFactor = 1.0 + strength * 0.62 * waistProfile;
    float vertFactor  = 1.0 + strength * 0.14 * waistProfile;

    float2 warpedUv = float2(
        0.5 + centered.x * horizFactor,
        pinchCenterY + centered.y * vertFactor
    );

    // Soft out-of-bounds vignette mask when edges pull inward past [0..1]
    if (warpedUv.x < 0.0 || warpedUv.x > 1.0 || warpedUv.y < 0.0 || warpedUv.y > 1.0) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }

    // Subtle optical lens RGB axial dispersion near pinched waist edges
    float dispersion = strength * 0.006 * abs(centered.x) * waistProfile;
    float2 dispOffset = float2(dispersion, 0.0);
    float2 rUv = clamp(warpedUv + dispOffset, float2(0.0), float2(1.0));
    float2 bUv = clamp(warpedUv - dispOffset, float2(0.0), float2(1.0));

    half4 rSample = content.eval(rUv * resolution);
    half4 gba = content.eval(warpedUv * resolution);
    half4 bSample = content.eval(bUv * resolution);

    // Subtle edge darkening along pinched waist for 3D depth cue
    half vignette = half(1.0 - strength * 0.28 * waistProfile * abs(centered.x) * 2.0);
    return half4(rSample.r * vignette, gba.g * vignette, bSample.b * vignette, gba.a);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class HourglassShaderHolder {
    private val shader: RuntimeShader = RuntimeShader(HOURGLASS_PINCH_AGSL)
    private var cachedWidth: Float = -1f
    private var cachedHeight: Float = -1f
    private var cachedProgress: Float = -1f
    private var cachedCenterY: Float = -1f
    private var cachedDensity: Float = -1f
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun obtainRenderEffect(
        width: Float,
        height: Float,
        progress: Float,
        centerY: Float,
        density: Float,
        blurRadiusPx: Float,
    ): androidx.compose.ui.graphics.RenderEffect {
        val current = cachedEffect
        if (
            current != null &&
            cachedDensity == density &&
            kotlin.math.abs(cachedWidth - width) < CACHE_EPSILON &&
            kotlin.math.abs(cachedHeight - height) < CACHE_EPSILON &&
            kotlin.math.abs(cachedProgress - progress) < CACHE_EPSILON &&
            kotlin.math.abs(cachedCenterY - centerY) < CACHE_EPSILON
        ) {
            return current
        }

        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("pinchProgress", progress)
        shader.setFloatUniform("pinchCenterY", centerY)

        val warpEffect = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
        val blurEffect = AndroidRenderEffect.createBlurEffect(
            blurRadiusPx,
            blurRadiusPx,
            Shader.TileMode.DECAL,
        )
        val next = AndroidRenderEffect
            .createChainEffect(blurEffect, warpEffect)
            .asComposeRenderEffect()

        cachedWidth = width
        cachedHeight = height
        cachedProgress = progress
        cachedCenterY = centerY
        cachedDensity = density
        cachedEffect = next
        return next
    }

    private companion object {
        const val CACHE_EPSILON = 0.001f
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class HourglassApi31BlurHolder {
    private var cachedProgress: Float = -1f
    private var cachedDensity: Float = -1f
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun obtainBlurEffect(
        progress: Float,
        density: Float,
        blurRadiusPx: Float,
    ): androidx.compose.ui.graphics.RenderEffect {
        val current = cachedEffect
        if (
            current != null &&
            cachedDensity == density &&
            kotlin.math.abs(cachedProgress - progress) < 0.001f
        ) {
            return current
        }
        val next = AndroidRenderEffect
            .createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.DECAL)
            .asComposeRenderEffect()
        cachedProgress = progress
        cachedDensity = density
        cachedEffect = next
        return next
    }
}

/**
 * Attaches Focal's Hourglass Pinch-to-Summarize optical warp to a viewport surface.
 *
 * Strictly reads [pinchProgressState] and [pinchCenterYState] inside `Modifier.graphicsLayer {}`
 * so 120Hz two-finger pinch gestures execute entirely on `RenderThread` with **zero Compose recompositions**.
 *
 * Includes safe pre-API-33 hardware-layer scale + progressive blur fallbacks.
 */
@Composable
fun Modifier.hourglassPinchEffect(
    pinchProgressState: FloatState,
    pinchCenterYState: FloatState,
): Modifier {
    val api33Holder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { runCatching { HourglassShaderHolder() }.getOrNull() }
    } else {
        null
    }
    val api31BlurHolder = if (api33Holder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        remember { HourglassApi31BlurHolder() }
    } else {
        null
    }

    return this.graphicsLayer {
        val p = pinchProgressState.floatValue.coerceIn(0f, 1f)
        if (p <= 0.005f) {
            renderEffect = null
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            return@graphicsLayer
        }

        val cy = pinchCenterYState.floatValue.coerceIn(0.15f, 0.85f)
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && api33Holder != null) {
            val blurRadius = (p * 22.dp.toPx()).coerceAtLeast(0.5f)
            renderEffect = api33Holder.obtainRenderEffect(
                width = w,
                height = h,
                progress = p,
                centerY = cy,
                density = density,
                blurRadiusPx = blurRadius,
            )
            scaleX = 1f
            scaleY = 1f
            alpha = (1f - p * 0.18f).coerceIn(0.6f, 1f)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && api31BlurHolder != null) {
            // API 31–32 fallback: hardware RenderEffect blur + anisotropic inward waist squeeze
            val strength = p * (1f - 0.35f * p)
            val blurRadius = (p * 20.dp.toPx()).coerceAtLeast(0.5f)
            renderEffect = api31BlurHolder.obtainBlurEffect(
                progress = p,
                density = density,
                blurRadiusPx = blurRadius,
            )
            scaleX = 1f - strength * 0.16f
            scaleY = 1f - strength * 0.06f
            alpha = (1f - p * 0.24f).coerceIn(0.55f, 1f)
        } else {
            // API 29–30 fallback: pure RenderNode transform without GPU shader compilation
            val strength = p * (1f - 0.35f * p)
            scaleX = 1f - strength * 0.18f
            scaleY = 1f - strength * 0.08f
            alpha = (1f - p * 0.32f).coerceIn(0.5f, 1f)
        }
    }
}
