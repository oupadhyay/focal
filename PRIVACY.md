# Focal Browser — Privacy Policy & Network Manifest

**Last Updated:** 2026-11-26

Focal is built to be **private by architecture**, not just by policy. There are no user accounts, no analytics SDKs, no crash-reporting SDKs, no advertising IDs, and no telemetry endpoints operated by Focal.

Every outbound network connection Focal can make is documented below and verifiable directly in this repository.

---

## 1. Zero-Collection Guarantees

- **No accounts or sign-in required**: Browsing, Spaces, bookmarks, adblocking, reader mode, summarization, and even cross-device desktop sync (`Focal Bridge`) work without creating an account or providing an email address.
- **No telemetry, analytics, or crash reporters**: Focal contains zero Firebase Analytics, Sentry, Crashlytics, Mixpanel, or telemetry beacons.
- **No Google SafeBrowsing lookups**: Android WebView SafeBrowsing (`android.webkit.WebView.EnableSafeBrowsing`) is explicitly disabled in [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) and [`WarmWebViewPool.kt`](core/engine-webview/src/main/kotlin/app/focal/engine/webview/WarmWebViewPool.kt); ad/tracker blocking runs 100% locally via Rust [`adblock-rust`](core/adblock-rust/).
- **Global Privacy Control (`Sec-GPC: 1`) enabled by default**: Focal signals `Sec-GPC: 1` (HTTP header & `navigator.globalPrivacyControl`) to websites you visit; configurable in **Settings → Privacy & Security**.
- **On-device AI summarization & inline answers**: Pinch-to-Summarize, unit/currency/math/date evaluations (`InlineFieldEvaluator`), and local bookmark/history search suggestions run strictly on your phone. Page text is never uploaded to a Focal server.

---

## 2. Complete Outbound Network Manifest

Focal initiates network traffic **only** for the following purposes:

| Purpose | Destination | When It Runs | Private Aperture (`◎`) Behavior | User Control |
| :--- | :--- | :--- | :--- | :--- |
| **Web Browsing** | Websites you navigate to | When you enter a URL, tap a link, or open a bookmark | Isolated Chromium ephemeral profile (`focal_ephemeral`) | Standard browsing + per-site overrides & JS/Adblock toggles |
| **Web Search** | Your chosen search engine (default: **DuckDuckGo**; presets: Google, Google AI, Kagi, Brave, Perplexity, Custom) | Only when you submit a search query (never keystroke-by-keystroke remote autocomplete) | Same selected search engine; zero local history saved | 1-tap switcher in Search Bar or **Settings → Search Engine** |
| **In-App Update Checks & Downloads** | `https://api.github.com/repos/oupadhyay/focal/releases/latest` & GitHub Release HTTPS assets | Quietly at most once every 24 hours (deferred after launch) or manually via **Settings → Check for Updates** | **Strictly disabled** (never checks inside Private Aperture) | Toggle **Settings → Auto-Check for Updates** OFF for 100% manual-only checks |
| **Site Favicons** | Site `/favicon.ico` or `apple-touch-icon` directly from the visited origin | Only when a domain has no built-in vector mark and no cached icon | Cached strictly in-memory only; never written to disk | Cached locally in app cache; cleared on Clear Browsing Data |
| **On-Device Page Translation Models** | Google ML Kit model CDN (via `com.google.mlkit:translate`) | **Only** when you explicitly tap **Translate Page** in the `···` menu and a language pack is not yet on disk | Translation runs locally once the model is downloaded | Completely unused unless you invoke Translate Page |
| **E2EE Desktop Continuity (`Focal Bridge`)** | Your configured `wss://` / `https://` Sync Relay | **Only** after explicit QR pairing + 4-part SAS verification | **Strictly excluded** — Private Aperture tabs are never synced | Unpair anytime; relay sees only `AES-256-GCM` ciphertext |

---

## 3. Private Aperture (`◎`) Isolation

When Private Aperture (`◎`) is active:
- WebViews run in Android WebView's dedicated ephemeral profile (`focal_ephemeral`) rather than the default persistent profile.
- **Zero SQLite writes**: Visited URLs, tab metadata, browsing history, and per-origin overrides are kept strictly in RAM and never written to Room (`FocalDatabase`).
- **Zero disk caches**: Tab preview thumbnails and favicons are never persisted to disk, and speculative URL prefetching is disabled.
- **Zero background update checks**: Daily update checks are skipped while in Private Aperture.
- **Optional Biometric Lock**: Enable **Settings → Lock Private Aperture** to require device biometrics/PIN before switching into Private Aperture.
- Closing a Private Aperture tab or exiting Private Aperture immediately destroys its in-memory session and ephemeral WebView state.

---

## 4. Data Stored Locally on Your Device

All personal browsing state remains strictly on your device inside Focal's private Android app sandbox (`android:allowBackup="false"` prevents automatic cloud backups):

- **Room SQLite (`focal_browser.db`)**: Active/archived non-private tabs, Spaces, bookmarks/quick pins, local browsing history, download metadata, and per-site settings (adblock exceptions, zapped CSS selectors, reader preferences, force-dark/zoom).
- **SharedPreferences**: Selected search engine, theme mode, scroll-hide preference, GPC toggle, biometric lock preference, auto-update toggle, last update-check timestamp, last dismissed update tag, and anonymous portrait/landscape keyboard height pixels (`LaunchKeyboardStore`) for zero-jump keyboard layout.
- **Encrypted Pairing Key (`AndroidKeyStore`)**: When paired with `Focal Bridge`, the 256-bit sync secret is sealed with hardware-backed `AES-256-GCM` inside Android Keystore.
- **App Cache (`cacheDir`)**: Serialized Rust adblock `.dat` cache, `RGB_565` tab preview thumbnails, site favicons, and temporary update APKs (automatically deleted after installation).

**Settings → Clear Browsing Data** wipes local browsing history, disk-cached tab thumbnails, WebView cookies, WebStorage, and WebView cache.

---

## 5. Android Permissions Explained

| Permission | Why Focal Requests It |
| :--- | :--- |
| `INTERNET` | Required for web browsing, search, and user-initiated updates/sync. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keeps background audio/video playback alive with lockscreen & notification controls (`Media3`) when playing media from a tab. |
| `POST_NOTIFICATIONS` | Optional runtime permission to display media playback and download notifications on Android 13+. |
| `USE_BIOMETRIC` | Optional device biometric/credential prompt when **Lock Private Aperture** is enabled. |
| `REQUEST_INSTALL_PACKAGES` | Used strictly when you confirm an in-app Focal update (`PackageInstaller.Session`) so you can update directly inside Focal without downloading APKs manually. |
