package app.focal.feature.search

import app.focal.database.BookmarkEntity
import app.focal.database.HistoryEntryEntity
import app.focal.database.SpaceEntity
import app.focal.database.TabMetadataEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchSuggesterTest {

    private val baseNowMs = 1_763_400_000_000L
    private val oneHourMs = 60L * 60L * 1_000L
    private val oneDayMs = 24L * oneHourMs

    private val workSpace = SpaceEntity(
        id = "work",
        name = "Work",
        ambientHex = "#94A3B8",
        sortOrder = 1,
        updatedAtEpochMs = baseNowMs,
    )

    private val readingSpace = SpaceEntity(
        id = "reading",
        name = "Reading",
        ambientHex = "#4ADE80",
        sortOrder = 2,
        updatedAtEpochMs = baseNowMs,
    )

    @Test
    fun quietState_skipsSuggestionsAndAutocompleteWhenQueryUnderTwoChars() {
        val tabs = listOf(
            TabMetadataEntity(
                id = "t1",
                spaceId = "work",
                url = "https://github.com/oupadhyay/focal",
                domain = "github.com",
                title = "GitHub Focal",
                lastActiveEpochMs = baseNowMs,
                createdAtEpochMs = baseNowMs - oneHourMs,
            ),
        )
        val bookmarks = listOf(
            BookmarkEntity(
                id = "b1",
                spaceId = "work",
                title = "GitHub",
                url = "https://github.com",
                createdAtEpochMs = baseNowMs,
                updatedAtEpochMs = baseNowMs,
            ),
        )
        val history = listOf(
            HistoryEntryEntity(
                id = "h1",
                url = "https://github.com",
                domain = "github.com",
                title = "GitHub",
                visitedAtEpochMs = baseNowMs,
                visitCount = 42,
            ),
        )

        for (shortQuery in listOf("", "   ", "g", " g ", "\t")) {
            val result = LocalSearchSuggester.suggest(
                query = shortQuery,
                openTabs = tabs,
                bookmarks = bookmarks,
                history = history,
                spacesById = mapOf(workSpace.id to workSpace),
            )
            assertTrue("Expected empty suggestions for '$shortQuery'", result.suggestions.isEmpty())
            assertNull("Expected null inlineAutocomplete for '$shortQuery'", result.inlineAutocomplete)
        }
    }

    @Test
    fun openTabsRankAboveBookmarksAndHistory_andIncludeSpaceMetadata() {
        val tabs = listOf(
            TabMetadataEntity(
                id = "t-active",
                spaceId = "work",
                url = "https://github.com/oupadhyay/focal",
                domain = "github.com",
                title = "oupadhyay/focal",
                lastActiveEpochMs = baseNowMs - oneHourMs,
                createdAtEpochMs = baseNowMs - oneDayMs,
            ),
            TabMetadataEntity(
                id = "t-archived",
                spaceId = "work",
                url = "https://github.com/archived/repo",
                domain = "github.com",
                title = "Archived GitHub Repo",
                lastActiveEpochMs = baseNowMs,
                createdAtEpochMs = baseNowMs - oneDayMs,
                isArchived = true,
            ),
            TabMetadataEntity(
                id = "t-blank",
                spaceId = "work",
                url = "about:blank",
                domain = "",
                title = "github blank",
                lastActiveEpochMs = baseNowMs,
                createdAtEpochMs = baseNowMs,
            ),
        )
        val bookmarks = listOf(
            BookmarkEntity(
                id = "b-folder",
                spaceId = "work",
                isFolder = true,
                title = "GitHub Folder",
                url = null,
                createdAtEpochMs = baseNowMs,
                updatedAtEpochMs = baseNowMs,
            ),
            BookmarkEntity(
                id = "b-blank",
                spaceId = "work",
                isFolder = false,
                title = "GitHub Empty Url",
                url = "   ",
                createdAtEpochMs = baseNowMs,
                updatedAtEpochMs = baseNowMs,
            ),
            BookmarkEntity(
                id = "b-kotlin",
                spaceId = "work",
                title = "GitHub Kotlin",
                url = "https://github.com/JetBrains/kotlin",
                createdAtEpochMs = baseNowMs,
                updatedAtEpochMs = baseNowMs,
            ),
        )
        val history = listOf(
            HistoryEntryEntity(
                id = "h-top",
                url = "https://github.com",
                domain = "github.com",
                title = "GitHub",
                visitedAtEpochMs = baseNowMs,
                visitCount = 250,
            ),
        )

        val result = LocalSearchSuggester.suggest(
            query = "git",
            openTabs = tabs,
            bookmarks = bookmarks,
            history = history,
            spacesById = mapOf(workSpace.id to workSpace, readingSpace.id to readingSpace),
        )

        assertEquals(3, result.suggestions.size)

        val first = result.suggestions[0] as LocalSearchSuggestion.SwitchToTab
        assertEquals("t-active", first.tabId)
        assertEquals("Work", first.spaceName)
        assertEquals("#94A3B8", first.spaceAmbientHex)

        val second = result.suggestions[1] as LocalSearchSuggestion.BookmarkMatch
        assertEquals("b-kotlin", second.bookmarkId)

        val third = result.suggestions[2] as LocalSearchSuggestion.HistoryMatch
        assertEquals("h-top", third.historyId)
        assertEquals(250, third.visitCount)
    }

    @Test
    fun deduplicatesNormalizedUrlsAcrossOpenTabsBookmarksAndHistory() {
        val tabs = listOf(
            TabMetadataEntity(
                id = "t1",
                spaceId = "work",
                url = "https://www.github.com/oupadhyay/focal/",
                domain = "www.github.com",
                title = "Focal Browser",
                lastActiveEpochMs = baseNowMs,
                createdAtEpochMs = baseNowMs - oneHourMs,
            ),
        )
        val bookmarks = listOf(
            BookmarkEntity(
                id = "b-dup-tab",
                spaceId = "work",
                title = "Focal Browser Bookmark",
                url = "https://github.com/oupadhyay/focal",
                createdAtEpochMs = baseNowMs,
                updatedAtEpochMs = baseNowMs,
            ),
            BookmarkEntity(
                id = "b-unique",
                spaceId = "reading",
                title = "Focal Architecture Notes",
                url = "https://focal.dev/architecture/",
                createdAtEpochMs = baseNowMs,
                updatedAtEpochMs = baseNowMs,
            ),
        )
        val history = listOf(
            HistoryEntryEntity(
                id = "h-dup-tab",
                url = "http://github.com/oupadhyay/focal#readme",
                domain = "github.com",
                title = "Focal Readme",
                visitedAtEpochMs = baseNowMs,
                visitCount = 50,
            ),
            HistoryEntryEntity(
                id = "h-dup-bookmark",
                url = "https://www.focal.dev/architecture",
                domain = "focal.dev",
                title = "Focal Architecture",
                visitedAtEpochMs = baseNowMs,
                visitCount = 20,
            ),
            HistoryEntryEntity(
                id = "h-distinct-query",
                url = "https://focal.dev/architecture?tab=perf",
                domain = "focal.dev",
                title = "Focal Performance Architecture",
                visitedAtEpochMs = baseNowMs - oneHourMs,
                visitCount = 5,
            ),
        )

        val result = LocalSearchSuggester.suggest(
            query = "focal",
            openTabs = tabs,
            bookmarks = bookmarks,
            history = history,
            spacesById = mapOf(workSpace.id to workSpace),
        )

        assertEquals(3, result.suggestions.size)
        assertTrue(result.suggestions[0] is LocalSearchSuggestion.SwitchToTab)
        assertEquals("t1", (result.suggestions[0] as LocalSearchSuggestion.SwitchToTab).tabId)

        assertTrue(result.suggestions[1] is LocalSearchSuggestion.BookmarkMatch)
        assertEquals("b-unique", (result.suggestions[1] as LocalSearchSuggestion.BookmarkMatch).bookmarkId)

        assertTrue(result.suggestions[2] is LocalSearchSuggestion.HistoryMatch)
        assertEquals("h-distinct-query", (result.suggestions[2] as LocalSearchSuggestion.HistoryMatch).historyId)
    }

    @Test
    fun historyRanking_weightsPrefixMatchVisitCountAndRecency() {
        val history = listOf(
            HistoryEntryEntity(
                id = "h-substring-many",
                url = "https://example.org/archive/notes-about-dev-tools",
                domain = "example.org",
                title = "Notes archive",
                visitedAtEpochMs = baseNowMs,
                visitCount = 30,
            ),
            HistoryEntryEntity(
                id = "h-prefix-low-old",
                url = "https://devtools.dev/recent",
                domain = "devtools.dev",
                title = "DevTools Reference",
                visitedAtEpochMs = baseNowMs - (14L * oneDayMs),
                visitCount = 1,
            ),
            HistoryEntryEntity(
                id = "h-prefix-high-recent",
                url = "https://developer.android.com/jetpack/compose",
                domain = "developer.android.com",
                title = "Android Developers",
                visitedAtEpochMs = baseNowMs - oneHourMs,
                visitCount = 24,
            ),
            HistoryEntryEntity(
                id = "h-prefix-same-visits-recent",
                url = "https://devdocs.io/kotlin/",
                domain = "devdocs.io",
                title = "DevDocs Kotlin",
                visitedAtEpochMs = baseNowMs,
                visitCount = 1,
            ),
        )

        val result = LocalSearchSuggester.suggest(
            query = "dev",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = history,
            maxResults = 4,
        )

        val ids = result.suggestions.map { (it as LocalSearchSuggestion.HistoryMatch).historyId }
        assertEquals(4, ids.size)
        // High visit count + recent domain prefix ranks first
        assertEquals("h-prefix-high-recent", ids[0])
        // Same visit count (1) and same domain prefix tier -> recent beats 14-day-old
        assertTrue(ids.indexOf("h-prefix-same-visits-recent") < ids.indexOf("h-prefix-low-old"))
        // Substring URL match ranks after all domain prefix matches
        assertEquals("h-substring-many", ids.last())
    }

    @Test
    fun historyRanking_higherVisitCountWinsAtEqualRecencyAndMatchTier() {
        val history = listOf(
            HistoryEntryEntity(
                id = "h-few",
                url = "https://kotlinlang.org/docs/basics.html",
                domain = "kotlinlang.org",
                title = "Kotlin Basics",
                visitedAtEpochMs = baseNowMs,
                visitCount = 2,
            ),
            HistoryEntryEntity(
                id = "h-frequent",
                url = "https://kotlinlang.org/docs/coroutines.html",
                domain = "kotlinlang.org",
                title = "Kotlin Coroutines",
                visitedAtEpochMs = baseNowMs,
                visitCount = 18,
            ),
        )

        val result = LocalSearchSuggester.suggest(
            query = "kot",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = history,
        )

        assertEquals(2, result.suggestions.size)
        assertEquals("h-frequent", (result.suggestions[0] as LocalSearchSuggestion.HistoryMatch).historyId)
        assertEquals("h-few", (result.suggestions[1] as LocalSearchSuggestion.HistoryMatch).historyId)
    }

    @Test
    fun inlineAutocomplete_computesSuffixForBuiltInAndLocalHosts_andRespectsGuards() {
        // Built-in popular host match when local stores are empty
        val gitBuiltIn = LocalSearchSuggester.suggest(
            query = "git",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = emptyList(),
        )
        assertTrue(gitBuiltIn.suggestions.isEmpty())
        assertNotNull(gitBuiltIn.inlineAutocomplete)
        assertEquals("github.com", gitBuiltIn.inlineAutocomplete?.displayHost)
        assertEquals("hub.com", gitBuiltIn.inlineAutocomplete?.completionSuffix)
        assertEquals("https://github.com", gitBuiltIn.inlineAutocomplete?.targetUrl)

        // Case-insensitive query produces exact lowercase remainder suffix
        val upperDev = LocalSearchSuggester.suggest(
            query = "DEV",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = emptyList(),
        )
        assertEquals("developer.android.com", upperDev.inlineAutocomplete?.displayHost)
        assertEquals("eloper.android.com", upperDev.inlineAutocomplete?.completionSuffix)
        assertEquals("https://developer.android.com", upperDev.inlineAutocomplete?.targetUrl)

        // Local bookmark host takes priority over built-in host for the same prefix
        val gitlabBookmark = BookmarkEntity(
            id = "b-gl",
            spaceId = "work",
            title = "GitLab",
            url = "https://gitlab.com/dashboard",
            createdAtEpochMs = baseNowMs,
            updatedAtEpochMs = baseNowMs,
        )
        val gitLocal = LocalSearchSuggester.suggest(
            query = "git",
            openTabs = emptyList(),
            bookmarks = listOf(gitlabBookmark),
            history = emptyList(),
        )
        assertEquals("gitlab.com", gitLocal.inlineAutocomplete?.displayHost)
        assertEquals("lab.com", gitLocal.inlineAutocomplete?.completionSuffix)
        assertEquals("https://gitlab.com", gitLocal.inlineAutocomplete?.targetUrl)

        // Exact full host has no remaining suffix -> null autocomplete
        val exactHost = LocalSearchSuggester.suggest(
            query = "github.com",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = emptyList(),
        )
        assertNull(exactHost.inlineAutocomplete)

        // Queries with spaces never produce inline URL autocomplete
        val withSpace = LocalSearchSuggester.suggest(
            query = "git rebase",
            openTabs = emptyList(),
            bookmarks = listOf(gitlabBookmark),
            history = emptyList(),
        )
        assertNull(withSpace.inlineAutocomplete)

        val trailingSpace = LocalSearchSuggester.suggest(
            query = "git ",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = emptyList(),
        )
        assertNull(trailingSpace.inlineAutocomplete)
    }

    @Test
    fun multiTokenQueriesAndMaxResultsCapWorkDeterministically() {
        val history = (1..6).map { idx ->
            HistoryEntryEntity(
                id = "h-$idx",
                url = "https://developer.android.com/jetpack/compose/guide-$idx",
                domain = "developer.android.com",
                title = "Jetpack Compose Guide $idx",
                visitedAtEpochMs = baseNowMs - (idx * oneHourMs),
                visitCount = 10 - idx,
            )
        }

        val capped = LocalSearchSuggester.suggest(
            query = "jetpack compose",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = history,
            maxResults = 4,
        )
        assertEquals(4, capped.suggestions.size)
        assertNull(capped.inlineAutocomplete)
        assertEquals("h-1", (capped.suggestions[0] as LocalSearchSuggestion.HistoryMatch).historyId)

        // Non-matching second token filters out candidates cleanly
        val noMatch = LocalSearchSuggester.suggest(
            query = "jetpack python",
            openTabs = emptyList(),
            bookmarks = emptyList(),
            history = history,
        )
        assertTrue(noMatch.suggestions.isEmpty())
    }
}
