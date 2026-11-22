package app.focal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
)

data class FocalReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val notes: String,
    val htmlUrl: String,
    val apkAsset: ReleaseAssetInfo?,
)

sealed interface ReleaseCheckResult {
    data class UpToDate(val currentVersion: String) : ReleaseCheckResult
    data class UpdateAvailable(val currentVersion: String, val release: FocalReleaseInfo) : ReleaseCheckResult
    data class Failed(val message: String) : ReleaseCheckResult
}

/**
 * Pure JVM version comparison and GitHub Release asset selection helpers for [FocalReleaseUpdater].
 */
object FocalReleaseVersionMath {

    fun normalizeVersionTuple(rawVersion: String): List<Int> {
        val stripped = rawVersion.trim().removePrefix("v").removePrefix("V")
        val core = stripped.substringBefore('-').substringBefore('+')
        return core.split('.').mapNotNull { part -> part.toIntOrNull() }
    }

    fun isNewerVersion(candidateTag: String, currentVersion: String): Boolean {
        val cand = normalizeVersionTuple(candidateTag)
        val curr = normalizeVersionTuple(currentVersion)
        if (cand.isEmpty() || curr.isEmpty()) return false
        val maxLen = maxOf(cand.size, curr.size)
        for (i in 0 until maxLen) {
            val c = cand.getOrElse(i) { 0 }
            val r = curr.getOrElse(i) { 0 }
            if (c != r) return c > r
        }
        return false
    }

    fun selectBestApkAsset(
        assets: List<ReleaseAssetInfo>,
        supportedAbis: List<String>,
    ): ReleaseAssetInfo? {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.downloadUrl.startsWith("https://", ignoreCase = true)
        }
        if (apks.isEmpty()) return null
        for (abi in supportedAbis) {
            apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) } ?: apks.first()
    }
}

/**
 * Strictly opt-in manual GitHub Releases update checker & APK installer (`oupadhyay/focal`).
 *
 * Makes zero automatic background network requests; runs strictly when the user taps
 * **"Check for Updates"** inside Global Settings.
 */
object FocalReleaseUpdater {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/oupadhyay/focal/releases/latest"
    private const val USER_AGENT = "Focal-Android-Updater/1.0"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    fun currentAppVersionName(context: Context): String {
        return runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: "0.1.0"
        }.getOrDefault("0.1.0")
    }

    suspend fun checkLatestRelease(context: Context): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = currentAppVersionName(context)
        try {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext ReleaseCheckResult.UpToDate(currentVersion)
            }
            if (code !in 200..299) {
                return@withContext ReleaseCheckResult.Failed("GitHub returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = org.json.JSONObject(body)
            val tagName = json.optString("tag_name").trim()
            if (tagName.isBlank() || !FocalReleaseVersionMath.isNewerVersion(tagName, currentVersion)) {
                return@withContext ReleaseCheckResult.UpToDate(currentVersion)
            }

            val assetsJson = json.optJSONArray("assets")
            val parsedAssets = buildList {
                if (assetsJson != null) {
                    for (i in 0 until assetsJson.length()) {
                        val obj = assetsJson.optJSONObject(i) ?: continue
                        val name = obj.optString("name")
                        val url = obj.optString("browser_download_url")
                        val size = obj.optLong("size", 0L)
                        if (name.isNotBlank() && url.isNotBlank()) {
                            add(ReleaseAssetInfo(name = name, downloadUrl = url, sizeBytes = size))
                        }
                    }
                }
            }
            val bestApk = FocalReleaseVersionMath.selectBestApkAsset(
                assets = parsedAssets,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            ReleaseCheckResult.UpdateAvailable(
                currentVersion = currentVersion,
                release = FocalReleaseInfo(
                    tagName = tagName,
                    releaseName = json.optString("name").ifBlank { tagName },
                    notes = json.optString("body").trim().take(600),
                    htmlUrl = json.optString("html_url").ifBlank { "https://github.com/oupadhyay/focal/releases" },
                    apkAsset = bestApk,
                ),
            )
        } catch (e: Exception) {
            ReleaseCheckResult.Failed(e.message ?: "Unable to reach GitHub Releases")
        }
    }

    suspend fun downloadAndPromptInstall(
        context: Context,
        release: FocalReleaseInfo,
    ): Boolean = withContext(Dispatchers.IO) {
        val asset = release.apkAsset
        if (asset == null) {
            withContext(Dispatchers.Main) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
            return@withContext true
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetFile = File(updatesDir, safeName)
        val tmpFile = File(updatesDir, "$safeName.part")

        try {
            val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext false
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            if (targetFile.exists()) targetFile.delete()
            tmpFile.renameTo(targetFile)

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(installIntent)
            }
            true
        } catch (_: Exception) {
            tmpFile.delete()
            false
        }
    }
}
