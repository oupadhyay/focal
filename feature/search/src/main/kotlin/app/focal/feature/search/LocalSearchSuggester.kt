package app.focal.feature.search

import androidx.compose.runtime.Immutable
import app.focal.database.BookmarkEntity
import app.focal.database.HistoryEntryEntity
import app.focal.database.SpaceEntity
import app.focal.database.TabMetadataEntity
import kotlin.math.ln

@Immutable
sealed interface LocalSearchSuggestion {
    val title: String
    val domain: String
    val url: String

    @Immutable
    data class SwitchToTab(
        val tabId: String,
        override val title: String,
        override val domain: String,
        override val url: String,
        val spaceName: String?,
        val spaceAmbientHex: String?,
    ) : LocalSearchSuggestion

    @Immutable
    data class BookmarkMatch(
        val bookmarkId: String,
        override val title: String,
        override val domain: String,
        override val url: String,
    ) : LocalSearchSuggestion

    @Immutable
    data class HistoryMatch(
        val historyId: String,
        override val title: String,
        override val domain: String,
        override val url: String,
        val visitCount: Int,
    ) : LocalSearchSuggestion
}

@Immutable
data class InlineUrlAutocomplete(
    val displayHost: String,
    val targetUrl: String,
    val completionSuffix: String,
)

@Immutable
data class LocalSuggestionResult(
    val suggestions: List<LocalSearchSuggestion>,
    val inlineAutocomplete: InlineUrlAutocomplete?,
) {
    companion object {
        val Empty = LocalSuggestionResult(
            suggestions = emptyList(),
            inlineAutocomplete = null,
        )
    }
}

/**
 * Pure-JVM, zero-network local search ranking and inline URL autocomplete engine.
 *
 * Ranks exclusively over on-device state:
 * 1. Open Tabs ([LocalSearchSuggestion.SwitchToTab]) — highest priority to prevent duplicate tabs.
 * 2. Bookmarks ([LocalSearchSuggestion.BookmarkMatch]) — excluding folders / blank URLs.
 * 3. Local History ([LocalSearchSuggestion.HistoryMatch]) — weighted by prefix match, visit count,
 *    and recency, deduplicated by normalized URL against higher-priority matches.
 */
object LocalSearchSuggester {

    private const val OPEN_TAB_TIER_BOOST = 10_000.0
    private const val BOOKMARK_TIER_BOOST = 5_000.0
    private const val HISTORY_TIER_BOOST = 0.0

    private const val AUTOCOMPLETE_BOOKMARK_BASE = 400.0
    private const val AUTOCOMPLETE_TAB_BASE = 350.0
    private const val AUTOCOMPLETE_HISTORY_BASE = 200.0
    private const val AUTOCOMPLETE_BUILTIN_BASE = 100.0

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val WORD_SEPARATOR_REGEX = Regex("[\\s/_.:\\-–—|·,()\\[\\]]+")

    val BuiltInPopularHosts: List<String> = listOf(
        "github.com",
        "youtube.com",
        "wikipedia.org",
        "reddit.com",
        "news.ycombinator.com",
        "developer.android.com",
        "arstechnica.com",
        "stratechery.com",
    )

