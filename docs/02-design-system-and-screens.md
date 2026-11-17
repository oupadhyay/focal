# 02 · Focal Design System & Screen-by-Screen Specification

## 1. Locked Visual Identity: Achromatic Optical Smoked Glass

Focal adopts **Lens-like Achromatic Optical Smoked Glass**:
- **Zero Brand Accent in Browser Chrome**: Every toolbar pill, button, toggle, badge, and sheet uses pure achromatic neutrals (white/silver/graphite ink over translucent dark obsidian or light alabaster glass).
- **Contextual Content Color Only**: Chromatic color is reserved strictly for:
  1. **Double-Space Site Search Chips**: e.g., `IMDb` (`#F5C518` gold + `22%` radial input glow), `YouTube` (`#FF3B30`), `Reddit` (`#FF5700`), while `Wikipedia` and `GitHub` remain crisp monochrome.
  2. **Spaces Ambient Backdrop & Tags**: Switching between user Spaces (`Personal` `#F4725E` warm rose-smoke, `Work` `#94A3B8` cool slate-smoke, `Reading` `#4ADE80` sage-emerald smoke) tints the subtle radial glow behind the Spaces sheet while keeping foreground controls 100% achromatic.
  3. **Reader Mode Paper Swatches**: `Obsidian`, `Paper`, `Sepia`, `Alabaster`, and `Sage`.

### Surface & Semantic Color Tokens

| Token | Dark Smoked OLED Glass (`luminance < 0.45`) | Light Alabaster Frosted Glass (`luminance >= 0.45`) | Usage |
| :--- | :--- | :--- | :--- |
| `surface.glass.bar` | `rgba(22, 23, 27, 0.82)` | `rgba(246, 247, 249, 0.85)` | 3-part bottom bar, Mini-Player, smart pills |
| `surface.glass.sheet` | `rgba(28, 30, 35, 0.90)` | `rgba(255, 255, 255, 0.93)` | Search sheet, Summary sheet, `···` Menu, Link Peek |
| `surface.glass.private` | `rgba(10, 11, 14, 0.94)` | `rgba(14, 15, 19, 0.94)` | Always dark obsidian when Private Aperture `◎` is armed |
| `surface.pill.subtle` | `rgba(255, 255, 255, 0.08)` | `rgba(15, 17, 21, 0.06)` | Squircles, inactive pills, settings rows |
| `surface.pill.active` | `#F4F5F7` (ink `#0B0C0E`) | `#131417` (ink `#FFFFFF`) | Primary active pills (`Go`, `Summary`, active subtab) |
| `border.specular` | `rgba(255, 255, 255, 0.11)` | `rgba(15, 17, 21, 0.09)` | `1dp` hairline inner/outer rim on all glass surfaces |
| `ink.primary` | `#F5F6F8` | `#121316` | Primary text, active icons, tab counter |
| `ink.secondary` | `rgba(245, 246, 248, 0.64)` | `rgba(18, 19, 22, 0.62)` | Subtitles, secondary URL metadata, inactive tabs |
| `ink.faint` | `rgba(245, 246, 248, 0.38)` | `rgba(18, 19, 22, 0.40)` | Gesture hints, timestamps, source-jump captions |

---

## 2. Locked Typography System: **Allo Crisp** + **Switzer**

Both variable font binaries are bundled in [`design/fonts/`](../design/fonts/) ready for `app/src/main/res/font/`:
- **Headings, Display & Focus Text (`Allo Variable` — SIL OFL 1.1)**:
  - File: [`allo_variable.ttf`](../design/fonts/allo_variable.ttf)
  - Axes locked to **Crisp Neo-Grotesk**: `'ROND' 0f`, optical size `'opsz' 24f..32f` for headings and `'opsz' 14f..16f` for focus UI (active search query, URL domain pill, tab badge numerals, speed badge `1.25×`).
  - OpenType features: `'tnum' 1, 'calt' 1, 'ss01' 1, 'zero' 1` — enforces tabular numbers (zero jitter during tab countdowns or benchmarks) and clear `I / l / 1 / 0 / O` disambiguation in URLs to prevent homograph/phishing spoofing.
- **Body & Dense Reading Copy (`Switzer Variable` — ITF Free Commercial License)**:
  - File: [`switzer_variable.ttf`](../design/fonts/switzer_variable.ttf)
  - Weights `400` (Regular) and `500` (Medium), tight Swiss neo-grotesk proportions for effortless scanning in summaries, Reader mode sans option, Link Peek previews, and settings descriptions.

