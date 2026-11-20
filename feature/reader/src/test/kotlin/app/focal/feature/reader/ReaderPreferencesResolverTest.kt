package app.focal.feature.reader

import app.focal.design.ReaderPaperPalette
import app.focal.engine.api.OriginOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPreferencesResolverTest {

    @Test
    fun resolve_prefersDomainOverridesOverGlobalDefaults() {
        val global = ReaderPreferences(
            theme = ReaderPaperPalette.Obsidian,
            useEditorialSerif = true,
            fontSizeSp = 19,
            lineHeightMultiplier = 1.75f,
        )
        val domainOverrides = OriginOverrides(
            origin = "stratechery.com",
            autoReaderMode = true,
            readerThemeId = ReaderPaperPalette.Paper.id,
            readerUseSerif = false,
            readerFontSizeSp = 16,
            readerLineHeight = 1.45f,
        )

        val resolved = ReaderSitePreferencesResolver.resolve(domainOverrides, global)
        assertEquals(ReaderPaperPalette.Paper, resolved.theme)
        assertFalse(resolved.useEditorialSerif)
        assertEquals(16, resolved.fontSizeSp)
        assertEquals(1.45f, resolved.lineHeightMultiplier, 0.001f)
    }

    @Test
    fun resolve_fallsBackToGlobalDefaultsForUnsetDomainFields() {
        val global = ReaderPreferences(
            theme = ReaderPaperPalette.Sage,
            useEditorialSerif = true,
            fontSizeSp = 20,
            lineHeightMultiplier = 1.75f,
        )
        val freshDomain = OriginOverrides(origin = "example.com")

        val resolved = ReaderSitePreferencesResolver.resolve(freshDomain, global)
        assertEquals(ReaderPaperPalette.Sage, resolved.theme)
        assertTrue(resolved.useEditorialSerif)
        assertEquals(20, resolved.fontSizeSp)
        assertEquals(1.75f, resolved.lineHeightMultiplier, 0.001f)
    }

    @Test
    fun resolve_clampsCorruptBoundsAndHandlesUnknownThemeId() {
        val corrupt = OriginOverrides(
            origin = "corrupt.example",
            readerThemeId = "nonexistent-theme",
            readerFontSizeSp = 99,
            readerLineHeight = 0.5f,
        )
        val resolved = ReaderSitePreferencesResolver.resolve(corrupt)
        assertEquals(ReaderPaperPalette.Sepia, resolved.theme)
        assertEquals(ReaderSitePreferencesResolver.MAX_FONT_SIZE_SP, resolved.fontSizeSp)
        assertEquals(ReaderSitePreferencesResolver.MIN_LINE_HEIGHT, resolved.lineHeightMultiplier, 0.001f)
    }

    @Test
    fun applyToOverrides_preservesOtherSiteOverridesAndPersistsReaderSettings() {
        val base = OriginOverrides(
            origin = "stratechery.com",
            blockAdsAndTrackers = true,
            forceDarkWebSurface = true,
            pageZoomPercent = 110,
            autoReaderMode = true,
        )
        val prefs = ReaderPreferences(
            theme = ReaderPaperPalette.Alabaster,
            useEditorialSerif = true,
            fontSizeSp = 18,
            lineHeightMultiplier = 1.60f,
        )
        val updated = ReaderSitePreferencesResolver.applyToOverrides(base, prefs)
        assertEquals("stratechery.com", updated.origin)
        assertTrue(updated.forceDarkWebSurface)
        assertEquals(110, updated.pageZoomPercent)
        assertTrue(updated.autoReaderMode)
        assertEquals(ReaderPaperPalette.Alabaster.id, updated.readerThemeId)
        assertEquals(true, updated.readerUseSerif)
        assertEquals(18, updated.readerFontSizeSp)
        assertEquals(1.60f, updated.readerLineHeight ?: 0f, 0.001f)
    }

    @Test
    fun shouldAutoOpenReader_respectsEligibilityLoadingAndSessionDismissal() {
        val url = "https://stratechery.com/2026/nvidia-earnings/"
        assertTrue(
            ReaderSitePreferencesResolver.shouldAutoOpenReader(
                autoReaderEnabled = true,
                isReaderEligible = true,
                isPageLoading = false,
                currentUrl = url,
                dismissedUrlsInSession = emptySet(),
                isOverlayCurrentlyOpen = false,
            ),
        )
        // Once user taps Done on this article, do not auto-reopen for the same URL
        assertFalse(
            ReaderSitePreferencesResolver.shouldAutoOpenReader(
                autoReaderEnabled = true,
                isReaderEligible = true,
                isPageLoading = false,
                currentUrl = url,
                dismissedUrlsInSession = setOf(url),
                isOverlayCurrentlyOpen = false,
            ),
        )
        // Navigating to a different article on the same site auto-opens again
        assertTrue(
            ReaderSitePreferencesResolver.shouldAutoOpenReader(
                autoReaderEnabled = true,
                isReaderEligible = true,
                isPageLoading = false,
                currentUrl = "https://stratechery.com/2026/next-article/",
                dismissedUrlsInSession = setOf(url),
                isOverlayCurrentlyOpen = false,
            ),
        )
        // Never auto-open while still loading or while a modal sheet is open
        assertFalse(
            ReaderSitePreferencesResolver.shouldAutoOpenReader(
                autoReaderEnabled = true,
                isReaderEligible = true,
                isPageLoading = true,
                currentUrl = url,
                dismissedUrlsInSession = emptySet(),
                isOverlayCurrentlyOpen = false,
            ),
        )
    }
}
