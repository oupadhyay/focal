package app.focal.feature.search

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.consumeClicks
import app.focal.design.shaders.SmokedGlassBox
import app.focal.sync.SyncEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class QuickSquirclePin(
    val id: String,
    val shortCode: String,
    val title: String,
    val url: String,
)

val DefaultSearchSquirclePins: ImmutableList<QuickSquirclePin> = persistentListOf(
    QuickSquirclePin("pin-imdb", "IM", "IMDb", "https://www.imdb.com"),
    QuickSquirclePin("pin-wiki", "WI", "Wiki", "https://en.wikipedia.org"),
    QuickSquirclePin("pin-gh", "GH", "GitHub", "https://github.com"),
    QuickSquirclePin("pin-ars", "AR", "Ars", "https://arstechnica.com"),
    QuickSquirclePin("pin-rd", "RD", "Reddit", "https://www.reddit.com"),
)

/**
 * Screen 02 · Floating Search-First Smoked Glass Sheet anchored `8.dp` above `WindowInsets.ime`.
 *
 * Features:
 * - E2EE Continuity Handoff Banner at top (when a paired Desktop browser shares a tab/link).
 * - 5 Quick Squircle Pins in lower thumb reach.
 * - Double-Space Site Search (`im␣␣`, `wi␣␣`, `rd␣␣`, `yt␣␣`, `gh␣␣`) with `135.dp` radial contextual
 *   glow strictly clipped inside the left side of the input pill + Backspace chip unwrap.
 * - Right-aligned `28.dp` Private Aperture `◎` circular toggle darkening sheet to `surface.glass.private`.
 */
