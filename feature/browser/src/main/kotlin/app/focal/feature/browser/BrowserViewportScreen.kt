package app.focal.feature.browser

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.design.FocalGlyphs
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.SiteFavicon
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.LocalFocalBackdropLayer
import app.focal.design.shaders.SmokedGlassBox
import app.focal.design.shaders.hourglassPinchEffect
import app.focal.design.shaders.opticalVignetteBackground
import app.focal.design.shaders.recordFocalBackdrop
import app.focal.engine.api.ActiveMediaSessionInfo
import app.focal.engine.api.EngineSession
import app.focal.engine.api.EngineSessionState
import app.focal.engine.api.PageTranslationPhase
import app.focal.engine.webview.WebEngineSurface
import app.focal.feature.media.MediaMiniPlayerDock
import app.focal.sync.SyncEvent
import java.util.Locale

/**
 * Screen 01 & Screen 03 · Full-Bleed 120Hz Browser Viewport + Floating Optical Chrome.
 *
 * Strict 120Hz & Android Predictive Back guarantees:
 * 1. Left & right `24.dp` screen edges have **zero horizontal drag interceptors**, leaving
 *    `WindowInsets.systemGestures` 100% clear for Android 14–16 `PredictiveBackHandler`.
 * 2. The underlying [WebEngineSurface] fills `Modifier.fillMaxSize()` and **never resizes**
 *    during scroll (`0.0ms` layout pass).
 * 3. All gesture state (`pinchProgressState`, `scrubOffsetPxState`, `bottomChromeTranslationY`)
 *    is read strictly inside `Modifier.graphicsLayer {}`.
 */
