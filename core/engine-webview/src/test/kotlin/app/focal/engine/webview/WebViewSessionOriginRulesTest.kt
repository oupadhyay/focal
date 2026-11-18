package app.focal.engine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewSessionOriginRulesTest {

    @Test
    fun `ALLOWED_BRIDGE_ORIGIN_RULES uses valid WebViewCompat wildcard syntax`() {
        // Regression guard: Chromium OriginMatcher throws IllegalArgumentException on "https://*" / "http://*"
        // and requires "*" when registering WebMessageListeners across arbitrary web origins.
        assertEquals(setOf("*"), WebViewSession.ALLOWED_BRIDGE_ORIGIN_RULES)
        for (rule in WebViewSession.ALLOWED_BRIDGE_ORIGIN_RULES) {
            assertFalse("Illegal bare scheme wildcard: $rule", rule == "https://*" || rule == "http://*")
        }
    }

    @Test
    fun `isAllowedNavigationUrl permits http, https, and about blank while blocking unsafe schemes`() {
        assertTrue(WebViewSession.isAllowedNavigationUrl("https://google.com"))
        assertTrue(WebViewSession.isAllowedNavigationUrl("https://www.google.com/search?q=focal"))
        assertTrue(WebViewSession.isAllowedNavigationUrl("http://localhost:8080/test"))
        assertTrue(WebViewSession.isAllowedNavigationUrl("about:blank"))

        assertFalse(WebViewSession.isAllowedNavigationUrl(""))
        assertFalse(WebViewSession.isAllowedNavigationUrl("   "))
        assertFalse(WebViewSession.isAllowedNavigationUrl("javascript:alert(1)"))
        assertFalse(WebViewSession.isAllowedNavigationUrl("file:///etc/hosts"))
        assertFalse(WebViewSession.isAllowedNavigationUrl("content://media/external/images"))
        assertFalse(WebViewSession.isAllowedNavigationUrl("intent://scan/#Intent;end"))
    }
}
