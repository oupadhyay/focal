package app.focal.feature.spaces

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.database.HistoryEntryEntity
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalGroupedCard
import app.focal.design.FocalHairlineDivider
import app.focal.design.FocalMotion
import app.focal.design.FocalSheetGrabber
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseOwner
import app.focal.design.GestureLeaseToken
import app.focal.design.LocalGestureLeaseController
import app.focal.design.SiteFavicon
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class HistoryDayBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    EARLIER("Earlier"),
}

/**
 * Pure local-calendar grouper & filter for [HistoryEntryEntity] items.
 *
 * Groups non-empty buckets in `Today` -> `Yesterday` -> `Earlier` order while preserving
 * descending visit timestamp order inside each bucket.
 */
object HistoryDayGrouper {

    fun groupEntries(
        entries: List<HistoryEntryEntity>,
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<HistoryDayBucket, List<HistoryEntryEntity>>> {
        if (entries.isEmpty()) return emptyList()

        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)

        val sorted = entries.sortedByDescending { it.visitedAtEpochMs }
        val todayEntries = mutableListOf<HistoryEntryEntity>()
        val yesterdayEntries = mutableListOf<HistoryEntryEntity>()
        val earlierEntries = mutableListOf<HistoryEntryEntity>()

        for (entry in sorted) {
            val visitDate = Instant.ofEpochMilli(entry.visitedAtEpochMs).atZone(zoneId).toLocalDate()
            when {
                !visitDate.isBefore(today) -> todayEntries.add(entry)
                visitDate == yesterday -> yesterdayEntries.add(entry)
                else -> earlierEntries.add(entry)
            }
        }

        return buildList(3) {
            if (todayEntries.isNotEmpty()) add(HistoryDayBucket.TODAY to todayEntries)
            if (yesterdayEntries.isNotEmpty()) add(HistoryDayBucket.YESTERDAY to yesterdayEntries)
            if (earlierEntries.isNotEmpty()) add(HistoryDayBucket.EARLIER to earlierEntries)
        }
    }

    fun filterEntries(
        entries: List<HistoryEntryEntity>,
        query: String,
    ): List<HistoryEntryEntity> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return entries
        return entries.filter { entry ->
            entry.title.contains(trimmed, ignoreCase = true) ||
                entry.domain.contains(trimmed, ignoreCase = true) ||
                entry.url.contains(trimmed, ignoreCase = true)
        }
    }
}

/**
 * Searchable Local Browsing History Sheet rendered in Focal's achromatic Smoked Glass.
 *
 * Features real-time local filtering across title/domain/URL, calendar-bucketed sections
 * (`Today`, `Yesterday`, `Earlier`) in borderless [FocalGroupedCard] containers, single-entry
 * removal, clear-all control, and drag-down-to-dismiss with critically-damped cancel settle.
 */
