package app.focal.design.shaders

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focal.design.LocalFocalColors
import org.intellij.lang.annotations.Language

/**
 * Shared [GraphicsLayer] holding the live recorded viewport backdrop (`WebEngineSurface` or
 * `CalmOpticalLaunchCanvas`) so floating Smoked Glass chrome refracts real underlying content.
 */
val LocalFocalBackdropLayer = staticCompositionLocalOf<GraphicsLayer?> { null }

/**
 * Records a viewport surface into [backdropLayer] and draws it to screen in a single pass.
 */
@Composable
fun Modifier.recordFocalBackdrop(backdropLayer: GraphicsLayer): Modifier {
    return this.drawWithContent {
        backdropLayer.record {
            this@drawWithContent.drawContent()
        }
        drawLayer(backdropLayer)
    }
}

/**
 * Production AGSL Fragment Shader (`Android 13+ / API 33+`) for Focal's
 * Luminance-Adaptive Achromatic Smoked Glass.
 *
 * Chains a `28.dp` Gaussian blur into `SmokedGlassShader` to:
 * 1. Dampen underlying background chroma (`saturation * 0.32`) so achromatic glass stays pure
 *    over high-saturation web banners.
 * 2. Blend optional contextual Space radial top-origin tint (`spaceAmbient`).
 * 3. Add a `1px` specular top-edge refraction highlight (`topRim`).
 * 4. Inject `±0.5/255` procedural triangular blue-noise dithering to eliminate 8-bit OLED banding.
 */
@Language("AGSL")
const val SMOKED_GLASS_AGSL: String = """
uniform shader blurredBackdrop;
uniform float2 resolution;
uniform float4 glassBaseTint;   // Dark: (0.086, 0.090, 0.106, 0.82) | Light: (0.965, 0.968, 0.976, 0.85)
uniform float4 spaceAmbient;    // (0,0,0,0) normally; subtle rose/slate/sage in Spaces sheet

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    half4 bg = blurredBackdrop.eval(fragCoord);

    // Desaturate underlying web colors to preserve achromatic optical neutrality
    half luma = dot(bg.rgb, half3(0.2126, 0.7152, 0.0722));
    half3 neutralBg = mix(half3(luma), bg.rgb, half(0.32));

    // Blend base smoked/alabaster glass + optional top-origin Space radial tint
    float radial = 1.0 - smoothstep(0.0, 0.85, distance(uv, float2(0.5, 0.0)));
    half3 tintedGlass = mix(half3(glassBaseTint.rgb), half3(spaceAmbient.rgb), half(spaceAmbient.a * radial));
    half3 col = mix(neutralBg, tintedGlass, half(glassBaseTint.a));

    // Subtle specular top rim + micro-dither against 8-bit OLED banding
    float topRim = smoothstep(2.0 / resolution.y, 0.0, uv.y) * 0.07;
    float dither = (hash12(fragCoord) - 0.5) / 255.0;
    return half4(col + half(topRim + dither), 1.0);
}
"""

/**
 * Calm Optical Vignette AGSL Shader for Screen 01 Empty Launch Canvas.
 */
