# 04 · Animations, Gestures & AGSL Shader Engineering Specification

Every animation in Focal is engineered for **locked `120Hz` (`8.33ms` frame budget)** on Android 13–16+ using **Jetpack Compose spring physics** (`Animatable` / `AnchoredDraggable` read purely in the `graphicsLayer` draw phase) and **AGSL (`Android Graphics Shading Language`) `RuntimeShader` effects** executing on `RenderThread`.

---

## 1. AGSL Shader #1: Hourglass Pinch-to-Summarize (`HourglassPinchShader`)

In `@__maximos`'s Lens v1.2 video (`00:06–00:10`), pinching two fingers inward does **not** merely scale the webpage rectangle uniformly—it warps the live page inward at the vertical waist (`y ≈ 0.5` or the midpoint between the user's two fingers) like a **concave optical lens**, with subtle radial RGB chromatic dispersion before cross-fading into the Summary card.

### Production AGSL Source (`Android 13+ / API 33+`)

```glsl
// core/designsystem/src/main/kotlin/app/focal/design/shaders/HourglassPinchShader.kt
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

    half r = content.eval((warpedUv + dispOffset) * resolution).r;
    half4 gba = content.eval(warpedUv * resolution);
    half b = content.eval((warpedUv - dispOffset) * resolution).b;

    // Subtle edge darkening along pinched waist for 3D depth cue
    half vignette = half(1.0 - strength * 0.28 * waistProfile * abs(centered.x) * 2.0);
    return half4(r * vignette, gba.g * vignette, b * vignette, gba.a);
}
```

### Zero-Recomposition Compose Attachment

```kotlin
val pinchShader = remember { RuntimeShader(HOURGLASS_PINCH_AGSL) }

Box(
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            val p = pinchProgressState.floatValue
            if (p > 0.005f) {
                pinchShader.setFloatUniform("resolution", size.width, size.height)
                pinchShader.setFloatUniform("pinchProgress", p)
                pinchShader.setFloatUniform("pinchCenterY", pinchCentroidYState.floatValue)
                val warpEffect = RenderEffect.createRuntimeShaderEffect(pinchShader, "content")
                val blurRadius = (p * 22.dp.toPx()).coerceAtLeast(0.5f)
                val blurEffect = RenderEffect.createBlurEffect(
                    blurRadius, blurRadius, Shader.TileMode.DECAL
                )
                renderEffect = RenderEffect
                    .createChainEffect(blurEffect, warpEffect)
                    .asComposeRenderEffect()
            } else {
                renderEffect = null
            }
        }
) {
    WebEngineSurface(session = activeSession)
}
```

---

## 2. AGSL Shader #2: Luminance-Adaptive Achromatic Smoked Glass (`SmokedGlassShader`)

Standard translucent black/white rectangles look muddy over high-saturation images and band visibly on 8-bit OLED panels. Focal chains a `28.dp` Gaussian blur into `SmokedGlassShader` to:
1. Dampen underlying background chroma (`saturation * 0.35`) so **achromatic glass stays pure** even over neon web banners (unless a contextual Space tint uniform is active).
2. Add a `1px` specular top-edge refraction highlight (`innerRim`).
3. Inject `±0.5/255` procedural triangular blue-noise dithering to eliminate OLED gradient banding.

```glsl
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
```

### Off-Thread Luminance Sampling (`AdaptiveGlassController`)
Every `250ms` while scrolling (and immediately on navigation commit), a background `HandlerThread` requests a tiny **`16×16` `Bitmap.Config.ARGB_8888`** downsample of the bottom `64dp` region via `PixelCopy.request(...)` (`~0.09ms`), computes BT.709 median luminance with **`0.08` hysteresis** (`dark -> light` at `0.52`, `light -> dark` at `0.44` to prevent flicker), and animates `glassBaseTint` smoothly over `180ms`.

---

## 3. Multi-Axis 3-Part Bottom Bar & Android Predictive Back Harmony