    fun suggest(
        query: String,
        openTabs: List<TabMetadataEntity>,
        bookmarks: List<BookmarkEntity>,
        history: List<HistoryEntryEntity>,
        spacesById: Map<String, SpaceEntity> = emptyMap(),
        maxResults: Int = 4,
    ): LocalSuggestionResult {
        val trimmed = query.trim()
        if (trimmed.length < 2) return LocalSuggestionResult.Empty

        val queryLower = trimmed.lowercase()
        val hostQuery = stripSchemeAndWww(queryLower)
        val tokens = queryLower.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return LocalSuggestionResult.Empty

        val activeTabs = openTabs.filter { tab ->
            !tab.isArchived && isNavigableUrl(tab.url)
        }
        val validBookmarks = bookmarks.filter { bookmark ->
            !bookmark.isFolder && isNavigableUrl(bookmark.url)
        }
        val validHistory = history.filter { entry ->
            isNavigableUrl(entry.url)
        }

        val inlineAutocomplete = if (query.none { it.isWhitespace() }) {
            resolveInlineAutocomplete(
                queryLower = queryLower,
                openTabs = activeTabs,
                bookmarks = validBookmarks,
                history = validHistory,
            )
        } else {
            null
        }

        val cappedMaxResults = maxResults.coerceAtLeast(0)
        if (cappedMaxResults == 0) {
            return LocalSuggestionResult(
                suggestions = emptyList(),
                inlineAutocomplete = inlineAutocomplete,
            )
        }

        val maxTabActiveMs = activeTabs.maxOfOrNull { it.lastActiveEpochMs } ?: 0L
        val maxBookmarkUpdatedMs = validBookmarks.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
        val maxHistoryVisitedMs = validHistory.maxOfOrNull { it.visitedAtEpochMs } ?: 0L

        val scoredTabs = ArrayList<ScoredCandidate>(activeTabs.size)
        for (tab in activeTabs) {
            val effectiveDomain = normalizeDomainOrExtract(tab.domain, tab.url)
            val displayTitle = tab.title.trim().ifBlank { effectiveDomain.ifBlank { tab.url.trim() } }
            val matchScore = scoreTextMatch(
                queryLower = queryLower,
                hostQuery = hostQuery,
                tokens = tokens,
                title = displayTitle,
                domain = effectiveDomain,
                url = tab.url,
            )
            if (matchScore <= 0.0) continue

            val recencyBonus = computeRecencyScore(
                timestampMs = tab.lastActiveEpochMs,
                referenceMaxMs = maxTabActiveMs,
                maxBonus = 50.0,
            )
            val space = spacesById[tab.spaceId]
            scoredTabs.add(
                ScoredCandidate(
                    normalizedUrl = normalizeUrlForDedup(tab.url),
                    score = OPEN_TAB_TIER_BOOST + matchScore + recencyBonus,
                    recencyTieBreakMs = tab.lastActiveEpochMs,
                    idTieBreak = tab.id,
                    suggestion = LocalSearchSuggestion.SwitchToTab(
                        tabId = tab.id,
                        title = displayTitle,
                        domain = effectiveDomain,
                        url = tab.url.trim(),
                        spaceName = space?.name,
                        spaceAmbientHex = space?.ambientHex,
                    ),
                ),
            )
        }

        val scoredBookmarks = ArrayList<ScoredCandidate>(validBookmarks.size)
        for (bookmark in validBookmarks) {
            val rawUrl = bookmark.url?.trim().orEmpty()
            val effectiveDomain = extractHost(rawUrl)
            val displayTitle = bookmark.title.trim().ifBlank { effectiveDomain.ifBlank { rawUrl } }
            val matchScore = scoreTextMatch(
                queryLower = queryLower,
                hostQuery = hostQuery,
                tokens = tokens,
                title = displayTitle,
                domain = effectiveDomain,
                url = rawUrl,
            )
            if (matchScore <= 0.0) continue

            val pinnedBonus = if (bookmark.isPinnedSquircle) 25.0 else 0.0
            val recencyBonus = computeRecencyScore(
                timestampMs = bookmark.updatedAtEpochMs,
                referenceMaxMs = maxBookmarkUpdatedMs,
                maxBonus = 25.0,
            )
            scoredBookmarks.add(
                ScoredCandidate(
                    normalizedUrl = normalizeUrlForDedup(rawUrl),
                    score = BOOKMARK_TIER_BOOST + matchScore + pinnedBonus + recencyBonus,
                    recencyTieBreakMs = bookmark.updatedAtEpochMs,
                    idTieBreak = bookmark.id,
                    suggestion = LocalSearchSuggestion.BookmarkMatch(
                        bookmarkId = bookmark.id,
                        title = displayTitle,
                        domain = effectiveDomain,
                        url = rawUrl,
                    ),
                ),
            )
        }

        val scoredHistory = ArrayList<ScoredCandidate>(validHistory.size)
        for (entry in validHistory) {
            val rawUrl = entry.url.trim()
            val effectiveDomain = normalizeDomainOrExtract(entry.domain, rawUrl)
            val displayTitle = entry.title.trim().ifBlank { effectiveDomain.ifBlank { rawUrl } }
            val matchScore = scoreTextMatch(
                queryLower = queryLower,
                hostQuery = hostQuery,
                tokens = tokens,
                title = displayTitle,
                domain = effectiveDomain,
                url = rawUrl,
            )
            if (matchScore <= 0.0) continue

            val visits = entry.visitCount.coerceAtLeast(1)
            val visitBonus = ln(visits.toDouble() + 1.0) * 55.0
            val recencyBonus = computeRecencyScore(
                timestampMs = entry.visitedAtEpochMs,
                referenceMaxMs = maxHistoryVisitedMs,
                maxBonus = 100.0,
            )
            scoredHistory.add(
                ScoredCandidate(
                    normalizedUrl = normalizeUrlForDedup(rawUrl),
                    score = HISTORY_TIER_BOOST + matchScore + visitBonus + recencyBonus,
                    recencyTieBreakMs = entry.visitedAtEpochMs,
                    idTieBreak = entry.id,
                    suggestion = LocalSearchSuggestion.HistoryMatch(
                        historyId = entry.id,
                        title = displayTitle,
                        domain = effectiveDomain,
                        url = rawUrl,
                        visitCount = entry.visitCount,
                    ),
                ),
            )
        }

        val comparator = compareByDescending<ScoredCandidate> { it.score }
            .thenByDescending { it.recencyTieBreakMs }
            .thenBy { it.idTieBreak }

        scoredTabs.sortWith(comparator)
        scoredBookmarks.sortWith(comparator)
        scoredHistory.sortWith(comparator)

        val seenUrls = HashSet<String>(cappedMaxResults * 2)
        val merged = ArrayList<LocalSearchSuggestion>(cappedMaxResults)

        fun collectFrom(candidates: List<ScoredCandidate>) {
            for (candidate in candidates) {
                if (merged.size >= cappedMaxResults) return
                val dedupKey = candidate.normalizedUrl.ifEmpty { candidate.suggestion.url.lowercase() }
                if (seenUrls.add(dedupKey)) {
                    merged.add(candidate.suggestion)
                }
            }
        }

        collectFrom(scoredTabs)
        collectFrom(scoredBookmarks)
        collectFrom(scoredHistory)

        return LocalSuggestionResult(
            suggestions = merged,
            inlineAutocomplete = inlineAutocomplete,
        )
    }

