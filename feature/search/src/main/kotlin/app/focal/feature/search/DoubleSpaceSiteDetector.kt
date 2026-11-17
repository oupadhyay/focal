package app.focal.feature.search

import androidx.compose.runtime.Immutable
import app.focal.design.ContextualSitePalette
import app.focal.design.SiteChipColors
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Immutable
data class SiteSearchEngine(
    val shortcut: String,
    val label: String,
    val placeholder: String,
    val urlTemplate: String,
    val colors: SiteChipColors,
) {
    fun formatSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        return urlTemplate.replace("%s", encoded)
    }
}

@Immutable
data class DoubleSpaceTransitionResult(
    val updatedQuery: String,
    val activeSite: SiteSearchEngine?,
    val chipJustActivated: Boolean = false,
    val chipJustUnwrapped: Boolean = false,
)

/**
 * PrefixTrie-backed Double-Space Site Search Engine (`< 0.05ms` lookup).
 *
 * Typing a 2–3 character shortcut (`im`, `wi`, `rd`, `yt`, `gh`, `hn`) followed by
 * **two spaces within `320ms`** (`"im  "`) converts the prefix into a leading site chip
 * (`[IMDb ×]`). Pressing `Backspace` when the query text is empty dissolves the chip back
 * into its plain-text shortcut (`"im"`).
 */
