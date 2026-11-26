package app.focal.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class BuiltInFaviconMark {
    IMDB,
    GITHUB,
    HACKER_NEWS,
    WIKIPEDIA,
    ANDROID_DEV,
    REDDIT,
    YOUTUBE,
    ARS_TECHNICA,
    RUST_DOCS,
    GOOGLE,
    DUCKDUCKGO,
}

/**
 * Privacy-preserving, two-level (`LruCache` RAM + `cacheDir/favicons` PNG) site favicon store.
 *
 * - Fetches favicons **directly from the target origin** (`apple-touch-icon.png` / `favicon.ico` /
 *   `<link rel="icon">`), never leaking visited or bookmarked domains to third-party icon APIs.
 * - Strictly skips disk & shared-memory caching when `isPrivate == true` (`◎` Private Aperture).
 * - Exposes per-host [StateFlow] streams so loading one favicon recomposes only composables for
 *   that exact host, and bounds concurrent origin HTTP fetches via a 4-permit [Semaphore].
 */
object FaviconCache {
    private const val MAX_MEMORY_ENTRIES = 128
    private const val MAX_HOST_FLOW_ENTRIES = 256
    private const val MAX_CONCURRENT_NETWORK_FETCHES = 4
    private const val MAX_ICON_DIMENSION_PX = 96
    private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_500
    private const val MAX_REDIRECT_HOPS = 4
    private const val FAVICON_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private data class CachedIcon(
        val bitmap: Bitmap,
        val imageBitmap: ImageBitmap,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightByHost = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val failedHostsSession = ConcurrentHashMap.newKeySet<String>()
    private val networkFetchSemaphore = Semaphore(permits = MAX_CONCURRENT_NETWORK_FETCHES)

    private val emptyHostFlow: StateFlow<Bitmap?> = MutableStateFlow<Bitmap?>(null).asStateFlow()
    private val hostFlows = ConcurrentHashMap<String, MutableStateFlow<Bitmap?>>()

    private val memoryCache = object : LinkedHashMap<String, CachedIcon>(MAX_MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedIcon>?): Boolean {
            if (size > MAX_MEMORY_ENTRIES) {
                val evictedHost = eldest?.key
                if (evictedHost != null) {
                    val flow = hostFlows[evictedHost]
                    if (flow != null && flow.subscriptionCount.value == 0) {
                        hostFlows.remove(evictedHost, flow)
                    }
                }
                return true
            }
            return false
        }
    }

    /**
     * Pure-JVM host normalizer safe for both Android runtime and JVM unit tests.
     * Strips leading `www.` / `m.` / `mobile.` while keeping meaningful subdomains
     * (`news.ycombinator.com`, `developer.android.com`, `en.wikipedia.org`).
     */
    fun normalizeHost(urlOrDomain: String): String {
        val raw = urlOrDomain.trim().replace("%s", "")
        if (raw.isEmpty() || raw.equals("about:blank", ignoreCase = true)) return ""
        if (raw.startsWith("focal://", ignoreCase = true) || raw.startsWith("focal-pair://", ignoreCase = true)) {
            return ""
        }

        val candidateWithScheme = if ("://" in raw) raw else "https://$raw"
        val extractedHost = try {
            URI(candidateWithScheme).host
        } catch (_: Exception) {
            null
        } ?: candidateWithScheme
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')

        val clean = extractedHost
            .trim()
            .trimEnd('.')
            .lowercase()
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("mobile.")

        return if ('.' in clean || clean == "localhost") clean else ""
    }

