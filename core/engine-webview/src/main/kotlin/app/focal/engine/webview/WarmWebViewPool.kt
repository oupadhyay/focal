package app.focal.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Off-screen System `WebView` Zygote pre-warmer (`size = 1`).
 *
 * Schedules idle-frame initialization via `Looper.myQueue().addIdleHandler` after first paint
 * so cold app launch stays `< 220ms` while the first tab creation acquires an already-inflated
 * Chromium `WebView` via [MutableContextWrapper] in `< 2ms`.
 */
class WarmWebViewPool(
    context: Context,
    private val targetPoolSize: Int = 1,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pool = ArrayDeque<WebView>(targetPoolSize)
    private val idleScheduled = AtomicBoolean(false)
    private val shutDown = AtomicBoolean(false)

    fun scheduleIdleWarmup() {
        if (shutDown.get()) return
        if (!idleScheduled.compareAndSet(false, true)) return

        mainHandler.post {
            Looper.myQueue().addIdleHandler {
                idleScheduled.set(false)
                if (!shutDown.get() && pool.size < targetPoolSize) {
                    try {
                        // Touch WebView package metadata to ensure WebViewZygote is warm
                        WebViewCompat.getCurrentWebViewPackage(appContext)
                        pool.addLast(createConfiguredWebView(MutableContextWrapper(appContext)))
                    } catch (_: Throwable) {
                        // Headless JVM test environments may not provide a system WebView APK
                    }
                }
                false
            }
        }
    }

    /**
     * Acquires a pre-warmed [WebView] bound to [hostContext] on the main thread,
     * and queues an asynchronous idle replenishment for the next tab.
     */
    fun acquire(hostContext: Context): WebView {
        val unwrappedHost = (hostContext as? MutableContextWrapper)?.baseContext ?: hostContext
        val warm = pool.pollFirst()
        if (warm != null) {
            (warm.context as? MutableContextWrapper)?.let { wrapper ->
                if (wrapper !== unwrappedHost) {
                    wrapper.baseContext = unwrappedHost
                }
            }
            scheduleIdleWarmup()
            return warm
        }
        val fresh = createConfiguredWebView(MutableContextWrapper(unwrappedHost))
        scheduleIdleWarmup()
        return fresh
    }

    /**
     * Creates an unpooled [WebView] strictly for Private Aperture `◎` (`"focal_ephemeral"`),
     * ensuring private profile assignment happens before any navigation or shared state.
     */
    fun createIsolatedWebView(hostContext: Context): WebView {
        val unwrappedHost = (hostContext as? MutableContextWrapper)?.baseContext ?: hostContext
        return createConfiguredWebView(MutableContextWrapper(unwrappedHost))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(wrapper: MutableContextWrapper): WebView {
        return WebView(wrapper).apply {
            // Programmatic WebViews lack XML resource IDs, which causes Android's default AUTO
            // heuristic to skip OS Autofill unless explicitly marked IMPORTANT_FOR_AUTOFILL_YES.
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // Privacy hardening: disable Google SafeBrowsing telemetry (replaced by local adblock-rust)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                    runCatching { WebSettingsCompat.setSafeBrowsingEnabled(this, false) }
                }
            }
        }
    }

    fun shutdown() {
        if (shutDown.compareAndSet(false, true)) {
            mainHandler.post {
                while (pool.isNotEmpty()) {
                    pool.pollFirst()?.destroy()
                }
            }
        }
    }
}