@Composable
fun HistorySheet(
    entries: ImmutableList<HistoryEntryEntity>,
    onOpenUrl: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val gestureLease = LocalGestureLeaseController.current
    val sheetBaseTint = rememberUpdatedState(colors.effectiveSheetGlass)

    var internalDismissDragPx by remember { mutableFloatStateOf(0f) }
    val dismissSettleScope = rememberCoroutineScope()
    val dismissSettleAnim = remember { Animatable(0f) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredEntries = remember(entries, searchQuery) {
        HistoryDayGrouper.filterEntries(entries, searchQuery)
    }
    val groupedSections = remember(filteredEntries) {
        HistoryDayGrouper.groupEntries(filteredEntries)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = FocalGeometry.SheetOuterShape,
            glassTintState = sheetBaseTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding()
                .testTag("history_sheet")
                .focalSheetEntrance(riseDp = 18.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = FocalGeometry.SheetOuterShape,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .graphicsLayer {
                    translationY = (internalDismissDragPx + dismissSettleAnim.value).coerceAtLeast(0f)
                }
                .pointerInput(gestureLease) {
                    var dragLeaseToken: GestureLeaseToken? = null
                    try {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragLeaseToken = gestureLease?.tryAcquire(GestureLeaseOwner.SHEET_DRAG)
                            },
                            onDragEnd = {
                                val stillValid = gestureLease == null || gestureLease.isValid(dragLeaseToken)
                                gestureLease?.release(dragLeaseToken)
                                dragLeaseToken = null
                                val dragged = internalDismissDragPx
                                internalDismissDragPx = 0f
                                if (stillValid && dragged > 120.dp.toPx()) {
                                    onDismiss()
                                } else if (dragged > 0f) {
                                    dismissSettleScope.launch {
                                        dismissSettleAnim.snapTo(dragged)
                                        dismissSettleAnim.animateTo(
                                            targetValue = 0f,
                                            animationSpec = FocalMotion.spacesSheetSpring(),
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                gestureLease?.release(dragLeaseToken)
                                dragLeaseToken = null
                                val dragged = internalDismissDragPx
                                internalDismissDragPx = 0f
                                if (dragged > 0f) {
                                    dismissSettleScope.launch {
                                        dismissSettleAnim.snapTo(dragged)
                                        dismissSettleAnim.animateTo(
                                            targetValue = 0f,
                                            animationSpec = FocalMotion.spacesSheetSpring(),
                                        )
                                    }
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (gestureLease != null && !gestureLease.isValid(dragLeaseToken)) {
                                    internalDismissDragPx = 0f
                                    return@detectVerticalDragGestures
                                }
                                if (dragAmount > 0f || internalDismissDragPx > 0f) {
                                    change.consume()
                                    internalDismissDragPx = (internalDismissDragPx + dragAmount).coerceAtLeast(0f)
                                }
                            },
                        )
                    } finally {
                        gestureLease?.release(dragLeaseToken)
                    }
                }
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FocalSheetGrabber(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                // Header Row (`History` · `Clear`)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Clock,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "History",
                            style = typography.sheetTitle,
                            color = colors.inkPrimary,
                        )
                    }

                    if (entries.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillSubtle)
                                .clickable(onClick = onClearAllHistory)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Clear",
                                style = typography.controlPillLabel,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                }

                // Compact inline search field filtering local history by title/domain/URL
                if (entries.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(FocalGeometry.InputPillShape)
                            .background(colors.surfacePillSubtle)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Search,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search history…",
                                    style = typography.bodyCompact,
                                    color = colors.inkFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = typography.bodyCompact.copy(color = colors.inkPrimary),
                                cursorBrush = SolidColor(colors.inkPrimary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfacePillSubtle)
                                    .clickable { searchQuery = "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = FocalGlyphs.Close,
                                    contentDescription = "Clear search",
                                    tint = colors.inkSecondary,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                }

                // Scrollable grouped history sections or calm empty states
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (entries.isEmpty()) {
                        FocalGroupedCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(colors.surfacePillSubtle),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = FocalGlyphs.Clock,
                                        contentDescription = null,
                                        tint = colors.inkSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "No browsing history yet",
                                    style = typography.cardTitle,
                                    color = colors.inkPrimary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "Pages you visit in regular spaces appear here",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else if (groupedSections.isEmpty()) {
                        FocalGroupedCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = FocalGlyphs.Search,
                                    contentDescription = null,
                                    tint = colors.inkFaint,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "No matching history",
                                    style = typography.cardTitle,
                                    color = colors.inkPrimary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "Try a different title, domain, or address",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        groupedSections.forEach { (bucket, bucketEntries) ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = bucket.label,
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                FocalGroupedCard {
                                    bucketEntries.forEachIndexed { index, entry ->
                                        if (index > 0) {
                                            FocalHairlineDivider(insetStart = 50.dp)
                                        }
                                        HistoryEntryRow(
                                            entry = entry,
                                            onOpen = {
                                                if (entry.url.isNotBlank()) {
                                                    onOpenUrl(entry.url)
                                                }
                                            },
                                            onDelete = { onDeleteEntry(entry.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Subtle Privacy Footer + Done Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = FocalGlyphs.Aperture,
                            contentDescription = null,
                            tint = colors.inkFaint,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "Private Aperture visits are never saved",
                            style = typography.captionMetadata,
                            color = colors.inkFaint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.surfacePillSubtle)
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "Done",
                            style = typography.controlPillLabel,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(
    entry: HistoryEntryEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val displayDomain = remember(entry.domain, entry.url) {
        entry.domain.ifBlank {
            entry.url
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .substringBefore('/')
        }
    }
    val displayTitle = entry.title.ifBlank { displayDomain.ifBlank { entry.url } }
    val showDomainSubtitle = displayDomain.isNotBlank() && displayDomain != displayTitle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteFavicon(
            urlOrDomain = entry.url.ifBlank { entry.domain },
            size = 24.dp,
            cornerRadius = 7.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = displayTitle,
                style = typography.bodyCompact.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                ),
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showDomainSubtitle) {
                Text(
                    text = displayDomain,
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.surfacePillSubtle)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FocalGlyphs.Close,
                contentDescription = "Remove $displayTitle",
                tint = colors.inkSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