    fun resolveBuiltInMark(hostOrUrl: String): BuiltInFaviconMark? {
        val host = if ('/' in hostOrUrl || ':' in hostOrUrl) normalizeHost(hostOrUrl) else hostOrUrl.lowercase()
        if (host.isEmpty()) return null
        return when {
            host == "imdb.com" || host.endsWith(".imdb.com") -> BuiltInFaviconMark.IMDB
            host == "github.com" || host.endsWith(".github.com") -> BuiltInFaviconMark.GITHUB
            host == "news.ycombinator.com" || host == "hn.algolia.com" || host == "ycombinator.com" ->
                BuiltInFaviconMark.HACKER_NEWS
            host == "wikipedia.org" || host.endsWith(".wikipedia.org") -> BuiltInFaviconMark.WIKIPEDIA
            host == "developer.android.com" || host == "android.com" || host.endsWith(".android.com") ->
                BuiltInFaviconMark.ANDROID_DEV
            host == "reddit.com" || host.endsWith(".reddit.com") -> BuiltInFaviconMark.REDDIT
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" ->
                BuiltInFaviconMark.YOUTUBE
            host == "arstechnica.com" || host.endsWith(".arstechnica.com") -> BuiltInFaviconMark.ARS_TECHNICA
            host == "docs.rs" || host == "crates.io" || host == "rust-lang.org" || host.endsWith(".rust-lang.org") ->
                BuiltInFaviconMark.RUST_DOCS
            host == "google.com" || host.endsWith(".google.com") -> BuiltInFaviconMark.GOOGLE
            host == "duckduckgo.com" || host.endsWith(".duckduckgo.com") -> BuiltInFaviconMark.DUCKDUCKGO
            else -> null
        }
    }

    /**
     * Direct-origin candidate icon URLs ordered from sharpest high-DPI touch icon to standard `.ico`.
     */
    fun buildOriginIconCandidates(host: String, explicitIconUrl: String? = null): List<String> {
        if (host.isBlank() || host == "localhost") return emptyList()
        return buildList(4) {
            if (!explicitIconUrl.isNullOrBlank() &&
                (explicitIconUrl.startsWith("https://", ignoreCase = true) ||
                    explicitIconUrl.startsWith("http://", ignoreCase = true))
            ) {
                add(explicitIconUrl.trim())
            }
            add("https://$host/apple-touch-icon.png")
            add("https://$host/favicon.ico")
            if (host.count { it == '.' } == 1) {
                add("https://www.$host/favicon.ico")
            }
        }.distinct()
    }

    /**
     * Scans an `.ico` buffer for an embedded PNG (`89 50 4E 47 0D 0A 1A 0A`) if Skia's top-level
     * `.ico` directory decode returns `null` on multi-entry desktop `.ico` files.
     */
    internal fun findEmbeddedPngOffset(bytes: ByteArray): Int {
        if (bytes.size < 24) return -1
        val sig0 = 0x89.toByte()
        val sig1 = 0x50.toByte()
        val sig2 = 0x4E.toByte()
        val sig3 = 0x47.toByte()
        val sig4 = 0x0D.toByte()
        val sig5 = 0x0A.toByte()
        val sig6 = 0x1A.toByte()
        val sig7 = 0x0A.toByte()
        for (i in 0..(bytes.size - 16)) {
            if (
                bytes[i] == sig0 &&
                bytes[i + 1] == sig1 &&
                bytes[i + 2] == sig2 &&
                bytes[i + 3] == sig3 &&
                bytes[i + 4] == sig4 &&
                bytes[i + 5] == sig5 &&
                bytes[i + 6] == sig6 &&
                bytes[i + 7] == sig7
            ) {
                return i
            }
        }
        return -1
    }

