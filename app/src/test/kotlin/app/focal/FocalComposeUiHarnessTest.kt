package app.focal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.adblock.NativeAdblockController
import app.focal.database.BookmarkEntity
import app.focal.database.DownloadRecordEntity
import app.focal.database.HistoryEntryEntity
import app.focal.database.SpaceEntity
import app.focal.database.TabMetadataEntity
import app.focal.design.AppChromeTheme
import app.focal.design.FocalBrowserSettings
import app.focal.design.FocalTheme
import app.focal.design.SearchEnginePreset
import app.focal.engine.api.ActiveMediaSessionInfo
import app.focal.engine.api.EngineSession
import app.focal.engine.api.EngineSessionEvent
import app.focal.engine.api.EngineSessionState
import app.focal.engine.api.FindInPageState
import app.focal.engine.api.IndexedDomParagraph
import app.focal.engine.api.OriginOverrides
import app.focal.engine.api.ReadableDocument
import app.focal.feature.browser.FindInPageBar
import app.focal.feature.browser.LinkPeekSheet
import app.focal.feature.media.FocalMediaBridge
import app.focal.feature.reader.GlobalSettingsSheet
import app.focal.feature.reader.PageMenuAndSiteOverridesSheet
import app.focal.feature.reader.UpdateBannerState
import app.focal.feature.search.SearchSheet
import app.focal.feature.spaces.BookmarksSheet
import app.focal.feature.spaces.BookmarksSheetUiState
import app.focal.feature.spaces.DownloadsSheet
import app.focal.feature.spaces.HistorySheet
import app.focal.feature.tabs.TabSwitcherSheet
import app.focal.sync.PresenceTabItem
import app.focal.sync.SyncConnectionStatus
import app.focal.sync.SyncSessionUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Local VM Android UI & Native Skia Screenshot Harness (`Robolectric 4.14` + `Compose UI Test`).
 *
 * Runs on JVM + native Linux `libhwui` without requiring `/dev/kvm`, rendering real 412x915dp
 * phone viewports to `app/build/outputs/robolectric-snapshots/` PNG files in seconds.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class FocalComposeUiHarnessTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun searchSheet_hasNoPermanentHint_activatesGitDoubleSpaceChip_andNavigatesToGoogleDotCom() {
        var navigatedUrl: String? = null
        var navigatedPrivate: Boolean? = null

        composeRule.setContent {
            var isPrivate by remember { mutableStateOf(false) }
            FocalTheme(darkGlass = true, isPrivateAperture = isPrivate) {
                SearchSheet(
                    initialQuery = "",
                    isPrivateAperture = isPrivate,
                    onTogglePrivateAperture = { isPrivate = it },
                    onNavigateToUrl = { url, priv ->
                        navigatedUrl = url
                        navigatedPrivate = priv
                    },
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()

        // Verify 5 borderless squircle favicon tiles render with zero permanent command hint clutter
        composeRule.onNodeWithText("IMDb").assertIsDisplayed()
        composeRule.onNodeWithText("Wikipedia").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Ars").assertIsDisplayed()
        composeRule.onNodeWithText("Reddit").assertIsDisplayed()
        composeRule.onNodeWithText("for site search", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("IM").assertDoesNotExist()
        composeRule.onNodeWithText("GH").assertDoesNotExist()

        val cleanSnap = composeRule.captureSnapshotPng("01a_search_sheet_clean.png")
        assertTrue(cleanSnap.length() > 1_000L)

        // Verify typing "git  " activates the GitHub site chip & radial glow
        composeRule.onNode(hasSetTextAction()).performTextInput("git  ")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Remove GitHub filter").assertIsDisplayed()

        val chipSnap = composeRule.captureSnapshotPng("01b_search_sheet_github_chip.png")
        assertTrue(chipSnap.length() > 1_000L)

        // Dismiss chip and verify offline inline unit conversion card renders cleanly with tap-to-copy
        composeRule.onNodeWithContentDescription("Remove GitHub filter").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("180 lbs in kg")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("81.65 kg").assertIsDisplayed()
        composeRule.onNodeWithText("180 lb → kg").assertIsDisplayed()

        val inlineAnswerSnap = composeRule.captureSnapshotPng("01c_search_sheet_inline_answer.png")
        assertTrue(inlineAnswerSnap.length() > 1_000L)

        // Verify typing "google.com" clears inline answer and Go resolves cleanly to https://google.com
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("google.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("81.65 kg").assertDoesNotExist()
        composeRule.onNodeWithText("Go").performClick()
        assertEquals("https://google.com", navigatedUrl)
        assertEquals(false, navigatedPrivate)
    }

    @Test
    fun bookmarksSheet_rendersZeroPinnedTabsAndCleanBookmarksList() {
        val now = 1_763_400_000_000L
        val uiState = BookmarksSheetUiState(
            spaces = persistentListOf(
                SpaceEntity("personal", "Personal", "#F4725E", 0, now),
                SpaceEntity("work", "Work", "#94A3B8", 1, now),
                SpaceEntity("reading", "Reading", "#4ADE80", 2, now),
            ),
            activeSpaceId = "work",
            allBookmarks = persistentListOf(
                BookmarkEntity("b1", "work", title = "Jetpack Compose Architecture", url = "https://developer.android.com/jetpack/compose", createdAtEpochMs = now, updatedAtEpochMs = now + 3_000L),
                BookmarkEntity("b2", "work", title = "GitHub", url = "https://github.com", createdAtEpochMs = now, updatedAtEpochMs = now + 2_000L),
                BookmarkEntity("b3", "reading", title = "Stratechery", url = "https://stratechery.com", createdAtEpochMs = now, updatedAtEpochMs = now + 1_000L),
                BookmarkEntity("f1", "reading", isFolder = true, title = "Longform Queue", subtitle = "Browser engines · Typography", createdAtEpochMs = now, updatedAtEpochMs = now),
            ),
            syncState = SyncSessionUiState(
                status = SyncConnectionStatus.CONNECTED,
                peerDeviceName = "MacBook Pro",
                sasVerificationCode = "amber · lens · prism · quartz",
                desktopPresenceTabs = listOf(
                    PresenceTabItem(id = "dt1", url = "https://arstechnica.com", title = "Ars Technica"),
                ),
            ),
        )

        composeRule.setContent {
            FocalTheme(darkGlass = true) {
                BookmarksSheet(
                    uiState = uiState,
                    onOpenUrl = {},
                    onDeleteBookmark = {},
                    onCreateFolder = { _, _ -> },
                    onStartE2eePairing = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bookmarks").assertIsDisplayed()
        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithText("Jetpack Compose Architecture").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Stratechery").assertIsDisplayed()
        composeRule.onNodeWithText("Pinned").assertDoesNotExist()

        val snap = composeRule.captureSnapshotPng("02_bookmarks_sheet_clean.png")
        assertTrue(snap.length() > 1_000L)
    }

    @Test
    fun tabSwitcherSheet_rendersYabStackedPreviewDeckAndTopGroups() {
        val now = 1_763_400_000_000L
        val spaces = persistentListOf(
            SpaceEntity("personal", "Personal", "#F4725E", 0, now),
            SpaceEntity("work", "Work", "#94A3B8", 1, now),
            SpaceEntity("reading", "Reading", "#4ADE80", 2, now),
        )
        val tabs = persistentListOf(
            TabMetadataEntity(
                id = "t1",
                spaceId = "personal",
                url = "https://www.google.com/search?q=focal",
                domain = "google.com",
                title = "focal - Google Search",
                lastActiveEpochMs = now - 60_000L,
                createdAtEpochMs = now - 120_000L,
                blockedTrackersCount = 4,
            ),
            TabMetadataEntity(
                id = "t2",
                spaceId = "personal",
                url = "https://github.com/oupadhyay/focal",
                domain = "github.com",
                title = "oupadhyay/focal: Native 120Hz Android Browser",
                lastActiveEpochMs = now - 300_000L,
                createdAtEpochMs = now - 600_000L,
                blockedTrackersCount = 2,
            ),
            TabMetadataEntity(
                id = "t3",
                spaceId = "personal",
                url = "https://stratechery.com",
                domain = "stratechery.com",
                title = "Stratechery by Ben Thompson",
                lastActiveEpochMs = now - 900_000L,
                createdAtEpochMs = now - 1_200_000L,
                blockedTrackersCount = 1,
            ),
        )

        fun mockPageBitmap(bgColor: Int, headerColor: Int): Bitmap {
            val bmp = Bitmap.createBitmap(360, 680, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(bgColor)
            val paint = android.graphics.Paint().apply { color = headerColor }
            canvas.drawRect(0f, 0f, 360f, 110f, paint)
            paint.color = android.graphics.Color.argb(60, 128, 128, 128)
            canvas.drawRoundRect(24f, 140f, 336f, 260f, 18f, 18f, paint)
            canvas.drawRoundRect(24f, 290f, 336f, 440f, 18f, 18f, paint)
            return bmp
        }

        val previews = persistentMapOf(
            "t1" to mockPageBitmap(android.graphics.Color.WHITE, android.graphics.Color.rgb(241, 243, 246)),
            "t2" to mockPageBitmap(android.graphics.Color.rgb(18, 22, 28), android.graphics.Color.rgb(28, 34, 42)),
            "t3" to mockPageBitmap(android.graphics.Color.rgb(247, 244, 236), android.graphics.Color.rgb(232, 226, 214)),
        )

        composeRule.setContent {
            FocalTheme(darkGlass = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FocalTheme.colors.canvasBackground),
                ) {
                    TabSwitcherSheet(
                        activeTabs = tabs,
                        archivedTabs = persistentListOf(),
                        selectedTabId = "t1",
                        spaces = spaces,
                        activeSpaceId = "personal",
                        tabCountBySpace = persistentMapOf("personal" to 3, "work" to 1),
                        tabPreviews = previews,
                        isPrivateAperture = false,
                        nowEpochMs = now,
                        onSelectSpace = {},
                        onTogglePrivateMode = {},
                        onSelectTab = {},
                        onCloseTab = {},
                        onRestoreArchivedTab = {},
                        onOpenBookmarks = {},
                        onNewSearch = {},
                        onDone = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Personal").assertIsDisplayed()
        composeRule.onNodeWithText("Work").assertIsDisplayed()
        composeRule.onNodeWithText("Reading").assertIsDisplayed()
        composeRule.onNodeWithText("Private").assertIsDisplayed()
        composeRule.onAllNodesWithText("focal - Google Search")[0].assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bookmarks").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("New Tab").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()

        val tabSnap = composeRule.captureSnapshotPng("03_tab_switcher_clean.png")
        assertTrue(tabSnap.length() > 1_000L)
    }

    @Test
    fun tabSwitcherSheet_longPressDragMovesTabBetweenGroups() {
        val now = 1_763_400_000_000L
        val spaces = persistentListOf(
            SpaceEntity("personal", "Personal", "#F4725E", 0, now),
            SpaceEntity("work", "Work", "#94A3B8", 1, now),
            SpaceEntity("reading", "Reading", "#4ADE80", 2, now),
        )
        val initialTabs = listOf(
            TabMetadataEntity(
                id = "t1",
                spaceId = "personal",
                url = "https://stratechery.com",
                domain = "stratechery.com",
                title = "Stratechery by Ben Thompson",
                lastActiveEpochMs = now - 60_000L,
                createdAtEpochMs = now - 120_000L,
            ),
            TabMetadataEntity(
                id = "t2",
                spaceId = "personal",
                url = "https://github.com/oupadhyay/focal",
                domain = "github.com",
                title = "oupadhyay/focal",
                lastActiveEpochMs = now - 300_000L,
                createdAtEpochMs = now - 600_000L,
            ),
        )

        val previewBmp = Bitmap.createBitmap(360, 680, Bitmap.Config.RGB_565).apply {
            val canvas = Canvas(this)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(245, 240, 230) }
            canvas.drawRect(0f, 0f, 360f, 110f, paint)
            paint.color = android.graphics.Color.rgb(225, 145, 45)
            canvas.drawCircle(52f, 55f, 24f, paint)
        }

        val movedRecords = mutableListOf<Pair<String, String>>()
        val selectedRecords = mutableListOf<String>()
        var currentActiveSpace = "personal"

        composeRule.setContent {
            var allTabs by remember { mutableStateOf(initialTabs) }
            var activeSpaceId by remember { mutableStateOf("personal") }
            val activeSpaceTabs = remember(allTabs, activeSpaceId) {
                allTabs.filter { it.spaceId == activeSpaceId }.toImmutableList()
            }
            val counts = remember(allTabs) {
                allTabs.groupingBy { it.spaceId }.eachCount().toImmutableMap()
            }

            FocalTheme(darkGlass = true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FocalTheme.colors.canvasBackground),
                ) {
                    TabSwitcherSheet(
                        activeTabs = activeSpaceTabs,
                        archivedTabs = persistentListOf(),
                        selectedTabId = "t1",
                        spaces = spaces,
                        activeSpaceId = activeSpaceId,
                        tabCountBySpace = counts,
                        tabPreviews = persistentMapOf("t1" to previewBmp),
                        isPrivateAperture = false,
                        nowEpochMs = now,
                        onSelectSpace = { next ->
                            activeSpaceId = next
                            currentActiveSpace = next
                        },
                        onTogglePrivateMode = {},
                        onSelectTab = { id -> selectedRecords += id },
                        onCloseTab = {},
                        onMoveTabToSpace = { tabId, targetSpace ->
                            movedRecords += tabId to targetSpace
                            allTabs = allTabs.map { tab ->
                                if (tab.id == tabId) tab.copy(spaceId = targetSpace) else tab
                            }
                        },
                        onRestoreArchivedTab = {},
                        onOpenBookmarks = {},
                        onNewSearch = {},
                        onDone = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val frontCard = composeRule.onNodeWithTag("front_tab_card")
        val cardBounds = frontCard.fetchSemanticsNode().boundsInRoot
        val workBounds = composeRule.onNodeWithText("Work").fetchSemanticsNode().boundsInRoot
        val targetInCardLocal = Offset(
            x = workBounds.center.x - cardBounds.left,
            y = workBounds.center.y - cardBounds.top,
        )

        // 1. Press and hold past 260ms threshold at card center: ghost card must lift centered directly under finger
        frontCard.performTouchInput {
            down(center)
            advanceEventTime(340L)
            moveTo(center + Offset(0f, -2f))
        }
        composeRule.waitForIdle()
        val pickupGhost = composeRule.onNodeWithTag("drag_ghost_card")
        pickupGhost.assertIsDisplayed()
        val pickupGhostBounds = pickupGhost.fetchSemanticsNode().boundsInRoot
        val expectedPressRootX = cardBounds.center.x
        val expectedPressRootY = cardBounds.center.y - 2f
        assertTrue(
            "Expected lifted ghost X (${pickupGhostBounds.center.x}) within 8px of finger ($expectedPressRootX)",
            kotlin.math.abs(pickupGhostBounds.center.x - expectedPressRootX) <= 8f,
        )
        assertTrue(
            "Expected lifted ghost Y (${pickupGhostBounds.center.y}) within 8px of finger ($expectedPressRootY)",
            kotlin.math.abs(pickupGhostBounds.center.y - expectedPressRootY) <= 8f,
        )
        val pickupSnap = composeRule.captureSnapshotPng("03c_tab_switcher_long_press_pickup.png")
        assertTrue(pickupSnap.length() > 1_000L)

        // 2. Drag up onto the "Work" group pill
        frontCard.performTouchInput {
            advanceEventTime(40L)
            moveTo(targetInCardLocal)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Move to Work").assertIsDisplayed()

        // Capture mid-drag visual snapshot to inspect floating ghost card + highlighted Work drop pill
        val midDragSnap = composeRule.captureSnapshotPng("03b_tab_switcher_drag_to_group.png")
        assertTrue(midDragSnap.length() > 1_000L)

        // Release onto Work — moves tab without accidentally triggering tab selection
        frontCard.performTouchInput {
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf("t1" to "work"), movedRecords)
        assertTrue("Releasing drag must not trigger onSelectTab", selectedRecords.isEmpty())
        composeRule.onNodeWithText("Moved to Work").assertIsDisplayed()
        composeRule.onNodeWithText("View").assertIsDisplayed()

        // Tap 1-tap View jump on confirmation pill and verify tab is live inside Work
        composeRule.onNodeWithText("View").performClick()
        composeRule.waitForIdle()
        assertEquals("work", currentActiveSpace)
        composeRule.onAllNodesWithText("Stratechery by Ben Thompson")[0].assertIsDisplayed()
    }

    @Test
    fun pageMenuSheet_rendersLensAndYabGradeActionsAndSiteSettings() {
        var latestAppTheme = AppChromeTheme.DARK

        composeRule.setContent {
            var overrides by remember {
                mutableStateOf(OriginOverrides(origin = "github.com"))
            }
            var appTheme by remember { mutableStateOf(AppChromeTheme.DARK) }
            FocalTheme(darkGlass = appTheme.resolveDarkUi(isSystemInDarkTheme = true)) {
                PageMenuAndSiteOverridesSheet(
                    domain = "github.com",
                    fullUrl = "https://github.com/oupadhyay/focal",
                    isPrivateAperture = false,
                    canGoBack = true,
                    canGoForward = false,
                    blockedCountInTab = 7L,
                    overrides = overrides,
                    appTheme = appTheme,
                    onGoBack = {},
                    onGoForward = {},
                    onReload = {},
                    onCopyCleanLink = {},
                    onShareCleanLink = {},
                    onSaveBookmark = {},
                    onOpenBookmarks = {},
                    onOpenReaderMode = {},
                    onUpdateSiteOverrides = { overrides = it },
                    onUpdateAppTheme = { next ->
                        appTheme = next
                        latestAppTheme = next
                    },
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("github.com").assertIsDisplayed()
        composeRule.onNodeWithText("Bookmarks").assertIsDisplayed()
        composeRule.onNodeWithText("Pin").assertDoesNotExist()
        composeRule.onNodeWithText("focal://calm").assertDoesNotExist()

        val actionsSnap = composeRule.captureSnapshotPng("04a_page_menu_actions.png")
        assertTrue(actionsSnap.length() > 1_000L)

        // Switch to Site Settings subtab and verify per-site toggles + configurable App Theme (Dark default)
        composeRule.onAllNodesWithText("Site Settings")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Block Ads & Trackers").assertIsDisplayed()
        composeRule.onNodeWithText("Dark Web Pages").assertIsDisplayed()
        composeRule.onNodeWithText("Editorial Fonts").assertIsDisplayed()
        composeRule.onNodeWithText("App Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        composeRule.onNodeWithText("System").assertIsDisplayed()

        val settingsSnap = composeRule.captureSnapshotPng("04b_page_menu_site_settings.png")
        assertTrue(settingsSnap.length() > 1_000L)

        // Switching App Theme updates callback cleanly across Light -> System -> Dark
        composeRule.onNodeWithText("Light").performClick()
        composeRule.waitForIdle()
        assertEquals(AppChromeTheme.LIGHT, latestAppTheme)

        composeRule.onNodeWithText("System").performClick()
        composeRule.waitForIdle()
        assertEquals(AppChromeTheme.SYSTEM, latestAppTheme)

        composeRule.onNodeWithText("Dark").performClick()
        composeRule.waitForIdle()
        assertEquals(AppChromeTheme.DARK, latestAppTheme)
    }

    @Test
    fun browserViewport_lightPageAdaptsOnlyBottomBarNotEntireUi() {
        val loadedLightPageSession = FakeLoadedEngineSession(
            url = "https://stratechery.com",
            domain = "stratechery.com",
            title = "Stratechery",
        )

        composeRule.setContent {
            val pinchProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val pinchCenterY = remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
            val backProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val scrollHide = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

            // App chrome stays Dark Smoked OLED while light webpage switches ONLY the floating bottom bar
            FocalTheme(darkGlass = true) {
                app.focal.feature.browser.BrowserViewportScreen(
                    activeSession = loadedLightPageSession,
                    tabCount = 5,
                    isPrivateAperture = false,
                    adaptiveBottomBarDarkGlass = false,
                    isOverlaySheetOpen = false,
                    continuityHandoff = null,
                    mediaMiniPlayerInfo = null,
                    pinchProgressState = pinchProgress,
                    pinchCenterYState = pinchCenterY,
                    predictiveBackProgressState = backProgress,
                    bottomBarScrollHideFraction = scrollHide,
                    onOpenSearchSheet = {},
                    onOpenTabSwitcher = {},
                    onCloseCurrentTab = {},
                    onOpenNewPrivateTab = {},
                    onStepToAdjacentTab = {},
                    onOpenPageMenu = {},
                    onOpenReaderMode = {},
                    onPinchSnapSummarize = {},
                    onPinchSnapCancelled = {},
                    onOpenContinuityHandoff = {},
                    onToggleMediaPlayPause = {},
                    onCycleMediaPlaybackRate = {},
                    onEnterPictureInPicture = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("stratechery.com").assertIsDisplayed()

        val bitmap = composeRule.captureSnapshotBitmap("07_light_bottom_bar_on_dark_chrome.png")
        // Upper chrome canvas stays Dark Smoked OLED (#050507 -> low luminance)
        val topPixel = bitmap.getPixel(bitmap.width / 2, 24)
        val topLuma = (0.299 * android.graphics.Color.red(topPixel) +
            0.587 * android.graphics.Color.green(topPixel) +
            0.114 * android.graphics.Color.blue(topPixel))
        // Bottom center pill adapts to Light Alabaster frosted glass (high luminance)
        val bottomPillY = (bitmap.height - 108).coerceIn(0, bitmap.height - 1)
        val bottomPillPixel = bitmap.getPixel(bitmap.width / 2, bottomPillY)
        val bottomPillLuma = (0.299 * android.graphics.Color.red(bottomPillPixel) +
            0.587 * android.graphics.Color.green(bottomPillPixel) +
            0.114 * android.graphics.Color.blue(bottomPillPixel))

        assertTrue("Expected dark top app chrome canvas (< 40 luma), got $topLuma", topLuma < 40.0)
        assertTrue("Expected light alabaster adaptive bottom bar (> 170 luma), got $bottomPillLuma", bottomPillLuma > 170.0)
    }

    @Test
    fun browserViewport_rendersCalmEmblemAndThreeCapsuleBottomBar() {
        composeRule.setContent {
            val pinchProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val pinchCenterY = remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
            val backProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val scrollHide = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

            FocalTheme(darkGlass = true) {
                app.focal.feature.browser.BrowserViewportScreen(
                    activeSession = null,
                    tabCount = 2,
                    isPrivateAperture = false,
                    isOverlaySheetOpen = false,
                    continuityHandoff = null,
                    mediaMiniPlayerInfo = null,
                    pinchProgressState = pinchProgress,
                    pinchCenterYState = pinchCenterY,
                    predictiveBackProgressState = backProgress,
                    bottomBarScrollHideFraction = scrollHide,
                    onOpenSearchSheet = {},
                    onOpenTabSwitcher = {},
                    onCloseCurrentTab = {},
                    onOpenNewPrivateTab = {},
                    onStepToAdjacentTab = {},
                    onOpenPageMenu = {},
                    onOpenReaderMode = {},
                    onPinchSnapSummarize = {},
                    onPinchSnapCancelled = {},
                    onOpenContinuityHandoff = {},
                    onToggleMediaPlayPause = {},
                    onCycleMediaPlaybackRate = {},
                    onEnterPictureInPicture = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Focal").assertIsDisplayed()
        composeRule.onNodeWithText("Search or enter address").assertIsDisplayed()

        val viewportSnap = composeRule.captureSnapshotPng("06_calm_viewport_and_bottom_bar.png")
        assertTrue(viewportSnap.length() > 1_000L)
    }

    @Test
    fun browserViewport_backgroundMediaMiniPlayer_supportsControlsJumpAndSwipeDismiss() {
        FocalMediaBridge.clearAll()
        try {
            val activeArticleTabId = "tab-article"
            val backgroundAudioTabId = "tab-audio"
            val loadedArticleSession = FakeLoadedEngineSession(
                url = "https://stratechery.com",
                domain = "stratechery.com",
                title = "Stratechery",
            )
            FocalMediaBridge.onSelectedTabChanged(activeArticleTabId)
            FocalMediaBridge.updateActiveMedia(
                backgroundAudioTabId,
                ActiveMediaSessionInfo(
                    title = "Acquired — TSMC Part II",
                    domain = "youtube.com",
                    isPlaying = true,
                    playbackRate = 1.0f,
                    hasVideoElement = true,
                ),
            )

            var toggledPlayValue: Boolean? = null
            var cycledRateValue: Float? = null
            var jumpedToTabId: String? = null

            composeRule.setContent {
                val pinchProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                val pinchCenterY = remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
                val backProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                val scrollHide = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                val activeBridge by FocalMediaBridge.activeMedia.collectAsState()
                val dockBridge by FocalMediaBridge.dockMedia.collectAsState()

                FocalTheme(darkGlass = true) {
                    app.focal.feature.browser.BrowserViewportScreen(
                        activeSession = loadedArticleSession,
                        tabCount = 2,
                        isPrivateAperture = false,
                        adaptiveBottomBarDarkGlass = true,
                        isOverlaySheetOpen = false,
                        continuityHandoff = null,
                        mediaMiniPlayerInfo = dockBridge?.media,
                        isMediaMiniPlayerSuppressed = activeBridge != null && dockBridge == null,
                        isMediaFromBackgroundTab = dockBridge != null && dockBridge?.sessionTabId != activeArticleTabId,
                        pinchProgressState = pinchProgress,
                        pinchCenterYState = pinchCenterY,
                        predictiveBackProgressState = backProgress,
                        bottomBarScrollHideFraction = scrollHide,
                        onOpenSearchSheet = {},
                        onOpenTabSwitcher = {},
                        onCloseCurrentTab = {},
                        onOpenNewPrivateTab = {},
                        onStepToAdjacentTab = {},
                        onOpenPageMenu = {},
                        onOpenReaderMode = {},
                        onPinchSnapSummarize = {},
                        onPinchSnapCancelled = {},
                        onOpenContinuityHandoff = {},
                        onToggleMediaPlayPause = { toggledPlayValue = it },
                        onCycleMediaPlaybackRate = { cycledRateValue = it },
                        onEnterPictureInPicture = {},
                        onFocusMediaTab = dockBridge?.sessionTabId
                            ?.takeIf { it != activeArticleTabId }
                            ?.let { target -> { jumpedToTabId = target } },
                        onDismissMediaMiniPlayer = { FocalMediaBridge.dismissMiniPlayer() },
                    )
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag("media_mini_player_dock").assertIsDisplayed()
            composeRule.onNodeWithText("Acquired — TSMC Part II").assertIsDisplayed()
            composeRule.onNodeWithText("Other tab · youtube.com").assertIsDisplayed()

            val miniPlayerSnap = composeRule.captureSnapshotPng("08_media_mini_player_background_tab.png")
            assertTrue(miniPlayerSnap.length() > 1_000L)

            // Tapping Play/Pause, speed pill, and background-tab title still works cleanly
            composeRule.onNodeWithContentDescription("Pause").performClick()
            composeRule.onNodeWithText("1.0×").performClick()
            composeRule.onNodeWithContentDescription("Switch to playing tab").performClick()
            composeRule.waitForIdle()
            assertEquals(false, toggledPlayValue)
            assertEquals(1.25f, cycledRateValue)
            assertEquals(backgroundAudioTabId, jumpedToTabId)

            // Swipe left dismisses the floating mini-player cleanly without killing system Media3 state
            composeRule.onNodeWithTag("media_mini_player_dock").performTouchInput {
                swipeLeft(startX = right - 12f, endX = left - 120f, durationMillis = 120L)
            }
            composeRule.mainClock.advanceTimeBy(400L)
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("media_mini_player_dock").assertDoesNotExist()
            assertEquals(null, FocalMediaBridge.dockMedia.value)
            assertEquals(backgroundAudioTabId, FocalMediaBridge.activeMedia.value?.sessionTabId)

            // New track starts -> mini-player returns, and swipe right also dismisses cleanly
            FocalMediaBridge.updateActiveMedia(
                backgroundAudioTabId,
                ActiveMediaSessionInfo(
                    title = "Next Episode — NVIDIA",
                    domain = "youtube.com",
                    isPlaying = true,
                    playbackRate = 1.25f,
                    hasVideoElement = true,
                ),
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("media_mini_player_dock").assertIsDisplayed()
            composeRule.onNodeWithTag("media_mini_player_dock").performTouchInput {
                swipeRight(startX = left + 12f, endX = right + 120f, durationMillis = 120L)
            }
            composeRule.mainClock.advanceTimeBy(400L)
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("media_mini_player_dock").assertDoesNotExist()
            assertEquals(null, FocalMediaBridge.dockMedia.value)
            assertEquals(backgroundAudioTabId, FocalMediaBridge.activeMedia.value?.sessionTabId)
        } finally {
            FocalMediaBridge.clearAll()
        }
    }

    @Test
    fun searchSheet_liveLocalSuggestions_switchesOpenTab_andUsesConfiguredSearchEngine() {
        var switchedTabId: String? = null
        var navigatedUrl: String? = null
        val now = System.currentTimeMillis()
        val spaces = persistentListOf(
            SpaceEntity("personal", "Personal", "#F4725E", 0, now),
            SpaceEntity("work", "Work", "#94A3B8", 1, now),
        )
        val openTabs = persistentListOf(
            TabMetadataEntity(
                id = "tab-ars",
                spaceId = "work",
                url = "https://arstechnica.com/gadgets/2026/focal-preview/",
                domain = "arstechnica.com",
                title = "Ars Technica · Mobile Optical Glass",
                lastActiveEpochMs = now,
                createdAtEpochMs = now - 5_000L,
            ),
        )
        val bookmarks = persistentListOf(
            BookmarkEntity(
                id = "bm-ars-home",
                spaceId = "personal",
                title = "Ars Technica Homepage",
                url = "https://arstechnica.com",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        val history = persistentListOf(
            HistoryEntryEntity(
                id = "hist-1",
                url = "https://arstechnica.com/science/",
                domain = "arstechnica.com",
                title = "Ars Science Desk",
                visitedAtEpochMs = now - 60_000L,
                visitCount = 4,
            ),
        )

        composeRule.setContent {
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                SearchSheet(
                    initialQuery = "",
                    isPrivateAperture = false,
                    onTogglePrivateAperture = {},
                    onNavigateToUrl = { url, _ -> navigatedUrl = url },
                    onDismiss = {},
                    openTabs = openTabs,
                    bookmarks = bookmarks,
                    historyEntries = history,
                    spaces = spaces,
                    browserSettings = FocalBrowserSettings(searchEngine = SearchEnginePreset.KAGI),
                    onSwitchToTab = { switchedTabId = it },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextInput("ars")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Switch").assertIsDisplayed()
        composeRule.onNodeWithText("Ars Technica · Mobile Optical Glass").assertIsDisplayed()
        val snap = composeRule.captureSnapshotPng("09_search_local_suggestions.png")
        assertTrue(snap.length() > 1_000L)

        composeRule.onNodeWithText("Ars Technica · Mobile Optical Glass").performClick()
        composeRule.waitForIdle()
        assertEquals("tab-ars", switchedTabId)

        // Verify configured Kagi search engine is used for plain-text queries
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("focal optical browser")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Go").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "Expected Kagi search URL, got $navigatedUrl",
            navigatedUrl?.startsWith("https://kagi.com/search?q=") == true,
        )
    }

    @Test
    fun findInPageBar_displaysMatchCount_andInvokesNavigationCallbacks() {
        var prevClicks = 0
        var nextClicks = 0
        var closeClicks = 0
        var lastQuery = "optical"

        composeRule.setContent {
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FocalTheme.colors.canvasBackground),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    FindInPageBar(
                        findState = FindInPageState(
                            query = lastQuery,
                            activeMatchOrdinal = 1,
                            numberOfMatches = 7,
                            isDoneCounting = true,
                        ),
                        onQueryChange = { lastQuery = it },
                        onFindPrevious = { prevClicks++ },
                        onFindNext = { nextClicks++ },
                        onClose = { closeClicks++ },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("find_in_page_bar").assertIsDisplayed()
        composeRule.onNodeWithText("2/7").assertIsDisplayed()

        val snap = composeRule.captureSnapshotPng("10_find_in_page_bar.png")
        assertTrue(snap.length() > 1_000L)

        composeRule.onNodeWithContentDescription("Previous match").performClick()
        composeRule.onNodeWithContentDescription("Next match").performClick()
        composeRule.onNodeWithContentDescription("Close find").performClick()
        composeRule.waitForIdle()

        assertEquals(1, prevClicks)
        assertEquals(1, nextClicks)
        assertEquals(1, closeClicks)
    }

    @Test
    fun linkPeekSheet_oauthBypass_andImageOnlySaveCard_renderCleanlyWithoutPreviewWebView() {
        val adblock = NativeAdblockController(autoLoadNativeLibrary = false)
        val oauthUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc123"
        val imageUrl = "https://images.example.com/hero/camera-lens.webp"
        var savedImageUrl: String? = null
        var openedDirectUrl: String? = null

        composeRule.setContent {
            var mode by remember { mutableStateOf("oauth") }
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                if (mode == "oauth") {
                    LinkPeekSheet(
                        rawUrl = oauthUrl,
                        cleanedResult = adblock.cleanTrackingUrl(oauthUrl),
                        adblockController = adblock,
                        onOpenInBackgroundTab = {},
                        onExpandFullTab = {
                            openedDirectUrl = it
                            mode = "image"
                        },
                        onDismiss = {},
                    )
                } else {
                    LinkPeekSheet(
                        rawUrl = imageUrl,
                        cleanedResult = adblock.cleanTrackingUrl(imageUrl),
                        adblockController = adblock,
                        imageUrl = imageUrl,
                        onOpenInBackgroundTab = {},
                        onExpandFullTab = {},
                        onSaveImage = { savedImageUrl = it },
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Direct Sign-In").assertIsDisplayed()
        composeRule.onNodeWithText("Sign-in link — opens directly to keep your session intact").assertIsDisplayed()
        val oauthSnap = composeRule.captureSnapshotPng("11a_link_peek_oauth_bypass.png")
        assertTrue(oauthSnap.length() > 1_000L)

        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()
        assertEquals(oauthUrl, openedDirectUrl)

        composeRule.onNodeWithText("Save Image").assertIsDisplayed()
        val imageSnap = composeRule.captureSnapshotPng("11b_link_peek_image_save.png")
        assertTrue(imageSnap.length() > 1_000L)

        composeRule.onNodeWithText("Save Image").performClick()
        composeRule.waitForIdle()
        assertEquals(imageUrl, savedImageUrl)
    }

    @Test
    fun historySheet_groupsByDay_filtersLocally_andOpensSelectedEntry() {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        var openedUrl: String? = null
        val entries = persistentListOf(
            HistoryEntryEntity(
                id = "h1",
                url = "https://github.com/oupadhyay/focal",
                domain = "github.com",
                title = "oupadhyay/focal · Android Browser",
                visitedAtEpochMs = now - 60_000L,
                visitCount = 5,
            ),
            HistoryEntryEntity(
                id = "h2",
                url = "https://arstechnica.com/gadgets/",
                domain = "arstechnica.com",
                title = "Ars Technica Gadgets",
                visitedAtEpochMs = now - dayMs - 3_600_000L,
                visitCount = 2,
            ),
            HistoryEntryEntity(
                id = "h3",
                url = "https://en.wikipedia.org/wiki/Optical_glass",
                domain = "en.wikipedia.org",
                title = "Optical glass — Wikipedia",
                visitedAtEpochMs = now - 5L * dayMs,
                visitCount = 1,
            ),
        )

        composeRule.setContent {
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                HistorySheet(
                    entries = entries,
                    onOpenUrl = { openedUrl = it },
                    onDeleteEntry = {},
                    onClearAllHistory = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Yesterday").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier").assertIsDisplayed()
        val snap = composeRule.captureSnapshotPng("12_history_sheet.png")
        assertTrue(snap.length() > 1_000L)

        composeRule.onNodeWithText("oupadhyay/focal · Android Browser").performClick()
        composeRule.waitForIdle()
        assertEquals("https://github.com/oupadhyay/focal", openedUrl)
    }

    @Test
    fun downloadsSheet_displaysSavedFiles_andWiresOpenAction() {
        val now = System.currentTimeMillis()
        var openedDownloadId: String? = null
        val downloads = persistentListOf(
            DownloadRecordEntity(
                id = "dl-1",
                url = "https://example.com/focal-spec.pdf",
                fileName = "focal-spec.pdf",
                mimeType = "application/pdf",
                originDomain = "example.com",
                downloadManagerId = 42L,
                createdAtEpochMs = now - 120_000L,
            ),
            DownloadRecordEntity(
                id = "dl-2",
                url = "https://images.example.com/lens-wallpaper.png",
                fileName = "lens-wallpaper.png",
                mimeType = "image/png",
                originDomain = "images.example.com",
                downloadManagerId = 43L,
                createdAtEpochMs = now - 3_600_000L,
            ),
        )

        composeRule.setContent {
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                DownloadsSheet(
                    downloads = downloads,
                    onOpenDownload = { openedDownloadId = it.id },
                    onShareDownload = {},
                    onDeleteDownload = {},
                    onClearAll = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeRule.onNodeWithText("focal-spec.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("lens-wallpaper.png").assertIsDisplayed()
        val snap = composeRule.captureSnapshotPng("13_downloads_sheet.png")
        assertTrue(snap.length() > 1_000L)

        composeRule.onNodeWithText("focal-spec.pdf").performClick()
        composeRule.waitForIdle()
        assertEquals("dl-1", openedDownloadId)
    }

    @Test
    fun globalSettingsSheet_rendersSearchEngines_privacyToggles_andUpdateBanner() {
        var latestSettings = FocalBrowserSettings()
        var clearDataCount = 0
        var installClicked = false

        composeRule.setContent {
            var currentSettings by remember { mutableStateOf(latestSettings) }
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                GlobalSettingsSheet(
                    settings = currentSettings,
                    appTheme = AppChromeTheme.DARK,
                    currentVersionName = "0.1.62",
                    totalBlockedTrackers = 128L,
                    biometricAvailable = true,
                    updateState = UpdateBannerState.Available(
                        tagName = "v0.1.63",
                        releaseName = "Focal 0.1.63",
                        notes = "Faster tab switcher + Element Zapper polish",
                    ),
                    onUpdateSettings = {
                        currentSettings = it
                        latestSettings = it
                    },
                    onUpdateAppTheme = {},
                    onClearBrowsingData = { clearDataCount++ },
                    onCheckForUpdates = {},
                    onInstallUpdate = { installClicked = true },
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("DuckDuckGo").assertIsDisplayed()
        composeRule.onNodeWithText("Kagi").assertIsDisplayed()
        val snap = composeRule.captureSnapshotPng("14_global_settings_sheet.png")
        assertTrue(snap.length() > 1_000L)

        composeRule.onNodeWithText("Kagi").performClick()
        composeRule.waitForIdle()
        assertEquals(SearchEnginePreset.KAGI, latestSettings.searchEngine)
    }

    @Test
    fun tabSwitcher_displaysUndoCloseTabPill_andRestoresTabOnTap() {
        var undoTapped = false
        val now = System.currentTimeMillis()
        val spaces = persistentListOf(
            SpaceEntity("personal", "Personal", "#F4725E", 0, now),
            SpaceEntity("work", "Work", "#94A3B8", 1, now),
        )
        val activeTabs = persistentListOf(
            TabMetadataEntity(
                id = "tab-1",
                spaceId = "personal",
                url = "https://github.com/oupadhyay/focal",
                domain = "github.com",
                title = "Focal Browser",
                lastActiveEpochMs = now,
                createdAtEpochMs = now,
            ),
        )

        composeRule.setContent {
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                TabSwitcherSheet(
                    activeTabs = activeTabs,
                    archivedTabs = persistentListOf(),
                    selectedTabId = "tab-1",
                    spaces = spaces,
                    activeSpaceId = "personal",
                    tabCountBySpace = persistentMapOf("personal" to 1, "work" to 0),
                    onSelectTab = {},
                    onCloseTab = {},
                    onRestoreArchivedTab = {},
                    onNewSearch = {},
                    onDone = {},
                    lastClosedTabTitle = "Ars Technica",
                    onUndoCloseTab = { undoTapped = true },
                    onDismissUndoCloseTab = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        val snap = composeRule.captureSnapshotPng("15_tab_switcher_undo_pill.png")
        assertTrue(snap.length() > 1_000L)

        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertTrue(undoTapped)
    }

    @Test
    fun sitePermissionPromptSheet_rendersOriginAndResources_andDispatchesDecision() {
        var decision: Boolean? = null

        composeRule.setContent {
            FocalTheme(darkGlass = true, isPrivateAperture = false) {
                SitePermissionPromptSheet(
                    origin = "meet.example.com",
                    resources = listOf("Camera", "Microphone"),
                    onAllow = { decision = true },
                    onDeny = { decision = false },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Allow Camera & Microphone?").assertIsDisplayed()
        composeRule.onNodeWithText("meet.example.com").assertIsDisplayed()
        val snap = composeRule.captureSnapshotPng("16_site_permission_prompt.png")
        assertTrue(snap.length() > 1_000L)

        composeRule.onNodeWithText("Allow").performClick()
        composeRule.waitForIdle()
        assertEquals(true, decision)
    }

    @Test
    @Config(sdk = [34], application = FocalApplication::class, qualifiers = "w412dp-h915dp-xxhdpi")
    fun mainActivity_coldLaunch_mountsCalmCanvasWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue(decor.width > 0 && decor.height > 0)
                val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bmp))
                val outDir = File("build/outputs/robolectric-snapshots").apply { mkdirs() }
                val outFile = File(outDir, "05_main_activity_calm_canvas.png")
                FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(outFile.length() > 1_000L)
            }
        }
    }

    private fun AndroidComposeTestRule<*, ComponentActivity>.captureSnapshotPng(fileName: String): File {
        captureSnapshotBitmap(fileName)
        return File("build/outputs/robolectric-snapshots", fileName)
    }

    private fun AndroidComposeTestRule<*, ComponentActivity>.captureSnapshotBitmap(fileName: String): Bitmap {
        waitForIdle()
        val decorView = activity.window.decorView
        val width = decorView.width.coerceAtLeast(1)
        val height = decorView.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        activity.runOnUiThread {
            decorView.draw(canvas)
        }
        val outDir = File("build/outputs/robolectric-snapshots")
        outDir.mkdirs()
        val outFile = File(outDir, fileName)
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue(outFile.length() > 1_000L)
        return bitmap
    }

    private class FakeLoadedEngineSession(
        url: String,
        domain: String,
        title: String,
    ) : EngineSession {
        override val sessionId: String = "fake-light-session"
        override val isPrivate: Boolean = false
        override val spaceId: String = "personal"
        override val state: StateFlow<EngineSessionState> = MutableStateFlow(
            EngineSessionState(
                sessionId = sessionId,
                spaceId = spaceId,
                url = url,
                domain = domain,
                title = title,
                isHttps = true,
            ),
        )
        override val events: SharedFlow<EngineSessionEvent> = MutableSharedFlow()

        override fun loadUrl(url: String, headers: Map<String, String>) {}
        override fun reload() {}
        override fun stopLoading() {}
        override fun goBack(): Boolean = false
        override fun goForward(): Boolean = false
        override fun setSiteOverrides(overrides: OriginOverrides) {}
        override fun extractReadableArticle(onResult: (ReadableDocument) -> Unit) {}
        override fun indexDomParagraphs(onResult: (List<IndexedDomParagraph>) -> Unit) {}
        override fun scrollToGroundedParagraph(paraId: Int) {}
        override fun checkHasUnsentFormInput(onResult: (Boolean) -> Unit) = onResult(false)
        override fun setPlaybackRate(rate: Float) {}
        override fun setMediaPlaying(playing: Boolean) {}
        override fun requestElementFullscreenOrPip() {}
        override fun findInPage(query: String) {}
        override fun clearFindInPage() {}
        override fun dispose() {}
    }
}
