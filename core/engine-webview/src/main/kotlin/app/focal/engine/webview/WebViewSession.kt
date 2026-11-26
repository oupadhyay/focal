package app.focal.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.focal.adblock.AdblockController
import app.focal.adblock.AdblockWebResponses
import app.focal.adblock.NativeAdblockController
import app.focal.design.FaviconCache
import app.focal.engine.api.ActiveMediaSessionInfo
import app.focal.engine.api.EngineSession
import app.focal.engine.api.EngineSessionEvent
import app.focal.engine.api.EngineSessionState
import app.focal.engine.api.EngineTelemetryState
import app.focal.engine.api.FindInPageState
import app.focal.engine.api.IndexedDomParagraph
import app.focal.engine.api.OriginOverrides
import app.focal.engine.api.PageTranslationPhase
import app.focal.engine.api.PageTranslationStatus
import app.focal.engine.api.ReadableDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * System `WebView` (`androidx.webkit:webkit:1.13+`) implementation of [EngineSession].
 *
 * Key 120Hz & Privacy capabilities:
 * - **`shouldInterceptRequest`**: Wired directly to [AdblockController.checkNetworkRequest] on
 *   Chromium's background network I/O worker thread, returning [AdblockWebResponses]'s zero-alloc
 *   static `200` singleton `WebResourceResponse` when blocked, plus [WebViewAssetLoader] serving
 *   bundled `Allo` and `Switzer` variable fonts with CORS headers.
 * - **`WebViewCompat.addDocumentStartJavaScript`**: Pre-paint cosmetic CSS injection, live
 *   per-origin overrides (`Allo + Switzer` fonts, dark surface), dirty-form tracking
 *   (`window.__focalFormDirty`), and `<video>`/`<audio>` playback rate hooks inside a private
 *   lexical closure (`delete window.focalBridge`).
 * - **Private Aperture `◎` Isolation**: Assigns `"focal_ephemeral"` profile via `ProfileStore`
 *   when `WebViewFeature.MULTI_PROFILE` is supported, and isolates fallback WebViews without
 *   ever touching global user storage.
 * - **Grounded Summarizer & Reader Hooks**: Tags substantive paragraphs with `data-focal-para-id`,
 *   smooth-scrolls & pulses grounded source paragraphs, and extracts structured Reader articles.
 */
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
class WebViewSession internal constructor(
    private val appContext: Context,
    private val pool: WarmWebViewPool,
    private val adblockController: AdblockController,
    override val isPrivate: Boolean,
    override val spaceId: String?,
    private val onSessionDisposed: (WebViewSession) -> Unit,
    override val sessionId: String = UUID.randomUUID().toString(),
) : EngineSession {

    companion object {
        const val EPHEMERAL_PROFILE_NAME = "focal_ephemeral"
        const val LINK_PEEK_PROFILE_NAME = "focal_link_peek"
        const val JS_BRIDGE_NAME = "focalBridge"
        const val ASSET_LOADER_DOMAIN = "appassets.androidplatform.net"

        // WebViewCompat.addWebMessageListener and addDocumentStartJavaScript only accept "*"
        // for arbitrary web hosts (bare scheme wildcards without a domain are rejected by
        // Chromium's OriginMatcher with IllegalArgumentException). All bridge callbacks strictly
        // enforce sourceOrigin.scheme in {"https", "http"} and a non-blank sourceOrigin.host.
        val ALLOWED_BRIDGE_ORIGIN_RULES: Set<String> = setOf("*")

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private const val HISTORY_PREVIEW_MAX_WIDTH_PX = 420
        private const val HISTORY_SNAPSHOT_DEDUP_WINDOW_MS = 400L
        private const val COSMETIC_CSS_CACHE_MAX_ENTRIES = 128

        /**
         * Permits only `https://`, `http://`, and `about:blank`, blocking `file://`,
         * `content://`, `javascript:`, `data:`, and custom intent schemes.
         */
        fun isAllowedNavigationUrl(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.equals("about:blank", ignoreCase = true)) return true
            return trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
        }
    }

    private data class PendingImageAnchorHit(
        val imageExtra: String?,
        val touchXRatio: Float,
        val touchYRatio: Float,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val weakSelf = WeakReference(this)
    private val hrefMessageHandler = Handler(
        Looper.getMainLooper(),
        Handler.Callback { msg ->
            val session = weakSelf.get() ?: return@Callback true
            if (session.disposed.get()) return@Callback true
            val hitMeta = msg.obj as? PendingImageAnchorHit
            val bundle = msg.data
            val hrefUrl = bundle?.getString("url")?.trim().orEmpty()
            val anchorTitle = bundle?.getString("title")?.trim()?.takeIf { it.isNotBlank() }
            val srcUrl = bundle?.getString("src")?.trim().orEmpty()
            val fallbackImageUrl = hitMeta?.imageExtra?.trim().orEmpty()
            val resolvedImageUrl = when {
                srcUrl.isNotBlank() && isAllowedNavigationUrl(srcUrl) -> srcUrl
                fallbackImageUrl.isNotBlank() && isAllowedNavigationUrl(fallbackImageUrl) -> fallbackImageUrl
                else -> null
            }
            val resolvedLinkUrl = when {
                hrefUrl.isNotBlank() && isAllowedNavigationUrl(hrefUrl) -> hrefUrl
                resolvedImageUrl != null -> resolvedImageUrl
                else -> null
            }
            if (resolvedLinkUrl != null) {
                session._events.tryEmit(
                    EngineSessionEvent.AnchorLongPressed(
                        rawUrl = resolvedLinkUrl,
                        anchorTitle = anchorTitle,
                        touchXRatio = hitMeta?.touchXRatio ?: session.lastTouchXRatio,
                        touchYRatio = hitMeta?.touchYRatio ?: session.lastTouchYRatio,
                        imageUrl = resolvedImageUrl,
                    ),
                )
            }
            true
        },
    )
    private val disposed = AtomicBoolean(false)
    private val sessionBlockedCount = AtomicLong(0L)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val translationGeneration = AtomicLong(0L)
    private var activeTranslationJob: Job? = null
    private val translationEngine: OnDeviceTranslationEngine by lazy { MlKitTranslationEngine() }
    private val documentScriptHandlers = mutableListOf<ScriptHandler>()
    private var gpcScriptHandler: ScriptHandler? = null
    private val historyFrames = HistoryFrameStore()
    private val cosmeticCssCache = LruCache<String, String>(COSMETIC_CSS_CACHE_MAX_ENTRIES)
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_LOADER_DOMAIN)
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(appContext.applicationContext))
        .build()

    @Volatile
    private var currentOverrides = OriginOverrides(origin = "")

    @Volatile
    private var defaultMobileUserAgent: String = ""

    @Volatile
    private var gpcEnabled: Boolean = true

    @Volatile
    private var bottomChromeInsetPx: Int = 0
    private var lastAppliedBottomInsetPx: Int = -1
    private var hasInjectedLiveOverrides: Boolean = false
    private var lastHistorySnapshotUrl: String = ""
    private var lastHistorySnapshotAtMs: Long = 0L

    private var lastTouchXRatio: Float = 0.5f
    private var lastTouchYRatio: Float = 0.5f
    private var lastScrollY: Int = 0

    private val _state = MutableStateFlow(
        EngineSessionState(
            sessionId = sessionId,
            isPrivate = isPrivate,
            spaceId = spaceId,
        ),
    )
    override val state: StateFlow<EngineSessionState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(EngineTelemetryState())
    override val telemetry: StateFlow<EngineTelemetryState> = _telemetry.asStateFlow()

    private val _events = MutableSharedFlow<EngineSessionEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<EngineSessionEvent> = _events.asSharedFlow()

    val webView: WebView = (if (isPrivate) {
        pool.createIsolatedWebView(appContext)
    } else {
        pool.acquire(appContext)
    }).apply {
        defaultMobileUserAgent = settings.userAgentString.orEmpty()
        configureIsolationAndStorage(this)
        installWebMessageBridge(this)
        installDocumentStartScripts(this)
        installTouchAndScrollListeners(this)
        installClients(this)
    }

    @SuppressLint("WebViewApiAvailability")
    private fun configureIsolationAndStorage(view: WebView) {
        view.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = true
        }
        if (isPrivate) {
            view.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            val multiProfileBound = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                runCatching { WebViewCompat.setProfile(view, EPHEMERAL_PROFILE_NAME) }.isSuccess
            if (multiProfileBound) {
                view.settings.domStorageEnabled = true
                view.settings.databaseEnabled = true
            } else {
                // Pre-MULTI_PROFILE fallback: disable persistent DOM/WebSQL storage on this
                // private WebView so regular user profile storage is never polluted or wiped.
                view.settings.domStorageEnabled = false
                view.settings.databaseEnabled = false
            }
            view.settings.saveFormData = false
            view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, false) }
        } else {
            view.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, false) }
        }
    }

    private fun installWebMessageBridge(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return

        runCatching {
            WebViewCompat.addWebMessageListener(
                view,
                JS_BRIDGE_NAME,
                ALLOWED_BRIDGE_ORIGIN_RULES,
            ) { _, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy ->
                val originScheme = sourceOrigin.scheme?.lowercase()
                val originHost = sourceOrigin.host?.lowercase()
                if ((originScheme != "https" && originScheme != "http") || originHost.isNullOrBlank()) {
                    return@addWebMessageListener
                }

                val raw = message.data ?: return@addWebMessageListener
                try {
                    val json = JSONObject(raw)
                    when (json.optString("type")) {
                        "doc_start" -> {
                            val verifiedUrl = verifySameOriginUrl(
                                reportedUrl = json.optString("url", ""),
                                sourceOrigin = sourceOrigin,
                                originScheme = originScheme,
                                originHost = originHost,
                            )
                            if (currentOverrides.blockAdsAndTrackers) {
                                val cacheKey = "$originScheme://$originHost"
                                val cachedCss = cosmeticCssCache.get(cacheKey)
                                if (cachedCss != null) {
                                    if (cachedCss.isNotBlank()) {
                                        val reply = JSONObject()
                                            .put("type", "cosmetic_css")
                                            .put("css", cachedCss)
                                            .toString()
                                        runCatching { replyProxy.postMessage(reply) }
                                    }
                                } else {
                                    sessionScope.launch(Dispatchers.Default) {
                                        val css = adblockController.cosmeticCssForUrl(verifiedUrl)
                                        val engineReady =
                                            (adblockController as? NativeAdblockController)?.isNativeEngineActive
                                                ?: true
                                        if (engineReady || css.isNotEmpty()) {
                                            cosmeticCssCache.put(cacheKey, css)
                                        }
                                        if (css.isNotBlank() && !disposed.get()) {
                                            val reply = JSONObject()
                                                .put("type", "cosmetic_css")
                                                .put("css", css)
                                                .toString()
                                            runOnMain {
                                                if (!disposed.get()) {
                                                    runCatching { replyProxy.postMessage(reply) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "dom_classes_ids" -> {
                            val verifiedUrl = verifySameOriginUrl(
                                reportedUrl = json.optString("url", ""),
                                sourceOrigin = sourceOrigin,
                                originScheme = originScheme,
                                originHost = originHost,
                            )
                            if (currentOverrides.blockAdsAndTrackers) {
                                val rawClasses = json.optJSONArray("classes")
                                val rawIds = json.optJSONArray("ids")
                                sessionScope.launch(Dispatchers.Default) {
                                    val classes = jsonArrayToStringList(rawClasses, limit = 512)
                                    val ids = jsonArrayToStringList(rawIds, limit = 512)
                                    if (classes.isEmpty() && ids.isEmpty()) return@launch
                                    val dynamicCss = adblockController.cosmeticCssForClassesAndIds(
                                        url = verifiedUrl,
                                        classes = classes,
                                        ids = ids,
                                    )
                                    if (dynamicCss.isNotBlank() && !disposed.get()) {
                                        val reply = JSONObject()
                                            .put("type", "cosmetic_dynamic_css")
                                            .put("css", dynamicCss)
                                            .toString()
                                        runOnMain {
                                            if (!disposed.get()) {
                                                runCatching { replyProxy.postMessage(reply) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "form_dirty" -> {
                            if (isMainFrame) {
                                val dirty = json.optBoolean("dirty", false)
                                _state.update { it.copy(hasUnsentFormDraft = dirty) }
                            }
                        }
                        "page_meta" -> {
                            if (isMainFrame) {
                                val readerEligible = json.optBoolean("readerEligible", false)
                                val lang = json.optString("lang").takeIf { it.isNotBlank() }
                                val iconUrl = json.optString("iconUrl").takeIf { it.isNotBlank() }
                                if (iconUrl != null) {
                                    FaviconCache.prefetchExplicitIconUrl(
                                        pageUrlOrDomain = sourceOrigin.toString(),
                                        iconUrl = iconUrl,
                                        appContext = appContext,
                                        isPrivate = isPrivate,
                                    )
                                }
                                _state.update {
                                    it.copy(
                                        isReaderEligible = readerEligible,
                                        pageLanguageTag = lang,
                                    )
                                }
                            }
                        }
                        "media_state" -> {
                            if (isMainFrame) {
                                val playing = json.optBoolean("playing", false)
                                val active = json.optBoolean("active", true)
                                if (!active) {
                                    _state.update { it.copy(activeMedia = null) }
                                } else {
                                    val mediaTitle = json.optString("title").ifBlank { _state.value.title }
                                    val rate = json.optDouble("rate", 1.0).toFloat()
                                    val hasVideo = json.optBoolean("hasVideo", false)
                                    val duration = json.optDouble("duration", 0.0).toFloat()
                                    val currentTime = json.optDouble("currentTime", 0.0).toFloat()
                                    _state.update { current ->
                                        current.copy(
                                            activeMedia = ActiveMediaSessionInfo(
                                                title = mediaTitle,
                                                domain = current.domain,
                                                isPlaying = playing,
                                                playbackRate = rate,
                                                hasVideoElement = hasVideo,
                                                durationSec = duration,
                                                currentTimeSec = currentTime,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        "element_zapped" -> {
                            if (isMainFrame && _state.value.isElementZapperActive) {
                                val rawSelector = json.optString("selector", "")
                                val sanitized = ElementZapperScripts.sanitizeSelector(rawSelector)
                                if (sanitized != null) {
                                    val origin = _state.value.domain.ifBlank { originHost.removePrefix("www.") }
                                    _events.tryEmit(
                                        EngineSessionEvent.ElementZapped(
                                            origin = origin,
                                            cssSelector = sanitized,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore malformed guest-page bridge frames
                }
            }
        }
    }

    private fun installDocumentStartScripts(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return

        if (isPrivate && !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                documentScriptHandlers += WebViewCompat.addDocumentStartJavaScript(
                    view,
                    FOCAL_EPHEMERAL_COOKIE_SHIELD_JS,
                    ALLOWED_BRIDGE_ORIGIN_RULES,
                )
            }
        }

        if (gpcEnabled) {
            runCatching {
                gpcScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                    view,
                    ElementZapperScripts.GLOBAL_PRIVACY_CONTROL_JS,
                    ALLOWED_BRIDGE_ORIGIN_RULES,
                )
            }
        }

        // Single unified document-start IIFE that captures `window.focalBridge` into a private
        // lexical closure and immediately deletes `window.focalBridge` from the global object.
        runCatching {
            documentScriptHandlers += WebViewCompat.addDocumentStartJavaScript(
                view,
                FOCAL_DOCUMENT_START_BOOTSTRAP_JS,
                ALLOWED_BRIDGE_ORIGIN_RULES,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchAndScrollListeners(view: WebView) {
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val w = v.width.coerceAtLeast(1).toFloat()
                val h = v.height.coerceAtLeast(1).toFloat()
                lastTouchXRatio = (event.x / w).coerceIn(0f, 1f)
                lastTouchYRatio = (event.y / h).coerceIn(0f, 1f)
            }
            false
        }

        view.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            lastScrollY = scrollY
            _events.tryEmit(EngineSessionEvent.ScrollDeltaChanged(scrollY = scrollY, deltaY = dy))
        }

        view.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            _state.update { current ->
                if (current.findInPageState.query.isBlank()) {
                    current.copy(findInPageState = FindInPageState())
                } else {
                    current.copy(
                        findInPageState = current.findInPageState.copy(
                            activeMatchOrdinal = activeMatchOrdinal,
                            numberOfMatches = numberOfMatches,
                            isDoneCounting = isDoneCounting,
                        ),
                    )
                }
            }
        }

        view.setOnLongClickListener { webViewInstance ->
            if (_state.value.isElementZapperActive) return@setOnLongClickListener false
            val targetView = (webViewInstance as? WebView) ?: view
            val hit = targetView.hitTestResult
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val targetUrl = hit.extra
                    if (!targetUrl.isNullOrBlank() && isAllowedNavigationUrl(targetUrl)) {
                        _events.tryEmit(
                            EngineSessionEvent.AnchorLongPressed(
                                rawUrl = targetUrl,
                                anchorTitle = null,
                                touchXRatio = lastTouchXRatio,
                                touchYRatio = lastTouchYRatio,
                                imageUrl = null,
                            ),
                        )
                        true
                    } else {
                        false
                    }
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val msg = Message.obtain(hrefMessageHandler).apply {
                        obj = PendingImageAnchorHit(
                            imageExtra = hit.extra,
                            touchXRatio = lastTouchXRatio,
                            touchYRatio = lastTouchYRatio,
                        )
                    }
                    targetView.requestFocusNodeHref(msg)
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val imageUrl = hit.extra
                    if (!imageUrl.isNullOrBlank() && isAllowedNavigationUrl(imageUrl)) {
                        _events.tryEmit(
                            EngineSessionEvent.AnchorLongPressed(
                                rawUrl = imageUrl,
                                anchorTitle = null,
                                touchXRatio = lastTouchXRatio,
                                touchYRatio = lastTouchYRatio,
                                imageUrl = imageUrl,
                            ),
                        )
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (isAllowedNavigationUrl(url)) {
                _events.tryEmit(
                    EngineSessionEvent.DownloadRequested(
                        url = url,
                        mimeType = mimeType,
                        contentLength = contentLength,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                    ),
                )
            }
        }
    }

    private fun installClients(view: WebView) {
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val targetUrl = request?.url?.toString() ?: return true
                // Block file://, content://, javascript:, data:, intent:, and unknown schemes
                if (!isAllowedNavigationUrl(targetUrl)) return true
                if (
                    request.isForMainFrame &&
                    !request.isRedirect &&
                    request.hasGesture() &&
                    Looper.myLooper() == Looper.getMainLooper() &&
                    !targetUrl.equals(_state.value.url, ignoreCase = true)
                ) {
                    captureHistorySnapshotIfNotRecent()?.let { snapshot ->
                        historyFrames.pushBack(snapshot)
                    }
                    historyFrames.clearForward()
                }
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val targetUrl = url ?: return true
                return !isAllowedNavigationUrl(targetUrl)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val req = request ?: return null
                val reqUrl = req.url ?: return null

                // Serve bundled Allo & Switzer variable fonts via WebViewAssetLoader with CORS
                if (reqUrl.host.equals(ASSET_LOADER_DOMAIN, ignoreCase = true)) {
                    val assetResp = assetLoader.shouldInterceptRequest(reqUrl) ?: return null
                    val headers = HashMap<String, String>()
                    assetResp.responseHeaders?.let { headers.putAll(it) }
                    headers["Access-Control-Allow-Origin"] = "*"
                    headers["Cache-Control"] = "public, max-age=31536000, immutable"
                    val mime = if (reqUrl.path?.endsWith(".ttf", ignoreCase = true) == true) {
                        "font/ttf"
                    } else {
                        assetResp.mimeType ?: "application/octet-stream"
                    }
                    val status = assetResp.statusCode.takeIf { it in 100..599 } ?: 200
                    val reason = assetResp.reasonPhrase?.takeIf { it.isNotBlank() } ?: "OK"
                    return WebResourceResponse(
                        mime,
                        assetResp.encoding,
                        status,
                        reason,
                        headers,
                        assetResp.data,
                    )
                }

                // Never block top-level user navigation frames here; block subresources on background I/O thread
                if (req.isForMainFrame) return null
                if (!currentOverrides.blockAdsAndTrackers) return null

                val requestUrl = reqUrl.toString()
                val sourceUrl = _state.value.url
                val resourceType = inferAdblockResourceType(req)

                val decision = adblockController.checkNetworkRequest(
                    requestUrl = requestUrl,
                    sourceUrl = sourceUrl,
                    resourceType = resourceType,
                )
                if (!decision.matched) return null

                val nextCount = sessionBlockedCount.incrementAndGet()
                _telemetry.update { it.copy(blockedTrackersInSession = nextCount) }

                // Returns static singleton 200 empty WebResourceResponse (zero allocations per blocked tracker!)
                return AdblockWebResponses.toWebResourceResponse(decision)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val safeUrl = url.orEmpty()
                val nextDomain = extractHostDomain(safeUrl)
                lastAppliedBottomInsetPx = -1
                hasInjectedLiveOverrides = false
                cancelAndResetTranslation(resetState = false)
                sessionBlockedCount.set(0L)
                _telemetry.update {
                    it.copy(
                        loadProgress = 10,
                        blockedTrackersInSession = 0L,
                    )
                }
                _state.update { prev ->
                    prev.copy(
                        url = safeUrl,
                        domain = nextDomain,
                        favicon = favicon ?: if (prev.domain == nextDomain) prev.favicon else null,
                        isLoading = true,
                        loadProgress = 10,
                        isHttps = safeUrl.startsWith("https://", ignoreCase = true),
                        blockedTrackersInSession = 0L,
                        hasUnsentFormDraft = false,
                        isReaderEligible = false,
                        pageLanguageTag = null,
                        translationStatus = PageTranslationStatus(),
                        activeMedia = null,
                        findInPageState = FindInPageState(),
                        isElementZapperActive = false,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true,
                    )
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                val safeUrl = url ?: view?.url.orEmpty()
                _state.update { current ->
                    if (safeUrl.isNotBlank()) {
                        current.copy(
                            url = safeUrl,
                            domain = extractHostDomain(safeUrl),
                            isHttps = safeUrl.startsWith("https://", ignoreCase = true),
                            canGoBack = view?.canGoBack() == true,
                            canGoForward = view?.canGoForward() == true,
                        )
                    } else {
                        current.copy(
                            canGoBack = view?.canGoBack() == true,
                            canGoForward = view?.canGoForward() == true,
                        )
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                val safeUrl = url.orEmpty()
                if (_state.value.isElementZapperActive) {
                    _state.update { it.copy(isElementZapperActive = false) }
                }
                applyLiveOverridesJs(view)
                applyHiddenSelectorsForPage(view, safeUrl, onlyIfNonEmpty = true)
                if (bottomChromeInsetPx > 0) {
                    applyBottomChromeInsetJs(view, bottomChromeInsetPx)
                }
                // Fallback cosmetic & GPC injection if DocumentStartJavaScript was unavailable on older WebView APK
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    if (gpcEnabled) {
                        view?.evaluateJavascript(ElementZapperScripts.GLOBAL_PRIVACY_CONTROL_JS, null)
                    }
                    if (currentOverrides.blockAdsAndTrackers && safeUrl.isNotBlank()) {
                        val parsedUri = runCatching { Uri.parse(safeUrl) }.getOrNull()
                        val cacheKey = if (parsedUri?.scheme != null && parsedUri.host != null) {
                            "${parsedUri.scheme?.lowercase()}://${parsedUri.host?.lowercase()}"
                        } else {
                            safeUrl
                        }
                        val cachedCss = cosmeticCssCache.get(cacheKey)
                        if (cachedCss != null) {
                            if (cachedCss.isNotBlank()) {
                                injectCssRuleBlock(view, "focal-cosmetic-fallback", cachedCss)
                            }
                        } else {
                            sessionScope.launch(Dispatchers.Default) {
                                val css = adblockController.cosmeticCssForUrl(safeUrl)
                                val engineReady =
                                    (adblockController as? NativeAdblockController)?.isNativeEngineActive
                                        ?: true
                                if (engineReady || css.isNotEmpty()) {
                                    cosmeticCssCache.put(cacheKey, css)
                                }
                                if (css.isNotBlank() && !disposed.get()) {
                                    runOnMain {
                                        if (!disposed.get()) {
                                            injectCssRuleBlock(view, "focal-cosmetic-fallback", css)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                _events.tryEmit(EngineSessionEvent.NavigationCommitted(url = safeUrl, isMainFrame = true))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val safeUrl = url ?: view?.url.orEmpty()
                val totalBlocked = sessionBlockedCount.get()
                applyHiddenSelectorsForPage(view, safeUrl, onlyIfNonEmpty = false)
                val insetPx = bottomChromeInsetPx
                if (insetPx > 0 || lastAppliedBottomInsetPx > 0) {
                    applyBottomChromeInsetJs(view, insetPx)
                }
                lastAppliedBottomInsetPx = insetPx
                _telemetry.update {
                    it.copy(
                        loadProgress = 100,
                        blockedTrackersInSession = totalBlocked,
                    )
                }
                _state.update {
                    it.copy(
                        url = safeUrl,
                        domain = extractHostDomain(safeUrl),
                        title = view?.title?.takeIf { t -> t.isNotBlank() } ?: it.title,
                        isLoading = false,
                        loadProgress = 100,
                        blockedTrackersInSession = totalBlocked,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true,
                    )
                }
                _events.tryEmit(EngineSessionEvent.PageLoaded(url = safeUrl))
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                val baseScale = view?.resources?.displayMetrics?.density ?: 1f
                val normalized = (newScale / baseScale.coerceAtLeast(0.5f)).coerceAtLeast(0.5f)
                _telemetry.update { it.copy(currentScaleFactor = normalized) }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                _telemetry.update { it.copy(loadProgress = newProgress) }
                val loading = newProgress < 100
                if (_state.value.isLoading != loading) {
                    _state.update {
                        it.copy(
                            isLoading = loading,
                            loadProgress = if (loading) 10 else 100,
                            blockedTrackersInSession = sessionBlockedCount.get(),
                        )
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    _state.update { it.copy(title = title) }
                }
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null && !icon.isRecycled && icon.width > 0 && icon.height > 0) {
                    val safeCopy = runCatching { icon.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: icon
                    _state.update { it.copy(favicon = safeCopy) }
                    val currentUrl = view?.url ?: _state.value.url
                    FaviconCache.onLiveIconReceived(
                        urlOrDomain = currentUrl,
                        bitmap = safeCopy,
                        appContext = appContext,
                        isPrivate = isPrivate,
                    )
                }
            }

            override fun onReceivedTouchIconUrl(view: WebView?, url: String?, precomposed: Boolean) {
                if (!url.isNullOrBlank()) {
                    val currentUrl = view?.url ?: _state.value.url
                    FaviconCache.prefetchExplicitIconUrl(
                        pageUrlOrDomain = currentUrl,
                        iconUrl = url,
                        appContext = appContext,
                        isPrivate = isPrivate,
                    )
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val callback = filePathCallback ?: return false
                cancelPendingFileChooser()
                if (disposed.get()) return false

                val mimeTypes = fileChooserParams?.acceptTypes
                    ?.mapNotNull { it?.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    .orEmpty()
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                pendingFileChooserCallback = callback
                val completed = AtomicBoolean(false)
                val onResult: (List<Uri>?) -> Unit = { uris ->
                    if (completed.compareAndSet(false, true)) {
                        runOnMain {
                            if (pendingFileChooserCallback === callback) {
                                pendingFileChooserCallback = null
                                val payload = uris?.takeIf { it.isNotEmpty() }?.toTypedArray()
                                runCatching { callback.onReceiveValue(payload) }
                            }
                        }
                    }
                }

                val emitted = _events.tryEmit(
                    EngineSessionEvent.FileChooserRequested(
                        mimeTypes = mimeTypes,
                        allowMultiple = allowMultiple,
                        onResult = onResult,
                    ),
                )
                if (!emitted) {
                    if (pendingFileChooserCallback === callback) {
                        pendingFileChooserCallback = null
                    }
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                if (disposed.get()) {
                    runCatching { req.deny() }
                    return
                }
                val resources = req.resources ?: emptyArray()
                if (resources.isEmpty()) {
                    runCatching { req.deny() }
                    return
                }
                val labels = resources.map { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "Camera"
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "Microphone"
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "Protected Media"
                        PermissionRequest.RESOURCE_MIDI_SYSEX -> "MIDI"
                        else -> resource
                    }
                }.distinct()
                val rawOrigin = req.origin?.toString().orEmpty()
                val displayOrigin = extractHostDomain(rawOrigin)
                    .ifBlank { rawOrigin.ifBlank { _state.value.domain } }
                val decided = AtomicBoolean(false)
                val emitted = _events.tryEmit(
                    EngineSessionEvent.SitePermissionRequested(
                        origin = displayOrigin,
                        resources = labels,
                        onDecision = { granted ->
                            if (decided.compareAndSet(false, true)) {
                                runOnMain {
                                    if (disposed.get()) {
                                        runCatching { req.deny() }
                                    } else {
                                        runCatching {
                                            if (granted) req.grant(resources) else req.deny()
                                        }
                                    }
                                }
                            }
                        },
                    ),
                )
                if (!emitted && decided.compareAndSet(false, true)) {
                    runCatching { req.deny() }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                val cb = callback ?: return
                val rawOrigin = origin.orEmpty()
                if (disposed.get() || rawOrigin.isBlank()) {
                    runCatching { cb.invoke(rawOrigin, false, false) }
                    return
                }
                val displayOrigin = extractHostDomain(rawOrigin).ifBlank { rawOrigin }
                val decided = AtomicBoolean(false)
                val emitted = _events.tryEmit(
                    EngineSessionEvent.SitePermissionRequested(
                        origin = displayOrigin,
                        resources = listOf("Location"),
                        onDecision = { granted ->
                            if (decided.compareAndSet(false, true)) {
                                runOnMain {
                                    val allow = granted && !disposed.get()
                                    runCatching { cb.invoke(rawOrigin, allow, false) }
                                }
                            }
                        },
                    ),
                )
                if (!emitted && decided.compareAndSet(false, true)) {
                    runCatching { cb.invoke(rawOrigin, false, false) }
                }
            }
        }
    }

    override fun loadUrl(url: String, headers: Map<String, String>) {
        if (!isAllowedNavigationUrl(url) || disposed.get()) return
        runOnMain {
            if (disposed.get()) return@runOnMain
            val cleaned = if (currentOverrides.blockAdsAndTrackers) {
                adblockController.cleanTrackingUrl(url).cleanUrl
            } else {
                url
            }
            if (!isAllowedNavigationUrl(cleaned)) return@runOnMain
            val isBlankTarget = cleaned.equals("about:blank", ignoreCase = true)
            if (!isBlankTarget) {
                captureHistorySnapshotIfNotRecent()?.let { snapshot ->
                    historyFrames.pushBack(snapshot)
                }
                historyFrames.clearForward()
                cancelAndResetTranslation(resetState = false)
                _telemetry.update { it.copy(loadProgress = 8) }
                _state.update {
                    it.copy(
                        url = cleaned,
                        domain = extractHostDomain(cleaned),
                        isLoading = true,
                        loadProgress = 8,
                        isHttps = cleaned.startsWith("https://", ignoreCase = true),
                        pageLanguageTag = null,
                        translationStatus = PageTranslationStatus(),
                        findInPageState = FindInPageState(),
                        isElementZapperActive = false,
                    )
                }
            }
            val mergedHeaders = if (gpcEnabled && !isBlankTarget) {
                val hasGpc = headers.keys.any { it.equals("Sec-GPC", ignoreCase = true) }
                if (hasGpc) headers else headers + ("Sec-GPC" to "1")
            } else {
                headers
            }
            if (mergedHeaders.isEmpty()) {
                webView.loadUrl(cleaned)
            } else {
                webView.loadUrl(cleaned, mergedHeaders)
            }
        }
    }

    override fun reload() = runOnMain {
        if (disposed.get()) return@runOnMain
        cancelAndResetTranslation(resetState = true)
        webView.reload()
    }

    override fun stopLoading() = runOnMain {
        if (!disposed.get()) webView.stopLoading()
    }

    override fun goBack(): Boolean {
        if (disposed.get()) return false
        return if (webView.canGoBack()) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (_state.value.isElementZapperActive) {
                    setElementZapperActive(false)
                }
                captureHistorySnapshotIfNotRecent()?.let { snapshot ->
                    historyFrames.pushForward(snapshot)
                }
            }
            historyFrames.popBack()
            webView.goBack()
            true
        } else {
            false
        }
    }

    override fun goForward(): Boolean {
        if (disposed.get()) return false
        return if (webView.canGoForward()) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (_state.value.isElementZapperActive) {
                    setElementZapperActive(false)
                }
                captureHistorySnapshotIfNotRecent()?.let { snapshot ->
                    historyFrames.pushBack(snapshot)
                }
            }
            historyFrames.popForward()
            webView.goForward()
            true
        } else {
            false
        }
    }

    override fun setSiteOverrides(overrides: OriginOverrides) {
        currentOverrides = overrides
        _state.update { it.copy(activeOverrides = overrides) }
        runOnMain {
            if (disposed.get()) return@runOnMain
            webView.settings.javaScriptEnabled = overrides.javascriptEnabled
            webView.settings.textZoom = overrides.pageZoomPercent.coerceIn(75, 200)
            webView.settings.userAgentString = if (overrides.desktopSite) {
                DESKTOP_USER_AGENT
            } else {
                defaultMobileUserAgent
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                runCatching {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                        webView.settings,
                        overrides.forceDarkWebSurface,
                    )
                }
            }
            applyLiveOverridesJs(webView)
            applyHiddenSelectorsJs(webView, overrides.hiddenCssSelectors)
        }
    }

    private fun applyLiveOverridesJs(view: WebView?) {
        val target = view ?: return
        val ov = currentOverrides
        val needsDark = ov.forceDarkWebSurface
        val needsFonts = ov.overridePublisherFonts
        if (!needsDark && !needsFonts) {
            if (!hasInjectedLiveOverrides) return
            hasInjectedLiveOverrides = false
            val clearJs = """
                (function() {
                  const style = document.getElementById('focal-live-site-overrides');
                  if (style) style.textContent = '';
                })();
            """.trimIndent()
            target.evaluateJavascript(clearJs, null)
            return
        }
        hasInjectedLiveOverrides = true
        val js = """
            (function() {
              const id = 'focal-live-site-overrides';
              let style = document.getElementById(id);
              if (!style) {
                style = document.createElement('style');
                style.id = id;
                (document.head || document.documentElement).appendChild(style);
              }
              let css = '';
              if ($needsDark) {
                css += 'html { color-scheme: dark !important; background: #0E0F12 !important; color: #F4F5F7 !important; } ';
              }
              if ($needsFonts) {
                css += "@font-face { font-family: 'FocalAllo'; src: url('https://$ASSET_LOADER_DOMAIN/res/font/allo_variable.ttf') format('truetype'); font-weight: 100 900; font-display: swap; } ";
                css += "@font-face { font-family: 'FocalSwitzer'; src: url('https://$ASSET_LOADER_DOMAIN/res/font/switzer_variable.ttf') format('truetype'); font-weight: 100 900; font-display: swap; } ";
                css += "h1, h2, h3, h4, h5, h6 { font-family: 'FocalAllo', system-ui, -apple-system, sans-serif !important; font-variation-settings: 'ROND' 0, 'opsz' 28 !important; letter-spacing: -0.02em !important; } ";
                css += "body, p, li, article, blockquote { font-family: 'FocalSwitzer', system-ui, -apple-system, sans-serif !important; line-height: 1.62 !important; } ";
              }
              style.textContent = css;
            })();
        """.trimIndent()
        target.evaluateJavascript(js, null)
    }

    private fun injectCssRuleBlock(view: WebView?, styleId: String, css: String) {
        val target = view ?: return
        val escapedCss = JSONObject.quote(css)
        val js = """
            (function() {
              let s = document.getElementById('$styleId');
              if (!s) {
                s = document.createElement('style');
                s.id = '$styleId';
                (document.head || document.documentElement).appendChild(s);
              }
              s.textContent = $escapedCss;
            })();
        """.trimIndent()
        target.evaluateJavascript(js, null)
    }

    override fun indexDomParagraphs(onResult: (List<IndexedDomParagraph>) -> Unit) {
        runOnMain {
            webView.evaluateJavascript(FOCAL_INDEX_PARAGRAPHS_JS) { rawJson ->
                val list = parseEvaluateJsJsonArray(rawJson) { obj ->
                    IndexedDomParagraph(
                        paraId = obj.optInt("id", 0),
                        text = obj.optString("text", ""),
                        tagName = obj.optString("tag", "p"),
                    )
                }
                onResult(list)
            }
        }
    }

    override fun extractReadableArticle(onResult: (ReadableDocument) -> Unit) {
        runOnMain {
            webView.evaluateJavascript(FOCAL_EXTRACT_READABLE_JS) { rawJson ->
                val currentState = _state.value
                val doc = try {
                    val decoded = unwrapEvaluateJavascriptString(rawJson)
                    val obj = JSONObject(decoded)
                    val arr = obj.optJSONArray("paragraphs") ?: JSONArray()
                    val paras = buildList(arr.length()) {
                        for (i in 0 until arr.length()) {
                            val p = arr.optString(i).trim()
                            if (p.isNotEmpty()) add(p)
                        }
                    }
                    val wordCount = paras.sumOf { it.split(Regex("\\s+")).size }
                    ReadableDocument(
                        url = currentState.url,
                        title = obj.optString("title").ifBlank { currentState.title },
                        byline = obj.optString("byline").takeIf { it.isNotBlank() },
                        siteName = obj.optString("siteName").ifBlank { currentState.domain },
                        paragraphs = paras,
                        estimatedReadMinutes = (wordCount / 215).coerceAtLeast(1),
                    )
                } catch (_: Exception) {
                    ReadableDocument(
                        url = currentState.url,
                        title = currentState.title.ifBlank { currentState.domain },
                        byline = null,
                        siteName = currentState.domain,
                        paragraphs = emptyList(),
                        estimatedReadMinutes = 1,
                    )
                }
                onResult(doc)
            }
        }
    }

    override fun scrollToGroundedParagraph(paraId: Int) {
        runOnMain {
            val js = """
                (function() {
                  const target = document.querySelector('[data-focal-para-id="$paraId"]');
                  if (!target) return false;
                  target.scrollIntoView({ behavior: 'smooth', block: 'center' });
                  const prevTransition = target.style.transition;
                  const prevOutline = target.style.outline;
                  const prevOutlineOffset = target.style.outlineOffset;
                  const prevRadius = target.style.borderRadius;
                  target.style.transition = 'outline-color 220ms ease, background-color 320ms ease';
                  target.style.borderRadius = '8px';
                  target.style.outlineOffset = '6px';
                  target.style.outline = '1.5px solid rgba(245, 246, 248, 0.72)';
                  target.style.backgroundColor = 'rgba(245, 246, 248, 0.10)';
                  setTimeout(function() {
                    target.style.outline = prevOutline;
                    target.style.outlineOffset = prevOutlineOffset;
                    target.style.borderRadius = prevRadius;
                    target.style.backgroundColor = '';
                    setTimeout(function() { target.style.transition = prevTransition; }, 350);
                  }, 1200);
                  return true;
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    override fun checkHasUnsentFormInput(onResult: (Boolean) -> Unit) {
        runOnMain {
            webView.evaluateJavascript("(function(){ return !!window.__focalFormDirty; })()") { raw ->
                val dirty = raw?.trim().equals("true", ignoreCase = true)
                _state.update { it.copy(hasUnsentFormDraft = dirty) }
                onResult(dirty)
            }
        }
    }

    override fun translatePageInPlace(targetLanguageCode: String?) {
        if (disposed.get()) return
        val currentUrl = _state.value.url
        if (currentUrl.isBlank() || currentUrl.equals("about:blank", ignoreCase = true)) return
        val targetLang = DomTranslationBatcher.normalizeLanguageCode(
            targetLanguageCode ?: Locale.getDefault().language,
        ) ?: "en"

        runOnMain {
            if (disposed.get()) return@runOnMain
            val gen = translationGeneration.incrementAndGet()
            activeTranslationJob?.cancel()
            _state.update {
                it.copy(
                    translationStatus = PageTranslationStatus(
                        phase = PageTranslationPhase.TRANSLATING,
                        targetLanguageCode = targetLang,
                    ),
                )
            }

            activeTranslationJob = sessionScope.launch {
                try {
                    val rawCollected = evaluateJsSuspend(
                        InPlaceDomTranslationScripts.buildCollectScript(gen),
                    )
                    if (translationGeneration.get() != gen || disposed.get()) return@launch

                    val parsed = JSONObject(unwrapEvaluateJavascriptString(rawCollected))
                    val htmlLang = parsed.optString("htmlLang", "")
                    val rawSegments = parsed.optJSONArray("segments") ?: JSONArray()
                    val segments = buildList(rawSegments.length()) {
                        for (i in 0 until rawSegments.length()) {
                            val obj = rawSegments.optJSONObject(i) ?: continue
                            val text = obj.optString("text", "")
                            if (text.isNotEmpty()) {
                                add(DomTextSegment(id = obj.optInt("id", i), text = text))
                            }
                        }
                    }

                    if (segments.isEmpty()) {
                        _state.update {
                            it.copy(
                                translationStatus = PageTranslationStatus(
                                    phase = PageTranslationPhase.ORIGINAL,
                                    targetLanguageCode = targetLang,
                                ),
                            )
                        }
                        return@launch
                    }

                    val sampleBuilder = StringBuilder(1_600)
                    for (seg in segments) {
                        if (sampleBuilder.length >= 1_500) break
                        if (sampleBuilder.isNotEmpty()) sampleBuilder.append('\n')
                        sampleBuilder.append(seg.text.trim())
                    }
                    val sample = sampleBuilder.toString().take(1_500)

                    val detectedLang = runCatching { translationEngine.identifyLanguage(sample) }.getOrNull()
                        ?: DomTranslationBatcher.normalizeLanguageCode(htmlLang)

                    if (translationGeneration.get() != gen || disposed.get()) return@launch

                    if (detectedLang == null) {
                        _state.update {
                            it.copy(
                                translationStatus = PageTranslationStatus(
                                    phase = PageTranslationPhase.FAILED,
                                    targetLanguageCode = targetLang,
                                    errorMessage = "Unsupported language",
                                ),
                            )
                        }
                        return@launch
                    }

                    if (detectedLang == targetLang) {
                        _state.update {
                            it.copy(
                                translationStatus = PageTranslationStatus(
                                    phase = PageTranslationPhase.ORIGINAL,
                                    sourceLanguageCode = detectedLang,
                                    targetLanguageCode = targetLang,
                                ),
                            )
                        }
                        return@launch
                    }

                    val pieces = DomTranslationBatcher.splitSurrogateSafe(segments)
                    val pieceBatches = DomTranslationBatcher.groupIntoBatches(pieces)
                    val textBatches = pieceBatches.map { batch -> batch.map { it.coreText } }
                    val assembler = DomTranslationBatcher.ProgressiveAssembler(pieces)

                    translationEngine.translateBatches(
                        sourceLanguage = detectedLang,
                        targetLanguage = targetLang,
                        batches = textBatches,
                        onDownloadingModel = {
                            if (translationGeneration.get() == gen && !disposed.get()) {
                                _state.update {
                                    it.copy(
                                        translationStatus = PageTranslationStatus(
                                            phase = PageTranslationPhase.DOWNLOADING_MODEL,
                                            sourceLanguageCode = detectedLang,
                                            targetLanguageCode = targetLang,
                                        ),
                                    )
                                }
                            }
                        },
                        onTranslating = {
                            if (translationGeneration.get() == gen && !disposed.get()) {
                                _state.update {
                                    it.copy(
                                        translationStatus = PageTranslationStatus(
                                            phase = PageTranslationPhase.TRANSLATING,
                                            sourceLanguageCode = detectedLang,
                                            targetLanguageCode = targetLang,
                                        ),
                                    )
                                }
                            }
                        },
                        onBatchTranslated = { batchIndex, translatedTexts ->
                            if (translationGeneration.get() != gen || disposed.get()) return@translateBatches
                            val readySegments = assembler.acceptBatch(pieceBatches[batchIndex], translatedTexts)
                            if (readySegments.isNotEmpty()) {
                                val applyScript = InPlaceDomTranslationScripts.buildApplyScript(gen, readySegments)
                                runOnMain {
                                    if (translationGeneration.get() == gen && !disposed.get()) {
                                        webView.evaluateJavascript(applyScript, null)
                                    }
                                }
                            }
                        },
                    )

                    if (translationGeneration.get() == gen && !disposed.get()) {
                        _state.update {
                            it.copy(
                                translationStatus = PageTranslationStatus(
                                    phase = PageTranslationPhase.TRANSLATED,
                                    sourceLanguageCode = detectedLang,
                                    targetLanguageCode = targetLang,
                                ),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (translationGeneration.get() == gen && !disposed.get()) {
                        _state.update {
                            it.copy(
                                translationStatus = PageTranslationStatus(
                                    phase = PageTranslationPhase.FAILED,
                                    targetLanguageCode = targetLang,
                                    errorMessage = t.message ?: "Translation failed",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun restoreOriginalPageLanguage() {
        runOnMain {
            cancelAndResetTranslation(resetState = true)
            if (!disposed.get()) {
                webView.evaluateJavascript(InPlaceDomTranslationScripts.RESTORE_ORIGINAL_SCRIPT, null)
            }
        }
    }

    override fun capturePreviewBitmap(maxWidthPx: Int, onResult: (Bitmap?) -> Unit) {
        if (disposed.get()) {
            onResult(null)
            return
        }
        runOnMain {
            onResult(captureSnapshotSync(maxWidthPx))
        }
    }

    private fun captureHistorySnapshotIfNotRecent(): Bitmap? {
        if (disposed.get()) return null
        val currentUrl = _state.value.url
        if (currentUrl.isBlank() || currentUrl.equals("about:blank", ignoreCase = true)) {
            return null
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (
            lastHistorySnapshotAtMs > 0L &&
            currentUrl.equals(lastHistorySnapshotUrl, ignoreCase = true) &&
            (nowMs - lastHistorySnapshotAtMs) < HISTORY_SNAPSHOT_DEDUP_WINDOW_MS
        ) {
            return null
        }
        val snapshot = captureSnapshotSync(HISTORY_PREVIEW_MAX_WIDTH_PX) ?: return null
        lastHistorySnapshotUrl = currentUrl
        lastHistorySnapshotAtMs = nowMs
        return snapshot
    }

    private fun captureSnapshotSync(maxWidthPx: Int): Bitmap? {
        if (disposed.get()) return null
        val currentUrl = _state.value.url
        val viewW = webView.width
        val viewH = webView.height
        if (
            currentUrl.isBlank() ||
            currentUrl.equals("about:blank", ignoreCase = true) ||
            viewW <= 0 ||
            viewH <= 0
        ) {
            return null
        }
        return runCatching {
            val safeMaxW = maxWidthPx.coerceIn(180, 720)
            val scale = (safeMaxW.toFloat() / viewW.toFloat()).coerceAtMost(1f)
            val targetW = (viewW * scale).toInt().coerceAtLeast(1)
            val targetH = (viewH * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
            webView.draw(canvas)
            bmp
        }.getOrNull()
    }

    private fun cancelAndResetTranslation(resetState: Boolean) {
        translationGeneration.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = null
        if (resetState) {
            _state.update { it.copy(translationStatus = PageTranslationStatus()) }
        }
    }

    private suspend fun evaluateJsSuspend(script: String): String? =
        suspendCancellableCoroutine { cont ->
            runOnMain {
                if (disposed.get() || !cont.isActive) {
                    if (cont.isActive) cont.resume(null)
                    return@runOnMain
                }
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        }

    override fun setPlaybackRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 3.0f)
        runOnMain {
            val js = """
                (function() {
                  window.__focalPreferredPlaybackRate = $clamped;
                  document.querySelectorAll('video, audio').forEach(function(m) {
                    m.playbackRate = $clamped;
                  });
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            _state.update { s ->
                s.copy(activeMedia = s.activeMedia?.copy(playbackRate = clamped))
            }
        }
    }

    override fun setMediaPlaying(playing: Boolean) {
        runOnMain {
            val cmd = if (playing) "m.play().catch(function(){});" else "m.pause();"
            val js = """
                (function() {
                  const media = document.querySelectorAll('video, audio');
                  media.forEach(function(m) { $cmd });
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            _state.update { s ->
                s.copy(activeMedia = s.activeMedia?.copy(isPlaying = playing))
            }
        }
    }

    override fun requestElementFullscreenOrPip() {
        runOnMain {
            val js = """
                (function() {
                  const v = document.querySelector('video');
                  if (v && v.requestPictureInPicture) {
                    v.requestPictureInPicture().catch(function(){});
                  }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    override fun findInPage(query: String) {
        runOnMain {
            if (disposed.get()) return@runOnMain
            if (query.isBlank()) {
                _state.update { it.copy(findInPageState = FindInPageState()) }
                webView.clearMatches()
            } else {
                _state.update {
                    it.copy(
                        findInPageState = FindInPageState(
                            query = query,
                            activeMatchOrdinal = 0,
                            numberOfMatches = 0,
                            isDoneCounting = false,
                        ),
                    )
                }
                webView.findAllAsync(query)
            }
        }
    }

    override fun findNext(forward: Boolean) {
        runOnMain {
            if (disposed.get()) return@runOnMain
            if (_state.value.findInPageState.query.isNotBlank()) {
                webView.findNext(forward)
            }
        }
    }

    override fun clearFindInPage() {
        runOnMain {
            if (disposed.get()) return@runOnMain
            _state.update { it.copy(findInPageState = FindInPageState()) }
            webView.clearMatches()
        }
    }

    override fun setGlobalPrivacyControl(enabled: Boolean) {
        gpcEnabled = enabled
        if (disposed.get()) return
        runOnMain {
            if (disposed.get()) return@runOnMain
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                if (enabled) {
                    if (gpcScriptHandler == null) {
                        runCatching {
                            gpcScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                                webView,
                                ElementZapperScripts.GLOBAL_PRIVACY_CONTROL_JS,
                                ALLOWED_BRIDGE_ORIGIN_RULES,
                            )
                        }
                    }
                } else {
                    runCatching { gpcScriptHandler?.remove() }
                    gpcScriptHandler = null
                }
            }
            webView.evaluateJavascript(ElementZapperScripts.buildGpcToggleScript(enabled), null)
        }
    }

    override fun setBottomChromeInsetPx(insetPx: Int) {
        val clamped = insetPx.coerceIn(0, 600)
        bottomChromeInsetPx = clamped
        if (disposed.get()) return
        runOnMain {
            if (disposed.get()) return@runOnMain
            if (lastAppliedBottomInsetPx == clamped) return@runOnMain
            lastAppliedBottomInsetPx = clamped
            applyBottomChromeInsetJs(webView, clamped)
        }
    }

    override fun setElementZapperActive(active: Boolean) {
        if (disposed.get()) return
        _state.update { it.copy(isElementZapperActive = active) }
        runOnMain {
            if (disposed.get()) return@runOnMain
            webView.evaluateJavascript(ElementZapperScripts.buildZapperModeToggleScript(active), null)
        }
    }

    override fun getHistoryBackPreview(): Bitmap? {
        if (disposed.get()) return null
        return historyFrames.peekBack()?.takeUnless { it.isRecycled }
    }

    override fun getHistoryForwardPreview(): Bitmap? {
        if (disposed.get()) return null
        return historyFrames.peekForward()?.takeUnless { it.isRecycled }
    }

    override fun setForegroundActive(active: Boolean) {
        if (disposed.get()) return
        runOnMain {
            if (disposed.get()) return@runOnMain
            if (active) {
                runCatching { webView.onResume() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching {
                        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                    }
                }
            } else {
                // Only pause WebView and waive renderer priority when media is not actively playing
                if (_state.value.activeMedia?.isPlaying != true) {
                    runCatching { webView.onPause() }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runCatching {
                            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                        }
                    }
                }
            }
        }
    }

    override fun saveNavigationState(): Bundle? {
        if (disposed.get() || Looper.myLooper() != Looper.getMainLooper()) return null
        return runCatching {
            val bundle = Bundle()
            if (webView.saveState(bundle) != null && !bundle.isEmpty) bundle else null
        }.getOrNull()
    }

    override fun restoreNavigationState(state: Bundle): Boolean {
        if (disposed.get() || state.isEmpty || Looper.myLooper() != Looper.getMainLooper()) return false
        return runCatching {
            val restoredList = webView.restoreState(state)
            if (restoredList != null) {
                val restoredUrl = restoredList.currentItem?.url ?: webView.url.orEmpty()
                val restoredTitle = restoredList.currentItem?.title ?: webView.title.orEmpty()
                if (restoredUrl.isNotBlank()) {
                    _state.update { prev ->
                        prev.copy(
                            url = restoredUrl,
                            domain = extractHostDomain(restoredUrl),
                            title = restoredTitle.ifBlank { prev.title },
                            isHttps = restoredUrl.startsWith("https://", ignoreCase = true),
                            canGoBack = webView.canGoBack(),
                            canGoForward = webView.canGoForward(),
                        )
                    }
                }
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    override fun trimMemory() {
        historyFrames.clear()
        cosmeticCssCache.evictAll()
        lastHistorySnapshotUrl = ""
        lastHistorySnapshotAtMs = 0L
    }

    private fun applyHiddenSelectorsJs(view: WebView?, selectors: List<String>) {
        val target = view ?: return
        target.evaluateJavascript(ElementZapperScripts.buildHiddenSelectorsCssScript(selectors), null)
    }

    private fun applyHiddenSelectorsForPage(
        view: WebView?,
        pageUrl: String,
        onlyIfNonEmpty: Boolean,
    ) {
        val target = view ?: return
        val ov = currentOverrides
        val pageDomain = extractHostDomain(pageUrl)
        val matchesOrigin = ov.origin.isBlank() ||
            pageDomain.isBlank() ||
            ov.origin.equals(pageDomain, ignoreCase = true)
        val selectors = if (matchesOrigin) ov.hiddenCssSelectors else emptyList()
        if (onlyIfNonEmpty && selectors.isEmpty()) return
        applyHiddenSelectorsJs(target, selectors)
    }

    private fun applyBottomChromeInsetJs(view: WebView?, insetPx: Int) {
        val target = view ?: return
        val currentUrl = target.url ?: _state.value.url
        if (currentUrl.isBlank() || currentUrl.equals("about:blank", ignoreCase = true)) return
        val density = target.resources?.displayMetrics?.density?.coerceAtLeast(1f) ?: 1f
        val cssPx = if (insetPx <= 0) 0 else kotlin.math.round(insetPx / density).toInt()
        target.evaluateJavascript(ElementZapperScripts.buildBottomInsetCssScript(cssPx), null)
    }

    private fun cancelPendingFileChooser() {
        val stale = pendingFileChooserCallback ?: return
        pendingFileChooserCallback = null
        runCatching { stale.onReceiveValue(null) }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        translationGeneration.incrementAndGet()
        sessionScope.cancel()
        historyFrames.clear()
        cosmeticCssCache.evictAll()
        lastHistorySnapshotUrl = ""
        lastHistorySnapshotAtMs = 0L
        runOnMain {
            try {
                cancelPendingFileChooser()
                hrefMessageHandler.removeCallbacksAndMessages(null)
                activeTranslationJob?.cancel()
                activeTranslationJob = null
                runCatching { gpcScriptHandler?.remove() }
                gpcScriptHandler = null
                documentScriptHandlers.forEach { handler -> runCatching { handler.remove() } }
                documentScriptHandlers.clear()
                runCatching { webView.setFindListener(null) }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    runCatching { WebViewCompat.removeWebMessageListener(webView, JS_BRIDGE_NAME) }
                }
                (webView.parent as? ViewGroup)?.removeView(webView)
                (webView.context as? MutableContextWrapper)?.baseContext =
                    webView.context.applicationContext
                webView.stopLoading()
                if (isPrivate) {
                    webView.clearHistory()
                    webView.clearCache(true)
                    webView.clearFormData()
                }
                webView.destroy()
            } finally {
                onSessionDisposed(this)
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}

/**
 * Full-bleed Compose host for [WebViewSession].
 * Never resizes during scroll (`Modifier.fillMaxSize()`), preserving `0.0ms` layout pass at 120Hz.
 */
@Composable
fun WebEngineSurface(
    session: EngineSession,
    modifier: Modifier = Modifier,
) {
    val webSession = session as? WebViewSession ?: return
    val hostContext = LocalContext.current
    key(webSession.sessionId) {
        AndroidView(
            modifier = modifier,
            factory = {
                val view = webSession.webView
                (view.parent as? ViewGroup)?.removeView(view)
                val unwrappedHost = (hostContext as? MutableContextWrapper)?.baseContext ?: hostContext
                (view.context as? MutableContextWrapper)?.let { wrapper ->
                    if (wrapper.baseContext !== unwrappedHost) {
                        wrapper.baseContext = unwrappedHost
                    }
                }
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                view
            },
            update = { view ->
                val unwrappedHost = (hostContext as? MutableContextWrapper)?.baseContext ?: hostContext
                (view.context as? MutableContextWrapper)?.let { wrapper ->
                    if (wrapper.baseContext !== unwrappedHost) {
                        wrapper.baseContext = unwrappedHost
                    }
                }
            },
            onRelease = { view ->
                (view.context as? MutableContextWrapper)?.baseContext = view.context.applicationContext
            },
        )
    }
}

private fun verifySameOriginUrl(
    reportedUrl: String,
    sourceOrigin: Uri,
    originScheme: String,
    originHost: String,
): String {
    if (reportedUrl.isNotBlank()) {
        try {
            val parsed = Uri.parse(reportedUrl)
            if (
                parsed.scheme?.lowercase() == originScheme &&
                parsed.host?.lowercase() == originHost
            ) {
                return reportedUrl
            }
        } catch (_: Exception) {
        }
    }
    return sourceOrigin.toString()
}

private fun jsonArrayToStringList(arr: JSONArray?, limit: Int): List<String> {
    if (arr == null || arr.length() == 0) return emptyList()
    val count = minOf(arr.length(), limit)
    return buildList(count) {
        for (i in 0 until count) {
            val token = arr.optString(i).trim()
            if (token.isNotEmpty()) add(token)
        }
    }
}

private fun extractHostDomain(url: String): String {
    if (url.isBlank()) return ""
    return try {
        Uri.parse(url).host?.removePrefix("www.").orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun inferAdblockResourceType(request: WebResourceRequest): String {
    if (request.isForMainFrame) return "main_frame"
    val headers = request.requestHeaders
    val accept = headers?.get("Accept")?.lowercase().orEmpty()
    val path = request.url?.path?.lowercase().orEmpty()
    return when {
        accept.contains("text/css") || path.endsWith(".css") -> "stylesheet"
        accept.contains("image/") || path.endsWith(".png") || path.endsWith(".jpg") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") -> "image"
        path.endsWith(".js") || path.endsWith(".mjs") || accept.contains("javascript") -> "script"
        path.endsWith(".woff2") || path.endsWith(".woff") || path.endsWith(".ttf") -> "font"
        accept.contains("application/json") ||
            headers?.containsKey("X-Requested-With") == true -> "xmlhttprequest"
        else -> "other"
    }
}

private fun unwrapEvaluateJavascriptString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return "{}"
    return if (raw.startsWith("\"") && raw.endsWith("\"")) {
        JSONArray("[$raw]").getString(0)
    } else {
        raw
    }
}

private fun <T> parseEvaluateJsJsonArray(
    raw: String?,
    mapper: (JSONObject) -> T,
): List<T> {
    return try {
        val unwrapped = unwrapEvaluateJavascriptString(raw)
        val arr = JSONArray(unwrapped)
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(mapper(obj))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private const val FOCAL_EPHEMERAL_COOKIE_SHIELD_JS = """
(function() {
  if (location.protocol !== 'https:' && location.protocol !== 'http:') return;
  try {
    Object.defineProperty(document, 'cookie', {
      get: function() { return ''; },
      set: function() { return true; },
      configurable: false
    });
  } catch (_) {}
})();
"""

private const val FOCAL_DOCUMENT_START_BOOTSTRAP_JS = """
(function() {
  const bridge = window.focalBridge;
  if (!bridge) return;
  try { delete window.focalBridge; } catch (_) {}
  if (location.protocol !== 'https:' && location.protocol !== 'http:') return;

  bridge.onmessage = function(event) {
    try {
      const msg = JSON.parse(event.data);
      if (msg.type === 'cosmetic_css' && msg.css) {
        let style = document.getElementById('focal-cosmetic-css');
        if (!style) {
          style = document.createElement('style');
          style.id = 'focal-cosmetic-css';
          (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = msg.css;
      } else if (msg.type === 'cosmetic_dynamic_css' && msg.css) {
        let style = document.getElementById('focal-cosmetic-dynamic-css');
        if (!style) {
          style = document.createElement('style');
          style.id = 'focal-cosmetic-dynamic-css';
          (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = msg.css;
      }
    } catch (_) {}
  };

  try {
    bridge.postMessage(JSON.stringify({ type: 'doc_start', url: location.href }));
  } catch (_) {}

  document.addEventListener('DOMContentLoaded', function() {
    if (window !== window.top) return;
    try {
      const paras = document.querySelectorAll('article p, main p, p');
      let chars = 0;
      for (let i = 0; i < Math.min(paras.length, 25); i++) {
        chars += (paras[i].textContent || '').trim().length;
      }
      const iconNode = document.querySelector(
        'link[rel="apple-touch-icon"], link[rel="apple-touch-icon-precomposed"], link[rel="icon"][sizes*="192"], link[rel="icon"][sizes*="128"], link[rel="icon"][sizes*="64"], link[rel="icon"][sizes*="32"], link[rel="icon"], link[rel="shortcut icon"]'
      );
      bridge.postMessage(JSON.stringify({
        type: 'page_meta',
        readerEligible: chars >= 480,
        lang: document.documentElement.lang || '',
        iconUrl: (iconNode && iconNode.href) ? iconNode.href : ''
      }));

      const classSet = new Set();
      const idSet = new Set();
      const allEls = document.querySelectorAll('[class], [id]');
      for (let i = 0; i < Math.min(allEls.length, 1200); i++) {
        const el = allEls[i];
        if (el.id && idSet.size < 400) idSet.add(el.id);
        if (el.classList && classSet.size < 400) {
          for (let j = 0; j < el.classList.length && classSet.size < 400; j++) {
            classSet.add(el.classList[j]);
          }
        }
      }
      if (classSet.size > 0 || idSet.size > 0) {
        bridge.postMessage(JSON.stringify({
          type: 'dom_classes_ids',
          url: location.href,
          classes: Array.from(classSet),
          ids: Array.from(idSet)
        }));
      }
    } catch (_) {}
  }, { once: true });

  window.__focalFormDirty = false;
  function checkEditable(el) {
    if (!el) return false;
    const tag = (el.tagName || '').toLowerCase();
    if (tag === 'textarea') return (el.value || '').trim().length > 0;
    if (tag === 'input') {
      const t = (el.type || 'text').toLowerCase();
      if (['hidden', 'submit', 'button', 'checkbox', 'radio', 'range', 'reset', 'file', 'image', 'color'].indexOf(t) >= 0) return false;
      return (el.value || '').trim().length > 0;
    }
    if (el.isContentEditable) return (el.innerText || '').trim().length > 0;
    return false;
  }
  function scanAnyEditableDirty() {
    const nodes = document.querySelectorAll('textarea, input, [contenteditable=""], [contenteditable="true"], [contenteditable="plaintext-only"]');
    for (let i = 0; i < nodes.length; i++) {
      if (checkEditable(nodes[i])) return true;
    }
    return false;
  }
  function notifyDirty(val) {
    if (window.__focalFormDirty === val) return;
    window.__focalFormDirty = val;
    try { bridge.postMessage(JSON.stringify({ type: 'form_dirty', dirty: val })); } catch (_) {}
  }
  function handleEditableMutation(e) {
    if (checkEditable(e.target)) {
      notifyDirty(true);
    } else if (window.__focalFormDirty) {
      notifyDirty(scanAnyEditableDirty());
    }
  }
  document.addEventListener('input', handleEditableMutation, { capture: true, passive: true });
  document.addEventListener('change', handleEditableMutation, { capture: true, passive: true });
  document.addEventListener('reset', function() {
    setTimeout(function() { notifyDirty(scanAnyEditableDirty()); }, 0);
  }, { capture: true, passive: true });
  document.addEventListener('submit', function() {
    notifyDirty(false);
  }, { capture: true, passive: true });

  window.__focalPreferredPlaybackRate = window.__focalPreferredPlaybackRate || 1.0;
  function reportMedia(el, playing) {
    if (!el) return;
    if (window.__focalPreferredPlaybackRate && Math.abs(el.playbackRate - window.__focalPreferredPlaybackRate) > 0.02) {
      el.playbackRate = window.__focalPreferredPlaybackRate;
    }
    try {
      bridge.postMessage(JSON.stringify({
        type: 'media_state',
        active: true,
        playing: playing,
        rate: el.playbackRate || 1.0,
        hasVideo: (el.tagName || '').toLowerCase() === 'video',
        duration: Number.isFinite(el.duration) ? el.duration : 0,
        currentTime: el.currentTime || 0,
        title: document.title || ''
      }));
    } catch (_) {}
  }
  document.addEventListener('play', function(e) {
    const t = (e.target && e.target.tagName || '').toLowerCase();
    if (t === 'video' || t === 'audio') reportMedia(e.target, true);
  }, true);
  document.addEventListener('pause', function(e) {
    const t = (e.target && e.target.tagName || '').toLowerCase();
    if (t === 'video' || t === 'audio') reportMedia(e.target, false);
  }, true);
  document.addEventListener('ratechange', function(e) {
    const t = (e.target && e.target.tagName || '').toLowerCase();
    if (t === 'video' || t === 'audio') reportMedia(e.target, !e.target.paused);
  }, true);

  window.addEventListener('__focal_zapper_event', function(e) {
    try {
      if (!window.__focalZapperActive) return;
      const sel = e && e.detail && typeof e.detail.selector === 'string' ? e.detail.selector : '';
      if (!sel) return;
      bridge.postMessage(JSON.stringify({ type: 'element_zapped', selector: sel }));
    } catch (_) {}
  });
})();
"""

private const val FOCAL_INDEX_PARAGRAPHS_JS = """
(function() {
  const root = document.querySelector('article, main, [role="main"]') || document.body;
  if (!root) return JSON.stringify([]);
  const nodes = root.querySelectorAll('p, li, blockquote');
  const results = [];
  let nextId = 0;
  for (let i = 0; i < nodes.length && nextId < 80; i++) {
    const el = nodes[i];
    const text = (el.innerText || '').replace(/\s+/g, ' ').trim();
    if (text.length < 38) continue;
    el.setAttribute('data-focal-para-id', String(nextId));
    results.push({
      id: nextId,
      text: text,
      tag: (el.tagName || 'p').toLowerCase()
    });
    nextId++;
  }
  return JSON.stringify(results);
})();
"""

private const val FOCAL_EXTRACT_READABLE_JS = """
(function() {
  const candidates = Array.from(document.querySelectorAll('article, main, [role="main"], .post-content, .article-body'));
  let bestRoot = candidates[0] || document.body;
  let bestScore = -1;
  candidates.forEach(function(el) {
    const textLen = (el.innerText || '').length;
    const linkLen = Array.from(el.querySelectorAll('a')).reduce(function(acc, a) {
      return acc + (a.innerText || '').length;
    }, 0);
    const linkDensity = textLen > 0 ? (linkLen / textLen) : 1;
    const score = textLen * (1 - Math.min(linkDensity, 0.85));
    if (score > bestScore) {
      bestScore = score;
      bestRoot = el;
    }
  });
  const paras = [];
  if (bestRoot) {
    bestRoot.querySelectorAll('p, blockquote').forEach(function(p) {
      const t = (p.innerText || '').replace(/\s+/g, ' ').trim();
      if (t.length >= 36) paras.push(t);
    });
  }
  const bylineMeta = document.querySelector('meta[name="author"], [rel="author"], .byline, .author');
  const siteMeta = document.querySelector('meta[property="og:site_name"]');
  return JSON.stringify({
    title: document.title || '',
    byline: bylineMeta ? (bylineMeta.getAttribute('content') || bylineMeta.innerText || '').trim() : '',
    siteName: siteMeta ? (siteMeta.getAttribute('content') || '').trim() : location.hostname.replace(/^www\./, ''),
    paragraphs: paras
  });
})();
"""
