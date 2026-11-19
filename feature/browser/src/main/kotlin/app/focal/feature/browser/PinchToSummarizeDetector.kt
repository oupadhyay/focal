package app.focal.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.focal.ai.GroundedBullet
import app.focal.ai.GroundedPageSummary
import app.focal.ai.LocalSummarizerBackend
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalSheetGrabber
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseOwner
import app.focal.design.GestureLeaseToken
import app.focal.design.LocalGestureLeaseController
import app.focal.design.consumeClicks
import app.focal.design.shaders.SmokedGlassBox

const val PINCH_SUMMARIZE_SNAP_THRESHOLD: Float = 0.68f

/**
 * Two-finger inward Pinch-to-Summarize gesture detector (`Screen 04`).
 *
 * Strictly ignores 1-finger touches and **never** intercepts the left or right `24.dp` screen
 * edges on gesture start so Android 14–16 `PredictiveBackHandler` stays 100% unobstructed.
 *
 * When webpage scale is at minimum (`<= 1.04f`) and two fingers pinch inward (`cumulativeZoom < 0.96f`),
 * acquires an exclusive [GestureLeaseOwner.PINCH_SUMMARIZE] token and continuously drives
 * [pinchProgressState] (`0f..1f`) and [pinchCenterYState] (`0f..1f`) at 120Hz.
 */
@Composable
fun Modifier.pinchToSummarizeDetector(
    enabled: Boolean,
    isPageAtMinimumZoom: () -> Boolean,
    pinchProgressState: MutableFloatState,
    pinchCenterYState: MutableFloatState,
    onSnapSummarizeCommitted: () -> Unit,
    onSnapCancelled: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val gestureLease = LocalGestureLeaseController.current
    val latestIsAtMinZoom = rememberUpdatedState(isPageAtMinimumZoom)
    val latestOnCommitted = rememberUpdatedState(onSnapSummarizeCommitted)
    val latestOnCancelled = rememberUpdatedState(onSnapCancelled)

    if (!enabled) return this

    return this.pointerInput(gestureLease) {
        val edgeGuardPx = 24.dp.toPx()

        awaitEachGesture {
            var cumulativeZoom = 1f
            var activeInwardPinch = false
            var activeLeaseToken: GestureLeaseToken? = null
            var preemptedByOtherGesture = false
            var crossedThresholdHapticSent = false

            try {
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val pressedPointers = event.changes.filter { it.pressed }

                    if (activeInwardPinch && gestureLease != null && !gestureLease.isValid(activeLeaseToken)) {
                        preemptedByOtherGesture = true
                        break
                    }

                    if (pressedPointers.size >= 2 && (activeInwardPinch || latestIsAtMinZoom.value())) {
                        // Only reject gestures starting inside Android's 24.dp Predictive Back edge zones
                        // BEFORE activeInwardPinch latches so mid-pinch finger drift never freezes progress.
                        val blockedByBackEdge = !activeInwardPinch && pressedPointers.any { change ->
                            change.position.x < edgeGuardPx || change.position.x > (size.width - edgeGuardPx)
                        }
                        if (!blockedByBackEdge) {
                            val stepZoom = event.calculateZoom()
                            if (stepZoom > 0f) {
                                cumulativeZoom *= stepZoom
                            }

                            if (!activeInwardPinch && cumulativeZoom < 0.96f) {
                                val acquired = gestureLease?.tryAcquire(GestureLeaseOwner.PINCH_SUMMARIZE)
                                if (gestureLease == null || acquired != null) {
                                    activeLeaseToken = acquired
                                    activeInwardPinch = true
                                }
                            }

                            if (activeInwardPinch) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid != Offset.Unspecified && size.height > 0) {
                                    pinchCenterYState.floatValue =
                                        (centroid.y / size.height.toFloat()).coerceIn(0.2f, 0.8f)
                                }

                                // Map zoom [0.96 -> 0.52] smoothly onto pinchProgress [0.0 -> 1.0]
                                val progress = ((0.96f - cumulativeZoom) / 0.44f).coerceIn(0f, 1f)
                                pinchProgressState.floatValue = progress

                                if (progress >= PINCH_SUMMARIZE_SNAP_THRESHOLD && !crossedThresholdHapticSent) {
                                    crossedThresholdHapticSent = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if (progress < PINCH_SUMMARIZE_SNAP_THRESHOLD) {
                                    crossedThresholdHapticSent = false
                                }

                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })

                if (activeInwardPinch) {
                    if (!preemptedByOtherGesture && pinchProgressState.floatValue >= PINCH_SUMMARIZE_SNAP_THRESHOLD) {
                        latestOnCommitted.value()
                    } else {
                        latestOnCancelled.value()
                    }
                }
            } finally {
                gestureLease?.release(activeLeaseToken)
            }
        }
    }
}

/**
 * Screen 04 · Smoked Glass Grounded Local AI Summary Sheet.
 *
 * Displays truthful on-device runtime badge (`Gemini Nano · On-Device` / `Gemma 3 1B` / `Focal Local · Offline RAM`),
 * `Allo Crisp` thesis headline, and 3–5 `Switzer` grounded bullet cards. Tapping any bullet
 * unpinches the sheet (`pinchProgress -> 0f`) and smooth-scrolls the WebView to `[data-focal-para-id="N"]`.
 */
@Composable
fun PinchSummarySheet(
    isSummarizing: Boolean,
    summary: GroundedPageSummary?,
    activeBackendLabel: String = LocalSummarizerBackend.LOCAL_SALIENT_EXTRACTOR.badgeLabel,
    onJumpToGroundedParagraph: (GroundedBullet) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sheetTint = rememberUpdatedState(colors.effectiveSheetGlass)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = FocalGeometry.SheetOuterShape,
            glassTintState = sheetTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FocalSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))

                // Top Runtime Privacy Badge + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.surfacePillSubtle)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = summary?.backend?.badgeLabel ?: activeBackendLabel,
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.surfacePillSubtle)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Close,
                            contentDescription = "Close summary",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                if (isSummarizing || summary == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Distilling on-device…",
                            style = typography.cardTitle,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Grounded to source paragraphs · Offline",
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                        )
                    }
                } else {
                    Text(
                        text = summary.headline,
                        style = typography.sheetTitle,
                        color = colors.inkPrimary,
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        summary.bullets.forEach { bullet ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(FocalGeometry.TileShape)
                                    .background(colors.surfacePillSubtle)
                                    .clickable { onJumpToGroundedParagraph(bullet) }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = bullet.text,
                                    style = typography.bodyCompact,
                                    color = colors.inkPrimary,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Source §${bullet.groundedParaId + 1}",
                                        style = typography.captionMetadata,
                                        color = colors.inkSecondary,
                                    )
                                    Icon(
                                        imageVector = FocalGlyphs.ExternalArrow,
                                        contentDescription = "Jump to paragraph",
                                        tint = colors.inkSecondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
