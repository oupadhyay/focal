# Credits, Inspirations & Third-Party Notices

Focal stands on the shoulders of independent browser makers, type designers, and open-source maintainers.

---

## Design & Product Inspirations

- **[Lens Browser](https://apps.apple.com/app/lens-simple-private-browser/id6740821242) (`@__maximos`)** — Inspired Focal's **Achromatic Optical Smoked Glass** visual materiality, search-first launch ergonomics, Double-Space site chips (`im␣␣`), and calm bottom-anchored chrome.
- **[YAB — Yet Another Browser](https://github.com/yetanotherbrowser/yab) (Apache-2.0)** — Inspired Focal's continuous-focus physics tab gallery deck, Spaces organization with ambient per-space washes, and tactile gesture ergonomics.
- **[Brave (`adblock-rust`)](https://github.com/brave/adblock-rust) (MPL-2.0)** — Powers Focal's native Rust network ad/tracker blocker and cosmetic CSS selector engine over JNI.
- **DuckDuckGo, Cromite, Firefox, Arc Search, Quiche & Vivaldi** — Inspired Focal's privacy-by-default defaults (Global Privacy Control, URL tracker stripping, zero-telemetry architecture) and one-handed mobile workflows.

---

## Typography & Bundled Font Licenses

Focal bundles two variable typefaces inside [`core/designsystem/src/main/res/font/`](core/designsystem/src/main/res/font/). Their full license texts are included in [`design/fonts/`](design/fonts/):

| Typeface | Designer / Foundry | License | License File |
| :--- | :--- | :--- | :--- |
| **Allo Variable** | **Josh Puckett** (built on **Inter** by Rasmus Andersson / The Inter Project Authors) | **SIL Open Font License 1.1 (OFL-1.1)** | [`design/fonts/ALLO_LICENSE_OFL.txt`](design/fonts/ALLO_LICENSE_OFL.txt) |
| **Switzer Variable** | **Indian Type Foundry (ITF)** via Fontshare | **ITF Free Font License (FFL)** | [`design/fonts/SWITZER_LICENSE.txt`](design/fonts/SWITZER_LICENSE.txt) |

> **Note for forks & redistributors**: The **Switzer Variable** typeface is licensed under the ITF Free Font License (free for personal, commercial, and bundled app use; redistribution as a standalone font package for sale is prohibited). **Allo Variable** is licensed under SIL OFL 1.1.

---

## Third-Party Open-Source Libraries

### Android / Kotlin (`gradle/libs.versions.toml`)
| Library | Purpose | License |
| :--- | :--- | :--- |
| **AndroidX Jetpack** (`Core KTX`, `Activity Compose`, `Lifecycle`, `Compose UI / Foundation / Animation / Material3`, `WebKit`, `Room`, `WorkManager`, `Media3`, `ProfileInstaller`) | Native Android UI, WebView APIs, persistence, background scheduling, media controls, and ART Baseline Profiles | Apache-2.0 |
| **Kotlin & Kotlinx** (`Coroutines`, `Collections Immutable`, `Serialization JSON`) | Language runtime, structured concurrency, immutable UI collections, JSON serialization | Apache-2.0 |
| **OkHttp** (Square) | WebSocket transport for opt-in E2EE `Focal Bridge` continuity | Apache-2.0 |
| **Google ML Kit** (`language-id`, `translate`) | Optional on-device language detection & offline in-place page translation | Google APIs Terms of Service |
| **JUnit 4** & **Robolectric** | JVM unit testing and Skia-rendered Compose UI verification | EPL-1.0 / MIT |

### Rust Native Adblock Engine (`core/adblock-rust/rust/Cargo.toml`)
| Crate | Purpose | License |
| :--- | :--- | :--- |
| **`adblock`** (Brave Software) | Native EasyList/EasyPrivacy network blocking & cosmetic filtering | MPL-2.0 |
| **`jni`** | Safe Rust ↔ Android JNI bindings | MIT / Apache-2.0 |
| **`once_cell`** | Lazy static initialization | MIT / Apache-2.0 |
| **`url`** (Servo) | RFC 3986 URL parsing & tracker query parameter canonicalization | MIT / Apache-2.0 |

### Desktop Extension & E2EE Relay (`desktop-extension/`, `sync-relay/`)
| Package | Purpose | License |
| :--- | :--- | :--- |
| **TypeScript** (Microsoft) | Build-time type checking & compilation | Apache-2.0 |
| **`@types/chrome`** / **`@cloudflare/workers-types`** | MV3 WebExtension & Cloudflare Durable Object type definitions | MIT / Apache-2.0 |
