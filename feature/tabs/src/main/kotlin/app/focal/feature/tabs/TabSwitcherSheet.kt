package app.focal.feature.tabs

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.focal.database.SpaceEntity
import app.focal.database.TabMetadataEntity
import app.focal.design.FocalGlyphs
import app.focal.design.FocalGroupedCard
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.SiteFavicon
import app.focal.design.SpaceAmbientPalette
import app.focal.design.shaders.SmokedGlassBox
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Screen 06 · YAB-Style Stacked Card-Deck Tab Switcher with Top Space Groups.
 *
 * Layout:
 * - **Top Group Strip**: Horizontal capsule switcher for Space groups (`Personal`, `Work`,
 *   `Reading`, custom spaces) + `Private ◎` directly above the tab deck.
 * - **Center Stacked Deck**: Full-height portrait live preview cards using `HorizontalPager` +
 *   `Modifier.graphicsLayer` deck overlap (`zIndex`, scale, counter-translation), floating favicon
 *   + title header above each card's top edge, `×` close button, and swipe-up-to-close.
 * - **Bottom 3-Button YAB Dock**: Circular **Bookmarks** button (left), wide **`+`** New Tab
 *   capsule (center), and circular **`✓`** Done button (right).
 */
@Composable
fun TabSwitcherSheet(
    activeTabs: ImmutableList<TabMetadataEntity>,
    archivedTabs: ImmutableList<TabMetadataEntity>,
    selectedTabId: String?,
    spaces: ImmutableList<SpaceEntity> = persistentListOf(),
    activeSpaceId: String = "personal",
    tabCountBySpace: ImmutableMap<String, Int> = persistentMapOf(),
    tabPreviews: ImmutableMap<String, Bitmap> = persistentMapOf(),
    isPrivateAperture: Boolean = false,
    privateTabs: ImmutableList<TabMetadataEntity> = persistentListOf(),
    nowEpochMs: Long = System.currentTimeMillis(),
    onSelectSpace: (String) -> Unit = {},
    onTogglePrivateMode: (Boolean) -> Unit = {},
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllPrivateTabs: () -> Unit = {},
    onRestoreArchivedTab: (String) -> Unit,
    onOpenBookmarks: () -> Unit = {},
    onNewSearch: (isPrivate: Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val displayedTabs = if (isPrivateAperture) privateTabs else activeTabs
    val activeSpace = spaces.firstOrNull { it.id == activeSpaceId } ?: spaces.firstOrNull()
    val ambientSpec = if (isPrivateAperture) {
        SpaceAmbientPalette.None
    } else {
        SpaceAmbientPalette.fromKeyOrHex(
            keyOrId = activeSpace?.id ?: activeSpaceId,
            fallbackHex = activeSpace?.ambientHex,
        )
    }
    val animatedAmbient by animateColorAsState(
        targetValue = ambientSpec.ambientFor(colors.isDarkGlass),
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "FocalTabDeckAmbient",
    )
    val dockGlassTint = rememberUpdatedState(colors.effectiveBarGlass)
    val cardGlassTint = rememberUpdatedState(
        if (isPrivateAperture) colors.surfaceGlassPrivate else colors.effectiveSheetGlass,
    )

    var archiveExpanded by remember(activeSpaceId, isPrivateAperture) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvasBackground)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        animatedAmbient.copy(alpha = animatedAmbient.alpha * 0.85f),
                        Color.Transparent,
                        Color.Transparent,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 1. Top Space / Tab Group Selector Strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopTabGroupsStrip(
                    spaces = spaces,
                    activeSpaceId = activeSpaceId,
                    tabCountBySpace = tabCountBySpace,
                    isPrivateAperture = isPrivateAperture,
                    privateTabCount = privateTabs.size,
                    onSelectSpace = { spaceId ->
                        onTogglePrivateMode(false)
                        onSelectSpace(spaceId)
                    },
                    onSelectPrivate = {
                        onTogglePrivateMode(true)
                    },
                )

                // Contextual sub-strip ONLY when Private has open tabs (Close All) or Tidied archive exists
                if ((isPrivateAperture && privateTabs.isNotEmpty()) || (!isPrivateAperture && archivedTabs.isNotEmpty())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isPrivateAperture && archivedTabs.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(colors.surfacePillSubtle)
                                    .clickable { archiveExpanded = !archiveExpanded }
                                    .padding(horizontal = 11.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = FocalGlyphs.Clock,
                                    contentDescription = null,
                                    tint = colors.inkSecondary,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "Tidied (${archivedTabs.size})",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                )
                                Icon(
                                    imageVector = if (archiveExpanded) FocalGlyphs.ChevronDown else FocalGlyphs.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.inkFaint,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        } else {
                            Spacer(Modifier.size(1.dp))
                        }

                        if (isPrivateAperture && privateTabs.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(colors.surfacePillSubtle)
                                    .clickable(onClick = onCloseAllPrivateTabs)
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = "Close All",
                                    style = typography.captionMetadata.copy(fontWeight = FontWeight.Medium),
                                    color = colors.inkPrimary,
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = !isPrivateAperture && archiveExpanded && archivedTabs.isNotEmpty()) {
                        Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                            FocalGroupedCard {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    archivedTabs.take(6).forEach { archived ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { onRestoreArchivedTab(archived.id) }
                                                .padding(vertical = 4.dp, horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            SiteFavicon(
                                                urlOrDomain = archived.url.ifBlank { archived.domain },
                                                allowNetworkFetch = true,
                                                size = 16.dp,
                                                cornerRadius = 4.dp,
                                            )
                                            Text(
                                                text = "${archived.domain.ifBlank { archived.url }} — ${archived.title}",
                                                style = typography.captionMetadata,
                                                color = colors.inkSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                text = "Restore",
                                                style = typography.captionMetadata.copy(fontWeight = FontWeight.Medium),
                                                color = colors.inkPrimary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Center Full-Height YAB-Style Stacked Card Deck
            if (displayedTabs.isEmpty()) {
                val emptyGroupName = activeSpace?.name ?: "this group"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colors.surfacePillSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPrivateAperture) FocalGlyphs.Aperture else FocalGlyphs.Plus,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (isPrivateAperture) "No Private Tabs" else "No Tabs in $emptyGroupName",
                        style = typography.sheetTitle,
                        color = colors.inkPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isPrivateAperture) {
                            "Ephemeral tabs never touch history or disk"
                        } else {
                            "Tap + below to start browsing"
                        },
                        style = typography.captionMetadata,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                key(activeSpaceId, isPrivateAperture) {
                    val deckTabs = remember(displayedTabs) { displayedTabs.reversed() }
                    val initialIdx = deckTabs.indexOfFirst { it.id == selectedTabId }
                        .takeIf { it >= 0 }
                        ?: (deckTabs.size - 1).coerceAtLeast(0)
                    val pagerState = rememberPagerState(
                        initialPage = initialIdx.coerceIn(0, (deckTabs.size - 1).coerceAtLeast(0)),
                        pageCount = { deckTabs.size },
                    )
                    val dismissThresholdPx = with(density) { 112.dp.toPx() }

                    HorizontalPager(
                        state = pagerState,
                        key = { idx -> deckTabs.getOrNull(idx)?.id ?: idx },
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        pageSpacing = 0.dp,
                        beyondViewportPageCount = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 14.dp, bottom = 16.dp),
                    ) { pageIndex ->
                        val tab = deckTabs.getOrNull(pageIndex) ?: return@HorizontalPager
                        val pageOffset = (
                            (pagerState.currentPage - pageIndex) +
                                pagerState.currentPageOffsetFraction
                            ).coerceIn(-2.5f, 2.5f)
                        val absOffset = pageOffset.absoluteValue
                        val isFrontCard = absOffset < 0.35f

                        var swipeUpDragPx by remember(tab.id) { mutableFloatStateOf(0f) }
                        val settleAnim = remember(tab.id) { Animatable(0f) }
                        val effectiveDragY = (swipeUpDragPx + settleAnim.value).coerceAtMost(0f)
                        val dragDismissFraction = (-effectiveDragY / dismissThresholdPx).coerceIn(0f, 1.4f)

                        val previewBitmap = tabPreviews[tab.id]
                        val cardShape = RoundedCornerShape(26.dp)

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(100f - absOffset * 10f)
                                .graphicsLayer {
                                    val depthScale = (1f - absOffset * 0.065f).coerceIn(0.80f, 1f)
                                    // Pull neighboring cards tightly behind the front card like a physical YAB deck
                                    val deckOverlapShift = pageOffset * size.width * 0.905f
                                    translationX = deckOverlapShift
                                    translationY = (absOffset * 6.dp.toPx()) + effectiveDragY
                                    scaleX = depthScale * (1f - dragDismissFraction * 0.05f)
                                    scaleY = depthScale * (1f - dragDismissFraction * 0.05f)
                                    alpha = (1f - (absOffset - 1.6f).coerceAtLeast(0f) * 0.65f)
                                        .coerceIn(0f, 1f) * (1f - dragDismissFraction * 0.55f).coerceIn(0.15f, 1f)
                                }
                                .pointerInput(tab.id, isFrontCard) {
                                    if (!isFrontCard) return@pointerInput
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (-swipeUpDragPx >= dismissThresholdPx) {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                swipeUpDragPx = 0f
                                                onCloseTab(tab.id)
                                            } else {
                                                val startOffset = swipeUpDragPx
                                                swipeUpDragPx = 0f
                                                scope.launch {
                                                    settleAnim.snapTo(startOffset)
                                                    settleAnim.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = FocalMotion.tabScrubSnapSpring(),
                                                    )
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            swipeUpDragPx = 0f
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            val next = (swipeUpDragPx + dragAmount).coerceAtMost(0f)
                                            if (next < 0f || swipeUpDragPx < 0f) {
                                                change.consume()
                                                swipeUpDragPx = next
                                            }
                                        },
                                    )
                                },
                        ) {
                            // Floating Favicon + Title Header right above the card's top edge (YAB style)
                            val titleAlpha = (1f - absOffset * 2.2f).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(colors.surfaceGlassSheet)
                                        .border(0.5.dp, colors.borderSpecular, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SiteFavicon(
                                        urlOrDomain = tab.url.ifBlank { tab.domain },
                                        allowNetworkFetch = !isPrivateAperture,
                                        size = 16.dp,
                                        cornerRadius = 8.dp,
                                    )
                                }

                                Text(
                                    text = tab.title.ifBlank { tab.domain.ifBlank { tab.url } },
                                    style = typography.bodyCompact.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.5.sp,
                                    ),
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer { alpha = titleAlpha },
                                )

                                if (isFrontCard) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(colors.surfacePillSubtle)
                                            .graphicsLayer { alpha = titleAlpha }
                                            .clickable {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onCloseTab(tab.id)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = FocalGlyphs.Close,
                                            contentDescription = "Close tab",
                                            tint = colors.inkSecondary,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            }

                            // Tall Portrait Live Preview Card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .shadow(
                                        elevation = if (isFrontCard) 22.dp else 10.dp,
                                        shape = cardShape,
                                        ambientColor = Color.Black.copy(alpha = 0.45f),
                                        spotColor = Color.Black.copy(alpha = 0.55f),
                                    )
                                    .clip(cardShape)
                                    .background(cardGlassTint.value)
                                    .border(
                                        width = if (tab.id == selectedTabId && isFrontCard) 1.dp else 0.75.dp,
                                        color = if (tab.id == selectedTabId && isFrontCard) {
                                            colors.inkPrimary.copy(alpha = 0.28f)
                                        } else {
                                            colors.borderSpecular
                                        },
                                        shape = cardShape,
                                    )
                                    .clickable {
                                        if (isFrontCard) {
                                            onSelectTab(tab.id)
                                        } else {
                                            scope.launch {
                                                pagerState.animateScrollToPage(pageIndex)
                                            }
                                        }
                                    },
                            ) {
                                if (previewBitmap != null && !previewBitmap.isRecycled) {
                                    val imageBitmap = remember(previewBitmap) { previewBitmap.asImageBitmap() }
                                    Image(
                                        bitmap = imageBitmap,
                                        contentDescription = tab.title.ifBlank { tab.domain },
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    OpticalFallbackTabCard(
                                        tab = tab,
                                        nowEpochMs = nowEpochMs,
                                        isPrivate = isPrivateAperture,
                                    )
                                }

                                // Subtle depth darkening scrim on stacked background cards
                                val depthDim = (absOffset * 0.24f).coerceIn(0f, 0.46f)
                                if (depthDim > 0.01f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = depthDim)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Bottom YAB-Style 3-Button Floating Dock (Bookmarks · + New Tab · ✓ Done)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left Circular Pill: Bookmarks List
                SmokedGlassBox(
                    shape = CircleShape,
                    glassTintState = dockGlassTint,
                    borderSpecular = colors.borderSpecular,
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = 0.35f),
                            spotColor = Color.Black.copy(alpha = 0.45f),
                        )
                        .clickable(onClick = onOpenBookmarks),
                ) {
                    Icon(
                        imageVector = FocalGlyphs.Bookmark,
                        contentDescription = "Bookmarks",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Center Wide Capsule Pill: + New Tab
                SmokedGlassBox(
                    shape = CircleShape,
                    glassTintState = dockGlassTint,
                    borderSpecular = colors.borderSpecular,
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(118.dp)
                        .height(52.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = 0.35f),
                            spotColor = Color.Black.copy(alpha = 0.45f),
                        )
                        .clickable { onNewSearch(isPrivateAperture) },
                ) {
                    Icon(
                        imageVector = FocalGlyphs.Plus,
                        contentDescription = if (isPrivateAperture) "New Private Tab" else "New Tab",
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }

                // Right Circular Primary Pill: ✓ Done
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = 0.35f),
                            spotColor = Color.Black.copy(alpha = 0.45f),
                        )
                        .clip(CircleShape)
                        .background(colors.surfacePillActive)
                        .clickable(onClick = onDone),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FocalGlyphs.Check,
                        contentDescription = "Done",
                        tint = colors.inkOnPillActive,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopTabGroupsStrip(
    spaces: ImmutableList<SpaceEntity>,
    activeSpaceId: String,
    tabCountBySpace: ImmutableMap<String, Int>,
    isPrivateAperture: Boolean,
    privateTabCount: Int,
    onSelectSpace: (String) -> Unit,
    onSelectPrivate: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        spaces.forEach { space ->
            val selected = !isPrivateAperture && space.id == activeSpaceId
            val dotColor = SpaceAmbientPalette.fromKeyOrHex(space.id, space.ambientHex).dotColor
            val count = tabCountBySpace[space.id] ?: 0
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) colors.surfacePillActive else colors.surfacePillSubtle)
                    .clickable { onSelectSpace(space.id) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = space.name,
                    style = typography.controlPillLabel,
                    color = if (selected) colors.inkOnPillActive else colors.inkPrimary,
                    maxLines = 1,
                )
                if (count > 0) {
                    Text(
                        text = count.toString(),
                        style = typography.captionMetadata,
                        color = if (selected) {
                            colors.inkOnPillActive.copy(alpha = 0.72f)
                        } else {
                            colors.inkSecondary
                        },
                    )
                }
            }
        }

        // Private Aperture ◎ group chip
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (isPrivateAperture) colors.surfacePillActive else colors.surfacePillSubtle)
                .clickable(onClick = onSelectPrivate)
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = FocalGlyphs.Aperture,
                contentDescription = null,
                tint = if (isPrivateAperture) colors.inkOnPillActive else colors.inkSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Private",
                style = typography.controlPillLabel,
                color = if (isPrivateAperture) colors.inkOnPillActive else colors.inkPrimary,
                maxLines = 1,
            )
            if (privateTabCount > 0) {
                Text(
                    text = privateTabCount.toString(),
                    style = typography.captionMetadata,
                    color = if (isPrivateAperture) {
                        colors.inkOnPillActive.copy(alpha = 0.72f)
                    } else {
                        colors.inkSecondary
                    },
                )
            }
        }
    }
}

