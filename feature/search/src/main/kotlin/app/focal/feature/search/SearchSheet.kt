package app.focal.feature.search

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.design.FocalFaviconTile
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.SiteFavicon
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import app.focal.sync.SyncEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay

@Immutable
data class QuickSquirclePin(
    val id: String,
    val shortCode: String = "",
    val title: String,
    val url: String,
)

val DefaultSearchSquirclePins: ImmutableList<QuickSquirclePin> = persistentListOf(
    QuickSquirclePin("pin-imdb", "IM", "IMDb", "https://www.imdb.com"),
    QuickSquirclePin("pin-wiki", "WI", "Wikipedia", "https://en.wikipedia.org"),
    QuickSquirclePin("pin-gh", "GH", "GitHub", "https://github.com"),
    QuickSquirclePin("pin-ars", "AR", "Ars", "https://arstechnica.com"),
    QuickSquirclePin("pin-rd", "RD", "Reddit", "https://www.reddit.com"),
)

/**
 * Screen 02 · Concentric Smoked Glass Search Sheet anchored cleanly above `WindowInsets.ime`.
 *
 * - Zero permanent command/bang hint clutter in or above the search bar.
 * - Double-Space Site Search (`GitHub␣␣`, `GH␣␣`, `git␣␣`, `YouTube␣␣`, `YT␣␣`, `Wiki␣␣`, etc. +
 *   user pinned sites) with `135.dp` radial contextual glow clipped inside the input pill.
 * - Borderless tonal Favicon Squircles + crisp optical vector glyphs.
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
    animateEntrance: Boolean = true,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val detector = remember(pins) {
        DoubleSpaceSiteDetector().apply {
            for (pinEngine in DoubleSpaceSiteDetector.enginesFromPins(pins)) {
                registerShortcut(pinEngine, overwrite = false)
            }
        }
    }
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

    val inlineAnswer = remember(textFieldValue.text, activeSite) {
        if (activeSite != null) null else InlineFieldEvaluator.evaluate(textFieldValue.text)
    }
    var copiedAnswerText by remember(inlineAnswer?.copyValue) { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedAnswerText) {
        if (copiedAnswerText != null) {
            delay(1_500L)
            copiedAnswerText = null
        }
    }

    val windowInfo = LocalWindowInfo.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) {
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboardController?.show()
        }
    }

    val effectiveSheetTint = rememberUpdatedState(
        if (isPrivateAperture) colors.surfaceGlassPrivate else colors.surfaceGlassSheet,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = FocalGeometry.SheetOuterShape,
            glassTintState = effectiveSheetTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .navigationBarsPadding()
                .zeroJumpImePadding()
                .padding(bottom = 8.dp)
                .then(if (animateEntrance) Modifier.focalSheetEntrance(riseDp = 12.dp) else Modifier)
                .shadow(
                    elevation = 24.dp,
                    shape = FocalGeometry.SheetOuterShape,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Optional E2EE Desktop Continuity Handoff Banner
                if (continuityHandoff != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FocalGeometry.TileShape)
                            .background(colors.surfacePillSubtle)
                            .clickable {
                                onNavigateToUrl(continuityHandoff.url, isPrivateAperture)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SiteFavicon(
                            urlOrDomain = continuityHandoff.url,
                            allowNetworkFetch = !isPrivateAperture,
                            size = 22.dp,
                            cornerRadius = 6.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "From ${continuityHandoff.sourceDeviceName ?: "Desktop"}",
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
                        Icon(
                            imageVector = FocalGlyphs.ExternalArrow,
                            contentDescription = "Open",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }

                // 5 Borderless Tonal Squircle Favicon Tiles
                if (displayedPins.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        displayedPins.forEach { pin ->
                            FocalFaviconTile(
                                title = pin.title,
                                url = pin.url,
                                allowNetworkFetch = !isPrivateAperture,
                                onClick = { onNavigateToUrl(pin.url, isPrivateAperture) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Zero-Network Inline Answer Row (Calculator · Units · World Clock)
                AnimatedVisibility(
                    visible = inlineAnswer != null,
                    enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(150)) +
                        expandVertically(
                            expandFrom = Alignment.Bottom,
                            animationSpec = FocalMotion.smartPillSpring(),
                        ),
                    exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(110)) +
                        shrinkVertically(
                            shrinkTowards = Alignment.Bottom,
                            animationSpec = FocalMotion.smartPillSpring(),
                        ),
                ) {
                    val answer = inlineAnswer
                    if (answer != null) {
                        val isCopied = copiedAnswerText == answer.copyValue
                        val kindIcon = when (answer.kind) {
                            InlineFieldAnswer.Kind.MATH -> FocalGlyphs.Equals
                            InlineFieldAnswer.Kind.UNIT -> FocalGlyphs.Convert
                            InlineFieldAnswer.Kind.TIMEZONE -> FocalGlyphs.Clock
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(FocalGeometry.TileShape)
                                .background(colors.surfacePillSubtle)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    clipboard.setText(AnnotatedString(answer.copyValue))
                                    copiedAnswerText = answer.copyValue
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(colors.inkPrimary.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = kindIcon,
                                    contentDescription = null,
                                    tint = colors.inkPrimary.copy(alpha = 0.88f),
                                    modifier = Modifier.size(15.dp),
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                Text(
                                    text = answer.headlineAnswer,
                                    style = typography.sheetTitle,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = answer.subtitle,
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (isCopied) {
                                            colors.surfacePillActive
                                        } else {
                                            colors.inkPrimary.copy(alpha = 0.08f)
                                        },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (!isCopied) {
                                    Icon(
                                        imageVector = FocalGlyphs.Copy,
                                        contentDescription = "Copy answer",
                                        tint = colors.inkSecondary,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                Text(
                                    text = if (isCopied) "Copied" else "Copy",
                                    style = typography.controlPillLabel,
                                    color = if (isCopied) colors.inkOnPillActive else colors.inkPrimary,
                                )
                            }
                        }
                    }
                }

                // Quiet Private Aperture Status Indicator (only shown when Private mode is armed)
                if (isPrivateAperture) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Aperture,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "Private Aperture · Ephemeral Session",
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Concentric Main Search Input Pill (with strictly-clipped 135.dp left contextual site glow)
                val siteGlowColor = activeSite?.colors?.radialInputGlow ?: Color.Transparent
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(FocalGeometry.InputPillShape)
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
                        .padding(start = 13.dp, end = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    // Critically-damped Site Search Chip (`[GitHub ×]`) — crisp reveal without overshoot
                    AnimatedVisibility(
                        visible = activeSite != null,
                        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(140)) + scaleIn(
                            initialScale = 0.90f,
                            animationSpec = FocalMotion.siteChipSpring(),
                        ),
                        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(100)) + scaleOut(
                            targetScale = 0.92f,
                            animationSpec = FocalMotion.siteChipSpring(),
                        ),
                    ) {
                        val site = activeSite
                        if (site != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(site.colors.badgeBackground)
                                    .clickable {
                                        activeSite = null
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                SiteFavicon(
                                    urlOrDomain = site.homeUrl,
                                    allowNetworkFetch = !isPrivateAperture,
                                    size = 15.dp,
                                    cornerRadius = 4.dp,
                                )
                                Text(
                                    text = site.label,
                                    style = typography.controlPillLabel,
                                    color = site.colors.badgeForeground,
                                )
                                Icon(
                                    imageVector = FocalGlyphs.Close,
                                    contentDescription = "Remove ${site.label} filter",
                                    tint = site.colors.badgeForeground.copy(alpha = 0.72f),
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }

                    if (activeSite == null) {
                        Icon(
                            imageVector = FocalGlyphs.Search,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }

                    // Core Input Field with Backspace unwrap + Double-Space detection
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = activeSite?.placeholder ?: "Search or enter address",
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
                                keyboardType = KeyboardType.Text,
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

                    // Go Pill when input has text or a site chip is active
                    if (textFieldValue.text.isNotBlank() || activeSite != null) {
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
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Go",
                                style = typography.controlPillLabel,
                                color = colors.inkOnPillActive,
                            )
                        }
                    }

                    // Right-aligned 32.dp Private Aperture Toggle
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPrivateAperture) {
                                    colors.surfacePillActive
                                } else {
                                    colors.inkPrimary.copy(alpha = 0.08f)
                                },
                            )
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTogglePrivateAperture(!isPrivateAperture)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Aperture,
                            contentDescription = "Private Aperture",
                            tint = if (isPrivateAperture) colors.inkOnPillActive else colors.inkSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
