package app.focal.feature.tabs

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

private data class ActiveTabDragState(
    val tab: TabMetadataEntity,
    val hoveredSpaceId: String?,
)

private data class TabMovedBanner(
    val spaceId: String,
    val spaceName: String,
    val ambientHex: String?,
)

private enum class DeckDragMode {
    UNDECIDED,
    HORIZONTAL_SCRUB,
    VERTICAL_CLOSE,
}

private class DeckInteractionState {
    var cardWidthPx: Float = 0f
    var isGestureActive: Boolean = false
    var suppressNextCardClick: Boolean = false
    var activeDeckAnimJob: Job? = null
    var activeLiftAnimJob: Job? = null
}

internal data class TabDeckCardVisuals(
    val translationX: Float,
    val translationY: Float,
    val scale: Float,
    val rotationY: Float,
    val veilAlpha: Float,
    val alpha: Float,
    val titleAlpha: Float,
    val zIndex: Float,
)

/**
 * Pure JVM physics & continuous depth-curve engine for the 120Hz stacked tab card deck.
 *
 * Drives multi-tab momentum flings (no 1-tab clamp), smooth C1 rubber-band edge resistance,
 * and continuous GPU-only depth transforms (`translationX/Y`, `scale`, `rotationY`, `veilAlpha`,
 * `alpha`) without triggering recompositions.
 */
internal object TabDeckPhysics {
    const val MAX_EDGE_OVERSHOOT_CARDS: Float = 0.38f
    const val MOMENTUM_PROJECTION_SEC: Float = 0.28f
    const val FLICK_VELOCITY_THRESHOLD_FOCUS_PER_SEC: Float = 0.8f
    const val SNAP_BACK_DISPLACEMENT_THRESHOLD: Float = 0.25f
    const val MAX_ROTATION_Y_DEGREES: Float = 3.5f

    fun rubberBand(overshoot: Float, maxRange: Float): Float {
        if (maxRange <= 0f || overshoot == 0f) return 0f
        val absOvershoot = overshoot.absoluteValue
        val resisted = maxRange * (1f - 1f / (1f + absOvershoot / maxRange))
        return if (overshoot > 0f) resisted else -resisted
    }

    fun resistedFocus(rawFocus: Float, maxFocus: Float, stepPx: Float): Float {
        val clampedMax = maxFocus.coerceAtLeast(0f)
        val safeStep = stepPx.coerceAtLeast(1f)
        val maxOvershootPx = safeStep * MAX_EDGE_OVERSHOOT_CARDS
        return when {
            rawFocus < 0f -> rubberBand(rawFocus * safeStep, maxOvershootPx) / safeStep
            rawFocus > clampedMax -> clampedMax + rubberBand((rawFocus - clampedMax) * safeStep, maxOvershootPx) / safeStep
            else -> rawFocus
        }
    }

    fun projectFlingTargetFocus(
        anchorFocus: Float,
        currentFocus: Float,
        velocityXpxPerSec: Float,
        stepPx: Float,
        maxFocus: Float,
    ): Int {
        val maxIndex = maxFocus.toInt().coerceAtLeast(0)
        if (maxIndex == 0) return 0

        val safeStep = stepPx.coerceAtLeast(1f)
        val anchorIndex = anchorFocus.roundToInt().coerceIn(0, maxIndex)
        val velocityFocusPerSec = -velocityXpxPerSec / safeStep
        val absVelFocus = velocityFocusPerSec.absoluteValue

        if (absVelFocus >= FLICK_VELOCITY_THRESHOLD_FOCUS_PER_SEC) {
            val projected = currentFocus + velocityFocusPerSec * MOMENTUM_PROJECTION_SEC
            val committed = if (velocityFocusPerSec > 0f) {
                val rounded = floor(projected + 0.5f).toInt()
                val minForward = floor(currentFocus).toInt() + 1
                maxOf(rounded, minForward)
            } else {
                val rounded = ceil(projected - 0.5f).toInt()
                val minBackward = ceil(currentFocus).toInt() - 1
                minOf(rounded, minBackward)
            }
            return committed.coerceIn(0, maxIndex)
        }

        val displacement = currentFocus - anchorFocus
        if (displacement.absoluteValue < SNAP_BACK_DISPLACEMENT_THRESHOLD) {
            return anchorIndex
        }

        val nearest = currentFocus.roundToInt()
        val resolved = if (nearest == anchorIndex) {
            anchorIndex + if (displacement > 0f) 1 else -1
        } else {
            nearest
        }
        return resolved.coerceIn(0, maxIndex)
    }