@Language("AGSL")
const val OPTICAL_VIGNETTE_AGSL: String = """
uniform float2 resolution;
uniform float4 baseColor;
uniform float4 bloomColor;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float dist = distance(uv, float2(0.5, 0.42));
    float radial = 1.0 - smoothstep(0.0, 0.78, dist);
    half3 col = mix(half3(baseColor.rgb), half3(bloomColor.rgb), half(radial * bloomColor.a));
    float dither = (hash12(fragCoord) - 0.5) / 255.0;
    return half4(col + half(dither), 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class SmokedGlassShaderHolder {
    val shader: RuntimeShader = RuntimeShader(SMOKED_GLASS_AGSL)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class OpticalVignetteShaderHolder {
    val shader: RuntimeShader = RuntimeShader(OPTICAL_VIGNETTE_AGSL)
    val brush: ShaderBrush = ShaderBrush(shader)
}

/**
 * Renders [OPTICAL_VIGNETTE_AGSL] on Android 13+ (`API 33+`) with blue-noise OLED dithering,
 * falling back cleanly to a radial gradient vignette on API 29–32.
 */
@Composable
fun Modifier.opticalVignetteBackground(
    baseColorState: State<Color>,
    bloomColorState: State<Color>,
): Modifier {
    val api33Holder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { OpticalVignetteShaderHolder() }
    } else {
        null
    }

    return this.drawBehind {
        val base = baseColorState.value
        val bloom = bloomColorState.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && api33Holder != null) {
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)
            val vignetteShader = api33Holder.shader
            vignetteShader.setFloatUniform("resolution", w, h)
            vignetteShader.setFloatUniform("baseColor", base.red, base.green, base.blue, base.alpha)
            vignetteShader.setFloatUniform("bloomColor", bloom.red, bloom.green, bloom.blue, bloom.alpha)
            drawRect(brush = api33Holder.brush)
        } else {
            drawRect(color = base)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(bloom, Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = (size.minDimension * 0.78f).coerceAtLeast(1f),
                ),
            )
        }
    }
}

/**
 * Applies the full Android 13+ AGSL `SmokedGlassShader` chained after a `28.dp` Gaussian blur
 * to a backdrop RenderNode, reading [glassBaseTintState] and [spaceAmbientState] purely inside
 * `Modifier.graphicsLayer {}` for zero Compose recomposition.
 *
 * Falls back cleanly on API 31–32 (`createBlurEffect`) and API 29–30 (dimmed hardware alpha).
 */
@Composable
fun Modifier.smokedGlassBackdropEffect(
    glassBaseTintState: State<Color>,
    spaceAmbientState: State<Color>,
    blurRadius: Dp = 28.dp,
    enabled: Boolean = true,
): Modifier {
    val api33Holder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { SmokedGlassShaderHolder() }
    } else {
        null
    }

    return this.graphicsLayer {
        if (!enabled) {
            renderEffect = null
            return@graphicsLayer
        }

        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val base = glassBaseTintState.value
        val ambient = spaceAmbientState.value
        val radiusPx = blurRadius.toPx().coerceAtLeast(1f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && api33Holder != null) {
            val glassShader = api33Holder.shader
            glassShader.setFloatUniform("resolution", w, h)
            glassShader.setFloatUniform("glassBaseTint", base.red, base.green, base.blue, base.alpha)
            glassShader.setFloatUniform("spaceAmbient", ambient.red, ambient.green, ambient.blue, ambient.alpha)

            val blurEffect = AndroidRenderEffect.createBlurEffect(
                radiusPx,
                radiusPx,
                Shader.TileMode.CLAMP,
            )
            val smokedEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                glassShader,
                "blurredBackdrop",
            )
            renderEffect = AndroidRenderEffect
                .createChainEffect(smokedEffect, blurEffect)
                .asComposeRenderEffect()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = AndroidRenderEffect
                .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        } else {
            renderEffect = null
        }
    }
}

private class BackdropCoordHolder {
    var rootX: Float = 0f
    var rootY: Float = 0f
}

/**
 * Floating Smoked Glass container (`FocalBottomBar` capsules, `MediaMiniPlayerDock`,
 * `SmartContextualPillsRow`, and modal Smoked Glass sheets) that samples the live
 * [LocalFocalBackdropLayer] through [smokedGlassBackdropEffect] (`28.dp` Gaussian blur +
 * `SMOKED_GLASS_AGSL` desaturation, Space radial ambient, specular top rim, and blue-noise dither)
 * on a dedicated background child `RenderNode` so foreground typography stays 100% razor-sharp.
 */
@Composable
fun SmokedGlassBox(
    shape: Shape,
    glassTintState: State<Color>,
    borderSpecular: Color,
    modifier: Modifier = Modifier,
    spaceAmbientState: State<Color>? = null,
    blurRadius: Dp = 28.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdropLayer = LocalFocalBackdropLayer.current
    val fallbackCanvasColor = LocalFocalColors.current.canvasBackground
    val emptyAmbient = remember { mutableStateOf(Color.Transparent) }
    val effectiveAmbient = spaceAmbientState ?: emptyAmbient
    val coordHolder = remember { BackdropCoordHolder() }

    val useLiveBackdrop = backdropLayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                coordHolder.rootX = pos.x
                coordHolder.rootY = pos.y
            }
            .let { baseMod ->
                if (useLiveBackdrop) {
                    baseMod
                        .clip(shape)
                        .border(width = 1.dp, color = borderSpecular, shape = shape)
                } else {
                    baseMod.smokedGlassSurface(
                        shape = shape,
                        glassTintState = glassTintState,
                        borderSpecular = borderSpecular,
                        spaceAmbientState = spaceAmbientState,
                    )
                }
            },
        contentAlignment = contentAlignment,
    ) {
        if (useLiveBackdrop && backdropLayer != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .smokedGlassBackdropEffect(
                        glassBaseTintState = glassTintState,
                        spaceAmbientState = effectiveAmbient,
                        blurRadius = blurRadius,
                    )
                    .drawBehind {
                        drawRect(color = fallbackCanvasColor)
                        translate(left = -coordHolder.rootX, top = -coordHolder.rootY) {
                            drawLayer(backdropLayer)
                        }
                    },
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawSmokedGlassTintOverlay(
                                baseTint = glassTintState.value,
                                ambient = spaceAmbientState?.value,
                            )
                        },
                )
            }
        }
        content()
    }
}

private fun DrawScope.drawSmokedGlassTintOverlay(
    baseTint: Color,
    ambient: Color?,
) {
    drawRect(color = baseTint)

    if (ambient != null && ambient.alpha > 0.005f) {
        val radius = (size.width.coerceAtLeast(size.height) * 0.85f).coerceAtLeast(1f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(ambient, Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = radius,
            ),
        )
    }

    // 1.5px top specular rim matching SmokedGlassShader topRim refraction
    val rimHeight = 2.dp.toPx().coerceAtMost(size.height)
    if (rimHeight > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                startY = 0f,
                endY = rimHeight,
            ),
        )
    }
}

/**
 * Draws a floating Smoked Glass container surface (bars, sheets, smart pills) reading animated
 * [glassTintState] and optional [spaceAmbientState] purely inside the draw phase (`drawBehind`),
 * paired with a `1.dp` specular hairline border and top-edge refraction highlight.
 */
@Composable
fun Modifier.smokedGlassSurface(
    shape: Shape,
    glassTintState: State<Color>,
    borderSpecular: Color,
    spaceAmbientState: State<Color>? = null,
): Modifier {
    return this
        .clip(shape)
        .drawBehind {
            drawSmokedGlassTintOverlay(
                baseTint = glassTintState.value,
                ambient = spaceAmbientState?.value,
            )
        }
        .border(width = 1.dp, color = borderSpecular, shape = shape)
}
