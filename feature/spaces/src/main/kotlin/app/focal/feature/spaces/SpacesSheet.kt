package app.focal.feature.spaces

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.database.BookmarkEntity
import app.focal.database.SpaceEntity
import app.focal.design.FocalFaviconTile
import app.focal.design.FocalGeometry
import app.focal.design.FocalGlyphs
import app.focal.design.FocalGroupedCard
import app.focal.design.FocalHairlineDivider
import app.focal.design.FocalSheetGrabber
import app.focal.design.FocalTheme
import app.focal.design.GestureLeaseOwner
import app.focal.design.GestureLeaseToken
import app.focal.design.LocalGestureLeaseController
import app.focal.design.SiteFavicon
import app.focal.design.SpaceAmbientPalette
import app.focal.design.consumeClicks
import app.focal.design.shaders.SmokedGlassBox
import app.focal.sync.PresenceTabItem
import app.focal.sync.SyncSessionUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class SpacesSheetUiState(
    val spaces: ImmutableList<SpaceEntity>,
    val activeSpaceId: String,
    val pinnedSquircles: ImmutableList<BookmarkEntity>,
    val foldersAndBookmarks: ImmutableList<BookmarkEntity>,
    val syncState: SyncSessionUiState,
)

/**
 * Screen 05 & Screen 09 · Concentric Spaces & Bookmarks Smoked Glass Sheet with Ambient GPU Tinting.
 *
 * - Switching between `Personal` (`#F4725E`), `Work` (`#94A3B8`), and `Reading` (`#4ADE80`)
 *   cross-fades `spaceAmbient` in `260ms` purely in the draw/shader phase while keeping all
 *   foreground controls 100% achromatic.
 * - Uses borderless tonal `FocalFaviconTile`s and grouped `FocalGroupedCard` lists with `0.5.dp`
 *   inset dividers.
 */
