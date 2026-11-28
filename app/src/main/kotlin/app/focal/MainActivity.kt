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
import android.os.SystemClock
import android.util.Rational
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import app.focal.database.DownloadRecordEntity
import app.focal.database.FocalDatabase
import app.focal.database.OriginHiddenSelectorsCodec
import app.focal.database.OriginOverrideEntity
import app.focal.database.TabMetadataEntity
import app.focal.design.AdaptiveGlassController
import app.focal.design.AppChromeTheme
import app.focal.design.FocalAppearanceStore
import app.focal.design.FocalBrowserSettings
import app.focal.design.FocalGlyphs
import app.focal.design.FocalMotion
import app.focal.design.FocalSettingsStore
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
import app.focal.feature.browser.PullToRefreshPhysics
import app.focal.feature.browser.ScrollVelocityBarController
import app.focal.feature.media.FocalMediaBridge
import app.focal.feature.reader.GlobalSettingsSheet
import app.focal.feature.reader.PageMenuAndSiteOverridesSheet
import app.focal.feature.reader.ReaderDefaultsStore
import app.focal.feature.reader.ReaderModeView
import app.focal.feature.reader.ReaderSitePreferencesResolver
import app.focal.feature.reader.UpdateBannerState
import app.focal.feature.search.DefaultSearchSquirclePins
import app.focal.feature.search.QuickSquirclePin
import app.focal.feature.search.SearchSheet
import app.focal.feature.spaces.BookmarksSheet
import app.focal.feature.spaces.BookmarksSheetUiState
import app.focal.feature.spaces.DownloadsSheet
import app.focal.feature.spaces.HistorySheet
import app.focal.feature.tabs.TabSwitcherSheet
import app.focal.sync.SyncRelaySessionManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var pendingExternalUri by mutableStateOf<String?>(null)
    private var pendingLaunchMode by mutableStateOf<String?>(null)
    private var isInPipModeState by mutableStateOf(false)
    private var activeSessionVm: BrowserSessionViewModel? = null

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
            pendingExternalUri = FocalExternalIntentAndDownloadMath.extractLaunchUrl(
                action = intent?.action,
                dataString = intent?.dataString,
                sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT),
            )
            pendingLaunchMode = intent?.getStringExtra(FocalQuickLaunchWidget.EXTRA_LAUNCH_MODE)
        }
        isInPipModeState = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        val focalApp = application as FocalApplication

        setContent {
            val sessionVm: BrowserSessionViewModel = viewModel(
                factory = BrowserSessionViewModel.factory(focalApp),
            )
            activeSessionVm = sessionVm
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
                pendingLaunchMode = pendingLaunchMode,
                onLaunchModeConsumed = { pendingLaunchMode = null },
                onEnterSystemPip = ::enterBrowserPipMode,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        activeSessionVm?.onAppForegroundChanged(inForeground = true)
    }

    override fun onPause() {
        activeSessionVm?.onAppForegroundChanged(inForeground = false)
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        activeSessionVm?.onTrimMemory(level)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingExternalUri = FocalExternalIntentAndDownloadMath.extractLaunchUrl(
            action = intent.action,
            dataString = intent.dataString,
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT),
        )
        pendingLaunchMode = intent.getStringExtra(FocalQuickLaunchWidget.EXTRA_LAUNCH_MODE)
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
    pendingLaunchMode: String?,
    onLaunchModeConsumed: () -> Unit,
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
    val lastClosedTab by sessionVm.lastClosedTab.collectAsState()

    // Room reactive streams wrapped in remember { ... } so recompositions never re-query SQLite
    val spaces by remember(database) {
        database.spaceDao().observeSpaces()
    }.collectAsState(initial = emptyList())
    val allBookmarks by remember(database) {
        database.bookmarkDao().observeAllBookmarks()
    }.collectAsState(initial = emptyList())
    val quickSearchBookmarks by remember(database) {
        database.bookmarkDao().observeQuickSearchPins(limit = 5)
    }.collectAsState(initial = emptyList())

    val allActiveTabs by remember(database) {
        database.tabMetadataDao().observeAllActiveTabs()
    }.collectAsState(initial = emptyList())
    val activeTabsInSpace by remember(database, activeSpaceId) {
        database.tabMetadataDao().observeActiveTabsForSpace(activeSpaceId)
    }.collectAsState(initial = emptyList())

    val immutableSpaces = remember(spaces) { spaces.toImmutableList() }
    val immutableAllBookmarks = remember(allBookmarks) { allBookmarks.toImmutableList() }
    val immutableAllActiveTabs = remember(allActiveTabs) { allActiveTabs.toImmutableList() }
    val immutableActiveTabs = remember(activeTabsInSpace) { activeTabsInSpace.toImmutableList() }
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

    // Global browser settings (default search engine, GPC, biometric private lock, full scroll-hide)
    val settingsStore = remember(context) { FocalSettingsStore(context) }
    var browserSettings by remember(settingsStore) {
        mutableStateOf(settingsStore.read())
    }
    val updateBrowserSettings: (FocalBrowserSettings) -> Unit = { updated ->
        browserSettings = updated
        settingsStore.write(updated)
    }
    LaunchedEffect(browserSettings.globalPrivacyControlEnabled) {
        sessionVm.setGlobalPrivacyControl(browserSettings.globalPrivacyControlEnabled)
    }

    val updateCheckStore = remember(context) { FocalUpdateCheckStore(context) }
    var updateBannerState by remember { mutableStateOf<UpdateBannerState>(UpdateBannerState.Idle) }
    var availableReleaseInfo by remember { mutableStateOf<FocalReleaseInfo?>(null) }
    var verifiedUpdateApk by remember { mutableStateOf<File?>(null) }
    var updateDownloadJob by remember { mutableStateOf<Job?>(null) }
    var dismissedUpdatePillTag by remember(updateCheckStore) {
        mutableStateOf(updateCheckStore.readDismissedTag())
    }

    fun FocalReleaseInfo.toAvailableState(): UpdateBannerState.Available = UpdateBannerState.Available(
        tagName = tagName,
        releaseName = releaseName,
        notes = notes,
        sizeBytes = apkAsset?.sizeBytes ?: 0L,
        isDownloading = false,
    )

    val commitVerifiedUpdateApk: (File, FocalReleaseInfo) -> Unit = { apkFile, release ->
        if (!FocalReleaseUpdater.canInstallPackages(context)) {
            updateBannerState = UpdateBannerState.PermissionRequired(release.tagName)
        } else {
            updateBannerState = UpdateBannerState.Installing(release.tagName)
            scope.launch {
                val result = FocalReleaseUpdater.commitPackageInstallerSession(context, apkFile)
                result.onFailure { err ->
                    updateBannerState = UpdateBannerState.Error(
                        err.message ?: "Unable to start in-app update installer",
                    )
                }
            }
        }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val release = availableReleaseInfo ?: return@rememberLauncherForActivityResult
        val apkFile = verifiedUpdateApk
        if (FocalReleaseUpdater.canInstallPackages(context) && apkFile != null && apkFile.isFile) {
            commitVerifiedUpdateApk(apkFile, release)
        } else if (apkFile != null && apkFile.isFile) {
            updateBannerState = UpdateBannerState.PermissionRequired(release.tagName)
        }
    }

    val requestInstallPermission: () -> Unit = {
        runCatching {
            installPermissionLauncher.launch(
                FocalReleaseUpdater.createUnknownSourcesSettingsIntent(context),
            )
        }.onFailure {
            updateBannerState = UpdateBannerState.Error("Open Android Settings to allow Focal installs")
        }
    }

    val startInAppUpdate: () -> Unit = {
        val release = availableReleaseInfo
        if (release != null) {
            val existingApk = verifiedUpdateApk
            if (existingApk != null && existingApk.isFile) {
                commitVerifiedUpdateApk(existingApk, release)
            } else {
                updateDownloadJob?.cancel()
                val totalHint = release.apkAsset?.sizeBytes ?: 0L
                updateBannerState = UpdateBannerState.Downloading(
                    tagName = release.tagName,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = totalHint,
                )
                updateDownloadJob = scope.launch {
                    try {
                        val verified = FocalReleaseUpdater.downloadAndVerifyApk(
                            context = context,
                            release = release,
                            onProgress = { downloaded, total ->
                                val safeTotal = total.coerceAtLeast(0L)
                                val fraction = if (safeTotal > 0L) {
                                    (downloaded.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                scope.launch(Dispatchers.Main.immediate) {
                                    if (updateBannerState is UpdateBannerState.Downloading) {
                                        updateBannerState = UpdateBannerState.Downloading(
                                            tagName = release.tagName,
                                            progress = fraction,
                                            downloadedBytes = downloaded,
                                            totalBytes = safeTotal,
                                        )
                                    }
                                }
                            },
                            onVerifying = {
                                scope.launch(Dispatchers.Main.immediate) {
                                    updateBannerState = UpdateBannerState.Verifying(release.tagName)
                                }
                            },
                        )
                        when (verified) {
                            is VerifiedApkResult.Ready -> {
                                verifiedUpdateApk = verified.apkFile
                                commitVerifiedUpdateApk(verified.apkFile, release)
                            }
                            is VerifiedApkResult.Failed -> {
                                verifiedUpdateApk = null
                                updateBannerState = UpdateBannerState.Error(verified.message)
                            }
                        }
                    } catch (_: CancellationException) {
                        updateBannerState = release.toAvailableState()
                    } finally {
                        updateDownloadJob = null
                    }
                }
            }
        }
    }

    val cancelInAppUpdate: () -> Unit = {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        val release = availableReleaseInfo
        updateBannerState = release?.toAvailableState() ?: UpdateBannerState.Idle
    }

    val runManualUpdateCheck: () -> Unit = {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        updateBannerState = UpdateBannerState.Checking
        scope.launch {
            when (val result = FocalReleaseUpdater.checkLatestRelease(context)) {
                is ReleaseCheckResult.UpToDate -> {
                    updateCheckStore.recordCheckTimestamp(System.currentTimeMillis())
                    availableReleaseInfo = null
                    verifiedUpdateApk = null
                    updateBannerState = UpdateBannerState.UpToDate(result.currentVersion)
                }
                is ReleaseCheckResult.UpdateAvailable -> {
                    updateCheckStore.recordCheckTimestamp(System.currentTimeMillis())
                    if (availableReleaseInfo?.tagName != result.release.tagName) {
                        verifiedUpdateApk = null
                    }
                    availableReleaseInfo = result.release
                    updateBannerState = result.release.toAvailableState()
                }
                is ReleaseCheckResult.Failed -> {
                    updateBannerState = UpdateBannerState.Error(result.message)
                }
            }
        }
    }

    // Receive Android PackageInstaller.Session callbacks cleanly inside Focal
    LaunchedEffect(activity) {
        FocalUpdateInstallStatusBus.events.collect { event ->
            when (event) {
                is FocalInstallEvent.PendingUserAction -> {
                    runCatching {
                        activity.startActivity(event.confirmIntent)
                    }.onFailure { err ->
                        updateBannerState = UpdateBannerState.Error(
                            err.message ?: "Unable to open Android update prompt",
                        )
                    }
                }
                FocalInstallEvent.Success -> {
                    verifiedUpdateApk = null
                    availableReleaseInfo = null
                    updateBannerState = UpdateBannerState.UpToDate(
                        FocalReleaseUpdater.currentAppVersionName(context),
                    )
                }
                FocalInstallEvent.Cancelled -> {
                    updateBannerState = availableReleaseInfo?.toAvailableState() ?: UpdateBannerState.Idle
                }
                is FocalInstallEvent.Failed -> {
                    updateBannerState = UpdateBannerState.Error(event.message)
                }
            }
        }
    }

    // Quiet daily auto-check: strictly deferred past cold launch & disabled inside Private Aperture ◎
    LaunchedEffect(browserSettings.autoCheckUpdatesEnabled, isPrivateAperture) {
        if (!browserSettings.autoCheckUpdatesEnabled || isPrivateAperture) return@LaunchedEffect
        delay(8_000L)
        if (!browserSettings.autoCheckUpdatesEnabled || isPrivateAperture) return@LaunchedEffect
        val nowMs = System.currentTimeMillis()
        if (!FocalReleaseVersionMath.shouldAutoCheck(
                autoCheckEnabled = browserSettings.autoCheckUpdatesEnabled,
                isPrivateAperture = isPrivateAperture,
                lastCheckedEpochMs = updateCheckStore.readLastCheckEpochMs(),
                nowEpochMs = nowMs,
            )
        ) {
            return@LaunchedEffect
        }
        val activeState = updateBannerState
        if (activeState is UpdateBannerState.Downloading ||
            activeState is UpdateBannerState.Verifying ||
            activeState is UpdateBannerState.Installing
        ) {
            return@LaunchedEffect
        }
        when (val result = FocalReleaseUpdater.checkLatestRelease(context)) {
            is ReleaseCheckResult.UpToDate -> {
                updateCheckStore.recordCheckTimestamp(System.currentTimeMillis())
                FocalReleaseUpdater.cleanupCachedUpdates(context)
            }
            is ReleaseCheckResult.UpdateAvailable -> {
                updateCheckStore.recordCheckTimestamp(System.currentTimeMillis())
                availableReleaseInfo = result.release
                if (updateBannerState == UpdateBannerState.Idle || updateBannerState is UpdateBannerState.UpToDate) {
                    updateBannerState = result.release.toAvailableState()
                }
            }
            is ReleaseCheckResult.Failed -> {
                // Silent failure for background auto-check; never interrupts browsing
            }
        }
    }

    val requestPrivateAperture: (Boolean, () -> Unit) -> Unit = { enablePrivate, onApproved ->
        if (!enablePrivate || isPrivateAperture || !browserSettings.biometricPrivateLockEnabled) {
            onApproved()
        } else {
            FocalBiometricLock.authenticatePrivateAperture(
                activity = activity,
                onAuthenticated = onApproved,
                onCancelledOrFailed = {},
            )
        }
    }

    // Observe per-origin overrides only when a real web origin is loaded (avoids cold-start "" SQLite query)
    val currentOrigin = activeSessionState?.domain.orEmpty()
    val savedOverrideEntity by remember(database, currentOrigin) {
        if (currentOrigin.isBlank()) {
            flowOf<OriginOverrideEntity?>(null)
        } else {
            database.originOverrideDao().observeOverrideForOrigin(currentOrigin)
        }
    }.collectAsState(initial = null)
    var privateOverridesByOrigin by remember(isPrivateAperture) {
        mutableStateOf<Map<String, OriginOverrides>>(emptyMap())
    }

    val effectiveOverrides = remember(
        currentOrigin,
        savedOverrideEntity,
        isPrivateAperture,
        privateOverridesByOrigin,
    ) {
        if (isPrivateAperture) {
            privateOverridesByOrigin[currentOrigin]
                ?: savedOverrideEntity?.toEngineOverrides()
                ?: OriginOverrides(origin = currentOrigin)
        } else {
            savedOverrideEntity?.toEngineOverrides() ?: OriginOverrides(origin = currentOrigin)
        }
    }

    val readerDefaultsStore = remember(context) { ReaderDefaultsStore(context) }
    var globalReaderDefaults by remember(readerDefaultsStore) {
        mutableStateOf(app.focal.feature.reader.ReaderPreferences())
    }
    var readerDefaultsLoaded by remember(readerDefaultsStore) { mutableStateOf(false) }
    LaunchedEffect(overlaySheet is ActiveOverlaySheet.Reader, readerDefaultsStore) {
        if (overlaySheet is ActiveOverlaySheet.Reader && !readerDefaultsLoaded) {
            readerDefaultsLoaded = true
            globalReaderDefaults = withContext(Dispatchers.IO) { readerDefaultsStore.read() }
        }
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
        if (isPrivateAperture) {
            if (updated.origin.isNotBlank()) {
                privateOverridesByOrigin = privateOverridesByOrigin + (updated.origin to updated)
            }
        } else if (updated.origin.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                database.originOverrideDao().upsertOverride(
                    updated.toEntity(updatedAtEpochMs = System.currentTimeMillis()),
                )
            }
        }
    }

    // Find in Page active state (auto-dismissed when switching tabs)
    var isFindInPageOpen by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTabId) {
        isFindInPageOpen = false
    }

    // Native File Upload Picker plumbing for WebChromeClient.onShowFileChooser
    var pendingFileChooserCallback by remember { mutableStateOf<((List<Uri>?) -> Unit)?>(null) }
    val completePendingFileChooser: (List<Uri>?) -> Unit = { uris ->
        val callback = pendingFileChooserCallback
        pendingFileChooserCallback = null
        callback?.invoke(uris)
    }
    val openSingleDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        completePendingFileChooser(uri?.let(::listOf))
    }
    val openMultipleDocumentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        completePendingFileChooser(uris.takeIf { it.isNotEmpty() })
    }
    DisposableEffect(Unit) {
        onDispose {
            pendingFileChooserCallback?.invoke(null)
            pendingFileChooserCallback = null
        }
    }

    // Native Site Permission Prompt Sheet (Camera, Microphone, Location, Protected Media)
    var pendingSitePermission by remember {
        mutableStateOf<EngineSessionEvent.SitePermissionRequested?>(null)
    }
    val resolvePendingSitePermission: (Boolean) -> Unit = { allow ->
        val pending = pendingSitePermission
        pendingSitePermission = null
        pending?.onDecision?.invoke(allow)
    }
    DisposableEffect(activeSession) {
        onDispose {
            val pending = pendingSitePermission
            pendingSitePermission = null
            pending?.onDecision?.invoke(false)
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
    val scrollBarController = remember { ScrollVelocityBarController() }
    var bottomBarSnapJob by remember { mutableStateOf<Job?>(null) }
    var bottomBarSettleTimerJob by remember { mutableStateOf<Job?>(null) }
    var isWebViewTouchScrolling by remember { mutableStateOf(false) }

    val revealBottomBarSmoothly: () -> Unit = {
        bottomBarSettleTimerJob?.cancel()
        bottomBarSnapJob?.cancel()
        scrollBarController.reset()
        bottomBarHiddenTarget = false
        if (bottomBarScrollHideState.floatValue > 0.001f) {
            bottomBarSnapJob = scope.launch {
                Animatable(bottomBarScrollHideState.floatValue).animateTo(
                    targetValue = 0f,
                    animationSpec = FocalMotion.smartPillSpring(),
                ) {
                    bottomBarScrollHideState.floatValue = value
                }
                scrollBarController.syncFraction(0f, hidden = false)
            }
        } else {
            bottomBarScrollHideState.floatValue = 0f
        }
    }

    val scheduleBottomBarSettle: () -> Unit = {
        bottomBarSettleTimerJob?.cancel()
        bottomBarSettleTimerJob = scope.launch {
            delay(90L)
            if (isWebViewTouchScrolling) return@launch
            val plan = scrollBarController.computeSettlePlan(density = density.density) ?: return@launch
            bottomBarSnapJob?.cancel()
            bottomBarSnapJob = scope.launch {
                Animatable(bottomBarScrollHideState.floatValue).animateTo(
                    targetValue = plan.targetFraction,
                    animationSpec = spring(
                        dampingRatio = 0.92f,
                        stiffness = plan.springStiffness,
                    ),
                    initialVelocity = plan.initialVelocityFractionPerSec,
                ) {
                    bottomBarScrollHideState.floatValue = value
                }
                scrollBarController.syncFraction(plan.targetFraction, hidden = plan.targetHidden)
                bottomBarHiddenTarget = plan.targetHidden
            }
        }
    }

    LaunchedEffect(
        activeSession?.sessionId,
        isFindInPageOpen,
        overlaySheet != ActiveOverlaySheet.None,
        browserSettings.fullHideBottomBarOnScroll,
    ) {
        isWebViewTouchScrolling = false
        revealBottomBarSmoothly()
    }

    val haptics = LocalHapticFeedback.current
    val pullToRefreshProgressState = remember { mutableFloatStateOf(0f) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    var pullToRefreshAnimJob by remember { mutableStateOf<Job?>(null) }
    var pullToRefreshHapticSent by remember { mutableStateOf(false) }

    val resetPullToRefresh: () -> Unit = {
        pullToRefreshAnimJob?.cancel()
        pullToRefreshHapticSent = false
        isPullRefreshing = false
        pullToRefreshProgressState.floatValue = 0f
    }

    LaunchedEffect(activeSession?.sessionId) {
        resetPullToRefresh()
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

    // Handle Home-Screen Quick-Launch Widget & App Shortcut deep launch modes
    val latestRequestPrivateAperture by rememberUpdatedState(requestPrivateAperture)
    LaunchedEffect(pendingLaunchMode) {
        val mode = pendingLaunchMode ?: return@LaunchedEffect
        onLaunchModeConsumed()
        when (mode) {
            FocalQuickLaunchWidget.LAUNCH_MODE_SEARCH -> {
                sessionVm.openSearchSheet(forNewTab = false, privateMode = false)
            }
            FocalQuickLaunchWidget.LAUNCH_MODE_PRIVATE -> {
                latestRequestPrivateAperture(true) {
                    sessionVm.openSearchSheet(forNewTab = true, privateMode = true)
                }
            }
        }
    }

    val hasLoadedWebUrl = activeSession != null &&
        !activeSessionState?.url.isNullOrBlank() &&
        activeSessionState?.url != "about:blank"

    LaunchedEffect(
        activeSession,
        hasLoadedWebUrl,
        overlaySheet == ActiveOverlaySheet.None,
        isInPictureInPictureMode,
        isFindInPageOpen,
        activeSessionState?.isElementZapperActive,
    ) {
        val enabled = hasLoadedWebUrl &&
            overlaySheet == ActiveOverlaySheet.None &&
            !isInPictureInPictureMode &&
            !isFindInPageOpen &&
            activeSessionState?.isElementZapperActive != true
        activeSession?.setPullToRefreshEnabled(enabled)
        if (!enabled) {
            resetPullToRefresh()
        }
    }

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
    val latestEffectiveOverrides by rememberUpdatedState(effectiveOverrides)
    val latestPersistSiteOverrides by rememberUpdatedState(persistSiteOverrides)

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

    var downloadStartedToastFileName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(downloadStartedToastFileName) {
        if (downloadStartedToastFileName != null) {
            delay(3_800L)
            downloadStartedToastFileName = null
        }
    }

    // Sync active EngineSession UI events (Link Peek, Scroll Luminance, Navigation, PageLoaded, Downloads, Uploads, Permissions, Zapper)
    LaunchedEffect(activeSession) {
        val session = activeSession ?: return@LaunchedEffect

        session.events.collect { event ->
            when (event) {
                is EngineSessionEvent.AnchorLongPressed -> {
                    sessionVm.setOverlaySheet(
                        ActiveOverlaySheet.LinkPeek(
                            rawUrl = event.rawUrl,
                            imageUrl = event.imageUrl,
                        ),
                    )
                }
                is EngineSessionEvent.ScrollTouchStateChanged -> {
                    isWebViewTouchScrolling = event.isTouching
                    if (event.isTouching) {
                        bottomBarSettleTimerJob?.cancel()
                        bottomBarSnapJob?.cancel()
                        scrollBarController.syncFraction(
                            fraction = bottomBarScrollHideState.floatValue,
                            hidden = bottomBarHiddenTarget,
                        )
                    } else {
                        scheduleBottomBarSettle()
                    }
                }
                is EngineSessionEvent.ScrollDeltaChanged -> {
                    bottomBarSnapJob?.cancel()
                    val eventTimeMs = if (event.timestampMs > 0L) event.timestampMs else SystemClock.uptimeMillis()
                    val step = scrollBarController.onScrollDelta(
                        scrollY = event.scrollY,
                        deltaY = event.deltaY,
                        timestampMs = eventTimeMs,
                        density = density.density,
                    )
                    bottomBarScrollHideState.floatValue = step.fraction
                    if (bottomBarHiddenTarget != step.settledHidden) {
                        bottomBarHiddenTarget = step.settledHidden
                    }
                    if (!event.isTouchActive && !isWebViewTouchScrolling) {
                        scheduleBottomBarSettle()
                    } else {
                        bottomBarSettleTimerJob?.cancel()
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
                is EngineSessionEvent.PullToRefreshGesture -> {
                    if (event.isActive) {
                        pullToRefreshAnimJob?.cancel()
                        isPullRefreshing = false
                        val progress = PullToRefreshPhysics.progressForRawPullDp(event.rawPullDp)
                        pullToRefreshProgressState.floatValue = progress
                        if (progress >= 1f && !pullToRefreshHapticSent) {
                            pullToRefreshHapticSent = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (progress < 0.88f) {
                            pullToRefreshHapticSent = false
                        }
                    } else {
                        pullToRefreshHapticSent = false
                        pullToRefreshAnimJob?.cancel()
                        if (event.released && PullToRefreshPhysics.shouldTriggerRefresh(event.rawPullDp)) {
                            isPullRefreshing = true
                            session.reload()
                            pullToRefreshAnimJob = scope.launch {
                                Animatable(pullToRefreshProgressState.floatValue).animateTo(
                                    targetValue = PullToRefreshPhysics.REFRESH_HOLD_PROGRESS,
                                    animationSpec = FocalMotion.smartPillSpring(),
                                ) {
                                    pullToRefreshProgressState.floatValue = value
                                }
                                val startMs = SystemClock.uptimeMillis()
                                delay(420L)
                                while (
                                    session.state.value.isLoading &&
                                    (SystemClock.uptimeMillis() - startMs) < 4_500L
                                ) {
                                    delay(90L)
                                }
                                Animatable(pullToRefreshProgressState.floatValue).animateTo(
                                    targetValue = 0f,
                                    animationSpec = FocalMotion.smartPillSpring(),
                                ) {
                                    pullToRefreshProgressState.floatValue = value
                                }
                                isPullRefreshing = false
                            }
                        } else {
                            isPullRefreshing = false
                            pullToRefreshAnimJob = scope.launch {
                                Animatable(pullToRefreshProgressState.floatValue).animateTo(
                                    targetValue = 0f,
                                    animationSpec = FocalMotion.smartPillSpring(),
                                ) {
                                    pullToRefreshProgressState.floatValue = value
                                }
                            }
                        }
                    }
                }
                is EngineSessionEvent.NavigationCommitted -> {
                    isWebViewTouchScrolling = false
                    revealBottomBarSmoothly()
                    // Delay past WebView's transient white blank-canvas flash before first read
                    scheduleSettledGlassSamples(longArrayOf(180L, 550L))
                }
                is EngineSessionEvent.PageLoaded -> {
                    sessionVm.recordHistoryVisit(
                        url = event.url,
                        title = session.state.value.title,
                        isPrivate = session.isPrivate,
                    )
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
                    val record = enqueueSafeBrowserDownload(
                        context = context,
                        event = event,
                        isPrivateSession = session.isPrivate,
                    )
                    if (record != null) {
                        downloadStartedToastFileName = record.fileName
                        if (!session.isPrivate) {
                            scope.launch(Dispatchers.IO) {
                                database.downloadRecordDao().upsert(record)
                            }
                        }
                    }
                }
                is EngineSessionEvent.FileChooserRequested -> {
                    pendingFileChooserCallback?.invoke(null)
                    pendingFileChooserCallback = event.onResult
                    val mimeArray = event.mimeTypes
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .ifEmpty { listOf("*/*") }
                        .toTypedArray()
                    runCatching {
                        if (event.allowMultiple) {
                            openMultipleDocumentsLauncher.launch(mimeArray)
                        } else {
                            openSingleDocumentLauncher.launch(mimeArray)
                        }
                    }.onFailure {
                        completePendingFileChooser(null)
                    }
                }
                is EngineSessionEvent.SitePermissionRequested -> {
                    pendingSitePermission?.onDecision?.invoke(false)
                    pendingSitePermission = event
                }
                is EngineSessionEvent.ElementZapped -> {
                    val selector = event.cssSelector.trim()
                    if (selector.isNotEmpty()) {
                        val base = latestEffectiveOverrides
                        val targetOrigin = event.origin.ifBlank { base.origin }
                        if (targetOrigin.isNotBlank()) {
                            val updatedSelectors = (base.hiddenCssSelectors + selector).distinct()
                            latestPersistSiteOverrides(
                                base.copy(
                                    origin = targetOrigin,
                                    hiddenCssSelectors = updatedSelectors,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // Lease-based exclusivity across tab scrub, Pinch-to-Summarize, sheet drags, and Predictive Back
    val gestureLeaseController = remember { GestureLeaseController() }

    // Android 14–16 Predictive Back Handler: shrinks & translates active sheet or web page 1:1 with finger (P1-5)
    val canInterceptBack = pendingPairingRequest != null ||
        pendingSitePermission != null ||
        overlaySheet != ActiveOverlaySheet.None ||
        isFindInPageOpen ||
        (activeSessionState?.isElementZapperActive == true) ||
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
                pendingSitePermission != null -> resolvePendingSitePermission(false)
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
                isFindInPageOpen -> {
                    isFindInPageOpen = false
                    activeSession?.clearFindInPage()
                }
                activeSessionState?.isElementZapperActive == true -> {
                    activeSession.setElementZapperActive(false)
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

    val historyBackPreview = remember(
        activeSession?.sessionId,
        activeSessionState?.url,
        activeSessionState?.canGoBack,
    ) {
        if (activeSessionState?.canGoBack == true) {
            activeSession?.getHistoryBackPreview()
        } else {
            null
        }
    }

    val totalBlockedTrackers = remember(allActiveTabs, privateTabs, activeSessionState?.blockedTrackersInSession) {
        val persistedSum = allActiveTabs.sumOf { it.blockedTrackersCount.toLong() } +
            privateTabs.sumOf { it.blockedTrackersCount.toLong() }
        val activeSessionCount = activeSessionState?.blockedTrackersInSession ?: 0L
        maxOf(persistedSum, activeSessionCount)
    }

    val saveImageToDownloads: (String) -> Unit = { imageUrl ->
        val record = enqueueSafeBrowserDownload(
            context = context,
            event = EngineSessionEvent.DownloadRequested(
                url = imageUrl,
                mimeType = inferImageMimeType(imageUrl),
                contentLength = -1L,
            ),
            isPrivateSession = isPrivateAperture,
        )
        if (!isPrivateAperture && record != null) {
            scope.launch(Dispatchers.IO) {
                database.downloadRecordDao().upsert(record)
            }
        }
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
                    requestPrivateAperture(true) {
                        sessionVm.openSearchSheet(forNewTab = true, privateMode = true)
                    }
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
                fullHideBottomBarOnScroll = browserSettings.fullHideBottomBarOnScroll,
                isBottomBarHidden = bottomBarHiddenTarget,
                adaptiveSurfaceColor = glassState.sampledSurfaceArgb?.let(::Color),
                isFindInPageOpen = isFindInPageOpen,
                onFindQueryChange = { query ->
                    activeSession?.findInPage(query)
                },
                onFindNext = { forward ->
                    activeSession?.findNext(forward)
                },
                onCloseFindInPage = {
                    isFindInPageOpen = false
                    activeSession?.clearFindInPage()
                },
                onExitElementZapper = {
                    activeSession?.setElementZapperActive(false)
                },
                onBottomChromeInsetChanged = { insetPx ->
                    activeSession?.setBottomChromeInsetPx(insetPx)
                },
                historyBackPreview = historyBackPreview,
                predictiveBackSwipeEdgeState = predictiveBackSwipeEdgeState,
                pullToRefreshProgressState = pullToRefreshProgressState,
                isPullRefreshing = isPullRefreshing,
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
                                    is ActiveOverlaySheet.LinkPeek -> "link:${sheet.rawUrl}:${sheet.imageUrl.orEmpty()}"
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
                                val searchHistory by remember(database, isPrivateAperture) {
                                    if (isPrivateAperture) {
                                        flowOf(emptyList())
                                    } else {
                                        database.historyDao().observeRecentHistory(limit = 200)
                                    }
                                }.collectAsState(initial = emptyList())
                                val immutableSearchHistory = remember(searchHistory) {
                                    searchHistory.toImmutableList()
                                }
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
                                    onTogglePrivateAperture = { priv ->
                                        requestPrivateAperture(priv) {
                                            sessionVm.setPrivateAperture(priv)
                                        }
                                    },
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
                                    openTabs = if (isPrivateAperture) privateTabs else immutableAllActiveTabs,
                                    bookmarks = immutableAllBookmarks,
                                    historyEntries = immutableSearchHistory,
                                    spaces = immutableSpaces,
                                    browserSettings = browserSettings,
                                    onSwitchToTab = { tabId ->
                                        sessionVm.selectTab(tabId, allActiveTabs)
                                    },
                                    onSelectSearchEngine = { preset ->
                                        updateBrowserSettings(browserSettings.copy(searchEngine = preset))
                                    },
                                    onOpenGlobalSettings = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.GlobalSettings)
                                    },
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
                                val archivedTabs by remember(database) {
                                    val retentionStart = System.currentTimeMillis() - TabMetadataEntity.ARCHIVE_RETENTION_MS
                                    database.tabMetadataDao().observeArchivedTabs(retentionStart)
                                }.collectAsState(initial = emptyList())
                                val immutableArchivedTabs = remember(archivedTabs) {
                                    archivedTabs.toImmutableList()
                                }
                                val undoClosedTitle = lastClosedTab?.let { rec ->
                                    rec.meta.title.ifBlank { rec.meta.domain.ifBlank { rec.meta.url } }
                                }
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
                                        requestPrivateAperture(priv) {
                                            sessionVm.setPrivateAperture(priv)
                                        }
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
                                        requestPrivateAperture(priv) {
                                            sessionVm.openSearchSheet(forNewTab = true, privateMode = priv)
                                        }
                                    },
                                    onDone = {
                                        sessionVm.dismissTabSwitcher(immutableActiveTabs)
                                    },
                                    lastClosedTabTitle = undoClosedTitle,
                                    onUndoCloseTab = {
                                        sessionVm.undoCloseTab()
                                    },
                                    onDismissUndoCloseTab = {
                                        sessionVm.dismissUndoCloseTab()
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
                                    onFindInPage = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                        isFindInPageOpen = true
                                    },
                                    onToggleElementZapper = {
                                        val nextActive = activeSessionState?.isElementZapperActive != true
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                        activeSession?.setElementZapperActive(nextActive)
                                    },
                                    onOpenHistory = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.History)
                                    },
                                    onOpenDownloads = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.Downloads)
                                    },
                                    onOpenGlobalSettings = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.GlobalSettings)
                                    },
                                    onResetHiddenElements = {
                                        persistSiteOverrides(
                                            effectiveOverrides.copy(hiddenCssSelectors = emptyList()),
                                        )
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

                            ActiveOverlaySheet.History -> {
                                val recentHistory by remember(database) {
                                    database.historyDao().observeRecentHistory(limit = 200)
                                }.collectAsState(initial = emptyList())
                                val immutableHistory = remember(recentHistory) {
                                    recentHistory.toImmutableList()
                                }
                                HistorySheet(
                                    entries = immutableHistory,
                                    onOpenUrl = { url ->
                                        sessionVm.openUrlInTab(
                                            url = url,
                                            privateMode = isPrivateAperture,
                                            openInBackground = false,
                                            forceNewTab = false,
                                        )
                                    },
                                    onDeleteEntry = { entryId ->
                                        scope.launch(Dispatchers.IO) {
                                            database.historyDao().deleteById(entryId)
                                        }
                                    },
                                    onClearAllHistory = {
                                        scope.launch(Dispatchers.IO) {
                                            database.historyDao().clearAll()
                                        }
                                    },
                                    onDismiss = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                )
                            }

                            ActiveOverlaySheet.Downloads -> {
                                val recentDownloads by remember(database) {
                                    database.downloadRecordDao().observeRecentDownloads(limit = 100)
                                }.collectAsState(initial = emptyList())
                                val immutableDownloads = remember(recentDownloads) {
                                    recentDownloads.toImmutableList()
                                }
                                DownloadsSheet(
                                    downloads = immutableDownloads,
                                    onOpenDownload = { record ->
                                        val openedLocally = openDownloadedFileOrFallback(context, record)
                                        if (!openedLocally && record.url.isNotBlank()) {
                                            sessionVm.openUrlInTab(
                                                url = record.url,
                                                privateMode = isPrivateAperture,
                                                openInBackground = false,
                                                forceNewTab = true,
                                            )
                                        }
                                    },
                                    onShareDownload = { record ->
                                        shareDownloadedFileOrUrl(context, record)
                                    },
                                    onDeleteDownload = { downloadId ->
                                        scope.launch(Dispatchers.IO) {
                                            database.downloadRecordDao().deleteById(downloadId)
                                        }
                                    },
                                    onClearAll = {
                                        scope.launch(Dispatchers.IO) {
                                            database.downloadRecordDao().clearAll()
                                        }
                                    },
                                    onDismiss = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                )
                            }

                            ActiveOverlaySheet.GlobalSettings -> {
                                val biometricAvailable = remember(context) {
                                    FocalBiometricLock.isDeviceAuthenticationAvailable(context)
                                }
                                val appVersionName = remember(context) {
                                    FocalReleaseUpdater.currentAppVersionName(context)
                                }
                                GlobalSettingsSheet(
                                    settings = browserSettings,
                                    appTheme = appChromeTheme,
                                    currentVersionName = appVersionName,
                                    totalBlockedTrackers = totalBlockedTrackers,
                                    biometricAvailable = biometricAvailable,
                                    updateState = updateBannerState,
                                    onUpdateSettings = updateBrowserSettings,
                                    onUpdateAppTheme = { nextTheme ->
                                        appChromeTheme = nextTheme
                                        appearanceStore.writeTheme(nextTheme)
                                    },
                                    onClearBrowsingData = {
                                        sessionVm.clearBrowsingData(context)
                                    },
                                    onCheckForUpdates = runManualUpdateCheck,
                                    onInstallUpdate = startInAppUpdate,
                                    onCancelUpdate = cancelInAppUpdate,
                                    onGrantInstallPermission = requestInstallPermission,
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
                                    imageUrl = currentOverlay.imageUrl,
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
                                    onOpenInPrivateTab = { cleanUrl ->
                                        requestPrivateAperture(true) {
                                            sessionVm.openUrlInTab(
                                                url = cleanUrl,
                                                privateMode = true,
                                                openInBackground = false,
                                                forceNewTab = true,
                                            )
                                        }
                                    },
                                    onCopyLink = { targetUrl ->
                                        val cm = context.getSystemService(ClipboardManager::class.java)
                                        cm?.setPrimaryClip(ClipData.newPlainText("Clean URL", targetUrl))
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onShareLink = { targetUrl ->
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, targetUrl)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, null))
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onSaveImage = { imgUrl ->
                                        saveImageToDownloads(imgUrl)
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                    onDismiss = {
                                        sessionVm.setOverlaySheet(ActiveOverlaySheet.None)
                                    },
                                )
                            }
                        }
                        }

                        // Non-intrusive Smoked Glass Update Pill when a newer GitHub Release is ready
                        val shouldShowUpdatePill = !isPrivateAperture &&
                            !isInPictureInPictureMode &&
                            overlaySheet == ActiveOverlaySheet.None &&
                            when (val state = updateBannerState) {
                                is UpdateBannerState.Available -> state.tagName != dismissedUpdatePillTag
                                is UpdateBannerState.Downloading,
                                is UpdateBannerState.Verifying,
                                is UpdateBannerState.PermissionRequired,
                                is UpdateBannerState.Installing,
                                -> true
                                else -> false
                            }
                        if (shouldShowUpdatePill) {
                            FocalUpdateAvailablePill(
                                updateState = updateBannerState,
                                onStartUpdate = startInAppUpdate,
                                onCancelUpdate = cancelInAppUpdate,
                                onGrantPermission = requestInstallPermission,
                                onDismissPill = { tag ->
                                    dismissedUpdatePillTag = tag
                                    updateCheckStore.dismissTag(tag)
                                },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 10.dp, start = 16.dp, end = 16.dp),
                            )
                        } else if (
                            !isInPictureInPictureMode &&
                            overlaySheet == ActiveOverlaySheet.None &&
                            downloadStartedToastFileName != null
                        ) {
                            val activeDownloadName = downloadStartedToastFileName.orEmpty()
                            FocalDownloadStartedPill(
                                fileName = activeDownloadName,
                                showDownloadsButton = !isPrivateAperture,
                                onOpenDownloads = {
                                    downloadStartedToastFileName = null
                                    sessionVm.setOverlaySheet(ActiveOverlaySheet.Downloads)
                                },
                                onDismiss = { downloadStartedToastFileName = null },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 10.dp, start = 16.dp, end = 16.dp),
                            )
                        }

                        // Native Site Permission Confirmation Sheet (Camera, Mic, Location, Protected Media)
                        val permissionReq = pendingSitePermission
                        if (permissionReq != null) {
                            SitePermissionPromptSheet(
                                origin = permissionReq.origin,
                                resources = permissionReq.resources,
                                onAllow = { resolvePendingSitePermission(true) },
                                onDeny = { resolvePendingSitePermission(false) },
                            )
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
): DownloadRecordEntity? {
    val rawUrl = event.url.trim()
    if (!rawUrl.startsWith("https://", ignoreCase = true) && !rawUrl.startsWith("http://", ignoreCase = true)) {
        return null
    }
    return try {
        val uri = Uri.parse(rawUrl)
        val dispositionFileName = FocalExternalIntentAndDownloadMath.extractContentDispositionFileName(
            event.contentDisposition,
        )
        val initialMime = FocalExternalIntentAndDownloadMath.resolveDownloadMimeType(
            url = rawUrl,
            fileNameHint = dispositionFileName,
            serverMimeType = event.mimeType,
        )
        val guessedName = URLUtil.guessFileName(
            rawUrl,
            event.contentDisposition?.takeIf { it.isNotBlank() },
            initialMime?.takeIf { it.isNotBlank() },
        )
        val fileName = FocalExternalIntentAndDownloadMath.preserveKnownFileExtension(
            url = rawUrl,
            guessedFileName = guessedName,
            contentDispositionFileName = dispositionFileName,
        )
        val effectiveMime = FocalExternalIntentAndDownloadMath.resolveDownloadMimeType(
            url = rawUrl,
            fileNameHint = fileName,
            serverMimeType = event.mimeType,
        )
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        if (!effectiveMime.isNullOrBlank()) {
            request.setMimeType(effectiveMime)
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
        val downloadId = context.getSystemService(DownloadManager::class.java)?.enqueue(request) ?: -1L
        DownloadRecordEntity(
            id = "dl-${UUID.randomUUID()}",
            url = rawUrl,
            fileName = fileName,
            mimeType = effectiveMime,
            originDomain = uri.host?.removePrefix("www.").orEmpty(),
            downloadManagerId = downloadId,
            createdAtEpochMs = System.currentTimeMillis(),
        )
    } catch (_: Throwable) {
        // Ignore malformed download URIs or missing DownloadManager on stripped ROMs
        null
    }
}

private fun inferImageMimeType(url: String): String? {
    val path = runCatching { Uri.parse(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
    return when {
        path.endsWith(".png") -> "image/png"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".avif") -> "image/avif"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        else -> null
    }
}

private fun openDownloadedFileOrFallback(
    context: Context,
    record: DownloadRecordEntity,
): Boolean {
    val dm = context.getSystemService(DownloadManager::class.java)
    if (dm != null && record.downloadManagerId >= 0L) {
        val localUri = runCatching { dm.getUriForDownloadedFile(record.downloadManagerId) }.getOrNull()
        if (localUri != null) {
            val dmMime = runCatching { dm.getMimeTypeForDownloadedFile(record.downloadManagerId) }.getOrNull()
            val mime = FocalExternalIntentAndDownloadMath.resolveDownloadMimeType(
                url = record.url,
                fileNameHint = record.fileName,
                serverMimeType = record.mimeType ?: dmMime,
            ) ?: "*/*"
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(localUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (runCatching { context.startActivity(viewIntent) }.isSuccess) {
                return true
            }
        }
    }
    return runCatching {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
    }.isSuccess
}

private fun shareDownloadedFileOrUrl(
    context: Context,
    record: DownloadRecordEntity,
) {
    val dm = context.getSystemService(DownloadManager::class.java)
    val localUri = if (dm != null && record.downloadManagerId >= 0L) {
        runCatching { dm.getUriForDownloadedFile(record.downloadManagerId) }.getOrNull()
    } else {
        null
    }
    val shareIntent = if (localUri != null) {
        Intent(Intent.ACTION_SEND).apply {
            type = record.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, localUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, record.url.ifBlank { record.fileName })
        }
    }
    runCatching {
        context.startActivity(Intent.createChooser(shareIntent, null))
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
    hiddenCssSelectors = OriginHiddenSelectorsCodec.decode(hiddenCssSelectorsJson),
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
    hiddenCssSelectorsJson = OriginHiddenSelectorsCodec.encode(hiddenCssSelectors),
)

@Composable
internal fun FocalUpdateAvailablePill(
    updateState: UpdateBannerState,
    onStartUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onGrantPermission: () -> Unit,
    onDismissPill: (tagName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (updateState !is UpdateBannerState.Available &&
        updateState !is UpdateBannerState.Downloading &&
        updateState !is UpdateBannerState.Verifying &&
        updateState !is UpdateBannerState.PermissionRequired &&
        updateState !is UpdateBannerState.Installing
    ) {
        return
    }
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val pillShape = RoundedCornerShape(24.dp)

    val pillTint = rememberUpdatedState(colors.effectiveSheetGlass)

    SmokedGlassBox(
        shape = pillShape,
        glassTintState = pillTint,
        borderSpecular = colors.borderSpecular,
        modifier = modifier
            .consumeClicks()
            .testTag("floating_update_pill"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = FocalGlyphs.Aperture,
                    contentDescription = null,
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(15.dp),
                )

                when (updateState) {
                    is UpdateBannerState.Available -> {
                        Text(
                            text = "Focal ${updateState.tagName} ready",
                            style = typography.controlPillLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillActive)
                                .clickable(onClick = onStartUpdate)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Update",
                                style = typography.controlPillLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.inkOnPillActive,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onDismissPill(updateState.tagName) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = FocalGlyphs.Close,
                                contentDescription = "Dismiss update notice",
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    is UpdateBannerState.Downloading -> {
                        val pct = (updateState.progress.coerceIn(0f, 1f) * 100f).toInt()
                        Text(
                            text = "Updating ${updateState.tagName} · $pct%",
                            style = typography.controlPillLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.inkPrimary,
                            maxLines = 1,
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onCancelUpdate),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = FocalGlyphs.Close,
                                contentDescription = "Cancel update",
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    is UpdateBannerState.Verifying -> {
                        Text(
                            text = "Verifying ${updateState.tagName}…",
                            style = typography.controlPillLabel.copy(fontWeight = FontWeight.Medium),
                            color = colors.inkPrimary,
                            maxLines = 1,
                        )
                    }

                    is UpdateBannerState.PermissionRequired -> {
                        Text(
                            text = "Allow Focal updates",
                            style = typography.controlPillLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.inkPrimary,
                            maxLines = 1,
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillActive)
                                .clickable(onClick = onGrantPermission)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Allow",
                                style = typography.controlPillLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.inkOnPillActive,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onDismissPill(updateState.tagName) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = FocalGlyphs.Close,
                                contentDescription = "Dismiss update notice",
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    is UpdateBannerState.Installing -> {
                        Text(
                            text = "Installing ${updateState.tagName}…",
                            style = typography.controlPillLabel.copy(fontWeight = FontWeight.Medium),
                            color = colors.inkPrimary,
                            maxLines = 1,
                        )
                    }

                    else -> Unit
                }
            }

            if (updateState is UpdateBannerState.Downloading) {
                Box(
                    modifier = Modifier
                        .width(190.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(colors.surfacePillSubtle),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(updateState.progress.coerceIn(0.03f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(colors.inkPrimary),
                    )
                }
            }
        }
    }
}

/**
 * Pure intent-URL extraction and download MIME/extension resolution helpers so external
 * `ACTION_VIEW` (including GitHub Release asset downloads with explicit MIME types) and
 * `ACTION_SEND` shared links open cleanly and keep their real `.apk`/`.zip`/`.pdf` metadata.
 */
internal object FocalExternalIntentAndDownloadMath {
    private val HTTP_URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val DISPOSITION_EXT_FILENAME_REGEX = Regex(
        """filename\*\s*=\s*(?:[^']*''?)([^;\r\n]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val DISPOSITION_FILENAME_REGEX = Regex(
        """filename\s*=\s*(?:"([^"\r\n]+)"|([^;\r\n]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val GENERIC_MIMES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/unknown",
        "*/*",
    )

    fun extractLaunchUrl(
        action: String?,
        dataString: String?,
        sharedText: String?,
    ): String? {
        val direct = dataString?.trim().orEmpty()
        if (direct.startsWith("https://", ignoreCase = true) ||
            direct.startsWith("http://", ignoreCase = true) ||
            direct.startsWith("focal-pair://", ignoreCase = true)
        ) {
            return direct
        }
        if (action == Intent.ACTION_SEND && !sharedText.isNullOrBlank()) {
            val match = HTTP_URL_REGEX.find(sharedText)?.value ?: return null
            return match.trimEnd('.', ',', ';', ':', ')', ']', '}')
        }
        return null
    }

    fun extractContentDispositionFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val extMatch = DISPOSITION_EXT_FILENAME_REGEX.find(contentDisposition)?.groupValues?.getOrNull(1)
        val rawName = if (!extMatch.isNullOrBlank()) {
            runCatching { Uri.decode(extMatch.trim().trim('"', '\'')) }.getOrNull() ?: extMatch.trim()
        } else {
            val stdMatch = DISPOSITION_FILENAME_REGEX.find(contentDisposition)
            (stdMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: stdMatch?.groupValues?.getOrNull(2))
                ?.trim()
                ?.trim('"', '\'')
        }
        return rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith(".") }
    }

    fun resolveDownloadMimeType(
        url: String,
        fileNameHint: String?,
        serverMimeType: String?,
    ): String? {
        val cleanedServer = serverMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
        if (cleanedServer != null && cleanedServer !in GENERIC_MIMES) {
            return cleanedServer
        }
        val inferred = inferMimeFromNameOrUrl(fileNameHint) ?: inferMimeFromNameOrUrl(url)
        return inferred ?: cleanedServer
    }

    fun preserveKnownFileExtension(
        url: String,
        guessedFileName: String,
        contentDispositionFileName: String? = null,
    ): String {
        if (!guessedFileName.endsWith(".bin", ignoreCase = true)) return guessedFileName
        val withoutBin = guessedFileName.dropLast(4)
        if (KNOWN_EXTENSIONS.any { withoutBin.endsWith(it, ignoreCase = true) }) {
            return withoutBin
        }
        val dispositionExt = contentDispositionFileName?.let { name ->
            KNOWN_EXTENSIONS.firstOrNull { name.endsWith(it, ignoreCase = true) }
        }
        val pathSegment = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val knownExt = dispositionExt
            ?: KNOWN_EXTENSIONS.firstOrNull { pathSegment.endsWith(it, ignoreCase = true) }
            ?: return guessedFileName
        return "$withoutBin$knownExt"
    }

    private fun inferMimeFromNameOrUrl(raw: String?): String? {
        val candidate = raw
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return null
        return when {
            candidate.endsWith(".apk") -> "application/vnd.android.package-archive"
            candidate.endsWith(".zip") -> "application/zip"
            candidate.endsWith(".pdf") -> "application/pdf"
            candidate.endsWith(".txt") || candidate.endsWith(".log") || candidate.endsWith(".md") -> "text/plain"
            candidate.endsWith(".json") -> "application/json"
            candidate.endsWith(".csv") -> "text/csv"
            candidate.endsWith(".png") -> "image/png"
            candidate.endsWith(".jpg") || candidate.endsWith(".jpeg") -> "image/jpeg"
            candidate.endsWith(".webp") -> "image/webp"
            candidate.endsWith(".gif") -> "image/gif"
            candidate.endsWith(".svg") -> "image/svg+xml"
            candidate.endsWith(".mp4") -> "video/mp4"
            candidate.endsWith(".webm") -> "video/webm"
            candidate.endsWith(".mp3") -> "audio/mpeg"
            else -> null
        }
    }

    private val KNOWN_EXTENSIONS = listOf(
        ".apk",
        ".zip",
        ".pdf",
        ".txt",
        ".json",
        ".csv",
        ".png",
        ".jpg",
        ".jpeg",
        ".webp",
        ".gif",
        ".svg",
        ".mp4",
        ".webm",
        ".mp3",
    )
}

@Composable
internal fun FocalDownloadStartedPill(
    fileName: String,
    showDownloadsButton: Boolean,
    onOpenDownloads: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val pillShape = RoundedCornerShape(24.dp)
    val pillTint = rememberUpdatedState(colors.effectiveSheetGlass)

    SmokedGlassBox(
        shape = pillShape,
        glassTintState = pillTint,
        borderSpecular = colors.borderSpecular,
        modifier = modifier
            .consumeClicks()
            .testTag("download_started_pill"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = FocalGlyphs.Download,
                contentDescription = null,
                tint = colors.inkPrimary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Downloading $fileName",
                style = typography.controlPillLabel.copy(fontWeight = FontWeight.SemiBold),
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showDownloadsButton) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.surfacePillActive)
                        .clickable(onClick = onOpenDownloads)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Downloads",
                        style = typography.controlPillLabel.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.inkOnPillActive,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Close,
                    contentDescription = "Dismiss download notice",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}