    fun computeCardVisuals(
        depth: Float,
        cardWidthPx: Float,
        density: Float,
    ): TabDeckCardVisuals {
        val clampedDepth = depth.coerceIn(-3.6f, 3.6f)
        val absDepth = clampedDepth.absoluteValue
        val safeWidth = cardWidthPx.coerceAtLeast(0f)
        val safeDensity = density.coerceAtLeast(0.5f)

        val translationX = if (clampedDepth == 0f || safeWidth == 0f) {
            0f
        } else {
            -safeWidth * (0.20f * clampedDepth) / (1f + 0.38f * absDepth)
        }
        val translationY = absDepth * 7f * safeDensity
        val scale = (1f / (1f + absDepth * 0.082f)).coerceIn(0.76f, 1f)
        val rotationY = if (clampedDepth == 0f) {
            0f
        } else {
            (-MAX_ROTATION_Y_DEGREES * (clampedDepth / (1f + 0.65f * absDepth)))
                .coerceIn(-MAX_ROTATION_Y_DEGREES, MAX_ROTATION_Y_DEGREES)
        }
        val veilAlpha = (absDepth * 0.24f).coerceIn(0f, 0.54f)
        val alpha = (1f - (absDepth - 2.2f).coerceAtLeast(0f)).coerceIn(0f, 1f)
        val titleAlpha = titleAlphaForDepth(clampedDepth)
        val zIndex = 100f - absDepth * 10f

        return TabDeckCardVisuals(
            translationX = translationX,
            translationY = translationY,
            scale = scale,
            rotationY = rotationY,
            veilAlpha = veilAlpha,
            alpha = alpha,
            titleAlpha = titleAlpha,
            zIndex = zIndex,
        )
    }

    fun titleAlphaForDepth(depth: Float): Float =
        (1f - depth.absoluteValue * 2.2f).coerceIn(0f, 1f)
}

