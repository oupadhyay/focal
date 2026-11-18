package app.focal.feature.spaces

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.database.BookmarkEntity
import app.focal.database.SpaceEntity
import app.focal.design.FocalTheme
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
 * Screen 05 & Screen 09 · Swipe-Up Spaces & Bookmarks Sheet with Ambient GPU Tinting + E2EE Continuity.
 *
 * - Switching between `Personal` (`#F4725E`), `Work` (`#94A3B8`), and `Reading` (`#4ADE80`)
 *   cross-fades `spaceAmbient` in `260ms` purely in the draw/shader phase while keeping all
 *   foreground controls 100% achromatic.
 * - Includes 4-column Space-scoped Pinned Squircles, Saved Bookmark Folders, Live Desktop Tabs,
 *   and Screen 09 Zero-Account QR / SAS Pairing view.
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

    val activeSpace = uiState.spaces.firstOrNull { it.id == uiState.activeSpaceId }
        ?: uiState.spaces.firstOrNull()
    val ambientSpec = SpaceAmbientPalette.fromKeyOrHex(
        keyOrId = activeSpace?.id ?: "personal",
        fallbackHex = activeSpace?.ambientHex,
    )

    // 260ms FastOutSlowInEasing GPU uniform interpolation (0 layout/composition churn)
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

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 28.dp, bottomEnd = 28.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SmokedGlassBox(
            shape = sheetShape,
            glassTintState = sheetBaseTint,
            borderSpecular = colors.borderSpecular,
            spaceAmbientState = animatedSpaceAmbient,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 10.dp)
                .graphicsLayer {
                    val ext = externalDragOffsetPx?.floatValue ?: 0f
                    translationY = (internalDismissDragPx + ext).coerceAtLeast(0f)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (internalDismissDragPx > 120.dp.toPx()) {
                                internalDismissDragPx = 0f
                                onDismiss()
                            } else {
                                internalDismissDragPx = 0f
                            }
                        },
                        onDragCancel = { internalDismissDragPx = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            internalDismissDragPx = (internalDismissDragPx + dragAmount).coerceAtLeast(0f)
                        },
                    )
                }
                .consumeClicks(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Top Drag Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(colors.inkFaint),
                )

                // Space Switcher Pills (Personal · Work · Reading)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfacePillSubtle)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.spaces.forEach { space ->
                        val selected = space.id == uiState.activeSpaceId
                        val dotColor = SpaceAmbientPalette.fromKeyOrHex(space.id, space.ambientHex).dotColor
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${activeSpace?.name ?: "SPACE"} PINNED",
                                style = typography.captionMetadata,
                                color = colors.inkSecondary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                pinsRow.forEach { pin ->
                                    val displayTitle = if (pin.title.equals("HackerNews", ignoreCase = true)) {
                                        "HN"
                                    } else {
                                        pin.title
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(colors.surfacePillSubtle)
                                            .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                                            .clickable { pin.url?.let(onOpenUrl) }
                                            .padding(vertical = 11.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        SiteFavicon(
                                            urlOrDomain = pin.url.orEmpty(),
                                            size = 26.dp,
                                            cornerRadius = 7.dp,
                                        )
                                        Text(
                                            text = displayTitle,
                                            style = typography.captionMetadata,
                                            color = colors.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }

                        // Saved Folders & Bookmarks
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "SAVED FOLDERS & BOOKMARKS",
                                style = typography.captionMetadata,
                                color = colors.inkSecondary,
                            )
                            rootItems.forEach { item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(colors.surfacePillSubtle)
                                        .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                                        .clickable {
                                            if (item.isFolder) {
                                                expandedFolderId =
                                                    if (expandedFolderId == item.id) null else item.id
                                            } else {
                                                item.url?.let(onOpenUrl)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                                            if (!item.isFolder) {
                                                SiteFavicon(
                                                    urlOrDomain = item.url.orEmpty(),
                                                    size = 18.dp,
                                                    cornerRadius = 5.dp,
                                                )
                                            }
                                            Text(
                                                text = if (item.isFolder) "📁 ${item.title}" else item.title,
                                                style = typography.urlDomainPill,
                                                color = colors.inkPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (item.isFolder && expandedFolderId == item.id) "⌄" else "›",
                                            style = typography.controlPillLabel,
                                            color = colors.inkFaint,
                                        )
                                    }
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

                                    // Nested Folder Children
                                    if (item.isFolder && expandedFolderId == item.id) {
                                        val children = uiState.foldersAndBookmarks.filter {
                                            it.parentFolderId == item.id
                                        }
                                        if (children.isNotEmpty()) {
                                            Spacer(Modifier.height(4.dp))
                                            children.forEach { child ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable { child.url?.let(onOpenUrl) }
                                                        .padding(vertical = 6.dp, horizontal = 6.dp),
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

                        // Custom Folder Name Prompt (P2-6)
                        if (showCreateFolderDialog) {
                            val submitFolder = {
                                val spaceId = activeSpace?.id ?: "personal"
                                val cleanName = customFolderName.trim().ifBlank { "New Folder" }
                                onCreateFolder(spaceId, cleanName)
                                customFolderName = ""
                                showCreateFolderDialog = false
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(colors.surfacePillSubtle)
                                    .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "NEW FOLDER IN ${(activeSpace?.name ?: "SPACE").uppercase()}",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.canvasBackground.copy(alpha = 0.45f))
                                        .border(1.dp, colors.borderSpecular, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (customFolderName.isEmpty()) {
                                        Text(
                                            text = "Folder name (e.g. Reading Set)…",
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
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "↓ Swipe down to page",
                            style = typography.captionMetadata,
                            color = colors.inkSecondary,
                            modifier = Modifier.clickable(onClick = onDismiss),
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.surfacePillActive)
                                .clickable {
                                    showCreateFolderDialog = true
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = "+ New Folder",
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfacePillSubtle)
            .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenPairingView)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⇄ Live on Desktop (E2EE)",
                style = typography.urlDomainPill,
                color = colors.inkPrimary,
            )
            Text(
                text = if (sasCode != null) "Paired · Verify SAS" else "Pair Focal Bridge ›",
                style = typography.captionMetadata,
                color = colors.inkFaint,
            )
        }

        if (desktopTabs.isEmpty()) {
            Text(
                text = peerName?.let { "$it · Zero open tabs shared" }
                    ?: "Pair Chrome, Brave, Zen, or Firefox via QR with zero account",
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
                        size = 15.dp,
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
                    Text(
                        text = "↗",
                        style = typography.captionMetadata,
                        color = colors.inkPrimary,
                    )
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
    val pairingUri = syncState.pairingUri ?: "Generating 256-bit CSPRNG secret…"

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
                text = "Zero-Account E2EE Pairing",
                style = typography.sheetTitle,
                color = colors.inkPrimary,
            )
            Text(
                text = "← Back",
                style = typography.controlPillLabel,
                color = colors.inkSecondary,
                modifier = Modifier.clickable(onClick = onBackToSpace),
            )
        }

        Text(
            text = "Paste or scan this URI in the Focal Bridge Desktop Extension. Keys are derived locally via HKDF-SHA256 and sealed with AES-256-GCM.",
            style = typography.bodyCompact,
            color = colors.inkSecondary,
        )

        // SAS Fingerprint Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfacePillSubtle)
                .border(1.dp, colors.borderSpecular, RoundedCornerShape(16.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "SHORT AUTHENTICATION STRING (SAS)",
                style = typography.captionMetadata,
                color = colors.inkFaint,
            )
            Text(
                text = syncState.sasVerificationCode ?: "— · — · — · 00",
                style = typography.cardTitle,
                color = colors.inkPrimary,
            )
        }

        // Pairing URI Copyable Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfacePillSubtle)
                .border(1.dp, colors.borderSpecular, RoundedCornerShape(14.dp))
                .clickable { syncState.pairingUri?.let(onCopyPairingUri) }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "PAIRING URI (TAP TO COPY)",
                style = typography.captionMetadata,
                color = colors.inkFaint,
            )
            Text(
                text = pairingUri,
                style = typography.captionMetadata,
                color = colors.inkPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
                    .border(1.dp, colors.borderSpecular, CircleShape)
                    .clickable(onClick = onGenerateNewSession)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Rotate Secret",
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
                    text = "Copy Pairing Code",
                    style = typography.controlPillLabel,
                    color = colors.inkOnPillActive,
                )
            }
        }
    }
}
