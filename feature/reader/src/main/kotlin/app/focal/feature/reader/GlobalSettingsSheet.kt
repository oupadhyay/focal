package app.focal.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.design.AppChromeTheme
import app.focal.design.FocalBrowserSettings
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalGroupedCard
import app.focal.design.FocalHairlineDivider
import app.focal.design.FocalMenuRow
import app.focal.design.FocalMotion
import app.focal.design.FocalSheetGrabber
import app.focal.design.FocalSwitch
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseOwner
import app.focal.design.GestureLeaseToken
import app.focal.design.LocalGestureLeaseController
import app.focal.design.SearchEnginePreset
import app.focal.design.consumeClicks
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Decoupled UI state for Focal's native in-app GitHub Releases self-updater.
 */
@Immutable
sealed interface UpdateBannerState {
    data object Idle : UpdateBannerState
    data object Checking : UpdateBannerState
    data class UpToDate(val currentVersion: String) : UpdateBannerState
    data class Available(
        val tagName: String,
        val releaseName: String,
        val notes: String,
        val sizeBytes: Long = 0L,
        val isDownloading: Boolean = false,
    ) : UpdateBannerState
    data class Downloading(
        val tagName: String,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateBannerState
    data class Verifying(val tagName: String) : UpdateBannerState
    data class PermissionRequired(val tagName: String) : UpdateBannerState
    data class Installing(val tagName: String) : UpdateBannerState
    data class Error(val message: String) : UpdateBannerState
}

/**
 * Global Browser Settings Smoked Glass Sheet.
 *
 * Groups Search Engine presets & custom URL template, Appearance & Chrome controls,
 * Privacy & Security toggles (GPC, Private Aperture biometric lock, Clear Browsing Data),
 * and native in-app GitHub Releases self-updates inside borderless [FocalGroupedCard] containers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSettingsSheet(
    settings: FocalBrowserSettings,
    appTheme: AppChromeTheme,
    currentVersionName: String,
    totalBlockedTrackers: Long = 0L,
    biometricAvailable: Boolean = true,
    updateState: UpdateBannerState = UpdateBannerState.Idle,
    onUpdateSettings: (FocalBrowserSettings) -> Unit,
    onUpdateAppTheme: (AppChromeTheme) -> Unit,
    onClearBrowsingData: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCancelUpdate: () -> Unit = {},
    onGrantInstallPermission: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val focusManager = LocalFocusManager.current
    val gestureLease = LocalGestureLeaseController.current
    val sheetGlassTint = rememberUpdatedState(colors.effectiveSheetGlass)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    var internalDismissDragPx by remember { mutableFloatStateOf(0f) }
    val dismissSettleScope = rememberCoroutineScope()
    val dismissSettleAnim = remember { Animatable(0f) }

    var clearedGeneration by remember { mutableIntStateOf(0) }
    var showClearedConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(clearedGeneration) {
        if (clearedGeneration > 0) {
            showClearedConfirmation = true
            delay(2_000L)
            showClearedConfirmation = false
        }
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
            glassTintState = sheetGlassTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding()
                .testTag("global_settings_sheet")
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
                                val pendingSettle = dismissSettleAnim.value
                                if (pendingSettle > 0f) {
                                    internalDismissDragPx += pendingSettle
                                    dismissSettleScope.launch { dismissSettleAnim.snapTo(0f) }
                                }
                            },
                            onDragEnd = {
                                val stillValid = gestureLease == null || gestureLease.isValid(dragLeaseToken)
                                gestureLease?.release(dragLeaseToken)
                                dragLeaseToken = null
                                val dragged = internalDismissDragPx
                                internalDismissDragPx = 0f
                                if (stillValid && dragged > 120.dp.toPx()) {
                                    latestOnDismiss()
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

                // Header Row (`Settings` · `Done`)
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
                            imageVector = FocalGlyphs.Sliders,
                            contentDescription = null,
                            tint = colors.inkPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Settings",
                            style = typography.sheetTitle,
                            color = colors.inkPrimary,
                        )
                    }

                    SettingsPillButton(
                        label = "Done",
                        active = false,
                        onClick = onDismiss,
                    )
                }

                // Scrollable Borderless Grouped Settings Sections
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 1. Search Engine
                    SettingsSectionGroup(title = "Search Engine") {
                        FocalGroupedCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    SearchEnginePreset.entries.forEach { preset ->
                                        val selected = settings.searchEngine == preset
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(
                                                    if (selected) {
                                                        colors.surfacePillActive
                                                    } else {
                                                        colors.inkPrimary.copy(alpha = 0.07f)
                                                    },
                                                )
                                                .clickable {
                                                    onUpdateSettings(settings.copy(searchEngine = preset))
                                                }
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = preset.displayName,
                                                style = typography.controlPillLabel,
                                                color = if (selected) {
                                                    colors.inkOnPillActive
                                                } else {
                                                    colors.inkPrimary
                                                },
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }

                                if (settings.searchEngine == SearchEnginePreset.CUSTOM) {
                                    val template = settings.customSearchTemplate
                                    val isValidTemplate = FocalBrowserSettings.isValidCustomTemplate(template)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(colors.canvasBackground.copy(alpha = 0.42f))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            if (template.isEmpty()) {
                                                Text(
                                                    text = "https://example.com/search?q=%s",
                                                    style = typography.bodyCompact,
                                                    color = colors.inkFaint,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            BasicTextField(
                                                value = template,
                                                onValueChange = { updated ->
                                                    onUpdateSettings(settings.copy(customSearchTemplate = updated))
                                                },
                                                singleLine = true,
                                                textStyle = typography.bodyCompact.copy(color = colors.inkPrimary),
                                                cursorBrush = SolidColor(colors.inkPrimary),
                                                keyboardOptions = KeyboardOptions(
                                                    capitalization = KeyboardCapitalization.None,
                                                    autoCorrectEnabled = false,
                                                    keyboardType = KeyboardType.Uri,
                                                    imeAction = ImeAction.Done,
                                                ),
                                                keyboardActions = KeyboardActions(
                                                    onDone = { focusManager.clearFocus() },
                                                ),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }

                                        if (template.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(colors.surfacePillSubtle)
                                                    .clickable {
                                                        onUpdateSettings(settings.copy(customSearchTemplate = ""))
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = FocalGlyphs.Close,
                                                    contentDescription = "Clear custom template",
                                                    tint = colors.inkSecondary,
                                                    modifier = Modifier.size(11.dp),
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (isValidTemplate) {
                                            Icon(
                                                imageVector = FocalGlyphs.Check,
                                                contentDescription = null,
                                                tint = colors.inkPrimary,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                text = "Valid search template",
                                                style = typography.captionMetadata,
                                                color = colors.inkPrimary,
                                            )
                                        } else {
                                            Text(
                                                text = if (template.isBlank()) {
                                                    "Use https://… and include %s where your query goes"
                                                } else {
                                                    "Must start with http(s)://, contain %s, and have no spaces"
                                                },
                                                style = typography.captionMetadata.copy(
                                                    fontWeight = if (template.isBlank()) {
                                                        FontWeight.Normal
                                                    } else {
                                                        FontWeight.Medium
                                                    },
                                                ),
                                                color = colors.inkSecondary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Appearance & Chrome
                    SettingsSectionGroup(title = "Appearance & Chrome") {
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

                            FocalHairlineDivider()

                            FocalMenuRow(
                                title = "Hide Bar on Scroll",
                                icon = FocalGlyphs.ChevronDown,
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(
                                            fullHideBottomBarOnScroll = !settings.fullHideBottomBarOnScroll,
                                        ),
                                    )
                                },
                                trailingContent = {
                                    FocalSwitch(
                                        checked = settings.fullHideBottomBarOnScroll,
                                        onCheckedChange = { checked ->
                                            onUpdateSettings(settings.copy(fullHideBottomBarOnScroll = checked))
                                        },
                                    )
                                },
                            )
                        }
                    }

                    // 3. Privacy & Security
                    SettingsSectionGroup(title = "Privacy & Security") {
                        FocalGroupedCard {
                            FocalMenuRow(
                                title = "Global Privacy Control",
                                subtitle = "Sec-GPC: 1",
                                icon = FocalGlyphs.Shield,
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(
                                            globalPrivacyControlEnabled = !settings.globalPrivacyControlEnabled,
                                        ),
                                    )
                                },
                                trailingContent = {
                                    FocalSwitch(
                                        checked = settings.globalPrivacyControlEnabled,
                                        onCheckedChange = { checked ->
                                            onUpdateSettings(settings.copy(globalPrivacyControlEnabled = checked))
                                        },
                                    )
                                },
                            )

                            FocalHairlineDivider()

                            FocalMenuRow(
                                title = "Lock Private Aperture",
                                subtitle = if (biometricAvailable) {
                                    null
                                } else {
                                    "Requires device lock or biometrics"
                                },
                                icon = FocalGlyphs.Fingerprint,
                                enabled = biometricAvailable,
                                onClick = if (biometricAvailable) {
                                    {
                                        onUpdateSettings(
                                            settings.copy(
                                                biometricPrivateLockEnabled = !settings.biometricPrivateLockEnabled,
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                                trailingContent = {
                                    FocalSwitch(
                                        checked = biometricAvailable && settings.biometricPrivateLockEnabled,
                                        onCheckedChange = { checked ->
                                            if (biometricAvailable) {
                                                onUpdateSettings(
                                                    settings.copy(biometricPrivateLockEnabled = checked),
                                                )
                                            }
                                        },
                                        modifier = Modifier.alpha(if (biometricAvailable) 1f else 0.42f),
                                    )
                                },
                            )

                            FocalHairlineDivider()

                            FocalMenuRow(
                                title = "Clear Browsing Data",
                                icon = FocalGlyphs.Trash,
                                showChevron = !showClearedConfirmation,
                                onClick = {
                                    onClearBrowsingData()
                                    clearedGeneration += 1
                                },
                                trailingContent = if (showClearedConfirmation) {
                                    {
                                        Row(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(colors.inkPrimary.copy(alpha = 0.08f))
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = FocalGlyphs.Check,
                                                contentDescription = null,
                                                tint = colors.inkPrimary,
                                                modifier = Modifier.size(12.dp),
                                            )
                                            Text(
                                                text = "Cleared",
                                                style = typography.controlPillLabel,
                                                color = colors.inkPrimary,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    // 4. Focal Updates
                    SettingsSectionGroup(title = "Focal Updates") {
                        FocalGroupedCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Icon(
                                                imageVector = FocalGlyphs.Aperture,
                                                contentDescription = null,
                                                tint = colors.inkSecondary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = "Focal v$currentVersionName",
                                                style = typography.bodyCompact.copy(
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 15.sp,
                                                    lineHeight = 20.sp,
                                                ),
                                                color = colors.inkPrimary,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (updateState is UpdateBannerState.UpToDate) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    modifier = Modifier
                                                        .weight(1f, fill = false)
                                                        .testTag("settings_update_up_to_date_inline"),
                                                ) {
                                                    Text(
                                                        text = "·",
                                                        style = typography.captionMetadata,
                                                        color = colors.inkSecondary.copy(alpha = 0.6f),
                                                        maxLines = 1,
                                                    )
                                                    Icon(
                                                        imageVector = FocalGlyphs.Check,
                                                        contentDescription = null,
                                                        tint = colors.inkSecondary,
                                                        modifier = Modifier.size(13.dp),
                                                    )
                                                    Text(
                                                        text = "Up to date",
                                                        style = typography.captionMetadata.copy(
                                                            fontWeight = FontWeight.Medium,
                                                        ),
                                                        color = colors.inkSecondary,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                        if (totalBlockedTrackers > 0L) {
                                            val formattedCount = remember(totalBlockedTrackers) {
                                                String.format(Locale.getDefault(), "%,d", totalBlockedTrackers)
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                Icon(
                                                    imageVector = FocalGlyphs.Shield,
                                                    contentDescription = null,
                                                    tint = colors.inkSecondary,
                                                    modifier = Modifier.size(12.dp),
                                                )
                                                Text(
                                                    text = if (totalBlockedTrackers == 1L) {
                                                        "1 tracker blocked"
                                                    } else {
                                                        "$formattedCount trackers blocked"
                                                    },
                                                    style = typography.captionMetadata,
                                                    color = colors.inkSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }

                                    when (updateState) {
                                        UpdateBannerState.Idle -> {
                                            Spacer(Modifier.width(8.dp))
                                            SettingsPillButton(
                                                label = "Check for Updates",
                                                active = false,
                                                onClick = onCheckForUpdates,
                                            )
                                        }

                                        UpdateBannerState.Checking -> {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Checking GitHub…",
                                                style = typography.controlPillLabel,
                                                color = colors.inkSecondary,
                                            )
                                        }

                                        is UpdateBannerState.UpToDate -> {
                                            Spacer(Modifier.width(8.dp))
                                            SettingsPillButton(
                                                label = "Check Again",
                                                active = false,
                                                onClick = onCheckForUpdates,
                                            )
                                        }

                                        is UpdateBannerState.Available,
                                        is UpdateBannerState.Downloading,
                                        is UpdateBannerState.Verifying,
                                        is UpdateBannerState.PermissionRequired,
                                        is UpdateBannerState.Installing,
                                        is UpdateBannerState.Error,
                                        -> Unit
                                    }
                                }

                                when (updateState) {
                                    UpdateBannerState.Idle,
                                    UpdateBannerState.Checking,
                                    is UpdateBannerState.UpToDate,
                                    -> Unit

                                    is UpdateBannerState.Available -> {
                                        FocalHairlineDivider(insetStart = 0.dp)
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = "Update ${updateState.tagName}",
                                                    style = typography.cardTitle,
                                                    color = colors.inkPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                val metaLabel = remember(updateState.releaseName, updateState.tagName, updateState.sizeBytes) {
                                                    when {
                                                        updateState.sizeBytes > 0L -> String.format(
                                                            Locale.US,
                                                            "%.1f MB",
                                                            updateState.sizeBytes / (1024.0 * 1024.0),
                                                        )
                                                        updateState.releaseName.isNotBlank() &&
                                                            !updateState.releaseName.equals(updateState.tagName, ignoreCase = true) -> {
                                                            updateState.releaseName
                                                        }
                                                        else -> ""
                                                    }
                                                }
                                                if (metaLabel.isNotBlank()) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = metaLabel,
                                                        style = typography.captionMetadata,
                                                        color = colors.inkSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }

                                            val cleanNotes = remember(updateState.notes) {
                                                updateState.notes
                                                    .lineSequence()
                                                    .map { it.trim() }
                                                    .filter { it.isNotEmpty() }
                                                    .joinToString(" · ")
                                            }
                                            if (cleanNotes.isNotBlank()) {
                                                Text(
                                                    text = cleanNotes,
                                                    style = typography.captionMetadata,
                                                    color = colors.inkSecondary,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                SettingsPillButton(
                                                    label = if (updateState.isDownloading) {
                                                        "Downloading…"
                                                    } else {
                                                        "Update Now"
                                                    },
                                                    active = true,
                                                    enabled = !updateState.isDownloading,
                                                    onClick = onInstallUpdate,
                                                )
                                            }
                                        }
                                    }

                                    is UpdateBannerState.Downloading -> {
                                        FocalHairlineDivider(insetStart = 0.dp)
                                        val clampedProgress = updateState.progress.coerceIn(0f, 1f)
                                        val percent = (clampedProgress * 100f).toInt()
                                        val progressLabel = remember(updateState.downloadedBytes, updateState.totalBytes, percent) {
                                            val dlMb = updateState.downloadedBytes / (1024.0 * 1024.0)
                                            if (updateState.totalBytes > 0L) {
                                                val totalMb = updateState.totalBytes / (1024.0 * 1024.0)
                                                String.format(Locale.US, "%.1f / %.1f MB · %d%%", dlMb, totalMb, percent)
                                            } else {
                                                String.format(Locale.US, "%.1f MB", dlMb)
                                            }
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("settings_update_downloading"),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    Text(
                                                        text = "Downloading ${updateState.tagName}",
                                                        style = typography.cardTitle,
                                                        color = colors.inkPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = progressLabel,
                                                        style = typography.captionMetadata,
                                                        color = colors.inkSecondary,
                                                        maxLines = 1,
                                                    )
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                SettingsPillButton(
                                                    label = "Cancel",
                                                    active = false,
                                                    onClick = onCancelUpdate,
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(5.dp)
                                                    .clip(CircleShape)
                                                    .background(colors.surfacePillSubtle),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(clampedProgress.coerceAtLeast(0.03f))
                                                        .fillMaxHeight()
                                                        .clip(CircleShape)
                                                        .background(colors.inkPrimary),
                                                )
                                            }
                                        }
                                    }

                                    is UpdateBannerState.Verifying -> {
                                        FocalHairlineDivider(insetStart = 0.dp)
                                        Text(
                                            text = "Verifying ${updateState.tagName} signature & SHA-256…",
                                            style = typography.captionMetadata.copy(fontWeight = FontWeight.Medium),
                                            color = colors.inkSecondary,
                                        )
                                    }

                                    is UpdateBannerState.PermissionRequired -> {
                                        FocalHairlineDivider(insetStart = 0.dp)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("settings_update_permission_required"),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "Allow Focal to install updates once in Android Settings",
                                                style = typography.captionMetadata,
                                                color = colors.inkSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            SettingsPillButton(
                                                label = "Allow Installs",
                                                active = true,
                                                onClick = onGrantInstallPermission,
                                            )
                                        }
                                    }

                                    is UpdateBannerState.Installing -> {
                                        FocalHairlineDivider(insetStart = 0.dp)
                                        Text(
                                            text = "Installing ${updateState.tagName}…",
                                            style = typography.captionMetadata.copy(fontWeight = FontWeight.Medium),
                                            color = colors.inkPrimary,
                                        )
                                    }

                                    is UpdateBannerState.Error -> {
                                        FocalHairlineDivider(insetStart = 0.dp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = updateState.message.ifBlank { "Update check failed" },
                                                style = typography.captionMetadata,
                                                color = colors.inkSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            SettingsPillButton(
                                                label = "Retry",
                                                active = false,
                                                onClick = onCheckForUpdates,
                                            )
                                        }
                                    }
                                }

                                FocalHairlineDivider(insetStart = 0.dp)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                onUpdateSettings(
                                                    settings.copy(
                                                        autoCheckUpdatesEnabled = !settings.autoCheckUpdatesEnabled,
                                                    ),
                                                )
                                            },
                                        )
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "Auto-Check for Updates",
                                            style = typography.cardTitle,
                                            color = colors.inkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "Checks GitHub Releases daily",
                                            style = typography.captionMetadata,
                                            color = colors.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    FocalSwitch(
                                        checked = settings.autoCheckUpdatesEnabled,
                                        onCheckedChange = { checked ->
                                            onUpdateSettings(settings.copy(autoCheckUpdatesEnabled = checked))
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

@Composable
private fun SettingsSectionGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = typography.captionMetadata,
            color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}

@Composable
private fun SettingsPillButton(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val bg = when {
        active -> colors.surfacePillActive
        else -> colors.inkPrimary.copy(alpha = 0.08f)
    }
    val textColor = when {
        !enabled && active -> colors.inkOnPillActive.copy(alpha = 0.65f)
        active -> colors.inkOnPillActive
        !enabled -> colors.inkFaint
        else -> colors.inkPrimary
    }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.controlPillLabel,
            color = textColor,
            maxLines = 1,
        )
    }
}
