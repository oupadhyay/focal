package app.focal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalReleaseUpdaterTest {

    @Test
    fun isNewerVersion_comparesSemanticAndCommitCountTagsAccurately() {
        assertTrue(FocalReleaseVersionMath.isNewerVersion("v0.1.62", "0.1.61"))
        assertTrue(FocalReleaseVersionMath.isNewerVersion("0.2.0", "0.1.99"))
        assertTrue(FocalReleaseVersionMath.isNewerVersion("v1.0.0", "0.9.99"))

        assertFalse(FocalReleaseVersionMath.isNewerVersion("v0.1.61", "0.1.61"))
        assertFalse(FocalReleaseVersionMath.isNewerVersion("0.1.60", "0.1.61"))
        assertFalse(FocalReleaseVersionMath.isNewerVersion("", "0.1.61"))
    }

    @Test
    fun selectBestApkAsset_matchesPrimaryAbiThenFallsBackCleanly() {
        val assets = listOf(
            ReleaseAssetInfo("focal-bridge-extension.zip", "https://github.com/oupadhyay/focal/releases/download/v0.2.0/ext.zip"),
            ReleaseAssetInfo("focal-x86_64.apk", "https://github.com/oupadhyay/focal/releases/download/v0.2.0/focal-x86_64.apk"),
            ReleaseAssetInfo("focal-arm64-v8a.apk", "https://github.com/oupadhyay/focal/releases/download/v0.2.0/focal-arm64-v8a.apk"),
        )

        val armPick = FocalReleaseVersionMath.selectBestApkAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("focal-arm64-v8a.apk", armPick?.name)

        val x86Pick = FocalReleaseVersionMath.selectBestApkAsset(assets, listOf("x86_64"))
        assertEquals("focal-x86_64.apk", x86Pick?.name)

        // Rejects non-HTTPS APK URLs
        val insecure = listOf(ReleaseAssetInfo("focal-arm64-v8a.apk", "http://example.com/app.apk"))
        assertNull(FocalReleaseVersionMath.selectBestApkAsset(insecure, listOf("arm64-v8a")))
    }
}
