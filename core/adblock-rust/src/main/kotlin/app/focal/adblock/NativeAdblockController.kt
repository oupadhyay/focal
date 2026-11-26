package app.focal.adblock

import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production [AdblockController] backed by `libfocal_adblock.so` via [RustAdblockBridge].
 *
 * Thread-safe across Android's UI thread, background coroutine dispatchers, and Chromium's
 * `shouldInterceptRequest` network I/O thread pool. Implements [AutoCloseable] to deterministically
 * release the native `Arc<FocalAdblockEngine>` handle when the application/engine scope tears down.
 */
class NativeAdblockController(
    autoLoadNativeLibrary: Boolean = true,
) : AdblockController, AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val engineHandle = AtomicLong(0L)
    private val blockedCounter = AtomicLong(0L)
    private val cacheBootstrapMutex = Mutex()

    private val _totalBlockedCount = MutableStateFlow(0L)
    override val totalBlockedCount: StateFlow<Long> = _totalBlockedCount.asStateFlow()

    /** `true` when backed by the live native Rust engine (`libfocal_adblock.so`). */
    val isNativeEngineActive: Boolean
        get() = engineHandle.get() > 0L && !closed.get()

    init {
        if (autoLoadNativeLibrary && RustAdblockBridge.ensureLibraryLoaded()) {
            val createdHandle = RustAdblockBridge.nativeCreateEngine()
            if (createdHandle > 0L) {
                engineHandle.set(createdHandle)
            }
        }
    }

    /**
     * Bootstraps or refreshes the native engine on [Dispatchers.IO] using Brave's compact `.dat`
     * binary snapshot at [cacheFile]:
     * - When [cacheFile] exists and is valid, deserializes the `.dat` snapshot in `< 5ms` (without
     *   re-parsing text rules when initialized lazily).
     * - When [cacheFile] is missing or corrupt, compiles starter rules off the UI thread and
     *   atomically persists the serialized `.dat` snapshot to [cacheFile] for subsequent cold starts.
     */
    suspend fun initializeOrLoadCache(cacheFile: File) {
        if (closed.get()) return
        withContext(Dispatchers.IO) {
            cacheBootstrapMutex.withLock {
                if (closed.get() || !RustAdblockBridge.ensureLibraryLoaded()) return@withLock

                val existingHandle = engineHandle.get()
                val cachedBytes = if (cacheFile.isFile && cacheFile.length() > 0L) {
                    try {
                        cacheFile.readBytes()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                if (existingHandle <= 0L) {
                    if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                        val restoredHandle = RustAdblockBridge.nativeCreateEngineFromDat(cachedBytes)
                        if (restoredHandle > 0L) {
                            if (!closed.get() && engineHandle.compareAndSet(0L, restoredHandle)) {
                                return@withLock
                            } else {
                                RustAdblockBridge.nativeDestroyEngine(restoredHandle)
                                return@withLock
                            }
                        }
                        try {
                            cacheFile.delete()
                        } catch (_: Exception) {
                        }
                    }

                    val createdHandle = RustAdblockBridge.nativeCreateEngine()
                    if (createdHandle <= 0L) return@withLock
                    if (closed.get() || !engineHandle.compareAndSet(0L, createdHandle)) {
                        RustAdblockBridge.nativeDestroyEngine(createdHandle)
                        return@withLock
                    }

                    val serialized = RustAdblockBridge.nativeSerializeDat(createdHandle)
                    if (serialized != null && serialized.isNotEmpty()) {
                        writeBytesAtomically(cacheFile, serialized)
                    }
                } else {
                    if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                        if (RustAdblockBridge.nativeLoadSerializedDat(existingHandle, cachedBytes)) {
                            return@withLock
                        }
                        try {
                            cacheFile.delete()
                        } catch (_: Exception) {
                        }
                    }

                    val serialized = RustAdblockBridge.nativeSerializeDat(existingHandle)
                    if (serialized != null && serialized.isNotEmpty()) {
                        writeBytesAtomically(cacheFile, serialized)
                    }
                }
            }
        }
    }

    override fun checkNetworkRequest(
        requestUrl: String,
        sourceUrl: String,
        resourceType: String,
    ): BlockDecision {
        if (requestUrl.isBlank()) return BlockDecision.ALLOW
        val handle = engineHandle.get()
        if (handle <= 0L || closed.get()) return BlockDecision.ALLOW

        val encodedDecision = RustAdblockBridge.nativeCheckNetworkRequest(
            enginePtr = handle,
            requestUrl = requestUrl,
            sourceUrl = sourceUrl,
            resourceType = resourceType.ifBlank { "other" },
        ) ?: return BlockDecision.ALLOW

        recordBlockedSubresource()

        val isImportant = encodedDecision.getOrNull(0) == "1"
        val redirectBody = encodedDecision.getOrNull(1)

        return when {
            redirectBody != null -> BlockDecision(
                matched = true,
                important = isImportant,
                redirectBody = redirectBody,
            )
            isImportant -> BlockDecision.BLOCK_IMPORTANT
            else -> BlockDecision.BLOCK
        }
    }

    /**
     * Ultra-low-overhead boolean check (`nativeShouldBlock`) for callers that only need a yes/no
     * block signal without `$redirect` surrogate inspection.
     */
    fun shouldBlock(
        requestUrl: String,
        sourceUrl: String,
        resourceType: String = "other",
    ): Boolean {
        if (requestUrl.isBlank()) return false
        val handle = engineHandle.get()
        if (handle <= 0L || closed.get()) return false

        val blocked = RustAdblockBridge.nativeShouldBlock(
            enginePtr = handle,
            requestUrl = requestUrl,
            sourceUrl = sourceUrl,
            resourceType = resourceType.ifBlank { "other" },
        )
        if (blocked) {
            recordBlockedSubresource()
        }
        return blocked
    }

    /**
     * Evaluates a subresource request and directly returns a zero-allocation [WebResourceResponse]
     * when blocked (or `null` when allowed).
     */
    fun interceptWebRequest(
        requestUrl: String,
        sourceUrl: String,
        resourceType: String,
    ): WebResourceResponse? {
        val decision = checkNetworkRequest(requestUrl, sourceUrl, resourceType)
        return AdblockWebResponses.toWebResourceResponse(decision)
    }

    override fun cosmeticCssForUrl(url: String): String {
        if (url.isBlank()) return ""
        val handle = engineHandle.get()
        if (handle <= 0L || closed.get()) return ""
        return RustAdblockBridge.nativeGetCosmeticCss(handle, url).orEmpty()
    }

    override fun cosmeticCssForClassesAndIds(
        url: String,
        classes: List<String>,
        ids: List<String>,
    ): String {
        if (url.isBlank() || (classes.isEmpty() && ids.isEmpty())) return ""
        val handle = engineHandle.get()
        if (handle <= 0L || closed.get()) return ""

        val classesTabSeparated = encodeTabSeparatedTokens(classes)
        val idsTabSeparated = encodeTabSeparatedTokens(ids)
        if (classesTabSeparated.isEmpty() && idsTabSeparated.isEmpty()) return ""

        return RustAdblockBridge.nativeHiddenClassIdSelectors(
            enginePtr = handle,
            url = url,
            classesTabSeparated = classesTabSeparated,
            idsTabSeparated = idsTabSeparated,
        ).orEmpty()
    }

    override fun cleanTrackingUrl(rawUrl: String): CleanedUrlResult {
        if (rawUrl.isBlank()) {
            return CleanedUrlResult(cleanUrl = rawUrl, strippedParams = emptyList())
        }
        // Zero-allocation fast-path: clean URLs without query or fragment cannot carry tracking
        // query params; skip ensureLibraryLoaded() / System.loadLibrary and JNI completely.
        if ('?' !in rawUrl && '#' !in rawUrl) {
            return CleanedUrlResult(cleanUrl = rawUrl, strippedParams = emptyList())
        }

        if (RustAdblockBridge.ensureLibraryLoaded()) {
            val resultArray = RustAdblockBridge.nativeCleanUrl(rawUrl)
            if (resultArray != null && resultArray.isNotEmpty()) {
                val cleanUrl = resultArray[0]
                if (cleanUrl != null) {
                    val stripped = if (resultArray.size > 1) {
                        buildList(resultArray.size - 1) {
                            for (i in 1 until resultArray.size) {
                                resultArray[i]?.let(::add)
                            }
                        }
                    } else {
                        emptyList()
                    }
                    return CleanedUrlResult(cleanUrl = cleanUrl, strippedParams = stripped)
                }
            }
        }

        return cleanTrackingUrlJvmFallback(rawUrl)
    }

    /**
     * Hot-swaps the native engine's filter set from Brave's serialized `.dat` bytes (`< 10ms`).
     */
    fun loadSerializedDat(datBytes: ByteArray): Boolean {
        if (datBytes.isEmpty()) return false
        val handle = engineHandle.get()
        if (handle <= 0L || closed.get()) return false
        return RustAdblockBridge.nativeLoadSerializedDat(handle, datBytes)
    }

    /**
     * Serializes the currently loaded filter set into `.dat` bytes for fast cold-start disk caching.
     */
    fun serializeDat(): ByteArray? {
        val handle = engineHandle.get()
        if (handle <= 0L || closed.get()) return null
        return RustAdblockBridge.nativeSerializeDat(handle)
    }

    /**
     * Reloads the native engine with custom Adblock Plus / uBlock filter rules.
     */
    fun loadCustomRules(rulesText: String, includeStarterRules: Boolean = true): Boolean {
        val handle = engineHandle.get()
        if (handle <= 0L || closed.get()) return false
        return RustAdblockBridge.nativeLoadCustomRules(handle, rulesText, includeStarterRules)
    }

    private fun recordBlockedSubresource() {
        val next = blockedCounter.incrementAndGet()
        _totalBlockedCount.update { current -> if (next > current) next else current }
    }

    /**
     * Idempotently releases the underlying native Rust engine handle.
     * Safe to invoke multiple times or concurrently with background queries.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val handle = engineHandle.getAndSet(0L)
            if (handle > 0L) {
                RustAdblockBridge.nativeDestroyEngine(handle)
            }
        }
    }

    companion object {
        internal fun encodeTabSeparatedTokens(tokens: List<String>): String {
            if (tokens.isEmpty()) return ""
            return buildString {
                for (raw in tokens) {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) continue
                    if (isNotEmpty()) append('\t')
                    append(trimmed)
                }
            }
        }

        private fun writeBytesAtomically(targetFile: File, bytes: ByteArray) {
            try {
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val tempFile = File(parent ?: File("."), "${targetFile.name}.tmp")
                tempFile.writeBytes(bytes)
                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (_: Exception) {
                // Best-effort disk cache persistence; in-memory engine remains active.
            }
        }
        private val FALLBACK_PREFIXES = arrayOf("utm_", "mtm_", "hsa_")
        private val FALLBACK_EXACT_TRACKERS = setOf(
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "gclsrc", "gad_source",
            "gad_campaignid", "srsltid", "msclkid", "mc_cid", "mc_eid", "igshid", "igsh",
            "si", "twclid", "yclid", "ymclid", "ttclid", "sccid", "epik", "li_fat_id",
            "rdt_cid", "qclid", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp",
            "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv", "wickedid",
            "spm", "scm", "_ga", "_gl", "ef_id", "s_kwcid", "tblci", "dicbo", "irclickid",
        )

        /**
         * Pure-JVM fallback canonicalizer used only when `libfocal_adblock.so` is absent
         * (e.g., Compose `@Preview` or pure-JVM unit tests without `-Djava.library.path`).
         */
        internal fun cleanTrackingUrlJvmFallback(rawUrl: String): CleanedUrlResult {
            val trimmed = rawUrl.trim()
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return CleanedUrlResult(rawUrl, emptyList())
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return CleanedUrlResult(trimmed, emptyList())
            }
            val rawQuery = uri.rawQuery
            if (rawQuery.isNullOrEmpty()) {
                return CleanedUrlResult(trimmed, emptyList())
            }

            val kept = ArrayList<String>()
            val stripped = ArrayList<String>()

            for (segment in rawQuery.split('&')) {
                if (segment.isEmpty()) continue
                val rawKey = segment.substringBefore('=')
                val decodedKey = try {
                    URLDecoder.decode(rawKey, Charsets.UTF_8.name())
                } catch (_: Exception) {
                    rawKey
                }
                val lowerKey = decodedKey.lowercase()
                val isTracker = FALLBACK_PREFIXES.any { lowerKey.startsWith(it) && lowerKey.length > it.length } ||
                    lowerKey in FALLBACK_EXACT_TRACKERS

                if (isTracker) {
                    stripped.add(lowerKey)
                } else {
                    kept.add(segment)
                }
            }

            if (stripped.isEmpty()) {
                return CleanedUrlResult(trimmed, emptyList())
            }

            val fragmentSuffix = uri.rawFragment?.let { "#$it" }.orEmpty()
            val baseBeforeQuery = trimmed.substringBefore('#').substringBefore('?')
            val rebuilt = if (kept.isEmpty()) {
                "$baseBeforeQuery$fragmentSuffix"
            } else {
                "$baseBeforeQuery?${kept.joinToString("&")}$fragmentSuffix"
            }
            return CleanedUrlResult(rebuilt, stripped)
        }
    }
}
