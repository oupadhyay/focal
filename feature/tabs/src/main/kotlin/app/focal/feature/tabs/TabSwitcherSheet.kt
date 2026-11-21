package app.focal.feature.tabs

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private data class ActiveTabDragState(
    val tab: TabMetadataEntity,
    val pointerInRoot: Offset,
    val hoveredSpaceId: String?,
)

private data class TabMovedBanner(
    val spaceId: String,
    val spaceName: String,
    val ambientHex: String?,
)

/**
 * Screen 06 · YAB-Style Stacked Card-Deck Tab Switcher with Top Space Groups.
 *
 * Layout:
 * - **Top Group Strip**: Horizontal capsule switcher for Space groups (`Personal`, `Work`,
 *   `Reading`, custom spaces) + `Private ◎` directly above the tab deck. Acts as magnetic
 *   drop targets when pressing and holding a tab card to move it between groups.
 * - **Center Stacked Deck**: Full-height portrait live preview cards using `HorizontalPager` +
 *   `Modifier.graphicsLayer` deck overlap (`zIndex`, scale, counter-translation), floating favicon
 *   + title header above each card's top edge, `×` close button, swipe-up-to-close, and
 *   press-and-hold drag-to-group.
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
    onMoveTabToSpace: (tabId: String, targetSpaceId: String) -> Unit = { _, _ -> },
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
    val canDragBetweenGroups = !isPrivateAperture && spaces.any { it.id != activeSpaceId }

    var sheetRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val spaceDropTargets = remember { mutableStateMapOf<String, Rect>() }
    var activeTabDrag by remember { mutableStateOf<ActiveTabDragState?>(null) }
    var movedBanner by remember { mutableStateOf<TabMovedBanner?>(null) }

    val dropSlopHorizontalPx = with(density) { 8.dp.toPx() }
    val dropSlopVerticalPx = with(density) { 22.dp.toPx() }
    val validDropTargetIds = remember(spaces, activeSpaceId, isPrivateAperture) {
        if (isPrivateAperture) {
            emptySet()
        } else {
            spaces.asSequence().map { it.id }.filter { it != activeSpaceId }.toSet()
        }
    }
    val latestValidDropTargetIds = rememberUpdatedState(validDropTargetIds)
    val latestSpaces = rememberUpdatedState(spaces)
    val latestOnMoveTabToSpace = rememberUpdatedState(onMoveTabToSpace)
    val latestIsDraggingTab = rememberUpdatedState(activeTabDrag != null)

    LaunchedEffect(activeSpaceId, isPrivateAperture) {
        activeTabDrag = null
    }

    LaunchedEffect(movedBanner) {
        if (movedBanner != null) {
            delay(2_400L)
            movedBanner = null
        }
    }

    val hoveredDropSpace = activeTabDrag?.hoveredSpaceId?.let { hoveredId ->
        spaces.firstOrNull { it.id == hoveredId }
    }
    val ambientSpec = if (isPrivateAperture) {
        SpaceAmbientPalette.None
    } else {
        val displaySpace = hoveredDropSpace ?: activeSpace
        SpaceAmbientPalette.fromKeyOrHex(
            keyOrId = displaySpace?.id ?: activeSpaceId,
            fallbackHex = displaySpace?.ambientHex,
        )
    }
    val animatedAmbient by animateColorAsState(
        targetValue = ambientSpec.ambientFor(colors.isDarkGlass),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
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
            .onGloballyPositioned { coords -> sheetRootCoords = coords }
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
            // 1. Top Space / Tab Group Selector Strip (also acts as drop targets while dragging)
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
                    isDraggingTab = activeTabDrag != null,
                    hoveredDropSpaceId = activeTabDrag?.hoveredSpaceId,
                    onSpacePillBoundsChanged = { spaceId, rectInRoot ->
                        spaceDropTargets[spaceId] = rectInRoot
                    },
                    onSelectSpace = { spaceId ->
                        movedBanner = null
                        onTogglePrivateMode(false)
                        onSelectSpace(spaceId)
                    },
                    onSelectPrivate = {
                        movedBanner = null
                        onTogglePrivateMode(true)
                    },
                )

                // Contextual sub-strip ONLY when Private has open tabs (Close All) or Tidied archive exists
                if (activeTabDrag == null && ((isPrivateAperture && privateTabs.isNotEmpty()) || (!isPrivateAperture && archivedTabs.isNotEmpty()))) {
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
                        userScrollEnabled = activeTabDrag == null,
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
                        val isThisTabBeingDragged = activeTabDrag?.tab?.id == tab.id

                        var swipeUpDragPx by remember(tab.id) { mutableFloatStateOf(0f) }
                        val settleAnim = remember(tab.id) { Animatable(0f) }
                        val effectiveDragY = (swipeUpDragPx + settleAnim.value).coerceAtMost(0f)
                        val dragDismissFraction = (-effectiveDragY / dismissThresholdPx).coerceIn(0f, 1.4f)
                        var cardCoords by remember(tab.id) { mutableStateOf<LayoutCoordinates?>(null) }

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
                                    val baseAlpha = (1f - (absOffset - 1.6f).coerceAtLeast(0f) * 0.65f)
                                        .coerceIn(0f, 1f) * (1f - dragDismissFraction * 0.55f).coerceIn(0.15f, 1f)
                                    alpha = if (isThisTabBeingDragged) baseAlpha * 0.24f else baseAlpha
                                }
                                .pointerInput(tab.id, isFrontCard) {
                                    if (!isFrontCard) return@pointerInput
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (latestIsDraggingTab.value) {
                                                swipeUpDragPx = 0f
                                                return@detectVerticalDragGestures
                                            }
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
                                            if (latestIsDraggingTab.value) return@detectVerticalDragGestures
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

                            // Tall Portrait Live Preview Card (Tap = open, Press-and-Hold = drag to Space group)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .then(if (isFrontCard) Modifier.testTag("front_tab_card") else Modifier)
                                    .onGloballyPositioned { coords -> cardCoords = coords }
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
                                        if (activeTabDrag != null) return@clickable
                                        if (isFrontCard) {
                                            onSelectTab(tab.id)
                                        } else {
                                            scope.launch {
                                                pagerState.animateScrollToPage(pageIndex)
                                            }
                                        }
                                    }
                                    .pointerInput(tab.id, isFrontCard, canDragBetweenGroups) {
                                        if (!isFrontCard || !canDragBetweenGroups) return@pointerInput
                                        detectTabLongPressDrag(
                                            longPressTimeoutMs = 260L,
                                            onDragStart = { localPos ->
                                                val coords = cardCoords?.takeIf { it.isAttached } ?: return@detectTabLongPressDrag
                                                val rootPos = coords.localToRoot(localPos)
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                swipeUpDragPx = 0f
                                                movedBanner = null
                                                val initialHover = resolveHoveredDropSpaceId(
                                                    pointerInRoot = rootPos,
                                                    dropTargets = spaceDropTargets,
                                                    validTargetIds = latestValidDropTargetIds.value,
                                                    slopHorizontalPx = dropSlopHorizontalPx,
                                                    slopVerticalPx = dropSlopVerticalPx,
                                                )
                                                activeTabDrag = ActiveTabDragState(
                                                    tab = tab,
                                                    pointerInRoot = rootPos,
                                                    hoveredSpaceId = initialHover,
                                                )
                                            },
                                            onDrag = { localPos ->
                                                val coords = cardCoords?.takeIf { it.isAttached } ?: return@detectTabLongPressDrag
                                                val rootPos = coords.localToRoot(localPos)
                                                val prevHover = activeTabDrag?.hoveredSpaceId
                                                val nextHover = resolveHoveredDropSpaceId(
                                                    pointerInRoot = rootPos,
                                                    dropTargets = spaceDropTargets,
                                                    validTargetIds = latestValidDropTargetIds.value,
                                                    slopHorizontalPx = dropSlopHorizontalPx,
                                                    slopVerticalPx = dropSlopVerticalPx,
                                                )
                                                if (nextHover != null && nextHover != prevHover) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                activeTabDrag = ActiveTabDragState(
                                                    tab = tab,
                                                    pointerInRoot = rootPos,
                                                    hoveredSpaceId = nextHover,
                                                )
                                            },
                                            onDragEnd = { localPos ->
                                                val coords = cardCoords?.takeIf { it.isAttached }
                                                val rootPos = coords?.localToRoot(localPos) ?: activeTabDrag?.pointerInRoot
                                                val targetSpaceId = rootPos?.let { pos ->
                                                    resolveHoveredDropSpaceId(
                                                        pointerInRoot = pos,
                                                        dropTargets = spaceDropTargets,
                                                        validTargetIds = latestValidDropTargetIds.value,
                                                        slopHorizontalPx = dropSlopHorizontalPx,
                                                        slopVerticalPx = dropSlopVerticalPx,
                                                    )
                                                } ?: activeTabDrag?.hoveredSpaceId
                                                activeTabDrag = null
                                                if (targetSpaceId != null) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    latestOnMoveTabToSpace.value(tab.id, targetSpaceId)
                                                    val destSpace = latestSpaces.value.firstOrNull { it.id == targetSpaceId }
                                                    if (destSpace != null) {
                                                        movedBanner = TabMovedBanner(
                                                            spaceId = destSpace.id,
                                                            spaceName = destSpace.name,
                                                            ambientHex = destSpace.ambientHex,
                                                        )
                                                    }
                                                }
                                            },
                                            onDragCancel = {
                                                activeTabDrag = null
                                            },
                                        )
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

        // 3b. Floating Zero-Layout-Shift Drag-to-Group Indicator Pill
        AnimatedVisibility(
            visible = activeTabDrag != null,
            enter = fadeIn(animationSpec = tween(140)),
            exit = fadeOut(animationSpec = tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .zIndex(240f),
        ) {
            val targetSpace = hoveredDropSpace
            val targetDotColor = targetSpace?.let {
                SpaceAmbientPalette.fromKeyOrHex(it.id, it.ambientHex).dotColor
            }
            SmokedGlassBox(
                shape = CircleShape,
                glassTintState = dockGlassTint,
                borderSpecular = colors.borderSpecular,
                modifier = Modifier.shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            if (targetSpace != null) colors.surfacePillActive else Color.Transparent,
                        )
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (targetDotColor != null) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(targetDotColor),
                        )
                    }
                    Text(
                        text = if (targetSpace != null) {
                            "Move to ${targetSpace.name}"
                        } else {
                            "Drag onto a group above to move"
                        },
                        style = typography.captionMetadata.copy(fontWeight = FontWeight.Medium),
                        color = if (targetSpace != null) colors.inkOnPillActive else colors.inkPrimary,
                    )
                }
            }
        }

        // 4. Floating Moved Confirmation Toast above the bottom dock (with 1-tap View jump)
        AnimatedVisibility(
            visible = movedBanner != null && activeTabDrag == null,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 86.dp)
                .zIndex(220f),
        ) {
            val banner = movedBanner
            if (banner != null) {
                val dotColor = SpaceAmbientPalette.fromKeyOrHex(banner.spaceId, banner.ambientHex).dotColor
                SmokedGlassBox(
                    shape = CircleShape,
                    glassTintState = dockGlassTint,
                    borderSpecular = colors.borderSpecular,
                    modifier = Modifier.shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.4f),
                        spotColor = Color.Black.copy(alpha = 0.5f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Text(
                            text = "Moved to ${banner.spaceName}",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillActive)
                                .clickable {
                                    movedBanner = null
                                    onTogglePrivateMode(false)
                                    onSelectSpace(banner.spaceId)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "View",
                                style = typography.captionMetadata.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.inkOnPillActive,
                            )
                        }
                    }
                }
            }
        }

        // 5. Floating Miniature Drag Ghost Card tracking the user's finger 1:1 across groups
        val currentDrag = activeTabDrag
        val rootBounds = sheetRootCoords?.takeIf { it.isAttached }?.boundsInRoot()
        if (currentDrag != null && rootBounds != null) {
            val ghostWidthDp = 136.dp
            val ghostHeightDp = 180.dp
            val ghostWidthPx = with(density) { ghostWidthDp.toPx() }
            val ghostHeightPx = with(density) { ghostHeightDp.toPx() }
            val ghostScale by animateFloatAsState(
                targetValue = if (currentDrag.hoveredSpaceId != null) 0.78f else 0.96f,
                animationSpec = FocalMotion.smartPillSpring(),
                label = "FocalDragGhostScale",
            )
            val localX = currentDrag.pointerInRoot.x - rootBounds.left
            val localY = currentDrag.pointerInRoot.y - rootBounds.top
            val ghostBitmap = tabPreviews[currentDrag.tab.id]
            val ghostShape = RoundedCornerShape(20.dp)
            val ghostBorderColor = hoveredDropSpace?.let {
                SpaceAmbientPalette.fromKeyOrHex(it.id, it.ambientHex).dotColor.copy(alpha = 0.85f)
            } ?: colors.inkPrimary.copy(alpha = 0.35f)

            val minXPx = with(density) { 12.dp.toPx() }
            val maxXPx = (rootBounds.width - ghostWidthPx - minXPx).coerceAtLeast(minXPx)
            val minYPx = with(density) { 96.dp.toPx() }
            val maxYPx = (rootBounds.height - ghostHeightPx - with(density) { 24.dp.toPx() }).coerceAtLeast(minYPx)
            val offsetXPx = (localX - ghostWidthPx * 0.5f).coerceIn(minXPx, maxXPx).roundToInt()
            val offsetYPx = (localY + with(density) { 26.dp.toPx() }).coerceIn(minYPx, maxYPx).roundToInt()

            Box(
                modifier = Modifier
                    .zIndex(300f)
                    .offset { IntOffset(offsetXPx, offsetYPx) }
                    .size(width = ghostWidthDp, height = ghostHeightDp)
                    .graphicsLayer {
                        scaleX = ghostScale
                        scaleY = ghostScale
                        alpha = 0.96f
                    }
                    .shadow(
                        elevation = 26.dp,
                        shape = ghostShape,
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.Black.copy(alpha = 0.6f),
                    )
                    .clip(ghostShape)
                    .background(cardGlassTint.value)
                    .border(
                        width = if (currentDrag.hoveredSpaceId != null) 1.5.dp else 1.dp,
                        color = ghostBorderColor,
                        shape = ghostShape,
                    ),
            ) {
                if (ghostBitmap != null && !ghostBitmap.isRecycled) {
                    val imageBitmap = remember(ghostBitmap) { ghostBitmap.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        SiteFavicon(
                            urlOrDomain = currentDrag.tab.url.ifBlank { currentDrag.tab.domain },
                            allowNetworkFetch = !isPrivateAperture,
                            size = 32.dp,
                            cornerRadius = 8.dp,
                        )
                    }
                }

                // Compact bottom label pill on the lifted ghost card
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                            ),
                        )
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    SiteFavicon(
                        urlOrDomain = currentDrag.tab.url.ifBlank { currentDrag.tab.domain },
                        allowNetworkFetch = !isPrivateAperture,
                        size = 13.dp,
                        cornerRadius = 4.dp,
                    )
                    Text(
                        text = currentDrag.tab.title.ifBlank { currentDrag.tab.domain.ifBlank { currentDrag.tab.url } },
                        style = typography.captionMetadata.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

internal fun resolveHoveredDropSpaceId(
    pointerInRoot: Offset,
    dropTargets: Map<String, Rect>,
    validTargetIds: Set<String>,
    slopHorizontalPx: Float,
    slopVerticalPx: Float,
): String? {
    var bestId: String? = null
    var bestDistX = Float.MAX_VALUE
    for (spaceId in validTargetIds) {
        val rect = dropTargets[spaceId] ?: continue
        val left = rect.left - slopHorizontalPx
        val right = rect.right + slopHorizontalPx
        // Extend upward to top of screen so dragging near status bar over a group still hits cleanly
        val top = (rect.top - slopVerticalPx).coerceAtMost(0f)
        val bottom = rect.bottom + slopVerticalPx
        if (pointerInRoot.x in left..right && pointerInRoot.y in top..bottom) {
            val centerDistX = (pointerInRoot.x - rect.center.x).absoluteValue
            if (centerDistX < bestDistX) {
                bestDistX = centerDistX
                bestId = spaceId
            }
        }
    }
    return bestId
}

private suspend fun PointerInputScope.detectTabLongPressDrag(
    longPressTimeoutMs: Long = 260L,
    onDragStart: (downLocalOffset: Offset) -> Unit,
    onDrag: (currentLocalOffset: Offset) -> Unit,
    onDragEnd: (finalLocalOffset: Offset) -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val touchSlop = viewConfiguration.touchSlop
        var lastPosition = down.position

        val cancelledBeforeLongPress = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed || change.isConsumed) {
                    return@withTimeoutOrNull true
                }
                if ((change.position - down.position).getDistance() > touchSlop) {
                    return@withTimeoutOrNull true
                }
                lastPosition = change.position
            }
        }
        if (cancelledBeforeLongPress != null) return@awaitEachGesture

        onDragStart(lastPosition)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: event.changes.firstOrNull()
                    ?: break
                lastPosition = change.position
                change.consume()
                if (!change.pressed) {
                    onDragEnd(lastPosition)
                    return@awaitEachGesture
                }
                onDrag(lastPosition)
            }
            onDragCancel()
        } catch (t: Throwable) {
            onDragCancel()
            throw t
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
    isDraggingTab: Boolean = false,
    hoveredDropSpaceId: String? = null,
    onSpacePillBoundsChanged: (spaceId: String, rectInRoot: Rect) -> Unit = { _, _ -> },
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
            val isDropCandidate = isDraggingTab && !selected
            val isDropHovered = isDraggingTab && hoveredDropSpaceId == space.id
            val isSourceReceded = isDraggingTab && selected && hoveredDropSpaceId != null
            val dotColor = SpaceAmbientPalette.fromKeyOrHex(space.id, space.ambientHex).dotColor
            val count = tabCountBySpace[space.id] ?: 0
            val pillScale by animateFloatAsState(
                targetValue = if (isDropHovered) 1.09f else 1f,
                animationSpec = FocalMotion.smartPillSpring(),
                label = "FocalSpacePillDropScale",
            )
            val pillBg = when {
                isDropHovered -> colors.surfacePillActive
                selected && !isSourceReceded -> colors.surfacePillActive
                else -> colors.surfacePillSubtle
            }
            val pillInk = if ((selected && !isSourceReceded) || isDropHovered) {
                colors.inkOnPillActive
            } else {
                colors.inkPrimary
            }
            val countInk = if ((selected && !isSourceReceded) || isDropHovered) {
                colors.inkOnPillActive.copy(alpha = 0.72f)
            } else {
                colors.inkSecondary
            }

            Row(
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        onSpacePillBoundsChanged(space.id, coords.boundsInRoot())
                    }
                    .graphicsLayer {
                        scaleX = pillScale
                        scaleY = pillScale
                        alpha = if (isSourceReceded) 0.58f else 1f
                    }
                    .clip(CircleShape)
                    .background(pillBg)
                    .then(
                        when {
                            isDropHovered -> Modifier.border(1.5.dp, dotColor.copy(alpha = 0.9f), CircleShape)
                            isDropCandidate -> Modifier.border(1.dp, dotColor.copy(alpha = 0.50f), CircleShape)
                            else -> Modifier
                        },
                    )
                    .clickable(enabled = !isDraggingTab) { onSelectSpace(space.id) }
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
                    color = pillInk,
                    maxLines = 1,
                )
                if (count > 0) {
                    Text(
                        text = count.toString(),
                        style = typography.captionMetadata,
                        color = countInk,
                    )
                }
            }
        }

        // Private Aperture ◎ group chip (dimmed & non-droppable while moving a normal tab)
        Row(
            modifier = Modifier
                .graphicsLayer {
                    alpha = if (isDraggingTab) 0.35f else 1f
                }
                .clip(CircleShape)
                .background(if (isPrivateAperture) colors.surfacePillActive else colors.surfacePillSubtle)
                .clickable(enabled = !isDraggingTab, onClick = onSelectPrivate)
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