    internal fun normalizeUrlForDedup(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""

        val withoutFragment = trimmed.substringBefore('#')
        val withoutScheme = when {
            withoutFragment.startsWith("https://", ignoreCase = true) -> withoutFragment.substring(8)
            withoutFragment.startsWith("http://", ignoreCase = true) -> withoutFragment.substring(7)
            else -> withoutFragment
        }

        val slashIdx = withoutScheme.indexOf('/')
        val queryIdx = withoutScheme.indexOf('?')
        val authorityEnd = when {
            slashIdx >= 0 && queryIdx >= 0 -> minOf(slashIdx, queryIdx)
            slashIdx >= 0 -> slashIdx
            queryIdx >= 0 -> queryIdx
            else -> withoutScheme.length
        }

        val rawAuthority = withoutScheme.substring(0, authorityEnd)
            .substringAfter('@')
            .lowercase()
        val hostAndPort = stripDefaultPort(rawAuthority)
        val normalizedAuthority = stripLeadingWww(hostAndPort).trimEnd('.')

        val remainder = withoutScheme.substring(authorityEnd)
        if (remainder.isEmpty() || remainder == "/") {
            return normalizedAuthority
        }

        val remQueryIdx = remainder.indexOf('?')
        val pathPart = if (remQueryIdx >= 0) remainder.substring(0, remQueryIdx) else remainder
        val queryPart = if (remQueryIdx >= 0) remainder.substring(remQueryIdx) else ""
        val trimmedPath = pathPart.trimEnd('/')

        return buildString(normalizedAuthority.length + trimmedPath.length + queryPart.length) {
            append(normalizedAuthority)
            append(trimmedPath)
            append(queryPart)
        }
    }

    internal fun extractHost(urlOrDomain: String): String {
        val raw = urlOrDomain.trim()
        if (raw.isEmpty() || raw.equals("about:blank", ignoreCase = true)) return ""
        if (raw.startsWith("focal://", ignoreCase = true) || raw.startsWith("focal-pair://", ignoreCase = true)) {
            return ""
        }
        val afterScheme = if ("://" in raw) raw.substringAfter("://") else raw
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')
            .substringBefore(':')
            .trim()
            .trimEnd('.')
            .lowercase()
        val clean = stripLeadingWww(authority)
        return if ('.' in clean || clean == "localhost") clean else ""
    }

