package app.focal.feature.reader

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.design.AlloCrispDisplay
import app.focal.design.FocalTheme
import app.focal.design.ReaderPaperPalette
import app.focal.design.ReaderPaperThemeColors
import app.focal.design.SwitzerBody
import app.focal.design.consumeClicks
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.api.OriginOverrides
import app.focal.engine.api.ReadableDocument

enum class PageMenuTab {
    MENU,
    SITE_OVERRIDES,
}

@Immutable
data class ReaderPreferences(
    val theme: ReaderPaperThemeColors = ReaderPaperPalette.Sepia,
    val useEditorialSerif: Boolean = false,
    val fontSizeSp: Int = 17,
    val lineHeightMultiplier: Float = 1.60f,
)

/**
 * Full-screen Distraction-Free 5-Theme Reader View (`Obsidian`, `Paper`, `Sepia`, `Alabaster`, `Sage`).
 */
@Composable
fun ReaderModeView(
    document: ReadableDocument,
    initialThemeId: String = "sepia",
    onCloseReader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = FocalTheme.typography
    var selectedTheme by remember(initialThemeId) {
        mutableStateOf(
            ReaderPaperPalette.All.firstOrNull { it.id == initialThemeId } ?: ReaderPaperPalette.Sepia,
        )
    }
    var useSerif by remember { mutableStateOf(false) }
    var fontSizeSp by remember { mutableIntStateOf(17) }
    var lineHeightRatio by remember { mutableFloatStateOf(1.60f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(selectedTheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Reader Appearance Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${document.siteName ?: "READER"} · ${document.estimatedReadMinutes} MIN READ",
                    style = typography.captionMetadata,
                    color = selectedTheme.secondaryInk,
                )
                Text(
                    text = "Done ✕",
                    style = typography.controlPillLabel,
                    color = selectedTheme.primaryInk,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onCloseReader)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            // 5 Paper Swatch Circles (`Obsidian`, `Paper`, `Sepia`, `Alabaster`, `Sage`) + Font Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderPaperPalette.All.forEach { swatch ->
                        val active = swatch.id == selectedTheme.id
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatch.background)
                                .border(
                                    width = if (active) 2.dp else 1.dp,
                                    color = if (active) selectedTheme.primaryInk else selectedTheme.divider,
                                    shape = CircleShape,
                                )
                                .clickable { selectedTheme = swatch },
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderControlChip(
                        label = if (useSerif) "Serif" else "Switzer",
                        theme = selectedTheme,
                        onClick = { useSerif = !useSerif },
                    )
                    ReaderControlChip(
                        label = "A−",
                        theme = selectedTheme,
                        onClick = { fontSizeSp = (fontSizeSp - 1).coerceIn(15, 22) },
                    )
                    ReaderControlChip(
                        label = "A+",
                        theme = selectedTheme,
                        onClick = { fontSizeSp = (fontSizeSp + 1).coerceIn(15, 22) },
                    )
                    ReaderControlChip(
                        label = String.format("%.2f×", lineHeightRatio),
                        theme = selectedTheme,
                        onClick = {
                            lineHeightRatio = when {
                                lineHeightRatio < 1.50f -> 1.60f
                                lineHeightRatio < 1.70f -> 1.75f
                                else -> 1.45f
                            }
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(selectedTheme.divider),
        )

        // Article Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = document.title,
                fontFamily = AlloCrispDisplay,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                color = selectedTheme.primaryInk,
            )

            if (!document.byline.isNullOrBlank()) {
                Text(
                    text = document.byline!!,
                    style = typography.captionMetadata,
                    color = selectedTheme.secondaryInk,
                )
            }

            val bodyFontFamily = if (useSerif) FontFamily.Serif else SwitzerBody
            val lineHeightSp = (fontSizeSp * lineHeightRatio).sp

            document.paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    fontFamily = bodyFontFamily,
                    fontSize = fontSizeSp.sp,
                    lineHeight = lineHeightSp,
                    color = selectedTheme.primaryInk,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ReaderControlChip(
    label: String,
    theme: ReaderPaperThemeColors,
    onClick: () -> Unit,
) {
    val typography = FocalTheme.typography
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, theme.divider, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.captionMetadata,
            color = theme.primaryInk,
        )
    }
}

