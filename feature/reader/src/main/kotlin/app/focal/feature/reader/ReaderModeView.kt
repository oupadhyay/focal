package app.focal.feature.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.design.AlloCrispDisplay
import app.focal.design.AppChromeTheme
import app.focal.design.FocalActionTile
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalGroupedCard
import app.focal.design.FocalHairlineDivider
import app.focal.design.FocalMenuRow
import app.focal.design.FocalSheetGrabber
import app.focal.design.FocalSwitch
import app.focal.design.FocalTheme
import app.focal.design.ReaderPaperPalette
import app.focal.design.ReaderPaperThemeColors
import app.focal.design.SiteFavicon
import app.focal.design.SwitzerBody
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.api.OriginOverrides
import app.focal.engine.api.PageTranslationPhase
import app.focal.engine.api.PageTranslationStatus
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
 * Full-screen Distraction-Free 5-Theme Reader View (`Obsidian`, `Paper`, `Sepia`, `Alabaster`, `Sage`)
 * with sticky per-domain typography/theme persistence and one-tap Auto-Reader toggle.
 */
@Composable
fun ReaderModeView(
    document: ReadableDocument,
    siteDomain: String = "",
    initialPreferences: ReaderPreferences = ReaderPreferences(),
    autoReaderEnabled: Boolean = false,
    canPersistForDomain: Boolean = true,
    onPreferencesChanged: (ReaderPreferences) -> Unit = {},
    onToggleAutoReader: (Boolean) -> Unit = {},
    onCloseReader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = FocalTheme.typography
    var selectedTheme by remember(siteDomain, initialPreferences.theme.id) {
        mutableStateOf(initialPreferences.theme)
    }
    var useSerif by remember(siteDomain, initialPreferences.useEditorialSerif) {
        mutableStateOf(initialPreferences.useEditorialSerif)
    }
    var fontSizeSp by remember(siteDomain, initialPreferences.fontSizeSp) {
        mutableIntStateOf(ReaderSitePreferencesResolver.clampFontSizeSp(initialPreferences.fontSizeSp))
    }
    var lineHeightRatio by remember(siteDomain, initialPreferences.lineHeightMultiplier) {
        mutableFloatStateOf(
            ReaderSitePreferencesResolver.clampLineHeight(initialPreferences.lineHeightMultiplier),
        )
    }

    val emitUpdatedPreferences: (
        theme: ReaderPaperThemeColors,
        serif: Boolean,
        sizeSp: Int,
        lineHeight: Float,
    ) -> Unit = { theme, serif, sizeSp, lineHeight ->
        selectedTheme = theme
        useSerif = serif
        fontSizeSp = sizeSp
        lineHeightRatio = lineHeight
        onPreferencesChanged(
            ReaderPreferences(
                theme = theme,
                useEditorialSerif = serif,
                fontSizeSp = sizeSp,
                lineHeightMultiplier = lineHeight,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(selectedTheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .navigationBarsPadding(),
    ) {
        // Top Reader Appearance Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${siteDomain.ifBlank { document.siteName ?: "Reader" }} · ${document.estimatedReadMinutes} min",
                    style = typography.captionMetadata,
                    color = selectedTheme.secondaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canPersistForDomain && siteDomain.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (autoReaderEnabled) {
                                        selectedTheme.primaryInk
                                    } else {
                                        selectedTheme.primaryInk.copy(alpha = 0.08f)
                                    },
                                )
                                .clickable { onToggleAutoReader(!autoReaderEnabled) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = FocalGlyphs.Reader,
                                contentDescription = "Always use Reader on $siteDomain",
                                tint = if (autoReaderEnabled) selectedTheme.background else selectedTheme.primaryInk,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = if (autoReaderEnabled) "Always On" else "Always",
                                style = typography.controlPillLabel,
                                color = if (autoReaderEnabled) selectedTheme.background else selectedTheme.primaryInk,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(selectedTheme.primaryInk.copy(alpha = 0.08f))
                            .clickable(onClick = onCloseReader)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = "Done",
                            style = typography.controlPillLabel,
                            color = selectedTheme.primaryInk,
                        )
                    }
                }
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
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(swatch.background)
                                .border(
                                    width = if (active) 2.dp else 0.75.dp,
                                    color = if (active) selectedTheme.primaryInk else selectedTheme.divider,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    emitUpdatedPreferences(swatch, useSerif, fontSizeSp, lineHeightRatio)
                                },
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderControlChip(
                        label = if (useSerif) "Serif" else "Sans",
                        theme = selectedTheme,
                        onClick = {
                            emitUpdatedPreferences(selectedTheme, !useSerif, fontSizeSp, lineHeightRatio)
                        },
                    )
                    ReaderControlChip(
                        label = "A−",
                        theme = selectedTheme,
                        onClick = {
                            val nextSize = ReaderSitePreferencesResolver.clampFontSizeSp(fontSizeSp - 1)
                            emitUpdatedPreferences(selectedTheme, useSerif, nextSize, lineHeightRatio)
                        },
                    )
                    ReaderControlChip(
                        label = "A+",
                        theme = selectedTheme,
                        onClick = {
                            val nextSize = ReaderSitePreferencesResolver.clampFontSizeSp(fontSizeSp + 1)
                            emitUpdatedPreferences(selectedTheme, useSerif, nextSize, lineHeightRatio)
                        },
                    )
                    ReaderControlChip(
                        label = String.format("%.2f×", lineHeightRatio),
                        theme = selectedTheme,
                        onClick = {
                            val nextRatio = when {
                                lineHeightRatio < 1.50f -> 1.60f
                                lineHeightRatio < 1.70f -> 1.75f
                                else -> 1.45f
                            }
                            emitUpdatedPreferences(selectedTheme, useSerif, fontSizeSp, nextRatio)
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(selectedTheme.divider),
        )

        val bodyFontFamily = if (useSerif) FontFamily.Serif else SwitzerBody
        val lineHeightSp = (fontSizeSp * lineHeightRatio).sp

        // Article Body (virtualized LazyColumn for long-form articles)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 22.dp,
                bottom = 54.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "reader_title", contentType = "article_title") {
                Text(
                    text = document.title,
                    fontFamily = AlloCrispDisplay,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    color = selectedTheme.primaryInk,
                )
            }

            val byline = document.byline
            if (!byline.isNullOrBlank()) {
                item(key = "reader_byline", contentType = "article_byline") {
                    Text(
                        text = byline,
                        style = typography.captionMetadata,
                        color = selectedTheme.secondaryInk,
                    )
                }
            }

            itemsIndexed(
                items = document.paragraphs,
                key = { index, _ -> "reader_paragraph_$index" },
                contentType = { _, _ -> "article_paragraph" },
            ) { _, paragraph ->
                Text(
                    text = paragraph,
                    fontFamily = bodyFontFamily,
                    fontSize = fontSizeSp.sp,
                    lineHeight = lineHeightSp,
                    color = selectedTheme.primaryInk,
                )
            }
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
            .background(theme.primaryInk.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
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
 * Screen 08 · Refined Lens/YAB-grade Page Menu & Live Per-Site Settings Smoked Glass Sheet.
 *
 * Uses concentric `32.dp` outer glass, borderless tonal `FocalGroupedCard` groups with `0.5.dp`
 * inset dividers, bespoke `FocalGlyphs` vector icons, and spring-animated `FocalSwitch` toggles.
 */
@Composable
fun PageMenuAndSiteOverridesSheet(
    domain: String,
    fullUrl: String,
    favicon: Bitmap? = null,
    isPrivateAperture: Boolean = false,
    canGoBack: Boolean,
    canGoForward: Boolean,
    blockedCountInTab: Long,
    overrides: OriginOverrides,
    appTheme: AppChromeTheme = AppChromeTheme.DARK,
    translationStatus: PageTranslationStatus = PageTranslationStatus(),
    isCurrentPageBookmarked: Boolean = false,
    initialTab: PageMenuTab = PageMenuTab.MENU,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onReload: () -> Unit,
    onCopyCleanLink: () -> Unit,
    onShareCleanLink: () -> Unit,
    onSaveBookmark: () -> Unit,
    onOpenBookmarks: () -> Unit = {},
    onOpenReaderMode: () -> Unit,
    onTranslatePage: () -> Unit = {},
    onFindInPage: () -> Unit = {},
    onToggleElementZapper: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
    onResetHiddenElements: () -> Unit = {},
    onUpdateSiteOverrides: (OriginOverrides) -> Unit,
    onUpdateAppTheme: (AppChromeTheme) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val sheetGlassTint = rememberUpdatedState(colors.effectiveSheetGlass)
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }

    val cleanUrl = fullUrl.takeIf { it.isNotBlank() && it != "about:blank" }.orEmpty()
    val hasActivePage = domain.isNotBlank() || cleanUrl.isNotBlank()
    val headerLabel = when {
        domain.isNotBlank() -> domain
        cleanUrl.isNotBlank() -> cleanUrl
        else -> "Start Page"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(onClick = onDismiss)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = FocalGeometry.SheetOuterShape,
            glassTintState = sheetGlassTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .focalSheetEntrance(riseDp = 18.dp)
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
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FocalSheetGrabber(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                // Borderless Tonal Address & Navigation Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(FocalGeometry.TileShape)
                        .background(colors.surfacePillSubtle)
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        HeaderIconButton(
                            icon = FocalGlyphs.ArrowLeft,
                            contentDescription = "Back",
                            enabled = canGoBack,
                            onClick = onGoBack,
                        )
                        HeaderIconButton(
                            icon = FocalGlyphs.ArrowRight,
                            contentDescription = "Forward",
                            enabled = canGoForward,
                            onClick = onGoForward,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (hasActivePage) {
                            SiteFavicon(
                                urlOrDomain = cleanUrl.ifBlank { domain },
                                liveBitmap = favicon,
                                allowNetworkFetch = !isPrivateAperture,
                                size = 16.dp,
                                cornerRadius = 4.dp,
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(
                            text = headerLabel,
                            style = typography.urlDomainPill,
                            color = if (hasActivePage) colors.inkPrimary else colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        HeaderIconButton(
                            icon = FocalGlyphs.Reload,
                            contentDescription = "Reload",
                            enabled = hasActivePage,
                            onClick = onReload,
                        )
                    }
                }

                // Sleek Capsule Segmented Switcher (`Actions` · `Site Settings`)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(colors.surfacePillSubtle)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        PageMenuTab.MENU to "Actions",
                        PageMenuTab.SITE_OVERRIDES to "Site Settings",
                    ).forEach { (tab, title) ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(if (selected) colors.surfacePillActive else Color.Transparent)
                                .clickable { activeTab = tab }
                                .padding(vertical = 8.dp),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    transitionSpec = {
                        val toSettings = targetState == PageMenuTab.SITE_OVERRIDES
                        val slideDistanceDivisor = 10
                        (
                            androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = 160,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
                            ) + androidx.compose.animation.slideInHorizontally(
                                animationSpec = app.focal.design.FocalMotion.menuSubtabSpring(),
                                initialOffsetX = { fullWidth ->
                                    if (toSettings) fullWidth / slideDistanceDivisor else -fullWidth / slideDistanceDivisor
                                },
                            )
                            ) togetherWith (
                            androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = 110,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
                            ) + androidx.compose.animation.slideOutHorizontally(
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = 130,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
                                targetOffsetX = { fullWidth ->
                                    if (toSettings) -fullWidth / slideDistanceDivisor else fullWidth / slideDistanceDivisor
                                },
                            )
                            ) using androidx.compose.animation.SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ -> app.focal.design.FocalMotion.menuSubtabSpring() },
                        )
                    },
                    label = "FocalPageMenuSubtab",
                ) { tab ->
                    if (tab == PageMenuTab.MENU) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 3 Borderless Tonal Quick Action Squircles (Share · Copy · Bookmark)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FocalActionTile(
                                    label = "Share",
                                    icon = FocalGlyphs.Share,
                                    enabled = hasActivePage,
                                    onClick = onShareCleanLink,
                                    modifier = Modifier.weight(1f),
                                )
                                FocalActionTile(
                                    label = "Copy",
                                    icon = FocalGlyphs.Copy,
                                    enabled = hasActivePage,
                                    onClick = onCopyCleanLink,
                                    modifier = Modifier.weight(1f),
                                )
                                FocalActionTile(
                                    label = if (isCurrentPageBookmarked) "Saved" else "Bookmark",
                                    icon = FocalGlyphs.Bookmark,
                                    enabled = hasActivePage,
                                    onClick = onSaveBookmark,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            // Primary Grouped Card with 0.5dp Inset Dividers (YAB / Lens style)
                            FocalGroupedCard {
                                FocalMenuRow(
                                    title = "Bookmarks",
                                    icon = FocalGlyphs.Bookmark,
                                    enabled = true,
                                    showChevron = true,
                                    onClick = onOpenBookmarks,
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Reader Mode",
                                    icon = FocalGlyphs.Reader,
                                    enabled = hasActivePage,
                                    showChevron = true,
                                    onClick = onOpenReaderMode,
                                )
                                FocalHairlineDivider()
                                val translateRowTitle = when (translationStatus.phase) {
                                    PageTranslationPhase.ORIGINAL -> "Translate Page"
                                    PageTranslationPhase.DOWNLOADING_MODEL -> "Downloading Language…"
                                    PageTranslationPhase.TRANSLATING -> "Translating Page…"
                                    PageTranslationPhase.TRANSLATED -> "Show Original"
                                    PageTranslationPhase.FAILED -> "Translate Page"
                                }
                                val translateTrailing = when (translationStatus.phase) {
                                    PageTranslationPhase.ORIGINAL -> null
                                    PageTranslationPhase.DOWNLOADING_MODEL -> "Cancel"
                                    PageTranslationPhase.TRANSLATING -> "Cancel"
                                    PageTranslationPhase.TRANSLATED -> "Translated"
                                    PageTranslationPhase.FAILED -> "Retry"
                                }
                                FocalMenuRow(
                                    title = translateRowTitle,
                                    icon = FocalGlyphs.Translate,
                                    trailingLabel = translateTrailing,
                                    enabled = hasActivePage,
                                    showChevron = translationStatus.phase == PageTranslationPhase.ORIGINAL,
                                    onClick = onTranslatePage,
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Desktop Site",
                                    icon = FocalGlyphs.Desktop,
                                    onClick = {
                                        onUpdateSiteOverrides(
                                            overrides.copy(desktopSite = !overrides.desktopSite),
                                        )
                                    },
                                    trailingContent = {
                                        FocalSwitch(
                                            checked = overrides.desktopSite,
                                            onCheckedChange = {
                                                onUpdateSiteOverrides(overrides.copy(desktopSite = it))
                                            },
                                        )
                                    },
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Site Settings",
                                    icon = FocalGlyphs.Shield,
                                    trailingLabel = if (blockedCountInTab > 0) {
                                        "$blockedCountInTab blocked"
                                    } else {
                                        null
                                    },
                                    showChevron = true,
                                    onClick = { activeTab = PageMenuTab.SITE_OVERRIDES },
                                )
                            }

                            // Utility & Browser Tools Card
                            FocalGroupedCard {
                                FocalMenuRow(
                                    title = "Find in Page",
                                    icon = FocalGlyphs.Search,
                                    enabled = hasActivePage,
                                    showChevron = true,
                                    onClick = onFindInPage,
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Hide Elements",
                                    icon = FocalGlyphs.EyeOff,
                                    trailingLabel = if (overrides.hiddenCssSelectors.isNotEmpty()) {
                                        "${overrides.hiddenCssSelectors.size} hidden"
                                    } else {
                                        null
                                    },
                                    enabled = hasActivePage,
                                    showChevron = true,
                                    onClick = onToggleElementZapper,
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "History",
                                    icon = FocalGlyphs.Clock,
                                    enabled = true,
                                    showChevron = true,
                                    onClick = onOpenHistory,
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Downloads",
                                    icon = FocalGlyphs.Download,
                                    enabled = true,
                                    showChevron = true,
                                    onClick = onOpenDownloads,
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Settings",
                                    icon = FocalGlyphs.Sliders,
                                    enabled = true,
                                    showChevron = true,
                                    onClick = onOpenGlobalSettings,
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Grouped Live Per-Site Settings Card (Zero Jargon Subtitles, Sleek Custom Switches)
                            FocalGroupedCard {
                                FocalMenuRow(
                                    title = "Block Ads & Trackers",
                                    icon = FocalGlyphs.Shield,
                                    trailingLabel = if (blockedCountInTab > 0) {
                                        "$blockedCountInTab"
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        onUpdateSiteOverrides(
                                            overrides.copy(blockAdsAndTrackers = !overrides.blockAdsAndTrackers),
                                        )
                                    },
                                    trailingContent = {
                                        FocalSwitch(
                                            checked = overrides.blockAdsAndTrackers,
                                            onCheckedChange = {
                                                onUpdateSiteOverrides(overrides.copy(blockAdsAndTrackers = it))
                                            },
                                        )
                                    },
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Dark Web Pages",
                                    icon = FocalGlyphs.Moon,
                                    onClick = {
                                        onUpdateSiteOverrides(
                                            overrides.copy(forceDarkWebSurface = !overrides.forceDarkWebSurface),
                                        )
                                    },
                                    trailingContent = {
                                        FocalSwitch(
                                            checked = overrides.forceDarkWebSurface,
                                            onCheckedChange = {
                                                onUpdateSiteOverrides(overrides.copy(forceDarkWebSurface = it))
                                            },
                                        )
                                    },
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Editorial Fonts",
                                    icon = FocalGlyphs.Typography,
                                    onClick = {
                                        onUpdateSiteOverrides(
                                            overrides.copy(overridePublisherFonts = !overrides.overridePublisherFonts),
                                        )
                                    },
                                    trailingContent = {
                                        FocalSwitch(
                                            checked = overrides.overridePublisherFonts,
                                            onCheckedChange = {
                                                onUpdateSiteOverrides(overrides.copy(overridePublisherFonts = it))
                                            },
                                        )
                                    },
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Auto Reader Mode",
                                    icon = FocalGlyphs.Reader,
                                    onClick = {
                                        onUpdateSiteOverrides(
                                            overrides.copy(autoReaderMode = !overrides.autoReaderMode),
                                        )
                                    },
                                    trailingContent = {
                                        FocalSwitch(
                                            checked = overrides.autoReaderMode,
                                            onCheckedChange = {
                                                onUpdateSiteOverrides(overrides.copy(autoReaderMode = it))
                                            },
                                        )
                                    },
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "JavaScript",
                                    icon = FocalGlyphs.Code,
                                    onClick = {
                                        onUpdateSiteOverrides(
                                            overrides.copy(javascriptEnabled = !overrides.javascriptEnabled),
                                        )
                                    },
                                    trailingContent = {
                                        FocalSwitch(
                                            checked = overrides.javascriptEnabled,
                                            onCheckedChange = {
                                                onUpdateSiteOverrides(overrides.copy(javascriptEnabled = it))
                                            },
                                        )
                                    },
                                )
                                FocalHairlineDivider()
                                FocalMenuRow(
                                    title = "Page Zoom",
                                    icon = FocalGlyphs.Search,
                                    trailingContent = {
                                        Row(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(colors.inkPrimary.copy(alpha = 0.08f))
                                                .padding(horizontal = 4.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        val next = (overrides.pageZoomPercent - 10).coerceIn(85, 150)
                                                        onUpdateSiteOverrides(overrides.copy(pageZoomPercent = next))
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = FocalGlyphs.Minus,
                                                    contentDescription = "Zoom out",
                                                    tint = colors.inkPrimary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                            Text(
                                                text = "${overrides.pageZoomPercent}%",
                                                style = typography.tabBadgeNumeral,
                                                color = colors.inkPrimary,
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        val next = (overrides.pageZoomPercent + 10).coerceIn(85, 150)
                                                        onUpdateSiteOverrides(overrides.copy(pageZoomPercent = next))
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = FocalGlyphs.Plus,
                                                    contentDescription = "Zoom in",
                                                    tint = colors.inkPrimary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                                if (overrides.hiddenCssSelectors.isNotEmpty()) {
                                    FocalHairlineDivider()
                                    FocalMenuRow(
                                        title = "Hidden Elements",
                                        icon = FocalGlyphs.EyeOff,
                                        trailingContent = {
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(colors.inkPrimary.copy(alpha = 0.08f))
                                                    .clickable(onClick = onResetHiddenElements)
                                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = "Reset (${overrides.hiddenCssSelectors.size})",
                                                    style = typography.controlPillLabel,
                                                    color = colors.inkPrimary,
                                                )
                                            }
                                        },
                                    )
                                }
                            }

                            // Global App Chrome Theme Card (Tab Switcher, Bookmarks & Menus — defaults to Dark)
                            FocalGroupedCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier.size(20.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = FocalGlyphs.Moon,
                                                contentDescription = null,
                                                tint = colors.inkSecondary,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            text = "App Theme",
                                            style = typography.bodyCompact.copy(
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 15.sp,
                                                lineHeight = 20.sp,
                                            ),
                                            color = colors.inkPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = "Tabs & menus",
                                            style = typography.captionMetadata,
                                            color = colors.inkSecondary,
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(CircleShape)
                                            .background(colors.surfacePillSubtle)
                                            .padding(3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AppChromeTheme.entries.forEach { option ->
                                            val selected = appTheme == option
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (selected) colors.surfacePillActive else Color.Transparent,
                                                    )
                                                    .clickable { onUpdateAppTheme(option) }
                                                    .padding(vertical = 7.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = option.label,
                                                    style = typography.controlPillLabel,
                                                    color = if (selected) {
                                                        colors.inkOnPillActive
                                                    } else {
                                                        colors.inkSecondary
                                                    },
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
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = FocalTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.inkPrimary else colors.inkFaint,
            modifier = Modifier.size(17.dp),
        )
    }
}