@Composable
fun SearchSheet(
    initialQuery: String,
    isPrivateAperture: Boolean,
    pins: ImmutableList<QuickSquirclePin> = DefaultSearchSquirclePins,
    continuityHandoff: SyncEvent.TabHandoffEvent? = null,
    onTogglePrivateAperture: (Boolean) -> Unit,
    onNavigateToUrl: (url: String, isPrivate: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val haptics = LocalHapticFeedback.current
    val detector = remember { DoubleSpaceSiteDetector() }
    val focusRequester = remember { FocusRequester() }
    val displayedPins = remember(pins) { pins.take(5).toImmutableList() }

    var activeSite by remember { mutableStateOf<SiteSearchEngine?>(null) }
    var textFieldValue by remember(initialQuery) {
        mutableStateOf(
            TextFieldValue(
                text = initialQuery,
                selection = TextRange(0, initialQuery.length),
            ),
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val effectiveSheetTint = rememberUpdatedState(
        if (isPrivateAperture) colors.surfaceGlassPrivate else colors.surfaceGlassSheet,
    )
    val sheetShape = RoundedCornerShape(26.dp)
    val inputPillShape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = sheetShape,
            glassTintState = effectiveSheetTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 8.dp)
                // Consume clicks inside the sheet so tapping backdrop dismisses cleanly
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Optional E2EE Desktop Continuity Handoff Banner
                if (continuityHandoff != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surfacePillSubtle)
                            .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                            .clickable {
                                onNavigateToUrl(continuityHandoff.url, isPrivateAperture)
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⇄ From ${continuityHandoff.sourceDeviceName ?: "Desktop"} · E2EE",
                                style = typography.captionMetadata,
                                color = colors.inkSecondary,
                            )
                            Text(
                                text = continuityHandoff.title.ifBlank { continuityHandoff.url },
                                style = typography.urlDomainPill,
                                color = colors.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Open ↗",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                    }
                }

                // 5 Squircle Pins Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    displayedPins.forEach { pin ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surfacePillSubtle)
                                .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                                .clickable { onNavigateToUrl(pin.url, isPrivateAperture) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = pin.shortCode,
                                style = typography.tabBadgeNumeral,
                                color = colors.inkPrimary,
                            )
                            Text(
                                text = pin.title,
                                style = typography.captionMetadata,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

            // Shortcut Hint Strip + Private Aperture Status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isPrivateAperture) {
                        "◎ Private Aperture · Ephemeral Zero-History Session"
                    } else {
                        "Type im␣␣ · wi␣␣ · rd␣␣ · yt␣␣ · gh␣␣ for site search"
                    },
                    style = typography.captionMetadata,
                    color = if (isPrivateAperture) colors.inkPrimary else colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Main Search Input Pill (with strictly-clipped 135.dp left contextual site glow)
            val siteGlowColor = activeSite?.colors?.radialInputGlow ?: Color.Transparent
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(inputPillShape)
                    .background(colors.surfacePillSubtle)
                    .drawBehind {
                        if (siteGlowColor.alpha > 0.005f) {
                            val glowRadius = 135.dp.toPx()
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(siteGlowColor, Color.Transparent),
                                    center = Offset(24.dp.toPx(), size.height * 0.5f),
                                    radius = glowRadius,
                                ),
                            )
                        }
                    }
                    .border(1.dp, colors.borderSpecular, inputPillShape)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Spring-animated Site Search Chip (`[IMDb ×]`)
                AnimatedVisibility(
                    visible = activeSite != null,
                    enter = fadeIn() + scaleIn(
                        initialScale = 0.75f,
                        animationSpec = FocalMotion.siteChipSpring(),
                    ),
                    exit = fadeOut() + scaleOut(
                        targetScale = 0.80f,
                        animationSpec = FocalMotion.siteChipSpring(),
                    ),
                ) {
                    val site = activeSite
                    if (site != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(site.colors.badgeBackground)
                                .clickable {
                                    activeSite = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = site.label,
                                style = typography.controlPillLabel,
                                color = site.colors.badgeForeground,
                            )
                            Text(
                                text = "×",
                                style = typography.controlPillLabel,
                                color = site.colors.badgeForeground.copy(alpha = 0.75f),
                            )
                        }
                    }
                }

                if (activeSite == null) {
                    Text(
                        text = "⌕",
                        style = typography.focusInput,
                        color = colors.inkSecondary,
                    )
                }

                // Core Input Field with Backspace unwrap + Double-Space detection
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            text = activeSite?.placeholder ?: "Search or enter address…",
                            style = typography.focusInput,
                            color = colors.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { nextFieldValue ->
                            val transition = detector.onQueryChanged(
                                previousQuery = textFieldValue.text,
                                newQuery = nextFieldValue.text,
                                currentSite = activeSite,
                                eventTimeMs = SystemClock.uptimeMillis(),
                            )
                            if (transition.chipJustActivated) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                activeSite = transition.activeSite
                                textFieldValue = TextFieldValue(
                                    text = transition.updatedQuery,
                                    selection = TextRange(transition.updatedQuery.length),
                                )
                            } else {
                                textFieldValue = nextFieldValue
                            }
                        },
                        singleLine = true,
                        textStyle = typography.focusInput.copy(color = colors.inkPrimary),
                        cursorBrush = SolidColor(colors.inkPrimary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val targetUrl = detector.resolveDestinationUrl(
                                    rawQuery = textFieldValue.text,
                                    activeSite = activeSite,
                                )
                                if (targetUrl != "about:blank") {
                                    onNavigateToUrl(targetUrl, isPrivateAperture)
                                }
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                if (
                                    keyEvent.type == KeyEventType.KeyDown &&
                                    keyEvent.key == Key.Backspace
                                ) {
                                    val unwrap = detector.onBackspacePressed(
                                        currentQuery = textFieldValue.text,
                                        currentSite = activeSite,
                                    )
                                    if (unwrap != null) {
                                        activeSite = null
                                        textFieldValue = TextFieldValue(
                                            text = unwrap.updatedQuery,
                                            selection = TextRange(unwrap.updatedQuery.length),
                                        )
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                false
                            },
                    )
                }

                // Go Pill when input has text
                if (textFieldValue.text.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.surfacePillActive)
                            .clickable {
                                val targetUrl = detector.resolveDestinationUrl(
                                    rawQuery = textFieldValue.text,
                                    activeSite = activeSite,
                                )
                                if (targetUrl != "about:blank") {
                                    onNavigateToUrl(targetUrl, isPrivateAperture)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Go",
                            style = typography.controlPillLabel,
                            color = colors.inkOnPillActive,
                        )
                    }
                }

                // Right-aligned 28.dp Private Aperture `◎` Circular Toggle
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPrivateAperture) colors.surfacePillActive else colors.surfacePillSubtle,
                        )
                        .border(1.dp, colors.borderSpecular, CircleShape)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePrivateAperture(!isPrivateAperture)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "◎",
                        style = typography.controlPillLabel,
                        color = if (isPrivateAperture) colors.inkOnPillActive else colors.inkSecondary,
                    )
                }
            }
            }
        }
    }
}