/**
 * Screen 08 · `···` Page Menu & Live Per-Site Overrides Smoked Glass Sheet.
 */
@Composable
fun PageMenuAndSiteOverridesSheet(
    domain: String,
    fullUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    blockedCountInTab: Long,
    overrides: OriginOverrides,
    initialTab: PageMenuTab = PageMenuTab.MENU,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onReload: () -> Unit,
    onCopyCleanLink: () -> Unit,
    onShareCleanLink: () -> Unit,
    onPinToSpace: () -> Unit,
    onSaveBookmark: () -> Unit,
    onOpenReaderMode: () -> Unit,
    onTranslatePage: () -> Unit = {},
    onUpdateSiteOverrides: (OriginOverrides) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sheetGlassTint = rememberUpdatedState(colors.effectiveSheetGlass)
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = RoundedCornerShape(26.dp),
            glassTintState = sheetGlassTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Compact Navigation & Clean Link Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfacePillSubtle)
                        .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "←",
                            style = typography.cardTitle,
                            color = if (canGoBack) colors.inkPrimary else colors.inkFaint,
                            modifier = Modifier.clickable(enabled = canGoBack, onClick = onGoBack),
                        )
                        Text(
                            text = "→",
                            style = typography.cardTitle,
                            color = if (canGoForward) colors.inkPrimary else colors.inkFaint,
                            modifier = Modifier.clickable(enabled = canGoForward, onClick = onGoForward),
                        )
                    }

                    Text(
                        text = domain.ifBlank { fullUrl.ifBlank { "focal://calm" } },
                        style = typography.urlDomainPill,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Copy Clean",
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                            modifier = Modifier.clickable(onClick = onCopyCleanLink),
                        )
                        Text(
                            text = "↻",
                            style = typography.cardTitle,
                            color = colors.inkPrimary,
                            modifier = Modifier.clickable(onClick = onReload),
                        )
                    }
                }

                // Subtab Selector (`Page Menu` vs `Per-Site Overrides`)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfacePillSubtle)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        PageMenuTab.MENU to "Page Actions",
                        PageMenuTab.SITE_OVERRIDES to "Per-Site Overrides",
                    ).forEach { (tab, title) ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (selected) colors.surfacePillActive else Color.Transparent)
                                .clickable { activeTab = tab }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                style = typography.controlPillLabel,
                                color = if (selected) colors.inkOnPillActive else colors.inkSecondary,
                            )
                        }
                    }
                }

                AnimatedContent(
                    targetState = activeTab,
                    label = "FocalPageMenuSubtab",
                ) { tab ->
                    if (tab == PageMenuTab.MENU) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 4 Squircle Quick Actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MenuSquircleButton(
                                    icon = "↗",
                                    label = "Share",
                                    onClick = onShareCleanLink,
                                    modifier = Modifier.weight(1f),
                                )
                                MenuSquircleButton(
                                    icon = "◈",
                                    label = "Pin Space",
                                    onClick = onPinToSpace,
                                    modifier = Modifier.weight(1f),
                                )
                                MenuSquircleButton(
                                    icon = "★",
                                    label = "Bookmark",
                                    onClick = onSaveBookmark,
                                    modifier = Modifier.weight(1f),
                                )
                                MenuSquircleButton(
                                    icon = "Aa",
                                    label = "Reader",
                                    onClick = onOpenReaderMode,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            MenuRowItem(
                                title = "Show Distraction-Free Reader",
                                subtitle = "Obsidian · Paper · Sepia · Alabaster · Sage",
                                onClick = onOpenReaderMode,
                            )
                            MenuRowItem(
                                title = "文A Translate Page",
                                subtitle = "Translate current page into device language",
                                onClick = onTranslatePage,
                            )
                            MenuRowItem(
                                title = if (overrides.desktopSite) "Desktop Viewport: ON" else "Request Desktop Site",
                                subtitle = "Toggle desktop User-Agent & wide viewport for ${overrides.origin}",
                                onClick = {
                                    onUpdateSiteOverrides(
                                        overrides.copy(desktopSite = !overrides.desktopSite),
                                    )
                                },
                            )
                            MenuRowItem(
                                title = "Live Site Overrides (${overrides.origin.ifBlank { "global" }})",
                                subtitle = "$blockedCountInTab trackers blocked · Custom font & dark mode",
                                onClick = { activeTab = PageMenuTab.SITE_OVERRIDES },
                            )
                        }
                    } else {
                        // Live Per-Site Overrides (`eTLD+1`)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OverrideSwitchRow(
                                title = "Block Ads & Trackers",
                                subtitle = "$blockedCountInTab network requests blocked via adblock-rust",
                                checked = overrides.blockAdsAndTrackers,
                                onCheckedChange = {
                                    onUpdateSiteOverrides(overrides.copy(blockAdsAndTrackers = it))
                                },
                            )
                            OverrideSwitchRow(
                                title = "Force Dark Web Surface",
                                subtitle = "Applies algorithmic dark surface tokens immediately",
                                checked = overrides.forceDarkWebSurface,
                                onCheckedChange = {
                                    onUpdateSiteOverrides(overrides.copy(forceDarkWebSurface = it))
                                },
                            )
                            OverrideSwitchRow(
                                title = "Override Publisher Fonts",
                                subtitle = "Render headings in Allo Crisp & body in Switzer",
                                checked = overrides.overridePublisherFonts,
                                onCheckedChange = {
                                    onUpdateSiteOverrides(overrides.copy(overridePublisherFonts = it))
                                },
                            )
                            OverrideSwitchRow(
                                title = "JavaScript Execution",
                                subtitle = "Scoped strictly to ${overrides.origin.ifBlank { "this origin" }}",
                                checked = overrides.javascriptEnabled,
                                onCheckedChange = {
                                    onUpdateSiteOverrides(overrides.copy(javascriptEnabled = it))
                                },
                            )

                            // Page Zoom Stepper (`85%–150%`)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.surfacePillSubtle)
                                    .border(1.dp, colors.borderSpecular, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Page Zoom",
                                    style = typography.urlDomainPill,
                                    color = colors.inkPrimary,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "−",
                                        style = typography.cardTitle,
                                        color = colors.inkPrimary,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable {
                                                val next = (overrides.pageZoomPercent - 10).coerceIn(85, 150)
                                                onUpdateSiteOverrides(overrides.copy(pageZoomPercent = next))
                                            }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                    Text(
                                        text = "${overrides.pageZoomPercent}%",
                                        style = typography.tabBadgeNumeral,
                                        color = colors.inkPrimary,
                                    )
                                    Text(
                                        text = "+",
                                        style = typography.cardTitle,
                                        color = colors.inkPrimary,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable {
                                                val next = (overrides.pageZoomPercent + 10).coerceIn(85, 150)
                                                onUpdateSiteOverrides(overrides.copy(pageZoomPercent = next))
                                            }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
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

@Composable
private fun MenuSquircleButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfacePillSubtle)
            .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = icon,
            style = typography.cardTitle,
            color = colors.inkPrimary,
        )
        Text(
            text = label,
            style = typography.captionMetadata,
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MenuRowItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfacePillSubtle)
            .border(1.dp, colors.borderSpecular, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.urlDomainPill,
                color = colors.inkPrimary,
            )
            Text(
                text = subtitle,
                style = typography.captionMetadata,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "›",
            style = typography.cardTitle,
            color = colors.inkFaint,
        )
    }
}

@Composable
private fun OverrideSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfacePillSubtle)
            .border(1.dp, colors.borderSpecular, RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.urlDomainPill,
                color = colors.inkPrimary,
            )
            Text(
                text = subtitle,
                style = typography.captionMetadata,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.inkOnPillActive,
                checkedTrackColor = colors.surfacePillActive,
                uncheckedThumbColor = colors.inkSecondary,
                uncheckedTrackColor = colors.surfacePillSubtle,
            ),
        )
    }
}
