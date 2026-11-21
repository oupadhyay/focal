package app.focal.feature.browser

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.design.FocalGlyphs
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseOwner
import app.focal.design.GestureLeaseToken
import app.focal.design.LocalGestureLeaseController
import app.focal.design.SiteFavicon
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.api.EngineTelemetryState
import app.focal.engine.api.PageTranslationPhase
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

private enum class CenterPillAxisLock {
    UNDECIDED,
    HORIZONTAL_TAB_SCRUB,
}

/**
 * Focal's 3-Part Floating Smoked Glass Bottom Bar (`48.dp` height):
 * - **Left Capsule (`[Tabs]`)**: Tab counter squircle (`Tap = YAB Tab Switcher`, `Long-Press = Quick Close / Private`).
 * - **Center Pill (`[Search / domain.com]`)**: Vector-locked within `10.dp` touch slop:
 *   - `Tap`: Opens Search-First Sheet above IME.
 *   - `Horizontal Scrub` (`|dx| > |dy| * 1.35f`): Live horizontal tab scrubbing with haptic ticks.
 * - **Right Capsule (`[...]`)**: Opens Page Menu, Bookmarks, Reader & Per-Site Settings sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocalBottomBar(
    tabCount: Int,
    domainOrHint: String,
    pageUrl: String = "",
    favicon: Bitmap? = null,
    isHttps: Boolean,
    isPrivateAperture: Boolean,
    isLoading: Boolean,
    loadProgress: Int = 0,
    telemetryFlow: StateFlow<EngineTelemetryState>? = null,
    scrubOffsetPxState: MutableFloatState,
    onOpenTabSwitcher: () -> Unit,
    onCloseCurrentTab: () -> Unit,
    onOpenNewPrivateTab: () -> Unit,
    onTapCenterSearch: () -> Unit,
    onStepToAdjacentTab: (directionDelta: Int) -> Unit,
    onOpenPageMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gestureLease = LocalGestureLeaseController.current
    val barTintState = rememberUpdatedState(colors.effectiveBarGlass)

    val latestOnTapSearch = rememberUpdatedState(onTapCenterSearch)
    val latestOnStepTab = rememberUpdatedState(onStepToAdjacentTab)

    var showTabQuickMenu by remember { mutableStateOf(false) }

    val slopPx = with(density) { 10.dp.toPx() }
    val tabCrossingStepPx = with(density) { 88.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Left Capsule: Tab Counter Squircle (`48.dp` circular glass pill)
        Box {
            SmokedGlassBox(
                shape = CircleShape,
                glassTintState = barTintState,
                borderSpecular = colors.borderSpecular,
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                    )
                    .semantics { contentDescription = "Open Tabs" }
                    .combinedClickable(
                        onClick = onOpenTabSwitcher,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            showTabQuickMenu = true
                        },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 21.dp, height = 20.dp)
                        .border(
                            width = 1.5.dp,
                            color = colors.inkPrimary.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(6.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabCount.coerceAtLeast(1).toString(),
                        style = typography.tabBadgeNumeral,
                        color = colors.inkPrimary,
                    )
                }
            }

            DropdownMenu(
                expanded = showTabQuickMenu,
                onDismissRequest = { showTabQuickMenu = false },
                containerColor = colors.effectiveSheetGlass,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Close Current Tab",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                    },
                    onClick = {
                        showTabQuickMenu = false
                        onCloseCurrentTab()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "New Private Tab",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                    },
                    onClick = {
                        showTabQuickMenu = false
                        onOpenNewPrivateTab()
                    },
                )
            }
        }

        // 2. Center Capsule: Tap = Search · Horizontal Scrub = Switch Tabs
        SmokedGlassBox(
            shape = CircleShape,
            glassTintState = barTintState,
            borderSpecular = colors.borderSpecular,
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                )
                .pointerInput(slopPx, tabCrossingStepPx, gestureLease) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDx = 0f
                        var totalDy = 0f
                        var axisLock = CenterPillAxisLock.UNDECIDED
                        var activeLeaseToken: GestureLeaseToken? = null
                        var preempted = false
                        var emittedSteps = 0

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                if (!change.pressed) {
                                    break
                                }

                                if (
                                    axisLock != CenterPillAxisLock.UNDECIDED &&
                                    gestureLease != null &&
                                    !gestureLease.isValid(activeLeaseToken)
                                ) {
                                    preempted = true
                                    break
                                }

                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y

                                if (axisLock == CenterPillAxisLock.UNDECIDED) {
                                    val absDx = abs(totalDx)
                                    val absDy = abs(totalDy)
                                    if (absDx > slopPx && absDx > absDy * 1.35f) {
                                        val token = gestureLease?.tryAcquire(GestureLeaseOwner.HORIZONTAL_TAB_SCRUB)
                                        if (gestureLease == null || token != null) {
                                            activeLeaseToken = token
                                            axisLock = CenterPillAxisLock.HORIZONTAL_TAB_SCRUB
                                            change.consume()
                                        }
                                    }
                                } else {
                                    change.consume()
                                }

                                when (axisLock) {
                                    CenterPillAxisLock.HORIZONTAL_TAB_SCRUB -> {
                                        scrubOffsetPxState.floatValue = totalDx * 0.42f
                                        val targetSteps = (totalDx / tabCrossingStepPx).toInt()
                                        if (targetSteps != emittedSteps) {
                                            val stepDir = if (targetSteps > emittedSteps) -1 else 1
                                            emittedSteps = targetSteps
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            latestOnStepTab.value(stepDir)
                                        }
                                    }
                                    CenterPillAxisLock.UNDECIDED -> Unit
                                }
                            }

                            scrubOffsetPxState.floatValue = 0f
                            if (
                                !preempted &&
                                axisLock == CenterPillAxisLock.UNDECIDED &&
                                abs(totalDx) <= slopPx &&
                                abs(totalDy) <= slopPx &&
                                gestureLease?.activeOwner == null
                            ) {
                                latestOnTapSearch.value()
                            }
                        } finally {
                            scrubOffsetPxState.floatValue = 0f
                            gestureLease?.release(activeLeaseToken)
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (!isPrivateAperture && domainOrHint.isNotBlank()) {
                        SiteFavicon(
                            urlOrDomain = pageUrl.ifBlank { domainOrHint },
                            liveBitmap = favicon,
                            allowNetworkFetch = true,
                            size = 16.dp,
                            cornerRadius = 4.dp,
                        )
                        if (!isHttps) {
                            Text(
                                text = "!",
                                style = typography.captionMetadata,
                                color = colors.inkSecondary,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (isPrivateAperture) FocalGlyphs.Aperture else FocalGlyphs.Search,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Text(
                        text = domainOrHint.ifBlank { "Search or enter address" },
                        style = typography.urlDomainPill,
                        color = if (domainOrHint.isBlank()) colors.inkSecondary else colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Isolated sub-composable collecting volatile loadProgress so root/bar never recomposes
                LoadProgressHairline(
                    isLoading = isLoading,
                    fallbackProgress = loadProgress,
                    telemetryFlow = telemetryFlow,
                )
            }
        }

        // 3. Right Capsule: Page Menu & Site Overrides (`48.dp` circular glass pill)
        SmokedGlassBox(
            shape = CircleShape,
            glassTintState = barTintState,
            borderSpecular = colors.borderSpecular,
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                )
                .clickable(onClick = onOpenPageMenu),
        ) {
            Icon(
                imageVector = FocalGlyphs.MoreHorizontal,
                contentDescription = "Page Menu",
                tint = colors.inkPrimary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun LoadProgressHairline(
    isLoading: Boolean,
    fallbackProgress: Int,
    telemetryFlow: StateFlow<EngineTelemetryState>?,
) {
    val colors = FocalTheme.colors
    val telemetry = telemetryFlow?.collectAsState()?.value
    val progress = telemetry?.loadProgress ?: fallbackProgress
    if (isLoading && progress in 1..99) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .fillMaxWidth((progress / 100f).coerceIn(0.08f, 1f))
                .height(1.5.dp)
                .clip(CircleShape)
                .background(colors.inkPrimary.copy(alpha = 0.65f)),
        )
    }
}

/**
 * Smart Contextual Pills (`Reader` and `Translate`) floating `8.dp` above the right side
 * of the 3-part bottom bar and collapsing smoothly on `> 18.dp` downward scroll.
 */
