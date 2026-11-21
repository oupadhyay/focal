package app.focal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.database.BookmarkEntity
import app.focal.database.SpaceEntity
import app.focal.database.TabMetadataEntity
import app.focal.design.FocalTheme
import app.focal.engine.api.OriginOverrides
import app.focal.feature.reader.PageMenuAndSiteOverridesSheet
import app.focal.feature.search.SearchSheet
import app.focal.feature.spaces.BookmarksSheet
import app.focal.feature.spaces.BookmarksSheetUiState
import app.focal.feature.tabs.TabSwitcherSheet
import app.focal.sync.PresenceTabItem
import app.focal.sync.SyncConnectionStatus
import app.focal.sync.SyncSessionUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
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
    fun pageMenuSheet_rendersLensAndYabGradeActionsAndSiteSettings() {
        composeRule.setContent {
            var overrides by remember {
                mutableStateOf(OriginOverrides(origin = "github.com"))
            }
            FocalTheme(darkGlass = true) {
                PageMenuAndSiteOverridesSheet(
                    domain = "github.com",
                    fullUrl = "https://github.com/oupadhyay/focal",
                    isPrivateAperture = false,
                    canGoBack = true,
                    canGoForward = false,
                    blockedCountInTab = 7L,
                    overrides = overrides,
                    onGoBack = {},
                    onGoForward = {},
                    onReload = {},
                    onCopyCleanLink = {},
                    onShareCleanLink = {},
                    onSaveBookmark = {},
                    onOpenBookmarks = {},
                    onOpenReaderMode = {},
                    onUpdateSiteOverrides = { overrides = it },
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

        // Switch to Site Settings subtab and capture snapshot matching user's 2nd screenshot
        composeRule.onAllNodesWithText("Site Settings")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Block Ads & Trackers").assertIsDisplayed()
        composeRule.onNodeWithText("Dark Web Pages").assertIsDisplayed()
        composeRule.onNodeWithText("Editorial Fonts").assertIsDisplayed()

        val settingsSnap = composeRule.captureSnapshotPng("04b_page_menu_site_settings.png")
        assertTrue(settingsSnap.length() > 1_000L)
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
        return outFile
    }
}