### Keeping Screen Edges 100% Clean for Android Predictive Back
Android 14–16's system-wide **Predictive Back** claims the left and right `24dp` system gesture insets (`WindowInsets.systemGestures`). Focal enforces two strict rules:
1. **Zero Edge-Swipe Interceptors**: Focal never attaches horizontal `pointerInput` drag detectors to the left/right edges of the viewport. Swiping inward from either screen edge drives `OnBackInvokedCallback` / Compose `PredictiveBackHandler`:
   - If `WebView.canGoBack()` -> previews previous page snapshot / shrinks active page slightly with system back curve.
   - If a floating sheet (`Search`, `Spaces`, `Summary`, `Menu`, `Link Peek`) is open -> shrinks and translates the glass sheet 1:1 with `BackEventCompat.progress` before dismissing!
2. **All Browser Spatial Gestures Live on the 3-Part Bottom Bar**:
   The center search capsule (`height = 48.dp`) uses directional vector locking within the first `10.dp` of touch slop:
   - **`|dx| == 0, |dy| == 0` (Tap)**: Triggers `SharedTransitionLayout` `sharedBounds` morph from bottom pill into the floating Search Sheet above the keyboard.
   - **`|dx| > |dy| * 1.35` (Horizontal Scrub)**: Locks into `TabScrubMode` — scales adjacent open tab surfaces to `0.93f` with `16.dp` gap and tracks finger `1:1` horizontally across tabs, firing `HapticFeedbackType.TextHandleMove` as each tab crosses center.
   - **`dy < -touchSlop && |dy| >= |dx|` (Vertical Swipe-Up)**: Locks into `SpacesSheetMode` — pulls the **Spaces & Bookmarks Sheet** (`Screen 05`) upward from below the bottom bar coupled `1:1` to finger velocity!

---

## 4. Master Motion & Spring Physics Table

| Interaction / Transition | Spring Spec (`Stiffness` / `Damping`) | Duration Equivalent & Haptics |
| :--- | :--- | :--- |
| **Bottom Pill <-> Search Sheet Morph** | `stiffness = 380f`, `dampingRatio = 0.82f` + `WindowInsetsAnimationCompat` IME curve | Tracks keyboard frame-for-frame; zero bounce overshoot |
| **Double-Space Site Pill (`im␣␣ -> [IMDb]`)** | `stiffness = 520f`, `dampingRatio = 0.76f` (`scaleX 0.75 -> 1.0` + radial bloom fade) | Crisp tactile snap + `CLOCK_TICK` micro-haptic |
| **Bottom-Bar Horizontal Tab Scrub** | Finger-coupled `1:1` during drag; release snap `stiffness = 420f`, `dampingRatio = 0.86f` | `TEXT_HANDLE_MOVE` tick on tab boundary crossing |
| **Bottom-Bar Swipe-Up -> Spaces Sheet** | `AnchoredDraggable` (`Hidden`, `Half 62%`, `Expanded 90%`): `stiffness = 390f`, `dampingRatio = 0.84f` | Velocity-aware fling handoff (`positionalThreshold = 35%`) |
| **Spaces Ambient Color Cross-Fade** | `tween(durationMillis = 260, FastOutSlowInEasing)` on `spaceAmbient` uniform | Pure GPU uniform interpolation (`0` recompositions) |
| **Pinch-to-Summarize Snap / Cancel** | Continuous `1:1` two-finger zoom ratio; release snap at `0.68f` via `stiffness = 340f`, `dampingRatio = 0.80f` | `CONFIRM` haptic tick exactly as `0.68f` threshold is crossed |
| **Long-Press Link Peek Reveal** | Origin-anchored `scale(0.84f -> 1.0f)` from anchor bounding rect: `stiffness = 460f`, `dampingRatio = 0.78f` | `LONG_PRESS` haptic + `18.dp` parent backdrop blur |
| **Smart Contextual Pill (`Aa Reader`) Appear** | `fadeIn(180ms) + scaleIn(initialScale = 0.88f, stiffness = 480f, damping = 0.82f)` | Collapses smoothly on `> 18.dp` downward page scroll |