@Composable
fun SpacesSheet(
    uiState: SpacesSheetUiState,
    externalDragOffsetPx: FloatState? = null,
    onSelectSpace: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCreateFolder: (spaceId: String, folderName: String) -> Unit,
    onStartE2eePairing: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val clipboard = LocalClipboardManager.current
    val gestureLease = LocalGestureLeaseController.current

    val activeSpace = uiState.spaces.firstOrNull { it.id == uiState.activeSpaceId }
        ?: uiState.spaces.firstOrNull()
    val ambientSpec = SpaceAmbientPalette.fromKeyOrHex(
        keyOrId = activeSpace?.id ?: "personal",
        fallbackHex = activeSpace?.ambientHex,
    )

    val animatedSpaceAmbient = animateColorAsState(
        targetValue = ambientSpec.ambientFor(colors.isDarkGlass),
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "FocalSpaceAmbientUniform",
    )
    val sheetBaseTint = rememberUpdatedState(colors.effectiveSheetGlass)

    var internalDismissDragPx by remember { mutableFloatStateOf(0f) }
    var showPairingModal by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var customFolderName by remember { mutableStateOf("") }
    var expandedFolderId by remember { mutableStateOf<String?>(null) }

    val pinsRow = remember(uiState.pinnedSquircles) {
        uiState.pinnedSquircles.take(4).toImmutableList()
    }
    val rootItems = remember(uiState.foldersAndBookmarks) {
        uiState.foldersAndBookmarks.filter { it.parentFolderId == null }.toImmutableList()
    }
    val desktopTabs = remember(uiState.syncState.desktopPresenceTabs) {
        uiState.syncState.desktopPresenceTabs.toImmutableList()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = FocalGeometry.SheetOuterShape,
            glassTintState = sheetBaseTint,
            borderSpecular = colors.borderSpecular,
            spaceAmbientState = animatedSpaceAmbient,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 10.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = FocalGeometry.SheetOuterShape,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .graphicsLayer {
                    val ext = externalDragOffsetPx?.floatValue ?: 0f
                    translationY = (internalDismissDragPx + ext).coerceAtLeast(0f)
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
                                if (stillValid && internalDismissDragPx > 120.dp.toPx()) {
                                    internalDismissDragPx = 0f
                                    onDismiss()
                                } else {
                                    internalDismissDragPx = 0f
                                }
                            },
                            onDragCancel = {
                                gestureLease?.release(dragLeaseToken)
                                dragLeaseToken = null
                                internalDismissDragPx = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (gestureLease != null && !gestureLease.isValid(dragLeaseToken)) {
                                    internalDismissDragPx = 0f
                                    return@detectVerticalDragGestures
                                }
                                change.consume()
                                internalDismissDragPx = (internalDismissDragPx + dragAmount).coerceAtLeast(0f)
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FocalSheetGrabber(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                // Capsule Space Switcher (Personal · Work · Reading)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(colors.surfacePillSubtle)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    uiState.spaces.forEach { space ->
                        val selected = space.id == uiState.activeSpaceId
                        val dotColor = SpaceAmbientPalette.fromKeyOrHex(space.id, space.ambientHex).dotColor
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(if (selected) colors.surfacePillActive else Color.Transparent)
                                .clickable { onSelectSpace(space.id) }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = space.name,
                                style = typography.controlPillLabel,
                                color = if (selected) colors.inkOnPillActive else colors.inkSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (showPairingModal) {
                    E2eePairingCard(
                        syncState = uiState.syncState,
                        onGenerateNewSession = onStartE2eePairing,
                        onCopyPairingUri = { uri ->
                            clipboard.setText(AnnotatedString(uri))
                        },
                        onBackToSpace = { showPairingModal = false },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // 4-Column Space-Scoped Pinned Squircles
                        if (pinsRow.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(
                                    text = "Pinned",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    pinsRow.forEach { pin ->
                                        FocalFaviconTile(
                                            title = pin.title,
                                            url = pin.url.orEmpty(),
                                            onClick = { pin.url?.let(onOpenUrl) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }

                        // Grouped Saved Folders & Bookmarks Card
                        if (rootItems.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(
                                    text = "Bookmarks",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                FocalGroupedCard {
                                    rootItems.forEachIndexed { index, item ->
                                        if (index > 0) {
                                            FocalHairlineDivider(insetStart = 48.dp)
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (item.isFolder) {
                                                        expandedFolderId =
                                                            if (expandedFolderId == item.id) null else item.id
                                                    } else {
                                                        item.url?.let(onOpenUrl)
                                                    }
                                                }
                                                .padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Row(
                                                    modifier = Modifier.weight(1f),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    if (item.isFolder) {
                                                        Box(
                                                            modifier = Modifier.size(20.dp),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            Icon(
                                                                imageVector = FocalGlyphs.Folder,
                                                                contentDescription = null,
                                                                tint = colors.inkSecondary,
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        }
                                                    } else {
                                                        SiteFavicon(
                                                            urlOrDomain = item.url.orEmpty(),
                                                            size = 20.dp,
                                                            cornerRadius = 5.dp,
                                                        )
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = item.title,
                                                            style = typography.bodyCompact.copy(
                                                                fontWeight = FontWeight.Medium,
                                                                fontSize = 14.5.sp,
                                                            ),
                                                            color = colors.inkPrimary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        val subtitle = item.subtitle ?: item.url
                                                        if (!subtitle.isNullOrBlank()) {
                                                            Text(
                                                                text = subtitle,
                                                                style = typography.captionMetadata,
                                                                color = colors.inkSecondary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Icon(
                                                    imageVector = if (item.isFolder && expandedFolderId == item.id) {
                                                        FocalGlyphs.ChevronDown
                                                    } else {
                                                        FocalGlyphs.ChevronRight
                                                    },
                                                    contentDescription = null,
                                                    tint = colors.inkFaint,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }

                                            // Nested Folder Children
                                            if (item.isFolder && expandedFolderId == item.id) {
                                                val children = uiState.foldersAndBookmarks.filter {
                                                    it.parentFolderId == item.id
                                                }
                                                if (children.isNotEmpty()) {
                                                    Spacer(Modifier.height(6.dp))
                                                    children.forEach { child ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .clickable { child.url?.let(onOpenUrl) }
                                                                .padding(start = 32.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        ) {
                                                            SiteFavicon(
                                                                urlOrDomain = child.url.orEmpty(),
                                                                size = 15.dp,
                                                                cornerRadius = 4.dp,
                                                            )
                                                            Text(
                                                                text = child.title,
                                                                style = typography.bodyCompact,
                                                                color = colors.inkPrimary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
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

                        // Custom Folder Name Prompt
                        if (showCreateFolderDialog) {
                            val submitFolder = {
                                val spaceId = activeSpace?.id ?: "personal"
                                val cleanName = customFolderName.trim().ifBlank { "New Folder" }
                                onCreateFolder(spaceId, cleanName)
                                customFolderName = ""
                                showCreateFolderDialog = false
                            }
                            FocalGroupedCard {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "New Folder",
                                        style = typography.controlPillLabel,
                                        color = colors.inkSecondary,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(colors.canvasBackground.copy(alpha = 0.42f))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (customFolderName.isEmpty()) {
                                            Text(
                                                text = "Folder name…",
                                                style = typography.bodyCompact,
                                                color = colors.inkFaint,
                                            )
                                        }
                                        BasicTextField(
                                            value = customFolderName,
                                            onValueChange = { customFolderName = it },
                                            singleLine = true,
                                            textStyle = typography.bodyCompact.copy(color = colors.inkPrimary),
                                            cursorBrush = SolidColor(colors.inkPrimary),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(onDone = { submitFolder() }),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .clickable {
                                                    customFolderName = ""
                                                    showCreateFolderDialog = false
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                        ) {
                                            Text(
                                                text = "Cancel",
                                                style = typography.controlPillLabel,
                                                color = colors.inkSecondary,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(colors.surfacePillActive)
                                                .clickable { submitFolder() }
                                                .padding(horizontal = 14.dp, vertical = 6.dp),
                                        ) {
                                            Text(
                                                text = "Create",
                                                style = typography.controlPillLabel,
                                                color = colors.inkOnPillActive,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Live on Desktop (E2EE) Presence Card
                        LiveDesktopPresenceCard(
                            peerName = uiState.syncState.peerDeviceName,
                            desktopTabs = desktopTabs,
                            sasCode = uiState.syncState.sasVerificationCode,
                            onOpenTab = onOpenUrl,
                            onOpenPairingView = {
                                if (uiState.syncState.pairingUri == null) {
                                    onStartE2eePairing()
                                }
                                showPairingModal = true
                            },
                        )
                    }

                    // Bottom Footer Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillActive)
                                .clickable {
                                    showCreateFolderDialog = true
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = FocalGlyphs.Plus,
                                contentDescription = null,
                                tint = colors.inkOnPillActive,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "New Folder",
                                style = typography.controlPillLabel,
                                color = colors.inkOnPillActive,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveDesktopPresenceCard(
    peerName: String?,
    desktopTabs: ImmutableList<PresenceTabItem>,
    sasCode: String?,
    onOpenTab: (String) -> Unit,
    onOpenPairingView: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography

    FocalGroupedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPairingView)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = FocalGlyphs.Laptop,
                        contentDescription = null,
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "Desktop Sync",
                        style = typography.urlDomainPill,
                        color = colors.inkPrimary,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (sasCode != null) "Paired" else "Pair",
                        style = typography.captionMetadata,
                        color = colors.inkSecondary,
                    )
                    Icon(
                        imageVector = FocalGlyphs.ChevronRight,
                        contentDescription = null,
                        tint = colors.inkFaint,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            if (desktopTabs.isEmpty()) {
                Text(
                    text = peerName?.let { "$it · No open tabs shared" }
                        ?: "Pair desktop browser via E2EE QR code",
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                )
            } else {
                desktopTabs.take(3).forEach { tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenTab(tab.url) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SiteFavicon(
                            urlOrDomain = tab.url,
                            size = 16.dp,
                            cornerRadius = 4.dp,
                        )
                        Text(
                            text = "${peerName ?: "Desktop"} · ${tab.title.ifBlank { tab.url }}",
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = FocalGlyphs.ExternalArrow,
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen 09 · Zero-Account E2EE Pairing View (`focal-pair://v1?secret=...` + 64-word SAS).
 */
@Composable
private fun E2eePairingCard(
    syncState: SyncSessionUiState,
    onGenerateNewSession: () -> Unit,
    onCopyPairingUri: (String) -> Unit,
    onBackToSpace: () -> Unit,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val pairingUri = syncState.pairingUri ?: "Generating 256-bit secret…"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Desktop Pairing",
                style = typography.sheetTitle,
                color = colors.inkPrimary,
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable(onClick = onBackToSpace)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = FocalGlyphs.ArrowLeft,
                    contentDescription = null,
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Back",
                    style = typography.controlPillLabel,
                    color = colors.inkSecondary,
                )
            }
        }

        Text(
            text = "Paste or scan this URI in Focal Bridge. Keys stay on your devices.",
            style = typography.bodyCompact,
            color = colors.inkSecondary,
        )

        FocalGroupedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Verification words",
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                )
                Text(
                    text = syncState.sasVerificationCode ?: "— · — · — · —",
                    style = typography.cardTitle,
                    color = colors.inkPrimary,
                )
            }
            FocalHairlineDivider(insetStart = 14.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { syncState.pairingUri?.let(onCopyPairingUri) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Pairing link (tap to copy)",
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                )
                Text(
                    text = pairingUri,
                    style = typography.captionMetadata,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable(onClick = onGenerateNewSession)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Rotate Key",
                    style = typography.controlPillLabel,
                    color = colors.inkPrimary,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(colors.surfacePillActive)
                    .clickable {
                        syncState.pairingUri?.let(onCopyPairingUri)
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Copy Code",
                    style = typography.controlPillLabel,
                    color = colors.inkOnPillActive,
                )
            }
        }
    }
}
