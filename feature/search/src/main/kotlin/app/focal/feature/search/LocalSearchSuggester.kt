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

        val canAutocomplete = query.none { it.isWhitespace() } &&
            queryLower.length >= 2 &&
            "://" !in queryLower &&
            '/' !in queryLower &&
            '?' !in queryLower &&
            '#' !in queryLower

        val cappedMaxResults = maxResults.coerceAtLeast(0)
        val needSuggestions = cappedMaxResults > 0
        val singleToken = tokens.size == 1
        val hostQueryHasPathOrPort = hostQuery.isNotEmpty() &&
            ('/' in hostQuery || '?' in hostQuery || ':' in hostQuery)

        // Single pass over openTabs: compute max timestamp, autocomplete hits, and scored suggestions together.
        var maxTabActiveMs = 0L
        var hasValidTab = false
        val tabAutocompleteMatches = if (canAutocomplete) ArrayList<RawAutocompleteHit>(4) else null
        val scoredTabs = if (needSuggestions) ArrayList<ScoredCandidate>(minOf(openTabs.size, 16)) else null

        for (tab in openTabs) {
            if (tab.isArchived || !isNavigableUrl(tab.url)) continue
            val ts = tab.lastActiveEpochMs
            if (!hasValidTab || ts > maxTabActiveMs) {
                maxTabActiveMs = ts
                hasValidTab = true
            }
            val rawUrl = tab.url.trim()
            val effectiveDomain = normalizeDomainOrExtract(tab.domain, rawUrl)

            if (
                tabAutocompleteMatches != null &&
                effectiveDomain.length > queryLower.length &&
                effectiveDomain.startsWith(queryLower)
            ) {
                tabAutocompleteMatches.add(
                    RawAutocompleteHit(
                        host = effectiveDomain,
                        rawUrl = rawUrl,
                        baseScore = AUTOCOMPLETE_TAB_BASE,
                        timestampMs = ts,
                    ),
                )
            }

            if (scoredTabs != null) {
                val displayTitle = tab.title.trim().ifBlank { effectiveDomain.ifBlank { rawUrl } }
                val matchScore = scoreTextMatch(
                    queryLower = queryLower,
                    hostQuery = hostQuery,
                    hostQueryHasPathOrPort = hostQueryHasPathOrPort,
                    tokens = tokens,
                    singleToken = singleToken,
                    title = displayTitle,
                    domainLower = effectiveDomain,
                    rawUrl = rawUrl,
                )
                if (matchScore > 0.0) {
                    val space = spacesById[tab.spaceId]
                    scoredTabs.add(
                        ScoredCandidate(
                            normalizedUrl = normalizeUrlForDedup(rawUrl),
                            score = OPEN_TAB_TIER_BOOST + matchScore,
                            recencyTieBreakMs = ts,
                            idTieBreak = tab.id,
                            suggestion = LocalSearchSuggestion.SwitchToTab(
                                tabId = tab.id,
                                title = displayTitle,
                                domain = effectiveDomain,
                                url = rawUrl,
                                spaceName = space?.name,
                                spaceAmbientHex = space?.ambientHex,
                            ),
                        ),
                    )
                }
            }
        }
        if (scoredTabs != null && scoredTabs.isNotEmpty() && maxTabActiveMs > 0L) {
            for (candidate in scoredTabs) {
                candidate.score += computeRecencyScore(
                    timestampMs = candidate.recencyTieBreakMs,
                    referenceMaxMs = maxTabActiveMs,
                    maxBonus = 50.0,
                )
            }
        }

        // Single pass over bookmarks.
        var maxBookmarkUpdatedMs = 0L
        var hasValidBookmark = false
        val bookmarkAutocompleteMatches = if (canAutocomplete) ArrayList<RawAutocompleteHit>(4) else null
        val scoredBookmarks = if (needSuggestions) ArrayList<ScoredCandidate>(minOf(bookmarks.size, 16)) else null

        for (bookmark in bookmarks) {
            if (bookmark.isFolder || !isNavigableUrl(bookmark.url)) continue
            val ts = bookmark.updatedAtEpochMs
            if (!hasValidBookmark || ts > maxBookmarkUpdatedMs) {
                maxBookmarkUpdatedMs = ts
                hasValidBookmark = true
            }
            val rawUrl = bookmark.url?.trim().orEmpty()
            val effectiveDomain = extractHost(rawUrl)

            if (
                bookmarkAutocompleteMatches != null &&
                effectiveDomain.length > queryLower.length &&
                effectiveDomain.startsWith(queryLower)
            ) {
                val pinnedAutoBonus = if (bookmark.isPinnedSquircle) 40.0 else 0.0
                bookmarkAutocompleteMatches.add(
                    RawAutocompleteHit(
                        host = effectiveDomain,
                        rawUrl = rawUrl,
                        baseScore = AUTOCOMPLETE_BOOKMARK_BASE + pinnedAutoBonus,
                        timestampMs = ts,
                    ),
                )
            }

            if (scoredBookmarks != null) {
                val displayTitle = bookmark.title.trim().ifBlank { effectiveDomain.ifBlank { rawUrl } }
                val matchScore = scoreTextMatch(
                    queryLower = queryLower,
                    hostQuery = hostQuery,
                    hostQueryHasPathOrPort = hostQueryHasPathOrPort,
                    tokens = tokens,
                    singleToken = singleToken,
                    title = displayTitle,
                    domainLower = effectiveDomain,
                    rawUrl = rawUrl,
                )
                if (matchScore > 0.0) {
                    val pinnedBonus = if (bookmark.isPinnedSquircle) 25.0 else 0.0
                    scoredBookmarks.add(
                        ScoredCandidate(
                            normalizedUrl = normalizeUrlForDedup(rawUrl),
                            score = BOOKMARK_TIER_BOOST + matchScore + pinnedBonus,
                            recencyTieBreakMs = ts,
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
            }
        }
        if (scoredBookmarks != null && scoredBookmarks.isNotEmpty() && maxBookmarkUpdatedMs > 0L) {
            for (candidate in scoredBookmarks) {
                candidate.score += computeRecencyScore(
                    timestampMs = candidate.recencyTieBreakMs,
                    referenceMaxMs = maxBookmarkUpdatedMs,
                    maxBonus = 25.0,
                )
            }
        }

        // Single pass over history.
        var maxHistoryVisitedMs = 0L
        var hasValidHistory = false
        val historyAutocompleteMatches = if (canAutocomplete) ArrayList<RawAutocompleteHit>(8) else null
        val scoredHistory = if (needSuggestions) ArrayList<ScoredCandidate>(minOf(history.size, 32)) else null

        for (entry in history) {
            if (!isNavigableUrl(entry.url)) continue
            val ts = entry.visitedAtEpochMs
            if (!hasValidHistory || ts > maxHistoryVisitedMs) {
                maxHistoryVisitedMs = ts
                hasValidHistory = true
            }
            val rawUrl = entry.url.trim()
            val effectiveDomain = normalizeDomainOrExtract(entry.domain, rawUrl)

            if (
                historyAutocompleteMatches != null &&
                effectiveDomain.length > queryLower.length &&
                effectiveDomain.startsWith(queryLower)
            ) {
                val visits = entry.visitCount.coerceAtLeast(1)
                val visitBonus = ln(visits.toDouble() + 1.0) * 40.0
                historyAutocompleteMatches.add(
                    RawAutocompleteHit(
                        host = effectiveDomain,
                        rawUrl = rawUrl,
                        baseScore = AUTOCOMPLETE_HISTORY_BASE + visitBonus,
                        timestampMs = ts,
                    ),
                )
            }

            if (scoredHistory != null) {
                val displayTitle = entry.title.trim().ifBlank { effectiveDomain.ifBlank { rawUrl } }
                val matchScore = scoreTextMatch(
                    queryLower = queryLower,
                    hostQuery = hostQuery,
                    hostQueryHasPathOrPort = hostQueryHasPathOrPort,
                    tokens = tokens,
                    singleToken = singleToken,
                    title = displayTitle,
                    domainLower = effectiveDomain,
                    rawUrl = rawUrl,
                )
                if (matchScore > 0.0) {
                    val visits = entry.visitCount.coerceAtLeast(1)
                    val visitBonus = ln(visits.toDouble() + 1.0) * 55.0
                    scoredHistory.add(
                        ScoredCandidate(
                            normalizedUrl = normalizeUrlForDedup(rawUrl),
                            score = HISTORY_TIER_BOOST + matchScore + visitBonus,
                            recencyTieBreakMs = ts,
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
            }
        }
        if (scoredHistory != null && scoredHistory.isNotEmpty() && maxHistoryVisitedMs > 0L) {
            for (candidate in scoredHistory) {
                candidate.score += computeRecencyScore(
                    timestampMs = candidate.recencyTieBreakMs,
                    referenceMaxMs = maxHistoryVisitedMs,
                    maxBonus = 100.0,
                )
            }
        }

        val inlineAutocomplete = if (canAutocomplete) {
            finalizeInlineAutocomplete(
                queryLower = queryLower,
                bookmarkHits = bookmarkAutocompleteMatches!!,
                maxBookmarkUpdatedMs = maxBookmarkUpdatedMs,
                tabHits = tabAutocompleteMatches!!,
                maxTabActiveMs = maxTabActiveMs,
                historyHits = historyAutocompleteMatches!!,
                maxHistoryVisitedMs = maxHistoryVisitedMs,
            )
        } else {
            null
        }

        if (!needSuggestions) {
            return LocalSuggestionResult(
                suggestions = emptyList(),
                inlineAutocomplete = inlineAutocomplete,
            )
        }

        val comparator = compareByDescending<ScoredCandidate> { it.score }
            .thenByDescending { it.recencyTieBreakMs }
            .thenBy { it.idTieBreak }

        scoredTabs!!.sortWith(comparator)
        scoredBookmarks!!.sortWith(comparator)
        scoredHistory!!.sortWith(comparator)

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

    private fun finalizeInlineAutocomplete(
        queryLower: String,
        bookmarkHits: List<RawAutocompleteHit>,
        maxBookmarkUpdatedMs: Long,
        tabHits: List<RawAutocompleteHit>,
        maxTabActiveMs: Long,
        historyHits: List<RawAutocompleteHit>,
        maxHistoryVisitedMs: Long,
    ): InlineUrlAutocomplete? {
        val candidatesByHost = LinkedHashMap<String, AutocompleteCandidate>()

        fun consider(host: String, rawUrl: String?, score: Double) {
            if (host.length <= queryLower.length || !host.startsWith(queryLower)) return
            val scheme = if (rawUrl != null && rawUrl.startsWith("http://", ignoreCase = true)) {
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

        for (hit in bookmarkHits) {
            val recencyBonus = computeRecencyScore(hit.timestampMs, maxBookmarkUpdatedMs, 20.0)
            consider(hit.host, hit.rawUrl, hit.baseScore + recencyBonus)
        }

        for (hit in tabHits) {
            val recencyBonus = computeRecencyScore(hit.timestampMs, maxTabActiveMs, 25.0)
            consider(hit.host, hit.rawUrl, hit.baseScore + recencyBonus)
        }

        for (hit in historyHits) {
            val recencyBonus = computeRecencyScore(hit.timestampMs, maxHistoryVisitedMs, 50.0)
            consider(hit.host, hit.rawUrl, hit.baseScore + recencyBonus)
        }

        BuiltInPopularHosts.forEachIndexed { index, host ->
            if (host.length > queryLower.length && host.startsWith(queryLower)) {
                val rankBonus = (BuiltInPopularHosts.size - index).toDouble()
                consider(host, "https://$host", AUTOCOMPLETE_BUILTIN_BASE + rankBonus)
            }
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
        hostQueryHasPathOrPort: Boolean,
        tokens: List<String>,
        singleToken: Boolean,
        title: String,
        domainLower: String,
        rawUrl: String,
    ): Double {
        if (singleToken) {
            if (hostQuery.isNotEmpty()) {
                if (domainLower == hostQuery || domainStemEquals(domainLower, hostQuery)) return 540.0
                if (domainLower.startsWith(hostQuery)) return 460.0
                if (hasDomainLabelPrefix(domainLower, hostQuery)) return 380.0
            }
            if (title.startsWith(queryLower, ignoreCase = true)) return 340.0
            if (hasWordPrefix(title, queryLower)) return 280.0
            if (
                hostQuery.isNotEmpty() &&
                (hostQueryHasPathOrPort || domainLower.isEmpty() || rawUrl.contains(hostQuery, ignoreCase = true))
            ) {
                if (normalizeUrlForDedup(rawUrl).startsWith(hostQuery, ignoreCase = true)) return 240.0
            }
            if (rawUrl.startsWith(queryLower, ignoreCase = true)) return 220.0
            if (hostQuery.isNotEmpty() && domainLower.contains(hostQuery)) return 140.0
            if (title.contains(queryLower, ignoreCase = true)) return 100.0
            if (rawUrl.contains(queryLower, ignoreCase = true)) return 60.0
            return 0.0
        }

        // Multi-token query: every token must match title, domain, or URL.
        var tokenScoreSum = 0.0
        for (token in tokens) {
            val tokenMatch = when {
                domainLower.startsWith(token) || hasDomainLabelPrefix(domainLower, token) -> 95.0
                title.startsWith(token, ignoreCase = true) || hasWordPrefix(title, token) -> 85.0
                domainLower.contains(token) -> 60.0
                title.contains(token, ignoreCase = true) -> 50.0
                rawUrl.contains(token, ignoreCase = true) -> 35.0
                else -> return 0.0
            }
            tokenScoreSum += tokenMatch
        }

        val phraseBonus = when {
            title.startsWith(queryLower, ignoreCase = true) -> 140.0
            title.contains(queryLower, ignoreCase = true) -> 90.0
            rawUrl.contains(queryLower, ignoreCase = true) -> 45.0
            else -> 0.0
        }
        return (tokenScoreSum / tokens.size) * 2.2 + phraseBonus
    }

    private fun domainStemEquals(domainLower: String, hostQuery: String): Boolean {
        val dot = domainLower.indexOf('.')
        val stemLen = if (dot < 0) domainLower.length else dot
        return stemLen == hostQuery.length && domainLower.regionMatches(0, hostQuery, 0, stemLen)
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

    private fun isWordSeparator(c: Char): Boolean =
        c.isWhitespace() ||
            c == '/' || c == '_' || c == '.' || c == ':' ||
            c == '-' || c == '–' || c == '—' || c == '|' ||
            c == '·' || c == ',' || c == '(' || c == ')' ||
            c == '[' || c == ']'

    private fun hasWordPrefix(text: String, prefixLower: String): Boolean {
        if (text.isEmpty() || prefixLower.isEmpty()) return false
        val len = text.length
        val pLen = prefixLower.length
        var i = 0
        while (i < len) {
            while (i < len && isWordSeparator(text[i])) i++
            if (i >= len) break
            val wordStart = i
            while (i < len && !isWordSeparator(text[i])) i++
            if (i - wordStart >= pLen && text.regionMatches(wordStart, prefixLower, 0, pLen, ignoreCase = true)) {
                return true
            }
        }
        return false
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

    private class ScoredCandidate(
        val normalizedUrl: String,
        var score: Double,
        val recencyTieBreakMs: Long,
        val idTieBreak: String,
        val suggestion: LocalSearchSuggestion,
    )

    private class RawAutocompleteHit(
        val host: String,
        val rawUrl: String,
        val baseScore: Double,
        val timestampMs: Long,
    )

    private data class AutocompleteCandidate(
        val host: String,
        val targetUrl: String,
        val score: Double,
    )
}
