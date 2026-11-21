package app.focal

import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Rational
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.adblock.AdblockController
import app.focal.ai.GroundedPageSummary
import app.focal.ai.IndexedParagraph
import app.focal.ai.LocalSummarizer
import app.focal.database.BookmarkEntity
import app.focal.database.FocalDatabase
import app.focal.database.OriginOverrideEntity
import app.focal.database.TabMetadataEntity
import app.focal.design.AdaptiveGlassController
import app.focal.design.AppChromeTheme
import app.focal.design.FocalAppearanceStore
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseController
import app.focal.design.GestureLeaseOwner
import app.focal.design.LocalGestureLeaseController
import app.focal.design.ReaderPaperPalette
import app.focal.design.consumeClicks
import app.focal.design.shaders.LocalFocalBackdropLayer
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.api.EngineSession
import app.focal.engine.api.EngineSessionEvent
import app.focal.engine.api.OriginOverrides
import app.focal.engine.api.PageTranslationPhase
import app.focal.engine.api.PageTranslationStatus
import app.focal.feature.browser.BrowserViewportScreen
import app.focal.feature.browser.LinkPeekSheet
import app.focal.feature.browser.PinchSummarySheet
import app.focal.feature.media.FocalMediaBridge
import app.focal.feature.reader.PageMenuAndSiteOverridesSheet
import app.focal.feature.reader.ReaderDefaultsStore
import app.focal.feature.reader.ReaderModeView
import app.focal.feature.reader.ReaderSitePreferencesResolver
import app.focal.feature.search.DefaultSearchSquirclePins
import app.focal.feature.search.QuickSquirclePin
import app.focal.feature.search.SearchSheet
import app.focal.feature.spaces.BookmarksSheet
import app.focal.feature.spaces.BookmarksSheetUiState
import app.focal.feature.tabs.TabSwitcherSheet
import app.focal.sync.SyncRelaySessionManager
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var pendingExternalUri by mutableStateOf<String?>(null)
    private var isInPipModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            pendingExternalUri = intent?.dataString
        }
        isInPipModeState = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        val focalApp = application as FocalApplication

        setContent {
            val sessionVm: BrowserSessionViewModel = viewModel(
                factory = BrowserSessionViewModel.factory(focalApp),
            )
            FocalRootCoordinator(
                activity = this,
                sessionVm = sessionVm,
                adblockController = focalApp.adblockController,
                database = focalApp.database,
                localSummarizer = focalApp.localSummarizer,
                syncManager = focalApp.syncManager,
                isInPictureInPictureMode = isInPipModeState,
                externalIntentUri = pendingExternalUri,
                onExternalUriConsumed = { pendingExternalUri = null },
                onEnterSystemPip = ::enterBrowserPipMode,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingExternalUri = intent.dataString
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
    }

    private fun enterBrowserPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            try {
                enterPictureInPictureMode(params)
            } catch (_: IllegalStateException) {
                // Device or window configuration may disallow PiP
            }
        }
    }
}

