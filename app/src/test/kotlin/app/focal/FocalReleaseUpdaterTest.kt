package app.focal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalReleaseUpdaterTest {

    @Test
    fun isNewerVersion_comparesSemanticAndCommitCountTagsAccurately() {
        assertTrue(FocalReleaseVersionMath.isNewerVersion("v0.1.71", "0.1.70"))
        assertTrue(FocalReleaseVersionMath.isNewerVersion("0.2.0", "0.1.99"))
        assertTrue(FocalReleaseVersionMath.isNewerVersion("v1.0.0", "0.9.99"))

        assertFalse(FocalReleaseVersionMath.isNewerVersion("v0.1.70", "0.1.70"))
        assertFalse(FocalReleaseVersionMath.isNewerVersion("0.1.69", "0.1.70"))
        assertFalse(FocalReleaseVersionMath.isNewerVersion("", "0.1.70"))
    }

    @Test
    fun selectBestApkAsset_matchesPrimaryAbiThenFallsBackCleanly() {
        val assets = listOf(
            ReleaseAssetInfo("focal-bridge-extension.zip", "https://github.com/oupadhyay/focal/releases/download/v0.2.0/ext.zip"),
            ReleaseAssetInfo("focal-x86_64.apk", "https://github.com/oupadhyay/focal/releases/download/v0.2.0/focal-x86_64.apk"),
            ReleaseAssetInfo("focal-arm64-v8a.apk", "https://github.com/oupadhyay/focal/releases/download/v0.2.0/focal-arm64-v8a.apk"),
            ReleaseAssetInfo("SHA256SUMS.txt", "https://github.com/oupadhyay/focal/releases/download/v0.2.0/SHA256SUMS.txt"),
        )

        val armPick = FocalReleaseVersionMath.selectBestApkAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("focal-arm64-v8a.apk", armPick?.name)

        val x86Pick = FocalReleaseVersionMath.selectBestApkAsset(assets, listOf("x86_64"))
        assertEquals("focal-x86_64.apk", x86Pick?.name)

        val checksumPick = FocalReleaseVersionMath.selectChecksumAsset(assets)
        assertEquals("SHA256SUMS.txt", checksumPick?.name)

        // Rejects non-HTTPS APK URLs
        val insecure = listOf(ReleaseAssetInfo("focal-arm64-v8a.apk", "http://example.com/app.apk"))
        assertNull(FocalReleaseVersionMath.selectBestApkAsset(insecure, listOf("arm64-v8a")))
    }

    @Test
    fun parseExpectedSha256_extractsMatchingDigestAndRejectsMalformedEntries() {
        val validArmHash = "39ee877ef5404c990d3419145f95b3c31284a19cdc9930a58fb912b16f413245"
        val validX86Hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
        val sumsText = """
            # Focal release checksums
            $validArmHash  focal-arm64-v8a.apk
            $validX86Hash *./dist/focal-x86_64.apk
            not-a-valid-hash  focal-bad.apk
        """.trimIndent()

        assertEquals(
            validArmHash,
            FocalReleaseVersionMath.parseExpectedSha256(sumsText, "focal-arm64-v8a.apk"),
        )
        assertEquals(
            validX86Hash.lowercase(),
            FocalReleaseVersionMath.parseExpectedSha256(sumsText, "focal-x86_64.apk"),
        )
        assertNull(FocalReleaseVersionMath.parseExpectedSha256(sumsText, "focal-bad.apk"))
        assertNull(FocalReleaseVersionMath.parseExpectedSha256(sumsText, "missing.apk"))
    }

    @Test
    fun shouldAutoCheck_respectsTogglePrivateApertureAnd24HourCadence() {
        val dayMs = FocalReleaseVersionMath.DEFAULT_AUTO_CHECK_INTERVAL_MS
        val now = 1_800_000_000_000L

        // First-ever check runs when enabled and non-private
        assertTrue(
            FocalReleaseVersionMath.shouldAutoCheck(
                autoCheckEnabled = true,
                isPrivateAperture = false,
                lastCheckedEpochMs = 0L,
                nowEpochMs = now,
            ),
        )

        // Never runs inside Private Aperture ◎
        assertFalse(
            FocalReleaseVersionMath.shouldAutoCheck(
                autoCheckEnabled = true,
                isPrivateAperture = true,
                lastCheckedEpochMs = 0L,
                nowEpochMs = now,
            ),
        )

        // Never runs when toggle is OFF
        assertFalse(
            FocalReleaseVersionMath.shouldAutoCheck(
                autoCheckEnabled = false,
                isPrivateAperture = false,
                lastCheckedEpochMs = 0L,
                nowEpochMs = now,
            ),
        )

        // Throttled within 24 hours
        assertFalse(
            FocalReleaseVersionMath.shouldAutoCheck(
                autoCheckEnabled = true,
                isPrivateAperture = false,
                lastCheckedEpochMs = now - (dayMs - 1_000L),
                nowEpochMs = now,
            ),
        )

        // Fires again once 24 hours have elapsed
        assertTrue(
            FocalReleaseVersionMath.shouldAutoCheck(
                autoCheckEnabled = true,
                isPrivateAperture = false,
                lastCheckedEpochMs = now - dayMs,
                nowEpochMs = now,
            ),
        )
    }
}
