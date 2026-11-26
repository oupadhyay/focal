package app.focal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

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
    val checksumAsset: ReleaseAssetInfo? = null,
)

sealed interface ReleaseCheckResult {
    data class UpToDate(val currentVersion: String) : ReleaseCheckResult
    data class UpdateAvailable(val currentVersion: String, val release: FocalReleaseInfo) : ReleaseCheckResult
    data class Failed(val message: String) : ReleaseCheckResult
}

sealed interface VerifiedApkResult {
    data class Ready(val apkFile: File) : VerifiedApkResult
    data class Failed(val message: String) : VerifiedApkResult
}

/**
 * Pure JVM version comparison, checksum parsing, asset selection, and daily check-cadence rules
 * for [FocalReleaseUpdater].
 */
object FocalReleaseVersionMath {
    const val DEFAULT_AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

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

    fun selectChecksumAsset(assets: List<ReleaseAssetInfo>): ReleaseAssetInfo? {
        return assets.firstOrNull {
            it.name.equals("SHA256SUMS.txt", ignoreCase = true) &&
                it.downloadUrl.startsWith("https://", ignoreCase = true)
        }
    }

    /**
     * Parses a standard `sha256sum` manifest (`<64-hex>  <filename>` or `<64-hex> *<filename>`)
     * and returns the expected lowercase 64-char SHA-256 digest for [targetFileName], or `null`
     * if no valid entry matches.
     */
    fun parseExpectedSha256(sumsText: String, targetFileName: String): String? {
        val normalizedTarget = targetFileName.trim().substringAfterLast('/')
        if (normalizedTarget.isEmpty()) return null

        for (rawLine in sumsText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val firstWhitespace = line.indexOfFirst { it.isWhitespace() }
            if (firstWhitespace <= 0) continue
            val hash = line.substring(0, firstWhitespace).trim()
            if (!SHA256_HEX_REGEX.matches(hash)) continue
            val fileToken = line.substring(firstWhitespace)
                .trim()
                .removePrefix("*")
                .removePrefix("./")
                .substringAfterLast('/')
            if (fileToken.equals(normalizedTarget, ignoreCase = true)) {
                return hash.lowercase()
            }
        }
        return null
    }

    /**
     * Decides whether Focal's quiet daily background update check should fire.
     * Strictly disabled while Private Aperture (`◎`) is active or auto-check is turned off.
     */
    fun shouldAutoCheck(
        autoCheckEnabled: Boolean,
        isPrivateAperture: Boolean,
        lastCheckedEpochMs: Long,
        nowEpochMs: Long,
        intervalMs: Long = DEFAULT_AUTO_CHECK_INTERVAL_MS,
    ): Boolean {
        if (!autoCheckEnabled || isPrivateAperture) return false
        if (lastCheckedEpochMs <= 0L) return true
        val elapsed = nowEpochMs - lastCheckedEpochMs
        return elapsed < 0L || elapsed >= intervalMs
    }
}

/**
 * Minimal local metadata store for Focal's quiet daily update checker.
 * Stores strictly two fields (`last_check_epoch_ms` and `dismissed_tag_name`) — zero URLs or
 * browsing history, never modified inside Private Aperture (`◎`).
 */
class FocalUpdateCheckStore internal constructor(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun readLastCheckEpochMs(): Long = prefs.getLong(KEY_LAST_CHECK_MS, 0L)

    fun recordCheckTimestamp(epochMs: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK_MS, epochMs).apply()
    }

    fun readDismissedTag(): String = prefs.getString(KEY_DISMISSED_TAG, "").orEmpty()

    fun dismissTag(tagName: String) {
        prefs.edit().putString(KEY_DISMISSED_TAG, tagName.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "focal_update_check_state"
        private const val KEY_LAST_CHECK_MS = "last_check_epoch_ms"
        private const val KEY_DISMISSED_TAG = "dismissed_tag_name"
    }
}

/**
 * Native in-app GitHub Releases self-updater (`oupadhyay/focal`) powered by Android's
 * [PackageInstaller.Session].
 *
 * - Queries `https://api.github.com/repos/oupadhyay/focal/releases/latest` (zero tokens required).
 * - Streams the matching ABI APK with real-time progress and clean cancellation.
 * - Verifies SHA-256 digest against the release's `SHA256SUMS.txt` (when present) AND verifies
 *   on-device that `packageName`, `longVersionCode`, and signing certificate match the installed
 *   Focal app before committing.
 * - Uses [PackageInstaller.Session] with `USER_ACTION_NOT_REQUIRED` on Android 12+ so repeat
 *   in-app updates install seamlessly without leaving Focal or opening a file manager.
 */
