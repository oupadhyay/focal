package app.focal.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import app.focal.adblock.AdblockController
import app.focal.engine.api.BrowserEngine
import app.focal.engine.api.EngineSession
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Production System `WebView` [BrowserEngine] implementation.
 *
 * Coordinates:
 * - [WarmWebViewPool] Zygote idle pre-warming (`< 220ms` cold launch).
 * - [AdblockController] native Rust filter matching across all sessions.
 * - Ephemeral Private Aperture (`◎`) `ProfileStore` (`"focal_ephemeral"`) lifecycle:
 *   automatically wipes cookies, `localStorage`, IndexedDB, and HTTP cache the moment
 *   the last private session is disposed, without ever touching the default user profile.
 */
class WebViewBrowserEngine(
    context: Context,
    private val adblockController: AdblockController,
) : BrowserEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmPool = WarmWebViewPool(appContext, targetPoolSize = 1)
    private val activePrivateSessionIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun warmup() {
        warmPool.scheduleIdleWarmup()
    }

    @SuppressLint("RequiresFeature")
    override fun createSession(isPrivate: Boolean, spaceId: String?): EngineSession {
        if (isPrivate && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runOnMainSyncOrPost {
                ProfileStore.getInstance().getOrCreateProfile(WebViewSession.EPHEMERAL_PROFILE_NAME)
            }
        }

        val session = WebViewSession(
            appContext = appContext,
            pool = warmPool,
            adblockController = adblockController,
            isPrivate = isPrivate,
            spaceId = spaceId,
            onSessionDisposed = ::handleSessionDisposed,
        )

        if (isPrivate) {
            activePrivateSessionIds.add(session.sessionId)
        }
        return session
    }

    private fun handleSessionDisposed(session: WebViewSession) {
        if (session.isPrivate) {
            activePrivateSessionIds.remove(session.sessionId)
            if (activePrivateSessionIds.isEmpty()) {
                clearEphemeralData()
            }
        }
    }

    @SuppressLint("RequiresFeature")
    override fun clearEphemeralData() {
        runOnMainSyncOrPost {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    val store = ProfileStore.getInstance()
                    val ephemeral = store.getProfile(WebViewSession.EPHEMERAL_PROFILE_NAME)
                    ephemeral?.cookieManager?.removeAllCookies(null)
                    ephemeral?.webStorage?.deleteAllData()
                    store.deleteProfile(WebViewSession.EPHEMERAL_PROFILE_NAME)
                } catch (_: IllegalStateException) {
                    // Profile may still have a tearing-down WebView; cookies/storage already cleared above
                }
            }
            // Pre-MULTI_PROFILE fallback WebViews disable domStorageEnabled, databaseEnabled,
            // cache, and document.cookie per-instance in WebViewSession, so we NEVER touch global
            // WebStorage.getInstance() or CookieManager.getInstance() here (preserving regular tabs).
        }
    }

    override fun shutdown() {
        warmPool.shutdown()
        if (activePrivateSessionIds.isNotEmpty()) {
            activePrivateSessionIds.clear()
            clearEphemeralData()
        }
    }

    private fun runOnMainSyncOrPost(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
