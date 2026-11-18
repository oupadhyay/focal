package app.focal.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.design.FocalTheme
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

    val deviceLang = remember { Locale.getDefault().language.lowercase() }
    val foreignLanguageDetected = remember(sessionState.pageLanguageTag, deviceLang) {
        val tag = sessionState.pageLanguageTag?.substringBefore('-')?.lowercase()
        !tag.isNullOrBlank() && tag != deviceLang
    }

    val pillsVisible by remember(bottomBarScrollHideFraction) {
        derivedStateOf { bottomBarScrollHideFraction.floatValue < 0.5f }
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
                    enabled = hasLoadedWebUrl && !isInPictureInPictureMode,
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
                    // Horizontal tab scrub parallax + Android 14–16 Predictive Back page preview curve
                    val scrubPx = scrubOffsetPxState.floatValue
                    val backProg = predictiveBackProgressState.floatValue
                    val scrubActive = kotlin.math.abs(scrubPx) > 1f
                    val scrubScale = if (scrubActive) 0.94f else 1f
                    val backScale = 1f - (backProg * 0.06f)

                    translationX = scrubPx
                    scaleX = scrubScale * backScale
                    scaleY = scrubScale * backScale
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
                        continuityHandoff = continuityHandoff,
                        onOpenContinuityHandoff = onOpenContinuityHandoff,
                        onTapStartSearch = onOpenSearchSheet,
                    )
                }
            }
        }

        // Layer 2: Floating Lower Dock (hidden completely in Picture-in-Picture mode)
        if (!isInPictureInPictureMode) {
            val effectiveBackdropLayer = if (hasLoadedWebUrl) null else backdropGraphicsLayer
            CompositionLocalProvider(LocalFocalBackdropLayer provides effectiveBackdropLayer) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                        .graphicsLayer {
                            // Hide contextual chrome smoothly via hardware layer only (0 layout pass)
                            translationY = bottomBarScrollHideFraction.floatValue * 18.dp.toPx()
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Dock Row `8.dp` above the 3-Part Bar: Media Mini-Player (left) + Smart Pills (right)
                    val activeMedia = mediaMiniPlayerInfo ?: sessionState.activeMedia

                    if (activeMedia != null || (hasLoadedWebUrl && (sessionState.isReaderEligible || foreignLanguageDetected))) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
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
 * Screen 01 · Calm Optical Launch / Empty State.
 * Zero news feed, zero crypto/rewards bloat — pure AGSL radial optical vignette + optional E2EE handoff pill.
 */
@Composable
private fun CalmOpticalLaunchCanvas(
    isPrivateAperture: Boolean,
    continuityHandoff: SyncEvent.TabHandoffEvent?,
    onOpenContinuityHandoff: (SyncEvent.TabHandoffEvent) -> Unit,
    onTapStartSearch: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val baseColorState = rememberUpdatedState(colors.canvasBackground)
    val bloomColorState = rememberUpdatedState(
        if (colors.isDarkGlass || isPrivateAperture) {
            Color.White.copy(alpha = 0.05f)
        } else {
            Color.Black.copy(alpha = 0.038f)
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .opticalVignetteBackground(
                baseColorState = baseColorState,
                bloomColorState = bloomColorState,
            )
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        // Optional Top E2EE Desktop Continuity Handoff Chip (only rendered when active)
        if (continuityHandoff != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clip(CircleShape)
                    .background(colors.effectiveBarGlass)
                    .border(1.dp, colors.borderSpecular, CircleShape)
                    .clickable { onOpenContinuityHandoff(continuityHandoff) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "⇄ ${continuityHandoff.sourceDeviceName ?: "Desktop"} · ${continuityHandoff.title.ifBlank { continuityHandoff.url }}",
                    style = typography.captionMetadata,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Centered Minimal Focal Aperture Mark
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .clickable(onClick = onTapStartSearch)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .border(1.dp, colors.borderSpecular, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "◎",
                    style = typography.displayHeadline,
                    color = colors.inkPrimary,
                )
            }

            Text(
                text = if (isPrivateAperture) "Private Aperture" else "Focal",
                style = typography.sheetTitle,
                color = colors.inkPrimary,
            )
            Text(
                text = if (isPrivateAperture) {
                    "Isolated ephemeral memory · Zero history writes"
                } else {
                    "Tap anywhere to search · Swipe ↑ bar for Spaces"
                },
                style = typography.captionMetadata,
                color = colors.inkFaint,
            )
        }
    }
}
