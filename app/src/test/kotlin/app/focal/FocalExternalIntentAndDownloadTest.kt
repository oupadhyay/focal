package app.focal

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FocalExternalIntentAndDownloadTest {

    @Test
    fun manifest_resolvesFocalForGithubAssetDownloadsAndBrowserSelectors() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pm = app.packageManager

        fun resolvesFocal(intent: Intent, flags: Int = PackageManager.MATCH_DEFAULT_ONLY): Boolean {
            return pm.queryIntentActivities(intent, flags).any {
                it.activityInfo?.packageName == app.packageName &&
                    it.activityInfo?.name == MainActivity::class.java.name
            }
        }

        val apkUrl = Uri.parse("https://github.com/oupadhyay/focal/releases/download/v0.1.72/focal-arm64-v8a.apk")
        val zipUrl = Uri.parse("https://github.com/oupadhyay/focal/releases/download/v0.1.72/focal-bridge-extension.zip")
        val txtUrl = Uri.parse("https://github.com/oupadhyay/focal/releases/download/v0.1.72/SHA256SUMS.txt")

        // GitHub Android app sends ACTION_VIEW with https URI + explicit asset MIME type
        assertTrue(
            "Focal must appear for HTTPS APK download intents",
            resolvesFocal(Intent(Intent.ACTION_VIEW).setDataAndType(apkUrl, "application/vnd.android.package-archive")),
        )
        assertTrue(
            "Focal must appear for HTTPS octet-stream download intents",
            resolvesFocal(Intent(Intent.ACTION_VIEW).setDataAndType(apkUrl, "application/octet-stream")),
        )
        assertTrue(
            "Focal must appear for HTTPS ZIP download intents",
            resolvesFocal(Intent(Intent.ACTION_VIEW).setDataAndType(zipUrl, "application/zip")),
        )
        assertTrue(
            "Focal must appear for HTTPS text/plain intents",
            resolvesFocal(Intent(Intent.ACTION_VIEW).setDataAndType(txtUrl, "text/plain")),
        )
        // Standard web links with null MIME type
        assertTrue(
            "Focal must match standard HTTPS links",
            resolvesFocal(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        )
        // System CATEGORY_APP_BROWSER discovery with MATCH_DEFAULT_ONLY (startActivity / chooser path)
        assertTrue(
            "Focal must advertise CATEGORY_APP_BROWSER with CATEGORY_DEFAULT",
            resolvesFocal(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)),
        )
        val browserSelectorIntent = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_BROWSER,
        ).apply {
            data = apkUrl
        }
        assertTrue(
            "Focal must match Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_BROWSER)",
            resolvesFocal(requireNotNull(browserSelectorIntent.selector)),
        )
        // Launcher icon resolution
        assertTrue(
            "Focal must keep its standard LAUNCHER entry",
            resolvesFocal(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), flags = 0),
        )
        // Share link to Focal
        assertTrue(
            "Focal must accept ACTION_SEND text/plain links",
            resolvesFocal(Intent(Intent.ACTION_SEND).setType("text/plain")),
        )
        // Must NOT hijack local file:// or content:// media intents
        assertFalse(
            "Focal must not hijack local file:// intents",
            resolvesFocal(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/photo.jpg"), "image/jpeg")),
        )
        assertFalse(
            "Focal must not hijack local content:// intents",
            resolvesFocal(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/1"), "image/png")),
        )
    }

    @Test
    fun extractLaunchUrl_handlesActionViewAndSharedActionSendText() {
        assertEquals(
            "https://github.com/oupadhyay/focal/releases/download/v0.1.72/focal-arm64-v8a.apk",
            FocalExternalIntentAndDownloadMath.extractLaunchUrl(
                action = Intent.ACTION_VIEW,
                dataString = "https://github.com/oupadhyay/focal/releases/download/v0.1.72/focal-arm64-v8a.apk",
                sharedText = null,
            ),
        )
        assertEquals(
            "focal-pair://v1?secret=abc",
            FocalExternalIntentAndDownloadMath.extractLaunchUrl(
                action = Intent.ACTION_VIEW,
                dataString = "focal-pair://v1?secret=abc",
                sharedText = null,
            ),
        )
        assertEquals(
            "https://github.com/oupadhyay/focal",
            FocalExternalIntentAndDownloadMath.extractLaunchUrl(
                action = Intent.ACTION_SEND,
                dataString = null,
                sharedText = "Check out Focal Browser (https://github.com/oupadhyay/focal).",
            ),
        )
        assertNull(
            FocalExternalIntentAndDownloadMath.extractLaunchUrl(
                action = Intent.ACTION_SEND,
                dataString = null,
                sharedText = "plain text without any link",
            ),
        )
    }

    @Test
    fun resolveDownloadMimeType_andPreserveExtension_fixGithubOctetStreamApkBug() {
        val apkUrl = "https://github.com/oupadhyay/focal/releases/download/v0.1.72/focal-arm64-v8a.apk"
        assertEquals(
            "application/vnd.android.package-archive",
            FocalExternalIntentAndDownloadMath.resolveDownloadMimeType(
                url = apkUrl,
                fileNameHint = "focal-arm64-v8a.apk",
                serverMimeType = "application/octet-stream",
            ),
        )
        assertEquals(
            "focal-arm64-v8a.apk",
            FocalExternalIntentAndDownloadMath.preserveKnownFileExtension(
                url = apkUrl,
                guessedFileName = "focal-arm64-v8a.bin",
            ),
        )
        assertEquals(
            "application/zip",
            FocalExternalIntentAndDownloadMath.resolveDownloadMimeType(
                url = "https://github.com/oupadhyay/focal/releases/download/v0.1.72/focal-bridge-extension.zip",
                fileNameHint = null,
                serverMimeType = "application/octet-stream",
            ),
        )

        // Real GitHub S3 redirect: opaque UUID path, filename only in Content-Disposition, octet-stream MIME
        val s3Url =
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/12345/00000000-0000-0000-0000-000000000000?X-Amz-Algorithm=AWS4-HMAC-SHA256"
        val disposition = "attachment; filename=focal-arm64-v8a.apk"
        val extracted = FocalExternalIntentAndDownloadMath.extractContentDispositionFileName(disposition)
        assertEquals("focal-arm64-v8a.apk", extracted)
        assertEquals(
            "application/vnd.android.package-archive",
            FocalExternalIntentAndDownloadMath.resolveDownloadMimeType(
                url = s3Url,
                fileNameHint = extracted,
                serverMimeType = "application/octet-stream",
            ),
        )
        assertEquals(
            "focal-arm64-v8a.apk",
            FocalExternalIntentAndDownloadMath.preserveKnownFileExtension(
                url = s3Url,
                guessedFileName = "focal-arm64-v8a.apk.bin",
                contentDispositionFileName = extracted,
            ),
        )
        assertEquals(
            "focal release 2.zip",
            FocalExternalIntentAndDownloadMath.extractContentDispositionFileName(
                "attachment; filename*=UTF-8''focal%20release%202.zip",
            ),
        )
    }
}