@Composable
fun BrowserViewportScreen(
    activeSession: EngineSession?,
    tabCount: Int,
    isPrivateAperture: Boolean,
    adaptiveBottomBarDarkGlass: Boolean = FocalTheme.colors.isDarkGlass,
    isOverlaySheetOpen: Boolean = false,
    isInPictureInPictureMode: Boolean = false,
    backdropGraphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    continuityHandoff: SyncEvent.TabHandoffEvent?,
    mediaMiniPlayerInfo: ActiveMediaSessionInfo?,
    isMediaMiniPlayerSuppressed: Boolean = false,
    isMediaFromBackgroundTab: Boolean = false,
    pinchProgressState: MutableFloatState,
    pinchCenterYState: MutableFloatState,
    predictiveBackProgressState: FloatState,
    bottomBarScrollHideFraction: FloatState,
    onOpenSearchSheet: () -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onCloseCurrentTab: () -> Unit,
    onOpenNewPrivateTab: () -> Unit,
    onStepToAdjacentTab: (Int) -> Unit,
    onOpenPageMenu: () -> Unit,
    onOpenReaderMode: () -> Unit,
    onTranslatePage: () -> Unit = {},
    onPinchSnapSummarize: () -> Unit,
    onPinchSnapCancelled: () -> Unit,
    onOpenContinuityHandoff: (SyncEvent.TabHandoffEvent) -> Unit,
    onToggleMediaPlayPause: (Boolean) -> Unit,
    onCycleMediaPlaybackRate: (Float) -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onFocusMediaTab: (() -> Unit)? = null,
    onDismissMediaMiniPlayer: (() -> Unit)? = null,
    fullHideBottomBarOnScroll: Boolean = true,
    isFindInPageOpen: Boolean = false,
    onFindQueryChange: (String) -> Unit = {},
    onFindNext: (Boolean) -> Unit = {},
    onCloseFindInPage: () -> Unit = {},
    onExitElementZapper: () -> Unit = {},
    onBottomChromeInsetChanged: (Int) -> Unit = {},
    historyBackPreview: Bitmap? = null,
    predictiveBackSwipeEdgeState: IntState? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sessionState by activeSession?.state?.collectAsState()
        ?: remember { androidx.compose.runtime.mutableStateOf(EngineSessionState(sessionId = "empty")) }

    val scrubOffsetPxState = remember { mutableFloatStateOf(0f) }
    val hasLoadedWebUrl = activeSession != null && sessionState.url.isNotBlank() && sessionState.url != "about:blank"

    val overlayDimProgress by animateFloatAsState(
        targetValue = if (isOverlaySheetOpen) 1f else 0f,
        animationSpec = FocalMotion.smartPillSpring(),
        label = "FocalOverlayDimProgress",
    )

    val deviceLang = remember { Locale.getDefault().language.lowercase() }
    val foreignLanguageDetected = remember(sessionState.pageLanguageTag, deviceLang) {
        val tag = sessionState.pageLanguageTag?.substringBefore('-')?.lowercase()
        !tag.isNullOrBlank() && tag != "und" && tag != deviceLang
    }
    val translationPhase = sessionState.translationStatus.phase
    val showTranslatePill = foreignLanguageDetected || translationPhase != PageTranslationPhase.ORIGINAL

    val pillsVisible by remember(bottomBarScrollHideFraction, isOverlaySheetOpen) {
        derivedStateOf { !isOverlaySheetOpen && bottomBarScrollHideFraction.floatValue < 0.5f }
    }

    // Bottom Chrome Viewport Clearance Reporting (`WebViewSession.setBottomChromeInsetPx`)
    var measuredDockHeightPx by remember { mutableIntStateOf(0) }
    val isDockScrolledOff by remember(bottomBarScrollHideFraction, fullHideBottomBarOnScroll) {
        derivedStateOf {
            fullHideBottomBarOnScroll && bottomBarScrollHideFraction.floatValue >= 0.5f
        }
    }
    val effectiveBottomInsetPx = if (
        isInPictureInPictureMode ||
        isOverlaySheetOpen ||
        isFindInPageOpen ||
        isDockScrolledOff
    ) {
        0
    } else {
        measuredDockHeightPx
    }
    val latestOnBottomChromeInsetChanged = rememberUpdatedState(onBottomChromeInsetChanged)
    LaunchedEffect(activeSession?.sessionId, effectiveBottomInsetPx) {
        latestOnBottomChromeInsetChanged.value(effectiveBottomInsetPx)
    }

    // Interactive Edge-Swipe Back Snapshot Preview (memoized ImageBitmap, zero recomposition during swipe)
    val historyBackImageBitmap = remember(historyBackPreview) {
        historyBackPreview?.takeUnless { it.isRecycled }?.asImageBitmap()
    }
    val showHistoryBackPreview by remember(
        predictiveBackProgressState,
        historyBackImageBitmap,
        isOverlaySheetOpen,
        isInPictureInPictureMode,
    ) {
        derivedStateOf {
            !isOverlaySheetOpen &&
                !isInPictureInPictureMode &&
                historyBackImageBitmap != null &&
                predictiveBackProgressState.floatValue > 0.01f
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvasBackground),
    ) {
        // Layer 0: Cached previous-page snapshot peeking beneath Layer 1 during predictive edge-swipe back
        if (showHistoryBackPreview && historyBackImageBitmap != null && historyBackPreview?.isRecycled == false) {
            Image(
                bitmap = historyBackImageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                    )
                    .graphicsLayer {
                        val backP = predictiveBackProgressState.floatValue.coerceIn(0f, 1f)
                        val previewScale = 0.96f + backP * 0.04f
                        scaleX = previewScale
                        scaleY = previewScale
                        alpha = (0.45f + backP * 0.55f).coerceIn(0f, 1f)
                    }
                    .testTag("history_back_preview"),
            )
        }

        // Layer 1: Full-Bleed Web Surface (direct 1x HWUI composition) OR Screen 01 Calm Optical Launch Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchToSummarizeDetector(
                    enabled = hasLoadedWebUrl &&
                        !isInPictureInPictureMode &&
                        !isOverlaySheetOpen &&
                        !sessionState.isElementZapperActive,
                    isPageAtMinimumZoom = {
                        (activeSession?.telemetry?.value?.currentScaleFactor ?: 1f) <= 1.04f
                    },
                    pinchProgressState = pinchProgressState,
                    pinchCenterYState = pinchCenterYState,
                    onSnapSummarizeCommitted = onPinchSnapSummarize,
                    onSnapCancelled = onPinchSnapCancelled,
                )
                .hourglassPinchEffect(
                    pinchProgressState = pinchProgressState,
                    pinchCenterYState = pinchCenterYState,
                )
                .graphicsLayer {
                    // Horizontal tab scrub parallax + Android 14–16 Predictive Back + subtle sheet recession
                    val scrubPx = scrubOffsetPxState.floatValue
                    val backProg = predictiveBackProgressState.floatValue.coerceIn(0f, 1f)
                    val edgeSign = if ((predictiveBackSwipeEdgeState?.intValue ?: 0) == 0) 1f else -1f
                    val showBackSlide = !isOverlaySheetOpen &&
                        !isInPictureInPictureMode &&
                        historyBackImageBitmap != null &&
                        backProg > 0.01f
                    val backSlidePx = if (showBackSlide) {
                        edgeSign * backProg * 96.dp.toPx()
                    } else {
                        0f
                    }
                    val scrubFraction = (kotlin.math.abs(scrubPx) / 88.dp.toPx()).coerceIn(0f, 1f)
                    val scrubScale = 1f - (scrubFraction * 0.06f)
                    val backScale = 1f - (backProg * 0.06f)
                    val sheetRecedeScale = 1f - (overlayDimProgress * 0.025f)

                    translationX = scrubPx + backSlidePx
                    scaleX = scrubScale * backScale * sheetRecedeScale
                    scaleY = scrubScale * backScale * sheetRecedeScale
                    shadowElevation = if (showBackSlide) 16.dp.toPx() * backProg else 0f
                },
        ) {
            if (hasLoadedWebUrl && activeSession != null) {
                WebEngineSurface(
                    session = activeSession,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isInPictureInPictureMode) {
                                Modifier
                            } else {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                                )
                            },
                        ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (overlayDimProgress < 0.99f) {
                                Modifier.recordFocalBackdrop(backdropGraphicsLayer)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    CalmOpticalLaunchCanvas(
                        isPrivateAperture = isPrivateAperture,
                        overlayDimProgress = overlayDimProgress,
                        continuityHandoff = continuityHandoff,
                        onOpenContinuityHandoff = onOpenContinuityHandoff,
                        onTapStartSearch = onOpenSearchSheet,
                    )
                }
            }
        }

        // Layer 1.5: Element Zapper Active Floating Smoked Glass Banner (top-center)
        if (sessionState.isElementZapperActive && !isOverlaySheetOpen && !isInPictureInPictureMode) {
            val zapperTint = rememberUpdatedState(colors.effectiveBarGlass)
            SmokedGlassBox(
                shape = CircleShape,
                glassTintState = zapperTint,
                borderSpecular = colors.borderSpecular,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                    .focalSheetEntrance(riseDp = (-12).dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                    )
                    .testTag("element_zapper_banner")
                    .consumeClicks(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = FocalGlyphs.EyeOff,
                        contentDescription = null,
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "Tap an element to hide",
                        style = typography.controlPillLabel,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.surfacePillActive)
                            .clickable(onClick = onExitElementZapper)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Done",
                            style = typography.controlPillLabel,
                            color = colors.inkOnPillActive,
                        )
                    }
                }
            }
        }

        // Layer 2: Floating Lower Dock — adapts ONLY bottom bar elements to light/dark web pages
        if (!isInPictureInPictureMode && !isFindInPageOpen && overlayDimProgress < 0.99f) {
            val effectiveBackdropLayer = if (hasLoadedWebUrl) null else backdropGraphicsLayer
            val dockDarkGlass = if (hasLoadedWebUrl) adaptiveBottomBarDarkGlass else colors.isDarkGlass
            FocalTheme(
                darkGlass = dockDarkGlass,
                isPrivateAperture = isPrivateAperture,
            ) {
                CompositionLocalProvider(LocalFocalBackdropLayer provides effectiveBackdropLayer) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { size -> measuredDockHeightPx = size.height }
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp)
                            .graphicsLayer {
                                val scrollHideFraction = bottomBarScrollHideFraction.floatValue.coerceIn(0f, 1f)
                                val scrollHideY = if (fullHideBottomBarOnScroll) {
                                    scrollHideFraction * 92.dp.toPx()
                                } else {
                                    scrollHideFraction * 18.dp.toPx()
                                }
                                val sheetHideY = overlayDimProgress * 28.dp.toPx()
                                val scrollAlpha = if (fullHideBottomBarOnScroll) {
                                    (1f - scrollHideFraction).coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                                val sheetAlpha = (1f - overlayDimProgress * 1.15f).coerceIn(0f, 1f)
                                translationY = scrollHideY + sheetHideY
                                alpha = scrollAlpha * sheetAlpha
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Dock Row `8.dp` above the 3-Part Bar: Media Mini-Player (left) + Smart Pills (right)
                        val activeMedia = if (isMediaMiniPlayerSuppressed) {
                            null
                        } else {
                            mediaMiniPlayerInfo ?: sessionState.activeMedia
                        }

                        if (activeMedia != null || (hasLoadedWebUrl && (sessionState.isReaderEligible || showTranslatePill))) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (activeMedia != null) {
                                    MediaMiniPlayerDock(
                                        mediaInfo = activeMedia,
                                        onTogglePlayPause = onToggleMediaPlayPause,
                                        onCyclePlaybackRate = onCycleMediaPlaybackRate,
                                        onEnterPictureInPicture = onEnterPictureInPicture,
                                        isFromBackgroundTab = isMediaFromBackgroundTab,
                                        onFocusMediaTab = onFocusMediaTab,
                                        onDismiss = onDismissMediaMiniPlayer,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                } else {
                                    Spacer(Modifier.size(1.dp))
                                }

                                SmartContextualPillsRow(
                                    visible = pillsVisible && hasLoadedWebUrl,
                                    showReaderPill = sessionState.isReaderEligible,
                                    showTranslatePill = showTranslatePill,
                                    translationPhase = translationPhase,
                                    onOpenReader = onOpenReaderMode,
                                    onTriggerTranslate = onTranslatePage,
                                )
                            }
                        }

                        FocalBottomBar(
                            tabCount = tabCount,
                            domainOrHint = if (hasLoadedWebUrl) sessionState.domain else "",
                            pageUrl = if (hasLoadedWebUrl) sessionState.url else "",
                            favicon = if (hasLoadedWebUrl) sessionState.favicon else null,
                            isHttps = sessionState.isHttps,
                            isPrivateAperture = isPrivateAperture,
                            isLoading = sessionState.isLoading,
                            loadProgress = sessionState.loadProgress,
                            telemetryFlow = activeSession?.telemetry,
                            scrubOffsetPxState = scrubOffsetPxState,
                            onOpenTabSwitcher = onOpenTabSwitcher,
                            onCloseCurrentTab = onCloseCurrentTab,
                            onOpenNewPrivateTab = onOpenNewPrivateTab,
                            onTapCenterSearch = onOpenSearchSheet,
                            onStepToAdjacentTab = onStepToAdjacentTab,
                            onOpenPageMenu = onOpenPageMenu,
                        )
                    }
                }
            }
        }

        // Layer 3: Find in Page Floating Smoked Glass Bar (replaces normal bottom bar cleanly above IME)
        if (isFindInPageOpen && !isOverlaySheetOpen && !isInPictureInPictureMode) {
            val effectiveBackdropLayer = if (hasLoadedWebUrl) null else backdropGraphicsLayer
            val dockDarkGlass = if (hasLoadedWebUrl) adaptiveBottomBarDarkGlass else colors.isDarkGlass
            FocalTheme(
                darkGlass = dockDarkGlass,
                isPrivateAperture = isPrivateAperture,
            ) {
                CompositionLocalProvider(LocalFocalBackdropLayer provides effectiveBackdropLayer) {
                    FindInPageBar(
                        findState = sessionState.findInPageState,
                        onQueryChange = onFindQueryChange,
                        onFindPrevious = { onFindNext(false) },
                        onFindNext = { onFindNext(true) },
                        onClose = onCloseFindInPage,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/**
 * Screen 01 · Serene Optical Launch Canvas.
 *
 * Positioned at upper-optical center (`verticalBias = -0.22f`) and faded cleanly when an overlay
 * sheet opens so it never collides with or pokes behind floating Smoked Glass sheets.
 */
@Composable
private fun CalmOpticalLaunchCanvas(
    isPrivateAperture: Boolean,
    overlayDimProgress: Float,
    continuityHandoff: SyncEvent.TabHandoffEvent?,
    onOpenContinuityHandoff: (SyncEvent.TabHandoffEvent) -> Unit,
    onTapStartSearch: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val baseColorState = rememberUpdatedState(colors.canvasBackground)
    val bloomColorState = rememberUpdatedState(
        if (colors.isDarkGlass || isPrivateAperture) {
            Color.White.copy(alpha = 0.042f)
        } else {
            Color.Black.copy(alpha = 0.032f)
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .opticalVignetteBackground(
                baseColorState = baseColorState,
                bloomColorState = bloomColorState,
            )
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onTapStartSearch,
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        // Optional Top E2EE Desktop Continuity Handoff Chip (only rendered when active)
        if (continuityHandoff != null && overlayDimProgress < 0.5f) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable { onOpenContinuityHandoff(continuityHandoff) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SiteFavicon(
                    urlOrDomain = continuityHandoff.url,
                    allowNetworkFetch = !isPrivateAperture,
                    size = 16.dp,
                    cornerRadius = 4.dp,
                )
                Text(
                    text = "${continuityHandoff.sourceDeviceName ?: "Desktop"} · ${continuityHandoff.title.ifBlank { continuityHandoff.url }}",
                    style = typography.captionMetadata,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Upper-Optical Serene Aperture Emblem (recedes completely when sheets open)
        Column(
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = 0f, verticalBias = -0.22f))
                .graphicsLayer {
                    val markAlpha = (1f - overlayDimProgress * 1.4f).coerceIn(0f, 1f)
                    alpha = markAlpha
                    scaleX = 1f - overlayDimProgress * 0.06f
                    scaleY = 1f - overlayDimProgress * 0.06f
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Aperture,
                    contentDescription = null,
                    tint = colors.inkPrimary.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp),
                )
            }

            Text(
                text = if (isPrivateAperture) "Private Aperture" else "Focal",
                style = typography.sheetTitle,
                color = colors.inkPrimary.copy(alpha = 0.88f),
            )
            if (isPrivateAperture) {
                Text(
                    text = "Zero history · Ephemeral memory",
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}
