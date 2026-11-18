package app.focal.feature.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.design.FocalGlyphs
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.SiteFavicon
import app.focal.design.shaders.LocalFocalBackdropLayer
import app.focal.design.shaders.hourglassPinchEffect
import app.focal.design.shaders.opticalVignetteBackground
import app.focal.design.shaders.recordFocalBackdrop
import app.focal.engine.api.ActiveMediaSessionInfo
import app.focal.engine.api.EngineSession
import app.focal.engine.api.EngineSessionState
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
    isOverlaySheetOpen: Boolean = false,
    isInPictureInPictureMode: Boolean = false,
    backdropGraphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    continuityHandoff: SyncEvent.TabHandoffEvent?,
    mediaMiniPlayerInfo: ActiveMediaSessionInfo?,
    pinchProgressState: MutableFloatState,
    pinchCenterYState: MutableFloatState,
    predictiveBackProgressState: FloatState,
    bottomBarScrollHideFraction: FloatState,
    spacesDragOffsetPxState: MutableFloatState? = null,
    spacesSheetRevealDistancePx: Float = 0f,
    onOpenSearchSheet: () -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onCloseCurrentTab: () -> Unit,
    onOpenNewPrivateTab: () -> Unit,
    onStepToAdjacentTab: (Int) -> Unit,
    onOpenSpacesSheet: () -> Unit,
    onSettleSpacesSwipeUp: (committed: Boolean) -> Unit = {},
    onOpenPageMenu: () -> Unit,
    onOpenReaderMode: () -> Unit,
    onTranslatePage: () -> Unit = {},
    onPinchSnapSummarize: () -> Unit,
    onPinchSnapCancelled: () -> Unit,
    onOpenContinuityHandoff: (SyncEvent.TabHandoffEvent) -> Unit,
    onToggleMediaPlayPause: (Boolean) -> Unit,
    onCycleMediaPlaybackRate: (Float) -> Unit,
    onEnterPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
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
        !tag.isNullOrBlank() && tag != deviceLang
    }

    val pillsVisible by remember(bottomBarScrollHideFraction, isOverlaySheetOpen) {
        derivedStateOf { !isOverlaySheetOpen && bottomBarScrollHideFraction.floatValue < 0.5f }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvasBackground),
    ) {
        // Layer 1: Full-Bleed Web Surface (direct 1x HWUI composition) OR Screen 01 Calm Optical Launch Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchToSummarizeDetector(
                    enabled = hasLoadedWebUrl && !isInPictureInPictureMode && !isOverlaySheetOpen,
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
                    val backProg = predictiveBackProgressState.floatValue
                    val scrubActive = kotlin.math.abs(scrubPx) > 1f
                    val scrubScale = if (scrubActive) 0.94f else 1f
                    val backScale = 1f - (backProg * 0.06f)
                    val sheetRecedeScale = 1f - (overlayDimProgress * 0.025f)

                    translationX = scrubPx
                    scaleX = scrubScale * backScale * sheetRecedeScale
                    scaleY = scrubScale * backScale * sheetRecedeScale
                },
        ) {
            if (hasLoadedWebUrl && activeSession != null) {
                WebEngineSurface(
                    session = activeSession,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .recordFocalBackdrop(backdropGraphicsLayer),
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

        // Layer 2: Floating Lower Dock (hidden in Picture-in-Picture and smoothly recedes when a sheet opens)
        if (!isInPictureInPictureMode && overlayDimProgress < 0.99f) {
            val effectiveBackdropLayer = if (hasLoadedWebUrl) null else backdropGraphicsLayer
            CompositionLocalProvider(LocalFocalBackdropLayer provides effectiveBackdropLayer) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                        .graphicsLayer {
                            val scrollHideY = bottomBarScrollHideFraction.floatValue * 18.dp.toPx()
                            val sheetHideY = overlayDimProgress * 28.dp.toPx()
                            translationY = scrollHideY + sheetHideY
                            alpha = (1f - overlayDimProgress * 1.15f).coerceIn(0f, 1f)
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Dock Row `8.dp` above the 3-Part Bar: Media Mini-Player (left) + Smart Pills (right)
                    val activeMedia = mediaMiniPlayerInfo ?: sessionState.activeMedia

                    if (activeMedia != null || (hasLoadedWebUrl && (sessionState.isReaderEligible || foreignLanguageDetected))) {
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
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            } else {
                                Spacer(Modifier.size(1.dp))
                            }

                            SmartContextualPillsRow(
                                visible = pillsVisible && hasLoadedWebUrl,
                                showReaderPill = sessionState.isReaderEligible,
                                showTranslatePill = foreignLanguageDetected,
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
                        spacesDragOffsetPxState = spacesDragOffsetPxState,
                        spacesSheetRevealDistancePx = spacesSheetRevealDistancePx,
                        onOpenTabSwitcher = onOpenTabSwitcher,
                        onCloseCurrentTab = onCloseCurrentTab,
                        onOpenNewPrivateTab = onOpenNewPrivateTab,
                        onTapCenterSearch = onOpenSearchSheet,
                        onStepToAdjacentTab = onStepToAdjacentTab,
                        onSwipeUpOpenSpaces = onOpenSpacesSheet,
                        onSettleSpacesSwipeUp = onSettleSpacesSwipeUp,
                        onOpenPageMenu = onOpenPageMenu,
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
            .statusBarsPadding()
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