    private fun resolveInlineAutocomplete(
        queryLower: String,
        openTabs: List<TabMetadataEntity>,
        bookmarks: List<BookmarkEntity>,
        history: List<HistoryEntryEntity>,
    ): InlineUrlAutocomplete? {
        if (queryLower.length < 2) return null
        if ("://" in queryLower || '/' in queryLower || '?' in queryLower || '#' in queryLower) {
            return null
        }

        val candidatesByHost = LinkedHashMap<String, AutocompleteCandidate>()

        fun consider(host: String, rawUrl: String?, score: Double) {
            if (host.length <= queryLower.length || !host.startsWith(queryLower)) return
            val scheme = if (rawUrl != null && rawUrl.trim().startsWith("http://", ignoreCase = true)) {
                "http"
            } else {
                "https"
            }
            val targetUrl = "$scheme://$host"
            val existing = candidatesByHost[host]
            if (existing == null || score > existing.score) {
                candidatesByHost[host] = AutocompleteCandidate(
                    host = host,
                    targetUrl = targetUrl,
                    score = score,
                )
            }
        }

        val maxBookmarkUpdatedMs = bookmarks.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
        for (bookmark in bookmarks) {
            val rawUrl = bookmark.url?.trim().orEmpty()
            val host = extractHost(rawUrl)
            if (host.isEmpty()) continue
            val pinnedBonus = if (bookmark.isPinnedSquircle) 40.0 else 0.0
            val recencyBonus = computeRecencyScore(bookmark.updatedAtEpochMs, maxBookmarkUpdatedMs, 20.0)
            consider(host, rawUrl, AUTOCOMPLETE_BOOKMARK_BASE + pinnedBonus + recencyBonus)
        }

        val maxTabActiveMs = openTabs.maxOfOrNull { it.lastActiveEpochMs } ?: 0L
        for (tab in openTabs) {
            val host = normalizeDomainOrExtract(tab.domain, tab.url)
            if (host.isEmpty()) continue
            val recencyBonus = computeRecencyScore(tab.lastActiveEpochMs, maxTabActiveMs, 25.0)
            consider(host, tab.url, AUTOCOMPLETE_TAB_BASE + recencyBonus)
        }

        val maxHistoryVisitedMs = history.maxOfOrNull { it.visitedAtEpochMs } ?: 0L
        for (entry in history) {
            val host = normalizeDomainOrExtract(entry.domain, entry.url)
            if (host.isEmpty()) continue
            val visits = entry.visitCount.coerceAtLeast(1)
            val visitBonus = ln(visits.toDouble() + 1.0) * 40.0
            val recencyBonus = computeRecencyScore(entry.visitedAtEpochMs, maxHistoryVisitedMs, 50.0)
            consider(host, entry.url, AUTOCOMPLETE_HISTORY_BASE + visitBonus + recencyBonus)
        }

        BuiltInPopularHosts.forEachIndexed { index, host ->
            val rankBonus = (BuiltInPopularHosts.size - index).toDouble()
            consider(host, "https://$host", AUTOCOMPLETE_BUILTIN_BASE + rankBonus)
        }

        val best = candidatesByHost.values.maxWithOrNull(
            compareBy<AutocompleteCandidate> { it.score }
                .thenByDescending { it.host.length }
                .thenByDescending { it.host },
        ) ?: return null

        return InlineUrlAutocomplete(
            displayHost = best.host,
            targetUrl = best.targetUrl,
            completionSuffix = best.host.removePrefix(queryLower),
        )
    }

