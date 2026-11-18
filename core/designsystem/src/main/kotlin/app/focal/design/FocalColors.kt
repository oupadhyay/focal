package app.focal.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Focal's Locked Visual Identity: Achromatic Optical Smoked Glass.
 *
 * Zero brand accent color is permitted anywhere in browser chrome (toolbars, pills, toggles,
 * badges, or sheets). Chromatic color is strictly scoped to:
 * 1. Contextual Double-Space Site Search Chips ([ContextualSitePalette])
 * 2. Contextual Space Ambient Backdrops ([SpaceAmbientPalette])
 * 3. Distraction-Free Reader Paper Swatches ([ReaderPaperPalette])
 */
@Immutable
data class FocalColorTokens(
    val isDarkGlass: Boolean,
    val isPrivateAperture: Boolean,
    val canvasBackground: Color,
    val surfaceGlassBar: Color,
    val surfaceGlassSheet: Color,
    val surfaceGlassPrivate: Color,
    val surfacePillSubtle: Color,
    val surfacePillActive: Color,
    val inkOnPillActive: Color,
    val borderSpecular: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkFaint: Color,
) {
    /** Effective sheet background accounting for Private Aperture `◎` obsidian override. */
    val effectiveSheetGlass: Color
        get() = if (isPrivateAperture) surfaceGlassPrivate else surfaceGlassSheet

    /** Effective bar background accounting for Private Aperture `◎` obsidian override. */
    val effectiveBarGlass: Color
        get() = if (isPrivateAperture) surfaceGlassPrivate else surfaceGlassBar
}

object FocalPalettes {
    /**
     * Dark Smoked OLED Glass (`luminance < 0.45`, hysteresis exit at `>= 0.52`).
     */
    val DarkSmokedOled = FocalColorTokens(
        isDarkGlass = true,
        isPrivateAperture = false,
        canvasBackground = Color(0xFF0A0B0D),
        // rgba(22, 23, 27, 0.88)
        surfaceGlassBar = Color(0xE016171B),
        // rgba(24, 25, 29, 0.95) — rich velvety smoked sheet
        surfaceGlassSheet = Color(0xF218191D),
        // rgba(10, 11, 14, 0.96)
        surfaceGlassPrivate = Color(0xF50A0B0E),
        // rgba(255, 255, 255, 0.075) — borderless tonal card fill
        surfacePillSubtle = Color(0x13FFFFFF),
        surfacePillActive = Color(0xFFF4F5F7),
        inkOnPillActive = Color(0xFF0B0C0E),
        // Top-edge specular highlight rgba(255, 255, 255, 0.15)
        borderSpecular = Color(0x26FFFFFF),
        inkPrimary = Color(0xFFF5F6F8),
        // rgba(245, 246, 248, 0.64)
        inkSecondary = Color(0xA3F5F6F8),
        // rgba(245, 246, 248, 0.36)
        inkFaint = Color(0x5CF5F6F8),
    )

    /**
     * Light Alabaster Frosted Glass (`luminance >= 0.45`, hysteresis exit at `<= 0.44`).
     */
    val LightAlabaster = FocalColorTokens(
        isDarkGlass = false,
        isPrivateAperture = false,
        canvasBackground = Color(0xFFF7F7F8),
        // rgba(246, 247, 249, 0.90)
        surfaceGlassBar = Color(0xE6F6F7F9),
        // rgba(252, 252, 253, 0.96)
        surfaceGlassSheet = Color(0xF5FCFCFD),
        // rgba(14, 15, 19, 0.96) — Always dark obsidian when Private Aperture ◎ is armed
        surfaceGlassPrivate = Color(0xF50E0F13),
        // rgba(15, 17, 21, 0.055)
        surfacePillSubtle = Color(0x0E0F1115),
        surfacePillActive = Color(0xFF131417),
        inkOnPillActive = Color(0xFFFFFFFF),
        // rgba(15, 17, 21, 0.09)
        borderSpecular = Color(0x170F1115),
        inkPrimary = Color(0xFF121316),
        // rgba(18, 19, 22, 0.60)
        inkSecondary = Color(0x99121316),
        // rgba(18, 19, 22, 0.38)
        inkFaint = Color(0x61121316),
    )

    /**
     * Dedicated Private Obsidian Glass tokens when Private Aperture `◎` is active,
     * forcing crisp silver-white ink on deep obsidian regardless of page luminance.
     */
    val PrivateObsidian = DarkSmokedOled.copy(
        isPrivateAperture = true,
        canvasBackground = Color(0xFF060709),
        surfaceGlassBar = Color(0xF20A0B0E),
        surfaceGlassSheet = Color(0xF70A0B0E),
    )
}

/**
 * Contextual Double-Space Site Search Chip palette (strictly confined inside the search input pill).
 */
@Immutable
data class SiteChipColors(
    val badgeBackground: Color,
    val badgeForeground: Color,
    val radialInputGlow: Color,
)

