package app.focal.feature.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.heightIn
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
import app.focal.design.focalSheetEntrance
import app.focal.design.shaders.SmokedGlassBox
import app.focal.sync.PresenceTabItem
import app.focal.sync.SyncSessionUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@Immutable
data class BookmarksSheetUiState(
    val spaces: ImmutableList<SpaceEntity>,
    val activeSpaceId: String,
    val allBookmarks: ImmutableList<BookmarkEntity>,
    val syncState: SyncSessionUiState,
)

/**
 * Dedicated Mobile Bookmarks Sheet — clean, zero-pinned-tabs list of saved bookmarks & folders
 * filterable by group (`All`, `Personal`, `Work`, `Reading`), with instant 1-tap opening,
 * Edit-mode removal, folder creation, and Desktop E2EE Sync access.
 */
@Composable
fun BookmarksSheet(
    uiState: BookmarksSheetUiState,
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit = {},
    onCreateFolder: (spaceId: String, folderName: String) -> Unit,
    onStartE2eePairing: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val clipboard = LocalClipboardManager.current
    val gestureLease = LocalGestureLeaseController.current
    val sheetBaseTint = rememberUpdatedState(colors.effectiveSheetGlass)

    var internalDismissDragPx by remember { mutableFloatStateOf(0f) }
    var selectedFilterSpaceId by remember { mutableStateOf<String?>(null) } // null == All
    var isEditing by remember { mutableStateOf(false) }
    var showPairingModal by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var customFolderName by remember { mutableStateOf("") }
    var expandedFolderId by remember { mutableStateOf<String?>(null) }

    val filteredEntries = remember(uiState.allBookmarks, selectedFilterSpaceId) {
        if (selectedFilterSpaceId == null) {
            uiState.allBookmarks
        } else {
            uiState.allBookmarks.filter { it.spaceId == selectedFilterSpaceId }
        }
    }

    val bookmarkLinks = remember(filteredEntries) {
        val seenUrls = LinkedHashSet<String>()
        filteredEntries
            .filter { !it.isFolder && !it.url.isNullOrBlank() && it.parentFolderId == null }
            .sortedByDescending { it.updatedAtEpochMs }
            .filter { entry ->
                val key = entry.url.orEmpty().trim().trimEnd('/').lowercase()
                seenUrls.add(key)
            }
            .toImmutableList()
    }

    val folders = remember(filteredEntries) {
        filteredEntries
            .filter { it.isFolder && it.parentFolderId == null }
            .toImmutableList()
    }

    val desktopTabs = remember(uiState.syncState.desktopPresenceTabs) {
        uiState.syncState.desktopPresenceTabs.toImmutableList()
    }

    val dismissSettleScope = androidx.compose.runtime.rememberCoroutineScope()
    val dismissSettleAnim = remember { androidx.compose.animation.core.Animatable(0f) }

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
            glassTintState = sheetBaseTint,
            borderSpecular = colors.borderSpecular,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding()
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
                                            animationSpec = app.focal.design.FocalMotion.spacesSheetSpring(),
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
                                            animationSpec = app.focal.design.FocalMotion.spacesSheetSpring(),
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

                if (showPairingModal) {
                    E2eePairingCard(
                        syncState = uiState.syncState,
                        onGenerateNewSession = onStartE2eePairing,
                        onCopyPairingUri = { uri ->
                            clipboard.setText(AnnotatedString(uri))
                        },
                        onBackToBookmarks = { showPairingModal = false },
                    )
                } else {
                    // Header Row (`Bookmarks` · `Edit`)
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
                                imageVector = FocalGlyphs.Bookmark,
                                contentDescription = null,
                                tint = colors.inkPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Bookmarks",
                                style = typography.sheetTitle,
                                color = colors.inkPrimary,
                            )
                        }

                        if (bookmarkLinks.isNotEmpty() || folders.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (isEditing) colors.surfacePillActive else colors.surfacePillSubtle,
                                    )
                                    .clickable { isEditing = !isEditing }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = if (isEditing) "Done" else "Edit",
                                    style = typography.controlPillLabel,
                                    color = if (isEditing) colors.inkOnPillActive else colors.inkSecondary,
                                )
                            }
                        }
                    }

                    // Space Filter Pills (`All` · `Personal` · `Work` · `Reading`)
                    if (uiState.spaces.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val allSelected = selectedFilterSpaceId == null
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (allSelected) colors.surfacePillActive else colors.surfacePillSubtle,
                                    )
                                    .clickable { selectedFilterSpaceId = null }
                                    .padding(horizontal = 13.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    text = "All",
                                    style = typography.controlPillLabel,
                                    color = if (allSelected) colors.inkOnPillActive else colors.inkSecondary,
                                )
                            }

                            uiState.spaces.forEach { space ->
                                val selected = selectedFilterSpaceId == space.id
                                val dotColor = SpaceAmbientPalette.fromKeyOrHex(space.id, space.ambientHex).dotColor
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) colors.surfacePillActive else colors.surfacePillSubtle,
                                        )
                                        .clickable { selectedFilterSpaceId = space.id }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                    Text(
                                        text = space.name,
                                        style = typography.controlPillLabel,
                                        color = if (selected) colors.inkOnPillActive else colors.inkSecondary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    // Scrollable Bookmarks, Folders, and Desktop Sync Content
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (bookmarkLinks.isEmpty() && folders.isEmpty()) {
                            FocalGroupedCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 26.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = FocalGlyphs.Bookmark,
                                        contentDescription = null,
                                        tint = colors.inkFaint,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Text(
                                        text = "No Bookmarks Yet",
                                        style = typography.cardTitle,
                                        color = colors.inkPrimary,
                                    )
                                    Text(
                                        text = "Tap ··· → Bookmark on any page to save it here",
                                        style = typography.captionMetadata,
                                        color = colors.inkSecondary,
                                    )
                                }
                            }
                        }

                        // Saved Bookmarks Card
                        if (bookmarkLinks.isNotEmpty()) {
                            FocalGroupedCard {
                                bookmarkLinks.forEachIndexed { index, item ->
                                    if (index > 0) {
                                        FocalHairlineDivider(insetStart = 48.dp)
                                    }
                                    val url = item.url.orEmpty()
                                    val displayHost = remember(url) {
                                        url.removePrefix("https://")
                                            .removePrefix("http://")
                                            .removePrefix("www.")
                                            .trimEnd('/')
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (!isEditing && url.isNotBlank()) {
                                                    onOpenUrl(url)
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SiteFavicon(
                                            urlOrDomain = url,
                                            size = 22.dp,
                                            cornerRadius = 6.dp,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title.ifBlank { displayHost },
                                                style = typography.bodyCompact.copy(
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 14.5.sp,
                                                ),
                                                color = colors.inkPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (displayHost.isNotBlank()) {
                                                Text(
                                                    text = displayHost,
                                                    style = typography.captionMetadata,
                                                    color = colors.inkSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        if (isEditing) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(colors.surfacePillSubtle)
                                                    .clickable { onDeleteBookmark(item.id) },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = FocalGlyphs.Close,
                                                    contentDescription = "Remove ${item.title}",
                                                    tint = colors.inkPrimary,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = FocalGlyphs.ChevronRight,
                                                contentDescription = null,
                                                tint = colors.inkFaint,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Saved Folders Card
                        if (folders.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Folders",
                                    style = typography.captionMetadata,
                                    color = colors.inkSecondary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                FocalGroupedCard {
                                    folders.forEachIndexed { index, folder ->
                                        if (index > 0) {
                                            FocalHairlineDivider(insetStart = 48.dp)
                                        }
                                        val children = remember(uiState.allBookmarks, folder.id) {
                                            uiState.allBookmarks.filter { it.parentFolderId == folder.id }
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (!isEditing) {
                                                        expandedFolderId =
                                                            if (expandedFolderId == folder.id) null else folder.id
                                                    }
                                                }
                                                .padding(horizontal = 14.dp, vertical = 11.dp),
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
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    Box(
                                                        modifier = Modifier.size(22.dp),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(
                                                            imageVector = FocalGlyphs.Folder,
                                                            contentDescription = null,
                                                            tint = colors.inkSecondary,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = folder.title,
                                                            style = typography.bodyCompact.copy(
                                                                fontWeight = FontWeight.Medium,
                                                                fontSize = 14.5.sp,
                                                            ),
                                                            color = colors.inkPrimary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        val subtitle = if (children.isNotEmpty()) {
                                                            "${children.size} saved"
                                                        } else {
                                                            folder.subtitle.orEmpty()
                                                        }
                                                        if (subtitle.isNotBlank()) {
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
                                                if (isEditing) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .clip(CircleShape)
                                                            .background(colors.surfacePillSubtle)
                                                            .clickable { onDeleteBookmark(folder.id) },
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(
                                                            imageVector = FocalGlyphs.Close,
                                                            contentDescription = "Remove ${folder.title}",
                                                            tint = colors.inkPrimary,
                                                            modifier = Modifier.size(13.dp),
                                                        )
                                                    }
                                                } else {
                                                    Icon(
                                                        imageVector = if (expandedFolderId == folder.id) {
                                                            FocalGlyphs.ChevronDown
                                                        } else {
                                                            FocalGlyphs.ChevronRight
                                                        },
                                                        contentDescription = null,
                                                        tint = colors.inkFaint,
                                                        modifier = Modifier.size(15.dp),
                                                    )
                                                }
                                            }

                                            if (expandedFolderId == folder.id && children.isNotEmpty()) {
                                                Spacer(Modifier.height(4.dp))
                                                children.forEach { child ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .clickable { child.url?.let(onOpenUrl) }
                                                            .padding(start = 34.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        SiteFavicon(
                                                            urlOrDomain = child.url.orEmpty(),
                                                            size = 16.dp,
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

                        // Custom Folder Creation Card
                        if (showCreateFolderDialog) {
                            val submitFolder = {
                                val targetSpace = selectedFilterSpaceId ?: uiState.activeSpaceId
                                val cleanName = customFolderName.trim().ifBlank { "New Folder" }
                                onCreateFolder(targetSpace, cleanName)
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

                        // Quiet Desktop Sync (E2EE) Presence Card
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

                    // Bottom Footer Bar (`Done` · `+ New Folder`)
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
                                .clickable { showCreateFolderDialog = true }
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

@Composable
private fun E2eePairingCard(
    syncState: SyncSessionUiState,
    onGenerateNewSession: () -> Unit,
    onCopyPairingUri: (String) -> Unit,
    onBackToBookmarks: () -> Unit,
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
                    .clickable(onClick = onBackToBookmarks)
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
                    .clickable { syncState.pairingUri?.let(onCopyPairingUri) }
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
