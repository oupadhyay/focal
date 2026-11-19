package app.focal.engine.api

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class OriginOverrides(
    val origin: String,
    val blockAdsAndTrackers: Boolean = true,
    val forceDarkWebSurface: Boolean = false,
    val overridePublisherFonts: Boolean = false,
    val pageZoomPercent: Int = 100,
    val javascriptEnabled: Boolean = true,
    val desktopSite: Boolean = false,
)

data class ReadableDocument(
    val url: String,
    val title: String,
    val byline: String?,
    val siteName: String?,
    val paragraphs: List<String>,
    val estimatedReadMinutes: Int,
) {
    val isSubstantive: Boolean
        get() = paragraphs.size >= 2 && paragraphs.sumOf { it.length } >= 240
}

data class IndexedDomParagraph(
    val paraId: Int,
    val text: String,
    val tagName: String = "p",
)

data class ActiveMediaSessionInfo(
    val title: String,
    val domain: String,
    val isPlaying: Boolean,
    val playbackRate: Float = 1.0f,
    val hasVideoElement: Boolean = false,
    val durationSec: Float = 0f,
    val currentTimeSec: Float = 0f,
)

enum class PageTranslationPhase {
    ORIGINAL,
    DOWNLOADING_MODEL,
    TRANSLATING,
    TRANSLATED,
    FAILED,
}

data class PageTranslationStatus(
    val phase: PageTranslationPhase = PageTranslationPhase.ORIGINAL,
    val sourceLanguageCode: String? = null,
    val targetLanguageCode: String? = null,
    val errorMessage: String? = null,
)

data class EngineSessionState(
    val sessionId: String,
    val isPrivate: Boolean = false,
    val spaceId: String? = null,
    val url: String = "",
    val domain: String = "",
    val title: String = "",
    val favicon: Bitmap? = null,
    val loadProgress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isHttps: Boolean = true,
    val isReaderEligible: Boolean = false,
    val pageLanguageTag: String? = null,
    val translationStatus: PageTranslationStatus = PageTranslationStatus(),
    val blockedTrackersInSession: Long = 0L,
    val hasUnsentFormDraft: Boolean = false,
    val currentScaleFactor: Float = 1.0f,
    val activeMedia: ActiveMediaSessionInfo? = null,
    val activeOverrides: OriginOverrides? = null,
)

data class EngineTelemetryState(
    val loadProgress: Int = 0,
    val currentScaleFactor: Float = 1.0f,
    val blockedTrackersInSession: Long = 0L,
)

sealed interface EngineSessionEvent {
    data class AnchorLongPressed(
        val rawUrl: String,
        val anchorTitle: String?,
        val touchXRatio: Float,
        val touchYRatio: Float,
    ) : EngineSessionEvent

    data class ScrollDeltaChanged(
        val scrollY: Int,
        val deltaY: Int,
    ) : EngineSessionEvent

    data class NavigationCommitted(
        val url: String,
        val isMainFrame: Boolean,
    ) : EngineSessionEvent

    data class PageLoaded(
        val url: String,
    ) : EngineSessionEvent

    data class DownloadRequested(
        val url: String,
        val mimeType: String?,
        val contentLength: Long,
        val userAgent: String? = null,
        val contentDisposition: String? = null,
    ) : EngineSessionEvent
}

/**
 * Dual-seam Browser Engine abstraction (`System WebView` in v1 -> `Mozilla GeckoView` in Phase 2).
 */
interface BrowserEngine {
    /** Pre-warms engine Zygote and spare off-screen session on idle frame after first paint. */
    fun warmup()

    /** Creates an isolated session; when [isPrivate] is `true`, binds to ephemeral storage (`◎`). */
    fun createSession(isPrivate: Boolean = false, spaceId: String? = null): EngineSession

    /** Wipes ephemeral cookies, `localStorage`, IndexedDB, and HTTP cache for Private Aperture `◎`. */
    fun clearEphemeralData()

    fun shutdown()
}

interface EngineSession {
    val sessionId: String
    val isPrivate: Boolean
    val spaceId: String?
    val state: StateFlow<EngineSessionState>
    val telemetry: StateFlow<EngineTelemetryState>
        get() = kotlinx.coroutines.flow.MutableStateFlow(EngineTelemetryState())
    val events: SharedFlow<EngineSessionEvent>

    fun loadUrl(url: String, headers: Map<String, String> = emptyMap())
    fun reload()
    fun stopLoading()
    fun goBack(): Boolean
    fun goForward(): Boolean

    fun setSiteOverrides(overrides: OriginOverrides)
    fun extractReadableArticle(onResult: (ReadableDocument) -> Unit)
    fun indexDomParagraphs(onResult: (List<IndexedDomParagraph>) -> Unit)
    fun scrollToGroundedParagraph(paraId: Int)
    fun checkHasUnsentFormInput(onResult: (Boolean) -> Unit)

    fun translatePageInPlace(targetLanguageCode: String? = null) {}
    fun restoreOriginalPageLanguage() {}

    fun setPlaybackRate(rate: Float)
    fun setMediaPlaying(playing: Boolean)
    fun requestElementFullscreenOrPip()
    fun findInPage(query: String)
    fun clearFindInPage()

    fun dispose()
}