class DoubleSpaceSiteDetector(
    customEngines: List<SiteSearchEngine> = emptyList(),
    private val maxDoubleSpaceIntervalMs: Long = 320L,
) {
    private class TrieNode {
        val children = HashMap<Char, TrieNode>(8)
        var engine: SiteSearchEngine? = null
    }

    private val root = TrieNode()
    private var pendingPrefix: String? = null
    private var firstSpaceTimestampMs: Long = -1L

    companion object {
        val BuiltInEngines: List<SiteSearchEngine> = listOf(
            SiteSearchEngine(
                shortcut = "im",
                label = "IMDb",
                placeholder = "Search IMDb titles, cast…",
                urlTemplate = "https://www.imdb.com/find/?q=%s",
                colors = ContextualSitePalette.IMDb,
            ),
            SiteSearchEngine(
                shortcut = "wi",
                label = "Wikipedia",
                placeholder = "Search Wikipedia articles…",
                urlTemplate = "https://en.wikipedia.org/w/index.php?search=%s",
                colors = ContextualSitePalette.Wikipedia,
            ),
            SiteSearchEngine(
                shortcut = "rd",
                label = "Reddit",
                placeholder = "Search subreddits & threads…",
                urlTemplate = "https://www.reddit.com/search/?q=%s",
                colors = ContextualSitePalette.Reddit,
            ),
            SiteSearchEngine(
                shortcut = "yt",
                label = "YouTube",
                placeholder = "Search videos (ad-free)…",
                urlTemplate = "https://www.youtube.com/results?search_query=%s",
                colors = ContextualSitePalette.YouTube,
            ),
            SiteSearchEngine(
                shortcut = "gh",
                label = "GitHub",
                placeholder = "Search repos, issues, code…",
                urlTemplate = "https://github.com/search?q=%s",
                colors = ContextualSitePalette.GitHub,
            ),
            SiteSearchEngine(
                shortcut = "hn",
                label = "Hacker News",
                placeholder = "Search Hacker News stories…",
                urlTemplate = "https://hn.algolia.com/?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
        )

        private val DOMAIN_LIKE_REGEX =
            Regex("^(localhost(:\\d+)?|([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(/.*)?$")
    }

    init {
        for (engine in BuiltInEngines + customEngines) {
            registerShortcut(engine)
        }
    }

    fun registerShortcut(engine: SiteSearchEngine) {
        val key = engine.shortcut.trim().lowercase()
        if (key.isEmpty()) return
        var cursor = root
        for (ch in key) {
            cursor = cursor.children.getOrPut(ch) { TrieNode() }
        }
        cursor.engine = engine
    }

    fun lookupExact(shortcut: String): SiteSearchEngine? {
        var cursor = root
        for (ch in shortcut.lowercase()) {
            cursor = cursor.children[ch] ?: return null
        }
        return cursor.engine
    }

    /**
     * Evaluates an incremental text edit for a `<shortcut>␣␣` trigger within `320ms`
     * (including IME auto-period replacement `"im. "` when Android Gboard double-space period is enabled!).
     */
    fun onQueryChanged(
        previousQuery: String,
        newQuery: String,
        currentSite: SiteSearchEngine?,
        eventTimeMs: Long,
    ): DoubleSpaceTransitionResult {
        if (currentSite != null) {
            resetPendingSpace()
            return DoubleSpaceTransitionResult(
                updatedQuery = newQuery,
                activeSite = currentSite,
            )
        }

        // Step 1: First space after a valid shortcut prefix (e.g., "im" -> "im ")
        if (newQuery.length >= 3 && newQuery.endsWith(' ') && !newQuery.dropLast(1).contains(' ')) {
            val candidateKey = newQuery.dropLast(1).lowercase()
            if (lookupExact(candidateKey) != null && previousQuery.equals(candidateKey, ignoreCase = true)) {
                pendingPrefix = candidateKey
                firstSpaceTimestampMs = eventTimeMs
                return DoubleSpaceTransitionResult(
                    updatedQuery = newQuery,
                    activeSite = null,
                )
            }
        }

        // Step 2: Second space within 320ms ("im  " OR Gboard auto-period "im. ")
        val matchedPrefix = when {
            newQuery.endsWith("  ") && !newQuery.dropLast(2).contains(' ') ->
                newQuery.dropLast(2).lowercase()
            newQuery.endsWith(". ") && !newQuery.dropLast(2).contains(' ') ->
                newQuery.dropLast(2).lowercase()
            else -> null
        }

        if (matchedPrefix != null) {
            val engine = lookupExact(matchedPrefix)
            val withinWindow = if (pendingPrefix == matchedPrefix && firstSpaceTimestampMs >= 0L) {
                (eventTimeMs - firstSpaceTimestampMs) in 0L..maxDoubleSpaceIntervalMs
            } else {
                // Also allow atomic paste/commit of "im  " in a single IME batch
                previousQuery.equals(matchedPrefix, ignoreCase = true)
            }

            resetPendingSpace()
            if (engine != null && withinWindow) {
                return DoubleSpaceTransitionResult(
                    updatedQuery = "",
                    activeSite = engine,
                    chipJustActivated = true,
                )
            }
        }

        if (!newQuery.endsWith(' ')) {
            resetPendingSpace()
        }

        return DoubleSpaceTransitionResult(
            updatedQuery = newQuery,
            activeSite = null,
        )
    }

    /**
     * Unwraps the active site chip back into plain shortcut text when Backspace is pressed on an empty query.
     */
    fun onBackspacePressed(
        currentQuery: String,
        currentSite: SiteSearchEngine?,
    ): DoubleSpaceTransitionResult? {
        if (currentSite == null || currentQuery.isNotEmpty()) return null
        resetPendingSpace()
        return DoubleSpaceTransitionResult(
            updatedQuery = currentSite.shortcut,
            activeSite = null,
            chipJustUnwrapped = true,
        )
    }

    fun resetPendingSpace() {
        pendingPrefix = null
        firstSpaceTimestampMs = -1L
    }

    fun resolveDestinationUrl(
        rawQuery: String,
        activeSite: SiteSearchEngine?,
        defaultSearchUrlTemplate: String = "https://duckduckgo.com/?q=%s",
    ): String {
        val trimmed = rawQuery.trim()
        if (activeSite != null) {
            return if (trimmed.isEmpty()) {
                // Navigate to site root if user submits empty chip
                activeSite.urlTemplate.substringBefore("/find").substringBefore("/w/")
                    .substringBefore("/search").substringBefore("/results")
                    .substringBefore("?q=")
            } else {
                activeSite.formatSearchUrl(trimmed)
            }
        }

        if (trimmed.isEmpty()) return "about:blank"
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("focal-pair://")) {
            return trimmed
        }
        if (!trimmed.contains(' ') && DOMAIN_LIKE_REGEX.matches(trimmed)) {
            return if (trimmed.startsWith("localhost")) "http://$trimmed" else "https://$trimmed"
        }

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        return defaultSearchUrlTemplate.replace("%s", encoded)
    }
}