@Composable
fun SmartContextualPillsRow(
    visible: Boolean,
    showReaderPill: Boolean,
    showTranslatePill: Boolean,
    translationPhase: PageTranslationPhase = PageTranslationPhase.ORIGINAL,
    onOpenReader: () -> Unit,
    onTriggerTranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val barTint = rememberUpdatedState(colors.effectiveBarGlass)
    val translateLabel = when (translationPhase) {
        PageTranslationPhase.ORIGINAL -> "Translate"
        PageTranslationPhase.DOWNLOADING_MODEL -> "Downloading…"
        PageTranslationPhase.TRANSLATING -> "Translating…"
        PageTranslationPhase.TRANSLATED -> "Original"
        PageTranslationPhase.FAILED -> "Retry Translate"
    }

    AnimatedVisibility(
        visible = visible && (showReaderPill || showTranslatePill),
        enter = fadeIn() + scaleIn(
            initialScale = 0.88f,
            animationSpec = FocalMotion.smartPillSpring(),
        ),
        exit = fadeOut() + scaleOut(
            targetScale = 0.88f,
            animationSpec = FocalMotion.smartPillSpring(),
        ),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showTranslatePill) {
                SmokedGlassBox(
                    shape = CircleShape,
                    glassTintState = barTint,
                    borderSpecular = colors.borderSpecular,
                    modifier = Modifier.clickable(onClick = onTriggerTranslate),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Translate,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = translateLabel,
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                    }
                }
            }

            if (showReaderPill) {
                SmokedGlassBox(
                    shape = CircleShape,
                    glassTintState = barTint,
                    borderSpecular = colors.borderSpecular,
                    modifier = Modifier.clickable(onClick = onOpenReader),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Reader,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Reader",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                    }
                }
            }
        }
    }
}
