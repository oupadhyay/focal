package app.focal.engine.webview

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePlatformFeaturesTest {

    // -------------------------------------------------------------------------
    // OAuthUrlDetector
    // -------------------------------------------------------------------------

    @Test
    fun `OAuthUrlDetector identifies known IdP domains and SSO subdomains`() {
        val authUrls = listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=123",
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            "https://appleid.apple.com/auth/authorize?response_type=code",
            "https://focal.us.auth0.com/u/login",
            "https://auth0.com/authorize",
            "https://acme.okta.com/oauth2/default/v1/authorize",
            "https://sso.company.example/saml/launch",
            "https://corp.sso.example.org/app",
            "https://oauth.provider.example/token",
            "https://login.example.org/step1",
        )
        for (url in authUrls) {
            assertTrue("Expected auth URL to be detected: $url", OAuthUrlDetector.isAuthOrOAuthUrl(url))
        }
    }

    @Test
    fun `OAuthUrlDetector identifies OAuth, OIDC, and login paths plus PKCE query params`() {
        val authUrls = listOf(
            "https://github.com/login",
            "https://github.com/login/oauth/authorize?client_id=abc",
            "https://example.com/oauth/authorize",
            "https://example.com/oauth2/v2/auth",
            "https://example.com/v1/authorize",
            "https://example.com/signin",
            "https://example.com/sign-in",
            "https://example.com/users/sign_in",
            "https://example.com/account/login.php",
            "https://example.com/auth/realms/main/protocol/openid-connect/auth",
            "https://custom.example.com/connect?client_id=app123&redirect_uri=https%3A%2F%2Fapp.test%2Fcb",
            "https://custom.example.com/start?client_id=app123&code_challenge=xyz&code_challenge_method=S256",
        )
        for (url in authUrls) {
            assertTrue("Expected OAuth/login endpoint to be detected: $url", OAuthUrlDetector.isAuthOrOAuthUrl(url))
        }
    }

    @Test
    fun `OAuthUrlDetector does not flag normal articles, documentation, or invalid URLs`() {
        val nonAuthUrls = listOf(
            "https://arstechnica.com/gadgets/2025/02/android-webview-performance/",
            "https://github.com/oupadhyay/focal",
            "https://stratechery.com/2025/browser-architecture-deep-dive/",
            "https://en.wikipedia.org/wiki/OAuth",
            "https://developer.example.com/docs/oauth",
            "https://developer.example.com/en-US/docs/login",
            "https://example.com/blog/how-sso-and-pkce-work",
            "https://example.com/posts/signing-in-safely",
            "https://example.com/search?q=oauth+login",
            "https://example.com/item?client_id=only_one_param",
            "",
            "   ",
            "about:blank",
            "javascript:alert(1)",
            "mailto:help@okta.com",
            "not a valid url",
        )
        for (url in nonAuthUrls) {
            assertFalse("Expected non-auth URL not to be flagged: $url", OAuthUrlDetector.isAuthOrOAuthUrl(url))
        }
    }

    // -------------------------------------------------------------------------
    // HistoryFrameStore & BoundedHistoryRing
    // -------------------------------------------------------------------------

    @Test
    fun `HistoryFrameStore maintains LIFO ordering, ring eviction, and clear semantics`() {
        val store = HistoryFrameStore(maxEntries = 3)
        assertNull(store.peekBack())
        assertNull(store.popBack())
        assertNull(store.peekForward())
        assertNull(store.popForward())

        val f1 = allocateStubBitmap()
        val f2 = allocateStubBitmap()
        val f3 = allocateStubBitmap()
        val f4 = allocateStubBitmap()

        store.pushBack(f1)
        store.pushBack(f2)
        store.pushBack(f3)
        assertEquals(3, store.backSize)
        assertTrue(store.peekBack() === f3)

        // Pushing a 4th frame evicts the oldest (f1)
        store.pushBack(f4)
        assertEquals(3, store.backSize)
        assertTrue(store.popBack() === f4)
        assertTrue(store.popBack() === f3)
        assertTrue(store.popBack() === f2)
        assertNull(store.popBack())
        assertEquals(0, store.backSize)

        // Forward stack + clearForward preserves back stack
        store.pushBack(f1)
        store.pushForward(f2)
        store.pushForward(f3)
        assertEquals(1, store.backSize)
        assertEquals(2, store.forwardSize)
        assertTrue(store.peekForward() === f3)

        store.clearForward()
        assertEquals(0, store.forwardSize)
        assertNull(store.peekForward())
        assertEquals(1, store.backSize)
        assertTrue(store.peekBack() === f1)

        // clearAll empties both stacks
        store.pushForward(f4)
        store.clearAll()
        assertEquals(0, store.backSize)
        assertEquals(0, store.forwardSize)
        assertNull(store.peekBack())
        assertNull(store.peekForward())
    }

    @Test
    fun `BoundedHistoryRing evicts oldest forward entries beyond capacity`() {
        val ring = BoundedHistoryRing<String>(maxEntries = 2)
        ring.pushForward("page-1")
        ring.pushForward("page-2")
        ring.pushForward("page-3")

        assertEquals(2, ring.forwardSize)
        assertEquals("page-3", ring.peekForward())
        assertEquals("page-3", ring.popForward())
        assertEquals("page-2", ring.popForward())
        assertNull(ring.popForward())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `HistoryFrameStore rejects non-positive maxEntries`() {
        HistoryFrameStore(maxEntries = 0)
    }

    // -------------------------------------------------------------------------
    // ElementZapperScripts: Selector Sanitization & CSS Generation
    // -------------------------------------------------------------------------

    @Test
    fun `sanitizeSelector preserves valid CSS selectors and strips or rejects dangerous payloads`() {
        assertEquals("#cookie-banner", ElementZapperScripts.sanitizeSelector("  #cookie-banner  "))
        assertEquals(
            "div.promo-card > aside.sticky:nth-of-type(2)",
            ElementZapperScripts.sanitizeSelector("div.promo-card > aside.sticky:nth-of-type(2)"),
        )
        assertEquals(
            "section[data-ad=\"top\"]",
            ElementZapperScripts.sanitizeSelector("section[data-ad=\"top\"]"),
        )

        // Dangerous HTML tag breakouts stripped safely
        assertEquals(
            "#ad-slot",
            ElementZapperScripts.sanitizeSelector("#ad-slot</style><script>alert(1)</script>"),
        )
        assertEquals(
            ".banner",
            ElementZapperScripts.sanitizeSelector(".banner`"),
        )

        // Pure tag breakout, CSS rule injection, at-rules, root selectors, unbalanced brackets rejected
        assertNull(ElementZapperScripts.sanitizeSelector("</style>"))
        assertNull(ElementZapperScripts.sanitizeSelector("#ad { display: block; }"))
        assertNull(ElementZapperScripts.sanitizeSelector("@import url(https://evil.test/a.css)"))
        assertNull(ElementZapperScripts.sanitizeSelector("div; body"))
        assertNull(ElementZapperScripts.sanitizeSelector("javascript:alert(1)"))
        assertNull(ElementZapperScripts.sanitizeSelector("div[data-x='unclosed]"))
        assertNull(ElementZapperScripts.sanitizeSelector("html"))
        assertNull(ElementZapperScripts.sanitizeSelector("body"))
        assertNull(ElementZapperScripts.sanitizeSelector("*"))
        assertNull(ElementZapperScripts.sanitizeSelector(":root"))
        assertNull(ElementZapperScripts.sanitizeSelector("   "))
    }

    @Test
    fun `buildHiddenSelectorsCssScript injects focal-element-zapper-style and escapes quotes safely`() {
        val script = ElementZapperScripts.buildHiddenSelectorsCssScript(
            listOf(
                "#cookie-banner",
                "#cookie-banner", // duplicate should be deduped
                "div[data-slot=\"promo\"]",
                "#evil</style><script>alert(1)</script>",
                "body { color: red; }", // rejected
            ),
        )

        assertTrue(script.contains(ElementZapperScripts.ZAPPER_STYLE_ID))
        assertTrue(script.contains("display: none !important; visibility: hidden !important;"))
        assertTrue(script.contains("#cookie-banner"))
        assertTrue(script.contains("div[data-slot=\\\"promo\\\"]"))
        assertTrue(script.contains("#evil"))
        assertFalse("Must never contain raw </style>", script.contains("</style>", ignoreCase = true))
        assertFalse("Must never contain <script>", script.contains("<script", ignoreCase = true))
        assertFalse("Must never contain injected CSS rule", script.contains("color: red"))
        assertFalse("Must never contain unescaped backticks", script.contains("`"))

        val emptyScript = ElementZapperScripts.buildHiddenSelectorsCssScript(emptyList())
        assertTrue(emptyScript.contains(ElementZapperScripts.ZAPPER_STYLE_ID))
        assertTrue(emptyScript.contains("style.textContent = \"\""))
    }

    @Test
    fun `buildZapperModeToggleScript installs and cleans up capture overlay and bridge dispatch`() {
        val enableScript = ElementZapperScripts.buildZapperModeToggleScript(active = true)
        assertTrue(enableScript.contains("window.__focalZapperActive = true"))
        assertTrue(enableScript.contains("element_zapped"))
        assertTrue(enableScript.contains("window.focalBridge"))
        assertTrue(enableScript.contains(ElementZapperScripts.ZAPPER_BRIDGE_EVENT_NAME))
        assertTrue(enableScript.contains(":nth-of-type("))
        assertTrue(enableScript.contains("document.addEventListener('click', handlers.onClick, true)"))

        val disableScript = ElementZapperScripts.buildZapperModeToggleScript(active = false)
        assertTrue(disableScript.contains("window.__focalZapperActive = false"))
        assertTrue(disableScript.contains("removeEventListener('click'"))
        assertTrue(disableScript.contains("focal-zapper-highlight"))
    }

    @Test
    fun `GLOBAL_PRIVACY_CONTROL_JS and toggle script gate navigator globalPrivacyControl`() {
        val gpc = ElementZapperScripts.GLOBAL_PRIVACY_CONTROL_JS
        assertTrue(gpc.contains("globalPrivacyControl"))
        assertTrue(gpc.contains("window.__focalGpcEnabled"))

        val enable = ElementZapperScripts.buildGpcToggleScript(true)
        assertTrue(enable.contains("window.__focalGpcEnabled = true"))
        assertTrue(enable.contains("globalPrivacyControl"))

        val disable = ElementZapperScripts.buildGpcToggleScript(false)
        assertTrue(disable.contains("window.__focalGpcEnabled = false"))
    }

    // -------------------------------------------------------------------------
    // ElementZapperScripts: BottomInsetCssScript
    // -------------------------------------------------------------------------

    @Test
    fun `buildBottomInsetCssScript sets root inset variables and guards fullscreen or fixed apps`() {
        val positive = ElementZapperScripts.buildBottomInsetCssScript(72)
        assertTrue(positive.contains(ElementZapperScripts.BOTTOM_INSET_STYLE_ID))
        assertTrue(positive.contains("--focal-bottom-inset: 72px;"))
        assertTrue(positive.contains("scroll-padding-bottom: 72px;"))
        assertTrue(positive.contains("fullscreenElement"))
        assertTrue(positive.contains("overflowY === 'hidden'"))
        assertTrue(positive.contains("html:not(:fullscreen) body:not(:fullscreen)"))
        assertTrue(positive.contains("margin-bottom: 72px !important;"))

        val zero = ElementZapperScripts.buildBottomInsetCssScript(0)
        assertTrue(zero.contains("--focal-bottom-inset: 0px;"))
        assertTrue(zero.contains("scroll-padding-bottom: 0px;"))
        assertFalse("Zero inset must not apply body margin-bottom", zero.contains("margin-bottom:"))

        val negative = ElementZapperScripts.buildBottomInsetCssScript(-48)
        assertTrue(negative.contains("--focal-bottom-inset: 0px;"))
        assertFalse(negative.contains("margin-bottom:"))

        val clampedMax = ElementZapperScripts.buildBottomInsetCssScript(9999)
        assertTrue(clampedMax.contains("--focal-bottom-inset: 600px;"))
        assertTrue(clampedMax.contains("margin-bottom: 600px !important;"))
    }

    private fun allocateStubBitmap(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val instance = allocateInstance.invoke(unsafe, Bitmap::class.java) as Bitmap
        assertNotNull(instance)
        return instance
    }
}
