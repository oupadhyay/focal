package app.focal.feature.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.database.TabMetadataEntity
import app.focal.design.FocalGlyphs
import app.focal.design.FocalGroupedCard
import app.focal.design.FocalHairlineDivider
import app.focal.design.FocalTheme
import app.focal.design.SiteFavicon
import app.focal.design.shaders.SmokedGlassBox
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.absoluteValue

/**
 * Screen 06 · Horizontal Tilted Card-Stack Tab Switcher + 24h Auto-Archive Tidied Tray.
 *
 * Uses `HorizontalPager` + `Modifier.graphicsLayer` (`rotationY = -6deg..+6deg`, scale depth)
 * reading pager offsets strictly in the draw/layer phase for locked 120Hz frame pacing.
 */
@Composable
fun TabSwitcherSheet(
    activeTabs: ImmutableList<TabMetadataEntity>,
    archivedTabs: ImmutableList<TabMetadataEntity>,
    selectedTabId: String?,
    isPrivateAperture: Boolean = false,
    privateTabs: ImmutableList<TabMetadataEntity> = persistentListOf(),
    nowEpochMs: Long = System.currentTimeMillis(),
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllPrivateTabs: () -> Unit = {},
    onRestoreArchivedTab: (String) -> Unit,
    onNewSearch: (isPrivate: Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val displayedTabs = if (isPrivateAperture) privateTabs else activeTabs
    val cardGlassTint = rememberUpdatedState(
        if (isPrivateAperture) colors.surfaceGlassPrivate else colors.effectiveSheetGlass,
    )

    val initialIdx = displayedTabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialIdx,
        pageCount = { displayedTabs.size.coerceAtLeast(1) },
    )
    var archiveExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvasBackground.copy(alpha = 0.97f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Header Metadata Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (isPrivateAperture) {
                    Icon(
                        imageVector = FocalGlyphs.Aperture,
                        contentDescription = null,
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = if (isPrivateAperture) {
                        "${privateTabs.size} Private ${if (privateTabs.size == 1) "Tab" else "Tabs"}"
                    } else {
                        "${activeTabs.size} ${if (activeTabs.size == 1) "Tab" else "Tabs"}"
                    },
                    style = typography.sheetTitle,
                    color = colors.inkPrimary,
                )
            }
            if (isPrivateAperture && privateTabs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.surfacePillSubtle)
                        .clickable(onClick = onCloseAllPrivateTabs)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "Close All",
                        style = typography.controlPillLabel,
                        color = colors.inkPrimary,
                    )
                }
            } else {
                Text(
                    text = if (isPrivateAperture) "Ephemeral" else "24h auto-archive",
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                )
            }
        }

        // Horizontal 3D Tilted Card-Stack Pager
        if (displayedTabs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.surfacePillSubtle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FocalGlyphs.Aperture,
                        contentDescription = null,
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (isPrivateAperture) "No Private Tabs" else "No Open Tabs",
                    style = typography.sheetTitle,
                    color = colors.inkSecondary,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                HorizontalPager(
                    state = pagerState,
                    key = { idx -> displayedTabs.getOrNull(idx)?.id ?: idx },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(212.dp),
                ) { pageIndex ->
                    val tab = displayedTabs.getOrNull(pageIndex) ?: return@HorizontalPager
                    val isCurrent = tab.id == selectedTabId
                    val cardShape = RoundedCornerShape(28.dp)

                    SmokedGlassBox(
                        shape = cardShape,
                        glassTintState = cardGlassTint,
                        borderSpecular = if (isCurrent) {
                            colors.inkPrimary.copy(alpha = 0.45f)
                        } else {
                            colors.borderSpecular
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageOffset = (
                                    (pagerState.currentPage - pageIndex) +
                                        pagerState.currentPageOffsetFraction
                                    ).coerceIn(-1.5f, 1.5f)
                                val absOffset = pageOffset.absoluteValue
                                cameraDistance = 12f * density
                                rotationY = pageOffset * -5.5f
                                scaleX = 1f - (absOffset * 0.055f).coerceAtMost(0.11f)
                                scaleY = 1f - (absOffset * 0.055f).coerceAtMost(0.11f)
                                alpha = 1f - (absOffset * 0.25f).coerceAtMost(0.45f)
                            }
                            .clickable { onSelectTab(tab.id) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    SiteFavicon(
                                        urlOrDomain = tab.url.ifBlank { tab.domain },
                                        allowNetworkFetch = !isPrivateAperture,
                                        size = 18.dp,
                                        cornerRadius = 5.dp,
                                    )
                                    Text(
                                        text = tab.domain.ifBlank { "New Tab" },
                                        style = typography.urlDomainPill,
                                        color = colors.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(colors.surfacePillSubtle)
                                        .clickable { onCloseTab(tab.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = FocalGlyphs.Close,
                                        contentDescription = "Close tab",
                                        tint = colors.inkSecondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = tab.title.ifBlank { tab.url },
                                    style = typography.sheetTitle,
                                    color = colors.inkPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = tab.url,
                                    style = typography.captionMetadata,
                                    color = colors.inkFaint,
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
                                    isPrivate = isPrivateAperture,
                                )
                            }
                        }
                    }
                }

                // Grouped Borderless Quick-Jump Stack + Collapsible 24h Tidied Tray
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FocalGroupedCard {
                        displayedTabs.forEachIndexed { index, tab ->
                            if (index > 0) {
                                FocalHairlineDivider(insetStart = 48.dp)
                            }
                            val isSelected = tab.id == selectedTabId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) {
                                            colors.inkPrimary.copy(alpha = 0.06f)
                                        } else {
                                            androidx.compose.ui.graphics.Color.Transparent
                                        },
                                    )
                                    .clickable { onSelectTab(tab.id) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SiteFavicon(
                                    urlOrDomain = tab.url.ifBlank { tab.domain },
                                    allowNetworkFetch = !isPrivateAperture,
                                    size = 20.dp,
                                    cornerRadius = 5.dp,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tab.title.ifBlank { tab.url },
                                        style = typography.bodyCompact.copy(
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.5.sp,
                                        ),
                                        color = colors.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = tab.domain.ifBlank { "New Tab" },
                                        style = typography.captionMetadata,
                                        color = colors.inkSecondary,
                                    )
                                }
                                TabTtlOrDraftBadge(
                                    tab = tab,
                                    nowEpochMs = nowEpochMs,
                                    isPrivate = isPrivateAperture,
                                )
                            }
                        }
                    }

                    // Collapsible >24h Auto-Archived Tidied Tray (non-private tabs only)
                    if (!isPrivateAperture && archivedTabs.isNotEmpty()) {
                        FocalGroupedCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { archiveExpanded = !archiveExpanded }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Icon(
                                            imageVector = FocalGlyphs.Clock,
                                            contentDescription = null,
                                            tint = colors.inkSecondary,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = "Tidied (${archivedTabs.size})",
                                            style = typography.controlPillLabel,
                                            color = colors.inkSecondary,
                                        )
                                    }
                                    Icon(
                                        imageVector = if (archiveExpanded) {
                                            FocalGlyphs.ChevronDown
                                        } else {
                                            FocalGlyphs.ChevronRight
                                        },
                                        contentDescription = null,
                                        tint = colors.inkFaint,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }

                                AnimatedVisibility(visible = archiveExpanded) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        archivedTabs.take(8).forEach { archived ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable { onRestoreArchivedTab(archived.id) }
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                SiteFavicon(
                                                    urlOrDomain = archived.url.ifBlank { archived.domain },
                                                    allowNetworkFetch = true,
                                                    size = 15.dp,
                                                    cornerRadius = 4.dp,
                                                )
                                                Text(
                                                    text = "${archived.domain} — ${archived.title}",
                                                    style = typography.captionMetadata,
                                                    color = colors.inkSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(
                                                    text = "Restore",
                                                    style = typography.captionMetadata,
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
            }
        }

        // Bottom Controls Bar (`Private` · `+ New Tab` · `Done`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable { onNewSearch(!isPrivateAperture) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = FocalGlyphs.Aperture,
                    contentDescription = null,
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = if (isPrivateAperture) "Regular" else "Private",
                    style = typography.controlPillLabel,
                    color = colors.inkPrimary,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(colors.surfacePillActive)
                    .clickable { onNewSearch(isPrivateAperture) }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = FocalGlyphs.Plus,
                    contentDescription = null,
                    tint = colors.inkOnPillActive,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isPrivateAperture) "New Private Tab" else "New Tab",
                    style = typography.controlPillLabel,
                    color = colors.inkOnPillActive,
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable(onClick = onDone)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Done",
                    style = typography.controlPillLabel,
                    color = colors.inkPrimary,
                )
            }
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
