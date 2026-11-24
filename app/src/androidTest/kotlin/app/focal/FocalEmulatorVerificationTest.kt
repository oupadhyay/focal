package app.focal

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.adblock.NativeAdblockController
import app.focal.design.AppChromeTheme
import app.focal.design.FocalAppearanceStore
import app.focal.feature.search.LaunchKeyboardStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end Android Emulator verification suite running on a real KVM-accelerated API 34 device.
 *
 * Verifies:
 * 1. Search-First cold launch + real soft IME + `LaunchKeyboardStore` persisted height + native
 *    Rust `libfocal_adblock.so` JNI adblock engine blocking `doubleclick.net`.
 * 2. Dismissing Search Sheet reveals Calm Optical Launch Canvas & 3-part Smoked Glass bottom bar,
 *    and tapping the bottom bar pill re-opens Search Sheet cleanly.
 * 3. Double-space `git  ` activates `[GitHub x]` chip (with zero permanent hint banner).
 * 4. Zero-network offline inline unit conversion (`180 lbs in kg` -> `81.65 kg`) + real Android
 *    `ClipboardManager` copy and inline calculator (`48 * 1.0825` -> `51.96`).
 * 5. Real Chromium `WebView` navigation (`example.com`) with zero crashes.
 * 6. Page Menu (`Translate Page` + `Reader Mode`) & `Site Settings` toggles + Back dismissal.
 *
 * Captures full-window SurfaceFlinger screenshots (including live WebView and soft IME) to
 * `/sdcard/Android/data/app.focal/files/emulator-shots/` for visual inspection.
 */
@RunWith(AndroidJUnit4::class)
class FocalEmulatorVerificationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun endToEnd_coldLaunch_rustAdblock_searchInlineAnswers_liveWebView_andPageMenu() {
        val app = composeRule.activity.application as FocalApplication

