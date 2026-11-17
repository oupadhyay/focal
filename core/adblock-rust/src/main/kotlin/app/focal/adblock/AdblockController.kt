package app.focal.adblock

import android.util.Base64
import android.webkit.WebResourceResponse
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Decision produced by [AdblockController.checkNetworkRequest] for a subresource or navigation URL.
 *
 * @property matched `true` when the network request matched a blocking rule (and was not allowlisted).
 * @property important `true` when matched by an `$important` rule overriding `@@` exceptions.
 * @property redirectBody Optional surrogate `data:<mime>;base64,...` payload when matched by `$redirect=`.
 */
data class BlockDecision(
    val matched: Boolean,
    val important: Boolean = false,
    val redirectBody: String? = null,
) {
    companion object {
        /** Shared zero-allocation singleton returned on allowed requests. */
        val ALLOW = BlockDecision(matched = false)

        /** Shared zero-allocation singleton returned on standard non-redirect blocks. */
        val BLOCK = BlockDecision(matched = true, important = false, redirectBody = null)

        /** Shared zero-allocation singleton returned on `$important` non-redirect blocks. */
        val BLOCK_IMPORTANT = BlockDecision(matched = true, important = true, redirectBody = null)
    }
}

/**
 * Output of `"Where Does This Go"` URL canonicalization.
 *
 * @property cleanUrl Canonical URL with known cross-site tracking query parameters removed.
 * @property strippedParams Lowercase canonical names of stripped parameters in encounter order.
 */
data class CleanedUrlResult(
    val cleanUrl: String,
    val strippedParams: List<String>,
)

/**
 * Thread-safe ad/tracker network blocker, pre-paint cosmetic CSS provider, and URL cleaner.
 *
 * Designed to be invoked directly from `WebViewClient.shouldInterceptRequest` background
 * Chromium network worker threads without main-thread hops or GC churn.
 */
interface AdblockController {
    val totalBlockedCount: StateFlow<Long>
    fun checkNetworkRequest(requestUrl: String, sourceUrl: String, resourceType: String): BlockDecision
    fun cosmeticCssForUrl(url: String): String

    /**
     * Evaluates generic cosmetic hide rules against observed DOM `class` and `id` tokens on [url],
     * returning newline-delimited `selector { display: none !important; }` rules (or `""` when none
     * match or `$generichide` applies). Defaults to `""` so mocks and test doubles remain compatible.
     */
    fun cosmeticCssForClassesAndIds(
        url: String,
        classes: List<String>,
        ids: List<String>,
    ): String = ""

    fun cleanTrackingUrl(rawUrl: String): CleanedUrlResult
}

/**
 * Zero-allocation static response constants and helpers for `WebViewClient.shouldInterceptRequest`.
 */
object AdblockWebResponses {
    /** Shared 0-byte payload constant for dummy blocked responses. */
    val EMPTY_RESPONSE_BYTES: ByteArray = ByteArray(0)

    /**
     * Permissive CORS headers on blocked 204 responses so blocked third-party XHR/fetch beacons
     * fail silently without noisy CORS console exceptions or retry storms on the page main thread.
     */
    val CORS_BLOCKED_HEADERS: Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
        "Cache-Control" to "no-store",
    )

    /**
     * Stateless, lock-free [InputStream] that immediately signals EOF (`-1`).
     *
     * Unlike `ByteArrayInputStream`, `read()` has no `synchronized` monitor lock, eliminating
     * thread contention across concurrent Chromium network I/O workers, and `close()` is a safe no-op
     * so a single [WebResourceResponse] singleton can be reused across every blocked request.
     */
    object ZeroAllocationEmptyInputStream : InputStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else -1
        override fun available(): Int = 0
        override fun close() = Unit
    }

    /**
     * Singleton `204 Blocked` [WebResourceResponse] initialized lazily (`PUBLICATION`) so JVM unit
     * tests without Android framework mocks can load [AdblockWebResponses] safely while Android
     * runtime reuses one allocation-free instance for the lifetime of the process.
     */
    val BLOCKED_WEB_RESOURCE_RESPONSE: WebResourceResponse by lazy(LazyThreadSafetyMode.PUBLICATION) {
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "Blocked",
            CORS_BLOCKED_HEADERS,
            ZeroAllocationEmptyInputStream,
        )
    }

    /**
     * Converts a [BlockDecision] into a [WebResourceResponse] for `shouldInterceptRequest`,
     * returning `null` when allowed (`matched == false`) so WebView proceeds with normal network I/O.
     */
    fun toWebResourceResponse(decision: BlockDecision): WebResourceResponse? {
        if (!decision.matched) return null

        val redirectUri = decision.redirectBody
        if (redirectUri.isNullOrEmpty()) {
            return BLOCKED_WEB_RESOURCE_RESPONSE
        }

        val parsed = parseBase64DataUri(redirectUri) ?: return BLOCKED_WEB_RESOURCE_RESPONSE
        return WebResourceResponse(
            parsed.first,
            "utf-8",
            200,
            "OK",
            CORS_BLOCKED_HEADERS,
            ByteArrayInputStream(parsed.second),
        )
    }

    internal fun parseBase64DataUri(dataUri: String): Pair<String, ByteArray>? {
        if (!dataUri.startsWith("data:", ignoreCase = true)) return null
        val commaIdx = dataUri.indexOf(',')
        if (commaIdx <= 5) return null

        val header = dataUri.substring(5, commaIdx)
        val payload = dataUri.substring(commaIdx + 1)
        val isBase64 = header.endsWith(";base64", ignoreCase = true)
        val mimeType = header
            .substringBefore(';')
            .ifBlank { "text/plain" }

        val bytes = if (isBase64) {
            try {
                java.util.Base64.getDecoder().decode(payload)
            } catch (_: IllegalArgumentException) {
                try {
                    Base64.decode(payload, Base64.DEFAULT)
                } catch (_: Throwable) {
                    return null
                }
            }
        } else {
            payload.toByteArray(Charsets.UTF_8)
        }
        return mimeType to bytes
    }
}
