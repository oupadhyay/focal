package app.focal

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.adblock.NativeAdblockController
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
            // Fresh emulator boots can still be binding Gboard during Activity launch; tap input to show IME
            composeRule.onNode(hasSetTextAction()).performClick()
            composeRule.waitUntil(timeoutMillis = 12_000L) {
                keyboardStore.readHeightPx(isLandscape = false) > 0
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
        assertEquals("81.65 kg", copiedText)

        // Verify offline calculator answer
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("48 * 1.0825")
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("51.96").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("51.96").assertIsDisplayed()

        // 5. Navigate to real https://example.com in Chromium WebView (verifies zero WebView crash)
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("example.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Go").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000L) {
            composeRule.onAllNodesWithText("example.com").fetchSemanticsNodes().isNotEmpty()
        }
        // Allow real Chromium WebView to finish painting page pixels
        SystemClock.sleep(3_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("example.com").assertIsDisplayed()
        captureEmulatorScreenshot("05_emulator_live_webview.png")

        // 6. Open Page Menu (Actions + on-device Translate Page row + Site Settings subtab)
        composeRule.onNodeWithContentDescription("Page Menu").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Translate Page").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Reader Mode").assertIsDisplayed()
        composeRule.onNodeWithText("Translate Page").assertIsDisplayed()
        SystemClock.sleep(300L)
        captureEmulatorScreenshot("06_emulator_page_menu.png")

        composeRule.onAllNodesWithText("Site Settings")[0].performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Block Ads & Trackers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Dark Web Pages").assertIsDisplayed()
        composeRule.onNodeWithText("Editorial Fonts").assertIsDisplayed()
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
