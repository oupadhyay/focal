package app.focal.design

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalSettingsStoreTest {

    @Test
    fun defaults_matchExpectedBrowserDefaults() {
        val settings = FocalBrowserSettings()
        assertEquals(SearchEnginePreset.DUCKDUCKGO, settings.searchEngine)
        assertEquals("", settings.customSearchTemplate)
        assertTrue(settings.globalPrivacyControlEnabled)
        assertFalse(settings.biometricPrivateLockEnabled)
        assertTrue(settings.fullHideBottomBarOnScroll)
        assertTrue(settings.autoCheckUpdatesEnabled)
    }

    @Test
    fun searchEnginePreset_fromIdResolvesAllPresetsAndFallsBackToDuckDuckGo() {
        assertEquals(SearchEnginePreset.DUCKDUCKGO, SearchEnginePreset.fromId(null))
        assertEquals(SearchEnginePreset.DUCKDUCKGO, SearchEnginePreset.fromId(""))
        assertEquals(SearchEnginePreset.DUCKDUCKGO, SearchEnginePreset.fromId("   "))
        assertEquals(SearchEnginePreset.DUCKDUCKGO, SearchEnginePreset.fromId("unknown"))

        for (preset in SearchEnginePreset.entries) {
            assertEquals(preset, SearchEnginePreset.fromId(preset.id))
            assertEquals(preset, SearchEnginePreset.fromId(preset.id.uppercase()))
        }
    }

    @Test
    fun buildSearchUrl_formatsEveryBuiltInPresetWithUrlEncoding() {
        val query = "  kotlin & compose+2026  "
        val encoded = "kotlin+%26+compose%2B2026"

        assertEquals(
            "https://duckduckgo.com/?q=$encoded",
            FocalBrowserSettings(searchEngine = SearchEnginePreset.DUCKDUCKGO).buildSearchUrl(query),
        )
        assertEquals(
            "https://www.google.com/search?q=$encoded",
            FocalBrowserSettings(searchEngine = SearchEnginePreset.GOOGLE).buildSearchUrl(query),
        )
        assertEquals(
            "https://www.google.com/search?udm=50&q=$encoded",
            FocalBrowserSettings(searchEngine = SearchEnginePreset.GOOGLE_AI).buildSearchUrl(query),
        )
        assertEquals(
            "https://kagi.com/search?q=$encoded",
            FocalBrowserSettings(searchEngine = SearchEnginePreset.KAGI).buildSearchUrl(query),
        )
        assertEquals(
            "https://search.brave.com/search?q=$encoded",
            FocalBrowserSettings(searchEngine = SearchEnginePreset.BRAVE).buildSearchUrl(query),
        )
        assertEquals(
            "https://www.perplexity.ai/search?q=$encoded",
            FocalBrowserSettings(searchEngine = SearchEnginePreset.PERPLEXITY).buildSearchUrl(query),
        )
    }

    @Test
    fun buildSearchUrl_acceptsValidCustomHttpAndHttpsTemplates() {
        val httpsSettings = FocalBrowserSettings(
            searchEngine = SearchEnginePreset.CUSTOM,
            customSearchTemplate = "  https://search.nixos.org/packages?channel=unstable&query=%s  ",
        )
        assertEquals(
            "https://search.nixos.org/packages?channel=unstable&query=ripgrep+rust",
            httpsSettings.buildSearchUrl("ripgrep rust"),
        )

        val httpSettings = FocalBrowserSettings(
            searchEngine = SearchEnginePreset.CUSTOM,
            customSearchTemplate = "http://localhost:8080/search?filter=%20&q=%s",
        )
        assertEquals(
            "http://localhost:8080/search?filter=%20&q=focal+browser",
            httpSettings.buildSearchUrl("focal browser"),
        )
    }

    @Test
    fun buildSearchUrl_rejectsUnsafeOrMalformedCustomTemplatesAndFallsBackToDuckDuckGo() {
        val invalidTemplates = listOf(
            "",
            "   ",
            "javascript:alert('%s')",
            "JAVASCRIPT:void('%s')",
            "file:///etc/passwd?q=%s",
            "data:text/html,%s",
            "https://example.com/search",
            "http://example.com/search?q=fixed",
            "https://example.com/search?q=%s trailing",
            "https://example.com/search?q=%s\ninjected",
            "example.com/search?q=%s",
        )

        for (template in invalidTemplates) {
            assertFalse("Template should be rejected: $template", FocalBrowserSettings.isValidCustomTemplate(template))
            val settings = FocalBrowserSettings(
                searchEngine = SearchEnginePreset.CUSTOM,
                customSearchTemplate = template,
            )
            assertEquals(
                "Invalid custom template '$template' must fall back to DuckDuckGo",
                "https://duckduckgo.com/?q=hello+world",
                settings.buildSearchUrl("hello world"),
            )
        }
    }

    @Test
    fun store_persistsAndRestoresAllBrowserSettings() {
        val prefs = InMemorySharedPreferences()
        val store = FocalSettingsStore(prefs)

        assertEquals(FocalBrowserSettings(), store.read())

        val updated = FocalBrowserSettings(
            searchEngine = SearchEnginePreset.KAGI,
            customSearchTemplate = "https://searx.example.org/search?q=%s",
            globalPrivacyControlEnabled = false,
            biometricPrivateLockEnabled = true,
            fullHideBottomBarOnScroll = false,
            autoCheckUpdatesEnabled = false,
        )
        store.write(updated)
        assertEquals(updated, store.read())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            (values[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = values?.toMutableSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals += key
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) values.clear()
                for (key in removals) values.remove(key)
                values.putAll(staged)
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
