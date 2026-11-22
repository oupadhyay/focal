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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.database.DownloadRecordEntity
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
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatDownloadRelativeTime(
    createdAtEpochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val diffMs = (nowEpochMs - createdAtEpochMs).coerceAtLeast(0L)
    return when {
        diffMs < 60_000L -> "Just now"
        diffMs < 3_600_000L -> "${diffMs / 60_000L}m ago"
        diffMs < 86_400_000L -> "${diffMs / 3_600_000L}h ago"
        diffMs < 7L * 86_400_000L -> "${diffMs / 86_400_000L}d ago"
        else -> {
            val date = Instant.ofEpochMilli(createdAtEpochMs).atZone(zoneId).toLocalDate()
            val nowDate = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
            val pattern = if (date.year == nowDate.year) "MMM d" else "MMM d, yyyy"
            date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
}

/**
 * In-App Downloads Sheet rendered in Focal's achromatic Smoked Glass.
 *
 * Lists saved files and downloaded images in a borderless [FocalGroupedCard] with 1-tap open,
 * quick share, single-item removal, clear-all control, and drag-down-to-dismiss with
 * critically-damped cancel settle.
 */
@Composable
fun DownloadsSheet(
    downloads: ImmutableList<DownloadRecordEntity>,
    onOpenDownload: (DownloadRecordEntity) -> Unit,
    onShareDownload: (DownloadRecordEntity) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onClearAll: () -> Unit,
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

    val orderedDownloads = remember(downloads) {
        downloads.sortedByDescending { it.createdAtEpochMs }
    }
    val nowEpochMs = remember(orderedDownloads) { System.currentTimeMillis() }

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
                .testTag("downloads_sheet")
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

                // Header Row (`Downloads` · `Clear`)
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
                            imageVector = FocalGlyphs.Download,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Downloads",
                            style = typography.sheetTitle,
                            color = colors.inkPrimary,
                        )
                    }

                    if (orderedDownloads.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillSubtle)
                                .clickable(onClick = onClearAll)
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

                // Scrollable Downloads List or Calm Empty State
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (orderedDownloads.isEmpty()) {
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
                                        imageVector = FocalGlyphs.Download,
                                        contentDescription = null,
                                        tint = colors.inkSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "No downloads yet",
                                    style = typography.cardTitle,
                                    color = colors.inkPrimary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "Files and saved images appear here",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        FocalGroupedCard {
                            orderedDownloads.forEachIndexed { index, record ->
                                if (index > 0) {
                                    FocalHairlineDivider(insetStart = 60.dp)
                                }
                                DownloadRecordRow(
                                    record = record,
                                    nowEpochMs = nowEpochMs,
                                    onOpen = { onOpenDownload(record) },
                                    onShare = { onShareDownload(record) },
                                    onDelete = { onDeleteDownload(record.id) },
                                )
                            }
                        }
                    }
                }

                // Footer Bar (count metadata + Done Pill)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (orderedDownloads.isNotEmpty()) {
                        Text(
                            text = if (orderedDownloads.size == 1) {
                                "1 saved file"
                            } else {
                                "${orderedDownloads.size} saved files"
                            },
                            style = typography.captionMetadata,
                            color = colors.inkFaint,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    } else {
                        Spacer(Modifier.size(1.dp))
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
private fun DownloadRecordRow(
    record: DownloadRecordEntity,
    nowEpochMs: Long,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val displayFileName = remember(record.fileName, record.url) {
        record.fileName.ifBlank {
            record.url
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .ifBlank { "Download" }
        }
    }
    val displayDomain = remember(record.originDomain, record.url) {
        record.originDomain.ifBlank {
            record.url
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .substringBefore('/')
        }
    }
    val relativeTime = remember(record.createdAtEpochMs, nowEpochMs) {
        formatDownloadRelativeTime(
            createdAtEpochMs = record.createdAtEpochMs,
            nowEpochMs = nowEpochMs,
        )
    }
    val metadataLine = if (displayDomain.isNotBlank()) {
        "$displayDomain · $relativeTime"
    } else {
        relativeTime
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfacePillSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FocalGlyphs.Download,
                contentDescription = null,
                tint = colors.inkPrimary,
                modifier = Modifier.size(17.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = displayFileName,
                style = typography.bodyCompact.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                ),
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metadataLine,
                style = typography.captionMetadata,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable(onClick = onShare),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Share,
                    contentDescription = "Share $displayFileName",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Close,
                    contentDescription = "Remove $displayFileName",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
