package app.focal.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalAppearanceStoreTest {

    @Test
    fun fromId_defaultsToDarkForNullBlankOrUnknownValues() {
        assertEquals(AppChromeTheme.DARK, AppChromeTheme.fromId(null))
        assertEquals(AppChromeTheme.DARK, AppChromeTheme.fromId(""))
        assertEquals(AppChromeTheme.DARK, AppChromeTheme.fromId("unknown"))
        assertEquals(AppChromeTheme.LIGHT, AppChromeTheme.fromId("light"))
        assertEquals(AppChromeTheme.LIGHT, AppChromeTheme.fromId("LIGHT"))
        assertEquals(AppChromeTheme.SYSTEM, AppChromeTheme.fromId("system"))
    }

    @Test
    fun resolveDarkUi_respectsDarkLightAndSystemModes() {
        assertTrue(AppChromeTheme.DARK.resolveDarkUi(isSystemInDarkTheme = false))
        assertTrue(AppChromeTheme.DARK.resolveDarkUi(isSystemInDarkTheme = true))

        assertFalse(AppChromeTheme.LIGHT.resolveDarkUi(isSystemInDarkTheme = false))
        assertFalse(AppChromeTheme.LIGHT.resolveDarkUi(isSystemInDarkTheme = true))

        assertFalse(AppChromeTheme.SYSTEM.resolveDarkUi(isSystemInDarkTheme = false))
        assertTrue(AppChromeTheme.SYSTEM.resolveDarkUi(isSystemInDarkTheme = true))
    }
}
