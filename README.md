# Focal Browser

**Focal** is a fast, privacy-first native Android browser built with **Kotlin**, **Jetpack Compose**, **AGSL RuntimeShaders**, and **Rust** (`adblock-rust` over JNI) — designed around **Achromatic Optical Smoked Glass**, `120Hz` physics-driven gestures, built-in ad & tracker blocking, on-device summarization, zero-account E2EE desktop sync, and seamless **in-app self-updates**.

> [Privacy Policy & Network Manifest](PRIVACY.md) · [Security Policy](SECURITY.md) · [Credits & Licenses](CREDITS.md) · [Apache-2.0 License](LICENSE)

---

## Highlights

- **Achromatic Optical Smoked Glass UI (`120Hz`)**: Luminance-adaptive frosted dark obsidian & light alabaster chrome driven by off-thread `PixelCopy` surface sampling — zero loud brand chrome, with color blooming strictly from content and active Spaces.
- **Physics-Driven Tab & Bar Gestures**:
  - Continuous-`focus` GPU momentum/spring tab switcher deck — flick across multiple tabs smoothly, catch mid-fling with your thumb, swipe up to close, or long-press and drag tabs between Spaces.
  - Bottom-anchored 3-part floating dock (`[Tabs]` · `[Search / Domain Pill]` · `[··· Menu]`) with `VelocityTracker` horizontal pill flicks to switch tabs and vertical swipe-up for Spaces & Bookmarks, leaving left/right `24dp` edges clear for native Android Predictive Back.
- **Search-First Launch & Inline Intelligence**:
  - Opens directly into a zero-jump Search-First sheet above your keyboard with Double-Space site chips (`im␣␣`, `gh␣␣`, `hn␣␣`, `wk␣␣`), instant offline math/unit/date evaluation, local bookmark/history suggestions, and a 1-tap inline Default Search Engine switcher (`DuckDuckGo`, `Google`, `Google AI`, `Kagi`, `Brave`, `Perplexity`, `Custom`).
- **Built-In Native Rust Ad & Tracker Blocker**:
  - Brave's [`adblock-rust`](https://github.com/brave/adblock-rust) compiled via `cargo-ndk` (`opt-level = 3`, zero-copy JNI) with pre-paint cosmetic hiding, interactive **Element Zapper**, automatic tracking-parameter stripping, and `Sec-GPC: 1` (Global Privacy Control) enabled by default.
- **Private Aperture (`◎`)**:
  - Isolated ephemeral WebView profile (`focal_ephemeral`) with zero SQLite history/tab writes, zero disk thumbnail/favicon caching, zero background update checks, and optional biometric/device-credential lock.
- **Reader Mode, On-Device AI & Translation**:
  - Clean distraction-free Reader Mode (`Allo Variable` + `Switzer Variable`), two-finger **Hourglass Pinch** on-device summarization with tap-to-highlight source grounding, and optional offline in-place page translation.
- **Media Continuity & Zero-Account E2EE Desktop Bridge**:
  - Swipeable background media mini-player with `Media3` lockscreen controls & Picture-in-Picture, plus QR-paired `HKDF-SHA256` + `AES-256-GCM` Spaces & open-tab continuity via the companion MV3 desktop extension (`desktop-extension/`) and stateless WebSocket relay (`sync-relay/`).
- **Native In-App Self-Updates**:
  - Quiet daily check against GitHub Releases (toggleable in **Settings → Focal Updates**, strictly disabled in Private Aperture `◎`), live download progress bar, `SHA256SUMS.txt` + signing-certificate verification, and native Android `PackageInstaller.Session` self-installation — no manual APK hunting required.

---

## Download & In-App Updates

