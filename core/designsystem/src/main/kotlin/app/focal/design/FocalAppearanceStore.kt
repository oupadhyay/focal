package app.focal.design

import android.content.Context

/**
 * Global appearance preference for Focal's own chrome (Tab Switcher, Bookmarks, Page Menu,
 * Search Sheet, and Launch Canvas).
 *
 * Defaults to [DARK] so opening the tab list or menus from a light webpage never flashes
 * bright white unless the user explicitly selects [LIGHT] or [SYSTEM] in settings.
 * Meanwhile, the floating bottom bar over loaded web pages independently adapts its colors
 * to the underlying page luminance.
 */
enum class AppChromeTheme(
    val id: String,
    val label: String,
) {
    DARK(id = "dark", label = "Dark"),
    LIGHT(id = "light", label = "Light"),
    SYSTEM(id = "system", label = "System"),
    ;

    fun resolveDarkUi(isSystemInDarkTheme: Boolean): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        SYSTEM -> isSystemInDarkTheme
    }

    companion object {
        fun fromId(rawId: String?): AppChromeTheme {
            if (rawId.isNullOrBlank()) return DARK
            return entries.firstOrNull { it.id.equals(rawId, ignoreCase = true) } ?: DARK
        }
    }
}

/**
 * Local preference store persisting the user's chosen [AppChromeTheme] across launches.
 */
class FocalAppearanceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readTheme(): AppChromeTheme {
        return AppChromeTheme.fromId(prefs.getString(KEY_APP_THEME, AppChromeTheme.DARK.id))
    }

    fun writeTheme(theme: AppChromeTheme) {
        prefs.edit()
            .putString(KEY_APP_THEME, theme.id)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "focal_appearance_prefs"
        private const val KEY_APP_THEME = "app_chrome_theme"
    }
}
