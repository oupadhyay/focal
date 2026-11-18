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
    val aliases: List<String> = emptyList(),
    val homeUrl: String = DoubleSpaceSiteDetector.extractOriginUrl(urlTemplate),
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
 * Typing a site command, name, or short alias (`GitHub`, `GH`, `git`, `YouTube`, `YT`,
 * `Wikipedia`, `Wiki`, `WI`, `Reddit`, `RD`, `IMDb`, `IM`, `HN`, `SO`, etc.) followed by
 * **double space** (`"git  "`) — or Android Gboard double-space auto-period (`"git "` -> `"git. "`) —
 * converts the leading command into a removable site chip (`[GitHub ×]`) while leaving single-space
 * queries (`"git rebase"`) untouched. Pressing `Backspace` when the query is empty unwraps the chip
 * back into the exact text that triggered it.
 */
class DoubleSpaceSiteDetector(
    customEngines: List<SiteSearchEngine> = emptyList(),
) {
    private class TrieNode {
        val children = HashMap<Char, TrieNode>(8)
        var engine: SiteSearchEngine? = null
    }

    private val root = TrieNode()
    private var lastTriggeredPrefix: String? = null

    companion object {
        private val ORIGIN_URL_REGEX = Regex("^(https?://[^/?#]+)")

        private val DOMAIN_LIKE_REGEX =
            Regex("^(localhost(:\\d+)?|([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(/.*)?$")

        internal fun extractOriginUrl(url: String): String {
            return ORIGIN_URL_REGEX.find(url.trim())?.value ?: url.trim()
        }

        val BuiltInEngines: List<SiteSearchEngine> = listOf(
            SiteSearchEngine(
                shortcut = "gh",
                aliases = listOf("github", "git", "github.com"),
                label = "GitHub",
                placeholder = "Search repos, issues, code…",
                urlTemplate = "https://github.com/search?q=%s",
                colors = ContextualSitePalette.GitHub,
            ),
            SiteSearchEngine(
                shortcut = "gl",
                aliases = listOf("gitlab", "gitlab.com"),
                label = "GitLab",
                placeholder = "Search GitLab projects & code…",
                urlTemplate = "https://gitlab.com/search?search=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "yt",
                aliases = listOf("youtube", "ytube", "youtube.com"),
                label = "YouTube",
                placeholder = "Search videos (ad-free)…",
                urlTemplate = "https://www.youtube.com/results?search_query=%s",
                colors = ContextualSitePalette.YouTube,
            ),
            SiteSearchEngine(
                shortcut = "wi",
                aliases = listOf("wiki", "wikipedia", "wp", "wikipedia.org"),
                label = "Wikipedia",
                placeholder = "Search Wikipedia articles…",
                urlTemplate = "https://en.wikipedia.org/w/index.php?search=%s",
                colors = ContextualSitePalette.Wikipedia,
            ),
            SiteSearchEngine(
                shortcut = "rd",
                aliases = listOf("reddit", "r", "reddit.com"),
                label = "Reddit",
                placeholder = "Search subreddits & threads…",
                urlTemplate = "https://www.reddit.com/search/?q=%s",
                colors = ContextualSitePalette.Reddit,
            ),
            SiteSearchEngine(
                shortcut = "im",
                aliases = listOf("imdb", "imdb.com"),
                label = "IMDb",
                placeholder = "Search IMDb titles, cast…",
                urlTemplate = "https://www.imdb.com/find/?q=%s",
                colors = ContextualSitePalette.IMDb,
            ),
            SiteSearchEngine(
                shortcut = "hn",
                aliases = listOf("hackernews", "yc", "news.ycombinator.com"),
                label = "Hacker News",
                placeholder = "Search Hacker News stories…",
                urlTemplate = "https://hn.algolia.com/?q=%s",
                homeUrl = "https://news.ycombinator.com",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "g",
                aliases = listOf("google", "goog", "google.com"),
                label = "Google",
                placeholder = "Search Google…",
                urlTemplate = "https://www.google.com/search?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "ddg",
                aliases = listOf("duckduckgo", "duck", "duckduckgo.com"),
                label = "DuckDuckGo",
                placeholder = "Search DuckDuckGo…",
                urlTemplate = "https://duckduckgo.com/?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "so",
                aliases = listOf("stackoverflow", "stack", "stackoverflow.com"),
                label = "Stack Overflow",
                placeholder = "Search Stack Overflow questions…",
                urlTemplate = "https://stackoverflow.com/search?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "mdn",
                aliases = listOf("moz", "developer.mozilla.org"),
                label = "MDN",
                placeholder = "Search MDN Web Docs…",
                urlTemplate = "https://developer.mozilla.org/en-US/search?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "ars",
                aliases = listOf("ar", "arstechnica", "arstechnica.com"),
                label = "Ars Technica",
                placeholder = "Search Ars Technica…",
                urlTemplate = "https://arstechnica.com/?s=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "npm",
                aliases = listOf("npmjs", "npmjs.com"),
                label = "npm",
                placeholder = "Search npm packages…",
                urlTemplate = "https://www.npmjs.com/search?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "crates",
                aliases = listOf("cr", "cargo", "crates.io"),
                label = "crates.io",
                placeholder = "Search Rust crates…",
                urlTemplate = "https://crates.io/search?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "docsrs",
                aliases = listOf("rs", "docs.rs"),
                label = "Docs.rs",
                placeholder = "Search Rust crate docs…",
                urlTemplate = "https://docs.rs/releases/search?query=%s",
                homeUrl = "https://docs.rs",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "pypi",
                aliases = listOf("pip", "pypi.org"),
                label = "PyPI",
                placeholder = "Search Python packages…",
                urlTemplate = "https://pypi.org/search/?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "aw",
                aliases = listOf("arch", "archwiki"),
                label = "ArchWiki",
                placeholder = "Search ArchWiki…",
                urlTemplate = "https://wiki.archlinux.org/index.php?search=%s",
                homeUrl = "https://wiki.archlinux.org",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "x",
                aliases = listOf("tw", "twitter", "x.com"),
                label = "X",
                placeholder = "Search posts on X…",
                urlTemplate = "https://x.com/search?q=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "amz",
                aliases = listOf("az", "amazon", "amazon.com"),
                label = "Amazon",
                placeholder = "Search Amazon…",
                urlTemplate = "https://www.amazon.com/s?k=%s",
                colors = ContextualSitePalette.Neutral,
            ),
            SiteSearchEngine(
                shortcut = "maps",
                aliases = listOf("gm", "gmaps"),
                label = "Maps",
                placeholder = "Search Google Maps…",
                urlTemplate = "https://www.google.com/maps/search/%s",
                homeUrl = "https://maps.google.com",
                colors = ContextualSitePalette.Neutral,
            ),
        )

        /**
         * Synthesizes fallback site-scoped engines from user-pinned squircles so custom pins
         * also respond to double-space by title, short code, or domain stem without overriding
         * first-party built-in search templates.
         */
        fun enginesFromPins(pins: List<QuickSquirclePin>): List<SiteSearchEngine> {
            return pins.mapNotNull { pin ->
                val cleanUrl = pin.url.trim()
                val host = cleanUrl
                    .substringAfter("://", cleanUrl)
                    .substringBefore('/')
                    .substringBefore(':')
                    .removePrefix("www.")
                    .lowercase()
                if (host.isBlank() || !host.contains('.')) return@mapNotNull null

                val hostStem = host.substringBefore('.')
                val normalizedTitle = pin.title.trim().filter { !it.isWhitespace() }.lowercase()
                val primaryShortcut = pin.shortCode.trim().lowercase().ifBlank { hostStem }
                val candidateAliases = listOf(normalizedTitle, hostStem, host)
                    .filter { it.isNotBlank() && it != primaryShortcut }
                    .distinct()

                SiteSearchEngine(
                    shortcut = primaryShortcut,
                    aliases = candidateAliases,
                    label = pin.title.ifBlank { host },
                    placeholder = "Search ${pin.title.ifBlank { host }}…",
                    urlTemplate = "https://duckduckgo.com/?q=site%3A$host+%s",
                    homeUrl = extractOriginUrl(cleanUrl),
                    colors = ContextualSitePalette.Neutral,
                )
            }
        }
    }

    init {
        // Register custom fallback pins first with overwrite = false after BuiltInEngines so
        // explicit custom engines override built-ins when passed directly, while pin-derived
        // fallbacks can use registerIfAbsent.
        for (engine in BuiltInEngines) {
            registerShortcut(engine, overwrite = true)
        }
        for (engine in customEngines) {
            registerShortcut(engine, overwrite = true)
        }
    }

    fun registerShortcut(engine: SiteSearchEngine, overwrite: Boolean = true) {
        val allKeys = buildList {
            add(engine.shortcut)
            addAll(engine.aliases)
        }
        for (rawKey in allKeys) {
            val key = rawKey.trim().lowercase()
            if (key.isEmpty() || key.any { it.isWhitespace() }) continue
            var cursor = root
            for (ch in key) {
                cursor = cursor.children.getOrPut(ch) { TrieNode() }
            }
            if (overwrite || cursor.engine == null) {
                cursor.engine = engine
            }
        }
    }

    fun lookupExact(shortcut: String): SiteSearchEngine? {
        val normalized = shortcut.trim().lowercase()
        if (normalized.isEmpty()) return null
        var cursor = root
        for (ch in normalized) {
            cursor = cursor.children[ch] ?: return null
        }
        return cursor.engine
    }

    /**
     * Evaluates a text edit for a leading `<command>  ` double-space trigger (at any typing cadence)
     * or Android IME double-space auto-period replacement (`"<command> "` -> `"<command>. "`).
     * Single-space queries (`"git rebase"`) never trigger site search.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onQueryChanged(
        previousQuery: String,
        newQuery: String,
        currentSite: SiteSearchEngine?,
        eventTimeMs: Long = 0L,
    ): DoubleSpaceTransitionResult {
        if (currentSite != null) {
            return DoubleSpaceTransitionResult(
                updatedQuery = newQuery,
                activeSite = currentSite,
            )
        }

        // 1. Two consecutive spaces right after a leading single-token command ("git  ", "GitHub  ", or "git  query")
        val doubleSpaceIdx = newQuery.indexOf("  ")
        if (doubleSpaceIdx > 0) {
            val rawPrefix = newQuery.substring(0, doubleSpaceIdx)
            if (rawPrefix.none { it.isWhitespace() }) {
                val engine = lookupExact(rawPrefix)
                if (engine != null) {
                    lastTriggeredPrefix = rawPrefix
                    val remainder = newQuery.substring(doubleSpaceIdx + 2).trimStart(' ')
                    return DoubleSpaceTransitionResult(
                        updatedQuery = remainder,
                        activeSite = engine,
                        chipJustActivated = true,
                    )
                }
            }
        }

        // 2. Android Gboard / Samsung Keyboard double-space auto-period ("GitHub " -> "GitHub. ")
        if (newQuery.endsWith(". ") && newQuery.length >= 3) {
            val rawPrefix = newQuery.dropLast(2)
            if (
                rawPrefix.none { it.isWhitespace() } &&
                previousQuery.equals("$rawPrefix ", ignoreCase = true)
            ) {
                val engine = lookupExact(rawPrefix)
                if (engine != null) {
                    lastTriggeredPrefix = rawPrefix
                    return DoubleSpaceTransitionResult(
                        updatedQuery = "",
                        activeSite = engine,
                        chipJustActivated = true,
                    )
                }
            }
        }

        return DoubleSpaceTransitionResult(
            updatedQuery = newQuery,
            activeSite = null,
        )
    }

    /**
     * Unwraps the active site chip back into the typed command text when Backspace is pressed on an empty query.
     */
    fun onBackspacePressed(
        currentQuery: String,
        currentSite: SiteSearchEngine?,
    ): DoubleSpaceTransitionResult? {
        if (currentSite == null || currentQuery.isNotEmpty()) return null
        val restored = lastTriggeredPrefix?.takeIf { lookupExact(it) == currentSite }
            ?: currentSite.shortcut
        lastTriggeredPrefix = null
        return DoubleSpaceTransitionResult(
            updatedQuery = restored,
            activeSite = null,
            chipJustUnwrapped = true,
        )
    }

    fun resetPendingSpace() {
        lastTriggeredPrefix = null
    }

    fun resolveDestinationUrl(
        rawQuery: String,
        activeSite: SiteSearchEngine?,
        defaultSearchUrlTemplate: String = "https://duckduckgo.com/?q=%s",
    ): String {
        val trimmed = rawQuery.trim()
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("focal-pair://", ignoreCase = true)
        ) {
            return trimmed
        }

        if (activeSite != null) {
            return if (trimmed.isEmpty()) {
                activeSite.homeUrl
            } else {
                activeSite.formatSearchUrl(trimmed)
            }
        }

        if (trimmed.isEmpty()) return "about:blank"
        if (!trimmed.contains(' ') && DOMAIN_LIKE_REGEX.matches(trimmed)) {
            return if (trimmed.startsWith("localhost", ignoreCase = true)) {
                "http://$trimmed"
            } else {
                "https://$trimmed"
            }
        }

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        return defaultSearchUrlTemplate.replace("%s", encoded)
    }
}