object FocalReleaseUpdater {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/oupadhyay/focal/releases/latest"
    private const val USER_AGENT = "Focal-Android-Updater/2.0"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val UPDATES_SUBDIR = "updates"

    fun currentAppVersionName(context: Context): String {
        return getInstalledPackageInfo(context, flags = 0)?.versionName ?: "0.1.0"
    }

    fun currentAppVersionCode(context: Context): Long {
        val info = getInstalledPackageInfo(context, flags = 0) ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }

    fun createUnknownSourcesSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    }

    fun cleanupCachedUpdates(context: Context) {
        runCatching {
            val updatesDir = File(context.cacheDir, UPDATES_SUBDIR)
            if (updatesDir.isDirectory) {
                updatesDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    suspend fun checkLatestRelease(context: Context): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = currentAppVersionName(context)
        try {
            val conn = openHttpsConnection(
                rawUrl = LATEST_RELEASE_API,
                acceptHeader = "application/vnd.github+json",
                readTimeoutMs = READ_TIMEOUT_MS,
            )
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                conn.disconnect()
                return@withContext ReleaseCheckResult.UpToDate(currentVersion)
            }
            if (code !in 200..299) {
                conn.disconnect()
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
            val checksumAsset = FocalReleaseVersionMath.selectChecksumAsset(parsedAssets)
            ReleaseCheckResult.UpdateAvailable(
                currentVersion = currentVersion,
                release = FocalReleaseInfo(
                    tagName = tagName,
                    releaseName = json.optString("name").ifBlank { tagName },
                    notes = json.optString("body").trim().take(600),
                    htmlUrl = json.optString("html_url").ifBlank { "https://github.com/oupadhyay/focal/releases" },
                    apkAsset = bestApk,
                    checksumAsset = checksumAsset,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReleaseCheckResult.Failed(e.message ?: "Unable to reach GitHub Releases")
        }
    }

    /**
     * Downloads the release APK with live streaming progress, validates its SHA-256 against
     * `SHA256SUMS.txt` when available, and validates on-device package identity + signing
     * certificate match against the currently installed Focal app.
     */
    suspend fun downloadAndVerifyApk(
        context: Context,
        release: FocalReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onVerifying: () -> Unit = {},
    ): VerifiedApkResult = withContext(Dispatchers.IO) {
        val asset = release.apkAsset
            ?: return@withContext VerifiedApkResult.Failed("No compatible APK asset found in ${release.tagName}")

        val updatesDir = File(context.cacheDir, UPDATES_SUBDIR).apply { mkdirs() }
        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeName = "${safeTag}_${asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val targetFile = File(updatesDir, safeName)
        val tmpFile = File(updatesDir, "$safeName.part")

        // Prune stale update files from older versions
        updatesDir.listFiles()?.forEach { file ->
            if (file.name != safeName) {
                file.delete()
            }
        }

        val expectedSha256 = release.checksumAsset?.let { checksumAsset ->
            fetchExpectedSha256(checksumAsset.downloadUrl, asset.name)
        }

        // Reuse an already-downloaded file only if it passes full SHA-256 + signature verification
        if (targetFile.isFile && targetFile.length() > 0L) {
            onVerifying()
            val cachedDigest = computeFileSha256(targetFile)
            val hashMatches = expectedSha256 == null || expectedSha256.equals(cachedDigest, ignoreCase = true)
            if (hashMatches && verifyApkMatchesInstalledFocal(context, targetFile) == null) {
                return@withContext VerifiedApkResult.Ready(targetFile)
            }
            targetFile.delete()
        }

        try {
            val conn = openHttpsConnection(
                rawUrl = asset.downloadUrl,
                acceptHeader = "application/octet-stream",
                readTimeoutMs = DOWNLOAD_READ_TIMEOUT_MS,
            )
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext VerifiedApkResult.Failed("Download failed (HTTP $code)")
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReportedBytes = 0L
            var lastReportedMs = 0L
            val buffer = ByteArray(32 * 1024)

            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (downloaded - lastReportedBytes >= 64 * 1024 || now - lastReportedMs >= 60L) {
                            lastReportedBytes = downloaded
                            lastReportedMs = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    output.fd.sync()
                }
            }
            conn.disconnect()
            onProgress(downloaded, totalBytes.coerceAtLeast(downloaded))

            onVerifying()
            val actualSha256 = digest.digest().toLowerHex()
            if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
                tmpFile.delete()
                return@withContext VerifiedApkResult.Failed("Download verification failed (SHA-256 mismatch)")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.delete()
                return@withContext VerifiedApkResult.Failed("Unable to stage downloaded update")
            }

            val signatureError = verifyApkMatchesInstalledFocal(context, targetFile)
            if (signatureError != null) {
                targetFile.delete()
                return@withContext VerifiedApkResult.Failed(signatureError)
            }

            VerifiedApkResult.Ready(targetFile)
        } catch (ce: CancellationException) {
            tmpFile.delete()
            throw ce
        } catch (e: Exception) {
            tmpFile.delete()
            VerifiedApkResult.Failed(e.message ?: "Failed to download update")
        }
    }

    /**
     * Streams a verified [apkFile] into an Android [PackageInstaller.Session] and commits it with
     * [FocalUpdateInstallReceiver] as the explicit status callback receiver.
     */
    suspend fun commitPackageInstallerSession(
        context: Context,
        apkFile: File,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            return@withContext Result.failure(IllegalStateException("Verified update APK is missing"))
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }

        var sessionId = -1
        var session: PackageInstaller.Session? = null
        try {
            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
            session.openWrite("focal_update.apk", 0L, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                    }
                }
                session.fsync(out)
            }

            val callbackIntent = Intent(context, FocalUpdateInstallReceiver::class.java).apply {
                action = FocalUpdateInstallReceiver.ACTION_INSTALL_RESULT
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val statusReceiver = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                pendingFlags,
            ).intentSender

            session.commit(statusReceiver)
            session.close()
            session = null
            Result.success(sessionId)
        } catch (ce: CancellationException) {
            runCatching { session?.abandon() }
            runCatching { session?.close() }
            if (sessionId != -1) runCatching { packageInstaller.abandonSession(sessionId) }
            throw ce
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            runCatching { session?.close() }
            if (sessionId != -1) runCatching { packageInstaller.abandonSession(sessionId) }
            Result.failure(e)
        }
    }

    private fun fetchExpectedSha256(checksumUrl: String, apkFileName: String): String? {
        return runCatching {
            val conn = openHttpsConnection(
                rawUrl = checksumUrl,
                acceptHeader = "text/plain",
                readTimeoutMs = READ_TIMEOUT_MS,
            )
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            conn.disconnect()
            FocalReleaseVersionMath.parseExpectedSha256(text, apkFileName)
        }.getOrNull()
    }

    /**
     * Opens an HTTPS connection following up to [MAX_REDIRECTS] hops while enforcing strict `https://`
     * on every redirect hop.
     */
    private fun openHttpsConnection(
        rawUrl: String,
        acceptHeader: String,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        var currentUrl = rawUrl
        repeat(MAX_REDIRECTS + 1) {
            require(currentUrl.startsWith("https://", ignoreCase = true)) {
                "Refusing non-HTTPS update URL"
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val status = conn.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")?.trim()
                conn.disconnect()
                require(!location.isNullOrBlank()) { "Redirect missing Location header" }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while fetching update")
    }

    private fun verifyApkMatchesInstalledFocal(context: Context, apkFile: File): String? {
        val archiveInfo = getArchivePackageInfo(context, apkFile)
            ?: return "Downloaded APK could not be parsed"
        if (archiveInfo.packageName != context.packageName) {
            return "Downloaded APK package (${archiveInfo.packageName}) does not match Focal"
        }
        val candidateCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
        val installedCode = currentAppVersionCode(context)
        if (installedCode > 0L && candidateCode <= installedCode) {
            return "Downloaded build ($candidateCode) is not newer than installed ($installedCode)"
        }

        val installedCerts = extractSignerDigests(
            getInstalledPackageInfo(context, PackageManager.GET_SIGNING_CERTIFICATES),
        )
        val archiveCerts = extractSignerDigests(archiveInfo)
        if (installedCerts.isNotEmpty() && archiveCerts.isNotEmpty()) {
            if (installedCerts.intersect(archiveCerts).isEmpty()) {
                return "Update signing certificate does not match installed Focal build"
            }
        }
        return null
    }

    private fun getInstalledPackageInfo(context: Context, flags: Int): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, flags)
            }
        }.getOrNull()
    }

    private fun getArchivePackageInfo(context: Context, apkFile: File): PackageInfo? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }
        }.getOrNull()
    }

    private fun extractSignerDigests(info: PackageInfo?): Set<String> {
        val signingInfo = info?.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()
        return signatures.mapTo(LinkedHashSet()) { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).toLowerHex()
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String {
        val chars = CharArray(size * 2)
        val hex = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }
}