@Composable
private fun FocalRootCoordinator(
    activity: ComponentActivity,
    sessionVm: BrowserSessionViewModel,
    adblockController: AdblockController,
    database: FocalDatabase,
    localSummarizer: LocalSummarizer,
    syncManager: SyncRelaySessionManager,
    isInPictureInPictureMode: Boolean,
    externalIntentUri: String?,
    onExternalUriConsumed: () -> Unit,
    onEnterSystemPip: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Off-thread 16x16 PixelCopy AdaptiveGlassController (0.44f / 0.52f hysteresis)
    val adaptiveGlass = remember { AdaptiveGlassController(initialDarkGlass = true) }
    val glassState by adaptiveGlass.state.collectAsState()

    DisposableEffect(adaptiveGlass) {
        onDispose { adaptiveGlass.close() }
    }

    // Configuration-surviving session state from BrowserSessionViewModel (P0-3)
    val liveSessions by sessionVm.liveSessions.collectAsState()
    val tabPreviews by sessionVm.tabPreviews.collectAsState()
    val privateTabs by sessionVm.privateTabs.collectAsState()
    val selectedTabId by sessionVm.selectedTabId.collectAsState()
    val isPrivateAperture by sessionVm.isPrivateAperture.collectAsState()
    val activeSpaceId by sessionVm.activeSpaceId.collectAsState()
    val overlaySheet by sessionVm.overlaySheet.collectAsState()
    val pendingNewTabInSearch by sessionVm.pendingNewTabInSearch.collectAsState()
    val pendingPairingRequest by sessionVm.pendingPairingRequest.collectAsState()

    // Room reactive streams wrapped in remember { toImmutableList() } (P2-1)
    val spaces by database.spaceDao().observeSpaces().collectAsState(initial = emptyList())
    val allBookmarks by remember {
        database.bookmarkDao().observeAllBookmarks()
    }.collectAsState(initial = emptyList())
    val quickSearchBookmarks by remember {
        database.bookmarkDao().observeQuickSearchPins(limit = 5)
    }.collectAsState(initial = emptyList())

    val allActiveTabs by remember {
        database.tabMetadataDao().observeAllActiveTabs()
    }.collectAsState(initial = emptyList())
    val activeTabsInSpace by remember(activeSpaceId) {
        database.tabMetadataDao().observeActiveTabsForSpace(activeSpaceId)
    }.collectAsState(initial = emptyList())
    val archivedTabs by remember {
        val retentionStart = System.currentTimeMillis() - TabMetadataEntity.ARCHIVE_RETENTION_MS
        database.tabMetadataDao().observeArchivedTabs(retentionStart)
    }.collectAsState(initial = emptyList())

    val immutableSpaces = remember(spaces) { spaces.toImmutableList() }
    val immutableAllBookmarks = remember(allBookmarks) { allBookmarks.toImmutableList() }
    val immutableActiveTabs = remember(activeTabsInSpace) { activeTabsInSpace.toImmutableList() }
    val immutableArchivedTabs = remember(archivedTabs) { archivedTabs.toImmutableList() }
    val immutableTabPreviews = remember(tabPreviews) { tabPreviews.toImmutableMap() }
    val tabCountBySpace = remember(allActiveTabs) {
        allActiveTabs.groupingBy { it.spaceId }.eachCount().toImmutableMap()
    }

    val syncUiState by syncManager.state.collectAsState()
    val mediaBridgeState by FocalMediaBridge.activeMedia.collectAsState()
    val dockMediaState by FocalMediaBridge.dockMedia.collectAsState()
    val hasActiveMedia = mediaBridgeState != null

    // Connect MediaSessionService lazily once background/foreground tab audio or video activates (P1-11)
    LaunchedEffect(context, hasActiveMedia) {
        if (hasActiveMedia) {
            FocalMediaBridge.ensureServiceConnected(context)
        }
    }

    val activeSession: EngineSession? = selectedTabId?.let { liveSessions[it] }
    val activeSessionState = activeSession?.state?.collectAsState()?.value

    // Observe per-origin overrides (including sticky Reader Mode settings) for current domain
    val currentOrigin = activeSessionState?.domain.orEmpty()
    val savedOverrideEntity by remember(currentOrigin) {
        database.originOverrideDao().observeOverrideForOrigin(currentOrigin)
    }.collectAsState(initial = null)

    val effectiveOverrides = remember(currentOrigin, savedOverrideEntity) {
        savedOverrideEntity?.toEngineOverrides() ?: OriginOverrides(origin = currentOrigin)
    }

    val readerDefaultsStore = remember(context) { ReaderDefaultsStore(context) }
    var globalReaderDefaults by remember(readerDefaultsStore) {
        mutableStateOf(readerDefaultsStore.read())
    }
    var privateReaderPrefsOverride by remember(isPrivateAperture) {
        mutableStateOf<app.focal.feature.reader.ReaderPreferences?>(null)
    }
    val effectiveReaderPrefs = remember(
        effectiveOverrides,
        globalReaderDefaults,
        isPrivateAperture,
        privateReaderPrefsOverride,
    ) {
        if (isPrivateAperture && privateReaderPrefsOverride != null) {
            privateReaderPrefsOverride!!
        } else {
            ReaderSitePreferencesResolver.resolve(effectiveOverrides, globalReaderDefaults)
        }
    }

    val appearanceStore = remember(context) { FocalAppearanceStore(context) }
    var appChromeTheme by remember(appearanceStore) {
        mutableStateOf(appearanceStore.readTheme())
    }
    val isSystemDark = isSystemInDarkTheme()
    val appDarkUi = appChromeTheme.resolveDarkUi(isSystemDark)

    val persistSiteOverrides: (OriginOverrides) -> Unit = { updated ->
        activeSession?.setSiteOverrides(updated)
        // Never write visited domain names to SQLite during Private Aperture ◎ sessions
        if (!isPrivateAperture && updated.origin.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                database.originOverrideDao().upsertOverride(
                    updated.toEntity(updatedAtEpochMs = System.currentTimeMillis()),
                )
            }
        }
    }

    // Sticky per-domain Auto-Reader Mode: auto-opens Reader when an eligible article finishes loading
    var dismissedAutoReaderUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(
        activeSession,
        activeSessionState?.url,
        activeSessionState?.isLoading,
        activeSessionState?.isReaderEligible,
        effectiveOverrides.autoReaderMode,
        overlaySheet == ActiveOverlaySheet.None,
    ) {
        val session = activeSession ?: return@LaunchedEffect
        val pageUrl = activeSessionState?.url.orEmpty()
        if (
            ReaderSitePreferencesResolver.shouldAutoOpenReader(
                autoReaderEnabled = effectiveOverrides.autoReaderMode,
                isReaderEligible = activeSessionState?.isReaderEligible == true,
                isPageLoading = activeSessionState?.isLoading != false,
                currentUrl = pageUrl,
                dismissedUrlsInSession = dismissedAutoReaderUrls,
                isOverlayCurrentlyOpen = overlaySheet != ActiveOverlaySheet.None,
            )
        ) {
            delay(180L)
            session.extractReadableArticle { doc ->
                if (doc.isSubstantive) {
                    sessionVm.setOverlaySheet(ActiveOverlaySheet.Reader(doc))
                } else {
                    dismissedAutoReaderUrls = dismissedAutoReaderUrls + pageUrl
                }
            }
        }
    }

    LaunchedEffect(activeSession, effectiveOverrides) {
        if (activeSession != null && currentOrigin.isNotBlank()) {
            activeSession.setSiteOverrides(effectiveOverrides)
        }
    }

    // Shared viewport GraphicsLayer for SmokedGlassBox live refraction across bars & modal sheets (P1-4)
    val viewportBackdropLayer = rememberGraphicsLayer()

    // Gesture & Shader FloatStates (read strictly inside Modifier.graphicsLayer for 0 recompositions)
    val pinchProgressState = remember { mutableFloatStateOf(0f) }
    val pinchCenterYState = remember { mutableFloatStateOf(0.5f) }
    val predictiveBackProgressState = remember { mutableFloatStateOf(0f) }
    val predictiveBackSwipeEdgeState = remember { mutableIntStateOf(0) }
    val bottomBarScrollHideState = remember { mutableFloatStateOf(0f) }
    var bottomBarHiddenTarget by remember { mutableStateOf(false) }

    // Spring-animate bottomBarScrollHideState instead of snapping 0f -> 1f (P1-3)
    LaunchedEffect(bottomBarHiddenTarget) {
        val target = if (bottomBarHiddenTarget) 1f else 0f
        if (bottomBarScrollHideState.floatValue != target) {
            Animatable(bottomBarScrollHideState.floatValue).animateTo(
                targetValue = target,
                animationSpec = FocalMotion.smartPillSpring(),
            ) {
                bottomBarScrollHideState.floatValue = value
            }
        }
    }

    var isSummarizing by remember { mutableStateOf(false) }
    var activeGroundedSummary by remember { mutableStateOf<GroundedPageSummary?>(null) }

    // Translate active page in place using 100% on-device ML Kit (zero external proxy tabs)
    val translateActivePage: () -> Unit = {
        val session = activeSession
        val rawUrl = activeSessionState?.url.orEmpty()
        if (session != null && rawUrl.isNotBlank() && rawUrl != "about:blank") {
            when (activeSessionState?.translationStatus?.phase ?: PageTranslationPhase.ORIGINAL) {
                PageTranslationPhase.ORIGINAL,
                PageTranslationPhase.FAILED,
                -> session.translatePageInPlace(targetLanguageCode = Locale.getDefault().language)

                PageTranslationPhase.DOWNLOADING_MODEL,
                PageTranslationPhase.TRANSLATING,
                PageTranslationPhase.TRANSLATED,
                -> session.restoreOriginalPageLanguage()
            }
        }
    }

    // Handle external VIEW / focal-pair:// intents with explicit SAS confirmation (P0-6)
    LaunchedEffect(externalIntentUri) {
        val uri = externalIntentUri ?: return@LaunchedEffect
        onExternalUriConsumed()
        if (uri.trim().startsWith("focal-pair://", ignoreCase = true)) {
            sessionVm.stageIncomingPairingUri(uri)
        } else {
            sessionVm.openUrlInTab(
                url = uri,
                privateMode = isPrivateAperture,
                openInBackground = false,
                forceNewTab = false,
            )
        }
    }

    val hasLoadedWebUrl = activeSession != null &&
        !activeSessionState?.url.isNullOrBlank() &&
        activeSessionState?.url != "about:blank"

    // Keep system status bar icons contrasted against Reader paper or top app chrome canvas,
    // and bottom navigation bar icons contrasted against Reader paper, live page bottom bar, or app chrome.
    val lightStatusBarIcons = if (overlaySheet is ActiveOverlaySheet.Reader) {
        effectiveReaderPrefs.theme.id == ReaderPaperPalette.Obsidian.id
    } else {
        appDarkUi || isPrivateAperture
    }
    val lightNavigationBarIcons = when {
        overlaySheet is ActiveOverlaySheet.Reader -> {
            effectiveReaderPrefs.theme.id == ReaderPaperPalette.Obsidian.id
        }
        overlaySheet == ActiveOverlaySheet.None && hasLoadedWebUrl && !isPrivateAperture -> {
            glassState.lightStatusBarIcons
        }
        else -> appDarkUi || isPrivateAperture
    }
    LaunchedEffect(lightStatusBarIcons, lightNavigationBarIcons) {
        val insetsController = WindowCompat.getInsetsController(activity.window, view)
        insetsController.isAppearanceLightStatusBars = !lightStatusBarIcons
        insetsController.isAppearanceLightNavigationBars = !lightNavigationBarIcons
    }

    val latestOverlaySheet by rememberUpdatedState(overlaySheet)
    val latestIsInPip by rememberUpdatedState(isInPictureInPictureMode)
    val latestHasLoadedWebUrl by rememberUpdatedState(hasLoadedWebUrl)

    val computeCleanWebSampleRect: () -> Rect? = {
        val rootInsets = ViewCompat.getRootWindowInsets(view)
        val statusTopPx = rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val navBottomPx = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val dockClearancePx = with(density) { 140.dp.roundToPx() }
        val stripHeightPx = with(density) { 48.dp.roundToPx() }
        val horizontalInsetPx = with(density) { 16.dp.roundToPx() }
        AdaptiveGlassController.computeUnobstructedSampleRect(
            viewportWidthPx = view.width,
            viewportHeightPx = view.height,
            statusBarTopPx = statusTopPx,
            navigationBarBottomPx = navBottomPx,
            dockClearancePx = dockClearancePx,
            stripHeightPx = stripHeightPx,
            horizontalInsetPx = horizontalInsetPx,
        )
    }

    var settleSampleJob by remember { mutableStateOf<Job?>(null) }
    val scheduleSettledGlassSamples: (LongArray) -> Unit = { delaysMs ->
        settleSampleJob?.cancel()
        settleSampleJob = scope.launch {
            for (waitMs in delaysMs) {
                if (waitMs > 0L) delay(waitMs)
                if (!latestHasLoadedWebUrl) {
                    adaptiveGlass.resetToDarkOled()
                    return@launch
                }
                if (latestOverlaySheet == ActiveOverlaySheet.None && !latestIsInPip) {
                    computeCleanWebSampleRect()?.let { rect ->
                        adaptiveGlass.onNavigationCommitted(activity.window, rect)
                    }
                }
            }
        }
    }

    // Re-sample when switching tabs, closing modal scrim overlays, toggling dark web pages,
    // or returning to the pure-dark Calm Launch Canvas.
    LaunchedEffect(
        selectedTabId,
        hasLoadedWebUrl,
        overlaySheet == ActiveOverlaySheet.None,
        effectiveOverrides.forceDarkWebSurface,
    ) {
        if (!hasLoadedWebUrl) {
            settleSampleJob?.cancel()
            adaptiveGlass.resetToDarkOled()
        } else if (overlaySheet == ActiveOverlaySheet.None && !isInPictureInPictureMode) {
            scheduleSettledGlassSamples(longArrayOf(220L, 550L))
        }
    }

    // Sync active EngineSession UI events (Link Peek, Scroll Luminance, Navigation, PageLoaded, Downloads)
    LaunchedEffect(activeSession) {
        val session = activeSession ?: return@LaunchedEffect
        val hideDeltaPx = with(density) { 18.dp.roundToPx() }
        val showDeltaPx = -with(density) { 8.dp.roundToPx() }

        session.events.collect { event ->
            when (event) {
                is EngineSessionEvent.AnchorLongPressed -> {
                    sessionVm.setOverlaySheet(ActiveOverlaySheet.LinkPeek(event.rawUrl))
                }
                is EngineSessionEvent.ScrollDeltaChanged -> {
                    if (event.deltaY > hideDeltaPx) {
                        bottomBarHiddenTarget = true
                    } else if (event.deltaY < showDeltaPx || event.scrollY <= 0) {
                        bottomBarHiddenTarget = false
                    }
                    if (
                        latestOverlaySheet == ActiveOverlaySheet.None &&
                        !latestIsInPip &&
                        latestHasLoadedWebUrl
                    ) {
                        computeCleanWebSampleRect()?.let { rect ->
                            adaptiveGlass.onViewportScrolled(activity.window, rect)
                        }
                    }
                }
                is EngineSessionEvent.NavigationCommitted -> {
                    bottomBarHiddenTarget = false
                    // Delay past WebView's transient white blank-canvas flash before first read
                    scheduleSettledGlassSamples(longArrayOf(180L, 550L))
                }
                is EngineSessionEvent.PageLoaded -> {
                    // Re-sample after DOM/CSS load and late SPA hydration (e.g., Next.js/Tailwind themes)
                    scheduleSettledGlassSamples(longArrayOf(80L, 420L, 1_000L))
                    val loadedTabId = sessionVm.selectedTabId.value
                    if (loadedTabId != null) {
                        scope.launch {
                            delay(280L)
                            if (sessionVm.selectedTabId.value == loadedTabId && latestOverlaySheet == ActiveOverlaySheet.None) {
                                sessionVm.capturePreviewForTab(loadedTabId)
                            }
                        }
                    }
                }
                is EngineSessionEvent.DownloadRequested -> {
                    enqueueSafeBrowserDownload(
                        context = context,
                        event = event,
                        isPrivateSession = session.isPrivate,
                    )
                }
            }
        }
    }

    // Lease-based exclusivity across tab scrub, Pinch-to-Summarize, sheet drags, and Predictive Back
    val gestureLeaseController = remember { GestureLeaseController() }

    // Android 14–16 Predictive Back Handler: shrinks & translates active sheet or web page 1:1 with finger (P1-5)
    val canInterceptBack = pendingPairingRequest != null ||
        overlaySheet != ActiveOverlaySheet.None ||
        (activeSessionState?.canGoBack == true)
    PredictiveBackHandler(enabled = canInterceptBack) { backFlow ->
        val backToken = gestureLeaseController.preemptForSystem(GestureLeaseOwner.PREDICTIVE_BACK)
        try {
            backFlow.collect { backEvent ->
                predictiveBackSwipeEdgeState.intValue = backEvent.swipeEdge
                predictiveBackProgressState.floatValue = backEvent.progress
            }
            predictiveBackProgressState.floatValue = 0f
            when {
                pendingPairingRequest != null -> sessionVm.dismissPendingPairing()
                overlaySheet == ActiveOverlaySheet.TabSwitcher -> {
                    sessionVm.dismissTabSwitcher(immutableActiveTabs)
                }
                overlaySheet != ActiveOverlaySheet.None -> {
                    if (overlaySheet == ActiveOverlaySheet.PinchSummary) {
                        pinchProgressState.floatValue = 0f
                    }
                    if (overlaySheet is ActiveOverlaySheet.Reader) {
                        activeSessionState?.url?.takeIf { it.isNotBlank() }?.let { url ->
                            dismissedAutoReaderUrls = dismissedAutoReaderUrls + url
                        }
                    }
                    sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                }
                else -> activeSession?.goBack()
            }
        } catch (_: CancellationException) {
            predictiveBackProgressState.floatValue = 0f
        } finally {
            gestureLeaseController.release(backToken)
        }
    }

    val searchPins = remember(quickSearchBookmarks) {
        if (quickSearchBookmarks.isEmpty()) {
            DefaultSearchSquirclePins
        } else {
            quickSearchBookmarks.mapNotNull { b ->
                val url = b.url ?: return@mapNotNull null
                QuickSquirclePin(
                    id = b.id,
                    shortCode = b.shortCode ?: b.title.take(2).uppercase(),
                    title = b.title,
                    url = url,
                )
            }.toImmutableList()
        }
    }

    val displayedTabCount = if (isPrivateAperture) {
        privateTabs.size.coerceAtLeast(if (activeSession?.isPrivate == true) 1 else 0)
    } else {
        immutableActiveTabs.size.coerceAtLeast(if (activeSession != null) 1 else 1)
    }

    val summarizerBackendBadge = remember(localSummarizer, overlaySheet) {
        localSummarizer.detectActiveBackend().badgeLabel
    }

    FocalTheme(
        darkGlass = appDarkUi,
        isPrivateAperture = isPrivateAperture,
    ) {
        CompositionLocalProvider(LocalGestureLeaseController provides gestureLeaseController) {
            Box(modifier = Modifier.fillMaxSize()) {
            BrowserViewportScreen(
                activeSession = activeSession,
                tabCount = displayedTabCount,
                isPrivateAperture = isPrivateAperture,
                adaptiveBottomBarDarkGlass = glassState.isDarkGlass,
                isOverlaySheetOpen = overlaySheet != ActiveOverlaySheet.None,
                isInPictureInPictureMode = isInPictureInPictureMode,
                backdropGraphicsLayer = viewportBackdropLayer,
                continuityHandoff = syncUiState.incomingHandoff,
                mediaMiniPlayerInfo = dockMediaState?.media,
                isMediaMiniPlayerSuppressed = mediaBridgeState != null && dockMediaState == null,
                isMediaFromBackgroundTab = dockMediaState != null && dockMediaState?.sessionTabId != selectedTabId,
                pinchProgressState = pinchProgressState,
                pinchCenterYState = pinchCenterYState,
                predictiveBackProgressState = predictiveBackProgressState,
                bottomBarScrollHideFraction = bottomBarScrollHideState,
                onOpenSearchSheet = {
                    sessionVm.openSearchSheet(forNewTab = false)
                },
                onOpenTabSwitcher = {
                    sessionVm.openTabSwitcher()
                },
                onCloseCurrentTab = {
                    val closingId = selectedTabId
                    if (closingId != null) {
                        sessionVm.closeTab(closingId, immutableActiveTabs)
                    }
                },
                onOpenNewPrivateTab = {
                    sessionVm.openSearchSheet(forNewTab = true, privateMode = true)
                },
                onStepToAdjacentTab = { delta ->
                    sessionVm.stepToAdjacentTab(delta, immutableActiveTabs)
                },
                onOpenPageMenu = {
                    sessionVm.setOverlaySheet(ActiveOverlaySheet.PageMenu)
                },
                onOpenReaderMode = {
                    activeSession?.extractReadableArticle { doc ->
                        sessionVm.setOverlaySheet(ActiveOverlaySheet.Reader(doc))
                    }
                },
                onTranslatePage = translateActivePage,
                onPinchSnapSummarize = {
                    val session = activeSession ?: return@BrowserViewportScreen
                    sessionVm.setOverlaySheet(ActiveOverlaySheet.PinchSummary)
                    isSummarizing = true
                    scope.launch {
                        Animatable(pinchProgressState.floatValue).animateTo(
                            targetValue = 1f,
                            animationSpec = FocalMotion.pinchSnapSpring(),
                        ) {
                            pinchProgressState.floatValue = value
                        }
                    }
                    session.indexDomParagraphs { domParas ->
                        scope.launch {
                            val indexed = domParas.map {
                                IndexedParagraph(paraId = it.paraId, text = it.text)
                            }
                            activeGroundedSummary = localSummarizer.summarizeAndGround(
                                pageTitle = activeSessionState?.title.orEmpty(),
                                paragraphs = indexed,
                            )
                            isSummarizing = false
                        }
                    }
                },
                onPinchSnapCancelled = {
                    scope.launch {
                        Animatable(pinchProgressState.floatValue).animateTo(
                            targetValue = 0f,
                            animationSpec = FocalMotion.pinchSnapSpring(),
                        ) {
                            pinchProgressState.floatValue = value
                        }
                    }
                },
                onOpenContinuityHandoff = { handoff ->
                    syncManager.dismissIncomingHandoff()
                    sessionVm.openUrlInTab(
                        url = handoff.url,
                        privateMode = isPrivateAperture,
                        openInBackground = false,
                        forceNewTab = false,
                    )
                },
                onToggleMediaPlayPause = { play ->
                    FocalMediaBridge.dispatchTogglePlayPause(play)
                },
                onCycleMediaPlaybackRate = { rate ->
                    FocalMediaBridge.dispatchPlaybackRate(rate)
                },
                onEnterPictureInPicture = {
                    FocalMediaBridge.dispatchRequestPip()
                    onEnterSystemPip()
                },
                onFocusMediaTab = dockMediaState?.sessionTabId
                    ?.takeIf { it != selectedTabId }
                    ?.let { playingTabId ->
                        { sessionVm.selectTab(playingTabId, allActiveTabs) }
                    },
                onDismissMediaMiniPlayer = {
                    FocalMediaBridge.dismissMiniPlayer()
                },
            )

            // Active Floating Sheet Layer (suppressed during PiP; coupled 1:1 with Predictive Back)
            if (!isInPictureInPictureMode) {
                // Scrim-backed modal sheets sit over a 52% dimmed backdrop where smokedGlassSurface
                // matches visually without forcing an offscreen 28.dp full-viewport blur pass on cold start.
                CompositionLocalProvider(LocalFocalBackdropLayer provides null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val backP = predictiveBackProgressState.floatValue
                                if (backP > 0f) {
                                    val edgeSign = if (predictiveBackSwipeEdgeState.intValue == 0) 1f else -1f
                                    scaleX = 1f - backP * 0.08f
                                    scaleY = 1f - backP * 0.08f
                                    translationX = edgeSign * backP * 24.dp.toPx()
                                    translationY = backP * 12.dp.toPx()
                                    alpha = 1f - backP * 0.25f
                                } else {
                                    scaleX = 1f
                                    scaleY = 1f
                                    translationX = 0f
                                    translationY = 0f
                                    alpha = 1f
                                }
                            },
                    ) {
                        var hasLeftInitialSearch by remember { mutableStateOf(overlaySheet != ActiveOverlaySheet.Search) }
                        LaunchedEffect(overlaySheet) {
                            if (overlaySheet != ActiveOverlaySheet.Search) {
                                hasLeftInitialSearch = true
                            }
                        }
                        AnimatedContent(
                            targetState = overlaySheet,
                            contentKey = { sheet ->
                                when (sheet) {
                                    is ActiveOverlaySheet.Reader -> "reader:${sheet.document.url}"
                                    is ActiveOverlaySheet.LinkPeek -> "link:${sheet.rawUrl}"
                                    else -> sheet::class.simpleName.orEmpty()
                                }
                            },
                            transitionSpec = {
                                val enteringFullScreen = targetState == ActiveOverlaySheet.TabSwitcher ||
                                    targetState is ActiveOverlaySheet.Reader
                                val exitingFullScreen = initialState == ActiveOverlaySheet.TabSwitcher ||
                                    initialState is ActiveOverlaySheet.Reader
                                val enter = when {
                                    targetState == ActiveOverlaySheet.None -> fadeIn(animationSpec = tween(1))
                                    enteringFullScreen && initialState == ActiveOverlaySheet.None -> {
                                        fadeIn(
                                            animationSpec = tween(165, easing = FastOutSlowInEasing),
                                        ) + scaleIn(
                                            initialScale = 0.98f,
                                            animationSpec = FocalMotion.spacesSheetSpring(),
                                        )
                                    }
                                    else -> fadeIn(animationSpec = tween(155, easing = FastOutSlowInEasing))
                                }
                                val exit = when {
                                    initialState == ActiveOverlaySheet.None -> fadeOut(animationSpec = tween(1))
                                    exitingFullScreen && targetState == ActiveOverlaySheet.None -> {
                                        fadeOut(
                                            animationSpec = tween(130, easing = FastOutSlowInEasing),
                                        ) + scaleOut(
                                            targetScale = 0.985f,
                                            animationSpec = tween(130, easing = FastOutSlowInEasing),
                                        )
                                    }
                                    else -> fadeOut(animationSpec = tween(120, easing = FastOutSlowInEasing))
                                }
                                (enter togetherWith exit).using(SizeTransform(clip = false))
                            },
                            label = "FocalActiveOverlaySheet",
                        ) { currentOverlay ->
                        when (currentOverlay) {
                            ActiveOverlaySheet.None -> Unit

                            ActiveOverlaySheet.Search -> {
                                val prefillQuery = if (
                                    pendingNewTabInSearch ||
                                    activeSession?.isPrivate != isPrivateAperture
                                ) {
                                    ""
                                } else {
                                    activeSessionState?.url?.takeIf { it != "about:blank" }.orEmpty()
                                }
                                SearchSheet(
                                    initialQuery = prefillQuery,
                                    isPrivateAperture = isPrivateAperture,
                                    pins = searchPins,
                                    continuityHandoff = syncUiState.incomingHandoff,
                                    onTogglePrivateAperture = { sessionVm.setPrivateAperture(it) },
                                    onNavigateToUrl = { targetUrl, priv ->
                                        sessionVm.openUrlInTab(
                                            url = targetUrl,
                                            privateMode = priv,
                                            openInBackground = false,
                                        )
                                    },
                                    onDismiss = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    animateEntrance = hasLeftInitialSearch,
                                )
                            }

                            ActiveOverlaySheet.Bookmarks -> {
                                val bookmarksUiState = remember(
                                    immutableSpaces,
                                    activeSpaceId,
                                    immutableAllBookmarks,
                                    syncUiState,
                                ) {
                                    BookmarksSheetUiState(
                                        spaces = immutableSpaces,
                                        activeSpaceId = activeSpaceId,
                                        allBookmarks = immutableAllBookmarks,
                                        syncState = syncUiState,
                                    )
                                }
                                BookmarksSheet(
                                    uiState = bookmarksUiState,
                                    onOpenUrl = { url ->
                                        sessionVm.openUrlInTab(
                                            url = url,
                                            privateMode = false,
                                            openInBackground = false,
                                            forceNewTab = false,
                                        )
                                    },
                                    onDeleteBookmark = { bookmarkId ->
                                        scope.launch(Dispatchers.IO) {
                                            database.bookmarkDao().deleteBookmark(bookmarkId)
                                        }
                                    },
                                    onCreateFolder = { spaceId, folderName ->
                                        val now = System.currentTimeMillis()
                                        scope.launch(Dispatchers.IO) {
                                            database.bookmarkDao().upsertBookmark(
                                                BookmarkEntity(
                                                    id = "folder-${UUID.randomUUID()}",
                                                    spaceId = spaceId,
                                                    isFolder = true,
                                                    title = folderName,
                                                    subtitle = "Empty folder",
                                                    createdAtEpochMs = now,
                                                    updatedAtEpochMs = now,
                                                ),
                                            )
                                        }
                                    },
                                    onStartE2eePairing = {
                                        sessionVm.startBootstrapPairing()
                                    },
                                    onDismiss = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                )
                            }

                            ActiveOverlaySheet.TabSwitcher -> {
                                TabSwitcherSheet(
                                    activeTabs = immutableActiveTabs,
                                    archivedTabs = immutableArchivedTabs,
                                    selectedTabId = selectedTabId,
                                    spaces = immutableSpaces,
                                    activeSpaceId = activeSpaceId,
                                    tabCountBySpace = tabCountBySpace,
                                    tabPreviews = immutableTabPreviews,
                                    isPrivateAperture = isPrivateAperture,
                                    privateTabs = privateTabs,
                                    onSelectSpace = { nextSpaceId ->
                                        sessionVm.selectSpace(nextSpaceId)
                                    },
                                    onTogglePrivateMode = { priv ->
                                        sessionVm.setPrivateAperture(priv)
                                    },
                                    onSelectTab = { tabId ->
                                        sessionVm.selectTab(tabId, immutableActiveTabs)
                                    },
                                    onCloseTab = { tabId ->
                                        sessionVm.closeTab(tabId, immutableActiveTabs)
                                    },
                                    onMoveTabToSpace = { tabId, targetSpaceId ->
                                        sessionVm.moveTabToSpace(tabId, targetSpaceId, immutableActiveTabs)
                                    },
                                    onCloseAllPrivateTabs = {
                                        sessionVm.closeAllPrivateTabs(immutableActiveTabs)
                                    },
                                    onRestoreArchivedTab = { archivedId ->
                                        scope.launch(Dispatchers.IO) {
                                            database.tabMetadataDao().restoreArchivedTab(
                                                tabId = archivedId,
                                                nowEpochMs = System.currentTimeMillis(),
                                            )
                                        }
                                    },
                                    onOpenBookmarks = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.Bookmarks)
                                    },
                                    onNewSearch = { priv ->
                                        sessionVm.openSearchSheet(forNewTab = true, privateMode = priv)
                                    },
                                    onDone = {
                                        sessionVm.dismissTabSwitcher(immutableActiveTabs)
                                    },
                                )
                            }

                            ActiveOverlaySheet.PageMenu -> {
                                val rawUrl = activeSessionState?.url.orEmpty()
                                val cleanedCurrentUrl = remember(rawUrl) {
                                    if (rawUrl.isBlank() || rawUrl == "about:blank") {
                                        ""
                                    } else {
                                        adblockController.cleanTrackingUrl(rawUrl).cleanUrl
                                    }
                                }
                                val existingBookmark = remember(immutableAllBookmarks, cleanedCurrentUrl) {
                                    val normalized = cleanedCurrentUrl.trim().trimEnd('/').lowercase()
                                    if (normalized.isBlank()) {
                                        null
                                    } else {
                                        immutableAllBookmarks.firstOrNull { bm ->
                                            !bm.isFolder &&
                                                bm.url.orEmpty().trim().trimEnd('/').lowercase() == normalized
                                        }
                                    }
                                }
                                val sessionTelemetry = activeSession?.telemetry?.collectAsState()?.value
                                PageMenuAndSiteOverridesSheet(
                                    domain = activeSessionState?.domain.orEmpty(),
                                    fullUrl = rawUrl,
                                    favicon = activeSessionState?.favicon,
                                    isPrivateAperture = isPrivateAperture,
                                    canGoBack = activeSessionState?.canGoBack == true,
                                    canGoForward = activeSessionState?.canGoForward == true,
                                    blockedCountInTab = sessionTelemetry?.blockedTrackersInSession ?: 0L,
                                    overrides = effectiveOverrides,
                                    appTheme = appChromeTheme,
                                    translationStatus = activeSessionState?.translationStatus ?: PageTranslationStatus(),
                                    isCurrentPageBookmarked = existingBookmark != null,
                                    onGoBack = {
                                        activeSession?.goBack()
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onGoForward = {
                                        activeSession?.goForward()
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onReload = {
                                        activeSession?.reload()
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onCopyCleanLink = {
                                        val cleaned = adblockController.cleanTrackingUrl(rawUrl).cleanUrl
                                        val cm = context.getSystemService(ClipboardManager::class.java)
                                        cm?.setPrimaryClip(ClipData.newPlainText("Clean URL", cleaned))
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onShareCleanLink = {
                                        val cleaned = adblockController.cleanTrackingUrl(rawUrl).cleanUrl
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, cleaned)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, null))
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onSaveBookmark = {
                                        if (cleanedCurrentUrl.isNotBlank()) {
                                            val currentSaved = existingBookmark
                                            if (currentSaved != null) {
                                                scope.launch(Dispatchers.IO) {
                                                    database.bookmarkDao().deleteBookmark(currentSaved.id)
                                                }
                                            } else {
                                                val now = System.currentTimeMillis()
                                                scope.launch(Dispatchers.IO) {
                                                    database.bookmarkDao().upsertBookmark(
                                                        BookmarkEntity(
                                                            id = "bm-${UUID.randomUUID()}",
                                                            spaceId = activeSpaceId,
                                                            title = activeSessionState?.title?.ifBlank { cleanedCurrentUrl }
                                                                ?: cleanedCurrentUrl,
                                                            url = cleanedCurrentUrl,
                                                            createdAtEpochMs = now,
                                                            updatedAtEpochMs = now,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onOpenBookmarks = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.Bookmarks)
                                    },
                                    onOpenReaderMode = {
                                        activeSession?.extractReadableArticle { doc ->
                                            sessionVm.setOverlaySheet(ActiveOverlaySheet.Reader(doc))
                                        }
                                    },
                                    onTranslatePage = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                        translateActivePage()
                                    },
                                    onUpdateSiteOverrides = { updated ->
                                        if (updated.autoReaderMode && rawUrl.isNotBlank()) {
                                            dismissedAutoReaderUrls = dismissedAutoReaderUrls - rawUrl
                                        }
                                        persistSiteOverrides(updated)
                                    },
                                    onUpdateAppTheme = { nextTheme ->
                                        appChromeTheme = nextTheme
                                        appearanceStore.writeTheme(nextTheme)
                                    },
                                    onDismiss = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                )
                            }

                            is ActiveOverlaySheet.Reader -> {
                                val readerPageUrl = activeSessionState?.url.orEmpty()
                                ReaderModeView(
                                    document = currentOverlay.document,
                                    siteDomain = currentOrigin,
                                    initialPreferences = effectiveReaderPrefs,
                                    autoReaderEnabled = effectiveOverrides.autoReaderMode,
                                    canPersistForDomain = !isPrivateAperture && currentOrigin.isNotBlank(),
                                    onPreferencesChanged = { updatedPrefs ->
                                        if (isPrivateAperture) {
                                            privateReaderPrefsOverride = updatedPrefs
                                        } else {
                                            globalReaderDefaults = updatedPrefs
                                            readerDefaultsStore.write(updatedPrefs)
                                            persistSiteOverrides(
                                                ReaderSitePreferencesResolver.applyToOverrides(
                                                    baseOverrides = effectiveOverrides,
                                                    preferences = updatedPrefs,
                                                ),
                                            )
                                        }
                                    },
                                    onToggleAutoReader = { enabled ->
                                        if (enabled && readerPageUrl.isNotBlank()) {
                                            dismissedAutoReaderUrls = dismissedAutoReaderUrls - readerPageUrl
                                        }
                                        persistSiteOverrides(
                                            effectiveOverrides.copy(autoReaderMode = enabled),
                                        )
                                    },
                                    onCloseReader = {
                                        if (readerPageUrl.isNotBlank()) {
                                            dismissedAutoReaderUrls = dismissedAutoReaderUrls + readerPageUrl
                                        }
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                )
                            }

                            ActiveOverlaySheet.PinchSummary -> {
                                val unpinchAndClose: () -> Unit = {
                                    sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    scope.launch {
                                        Animatable(pinchProgressState.floatValue).animateTo(
                                            targetValue = 0f,
                                            animationSpec = FocalMotion.pinchSnapSpring(),
                                        ) {
                                            pinchProgressState.floatValue = value
                                        }
                                    }
                                }

                                PinchSummarySheet(
                                    isSummarizing = isSummarizing,
                                    summary = activeGroundedSummary,
                                    activeBackendLabel = summarizerBackendBadge,
                                    onJumpToGroundedParagraph = { bullet ->
                                        unpinchAndClose()
                                        activeSession?.scrollToGroundedParagraph(bullet.groundedParaId)
                                    },
                                    onDismiss = unpinchAndClose,
                                )
                            }

                            is ActiveOverlaySheet.LinkPeek -> {
                                val cleaned = remember(currentOverlay.rawUrl) {
                                    adblockController.cleanTrackingUrl(currentOverlay.rawUrl)
                                }
                                LinkPeekSheet(
                                    rawUrl = currentOverlay.rawUrl,
                                    cleanedResult = cleaned,
                                    adblockController = adblockController,
                                    onOpenInBackgroundTab = { cleanUrl ->
                                        sessionVm.openUrlInTab(
                                            url = cleanUrl,
                                            privateMode = isPrivateAperture,
                                            openInBackground = true,
                                            forceNewTab = true,
                                        )
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onExpandFullTab = { cleanUrl ->
                                        sessionVm.openUrlInTab(
                                            url = cleanUrl,
                                            privateMode = isPrivateAperture,
                                            openInBackground = false,
                                            forceNewTab = false,
                                        )
                                    },
                                    onDismiss = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                )
                            }
                        }
                        }

                        // Explicit SAS & Relay Host Confirmation Sheet for focal-pair:// intents (P0-6)
                        val pairingRequest = pendingPairingRequest
                        if (pairingRequest != null) {
                            IncomingPairingConfirmationModal(
                                request = pairingRequest,
                                onConfirmAndPair = { sessionVm.confirmPendingPairing() },
                                onCancel = { sessionVm.dismissPendingPairing() },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * Explicit user confirmation sheet for `focal-pair://` deep links (`P0-6`), preventing zero-click
 * E2EE session hijacks by requiring visual verification of the 4-part SAS code and relay host.
 */
@Composable
private fun IncomingPairingConfirmationModal(
    request: PendingPairingRequest,
    onConfirmAndPair: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sheetTint = rememberUpdatedState(colors.effectiveSheetGlass)
    val preview = request.preview

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = RoundedCornerShape(26.dp),
            glassTintState = sheetTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (preview != null) "Confirm Focal Bridge Pairing" else "Blocked Pairing Link",
                    style = typography.sheetTitle,
                    color = colors.inkPrimary,
                )

                if (preview == null) {
                    Text(
                        text = request.errorMessage ?: "Invalid or insecure focal-pair:// URI.",
                        style = typography.bodyCompact,
                        color = colors.inkSecondary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(colors.surfacePillActive)
                            .clickable(onClick = onCancel)
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Dismiss",
                            style = typography.controlPillLabel,
                            color = colors.inkOnPillActive,
                        )
                    }
                } else {
                    Text(
                        text = "Verify that the 4-part Short Authentication String below matches your Desktop Focal Bridge extension before pairing.",
                        style = typography.bodyCompact,
                        color = colors.inkSecondary,
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surfacePillSubtle)
                            .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "SHORT AUTHENTICATION STRING (SAS)",
                            style = typography.captionMetadata,
                            color = colors.inkFaint,
                        )
                        Text(
                            text = preview.sas,
                            style = typography.cardTitle,
                            color = colors.inkPrimary,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surfacePillSubtle)
                            .border(1.dp, colors.borderSpecular, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (preview.isCustomRelay) {
                                "⚠ CUSTOM RELAY HOST · VERIFY TRUST"
                            } else {
                                "TLS RELAY ENDPOINT"
                            },
                            style = typography.captionMetadata,
                            color = if (preview.isCustomRelay) colors.inkPrimary else colors.inkFaint,
                        )
                        Text(
                            text = "${preview.relayHost} · room ${preview.roomIdShort}…",
                            style = typography.urlDomainPill,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(colors.surfacePillSubtle)
                                .border(1.dp, colors.borderSpecular, CircleShape)
                                .clickable(onClick = onCancel)
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Cancel",
                                style = typography.controlPillLabel,
                                color = colors.inkPrimary,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(colors.surfacePillActive)
                                .clickable(onClick = onConfirmAndPair)
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Confirm & Pair",
                                style = typography.controlPillLabel,
                                color = colors.inkOnPillActive,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun enqueueSafeBrowserDownload(
    context: Context,
    event: EngineSessionEvent.DownloadRequested,
    isPrivateSession: Boolean,
) {
    val rawUrl = event.url.trim()
    if (!rawUrl.startsWith("https://", ignoreCase = true) && !rawUrl.startsWith("http://", ignoreCase = true)) {
        return
    }
    try {
        val uri = Uri.parse(rawUrl)
        val fileName = URLUtil.guessFileName(
            rawUrl,
            event.contentDisposition?.takeIf { it.isNotBlank() },
            event.mimeType?.takeIf { it.isNotBlank() },
        )
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        if (!event.mimeType.isNullOrBlank()) {
            request.setMimeType(event.mimeType)
        }
        if (!event.userAgent.isNullOrBlank()) {
            request.addRequestHeader("User-Agent", event.userAgent)
        }
        if (!isPrivateSession) {
            val cookie = runCatching { CookieManager.getInstance().getCookie(rawUrl) }.getOrNull()
            if (!cookie.isNullOrBlank()) {
                request.addRequestHeader("Cookie", cookie)
            }
        }
        context.getSystemService(DownloadManager::class.java)?.enqueue(request)
    } catch (_: Throwable) {
        // Ignore malformed download URIs or missing DownloadManager on stripped ROMs
    }
}

private fun OriginOverrideEntity.toEngineOverrides(): OriginOverrides = OriginOverrides(
    origin = origin,
    blockAdsAndTrackers = blockAdsAndTrackers,
    forceDarkWebSurface = forceDarkWebSurface,
    overridePublisherFonts = overridePublisherFonts,
    pageZoomPercent = pageZoomPercent,
    javascriptEnabled = javascriptEnabled,
    desktopSite = desktopSite,
    autoReaderMode = autoReaderMode,
    readerThemeId = readerThemeId,
    readerUseSerif = readerUseSerif,
    readerFontSizeSp = readerFontSizeSp,
    readerLineHeight = readerLineHeight,
)

private fun OriginOverrides.toEntity(updatedAtEpochMs: Long): OriginOverrideEntity = OriginOverrideEntity(
    origin = origin,
    blockAdsAndTrackers = blockAdsAndTrackers,
    forceDarkWebSurface = forceDarkWebSurface,
    overridePublisherFonts = overridePublisherFonts,
    pageZoomPercent = pageZoomPercent,
    javascriptEnabled = javascriptEnabled,
    desktopSite = desktopSite,
    autoReaderMode = autoReaderMode,
    readerThemeId = readerThemeId,
    readerUseSerif = readerUseSerif,
    readerFontSizeSp = readerFontSizeSp,
    readerLineHeight = readerLineHeight,
    updatedAtEpochMs = updatedAtEpochMs,
)