### Jetpack Compose `Typography` Implementation

```kotlin
@OptIn(ExperimentalTextApi::class)
val AlloCrispDisplay = FontFamily(
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 28f),
            FontVariation.weight(600)
        )
    )
)

@OptIn(ExperimentalTextApi::class)
val AlloCrispFocusUi = FontFamily(
    Font(
        resId = R.font.allo_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", 14f),
            FontVariation.weight(500)
        )
    )
)

@OptIn(ExperimentalTextApi::class)
val SwitzerBody = FontFamily(
    Font(
        resId = R.font.switzer_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        resId = R.font.switzer_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    )
)
```

---

## 3. Screen-by-Screen UX Architecture (v1 Scope)

Interactive code-rendered mockups for all 9 screens live in [`design/prototype/index.html`](../design/prototype/index.html).

### Screen 01 · Calm Optical Launch / Empty State
- **Layout**: Zero news feed, zero crypto/rewards bloat. Subtle radial optical vignette (`OpticalVignetteShader`), centered minimal focal aperture mark, E2EE desktop continuity handoff chip at top (*only* rendered when a paired desktop browser has an active handoff or recent shared link), and the 3-part bottom bar.
- **Cold-Start Behavior**: Optional user preference (`Auto-open keyboard on cold launch`, enabled by default after onboarding) transitions directly into **Screen 02** in `0ms` so thumb-typing begins immediately.

### Screen 02 · Search-First Floating Sheet + Double-Space Site Search
- **Positioning**: Floating `26dp`-radius smoked glass card anchored `8dp` above `WindowInsets.ime`, keeping every touch target directly in the lower thumb arc.
- **Double-Space Site Search Engine**:
  - Typing a 2–3 character shortcut (`im`, `wi`, `rd`, `yt`, `gh`, `hn`, `am`, plus custom OpenSearch/URL-template shortcuts) followed by **two spaces within `320ms`** (`"im  "`) converts the prefix into a spring-animated leading chip (`[IMDb ×]`) and tints a subtle `135dp` radial glow strictly inside the left side of the input pill.
  - Pressing `Backspace` when the query text is empty dissolves the chip back into plain text (`"im"`).
- **Private Aperture Toggle (`◎`)**: Right-aligned `28dp` circular toggle inside the input bar. Arming `◎` darkens the card to `surface.glass.private` and routes the query to an isolated, in-memory `WebStorage` / cookie-jar ephemeral session with zero history writes.

### Screen 03 · Full-Bleed 120Hz Web Surface + 3-Part Bar + Media Dock
- **Edge-to-Edge Viewport**: `WindowCompat.setDecorFitsSystemWindows(window, false)`. Status bar icons adapt automatically via `WindowInsetsControllerCompat` based on top-edge page luminance.
- **3-Part Floating Bottom Bar (`48dp` height, `12dp` bottom inset above gesture pill)**:
  1. **Left Capsule (`[4]`)**: Tab counter squircle inside circular `48dp` glass pill -> taps into **Screen 06**. Long-press opens quick `"Close Current Tab / New Private Tab"` micro-popover.
  2. **Center Capsule (`[⌕ domain.com]`)**:
     - **Tap**: Shared-bounds morph into **Screen 02** Search Sheet with current URL pre-selected for instant copy/edit.
     - **Horizontal Drag / Scrub**: Scrubs live across open tabs with rubber-banded spring parallax (`0.94f` scale during drag).
     - **Vertical Swipe-Up**: Pulls up **Screen 05** (Spaces & Bookmarks Sheet) coupled 1:1 to finger velocity.
  3. **Right Capsule (`[···]`)**: Opens **Screen 08** Page Menu & Site Overrides.
- **Smart Contextual Pills**: `Aa Reader` (when Readability score `> threshold`), `文A Translate` (when page `<html lang>` != device locales), and `↓ Download` appear in a compact floating row `8dp` above the right side of the 3-part bar and auto-collapse on downward scroll (`> 18dp` delta).
- **Background Media Mini-Player**: Docks `8dp` above the left/center of the 3-part bar whenever a background or foreground tab holds an active audio/video session (`Play/Pause`, title marquee, `1.0× / 1.25× / 1.5× / 2.0×` speed pill, and `PiP` pop-out button).