    /**
     * Computes the largest power-of-two `BitmapFactory.Options.inSampleSize` keeping decoded
     * dimensions near [targetMaxPx] without allocating multi-megabyte intermediate bitmaps.
     */
    internal fun computePowerOfTwoSampleSize(
        width: Int,
        height: Int,
        targetMaxPx: Int = MAX_ICON_DIMENSION_PX,
    ): Int {
        if (width <= 0 || height <= 0 || targetMaxPx <= 0) return 1
        var sampleSize = 1
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        while ((maxDim / (sampleSize * 2)) >= targetMaxPx && (minDim / (sampleSize * 2)) >= 1) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Returns a per-host [StateFlow] of the cached [Bitmap] so composables observing one domain
     * never recompose when unrelated domains finish loading.
     */
    fun observeHost(urlOrDomain: String): StateFlow<Bitmap?> {
        val host = normalizeHost(urlOrDomain)
        if (host.isEmpty()) return emptyHostFlow
        pruneUnsubscribedHostFlowsIfNeeded()
        val flow = hostFlows.computeIfAbsent(host) {
            MutableStateFlow(getMemoryCachedBitmap(host))
        }
        if (flow.value == null) {
            getMemoryCachedBitmap(host)?.let { flow.value = it }
        }
        return flow.asStateFlow()
    }

    fun getMemoryCached(urlOrDomain: String): ImageBitmap? {
        val host = normalizeHost(urlOrDomain)
        if (host.isEmpty()) return null
        synchronized(memoryCache) {
            return memoryCache[host]?.takeUnless { it.bitmap.isRecycled }?.imageBitmap
        }
    }

    private fun getMemoryCachedBitmap(host: String): Bitmap? {
        synchronized(memoryCache) {
            return memoryCache[host]?.bitmap?.takeUnless { it.isRecycled }
        }
    }

    private fun putMemoryCache(host: String, bitmap: Bitmap): ImageBitmap {
        val imageBitmap = bitmap.asImageBitmap()
        synchronized(memoryCache) {
            memoryCache[host] = CachedIcon(bitmap = bitmap, imageBitmap = imageBitmap)
        }
        hostFlows[host]?.value = bitmap
        pruneUnsubscribedHostFlowsIfNeeded()
        return imageBitmap
    }

    private fun pruneUnsubscribedHostFlowsIfNeeded() {
        if (hostFlows.size <= MAX_HOST_FLOW_ENTRIES) return
        for ((host, flow) in hostFlows) {
            if (flow.subscriptionCount.value == 0) {
                val isInMemory = synchronized(memoryCache) { memoryCache.containsKey(host) }
                if (!isInMemory) {
                    hostFlows.remove(host, flow)
                }
            }
        }
    }

    /**
     * Records a live [Bitmap] delivered by `WebChromeClient.onReceivedIcon`.
     * Never persists or shares icons from Private Aperture (`isPrivate == true`).
     * Offloads all bitmap scaling, immutable-copying, and PNG encoding off the main thread.
     */
    fun onLiveIconReceived(
        urlOrDomain: String,
        bitmap: Bitmap,
        appContext: Context,
        isPrivate: Boolean,
    ) {
        if (isPrivate || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val host = normalizeHost(urlOrDomain)
        if (host.isEmpty()) return
        // Avoid replacing a high-res touch icon or crisp built-in mark with a tiny 16x16 legacy ico
        if (resolveBuiltInMark(host) != null && (bitmap.width < 28 || bitmap.height < 28)) return

        val appCtx = appContext.applicationContext
        scope.launch {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@launch
            val prepared = withContext(Dispatchers.Default) {
                if (bitmap.isRecycled) return@withContext null
                val scaled = scaleDownIfNeeded(bitmap)
                if (scaled.isMutable) {
                    val immutable = runCatching { scaled.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    if (immutable != null && scaled !== bitmap && !scaled.isRecycled) {
                        scaled.recycle()
                    }
                    immutable ?: scaled
                } else {
                    scaled
                }
            } ?: return@launch
            if (prepared.isRecycled) return@launch
            putMemoryCache(host, prepared)
            failedHostsSession.remove(host)
            writeDiskCache(appCtx, host, prepared)
        }
    }

    /**
     * Prefetches a page's `<link rel="apple-touch-icon">` or `<link rel="icon">` URL discovered at runtime.
     */
    fun prefetchExplicitIconUrl(
        pageUrlOrDomain: String,
        iconUrl: String,
        appContext: Context,
        isPrivate: Boolean,
    ) {
        if (isPrivate || iconUrl.isBlank()) return
        val host = normalizeHost(pageUrlOrDomain)
        if (host.isEmpty()) return
        scope.launch {
            ensureLoaded(
                appContext = appContext.applicationContext,
                urlOrDomain = host,
                allowNetworkFetch = true,
                explicitIconUrl = iconUrl,
            )
        }
    }

    suspend fun ensureLoaded(
        appContext: Context,
        urlOrDomain: String,
        allowNetworkFetch: Boolean,
        explicitIconUrl: String? = null,
    ): ImageBitmap? {
        val host = normalizeHost(urlOrDomain)
        if (host.isEmpty()) return null

        if (explicitIconUrl == null) {
            synchronized(memoryCache) {
                memoryCache[host]?.takeUnless { it.bitmap.isRecycled }?.let { return it.imageBitmap }
            }
        }

        // 1. Check local disk cache (`cacheDir/favicons/<sha256>.png`) strictly on Dispatchers.IO
        if (explicitIconUrl == null) {
            val diskBmp = withContext(Dispatchers.IO) {
                readDiskCache(appContext, host)?.let { decoded ->
                    val normalized = scaleDownIfNeeded(decoded)
                    if (normalized !== decoded && !decoded.isRecycled) {
                        decoded.recycle()
                    }
                    normalized
                }
            }
            if (diskBmp != null) {
                return putMemoryCache(host, diskBmp)
            }
        }

        if (!allowNetworkFetch) return null
        val hasBuiltIn = resolveBuiltInMark(host) != null
        // Skip blind external network probes when a crisp built-in vector brand mark already exists
        if (hasBuiltIn && explicitIconUrl == null) return null
        if (explicitIconUrl == null && failedHostsSession.contains(host)) return null

        val key = if (explicitIconUrl != null) "$host|$explicitIconUrl" else host
        val deferred = inFlightByHost.computeIfAbsent(key) {
            scope.async {
                try {
                    networkFetchSemaphore.withPermit {
                        val candidates = buildOriginIconCandidates(host, explicitIconUrl)
                        for (candidate in candidates) {
                            val decoded = downloadAndDecodeBitmap(candidate) ?: continue
                            if (hasBuiltIn && (decoded.width < 28 || decoded.height < 28)) {
                                if (!decoded.isRecycled) decoded.recycle()
                                continue
                            }
                            val normalizedBmp = scaleDownIfNeeded(decoded)
                            if (normalizedBmp !== decoded && !decoded.isRecycled) {
                                decoded.recycle()
                            }
                            writeDiskCache(appContext, host, normalizedBmp)
                            failedHostsSession.remove(host)
                            return@withPermit putMemoryCache(host, normalizedBmp)
                        }
                        if (explicitIconUrl == null) {
                            failedHostsSession.add(host)
                        }
                        null
                    }
                } finally {
                    inFlightByHost.remove(key)
                }
            }
        }
        return runCatching { deferred.await() }.getOrNull()
    }

    private fun downloadAndDecodeBitmap(initialUrl: String): Bitmap? {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECT_HOPS) {
            val parsed = runCatching { URL(currentUrl) }.getOrNull() ?: return null
            val protocol = parsed.protocol?.lowercase()
            if (protocol != "https" && protocol != "http") return null
            val targetHost = parsed.host ?: return null
            if (isDisallowedLocalAddress(targetHost)) return null

            var conn: HttpURLConnection? = null
            try {
                conn = (parsed.openConnection() as? HttpURLConnection) ?: return null
                conn.instanceFollowRedirects = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", FAVICON_USER_AGENT)
                conn.setRequestProperty("Accept", "image/png,image/webp,image/apng,image/*,*/*;q=0.8")

                val code = conn.responseCode
                if (code in 301..308) {
                    val location = conn.getHeaderField("Location")?.trim() ?: return null
                    currentUrl = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return null

                val bytes = conn.inputStream.use { readBoundedBytes(it, MAX_DOWNLOAD_BYTES) }
                if (bytes.size < 16) return null

                val direct = decodeSampledByteArray(bytes, 0, bytes.size)
                if (direct != null) {
                    return direct
                }
                val pngOffset = findEmbeddedPngOffset(bytes)
                if (pngOffset >= 0) {
                    return decodeSampledByteArray(bytes, pngOffset, bytes.size - pngOffset)
                }
                return null
            } catch (_: Throwable) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun decodeSampledByteArray(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        targetMaxPx: Int = MAX_ICON_DIMENSION_PX,
    ): Bitmap? {
        if (offset < 0 || length <= 0 || offset + length > bytes.size) return null
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, offset, length, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = computePowerOfTwoSampleSize(
                width = boundsOpts.outWidth,
                height = boundsOpts.outHeight,
                targetMaxPx = targetMaxPx,
            )
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, offset, length, decodeOpts)
        return decoded?.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun isDisallowedLocalAddress(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".internal")) return true
        return try {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress
        } catch (_: Throwable) {
            true
        }
    }

    private fun readBoundedBytes(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(8_192)
        val buf = ByteArray(4_096)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            val allowed = minOf(read, maxBytes - total)
            if (allowed > 0) {
                out.write(buf, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) break
        }
        return out.toByteArray()
    }

    private fun scaleDownIfNeeded(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= MAX_ICON_DIMENSION_PX && h <= MAX_ICON_DIMENSION_PX) return src
        val ratio = minOf(
            MAX_ICON_DIMENSION_PX.toFloat() / w.toFloat(),
            MAX_ICON_DIMENSION_PX.toFloat() / h.toFloat(),
        )
        val targetW = (w * ratio).toInt().coerceAtLeast(16)
        val targetH = (h * ratio).toInt().coerceAtLeast(16)
        return runCatching {
            Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }.getOrDefault(src)
    }

    private fun diskFileForHost(context: Context, host: String): File {
        val dir = File(context.applicationContext.cacheDir, "favicons")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(host.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (i in 0 until 16) {
                append(((digest[i].toInt() and 0xFF) + 0x100).toString(16).substring(1))
            }
        }
        return File(dir, "$hex.png")
    }

    private fun readDiskCache(context: Context, host: String): Bitmap? {
        return try {
            val file = diskFileForHost(context, host)
            if (!file.isFile || file.length() <= 0L) return null
            val path = file.absolutePath
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = computePowerOfTwoSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
            }
            BitmapFactory.decodeFile(path, decodeOpts)?.takeIf { it.width > 0 && it.height > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeDiskCache(context: Context, host: String, bitmap: Bitmap) {
        try {
            val target = diskFileForHost(context, host)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            tmp.renameTo(target)
        } catch (_: Throwable) {
        }
    }
}

/**
 * Crisp site favicon composable replacing legacy 2-letter monograms across Search, Spaces,
 * Bottom Bar, Tab Switcher, and Page Menu.
 *
 * Resolution order:
 * 1. [liveBitmap] from active `WebChromeClient.onReceivedIcon` (supports ephemeral private tabs in RAM).
 * 2. Cached high-DPI site favicon (`apple-touch-icon.png` / `favicon.ico`) from [FaviconCache].
 * 3. Instant `0ms` bespoke vector brand mark for built-in sites (`IMDb`, `GitHub`, `HN`, `Wikipedia`,
 *    `Android`, `Reddit`, `YouTube`, `Ars`, `Docs.rs`, `Google`, `DuckDuckGo`) or a minimal optical
 *    wireframe globe while fetching unknown domains.
 */
@Composable
fun SiteFavicon(
    urlOrDomain: String,
    modifier: Modifier = Modifier,
    liveBitmap: Bitmap? = null,
    allowNetworkFetch: Boolean = true,
    size: Dp = 24.dp,
    cornerRadius: Dp = 6.dp,
) {
    val context = LocalContext.current

    val host = remember(urlOrDomain) { FaviconCache.normalizeHost(urlOrDomain) }
    val builtInMark = remember(host) { FaviconCache.resolveBuiltInMark(host) }

    val liveImageBitmap = remember(liveBitmap) {
        liveBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
    }

    val hostFlow = remember(host) { FaviconCache.observeHost(host) }
    val cachedRawBitmap by hostFlow.collectAsState()
    val cachedImageBitmap = remember(cachedRawBitmap) {
        cachedRawBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
    }

    val shouldAllowNetworkFetch = allowNetworkFetch && builtInMark == null
    LaunchedEffect(host, shouldAllowNetworkFetch) {
        if (host.isNotEmpty() && cachedRawBitmap == null) {
            FaviconCache.ensureLoaded(
                appContext = context.applicationContext,
                urlOrDomain = host,
                allowNetworkFetch = shouldAllowNetworkFetch,
            )
        }
    }

    val effectiveBitmap = liveImageBitmap ?: cachedImageBitmap
    val clipShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    Box(
        modifier = modifier
            .size(size)
            .clip(clipShape),
        contentAlignment = Alignment.Center,
    ) {
        if (effectiveBitmap != null) {
            Image(
                bitmap = effectiveBitmap,
                contentDescription = host.ifEmpty { "Site icon" },
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (builtInMark != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawBuiltInFaviconMark(builtInMark)
            }
        } else {
            val colors = LocalFocalColors.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surfacePillSubtle)
                    .padding(size * 0.16f),
                contentAlignment = Alignment.Center,
            ) {
                val strokeColor = colors.inkSecondary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawOpticalGlobeGlyph(strokeColor)
                }
            }
        }
    }
}

private fun DrawScope.drawOpticalGlobeGlyph(color: Color) {
    val r = size.minDimension * 0.44f
    val c = center
    val sw = (size.minDimension * 0.095f).coerceAtLeast(1.2f)
    drawCircle(color = color, radius = r, center = c, style = Stroke(width = sw))
    drawOval(
        color = color,
        topLeft = Offset(c.x - r * 0.46f, c.y - r),
        size = Size(r * 0.92f, r * 2f),
        style = Stroke(width = sw * 0.9f),
    )
    drawLine(
        color = color,
        start = Offset(c.x - r, c.y),
        end = Offset(c.x + r, c.y),
        strokeWidth = sw * 0.9f,
    )
}

private fun DrawScope.drawBuiltInFaviconMark(mark: BuiltInFaviconMark) {
    val w = size.width
    val h = size.height
    val c = center
    when (mark) {
        BuiltInFaviconMark.IMDB -> {
            drawRect(color = Color(0xFFF5C518))
            val ink = Color(0xFF121212)
            val barTop = h * 0.27f
            val barH = h * 0.46f
            // Bold 'I' pillar + 'M' arch silhouette
            drawRoundRect(
                color = ink,
                topLeft = Offset(w * 0.17f, barTop),
                size = Size(w * 0.13f, barH),
                cornerRadius = CornerRadius(w * 0.03f),
            )
            val mPath = Path().apply {
                moveTo(w * 0.38f, barTop + barH)
                lineTo(w * 0.38f, barTop)
                lineTo(w * 0.51f, barTop)
                lineTo(w * 0.60f, barTop + barH * 0.48f)
                lineTo(w * 0.69f, barTop)
                lineTo(w * 0.82f, barTop)
                lineTo(w * 0.82f, barTop + barH)
                lineTo(w * 0.71f, barTop + barH)
                lineTo(w * 0.71f, barTop + barH * 0.40f)
                lineTo(w * 0.60f, barTop + barH * 0.86f)
                lineTo(w * 0.49f, barTop + barH * 0.40f)
                lineTo(w * 0.49f, barTop + barH)
                close()
            }
            drawPath(mPath, color = ink)
        }

        BuiltInFaviconMark.GITHUB -> {
            drawRect(color = Color(0xFF161B22))
            val fg = Color(0xFFF0F6FC)
            // Octocat silhouette: ears + head + shoulders/tentacle notch
            val ears = Path().apply {
                moveTo(w * 0.25f, h * 0.44f)
                lineTo(w * 0.23f, h * 0.20f)
                lineTo(w * 0.40f, h * 0.30f)
                lineTo(w * 0.60f, h * 0.30f)
                lineTo(w * 0.77f, h * 0.20f)
                lineTo(w * 0.75f, h * 0.44f)
                close()
            }
            drawPath(ears, color = fg)
            drawOval(
                color = fg,
                topLeft = Offset(w * 0.21f, h * 0.28f),
                size = Size(w * 0.58f, h * 0.44f),
            )
            drawRoundRect(
                color = fg,
                topLeft = Offset(w * 0.37f, h * 0.66f),
                size = Size(w * 0.26f, h * 0.22f),
                cornerRadius = CornerRadius(w * 0.08f),
            )
            // Subtle tail curve
            drawArc(
                color = fg,
                startAngle = 100f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(w * 0.16f, h * 0.58f),
                size = Size(w * 0.28f, h * 0.24f),
                style = Stroke(width = w * 0.07f, cap = StrokeCap.Round),
            )
        }

        BuiltInFaviconMark.HACKER_NEWS -> {
            drawRect(color = Color(0xFFFF6600))
            val sw = (w * 0.105f).coerceAtLeast(1.6f)
            val white = Color.White
            drawLine(
                color = white,
                start = Offset(w * 0.29f, h * 0.24f),
                end = Offset(w * 0.50f, h * 0.53f),
                strokeWidth = sw,
                cap = StrokeCap.Square,
            )
            drawLine(
                color = white,
                start = Offset(w * 0.71f, h * 0.24f),
                end = Offset(w * 0.50f, h * 0.53f),
                strokeWidth = sw,
                cap = StrokeCap.Square,
            )
            drawLine(
                color = white,
                start = Offset(w * 0.50f, h * 0.51f),
                end = Offset(w * 0.50f, h * 0.78f),
                strokeWidth = sw,
                cap = StrokeCap.Square,
            )
        }

        BuiltInFaviconMark.WIKIPEDIA -> {
            drawRect(color = Color(0xFFF4F5F7))
            val ink = Color(0xFF141518)
            val sw = (w * 0.085f).coerceAtLeast(1.4f)
            val wPath = Path().apply {
                moveTo(w * 0.16f, h * 0.28f)
                lineTo(w * 0.33f, h * 0.74f)
                lineTo(w * 0.50f, h * 0.36f)
                lineTo(w * 0.67f, h * 0.74f)
                lineTo(w * 0.84f, h * 0.28f)
            }
            drawPath(
                path = wPath,
                color = ink,
                style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Classic serif caps along top of W
            drawLine(ink, Offset(w * 0.11f, h * 0.28f), Offset(w * 0.23f, h * 0.28f), sw * 0.85f, StrokeCap.Round)
            drawLine(ink, Offset(w * 0.77f, h * 0.28f), Offset(w * 0.89f, h * 0.28f), sw * 0.85f, StrokeCap.Round)
        }

        BuiltInFaviconMark.ANDROID_DEV -> {
            drawRect(color = Color(0xFF0F1F17))
            val green = Color(0xFF3DDC84)
            val antSw = (w * 0.065f).coerceAtLeast(1.2f)
            // Antennae
            drawLine(green, Offset(w * 0.34f, h * 0.42f), Offset(w * 0.22f, h * 0.23f), antSw, StrokeCap.Round)
            drawLine(green, Offset(w * 0.66f, h * 0.42f), Offset(w * 0.78f, h * 0.23f), antSw, StrokeCap.Round)
            // Dome head
            drawArc(
                color = green,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(w * 0.18f, h * 0.34f),
                size = Size(w * 0.64f, h * 0.64f),
            )
            // Eyes
            val eyeColor = Color(0xFF0F1F17)
            drawCircle(color = eyeColor, radius = w * 0.048f, center = Offset(w * 0.37f, h * 0.52f))
            drawCircle(color = eyeColor, radius = w * 0.048f, center = Offset(w * 0.63f, h * 0.52f))
        }

        BuiltInFaviconMark.REDDIT -> {
            drawRect(color = Color(0xFFFF4500))
            val white = Color.White
            // Antenna
            val antSw = (w * 0.06f).coerceAtLeast(1.1f)
            drawLine(white, Offset(w * 0.50f, h * 0.36f), Offset(w * 0.60f, h * 0.20f), antSw, StrokeCap.Round)
            drawLine(white, Offset(w * 0.60f, h * 0.20f), Offset(w * 0.72f, h * 0.23f), antSw, StrokeCap.Round)
            drawCircle(white, radius = w * 0.055f, center = Offset(w * 0.74f, h * 0.23f))
            // Ears + Snoo head
            drawCircle(white, radius = w * 0.085f, center = Offset(w * 0.23f, h * 0.52f))
            drawCircle(white, radius = w * 0.085f, center = Offset(w * 0.77f, h * 0.52f))
            drawOval(
                color = white,
                topLeft = Offset(w * 0.22f, h * 0.36f),
                size = Size(w * 0.56f, h * 0.44f),
            )
            // Orange-red eyes
            val eye = Color(0xFFFF4500)
            drawCircle(eye, radius = w * 0.055f, center = Offset(w * 0.39f, h * 0.55f))
            drawCircle(eye, radius = w * 0.055f, center = Offset(w * 0.61f, h * 0.55f))
        }

        BuiltInFaviconMark.YOUTUBE -> {
            drawRect(color = Color(0xFF18181B))
            drawRoundRect(
                color = Color(0xFFFF0033),
                topLeft = Offset(w * 0.12f, h * 0.23f),
                size = Size(w * 0.76f, h * 0.54f),
                cornerRadius = CornerRadius(w * 0.16f),
            )
            val play = Path().apply {
                moveTo(w * 0.42f, h * 0.36f)
                lineTo(w * 0.64f, h * 0.50f)
                lineTo(w * 0.42f, h * 0.64f)
                close()
            }
            drawPath(play, color = Color.White)
        }

        BuiltInFaviconMark.ARS_TECHNICA -> {
            drawRect(color = Color(0xFFFF4E00))
            val slash = Path().apply {
                moveTo(w * 0.56f, h * 0.18f)
                lineTo(w * 0.76f, h * 0.18f)
                lineTo(w * 0.44f, h * 0.82f)
                lineTo(w * 0.24f, h * 0.82f)
                close()
            }
            drawPath(slash, color = Color.White)
            drawCircle(color = Color.White, radius = w * 0.09f, center = Offset(w * 0.68f, h * 0.70f))
        }

        BuiltInFaviconMark.RUST_DOCS -> {
            drawRect(color = Color(0xFF1E1B18))
            val copper = Color(0xFFDEA584)
            val ringSw = (w * 0.12f).coerceAtLeast(1.8f)
            drawCircle(color = copper, radius = w * 0.28f, center = c, style = Stroke(width = ringSw))
            // 8 gear teeth around circumference
            val toothR = w * 0.36f
            val diag = toothR * 0.7071f
            val offsets = arrayOf(
                Offset(0f, -toothR),
                Offset(0f, toothR),
                Offset(-toothR, 0f),
                Offset(toothR, 0f),
                Offset(-diag, -diag),
                Offset(diag, -diag),
                Offset(-diag, diag),
                Offset(diag, diag),
            )
            for (d in offsets) {
                drawCircle(color = copper, radius = w * 0.055f, center = c + d)
            }
            drawCircle(color = copper, radius = w * 0.10f, center = c)
        }

        BuiltInFaviconMark.GOOGLE -> {
            drawRect(color = Color.White)
            val sw = (w * 0.15f).coerceAtLeast(2f)
            val boxTopLeft = Offset(w * 0.22f, h * 0.22f)
            val boxSize = Size(w * 0.56f, h * 0.56f)
            val arcStroke = Stroke(width = sw, cap = StrokeCap.Butt)
            // Red top arc
            drawArc(Color(0xFFEA4335), 215f, 100f, false, boxTopLeft, boxSize, style = arcStroke)
            // Yellow left arc
            drawArc(Color(0xFFFBBC05), 135f, 82f, false, boxTopLeft, boxSize, style = arcStroke)
            // Green bottom arc
            drawArc(Color(0xFF34A853), 45f, 92f, false, boxTopLeft, boxSize, style = arcStroke)
            // Blue lower-right arc + crossbar
            drawArc(Color(0xFF4285F4), -12f, 60f, false, boxTopLeft, boxSize, style = arcStroke)
            drawRect(
                color = Color(0xFF4285F4),
                topLeft = Offset(w * 0.50f, h * 0.43f),
                size = Size(w * 0.32f, sw * 0.92f),
            )
        }

        BuiltInFaviconMark.DUCKDUCKGO -> {
            drawRect(color = Color(0xFFDE5833))
            drawCircle(color = Color.White, radius = w * 0.30f, center = Offset(w * 0.48f, h * 0.47f))
            // Orange bill + green bow tie
            drawRoundRect(
                color = Color(0xFFFFB000),
                topLeft = Offset(w * 0.52f, h * 0.44f),
                size = Size(w * 0.24f, h * 0.11f),
                cornerRadius = CornerRadius(w * 0.05f),
            )
            drawCircle(color = Color(0xFF1E293B), radius = w * 0.045f, center = Offset(w * 0.45f, h * 0.40f))
            drawRoundRect(
                color = Color(0xFF4CBA4A),
                topLeft = Offset(w * 0.36f, h * 0.68f),
                size = Size(w * 0.24f, h * 0.11f),
                cornerRadius = CornerRadius(w * 0.04f),
            )
        }
    }
}
