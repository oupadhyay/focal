package app.focal.feature.reader

import android.content.Context
import app.focal.design.ReaderPaperPalette
import app.focal.design.ReaderPaperThemeColors
import app.focal.engine.api.OriginOverrides

/**
 * Pure resolution and persistence mapping for sticky per-domain Reader Mode settings.
 *
 * Precedence:
 * 1. Domain-specific overrides persisted in Room `origin_overrides` (e.g., `stratechery.com`)
 * 2. User's last-used global Reader typography defaults ([ReaderDefaultsStore])
 * 3. Built-in defaults (`Sepia`, `Switzer Sans`, `17sp`, `1.60x`)
 */
object ReaderSitePreferencesResolver {
    const val MIN_FONT_SIZE_SP: Int = 15
    const val MAX_FONT_SIZE_SP: Int = 22
    const val MIN_LINE_HEIGHT: Float = 1.45f
    const val MAX_LINE_HEIGHT: Float = 1.75f

    fun themeForId(themeId: String?): ReaderPaperThemeColors {
        if (themeId.isNullOrBlank()) return ReaderPaperPalette.Sepia
        return ReaderPaperPalette.All.firstOrNull { it.id.equals(themeId, ignoreCase = true) }
            ?: ReaderPaperPalette.Sepia
    }

    fun clampFontSizeSp(sizeSp: Int): Int = sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)

    fun clampLineHeight(ratio: Float): Float = ratio.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)

    /**
     * Resolves effective [ReaderPreferences] for a site by overlaying any saved per-domain
     * fields in [siteOverrides] onto [globalDefaults].
     */
    fun resolve(
        siteOverrides: OriginOverrides?,
        globalDefaults: ReaderPreferences = ReaderPreferences(),
    ): ReaderPreferences {
        val resolvedTheme = siteOverrides?.readerThemeId?.let(::themeForId) ?: globalDefaults.theme
        val resolvedSerif = siteOverrides?.readerUseSerif ?: globalDefaults.useEditorialSerif
        val resolvedFontSize = clampFontSizeSp(
            siteOverrides?.readerFontSizeSp ?: globalDefaults.fontSizeSp,
        )
        val resolvedLineHeight = clampLineHeight(
            siteOverrides?.readerLineHeight ?: globalDefaults.lineHeightMultiplier,
        )
        return ReaderPreferences(
            theme = resolvedTheme,
            useEditorialSerif = resolvedSerif,
            fontSizeSp = resolvedFontSize,
            lineHeightMultiplier = resolvedLineHeight,
        )
    }

    /**
     * Copies [preferences] onto [baseOverrides] so they persist atomically per domain in Room.
     */
    fun applyToOverrides(
        baseOverrides: OriginOverrides,
        preferences: ReaderPreferences,
    ): OriginOverrides = baseOverrides.copy(
        readerThemeId = preferences.theme.id,
        readerUseSerif = preferences.useEditorialSerif,
        readerFontSizeSp = clampFontSizeSp(preferences.fontSizeSp),
        readerLineHeight = clampLineHeight(preferences.lineHeightMultiplier),
    )

    /**
     * Decides whether sticky Auto-Reader Mode should automatically engage for the settled page.
     */
    fun shouldAutoOpenReader(
        autoReaderEnabled: Boolean,
        isReaderEligible: Boolean,
        isPageLoading: Boolean,
        currentUrl: String,
        dismissedUrlsInSession: Set<String>,
        isOverlayCurrentlyOpen: Boolean,
    ): Boolean {
        if (!autoReaderEnabled || !isReaderEligible || isPageLoading || isOverlayCurrentlyOpen) {
            return false
        }
        val normalizedUrl = currentUrl.trim()
        if (normalizedUrl.isBlank() || normalizedUrl == "about:blank") return false
        return normalizedUrl !in dismissedUrlsInSession
    }
}

/**
 * Anonymous local preference store holding the user's last-used global Reader appearance defaults
 * (never stores URLs, domains, or Private Aperture browsing data).
 */
class ReaderDefaultsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ReaderPreferences {
        val themeId = prefs.getString(KEY_THEME_ID, ReaderPaperPalette.Sepia.id)
        val useSerif = prefs.getBoolean(KEY_USE_SERIF, false)
        val fontSizeSp = prefs.getInt(KEY_FONT_SIZE_SP, 17)
        val lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, 1.60f)
        return ReaderPreferences(
            theme = ReaderSitePreferencesResolver.themeForId(themeId),
            useEditorialSerif = useSerif,
            fontSizeSp = ReaderSitePreferencesResolver.clampFontSizeSp(fontSizeSp),
            lineHeightMultiplier = ReaderSitePreferencesResolver.clampLineHeight(lineHeight),
        )
    }

    fun write(preferences: ReaderPreferences) {
        prefs.edit()
            .putString(KEY_THEME_ID, preferences.theme.id)
            .putBoolean(KEY_USE_SERIF, preferences.useEditorialSerif)
            .putInt(KEY_FONT_SIZE_SP, ReaderSitePreferencesResolver.clampFontSizeSp(preferences.fontSizeSp))
            .putFloat(KEY_LINE_HEIGHT, ReaderSitePreferencesResolver.clampLineHeight(preferences.lineHeightMultiplier))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "focal_reader_defaults"
        private const val KEY_THEME_ID = "theme_id"
        private const val KEY_USE_SERIF = "use_serif"
        private const val KEY_FONT_SIZE_SP = "font_size_sp"
        private const val KEY_LINE_HEIGHT = "line_height"
    }
}