/**
 * Screen 06 · YAB-Style Stacked Card-Deck Tab Switcher with Top Space Groups.
 *
 * Layout:
 * - **Top Group Strip**: Horizontal capsule switcher for Space groups (`Personal`, `Work`,
 *   `Reading`, custom spaces) + `Private ◎` directly above the tab deck. Acts as magnetic
 *   drop targets when pressing and holding a tab card to move it between groups.
 * - **Center Stacked Deck**: Full-height portrait live preview cards driven by a continuous-`focus`
 *   120Hz GPU-only physics card deck ([TabDeckPhysics]) with multi-tab momentum flings, floating
 *   favicon + title header above each card's top edge, `×` close button, swipe-up-to-close, and
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
    nowEpochMs: Long = -1L,
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
    lastClosedTabTitle: String? = null,
    onUndoCloseTab: (() -> Unit)? = null,
    onDismissUndoCloseTab: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val effectiveNowEpochMs = remember(nowEpochMs) {
        if (nowEpochMs > 0L) nowEpochMs else System.currentTimeMillis()
    }

    val displayedTabs = if (isPrivateAperture) privateTabs else activeTabs
    val activeSpace = spaces.firstOrNull { it.id == activeSpaceId } ?: spaces.firstOrNull()
    val canDragBetweenGroups = !isPrivateAperture && spaces.any { it.id != activeSpaceId }

    var sheetRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val spaceDropTargets = remember { mutableStateMapOf<String, Rect>() }
    val dragPointerInRootState = remember { mutableStateOf(Offset.Zero) }
    var activeTabDrag by remember { mutableStateOf<ActiveTabDragState?>(null) }
    var movedBanner by remember { mutableStateOf<TabMovedBanner?>(null) }
    var displayedClosedTabTitle by remember { mutableStateOf(lastClosedTabTitle) }
    if (lastClosedTabTitle != null) {
        displayedClosedTabTitle = lastClosedTabTitle
    }

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
    val latestOnCloseTab = rememberUpdatedState(onCloseTab)
    val latestIsDraggingTab = rememberUpdatedState(activeTabDrag != null)
    val latestOnDismissUndoCloseTab = rememberUpdatedState(onDismissUndoCloseTab)

    LaunchedEffect(activeSpaceId, isPrivateAperture) {
        activeTabDrag = null
    }

    LaunchedEffect(movedBanner) {
        if (movedBanner != null) {
            delay(2_400L)
            movedBanner = null
        }
    }

    LaunchedEffect(lastClosedTabTitle) {
        if (lastClosedTabTitle != null) {
            delay(4_500L)
            latestOnDismissUndoCloseTab.value()
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
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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

                    AnimatedVisibility(
                        visible = !isPrivateAperture && archiveExpanded && archivedTabs.isNotEmpty(),
                        enter = fadeIn(animationSpec = tween(150)) +
                            expandVertically(
                                animationSpec = FocalMotion.menuSubtabSpring(),
                            ),
                        exit = fadeOut(animationSpec = tween(115)) +
                            shrinkVertically(
                                animationSpec = FocalMotion.menuSubtabSpring(),
                            ),
                    ) {
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
                    val initialIdx = remember(activeSpaceId, isPrivateAperture) {
                        val match = deckTabs.indexOfFirst { it.id == selectedTabId }
                        if (match >= 0) match else (deckTabs.size - 1).coerceAtLeast(0)
                    }
                    val focusAnim = remember(activeSpaceId, isPrivateAperture) {
                        Animatable(initialIdx.toFloat())
                    }
                    val swipeUpLiftAnim = remember(activeSpaceId, isPrivateAperture) {
                        Animatable(0f)
                    }
                    val swipeUpTargetTabIdState = remember(activeSpaceId, isPrivateAperture) {
                        mutableStateOf<String?>(null)
                    }
                    val interactionState = remember(activeSpaceId, isPrivateAperture) {
                        DeckInteractionState()
                    }
                    val latestDeckTabs = rememberUpdatedState(deckTabs)
                    val dismissThresholdPx = with(density) { 112.dp.toPx() }
                    val fallbackStepPx = with(density) { 180.dp.toPx() }
                    val cardHorizontalPaddingPx = with(density) { 48.dp.roundToPx() }

                    // Sync focus smoothly if deck shrinks/reorders without fighting active drags
                    LaunchedEffect(deckTabs.size) {
                        val maxFocus = (deckTabs.size - 1).coerceAtLeast(0).toFloat()
                        val clamped = focusAnim.value.coerceIn(0f, maxFocus)
                        if (!interactionState.isGestureActive && clamped != focusAnim.value) {
                            interactionState.activeDeckAnimJob?.cancel()
                            interactionState.activeDeckAnimJob = scope.launch {
                                focusAnim.animateTo(
                                    targetValue = clamped,
                                    animationSpec = spring(
                                        stiffness = 380f,
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                    ),
                                )
                            }
                        }
                    }

                    // Crisp mechanical haptic detents as cards glide past center during drags & flings
                    LaunchedEffect(activeSpaceId, isPrivateAperture, deckTabs.size) {
                        val maxIdx = (deckTabs.size - 1).coerceAtLeast(0)
                        snapshotFlow { focusAnim.value.roundToInt().coerceIn(0, maxIdx) }
                            .distinctUntilChanged()
                            .drop(1)
                            .collect {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                    }

                    val frontCardIndex by remember(deckTabs.size) {
                        derivedStateOf {
                            focusAnim.value.roundToInt().coerceIn(0, (deckTabs.size - 1).coerceAtLeast(0))
                        }
                    }
                    val minVisibleIndex = (frontCardIndex - 3).coerceAtLeast(0)
                    val maxVisibleIndex = (frontCardIndex + 3).coerceAtMost(deckTabs.lastIndex)
                    val focusProvider = remember(focusAnim) { { focusAnim.value } }
                    val swipeUpLiftProvider = remember(swipeUpLiftAnim, swipeUpTargetTabIdState) {
                        { tabId: String ->
                            if (swipeUpTargetTabIdState.value == tabId) {
                                swipeUpLiftAnim.value.coerceAtMost(0f)
                            } else {
                                0f
                            }
                        }
                    }

                    Layout(
                        content = {
                            for (pageIndex in minVisibleIndex..maxVisibleIndex) {
                                val tab = deckTabs[pageIndex]
                                key(tab.id) {
                                    TabDeckCard(
                                        tab = tab,
                                        pageIndex = pageIndex,
                                        isFrontCard = pageIndex == frontCardIndex,
                                        isSelectedTab = tab.id == selectedTabId,
                                        isPrivateAperture = isPrivateAperture,
                                        canDragBetweenGroups = canDragBetweenGroups,
                                        isThisTabBeingDragged = activeTabDrag?.tab?.id == tab.id,
                                        previewBitmap = tabPreviews[tab.id],
                                        cardGlassTint = cardGlassTint,
                                        effectiveNowEpochMs = effectiveNowEpochMs,
                                        dismissThresholdPx = dismissThresholdPx,
                                        focusProvider = focusProvider,
                                        swipeUpLiftProvider = swipeUpLiftProvider,
                                        onTapCard = {
                                            if (interactionState.suppressNextCardClick) {
                                                interactionState.suppressNextCardClick = false
                                            } else if (activeTabDrag == null) {
                                                if (pageIndex == frontCardIndex) {
                                                    onSelectTab(tab.id)
                                                } else {
                                                    interactionState.activeDeckAnimJob?.cancel()
                                                    interactionState.activeDeckAnimJob = scope.launch {
                                                        focusAnim.animateTo(
                                                            targetValue = pageIndex.toFloat(),
                                                            animationSpec = spring(
                                                                stiffness = 380f,
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onCloseTab = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onCloseTab(tab.id)
                                        },
                                        onLongPressDragStart = { rootPos ->
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            interactionState.activeLiftAnimJob?.cancel()
                                            swipeUpTargetTabIdState.value = null
                                            interactionState.activeLiftAnimJob = scope.launch(
                                                start = CoroutineStart.UNDISPATCHED,
                                            ) {
                                                swipeUpLiftAnim.snapTo(0f)
                                            }
                                            movedBanner = null
                                            dragPointerInRootState.value = rootPos
                                            val initialHover = resolveHoveredDropSpaceId(
                                                pointerInRoot = rootPos,
                                                dropTargets = spaceDropTargets,
                                                validTargetIds = latestValidDropTargetIds.value,
                                                slopHorizontalPx = dropSlopHorizontalPx,
                                                slopVerticalPx = dropSlopVerticalPx,
                                            )
                                            activeTabDrag = ActiveTabDragState(
                                                tab = tab,
                                                hoveredSpaceId = initialHover,
                                            )
                                        },
                                        onLongPressDrag = { rootPos ->
                                            dragPointerInRootState.value = rootPos
                                            val current = activeTabDrag
                                            if (current != null) {
                                                val nextHover = resolveHoveredDropSpaceId(
                                                    pointerInRoot = rootPos,
                                                    dropTargets = spaceDropTargets,
                                                    validTargetIds = latestValidDropTargetIds.value,
                                                    slopHorizontalPx = dropSlopHorizontalPx,
                                                    slopVerticalPx = dropSlopVerticalPx,
                                                )
                                                if (nextHover != current.hoveredSpaceId) {
                                                    if (nextHover != null) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                    activeTabDrag = current.copy(hoveredSpaceId = nextHover)
                                                }
                                            }
                                        },
                                        onLongPressDragEnd = { rootPos ->
                                            val finalPos = rootPos ?: dragPointerInRootState.value
                                            val targetSpaceId = resolveHoveredDropSpaceId(
                                                pointerInRoot = finalPos,
                                                dropTargets = spaceDropTargets,
                                                validTargetIds = latestValidDropTargetIds.value,
                                                slopHorizontalPx = dropSlopHorizontalPx,
                                                slopVerticalPx = dropSlopVerticalPx,
                                            ) ?: activeTabDrag?.hoveredSpaceId
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
                                        onLongPressDragCancel = {
                                            activeTabDrag = null
                                        },
                                        modifier = Modifier.layoutId(pageIndex),
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 14.dp, bottom = 16.dp)
                            .onSizeChanged { size ->
                                interactionState.cardWidthPx = (size.width - cardHorizontalPaddingPx * 2)
                                    .coerceAtLeast(1)
                                    .toFloat()
                            }
                            .pointerInput(activeSpaceId, isPrivateAperture) {
                                val velocityTracker = VelocityTracker()
                                val touchSlop = viewConfiguration.touchSlop
                                fun resolveStepPx(): Float =
                                    (interactionState.cardWidthPx * 0.72f)
                                        .takeIf { it > 1f }
                                        ?: fallbackStepPx

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (latestIsDraggingTab.value) return@awaitEachGesture
                                    val currentDeckTabs = latestDeckTabs.value
                                    if (currentDeckTabs.isEmpty()) return@awaitEachGesture

                                    val wasActivelyFlinging = focusAnim.isRunning &&
                                        (focusAnim.value - focusAnim.targetValue).absoluteValue > 0.15f
                                    interactionState.suppressNextCardClick = wasActivelyFlinging
                                    if (focusAnim.isRunning) {
                                        interactionState.activeDeckAnimJob?.cancel()
                                        interactionState.activeDeckAnimJob = scope.launch(
                                            start = CoroutineStart.UNDISPATCHED,
                                        ) {
                                            focusAnim.stop()
                                        }
                                    }

                                    velocityTracker.resetTracking()
                                    velocityTracker.addPointerInputChange(down)

                                    val anchorFocus = focusAnim.value
                                    val anchorIndex = anchorFocus.roundToInt()
                                        .coerceIn(0, (currentDeckTabs.size - 1).coerceAtLeast(0))
                                    val anchorTabId = currentDeckTabs.getOrNull(anchorIndex)?.id
                                    var totalDx = 0f
                                    var totalDy = 0f
                                    var dragMode = DeckDragMode.UNDECIDED
                                    var rawFocus = anchorFocus
                                    var swipeUpLiftPx = 0f
                                    var gestureCancelled = false

                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Main)
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: event.changes.firstOrNull()
                                                ?: break

                                            // Yield immediately when long-press drag-to-group takes ownership
                                            if (latestIsDraggingTab.value ||
                                                (dragMode == DeckDragMode.UNDECIDED && change.isConsumed)
                                            ) {
                                                gestureCancelled = true
                                                break
                                            }

                                            if (!change.pressed) {
                                                velocityTracker.addPointerInputChange(change)
                                                if (dragMode != DeckDragMode.UNDECIDED || wasActivelyFlinging) {
                                                    change.consume()
                                                }
                                                break
                                            }

                                            val delta = change.position - change.previousPosition
                                            totalDx += delta.x
                                            totalDy += delta.y
                                            velocityTracker.addPointerInputChange(change)

                                            if (dragMode == DeckDragMode.UNDECIDED) {
                                                val absDx = totalDx.absoluteValue
                                                val absDy = totalDy.absoluteValue
                                                if (absDx > touchSlop || absDy > touchSlop) {
                                                    if (absDx >= absDy * 0.9f) {
                                                        dragMode = DeckDragMode.HORIZONTAL_SCRUB
                                                        interactionState.isGestureActive = true
                                                        interactionState.suppressNextCardClick = false
                                                        val stepPx = resolveStepPx()
                                                        val maxFocus = (latestDeckTabs.value.size - 1)
                                                            .coerceAtLeast(0)
                                                            .toFloat()
                                                        val slopAdjustedDx = totalDx - sign(totalDx) * touchSlop
                                                        rawFocus = anchorFocus - slopAdjustedDx / stepPx
                                                        val resisted = TabDeckPhysics.resistedFocus(
                                                            rawFocus = rawFocus,
                                                            maxFocus = maxFocus,
                                                            stepPx = stepPx,
                                                        )
                                                        interactionState.activeDeckAnimJob?.cancel()
                                                        interactionState.activeDeckAnimJob = scope.launch(
                                                            start = CoroutineStart.UNDISPATCHED,
                                                        ) {
                                                            focusAnim.snapTo(resisted)
                                                        }
                                                        change.consume()
                                                    } else if (totalDy < -touchSlop && anchorTabId != null) {
                                                        dragMode = DeckDragMode.VERTICAL_CLOSE
                                                        interactionState.isGestureActive = true
                                                        interactionState.suppressNextCardClick = false
                                                        swipeUpTargetTabIdState.value = anchorTabId
                                                        swipeUpLiftPx = (totalDy + touchSlop).coerceAtMost(0f)
                                                        interactionState.activeLiftAnimJob?.cancel()
                                                        interactionState.activeLiftAnimJob = scope.launch(
                                                            start = CoroutineStart.UNDISPATCHED,
                                                        ) {
                                                            swipeUpLiftAnim.snapTo(swipeUpLiftPx)
                                                        }
                                                        change.consume()
                                                    } else if (totalDy > touchSlop) {
                                                        gestureCancelled = true
                                                        break
                                                    }
                                                }
                                            } else if (dragMode == DeckDragMode.HORIZONTAL_SCRUB) {
                                                change.consume()
                                                val stepPx = resolveStepPx()
                                                val maxFocus = (latestDeckTabs.value.size - 1)
                                                    .coerceAtLeast(0)
                                                    .toFloat()
                                                rawFocus -= delta.x / stepPx
                                                val resisted = TabDeckPhysics.resistedFocus(
                                                    rawFocus = rawFocus,
                                                    maxFocus = maxFocus,
                                                    stepPx = stepPx,
                                                )
                                                interactionState.activeDeckAnimJob?.cancel()
                                                interactionState.activeDeckAnimJob = scope.launch(
                                                    start = CoroutineStart.UNDISPATCHED,
                                                ) {
                                                    focusAnim.snapTo(resisted)
                                                }
                                            } else if (dragMode == DeckDragMode.VERTICAL_CLOSE) {
                                                val nextLift = (swipeUpLiftPx + delta.y).coerceAtMost(0f)
                                                if (nextLift < 0f || swipeUpLiftPx < 0f) {
                                                    change.consume()
                                                    swipeUpLiftPx = nextLift
                                                    interactionState.activeLiftAnimJob?.cancel()
                                                    interactionState.activeLiftAnimJob = scope.launch(
                                                        start = CoroutineStart.UNDISPATCHED,
                                                    ) {
                                                        swipeUpLiftAnim.snapTo(swipeUpLiftPx)
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        interactionState.isGestureActive = false
                                    }

                                    when (dragMode) {
                                        DeckDragMode.HORIZONTAL_SCRUB -> {
                                            val stepPx = resolveStepPx()
                                            val maxFocus = (latestDeckTabs.value.size - 1)
                                                .coerceAtLeast(0)
                                                .toFloat()
                                            val vx = if (gestureCancelled) {
                                                0f
                                            } else {
                                                velocityTracker.calculateVelocity().x
                                            }
                                            val targetIndex = TabDeckPhysics.projectFlingTargetFocus(
                                                anchorFocus = anchorFocus,
                                                currentFocus = focusAnim.value,
                                                velocityXpxPerSec = vx,
                                                stepPx = stepPx,
                                                maxFocus = maxFocus,
                                            )
                                            val rawVelFocus = (-vx / stepPx).coerceIn(-24f, 24f)
                                            val atLowerEdgePushingOut =
                                                targetIndex == 0 && focusAnim.value <= 0f && rawVelFocus < 0f
                                            val atUpperEdgePushingOut =
                                                targetIndex == maxFocus.toInt() &&
                                                    focusAnim.value >= maxFocus &&
                                                    rawVelFocus > 0f
                                            val initialVelocityFocus = if (
                                                atLowerEdgePushingOut || atUpperEdgePushingOut
                                            ) {
                                                0f
                                            } else {
                                                rawVelFocus
                                            }
                                            interactionState.activeDeckAnimJob?.cancel()
                                            interactionState.activeDeckAnimJob = scope.launch {
                                                focusAnim.animateTo(
                                                    targetValue = targetIndex.toFloat(),
                                                    initialVelocity = initialVelocityFocus,
                                                    animationSpec = spring(
                                                        stiffness = 340f,
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                    ),
                                                )
                                            }
                                        }
                                        DeckDragMode.VERTICAL_CLOSE -> {
                                            val vy = if (gestureCancelled) {
                                                0f
                                            } else {
                                                velocityTracker.calculateVelocity().y
                                            }
                                            val projectedUpwardPx = -(swipeUpLiftPx + minOf(0f, vy) * 0.18f)
                                            if (!gestureCancelled &&
                                                anchorTabId != null &&
                                                projectedUpwardPx >= dismissThresholdPx
                                            ) {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                interactionState.activeLiftAnimJob?.cancel()
                                                swipeUpTargetTabIdState.value = null
                                                interactionState.activeLiftAnimJob = scope.launch(
                                                    start = CoroutineStart.UNDISPATCHED,
                                                ) {
                                                    swipeUpLiftAnim.snapTo(0f)
                                                }
                                                latestOnCloseTab.value(anchorTabId)
                                            } else {
                                                val closingId = anchorTabId
                                                interactionState.activeLiftAnimJob?.cancel()
                                                interactionState.activeLiftAnimJob = scope.launch {
                                                    try {
                                                        swipeUpLiftAnim.animateTo(
                                                            targetValue = 0f,
                                                            animationSpec = FocalMotion.tabScrubSnapSpring(),
                                                        )
                                                    } finally {
                                                        if (swipeUpTargetTabIdState.value == closingId) {
                                                            swipeUpTargetTabIdState.value = null
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        DeckDragMode.UNDECIDED -> {
                                            if (wasActivelyFlinging && !gestureCancelled) {
                                                val maxIdx = (latestDeckTabs.value.size - 1).coerceAtLeast(0)
                                                val nearest = focusAnim.value.roundToInt().coerceIn(0, maxIdx)
                                                interactionState.activeDeckAnimJob?.cancel()
                                                interactionState.activeDeckAnimJob = scope.launch {
                                                    focusAnim.animateTo(
                                                        targetValue = nearest.toFloat(),
                                                        animationSpec = spring(
                                                            stiffness = 380f,
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                    ) { measurables, constraints ->
                        val cardWidthPx = (constraints.maxWidth - cardHorizontalPaddingPx * 2).coerceAtLeast(0)
                        val cardHeightPx = constraints.maxHeight.coerceAtLeast(0)
                        interactionState.cardWidthPx = cardWidthPx.coerceAtLeast(1).toFloat()
                        val cardConstraints = Constraints.fixed(width = cardWidthPx, height = cardHeightPx)
                        val indexedPlaceables = measurables.map { measurable ->
                            val idx = (measurable.layoutId as? Int) ?: 0
                            idx to measurable.measure(cardConstraints)
                        }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val focus = focusAnim.value
                            val centerX = (constraints.maxWidth - cardWidthPx) / 2
                            indexedPlaceables.forEach { (pageIndex, placeable) ->
                                val absDepth = (focus - pageIndex).absoluteValue
                                placeable.place(
                                    x = centerX,
                                    y = 0,
                                    zIndex = 100f - absDepth * 10f,
                                )
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

        // 4b. Floating Undo Close Tab Pill above the bottom dock
        AnimatedVisibility(
            visible = lastClosedTabTitle != null &&
                onUndoCloseTab != null &&
                activeTabDrag == null &&
                movedBanner == null,
            enter = fadeIn(animationSpec = tween(160)) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = FocalMotion.smartPillSpring(),
                ),
            exit = fadeOut(animationSpec = tween(160)) +
                scaleOut(
                    targetScale = 0.94f,
                    animationSpec = FocalMotion.smartPillSpring(),
                ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = 86.dp)
                .zIndex(225f),
        ) {
            val closedTitle = displayedClosedTabTitle
            if (closedTitle != null) {
                SmokedGlassBox(
                    shape = CircleShape,
                    glassTintState = dockGlassTint,
                    borderSpecular = colors.borderSpecular,
                    modifier = Modifier
                        .testTag("undo_close_tab_pill")
                        .shadow(
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
                        Icon(
                            imageVector = FocalGlyphs.Undo,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "Closed · ${closedTitle.trim().ifBlank { "Tab" }}",
                            style = typography.controlPillLabel,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillActive)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onUndoCloseTab?.invoke()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Undo",
                                style = typography.captionMetadata.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.inkOnPillActive,
                            )
                        }
                    }
                }
            }
        }
        }

        // 5. Floating Miniature Drag Ghost Card tracking the user's finger 1:1 in zero-inset root space
        val currentDrag = activeTabDrag
        val rootBounds = sheetRootCoords?.takeIf { it.isAttached }?.boundsInRoot()
        if (currentDrag != null && rootBounds != null) {
            val ghostWidthDp = 128.dp
            val ghostHeightDp = 168.dp
            val ghostWidthPx = with(density) { ghostWidthDp.toPx() }
            val ghostHeightPx = with(density) { ghostHeightDp.toPx() }
            val ghostScaleState = animateFloatAsState(
                targetValue = if (currentDrag.hoveredSpaceId != null) 0.78f else 0.96f,
                animationSpec = FocalMotion.smartPillSpring(),
                label = "FocalDragGhostScale",
            )
            val ghostBitmap = tabPreviews[currentDrag.tab.id]
            val ghostShape = RoundedCornerShape(20.dp)
            val ghostBorderColor = hoveredDropSpace?.let {
                SpaceAmbientPalette.fromKeyOrHex(it.id, it.ambientHex).dotColor.copy(alpha = 0.85f)
            } ?: colors.inkPrimary.copy(alpha = 0.35f)

            val minXPx = with(density) { 12.dp.toPx() }
            val maxXPx = (rootBounds.width - ghostWidthPx - minXPx).coerceAtLeast(minXPx)
            val stripBottomLocalY = spaceDropTargets.values
                .maxOfOrNull { it.bottom - rootBounds.top }
                ?: with(density) { 88.dp.toPx() }
            val minYPx = stripBottomLocalY + with(density) { 44.dp.toPx() }
            val maxYPx = (rootBounds.height - ghostHeightPx - with(density) { 24.dp.toPx() }).coerceAtLeast(minYPx)

            Box(
                modifier = Modifier
                    .zIndex(300f)
                    .offset {
                        val pointer = dragPointerInRootState.value
                        val localX = pointer.x - rootBounds.left
                        val localY = pointer.y - rootBounds.top
                        IntOffset(
                            x = (localX - ghostWidthPx * 0.5f).coerceIn(minXPx, maxXPx).roundToInt(),
                            y = (localY - ghostHeightPx * 0.5f).coerceIn(minYPx, maxYPx).roundToInt(),
                        )
                    }
                    .size(width = ghostWidthDp, height = ghostHeightDp)
                    .graphicsLayer {
                        val scale = ghostScaleState.value
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.96f
                    }
                    .testTag("drag_ghost_card")
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

@Composable
private fun TabDeckCard(
    tab: TabMetadataEntity,
    pageIndex: Int,
    isFrontCard: Boolean,
    isSelectedTab: Boolean,
    isPrivateAperture: Boolean,
    canDragBetweenGroups: Boolean,
    isThisTabBeingDragged: Boolean,
    previewBitmap: Bitmap?,
    cardGlassTint: State<Color>,
    effectiveNowEpochMs: Long,
    dismissThresholdPx: Float,
    focusProvider: () -> Float,
    swipeUpLiftProvider: (String) -> Float,
    onTapCard: () -> Unit,
    onCloseTab: () -> Unit,
    onLongPressDragStart: (rootPos: Offset) -> Unit,
    onLongPressDrag: (rootPos: Offset) -> Unit,
    onLongPressDragEnd: (rootPos: Offset?) -> Unit,
    onLongPressDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    var cardCoords by remember(tab.id) { mutableStateOf<LayoutCoordinates?>(null) }
    val latestOnLongPressDragStart = rememberUpdatedState(onLongPressDragStart)
    val latestOnLongPressDrag = rememberUpdatedState(onLongPressDrag)
    val latestOnLongPressDragEnd = rememberUpdatedState(onLongPressDragEnd)
    val latestOnLongPressDragCancel = rememberUpdatedState(onLongPressDragCancel)
    val cardShape = remember { RoundedCornerShape(26.dp) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val depth = focusProvider() - pageIndex
                val visuals = TabDeckPhysics.computeCardVisuals(
                    depth = depth,
                    cardWidthPx = size.width,
                    density = density,
                )
                val liftY = swipeUpLiftProvider(tab.id)
                val dragDismissFraction = (-liftY / dismissThresholdPx).coerceIn(0f, 1.4f)
                val effectiveScale = visuals.scale * (1f - dragDismissFraction * 0.05f)

                translationX = visuals.translationX
                translationY = visuals.translationY + liftY
                scaleX = effectiveScale
                scaleY = effectiveScale
                rotationY = visuals.rotationY
                cameraDistance = 14f * density
                val baseAlpha = visuals.alpha * (1f - dragDismissFraction * 0.55f).coerceIn(0.15f, 1f)
                alpha = if (isThisTabBeingDragged) baseAlpha * 0.24f else baseAlpha
            },
    ) {
        // Floating Favicon + Title Header right above the card's top edge (YAB style)
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
                    .graphicsLayer {
                        alpha = TabDeckPhysics.titleAlphaForDepth(focusProvider() - pageIndex)
                    },
            )

            if (isFrontCard) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            alpha = TabDeckPhysics.titleAlphaForDepth(focusProvider() - pageIndex)
                        }
                        .clip(CircleShape)
                        .background(colors.surfacePillSubtle)
                        .clickable(onClick = onCloseTab),
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

        // Tall Portrait Live Preview Card (Tap = open/focus, Press-and-Hold = drag to Space group)
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
                    width = if (isSelectedTab && isFrontCard) 1.dp else 0.75.dp,
                    color = if (isSelectedTab && isFrontCard) {
                        colors.inkPrimary.copy(alpha = 0.28f)
                    } else {
                        colors.borderSpecular
                    },
                    shape = cardShape,
                )
                .drawWithContent {
                    drawContent()
                    val veilAlpha = TabDeckPhysics.computeCardVisuals(
                        depth = focusProvider() - pageIndex,
                        cardWidthPx = size.width,
                        density = density,
                    ).veilAlpha
                    if (veilAlpha > 0.005f) {
                        drawRect(color = Color.Black.copy(alpha = veilAlpha))
                    }
                }
                .clickable(onClick = onTapCard)
                .pointerInput(tab.id, isFrontCard, canDragBetweenGroups) {
                    if (!isFrontCard || !canDragBetweenGroups) return@pointerInput
                    detectTabLongPressDrag(
                        longPressTimeoutMs = 260L,
                        onDragStart = { localPos ->
                            val coords = cardCoords?.takeIf { it.isAttached } ?: return@detectTabLongPressDrag
                            latestOnLongPressDragStart.value(coords.localToRoot(localPos))
                        },
                        onDrag = { localPos ->
                            val coords = cardCoords?.takeIf { it.isAttached } ?: return@detectTabLongPressDrag
                            latestOnLongPressDrag.value(coords.localToRoot(localPos))
                        },
                        onDragEnd = { localPos ->
                            val coords = cardCoords?.takeIf { it.isAttached }
                            latestOnLongPressDragEnd.value(coords?.localToRoot(localPos))
                        },
                        onDragCancel = {
                            latestOnLongPressDragCancel.value()
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
                    nowEpochMs = effectiveNowEpochMs,
                    isPrivate = isPrivateAperture,
                )
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