1. Download the latest `focal-arm64-v8a.apk` (most modern Android phones) or `focal-x86_64.apk` from [**GitHub Releases**](https://github.com/oupadhyay/focal/releases/latest) and install it once.
2. Going forward, Focal checks GitHub Releases quietly once a day (or on-demand via `··· → Settings → Check for Updates`) and displays a compact Smoked Glass update pill when a new version is ready.
3. Tap **Update** — Focal streams the signed APK, verifies its SHA-256 checksum & signing certificate, and installs the upgrade directly via Android's native `PackageInstaller` (after a one-time **"Allow from this source"** grant on Android).

---

## Repository Structure

| Path | Description |
| :--- | :--- |
| [`app/`](app/) | Main Android application (`MainActivity`, `BrowserSessionViewModel`, `FocalReleaseUpdater`, `PackageInstaller` receiver, home-screen widget, ART Baseline Profile, Robolectric UI harness & API 34 emulator instrumentation tests). |
| [`core/adblock-rust/`](core/adblock-rust/) | Native Rust `adblock-rust` crate + zero-copy JNI bridge (`libfocal_adblock.so`) and URL tracker cleaner. |
| [`core/engine-api/`](core/engine-api/) & [`core/engine-webview/`](core/engine-webview/) | Pluggable `BrowserEngine` abstraction + `androidx.webkit` System WebView implementation (BFCache, Speculative Loading, Element Zapper, DOM translation, ephemeral Private Aperture profile). |
| [`core/designsystem/`](core/designsystem/) | Optical Smoked Glass theme tokens, `AdaptiveGlassController`, AGSL `RuntimeShader`s (`SmokedGlassShader`, `HourglassPinchShader`), `Allo` + `Switzer` typography, favicon & `RGB_565` thumbnail caches, and settings store. |
| [`core/database/`](core/database/) | Room v4 SQLite schema (`spaces`, `tab_metadata`, `bookmarks`, `origin_overrides`, `history_entries`, `downloads`) with composite indices and migrations. |
| [`core/ai-summarizer/`](core/ai-summarizer/) | 100% on-device summarization (`AICore` / local fallback) + DOM paragraph grounding. |
| [`core/sync-e2ee/`](core/sync-e2ee/) | Zero-account QR pairing, SAS verification, `HKDF-SHA256` + `AES-256-GCM` E2EE crypto, and `AndroidKeyStore` session manager. |
| [`feature/`](feature/) | Modular Compose UI features: `browser`, `search`, `tabs`, `spaces`, `reader`, and `media`. |
| [`desktop-extension/`](desktop-extension/) | Companion Manifest V3 desktop browser extension (`Focal Bridge`) for Chrome, Brave, Helium, Firefox, and Zen. |
| [`sync-relay/`](sync-relay/) | Stateless zero-knowledge Cloudflare Worker + Durable Object WebSocket relay. |
| [`docs/`](docs/) & [`design/`](design/) | Architecture notes, AGSL shader documentation, font licenses, and interactive design prototype. |

---

## Building From Source

### Prerequisites
- **JDK 17**, **Gradle 8.11.1**, **Android SDK (API 35)**, and **Android NDK**
- **Rust stable** + `cargo-ndk` (`rustup target add aarch64-linux-android x86_64-linux-android`)
- **Node.js 24 LTS** (for `desktop-extension/` and `sync-relay/`)

### 1. Build Native Rust Adblock Engine (`libfocal_adblock.so`)
```bash
cd core/adblock-rust/rust
cargo test --locked
cargo ndk -t arm64-v8a -t x86_64 -o ../src/main/jniLibs build --release --locked
```

### 2. Build & Test Android App
```bash
gradle :app:assembleRelease :app:assembleDebug testDebugUnitTest --no-daemon
```
Signed APKs are emitted under `app/build/outputs/apk/release/` (`app-arm64-v8a-release.apk` and `app-x86_64-release.apk`).

### 3. Build & Test Desktop Extension & Sync Relay
```bash
(cd desktop-extension && npm ci && npm test)
(cd sync-relay && npm ci && npm test)
```

---

## Privacy, Security & Licensing

- **Privacy**: Zero telemetry, zero accounts, and full outbound network transparency — see [`PRIVACY.md`](PRIVACY.md).
- **Security**: E2EE protocol details, in-app update verification, and dev keystore transparency — see [`SECURITY.md`](SECURITY.md).
- **Credits & Third-Party Licenses**: Full acknowledgments for Lens Browser, YAB, Brave `adblock-rust`, `Allo Variable` (SIL OFL 1.1), `Switzer Variable` (ITF FFL), and all open-source libraries — see [`CREDITS.md`](CREDITS.md).
- **License**: Focal's original source code is licensed under the **[Apache License 2.0](LICENSE)**.