    private fun scoreTextMatch(
        queryLower: String,
        hostQuery: String,
        tokens: List<String>,
        title: String,
        domain: String,
        url: String,
    ): Double {
        val titleLower = title.lowercase()
        val domainLower = domain.lowercase()
        val urlLower = url.trim().lowercase()
        val normalizedUrlLower = normalizeUrlForDedup(url).lowercase()

        if (tokens.size == 1) {
            val domainStem = domainLower.substringBefore('.')
            return when {
                hostQuery.isNotEmpty() && (domainLower == hostQuery || domainStem == hostQuery) -> 540.0
                hostQuery.isNotEmpty() && domainLower.startsWith(hostQuery) -> 460.0
                hostQuery.isNotEmpty() && hasDomainLabelPrefix(domainLower, hostQuery) -> 380.0
                titleLower.startsWith(queryLower) -> 340.0
                hasWordPrefix(titleLower, queryLower) -> 280.0
                hostQuery.isNotEmpty() && normalizedUrlLower.startsWith(hostQuery) -> 240.0
                urlLower.startsWith(queryLower) -> 220.0
                hostQuery.isNotEmpty() && domainLower.contains(hostQuery) -> 140.0
                titleLower.contains(queryLower) -> 100.0
                urlLower.contains(queryLower) -> 60.0
                else -> 0.0
            }
        }

        // Multi-token query: every token must match title, domain, or URL.
        var tokenScoreSum = 0.0
        for (token in tokens) {
            val tokenMatch = when {
                domainLower.startsWith(token) || hasDomainLabelPrefix(domainLower, token) -> 95.0
                titleLower.startsWith(token) || hasWordPrefix(titleLower, token) -> 85.0
                domainLower.contains(token) -> 60.0
                titleLower.contains(token) -> 50.0
                urlLower.contains(token) -> 35.0
                else -> return 0.0
            }
            tokenScoreSum += tokenMatch
        }

        val phraseBonus = when {
            titleLower.startsWith(queryLower) -> 140.0
            titleLower.contains(queryLower) -> 90.0
            urlLower.contains(queryLower) -> 45.0
            else -> 0.0
        }
        return (tokenScoreSum / tokens.size) * 2.2 + phraseBonus
    }

    private fun hasDomainLabelPrefix(domainLower: String, prefix: String): Boolean {
        if (domainLower.isEmpty() || prefix.isEmpty()) return false
        var start = 0
        while (start < domainLower.length) {
            val dot = domainLower.indexOf('.', start)
            val end = if (dot < 0) domainLower.length else dot
            if (end - start >= prefix.length && domainLower.regionMatches(start, prefix, 0, prefix.length)) {
                return true
            }
            if (dot < 0) break
            start = dot + 1
        }
        return false
    }

    private fun hasWordPrefix(textLower: String, prefix: String): Boolean {
        if (textLower.isEmpty() || prefix.isEmpty()) return false
        return textLower
            .split(WORD_SEPARATOR_REGEX)
            .any { word -> word.isNotEmpty() && word.startsWith(prefix) }
    }

    private fun computeRecencyScore(
        timestampMs: Long,
        referenceMaxMs: Long,
        maxBonus: Double,
    ): Double {
        if (referenceMaxMs <= 0L || timestampMs <= 0L) return 0.0
        val ageMs = (referenceMaxMs - timestampMs).coerceAtLeast(0L)
        val ageHours = ageMs.toDouble() / 3_600_000.0
        return maxBonus / (1.0 + ageHours / 24.0)
    }

    private fun normalizeDomainOrExtract(rawDomain: String, rawUrl: String): String {
        return extractHost(rawDomain).ifBlank { extractHost(rawUrl) }
    }

    private fun stripSchemeAndWww(value: String): String {
        val withoutScheme = when {
            value.startsWith("https://") -> value.substring(8)
            value.startsWith("http://") -> value.substring(7)
            else -> value
        }
        return stripLeadingWww(withoutScheme)
    }

    private fun stripLeadingWww(host: String): String {
        return if (host.startsWith("www.") && host.indexOf('.', 4) > 0) {
            host.removePrefix("www.")
        } else {
            host
        }
    }

    private fun stripDefaultPort(authority: String): String {
        return when {
            authority.endsWith(":443") -> authority.removeSuffix(":443")
            authority.endsWith(":80") -> authority.removeSuffix(":80")
            else -> authority
        }
    }

    private fun isNavigableUrl(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return !trimmed.equals("about:blank", ignoreCase = true)
    }

    private data class ScoredCandidate(
        val normalizedUrl: String,
        val score: Double,
        val recencyTieBreakMs: Long,
        val idTieBreak: String,
        val suggestion: LocalSearchSuggestion,
    )

    private data class AutocompleteCandidate(
        val host: String,
        val targetUrl: String,
        val score: Double,
    )
}