### Screen 04 · Pinch-to-Summarize (Hourglass Warp -> Local Grounded Summary)
- **Trigger**: Two-finger **inward pinch** when webpage zoom is already at `1.0×` minimum scale (`scaleFactor < 0.96f`).
- **Optical Transition**: Drives `HourglassPinchShader` continuously from `progress = 0f..1f`. Releasing past `0.68f` snaps into the Smoked Glass Summary Sheet (with light haptic tick `HapticFeedbackConstants.CONFIRM`); releasing below `0.68f` springs the page cleanly back to flat `1.0×`.
- **Grounded Output**: Displays `Gemini Nano · On-Device` badge, `Allo Crisp` thesis headline, and 3–5 `Switzer` bullet cards. Tapping any card unpinches the sheet, smooth-scrolls the WebView to `[data-focal-para-id="N"]`, and pulses a subtle achromatic highlight border around the source paragraph.

### Screen 05 · Swipe-Up Spaces & Bookmarks Sheet
- **Trigger**: Vertical swipe-up gesture originating anywhere on the 3-part bottom bar.
- **Structure**: Top Space switcher pills (`Personal`, `Work`, `Reading`, `+`), 4-column Space-scoped Pinned Squircles, nested bookmark folders (supports HTML bookmark import/export), and a `Live on Desktop (E2EE)` presence card showing tabs currently open in the paired Desktop Extension.

### Screen 06 · Horizontal Card-Stack Tab Switcher + 24h Auto-Archive
- **Active Stack**: Hardware-accelerated tab cards scoped to the current Space, displaying domain, `Allo Crisp` title, and subtle remaining TTL (`19h left`).
- **Unsent-Form Draft Protection**: Before any tab is eligible for 24h auto-archiving, Focal checks a lightweight injected DOM dirty bit (`input/textarea/contenteditable` modified by user). Dirty tabs display `🔒 Unsent Draft Protected` and are **never** auto-closed.
- **Tidied Tray (`>24h idle`)**: Collapsible bottom drawer holding the last 30 days of auto-archived tabs as zero-RAM metadata entries for instant 1-tap restoration.

### Screen 07 · Long-Press Scrollable Link Peek + `"Where Does This Go"`
- **Trigger**: Long-pressing any `<a href="...">` inside a webpage (`HitTestResult.SRC_ANCHOR_TYPE`).
- **Top Cleaned Destination Pill**: Runs URL through `adblock-rust` query-parameter cleaner (`utm_*`, `fbclid`, `gclid`, `mc_eid`, `igshid`, `si`) + optional `HEAD` redirect hop resolver (`max 4 hops`), showing the stripped tracking params with strikethrough.
- **Live Scrollable Card**: Spawns an ephemeral sandboxed WebView inside a `26dp` rounded glass frame (`62%` viewport height) so users can read footnotes, tweets, or docs inline, then either flick down to dismiss (`0ms` return to parent scroll position) or pull up / tap `Expand Full Tab ↗` to promote into a real tab.

### Screen 08 · `···` Page Menu, Distraction-Free Reader & Live Per-Site Overrides
- **Menu Root**: Compact URL header (`Back`, `Forward`, domain, `Copy Clean Link`, `Reload`), 4 squircle quick actions (`Share`, `Pin to Space`, `Save Bookmark`, `Find in Page`), `Show Reader`, `Translate`, `Desktop Site`, and `Site Settings`.
- **Reader Mode**: 5 paper surfaces (`Obsidian`, `Paper`, `Sepia`, `Alabaster`, `Sage`), `Switzer Sans` vs `Editorial Serif` body toggle, font size (`15sp–22sp`), and line-height (`1.45–1.75`).
- **Per-Site Live Overrides**: Scoped per origin (`eTLD+1`), stored in Room + applied synchronously via `DocumentStartJavaScript`: `Block Ads & Trackers` (with live blocked count), `Force Dark Web Surface`, `Override Publisher Fonts (Allo + Switzer)`, `Page Zoom (85%–150%)`, and `JavaScript`.

### Screen 09 · Zero-Account E2EE Pairing + `Focal Bridge` Desktop Extension
- **Android Pairing View**: Generates an ephemeral `X25519` keypair + room bootstrap QR code and displays a 4-word SAS (Short Authentication String) fingerprint (`optics · glacier · shutter · 49`) to prevent MITM attacks.
- **Desktop MV3 Extension (`Focal Bridge`)**: 320px toolbar popover matching Focal's achromatic smoked glass aesthetic; supports `1-Click Send Tab to Phone`, right-click `"Send Link to Focal"`, viewing live Android open tabs, and bidirectional E2EE Spaces bookmark sync.
