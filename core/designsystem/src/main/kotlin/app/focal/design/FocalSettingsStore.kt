package app.focal.design

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import java.net.URLEncoder

/**
 * Supported default search engine presets for Focal's address bar and search sheet.
 */
enum class SearchEnginePreset(
    val id: String,
    val displayName: String,
    val urlTemplate: String,
) {
    DUCKDUCKGO(
        id = "duckduckgo",
        displayName = "DuckDuckGo",
        urlTemplate = "https://duckduckgo.com/?q=%s",
    ),
    GOOGLE(
        id = "google",
        displayName = "Google",
        urlTemplate = "https://www.google.com/search?q=%s",
    ),
    KAGI(
        id = "kagi",
        displayName = "Kagi",
        urlTemplate = "https://kagi.com/search?q=%s",
    ),
    BRAVE(
        id = "brave",
        displayName = "Brave",
        urlTemplate = "https://search.brave.com/search?q=%s",
    ),
    PERPLEXITY(
        id = "perplexity",
        displayName = "Perplexity",
        urlTemplate = "https://www.perplexity.ai/search?q=%s",
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom",
        urlTemplate = "https://duckduckgo.com/?q=%s",
    ),
    ;

    companion object {
        fun fromId(rawId: String?): SearchEnginePreset {
            if (rawId.isNullOrBlank()) return DUCKDUCKGO
            return entries.firstOrNull { it.id.equals(rawId.trim(), ignoreCase = true) }
                ?: DUCKDUCKGO
        }
    }
}

/**
 * Immutable snapshot of user-configurable browser settings persisted by [FocalSettingsStore].
 */
@Immutable
data class FocalBrowserSettings(
    val searchEngine: SearchEnginePreset = SearchEnginePreset.DUCKDUCKGO,
    val customSearchTemplate: String = "",
    val globalPrivacyControlEnabled: Boolean = true,
    val biometricPrivateLockEnabled: Boolean = false,
    val fullHideBottomBarOnScroll: Boolean = true,
) {
    /**
     * Resolves the effective `%s` search URL template, falling back cleanly to
     * [SearchEnginePreset.DUCKDUCKGO] whenever [SearchEnginePreset.CUSTOM] is selected with an
     * invalid or non-HTTP(S) template.
     */
    fun effectiveSearchTemplate(): String {
        if (searchEngine == SearchEnginePreset.CUSTOM) {
            val candidate = customSearchTemplate.trim()
            if (isValidCustomTemplate(candidate)) {
                return candidate
            }
            return SearchEnginePreset.DUCKDUCKGO.urlTemplate
        }
        return searchEngine.urlTemplate
    }

    /**
     * Builds a search URL for [rawQuery] using UTF-8 URL encoding and literal `%s` substitution.
     */
    fun buildSearchUrl(rawQuery: String): String {
        val trimmed = rawQuery.trim()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return effectiveSearchTemplate().replace("%s", encoded)
    }

    companion object {
        fun isValidCustomTemplate(template: String): Boolean {
            val candidate = template.trim()
            if (candidate.isEmpty()) return false
            if (candidate.any { it.isWhitespace() }) return false
            val hasHttpScheme = candidate.startsWith("https://", ignoreCase = true) ||
                candidate.startsWith("http://", ignoreCase = true)
            return hasHttpScheme && candidate.contains("%s")
        }
    }
}

/**
 * Local preference store persisting [FocalBrowserSettings] across launches.
 */
class FocalSettingsStore internal constructor(
    private val prefs: SharedPreferences,
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun read(): FocalBrowserSettings {
        val defaults = FocalBrowserSettings()
        return FocalBrowserSettings(
            searchEngine = SearchEnginePreset.fromId(
                prefs.getString(KEY_SEARCH_ENGINE, defaults.searchEngine.id),
            ),
            customSearchTemplate = prefs.getString(
                KEY_CUSTOM_SEARCH_TEMPLATE,
                defaults.customSearchTemplate,
            ).orEmpty(),
            globalPrivacyControlEnabled = prefs.getBoolean(
                KEY_GLOBAL_PRIVACY_CONTROL,
                defaults.globalPrivacyControlEnabled,
            ),
            biometricPrivateLockEnabled = prefs.getBoolean(
                KEY_BIOMETRIC_PRIVATE_LOCK,
                defaults.biometricPrivateLockEnabled,
            ),
            fullHideBottomBarOnScroll = prefs.getBoolean(
                KEY_FULL_HIDE_BOTTOM_BAR_ON_SCROLL,
                defaults.fullHideBottomBarOnScroll,
            ),
        )
    }

    fun write(settings: FocalBrowserSettings) {
        prefs.edit()
            .putString(KEY_SEARCH_ENGINE, settings.searchEngine.id)
            .putString(KEY_CUSTOM_SEARCH_TEMPLATE, settings.customSearchTemplate.trim())
            .putBoolean(KEY_GLOBAL_PRIVACY_CONTROL, settings.globalPrivacyControlEnabled)
            .putBoolean(KEY_BIOMETRIC_PRIVATE_LOCK, settings.biometricPrivateLockEnabled)
            .putBoolean(KEY_FULL_HIDE_BOTTOM_BAR_ON_SCROLL, settings.fullHideBottomBarOnScroll)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "focal_browser_settings"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_CUSTOM_SEARCH_TEMPLATE = "custom_search_template"
        private const val KEY_GLOBAL_PRIVACY_CONTROL = "global_privacy_control_enabled"
        private const val KEY_BIOMETRIC_PRIVATE_LOCK = "biometric_private_lock_enabled"
        private const val KEY_FULL_HIDE_BOTTOM_BAR_ON_SCROLL = "full_hide_bottom_bar_on_scroll"
    }
}
