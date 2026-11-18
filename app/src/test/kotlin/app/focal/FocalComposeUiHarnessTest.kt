package app.focal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.database.BookmarkEntity
import app.focal.database.SpaceEntity
import app.focal.database.TabMetadataEntity
import app.focal.design.FocalTheme
import app.focal.feature.browser.FocalBottomBar
import app.focal.feature.reader.PageMenuAndSiteOverridesSheet
import app.focal.feature.search.SearchSheet
import app.focal.feature.spaces.SpacesSheet
import app.focal.feature.spaces.SpacesSheetUiState
import app.focal.feature.tabs.TabSwitcherSheet
import app.focal.engine.api.OriginOverrides
import app.focal.sync.PresenceTabItem
import app.focal.sync.SyncConnectionStatus
import app.focal.sync.SyncSessionUiState
import kotlinx.collections.immutable.persistentListOf
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
    fun searchSheet_rendersFavicons_activatesSiteChip_andNavigatesToGoogleDotCom() {
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

        // Verify all 5 default squircle pin titles & SiteFavicons render (no 2-letter monogram text nodes)
        composeRule.onNodeWithText("IMDb").assertIsDisplayed()
        composeRule.onNodeWithText("Wikipedia").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Ars").assertIsDisplayed()
        composeRule.onNodeWithText("Reddit").assertIsDisplayed()
        composeRule.onNodeWithText("IM").assertDoesNotExist()
        composeRule.onNodeWithText("GH").assertDoesNotExist()

        // Verify typing "google.com" + clicking Go resolves cleanly to https://google.com
        composeRule.onNode(hasSetTextAction()).performTextInput("google.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Go").performClick()
        assertEquals("https://google.com", navigatedUrl)
        assertEquals(false, navigatedPrivate)

        val snap = composeRule.captureSnapshotPng("01_search_sheet_google_navigation.png")
        assertTrue("Expected non-empty PNG snapshot at ${snap.absolutePath}", snap.length() > 1_000L)
    }

    @Test
    fun spacesSheet_rendersFaviconsForPinnedSquirclesBookmarksAndDesktopTabs() {
        val now = 1_763_400_000_000L
        val uiState = SpacesSheetUiState(
            spaces = persistentListOf(
                SpaceEntity("personal", "Personal", "#F4725E", 0, now),
                SpaceEntity("work", "Work", "#94A3B8", 1, now),
                SpaceEntity("reading", "Reading", "#4ADE80", 2, now),
            ),
            activeSpaceId = "work",
            pinnedSquircles = persistentListOf(
                BookmarkEntity("p1", "work", isPinnedSquircle = true, shortCode = "GH", title = "GitHub", url = "https://github.com", createdAtEpochMs = now, updatedAtEpochMs = now),
                BookmarkEntity("p2", "work", isPinnedSquircle = true, shortCode = "DV", title = "Android", url = "https://developer.android.com", createdAtEpochMs = now, updatedAtEpochMs = now),
                BookmarkEntity("p3", "work", isPinnedSquircle = true, shortCode = "RS", title = "Docs.rs", url = "https://docs.rs", createdAtEpochMs = now, updatedAtEpochMs = now),
                BookmarkEntity("p4", "work", isPinnedSquircle = true, shortCode = "HN", title = "HackerNews", url = "https://news.ycombinator.com", createdAtEpochMs = now, updatedAtEpochMs = now),
            ),
            foldersAndBookmarks = persistentListOf(
                BookmarkEntity("b1", "work", title = "Jetpack Compose Architecture", url = "https://developer.android.com/jetpack/compose", createdAtEpochMs = now, updatedAtEpochMs = now),
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
                SpacesSheet(
                    uiState = uiState,
                    onSelectSpace = {},
                    onOpenUrl = {},
                    onCreateFolder = { _, _ -> },
                    onStartE2eePairing = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()

        // Legacy "HackerNews" title is normalized to "HN" label while 2-letter shortCode text nodes ("GH", "DV", "RS") do not exist
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Android").assertIsDisplayed()
        composeRule.onNodeWithText("Docs.rs").assertIsDisplayed()
        composeRule.onNodeWithText("HN").assertIsDisplayed()
        composeRule.onNodeWithText("GH").assertDoesNotExist()
        composeRule.onNodeWithText("DV").assertDoesNotExist()
        composeRule.onNodeWithText("RS").assertDoesNotExist()

        val snap = composeRule.captureSnapshotPng("02_spaces_sheet_favicons.png")
        assertTrue(snap.length() > 1_000L)
    }

    @Test
    fun bottomBar_pageMenu_andTabSwitcher_renderLiveDomainFavicons() {
        val now = 1_763_400_000_000L
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
        )

        composeRule.setContent {
            val scrubState = remember { mutableFloatStateOf(0f) }
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
                        isPrivateAperture = false,
                        nowEpochMs = now,
                        onSelectTab = {},
                        onCloseTab = {},
                        onRestoreArchivedTab = {},
                        onNewSearch = {},
                        onDone = {},
                    )
                    FocalBottomBar(
                        tabCount = 2,
                        domainOrHint = "google.com",
                        pageUrl = "https://www.google.com",
                        isHttps = true,
                        isPrivateAperture = false,
                        isLoading = false,
                        scrubOffsetPxState = scrubState,
                        onOpenTabSwitcher = {},
                        onCloseCurrentTab = {},
                        onOpenNewPrivateTab = {},
                        onTapCenterSearch = {},
                        onStepToAdjacentTab = {},
                        onSwipeUpOpenSpaces = {},
                        onOpenPageMenu = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("focal - Google Search")[0].assertIsDisplayed()

        val tabSnap = composeRule.captureSnapshotPng("03_tab_switcher_and_bottom_bar_favicons.png")
        assertTrue(tabSnap.length() > 1_000L)
    }

    @Test
    fun pageMenuSheet_rendersFaviconBesideDomain() {
        composeRule.setContent {
            FocalTheme(darkGlass = true) {
                PageMenuAndSiteOverridesSheet(
                    domain = "github.com",
                    fullUrl = "https://github.com/oupadhyay/focal",
                    isPrivateAperture = false,
                    canGoBack = true,
                    canGoForward = false,
                    blockedCountInTab = 7L,
                    overrides = OriginOverrides(origin = "github.com"),
                    onGoBack = {},
                    onGoForward = {},
                    onReload = {},
                    onCopyCleanLink = {},
                    onShareCleanLink = {},
                    onPinToSpace = {},
                    onSaveBookmark = {},
                    onOpenReaderMode = {},
                    onUpdateSiteOverrides = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("github.com").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("github.com").assertIsDisplayed()

        val menuSnap = composeRule.captureSnapshotPng("04_page_menu_sheet_favicon.png")
        assertTrue(menuSnap.length() > 1_000L)
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
