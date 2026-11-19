package app.focal.design

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Distinct gesture owners that must remain mutually exclusive on the active screen.
 */
enum class GestureLeaseOwner {
    HORIZONTAL_TAB_SCRUB,
    SPACES_SWIPE_UP,
    PINCH_SUMMARIZE,
    SHEET_DRAG,
    PREDICTIVE_BACK,
}

/**
 * Monotonically-versioned ownership token issued by [GestureLeaseController].
 *
 * A token becomes invalid immediately once released or preempted by a higher-priority
 * system gesture (such as Android 14-16 Predictive Back), ensuring stale `finally` or
 * `onDragCancel` callbacks can never release a subsequent gesture's lease.
 */
data class GestureLeaseToken(
    val owner: GestureLeaseOwner,
    val generation: Long,
)

/**
 * Explicit token-based gesture exclusivity coordinator.
 *
 * Prevents cross-triggering between horizontal bottom-bar tab scrubbing, vertical Spaces
 * sheet pulls, two-finger Pinch-to-Summarize, modal sheet drags, and system Predictive Back.
 */
@Stable
class GestureLeaseController {
    private val lock = Any()
    private var currentToken: GestureLeaseToken? = null
    private var nextGeneration: Long = 1L

    val activeOwner: GestureLeaseOwner?
        get() = synchronized(lock) { currentToken?.owner }

    /**
     * Attempts to acquire an exclusive lease for [owner].
     *
     * Returns a valid [GestureLeaseToken] if no gesture currently holds the lease (or if [owner]
     * already holds it), and returns `null` if another gesture currently owns exclusivity.
     */
    fun tryAcquire(owner: GestureLeaseOwner): GestureLeaseToken? = synchronized(lock) {
        val existing = currentToken
        if (existing != null && existing.owner != owner) {
            return@synchronized null
        }
        val token = GestureLeaseToken(owner = owner, generation = nextGeneration++)
        currentToken = token
        token
    }

    /**
     * Immediately supersedes any active in-app gesture lease for a system-level gesture
     * (defaulting to [GestureLeaseOwner.PREDICTIVE_BACK]). Any previously issued token
     * becomes invalid immediately.
     */
    fun preemptForSystem(
        owner: GestureLeaseOwner = GestureLeaseOwner.PREDICTIVE_BACK,
    ): GestureLeaseToken = synchronized(lock) {
        val token = GestureLeaseToken(owner = owner, generation = nextGeneration++)
        currentToken = token
        token
    }

    /**
     * Returns `true` only if [token] matches both the active owner and active generation.
     */
    fun isValid(token: GestureLeaseToken?): Boolean = synchronized(lock) {
        token != null && currentToken == token
    }

    fun isHeldBy(owner: GestureLeaseOwner): Boolean = synchronized(lock) {
        currentToken?.owner == owner
    }

    fun isHeldByOther(requester: GestureLeaseOwner): Boolean = synchronized(lock) {
        val held = currentToken?.owner
        held != null && held != requester
    }

    /**
     * Releases the lease strictly if [token] is still the active token.
     * Stale or preempted tokens are ignored safely.
     */
    fun release(token: GestureLeaseToken?): Boolean = synchronized(lock) {
        if (token != null && currentToken == token) {
            currentToken = null
            true
        } else {
            false
        }
    }

    /**
     * Releases the lease if currently held by [owner].
     */
    fun releaseOwner(owner: GestureLeaseOwner): Boolean = synchronized(lock) {
        if (currentToken?.owner == owner) {
            currentToken = null
            true
        } else {
            false
        }
    }

    fun clearAll() = synchronized(lock) {
        currentToken = null
    }
}

/**
 * Optional screen-level [GestureLeaseController] provided by `MainActivity`.
 * Standalone previews and isolated tests work seamlessly when `null`.
 */
val LocalGestureLeaseController = staticCompositionLocalOf<GestureLeaseController?> { null }
