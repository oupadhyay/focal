package app.focal.adblock

/**
 * Low-level JNI bindings to `libfocal_adblock.so` (`rust/src/jni_bridge.rs`).
 *
 * Every native function on the Rust side is guarded by `std::panic::catch_unwind` and backed
 * by a generation-safe `Arc<FocalAdblockEngine>` handle registry so invalid or concurrently
 * closed handles cannot cause Undefined Behavior across FFI. Every object-returning JNI
 * declaration uses a nullable Kotlin return type so `NULL` fallbacks on panic/OOM never trigger
 * `Intrinsics.checkNotNullExpressionValue` NPEs.
 */
object RustAdblockBridge {
    private const val LIBRARY_NAME = "focal_adblock"

    private val isLibraryLoaded: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Loads `libfocal_adblock.so` at most once using synchronized lazy initialization so
     * concurrent worker threads wait for `System.loadLibrary` completion rather than observing
     * a premature `false` while linking is still in progress.
     *
     * @return `true` when the native library is linked and ready; `false` in pure-JVM preview
     *         or unit test environments where `.so` binaries are not on `java.library.path`.
     */
    fun ensureLibraryLoaded(): Boolean = isLibraryLoaded

    /** Creates a native `FocalAdblockEngine` preloaded with starter rules; returns handle `> 0L` or `0L`. */
    @JvmStatic
    external fun nativeCreateEngine(): Long

    /**
     * Creates a native `FocalAdblockEngine` directly from Brave's compact `.dat` binary cache
     * (`< 5ms`) without compiling text starter rules first; returns handle `> 0L` or `0L`.
     */
    @JvmStatic
    external fun nativeCreateEngineFromDat(datBytes: ByteArray): Long

    /** Releases a native engine handle (`0L` or already-destroyed handles are safe no-ops). */
    @JvmStatic
    external fun nativeDestroyEngine(enginePtr: Long)

    /** Hot-swaps the active engine from Brave's compact binary `.dat` format. */
    @JvmStatic
    external fun nativeLoadSerializedDat(enginePtr: Long, datBytes: ByteArray): Boolean

    /** Serializes the active engine's filter set into Brave's compact `.dat` format. */
    @JvmStatic
    external fun nativeSerializeDat(enginePtr: Long): ByteArray?

    /** Rebuilds the active engine from newline-delimited Adblock Plus / uBlock filter rules. */
    @JvmStatic
    external fun nativeLoadCustomRules(
        enginePtr: Long,
        rulesText: String,
        includeStarter: Boolean,
    ): Boolean

    /** Fast-path boolean network check (`true` = block) for `WebViewClient.shouldInterceptRequest`. */
    @JvmStatic
    external fun nativeShouldBlock(
        enginePtr: Long,
        requestUrl: String,
        sourceUrl: String,
        resourceType: String,
    ): Boolean

    /**
     * Rich network check returning `null` when allowed (zero JNI heap allocation on the common
     * allow path), or a 2-element `Array<String?>` `[importantFlag ("1"|"0"), redirectBodyOrNull]`
     * when blocked.
     */
    @JvmStatic
    external fun nativeCheckNetworkRequest(
        enginePtr: Long,
        requestUrl: String,
        sourceUrl: String,
        resourceType: String,
    ): Array<String?>?

    /**
     * Returns pre-paint CSS hide selectors (`selector { display: none !important; }`) for `url`,
     * or `null` if a JNI panic/allocation fallback occurred.
     */
    @JvmStatic
    external fun nativeGetCosmeticCss(enginePtr: Long, url: String): String?

    /**
     * Evaluates generic cosmetic hide rules against tab-separated DOM `class` and `id` tokens on
     * `url`, returning `selector { display: none !important; }` rules (or `null` on fallback).
     */
    @JvmStatic
    external fun nativeHiddenClassIdSelectors(
        enginePtr: Long,
        url: String,
        classesTabSeparated: String,
        idsTabSeparated: String,
    ): String?

    /**
     * Strips 95+ known tracking query parameters from `rawUrl`.
     * Returns `[cleanUrl, strippedParam1, strippedParam2, ...]`, or `null` on JNI fallback.
     */
    @JvmStatic
    external fun nativeCleanUrl(rawUrl: String): Array<String?>?

    /** Returns cumulative atomic blocked network count for `enginePtr`. */
    @JvmStatic
    external fun nativeGetBlockedCount(enginePtr: Long): Long
}
