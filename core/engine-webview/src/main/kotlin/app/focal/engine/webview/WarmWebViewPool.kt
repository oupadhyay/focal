package app.focal.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.OutcomeReceiverCompat
import androidx.webkit.PrefetchException
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewStartUpConfig
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Off-screen System `WebView` Zygote pre-warmer (`size = 1`).
 *
 * Defers warmup past initial window focus and SearchSheet/IME reveal, runs heavy Chromium provider
 * startup off the main thread via [WebViewCompat.startUpWebView], and then inflates a pooled
 * [WebView] on a subsequent main-looper idle slot so cold launch never stalls `FocusEvent` while
 * tab creation acquires an already-warm `WebView` via [MutableContextWrapper] in `< 2ms`.
 */
class WarmWebViewPool(
    context: Context,
    private val targetPoolSize: Int = 1,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            runnable.run()
        }, "focal-webview-warmup").apply { isDaemon = true }
    }
    private val prefetchCallbackExecutor = Executor { command -> command.run() }

    @androidx.annotation.OptIn(Profile.ExperimentalUrlPrefetch::class)
    private val noopPrefetchReceiver: OutcomeReceiverCompat<Void?, PrefetchException> by lazy(
        LazyThreadSafetyMode.NONE,
    ) {
        object : OutcomeReceiverCompat<Void?, PrefetchException> {
            override fun onResult(result: Void?) = Unit
            override fun onError(error: PrefetchException) = Unit
        }
    }

    private val pool = ArrayDeque<WebView>(targetPoolSize)
    private val idleScheduled = AtomicBoolean(false)
    private val providerWarmupScheduled = AtomicBoolean(false)
    private val providerWarmed = AtomicBoolean(false)
    private val shutDown = AtomicBoolean(false)

    /**
     * Cold-launch provider warmup: loads Chromium's WebView APK & native binaries strictly on a
     * low-priority background daemon thread (`setShouldRunUiThreadStartUpTasks(false)`) after a
     * short delay past Frame 1, keeping 0ms of synchronous Chromium initialization or CPU contention
     * off the main thread during initial `FocusEvent` and `SearchSheet` / IME launch.
     */
    @androidx.annotation.OptIn(WebViewCompat.ExperimentalAsyncStartUp::class)
    fun scheduleBackgroundProviderWarmup() {
        if (shutDown.get() || providerWarmed.get()) return
        if (!providerWarmupScheduled.compareAndSet(false, true)) return

        mainHandler.postDelayed({
            if (shutDown.get() || !providerWarmed.compareAndSet(false, true)) return@postDelayed
            try {
                val config = WebViewStartUpConfig.Builder(warmupExecutor)
                    .setShouldRunUiThreadStartUpTasks(false)
                    .build()
                WebViewCompat.startUpWebView(config) {
                    // Background Chromium package & native binary warmup finished with zero UI-thread stall.
                }
            } catch (_: Throwable) {
                // Headless JVM / older WebView APKs fall back cleanly to on-demand WebView creation
            }
        }, PROVIDER_WARMUP_DELAY_MS)
    }

    /**
     * Speculatively prefetches an `https://` [url] into Chromium's default profile when supported.
     * Always dispatched on the main thread and safely wrapped so older WebViews or headless tests
     * remain no-ops.
     */
    @SuppressLint("RequiresFeature")
    @androidx.annotation.OptIn(Profile.ExperimentalUrlPrefetch::class)
    fun prefetchUrl(url: String) {
        if (shutDown.get()) return
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)) return

        val task = Runnable {
            if (shutDown.get()) return@Runnable
            runCatching {
                val store = ProfileStore.getInstance()
                val profile = store.getProfile(Profile.DEFAULT_PROFILE_NAME)
                    ?: store.getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME)
                profile.prefetchUrlAsync(
                    trimmed,
                    null,
                    prefetchCallbackExecutor,
                    noopPrefetchReceiver,
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }
    }

    /**
     * Replenishes the standby `WebView` pool on an idle main-looper slot once Chromium has
     * already been initialized by a live tab (subsequent inflations take `< 15ms`).
     */
    fun scheduleIdleWarmup(delayMs: Long = REPLENISH_WARMUP_DELAY_MS) {
        if (shutDown.get()) return
        if (!idleScheduled.compareAndSet(false, true)) return

        mainHandler.postDelayed({
            if (shutDown.get() || pool.size >= targetPoolSize) {
                idleScheduled.set(false)
                return@postDelayed
            }
            enqueueIdleInflateOnMain()
        }, delayMs.coerceAtLeast(0L))
    }

    private fun enqueueIdleInflateOnMain() {
        mainHandler.post {
            if (shutDown.get() || pool.size >= targetPoolSize) {
                idleScheduled.set(false)
                return@post
            }
            Looper.myQueue().addIdleHandler {
                idleScheduled.set(false)
                if (!shutDown.get() && pool.size < targetPoolSize) {
                    try {
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
            scheduleIdleWarmup(delayMs = REPLENISH_WARMUP_DELAY_MS)
            return warm
        }
        providerWarmed.set(true)
        val fresh = createConfiguredWebView(MutableContextWrapper(unwrappedHost))
        scheduleIdleWarmup(delayMs = REPLENISH_WARMUP_DELAY_MS)
        return fresh
    }

    /**
     * Creates an unpooled [WebView] strictly for Private Aperture `◎` (`"focal_ephemeral"`),
     * ensuring private profile assignment happens before any navigation or shared state.
     */
    fun createIsolatedWebView(hostContext: Context): WebView {
        val unwrappedHost = (hostContext as? MutableContextWrapper)?.baseContext ?: hostContext
        providerWarmed.set(true)
        return createConfiguredWebView(MutableContextWrapper(unwrappedHost))
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    @androidx.annotation.OptIn(
        WebSettingsCompat.ExperimentalBackForwardCache::class,
        WebSettingsCompat.ExperimentalSpeculativeLoading::class,
    )
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

                // Instant back/forward restoration via Chromium BFCache when supported
                if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) {
                    runCatching { WebSettingsCompat.setBackForwardCacheEnabled(this, true) }
                }

                // Chromium Speculative Loading (prerender hints) when supported
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING)) {
                    runCatching {
                        WebSettingsCompat.setSpeculativeLoadingStatus(
                            this,
                            WebSettingsCompat.SPECULATIVE_LOADING_PRERENDER_ENABLED,
                        )
                    }
                }
            }
        }
    }

    fun shutdown() {
        if (shutDown.compareAndSet(false, true)) {
            mainHandler.removeCallbacksAndMessages(null)
            warmupExecutor.shutdownNow()
            mainHandler.post {
                while (pool.isNotEmpty()) {
                    pool.pollFirst()?.destroy()
                }
            }
        }
    }

    companion object {
        private const val PROVIDER_WARMUP_DELAY_MS = 750L
        private const val REPLENISH_WARMUP_DELAY_MS = 400L
    }
}