object ContextualSitePalette {
    val IMDb = SiteChipColors(
        badgeBackground = Color(0xFFF5C518),
        badgeForeground = Color(0xFF111113),
        radialInputGlow = Color(0x38F5C518), // 22% radial input glow
    )
    val Wikipedia = SiteChipColors(
        badgeBackground = Color(0xFFF1F5F9),
        badgeForeground = Color(0xFF0F172A),
        radialInputGlow = Color(0x29F1F5F9), // Crisp monochrome
    )
    val Reddit = SiteChipColors(
        badgeBackground = Color(0xFFFF5700),
        badgeForeground = Color(0xFFFFFFFF),
        radialInputGlow = Color(0x38FF5700),
    )
    val YouTube = SiteChipColors(
        badgeBackground = Color(0xFFFF3B30),
        badgeForeground = Color(0xFFFFFFFF),
        radialInputGlow = Color(0x38FF3B30),
    )
    val GitHub = SiteChipColors(
        badgeBackground = Color(0xFFE4E4E7),
        badgeForeground = Color(0xFF09090B),
        radialInputGlow = Color(0x26E4E4E7), // Crisp monochrome
    )
    val Neutral = SiteChipColors(
        badgeBackground = Color(0xFFE4E4E7),
        badgeForeground = Color(0xFF0B0C0E),
        radialInputGlow = Color(0x22FFFFFF),
    )
}

/**
 * Contextual Space Ambient Backdrop tokens for GPU `spaceAmbient` uniform interpolation.
 * Foreground controls remain 100% achromatic.
 */
@Immutable
data class SpaceAmbientColor(
    val key: String,
    val dotColor: Color,
    val darkAmbientUniform: Color,
    val lightAmbientUniform: Color,
) {
    fun ambientFor(isDarkGlass: Boolean): Color =
        if (isDarkGlass) darkAmbientUniform else lightAmbientUniform
}

object SpaceAmbientPalette {
    val None = SpaceAmbientColor(
        key = "none",
        dotColor = Color.Transparent,
        darkAmbientUniform = Color(0f, 0f, 0f, 0f),
        lightAmbientUniform = Color(0f, 0f, 0f, 0f),
    )

    /** Warm rose-smoke (`#F4725E`) */
    val Personal = SpaceAmbientColor(
        key = "personal",
        dotColor = Color(0xFFF4725E),
        darkAmbientUniform = Color(0xFFF4725E).copy(alpha = 0.19f),
        lightAmbientUniform = Color(0xFFF4725E).copy(alpha = 0.16f),
    )

    /** Cool slate-smoke (`#94A3B8`) */
    val Work = SpaceAmbientColor(
        key = "work",
        dotColor = Color(0xFF94A3B8),
        darkAmbientUniform = Color(0xFF94A3B8).copy(alpha = 0.18f),
        lightAmbientUniform = Color(0xFF64748B).copy(alpha = 0.15f),
    )

    /** Sage-emerald smoke (`#4ADE80`) */
    val Reading = SpaceAmbientColor(
        key = "reading",
        dotColor = Color(0xFF4ADE80),
        darkAmbientUniform = Color(0xFF4ADE80).copy(alpha = 0.16f),
        lightAmbientUniform = Color(0xFF22C55E).copy(alpha = 0.14f),
    )

    fun fromKeyOrHex(keyOrId: String, fallbackHex: String? = null): SpaceAmbientColor {
        return when (keyOrId.lowercase()) {
            "personal" -> Personal
            "work" -> Work
            "reading" -> Reading
            else -> {
                val parsed = fallbackHex?.removePrefix("#")?.toLongOrNull(16)?.let { hex ->
                    Color(0xFF000000 or hex)
                } ?: Color(0xFF94A3B8)
                SpaceAmbientColor(
                    key = keyOrId,
                    dotColor = parsed,
                    darkAmbientUniform = parsed.copy(alpha = 0.17f),
                    lightAmbientUniform = parsed.copy(alpha = 0.14f),
                )
            }
        }
    }
}

/**
 * 5 Distraction-Free Reader Mode Paper Swatches.
 */
@Immutable
data class ReaderPaperThemeColors(
    val id: String,
    val label: String,
    val background: Color,
    val primaryInk: Color,
    val secondaryInk: Color,
    val divider: Color,
)

object ReaderPaperPalette {
    val Obsidian = ReaderPaperThemeColors(
        id = "obsidian",
        label = "Obsidian",
        background = Color(0xFF0D0E11),
        primaryInk = Color(0xFFF3F4F6),
        secondaryInk = Color(0xFF9CA3AF),
        divider = Color(0x1FFFFFFF),
    )
    val Paper = ReaderPaperThemeColors(
        id = "paper",
        label = "Paper",
        background = Color(0xFFF7F5EE),
        primaryInk = Color(0xFF191918),
        secondaryInk = Color(0xFF57534E),
        divider = Color(0x1A191918),
    )
    val Sepia = ReaderPaperThemeColors(
        id = "sepia",
        label = "Sepia",
        background = Color(0xFFEFE6D5),
        primaryInk = Color(0xFF2C2217),
        secondaryInk = Color(0xFF6B5742),
        divider = Color(0x1F2C2217),
    )
    val Alabaster = ReaderPaperThemeColors(
        id = "alabaster",
        label = "Alabaster",
        background = Color(0xFFFFFFFF),
        primaryInk = Color(0xFF111827),
        secondaryInk = Color(0xFF4B5563),
        divider = Color(0x17111827),
    )
    val Sage = ReaderPaperThemeColors(
        id = "sage",
        label = "Sage",
        background = Color(0xFFE6EFE9),
        primaryInk = Color(0xFF16261D),
        secondaryInk = Color(0xFF435E4F),
        divider = Color(0x1C16261D),
    )

    val All: List<ReaderPaperThemeColors> = listOf(Obsidian, Paper, Sepia, Alabaster, Sage)
}
