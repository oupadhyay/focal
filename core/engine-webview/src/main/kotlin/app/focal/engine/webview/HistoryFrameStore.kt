package app.focal.engine.webview

import android.graphics.Bitmap

/**
 * Thread-safe in-memory per-session ring cache for back/forward edge-swipe page previews.
 *
 * Evicted entries are dropped without calling `Bitmap.recycle()` so Compose UI surfaces holding
 * a preview reference during a swipe animation remain crash-free while Android's native allocation
 * registry reclaims memory automatically once unreferenced.
 */
class HistoryFrameStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val ring = BoundedHistoryRing<Bitmap>(maxEntries)

    fun pushBack(bitmap: Bitmap) = ring.pushBack(bitmap)

    fun peekBack(): Bitmap? = ring.peekBack()

    fun popBack(): Bitmap? = ring.popBack()

    fun pushForward(bitmap: Bitmap) = ring.pushForward(bitmap)

    fun peekForward(): Bitmap? = ring.peekForward()

    fun popForward(): Bitmap? = ring.popForward()

    fun clearForward() = ring.clearForward()

    fun clearAll() = ring.clearAll()

    val backSize: Int
        get() = ring.backSize

    val forwardSize: Int
        get() = ring.forwardSize

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 6
    }
}

/**
 * Generic thread-safe bounded ring stack backing [HistoryFrameStore].
 */
internal class BoundedHistoryRing<T : Any>(
    private val maxEntries: Int = HistoryFrameStore.DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val backStack = ArrayDeque<T>()
    private val forwardStack = ArrayDeque<T>()

    init {
        require(maxEntries >= 1) { "maxEntries must be >= 1, was $maxEntries" }
    }

    fun pushBack(frame: T) = synchronized(lock) {
        if (backStack.size >= maxEntries) {
            backStack.removeFirst()
        }
        backStack.addLast(frame)
    }

    fun peekBack(): T? = synchronized(lock) {
        backStack.lastOrNull()
    }

    fun popBack(): T? = synchronized(lock) {
        if (backStack.isEmpty()) null else backStack.removeLast()
    }

    fun pushForward(frame: T) = synchronized(lock) {
        if (forwardStack.size >= maxEntries) {
            forwardStack.removeFirst()
        }
        forwardStack.addLast(frame)
    }

    fun peekForward(): T? = synchronized(lock) {
        forwardStack.lastOrNull()
    }

    fun popForward(): T? = synchronized(lock) {
        if (forwardStack.isEmpty()) null else forwardStack.removeLast()
    }

    fun clearForward() = synchronized(lock) {
        forwardStack.clear()
    }

    fun clearAll() = synchronized(lock) {
        backStack.clear()
        forwardStack.clear()
    }

    val backSize: Int
        get() = synchronized(lock) { backStack.size }

    val forwardSize: Int
        get() = synchronized(lock) { forwardStack.size }
}