        // 1. Cold launch opens Search-First Sheet immediately with real favicons & no hint banner
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("GitHub").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Search or enter address").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Wikipedia").assertIsDisplayed()
        composeRule.onNodeWithText("for site search", substring = true).assertDoesNotExist()

        // Verify native Rust adblock-rs engine (libfocal_adblock.so) initialized on Android x86_64
        val nativeAdblock = app.adblockController as? NativeAdblockController
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (nativeAdblock != null && !nativeAdblock.isNativeEngineActive && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
        }
        assertTrue(
            "Expected native Rust libfocal_adblock.so engine to be active on emulator",
            nativeAdblock?.isNativeEngineActive == true,
        )
        val adDecision = app.adblockController.checkNetworkRequest(
            requestUrl = "https://doubleclick.net/ads.js",
            sourceUrl = "https://example.com",
            resourceType = "script",
        )
        assertTrue("Expected native adblock-rs to block doubleclick.net", adDecision.matched)

        // Wait for first-boot Gboard IME animation + LaunchKeyboardStore 180ms stabilize debounce
        // (uses composeRule.waitUntil so Compose's test clock advances LaunchedEffect delays)
        val keyboardStore = LaunchKeyboardStore(composeRule.activity)
        val initialImeReady = runCatching {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                keyboardStore.readHeightPx(isLandscape = false) > 0
            }
            true
        }.getOrDefault(false)
        if (!initialImeReady) {
            // Fresh emulator boots can still be starting Gboard on first run; retry focus + IME show
            for (attempt in 1..3) {
                composeRule.onNode(hasSetTextAction()).performClick()
                composeRule.runOnUiThread {
                    val imm = composeRule.activity.getSystemService(
                        android.view.inputmethod.InputMethodManager::class.java,
                    )
                    val targetView = composeRule.activity.currentFocus
                        ?: composeRule.activity.window.decorView
                    imm?.showSoftInput(targetView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    androidx.core.view.WindowCompat.getInsetsController(
                        composeRule.activity.window,
                        composeRule.activity.window.decorView,
                    ).show(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                val becameReady = runCatching {
                    composeRule.waitUntil(timeoutMillis = 7_000L) {
                        keyboardStore.readHeightPx(isLandscape = false) > 0
                    }
                    true
                }.getOrDefault(false)
                if (becameReady) break
                if (attempt < 3) SystemClock.sleep(1_000L)
            }
        }
        val persistedImePx = keyboardStore.readHeightPx(isLandscape = false)
        SystemClock.sleep(250L)
        captureEmulatorScreenshot("01_emulator_cold_launch_search.png")
        assertTrue(
            "Expected LaunchKeyboardStore to persist real soft keyboard height (>0px), got $persistedImePx",
            persistedImePx > 0,
        )

        // 2. Dismiss Search Sheet via Back to verify Calm Optical Launch Canvas & 3-part bottom bar
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("GitHub").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("Focal").fetchSemanticsNodes().isNotEmpty()
        }
        SystemClock.sleep(450L)
        composeRule.onNodeWithText("Focal").assertIsDisplayed()
        composeRule.onNodeWithText("Search or enter address").assertIsDisplayed()
        captureEmulatorScreenshot("02_emulator_calm_canvas.png")

        // Re-open Search Sheet from bottom bar pill (exercises zero-jump persisted IME height)
        composeRule.onNodeWithText("Search or enter address").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("GitHub").fetchSemanticsNodes().isNotEmpty()
        }

        // 2b. Verify 1-tap inline Default Search Engine switcher right inside Search Sheet
        composeRule.onNodeWithTag("search_engine_switcher_button").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Google AI").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Default Search Engine").assertIsDisplayed()
        composeRule.onNodeWithText("Google").assertIsDisplayed()
        composeRule.onNodeWithText("Google AI").assertIsDisplayed()
        SystemClock.sleep(250L)
        captureEmulatorScreenshot("03b_emulator_search_engine_picker.png")
        composeRule.onNodeWithText("Google AI").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Default Search Engine").fetchSemanticsNodes().isEmpty()
        }

        // 3. Verify double-space "git  " activates [GitHub x] chip
        composeRule.onNode(hasSetTextAction()).performTextInput("git  ")
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            runCatching {
                composeRule.onNodeWithContentDescription("Remove GitHub filter").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        SystemClock.sleep(350L)
        captureEmulatorScreenshot("03_emulator_github_chip.png")

        // 4. Dismiss chip, verify offline inline unit conversion + real Android clipboard copy + math
        composeRule.onNodeWithContentDescription("Remove GitHub filter").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("180 lbs in kg")
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("81.65 kg").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("81.65 kg").assertIsDisplayed()
        SystemClock.sleep(300L)
        captureEmulatorScreenshot("04_emulator_inline_answer.png")

        // Tap inline answer card to copy directly to real Android ClipboardManager
        composeRule.onNodeWithText("81.65 kg").performClick()
        composeRule.waitForIdle()
        var copiedText: String? = null
        composeRule.runOnIdle {
            val cm = composeRule.activity.getSystemService(ClipboardManager::class.java)
            copiedText = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        assertEquals("81.6466 kg", copiedText)

        // Verify offline calculator answer
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("48 * 1.0825")
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("= 51.96").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("= 51.96").assertIsDisplayed()

        // 5. Navigate to real https://example.com in Chromium WebView (verifies zero WebView crash)
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("example.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Go").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000L) {
            composeRule.onAllNodesWithText("Go").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("example.com").fetchSemanticsNodes().isNotEmpty()
        }
        // Allow real Chromium WebView to finish painting page pixels
        SystemClock.sleep(3_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("example.com").assertIsDisplayed()
        val webViewportBounds = composeRule.onNodeWithTag("web_engine_viewport_host")
            .fetchSemanticsNode().boundsInRoot
        val bottomBarTabsBounds = composeRule.onNodeWithContentDescription("Open Tabs")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "WebView bottom (${webViewportBounds.bottom}) must sit cleanly above bottom bar top (${bottomBarTabsBounds.top})",
            webViewportBounds.bottom <= bottomBarTabsBounds.top + 2f,
        )
        captureEmulatorScreenshot("05_emulator_live_webview.png")

        // 6. Open Page Menu (Actions + Bookmarks + on-device Translate Page row + Site Settings subtab)
        composeRule.onNodeWithContentDescription("Page Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Translate Page").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Bookmarks").assertIsDisplayed()
        composeRule.onNodeWithText("Reader Mode").assertIsDisplayed()
        composeRule.onNodeWithText("Translate Page").assertIsDisplayed()
        composeRule.onNodeWithText("Pin").assertDoesNotExist()
        SystemClock.sleep(300L)
        captureEmulatorScreenshot("06_emulator_page_menu.png")

        composeRule.onAllNodesWithText("Site Settings")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Block Ads & Trackers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Dark Web Pages").assertIsDisplayed()
        composeRule.onNodeWithText("Editorial Fonts").assertIsDisplayed()
        composeRule.onNodeWithText("Auto Reader Mode").assertIsDisplayed()
        composeRule.onNodeWithText("App Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        composeRule.onNodeWithText("System").assertIsDisplayed()
        assertEquals(
            AppChromeTheme.DARK,
            FocalAppearanceStore(composeRule.activity).readTheme(),
        )
        SystemClock.sleep(300L)
        captureEmulatorScreenshot("07_emulator_site_settings.png")

        // Dismiss Page Menu via Android Back dispatcher and verify clean return to WebView
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Block Ads & Trackers").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("example.com").assertIsDisplayed()

        // 7. Open YAB-style Stacked Tab Switcher (Dark default even from light example.com + top Space groups)
        composeRule.onNodeWithContentDescription("Open Tabs").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Personal").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Work").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Personal").assertIsDisplayed()
        composeRule.onNodeWithText("Work").assertIsDisplayed()
        composeRule.onNodeWithText("Private").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bookmarks").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
        SystemClock.sleep(450L)
        captureEmulatorScreenshot("07b_emulator_yab_tab_switcher.png")

        // 7b. Press-and-hold front tab card and drag onto "Work" pill to move tab between groups
        val frontCard = composeRule.onNodeWithTag("front_tab_card")
        val cardBounds = frontCard.fetchSemanticsNode().boundsInRoot
        val workBounds = composeRule.onNodeWithText("Work").fetchSemanticsNode().boundsInRoot
        val targetInCardLocal = Offset(
            x = workBounds.center.x - cardBounds.left,
            y = workBounds.center.y - cardBounds.top,
        )
        frontCard.performTouchInput {
            down(center)
            advanceEventTime(380L)
            val start = center
            for (step in 1..8) {
                advanceEventTime(25L)
                val fraction = step / 8f
                moveTo(
                    Offset(
                        x = start.x + (targetInCardLocal.x - start.x) * fraction,
                        y = start.y + (targetInCardLocal.y - start.y) * fraction,
                    ),
                )
            }
            advanceEventTime(80L)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Moved to Work").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Moved to Work").assertIsDisplayed()
        composeRule.onNodeWithText("View").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Moved to Work").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("Example Domain").fetchSemanticsNodes().isNotEmpty()
        }
        SystemClock.sleep(350L)
        captureEmulatorScreenshot("07d_emulator_tab_moved_to_work.png")

        // 8. Open dedicated Bookmarks sheet from Tab Switcher bottom-left button (zero Pinned section)
        composeRule.onNodeWithContentDescription("Bookmarks").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Bookmarks").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("All").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Bookmarks").assertIsDisplayed()
        composeRule.onNodeWithText("Pinned").assertDoesNotExist()
        SystemClock.sleep(300L)
        captureEmulatorScreenshot("07c_emulator_bookmarks_sheet.png")

        // Dismiss Bookmarks via Back and verify clean return to WebView
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("New Folder").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("example.com").assertIsDisplayed()

        // 9. Open Local Browsing History via Page Menu (verifies Room v3 HistoryDao logged example.com)
        composeRule.onNodeWithContentDescription("Page Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Find in Page").assertIsDisplayed()
        composeRule.onNodeWithText("Hide Elements").assertIsDisplayed()
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        composeRule.onNodeWithText("History").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("example.com").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        SystemClock.sleep(300L)
        captureEmulatorScreenshot("07e_emulator_history_sheet.png")

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Today").fetchSemanticsNodes().isEmpty()
        }

        // 10. Open Global Settings via Page Menu (Search Engine picker, GPC, Scroll-Hide, Opt-In Updates)
        composeRule.onNodeWithContentDescription("Page Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Global Privacy Control").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("DuckDuckGo").assertIsDisplayed()
        composeRule.onNodeWithText("Kagi").assertIsDisplayed()
        composeRule.onNodeWithText("Global Privacy Control").assertIsDisplayed()
        composeRule.onNodeWithText("Hide Bar on Scroll").assertIsDisplayed()
        SystemClock.sleep(300L)
        captureEmulatorScreenshot("07f_emulator_global_settings.png")

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Global Privacy Control").fetchSemanticsNodes().isEmpty()
        }

        // 11. Open Find in Page over live example.com WebView, search "Domain", and close cleanly
        composeRule.onNodeWithContentDescription("Page Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Find in Page").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Find in Page").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            runCatching {
                composeRule.onNodeWithTag("find_in_page_bar").assertIsDisplayed()
                composeRule.onNodeWithTag("find_in_page_input").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("find_in_page_input").performTextInput("Domain")
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("1/", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Domain").assertIsDisplayed()
        SystemClock.sleep(300L)
        composeRule.waitForIdle()
        captureEmulatorScreenshot("07g_emulator_find_in_page.png")

        composeRule.onNodeWithContentDescription("Close find").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("example.com").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("example.com").assertIsDisplayed()
    }

    private fun captureEmulatorScreenshot(fileName: String) {
        composeRule.waitForIdle()
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = uiAutomation.takeScreenshot() ?: return
        val dir = File(composeRule.activity.getExternalFilesDir(null), "emulator-shots").apply {
            mkdirs()
        }
        val outFile = File(dir, fileName)
        FileOutputStream(outFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue("Screenshot $fileName was empty", outFile.length() > 2_000L)
    }
}
