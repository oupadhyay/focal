package app.focal.feature.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.database.TabMetadataEntity
import app.focal.design.FocalTheme
import app.focal.design.shaders.SmokedGlassBox
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.absoluteValue

/**
 * Screen 06 · Horizontal Tilted Card-Stack Tab Switcher + 24h Auto-Archive Tidied Tray.
 *
 * Uses `HorizontalPager` + `Modifier.graphicsLayer` (`rotationY = -6deg..+6deg`, scale depth)
 * reading pager offsets strictly in the draw/layer phase for locked 120Hz frame pacing.
 * Supports isolated Private Aperture (`◎`) tab inspection and 1-tap `"Close All Private Tabs"` wipe.
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
        if (isPrivateAperture) colors.surfaceGlassPrivate else colors.effectiveBarGlass,
    )

    val initialIdx = displayedTabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialIdx,
        pageCount = { displayedTabs.size.coerceAtLeast(1) },
    )
    var archiveExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvasBackground.copy(alpha = 0.96f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Header Metadata Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isPrivateAperture) {
                    "◎ ${privateTabs.size} Private ${if (privateTabs.size == 1) "Tab" else "Tabs"}"
                } else {
                    "${activeTabs.size} Active Tabs"
                },
                style = typography.urlDomainPill,
                color = colors.inkPrimary,
            )
            if (isPrivateAperture && privateTabs.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.surfacePillSubtle)
                        .border(1.dp, colors.borderSpecular, CircleShape)
                        .clickable(onClick = onCloseAllPrivateTabs)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "Close All Private Tabs",
                        style = typography.controlPillLabel,
                        color = colors.inkPrimary,
                    )
                }
            } else {
                Text(
                    text = if (isPrivateAperture) {
                        "Ephemeral · Wiped on close"
                    } else {
                        "Auto-archives after 24h idle"
                    },
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                )
            }
        }

        // Horizontal 3D Tilted Card-Stack Pager
        if (displayedTabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isPrivateAperture) {
                        "◎ Private Aperture · Zero History"
                    } else {
                        "Calm Canvas · No Open Tabs"
                    },
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
                Spacer(Modifier.height(6.dp))

                HorizontalPager(
                    state = pagerState,
                    key = { idx -> displayedTabs.getOrNull(idx)?.id ?: idx },
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) { pageIndex ->
                    val tab = displayedTabs.getOrNull(pageIndex) ?: return@HorizontalPager
                    val isCurrent = tab.id == selectedTabId
                    val cardShape = RoundedCornerShape(24.dp)

                    SmokedGlassBox(
                        shape = cardShape,
                        glassTintState = cardGlassTint,
                        borderSpecular = if (isCurrent) colors.inkPrimary else colors.borderSpecular,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageOffset = (
                                    (pagerState.currentPage - pageIndex) +
                                        pagerState.currentPageOffsetFraction
                                    ).coerceIn(-1.5f, 1.5f)
                                val absOffset = pageOffset.absoluteValue
                                cameraDistance = 12f * density
                                rotationY = pageOffset * -6f
                                scaleX = 1f - (absOffset * 0.06f).coerceAtMost(0.12f)
                                scaleY = 1f - (absOffset * 0.06f).coerceAtMost(0.12f)
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
                                Text(
                                    text = tab.domain.ifBlank { "New Tab" },
                                    style = typography.urlDomainPill,
                                    color = colors.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TabTtlOrDraftBadge(
                                    tab = tab,
                                    nowEpochMs = nowEpochMs,
                                    isPrivate = isPrivateAperture,
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = tab.title.ifBlank { tab.url },
                                    style = typography.sheetTitle,
                                    color = colors.inkPrimary,
                                    maxLines = 3,
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
                                Text(
                                    text = if (tab.blockedTrackersCount > 0) {
                                        "${tab.blockedTrackersCount} trackers blocked"
                                    } else {
                                        "Clean surface"
                                    },
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                )
                                Text(
                                    text = "Close ✕",
                                    style = typography.controlPillLabel,
                                    color = colors.inkSecondary,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { onCloseTab(tab.id) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                // Compact Quick-Jump Stack + 24h Tidied Tray
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    displayedTabs.forEach { tab ->
                        val isSelected = tab.id == selectedTabId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.surfacePillSubtle)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) colors.inkPrimary else colors.borderSpecular,
                                    shape = RoundedCornerShape(18.dp),
                                )
                                .clickable { onSelectTab(tab.id) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tab.domain.ifBlank { "New Tab" },
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                )
                                Text(
                                    text = tab.title.ifBlank { tab.url },
                                    style = typography.cardTitle,
                                    color = colors.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TabTtlOrDraftBadge(
                                tab = tab,
                                nowEpochMs = nowEpochMs,
                                isPrivate = isPrivateAperture,
                            )
                        }
                    }

                    // Collapsible >24h Auto-Archived Tidied Tray (non-private tabs only)
                    if (!isPrivateAperture) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.surfacePillSubtle)
                                .border(1.dp, colors.borderSpecular, RoundedCornerShape(20.dp))
                                .clickable { archiveExpanded = !archiveExpanded }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Tidied (>24h idle · ${archivedTabs.size} tabs)",
                                    style = typography.controlPillLabel,
                                    color = colors.inkSecondary,
                                )
                                Text(
                                    text = "1-tap restore",
                                    style = typography.captionMetadata,
                                    color = colors.inkFaint,
                                )
                            }

                            AnimatedVisibility(visible = archiveExpanded) {
                                if (archivedTabs.isEmpty()) {
                                    Text(
                                        text = "Idle tabs without unsent form drafts tidy here automatically after 24h.",
                                        style = typography.captionMetadata,
                                        color = colors.inkFaint,
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        archivedTabs.take(8).forEach { archived ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable { onRestoreArchivedTab(archived.id) }
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    text = "· ${archived.domain} — ${archived.title}",
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

        // Bottom Controls Bar (`◎ Private` / `◈ Regular` · `+ New Search` · `Done`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.effectiveBarGlass)
                    .border(1.dp, colors.borderSpecular, CircleShape)
                    .clickable { onNewSearch(!isPrivateAperture) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isPrivateAperture) "◈ Regular" else "◎ Private",
                    style = typography.controlPillLabel,
                    color = colors.inkPrimary,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(colors.surfacePillActive)
                    .clickable { onNewSearch(isPrivateAperture) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isPrivateAperture) "+ New Private Tab" else "+ New Search",
                    style = typography.controlPillLabel,
                    color = colors.inkOnPillActive,
                )
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.effectiveBarGlass)
                    .border(1.dp, colors.borderSpecular, CircleShape)
                    .clickable(onClick = onDone)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
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

    val label = when {
        isPrivate -> "◎ Ephemeral"
        tab.hasUnsentFormDraft -> "🔒 Unsent Draft Protected"
        else -> {
            val remHours = (tab.remainingTtlMs(nowEpochMs) / (60L * 60L * 1000L)).coerceIn(1L, 24L)
            "⏱ ${remHours}h left"
        }
    }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surfacePillSubtle)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = typography.captionMetadata,
            color = if (tab.hasUnsentFormDraft || isPrivate) colors.inkPrimary else colors.inkSecondary,
        )
    }
}