@Composable
private fun OpticalFallbackTabCard(
    tab: TabMetadataEntity,
    nowEpochMs: Long,
    isPrivate: Boolean,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfacePillSubtle),
                contentAlignment = Alignment.Center,
            ) {
                SiteFavicon(
                    urlOrDomain = tab.url.ifBlank { tab.domain },
                    allowNetworkFetch = !isPrivate,
                    size = 30.dp,
                    cornerRadius = 8.dp,
                )
            }
            Text(
                text = tab.title.ifBlank { tab.domain.ifBlank { tab.url } },
                style = typography.sheetTitle,
                color = colors.inkPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = tab.domain.ifBlank { tab.url },
                style = typography.captionMetadata,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.blockedTrackersCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = FocalGlyphs.Shield,
                        contentDescription = null,
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "${tab.blockedTrackersCount} blocked",
                        style = typography.captionMetadata,
                        color = colors.inkSecondary,
                    )
                }
            } else {
                Spacer(Modifier.size(1.dp))
            }

            TabTtlOrDraftBadge(
                tab = tab,
                nowEpochMs = nowEpochMs,
                isPrivate = isPrivate,
            )
        }
    }
}

@Composable
private fun TabTtlOrDraftBadge(
    tab: TabMetadataEntity,
    nowEpochMs: Long,
    isPrivate: Boolean = false,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography

    val icon = when {
        isPrivate -> FocalGlyphs.Aperture
        tab.hasUnsentFormDraft -> FocalGlyphs.Lock
        else -> FocalGlyphs.Clock
    }
    val label = when {
        isPrivate -> "Ephemeral"
        tab.hasUnsentFormDraft -> "Draft"
        else -> {
            val remHours = (tab.remainingTtlMs(nowEpochMs) / (60L * 60L * 1000L)).coerceIn(1L, 24L)
            "${remHours}h"
        }
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surfacePillSubtle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.inkSecondary,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = label,
            style = typography.captionMetadata,
            color = colors.inkSecondary,
        )
    }
}
