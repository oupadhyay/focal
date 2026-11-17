# Focal Browser

**Focal** is a native Android (`Kotlin` + `Jetpack Compose` + `AGSL` + `Rust`) mobile browser engineered to feel calmer, cleaner, and measurably smoother than Chrome at `120Hz`—combining **optical tactile minimalism**, **built-in `adblock-rust` ad & tracker blocking**, **100% on-device grounded AI summarization**, and **zero-account E2EE desktop continuity (`Focal Bridge`)**.

---

## Locked v1 Product & Design Decisions

- **Platform & Engine**: Native Android (`Kotlin 2.x` + `Jetpack Compose` + `AGSL RuntimeShaders`) running over a clean `BrowserEngine` abstraction (`androidx.webkit` Zygote-prewarmed System WebView in v1 for `< 220ms` cold start & `~20 MB` APK, with a modular seam for `Mozilla GeckoView`).
- **Built-In Adblocking**: Brave's `adblock-rust` compiled via `cargo-ndk` over zero-allocation JNI (`~0.18ms` per subresource) + `DocumentStartJavaScript` pre-paint cosmetic hiding + `"Where Does This Go"` tracker-parameter stripping.
- **Visual Materiality & Color**: **Lens-style Achromatic Optical Smoked Glass** — luminance-adaptive dark obsidian and light alabaster frosted glass with **zero brand chrome color**; chromatic color blooms exclusively from contextual content (Double-Space site chips like `IMDb`, or ambient Space backdrops like `Personal` / `Work` / `Reading`).
- **Typography**:
  - **Headings & Focus Text**: [`Allo Variable`](design/fonts/allo_variable.ttf) (`'ROND' 0` crisp neo-grotesk, `'opsz' 14..32`, `'tnum'`, `'ss01'`, `'zero'` — SIL OFL 1.1 by Josh Puckett).
  - **Body Copy**: [`Switzer Variable`](design/fonts/switzer_variable.ttf) (`wght 400..500` — ITF Free Commercial License).
- **Navigation & Android Gesture Harmony**:
  - **3-part floating bottom bar**: `[Tabs]` · `[Search / Domain Pill]` · `[··· Menu]`.
  - **Tap center pill** -> Search-first sheet (`im␣␣` Double-Space site pills + Private Aperture `◎`).
  - **Horizontal scrub on bar** -> Instant 120Hz tab switching.
  - **Vertical swipe-up on bar** -> Spaces & Bookmarks sheet.
  - **Left/Right `24dp` screen edges** -> 100% clear for Android 16 native **Predictive Back**.
- **100% On-Device Pinch-to-Summarize**: Two-finger inward pinch warps the webpage via an **AGSL Hourglass Pinch Shader** before dissolving into a locally generated summary (`Android AICore Gemini Nano` or opt-in local `LiteRT-LM Gemma 3 1B int4`), with tap-to-highlight DOM source grounding. Zero page text ever leaves the phone.
- **Zero-Account Cross-Device Continuity (`Focal Bridge`)**: 256-bit CSPRNG QR pairing (`focal-pair://v1?secret=...`) with explicit 4-part SAS verification + `HKDF-SHA256` key derivation + `AES-256-GCM` authenticated envelopes (`AAD = roomId`, per-sender monotonic anti-replay) over a stateless TLS WebSocket relay + lightweight **MV3 Desktop Browser Extension** (`Chrome`, `Helium`, `Brave`, `Zen`, `Firefox` on macOS, Windows, Linux).

---

## Repository Blueprint

| Path | Contents |
| :--- | :--- |
| [`docs/01-lens-and-mobile-browser-teardown.md`](docs/01-lens-and-mobile-browser-teardown.md) | Frame-by-frame teardown of **Lens Browser v1.2** (`@__maximos`) + comparative matrix against **Arc Search**, **Quiche**, **Crest**, **YAB**, **Brim**, **Wider**, and **Brave/Cromite**. |
| [`docs/02-design-system-and-screens.md`](docs/02-design-system-and-screens.md) | Locked **Achromatic Optical Smoked Glass** tokens, Jetpack Compose `Allo Crisp` + `Switzer` typography definitions, and UX specs for all **9 v1 screens**. |
| [`docs/03-android-120hz-tech-stack.md`](docs/03-android-120hz-tech-stack.md) | Multi-module Gradle layout, `BrowserEngine` API, `adblock-rust` JNI pipeline, local `AICore` / `LiteRT-LM` grounded summarization, `MediaSession` mini-player, and `HKDF-SHA256` + `AES-256-GCM` E2EE continuity protocol. |
| [`docs/04-animations-gestures-and-agsl-shaders.md`](docs/04-animations-gestures-and-agsl-shaders.md) | Production **AGSL RuntimeShader** code (`HourglassPinchShader`, `SmokedGlassShader`), multi-axis bottom-bar gesture disambiguation, Predictive Back integration, and `120Hz` spring physics table. |
| [`design/prototype/index.html`](design/prototype/index.html) | Self-contained interactive **9-Screen Focal Prototype & Motion Studio** with embedded `Allo Crisp` + `Switzer` `.woff2` fonts and live Hourglass Pinch displacement animation. |
| [`design/fonts/`](design/fonts/) | Android-ready variable font binaries (`allo_variable.ttf` + `switzer_variable.ttf`) and license attributions ready for `app/src/main/res/font/`. |
