package app.focal

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.focal.database.BookmarkEntity
import app.focal.database.FocalDatabase
import app.focal.database.SpaceEntity
import app.focal.database.TabMetadataEntity
import app.focal.engine.api.BrowserEngine
import app.focal.engine.api.EngineSession
import app.focal.engine.api.ReadableDocument
import app.focal.feature.media.FocalMediaBridge
import app.focal.sync.FocalSyncCrypto
import app.focal.sync.PairingConfirmationPreview
import app.focal.sync.PresenceTabItem
import app.focal.sync.SpaceBookmarkItem
import app.focal.sync.SpaceSyncModel
import app.focal.sync.SyncConnectionStatus
import app.focal.sync.SyncRelaySessionManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Immutable
sealed interface ActiveOverlaySheet {
    data object None : ActiveOverlaySheet
    data object Search : ActiveOverlaySheet
    data object Spaces : ActiveOverlaySheet
    data object TabSwitcher : ActiveOverlaySheet
    data object PageMenu : ActiveOverlaySheet
    data class Reader(val document: ReadableDocument) : ActiveOverlaySheet
    data object PinchSummary : ActiveOverlaySheet
    data class LinkPeek(val rawUrl: String) : ActiveOverlaySheet
}

@Immutable
data class PendingPairingRequest(
    val rawUri: String,
    val preview: PairingConfirmationPreview? = null,
    val errorMessage: String? = null,
)

private data class TabPersistenceProjection(
    val url: String,
    val domain: String,
    val title: String,
    val hasUnsentFormDraft: Boolean,
    val blockedTrackersCount: Int,
)

/**
 * Configuration-surviving ViewModel managing Focal's live [EngineSession] pool,
 * isolated in-memory Private Aperture (`◎`) tabs, debounced Room tab persistence,
 * automatic disposal of 24h auto-archived tabs, multi-tab media routing, and
 * explicit SAS-verified E2EE pairing & Spaces/Presence continuity.
 */
@OptIn(FlowPreview::class)
class BrowserSessionViewModel(
    private val browserEngine: BrowserEngine,
    private val database: FocalDatabase,
    private val syncManager: SyncRelaySessionManager,
) : ViewModel() {

    private val sessionLock = Any()
    private val sessionMap = LinkedHashMap<String, EngineSession>()
    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val persistedNonPrivateTabIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val privateTabLock = Any()
    private val privateTabMap = LinkedHashMap<String, TabMetadataEntity>()

    private val _liveSessions = MutableStateFlow<Map<String, EngineSession>>(emptyMap())
    val liveSessions: StateFlow<Map<String, EngineSession>> = _liveSessions.asStateFlow()

    private val _privateTabs = MutableStateFlow<ImmutableList<TabMetadataEntity>>(persistentListOf())
    val privateTabs: StateFlow<ImmutableList<TabMetadataEntity>> = _privateTabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow<String?>(null)
    val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    private val _isPrivateAperture = MutableStateFlow(false)
    val isPrivateAperture: StateFlow<Boolean> = _isPrivateAperture.asStateFlow()

    private val _activeSpaceId = MutableStateFlow("personal")
    val activeSpaceId: StateFlow<String> = _activeSpaceId.asStateFlow()

    // Open Search-First Sheet immediately on cold launch so thumb-typing begins in 0ms
    private val _overlaySheet = MutableStateFlow<ActiveOverlaySheet>(ActiveOverlaySheet.Search)
    val overlaySheet: StateFlow<ActiveOverlaySheet> = _overlaySheet.asStateFlow()

    private val _pendingNewTabInSearch = MutableStateFlow(false)
    val pendingNewTabInSearch: StateFlow<Boolean> = _pendingNewTabInSearch.asStateFlow()

    private val _pendingPairingRequest = MutableStateFlow<PendingPairingRequest?>(null)
    val pendingPairingRequest: StateFlow<PendingPairingRequest?> = _pendingPairingRequest.asStateFlow()

    @Volatile
    private var suppressSnapshotEchoUntilMs: Long = 0L

    @Volatile
    private var lastPublishedSpacesModels: List<SpaceSyncModel>? = null

    init {
        bindMultiTabMediaHandlers()
        observeArchivedTabsForLiveDisposal()
        observeIncomingSpacesSnapshots()
        observeOutboundPresenceTabs()
        observeOutboundSpacesSnapshots()
    }

    private fun bindMultiTabMediaHandlers() {
        FocalMediaBridge.onTogglePlayPauseForTab = { tabId, play ->
            getSession(tabId)?.setMediaPlaying(play)
        }
        FocalMediaBridge.onPlaybackRateForTab = { tabId, rate ->
            getSession(tabId)?.setPlaybackRate(rate)
        }
        FocalMediaBridge.onRequestPipForTab = { tabId ->
            getSession(tabId)?.requestElementFullscreenOrPip()
        }
    }

    /**
     * Observes `observeAllActiveTabs()` and automatically disposes any non-private live
     * [EngineSession] that was swept into the 24h Tidied Archive by `AutoArchiveTabsWorker` (`P1-12`).
     */
    private fun observeArchivedTabsForLiveDisposal() {
        viewModelScope.launch {
            database.tabMetadataDao().observeAllActiveTabs().collect { allActiveTabs ->
                val activeIds = allActiveTabs.asSequence().map { it.id }.toHashSet()
                val archivedLiveIds = persistedNonPrivateTabIds.filter { id ->
                    id !in activeIds && getSession(id)?.isPrivate == false
                }
                if (archivedLiveIds.isEmpty()) return@collect

                for (archivedId in archivedLiveIds) {
                    persistedNonPrivateTabIds.remove(archivedId)
                    sessionJobs.remove(archivedId)?.cancel()
                    removeSessionInternal(archivedId)?.dispose()
                    FocalMediaBridge.clearIfOwnedBy(archivedId)

                    if (_selectedTabId.value == archivedId) {
                        val nextId = allActiveTabs.firstOrNull {
                            it.spaceId == _activeSpaceId.value
                        }?.id ?: allActiveTabs.firstOrNull()?.id
                        _selectedTabId.value = nextId
                        FocalMediaBridge.onSelectedTabChanged(nextId)
                    }
                }
            }
        }
    }

    /**
     * Applies inbound E2EE `SpacesSnapshotEvent` payloads into local Room tables (`P1-10`).
     */
    private fun observeIncomingSpacesSnapshots() {
        viewModelScope.launch(Dispatchers.IO) {
            syncManager.incomingSpacesSnapshots.collect { snapshot ->
                suppressSnapshotEchoUntilMs = System.currentTimeMillis() + 2_500L
                val spaceEntities = snapshot.spaces.mapIndexed { idx, space ->
                    val defaultHex = when (space.id.lowercase()) {
                        "personal" -> "#F4725E"
                        "work" -> "#94A3B8"
                        "reading" -> "#4ADE80"
                        else -> "#94A3B8"
                    }
                    SpaceEntity(
                        id = space.id,
                        name = space.label,
                        ambientHex = defaultHex,
                        sortOrder = idx,
                        updatedAtEpochMs = snapshot.updatedAtMs,
                    )
                }
                val bookmarkEntities = snapshot.spaces.flatMap { space ->
                    space.bookmarks.mapIndexed { bmIdx, bm ->
                        BookmarkEntity(
                            id = bm.id,
                            spaceId = space.id,
                            isFolder = false,
                            isPinnedSquircle = bm.pinned == true,
                            shortCode = bm.title.take(2).uppercase(),
                            title = bm.title,
                            url = bm.url,
                            sortOrder = bmIdx,
                            createdAtEpochMs = bm.addedAtMs,
                            updatedAtEpochMs = snapshot.updatedAtMs,
                        )
                    }
                }
                if (spaceEntities.isNotEmpty()) {
                    database.spaceDao().upsertAll(spaceEntities)
                }
                if (bookmarkEntities.isNotEmpty()) {
                    database.bookmarkDao().upsertAll(bookmarkEntities)
                }
            }
        }
    }

    /**
     * Broadcasts non-private active Android tabs to paired Desktop peers when connected (`P1-10`).
     * Private Aperture (`◎`) tabs are strictly excluded.
     */
    private fun observeOutboundPresenceTabs() {
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                syncManager.state.map { it.status }.distinctUntilChanged(),
                database.tabMetadataDao().observeAllActiveTabs(),
                _selectedTabId,
            ) { status, activeTabs, currentSelectedId ->
                Triple(status, activeTabs, currentSelectedId)
            }
                .debounce(600L)
                .collect { (status, activeTabs, currentSelectedId) ->
                    if (status != SyncConnectionStatus.CONNECTED) return@collect
                    val presenceItems = activeTabs.take(24).map { tab ->
                        PresenceTabItem(
                            id = tab.id,
                            url = tab.url,
                            title = tab.title.ifBlank { tab.domain.ifBlank { tab.url } },
                            spaceId = tab.spaceId,
                            active = tab.id == currentSelectedId,
                            lastActiveMs = tab.lastActiveEpochMs,
                        )
                    }
                    syncManager.publishPresenceTabs(presenceItems)
                }
        }
    }

    /**
     * Broadcasts updated Spaces & Bookmarks snapshots over E2EE relay when connected (`P1-10`).
     */
    private fun observeOutboundSpacesSnapshots() {
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                syncManager.state.map { it.status }.distinctUntilChanged(),
                database.spaceDao().observeSpaces(),
                database.bookmarkDao().observeAllBookmarks(),
            ) { status, spaces, bookmarks ->
                Triple(status, spaces, bookmarks)
            }
                .debounce(800L)
                .collect { (status, spaces, bookmarks) ->
                    if (status != SyncConnectionStatus.CONNECTED || spaces.isEmpty()) return@collect
                    if (System.currentTimeMillis() <= suppressSnapshotEchoUntilMs) return@collect

                    val bySpace = bookmarks.filter { !it.isFolder && !it.url.isNullOrBlank() }
                        .groupBy { it.spaceId }
                    val models = spaces.map { sp ->
                        val items = bySpace[sp.id].orEmpty().map { bm ->
                            SpaceBookmarkItem(
                                id = bm.id,
                                title = bm.title,
                                url = bm.url.orEmpty(),
                                pinned = bm.isPinnedSquircle,
                                addedAtMs = bm.createdAtEpochMs,
                            )
                        }
                        SpaceSyncModel(
                            id = sp.id,
                            label = sp.name,
                            bookmarks = items,
                        )
                    }
                    if (models != lastPublishedSpacesModels) {
                        if (syncManager.publishSpacesSnapshot(models)) {
                            lastPublishedSpacesModels = models
                        }
                    }
                }
        }
    }

    fun getSession(tabId: String?): EngineSession? {
        if (tabId == null) return null
        return synchronized(sessionLock) { sessionMap[tabId] }
    }

    fun setOverlaySheet(sheet: ActiveOverlaySheet) {
        if (sheet != ActiveOverlaySheet.Search) {
            _pendingNewTabInSearch.value = false
        }
        _overlaySheet.value = sheet
    }

    fun openSearchSheet(forNewTab: Boolean = false, privateMode: Boolean? = null) {
        _pendingNewTabInSearch.value = forNewTab
        if (privateMode != null) {
            _isPrivateAperture.value = privateMode
        }
        _overlaySheet.value = ActiveOverlaySheet.Search
    }

    fun setPrivateAperture(enabled: Boolean) {
        _isPrivateAperture.value = enabled
    }

    fun selectSpace(spaceId: String) {
        _activeSpaceId.value = spaceId
    }

    /**
     * Stages an incoming `focal-pair://` URI for explicit SAS & relay host user confirmation (`P0-6`).
     * Never auto-joins on deep-link Intent receipt or URL bar paste.
     */
    fun stageIncomingPairingUri(rawUri: String) {
        val trimmed = rawUri.trim()
        val result = runCatching { FocalSyncCrypto.previewPairingUri(trimmed) }
        _pendingPairingRequest.value = result.fold(
            onSuccess = { preview ->
                PendingPairingRequest(rawUri = trimmed, preview = preview)
            },
            onFailure = { err ->
                PendingPairingRequest(
                    rawUri = trimmed,
                    preview = null,
                    errorMessage = err.message ?: "Invalid or insecure pairing URI",
                )
            },
        )
    }

    fun confirmPendingPairing() {
        val preview = _pendingPairingRequest.value?.preview ?: run {
            _pendingPairingRequest.value = null
            return
        }
        _pendingPairingRequest.value = null
        runCatching {
            syncManager.joinFromPairingUri(preview.normalizedUri, connectRelay = true)
        }
        _overlaySheet.value = ActiveOverlaySheet.Spaces
    }

    fun dismissPendingPairing() {
        _pendingPairingRequest.value = null
    }

    fun startBootstrapPairing() {
        syncManager.bootstrapPairingSession(connectRelay = true)
    }

    /**
     * Opens or navigates [url] in the active or new tab:
     * - Intercepts `focal-pair://` URIs into SAS confirmation (`P0-6`).
     * - Reuses the current private `selectedTabId` during normal Private Aperture navigation (`P0-3`).
     * - Keeps private tab metadata 100% in RAM (`_privateTabs`) without touching SQLite (`P0-3`).
     */
    fun openUrlInTab(
        url: String,
        privateMode: Boolean = _isPrivateAperture.value,
        openInBackground: Boolean = false,
        forceNewTab: Boolean = _pendingNewTabInSearch.value,
    ) {
        _pendingNewTabInSearch.value = false
        val trimmedUrl = url.trim()
        if (trimmedUrl.startsWith("focal-pair://", ignoreCase = true)) {
            stageIncomingPairingUri(trimmedUrl)
            return
        }

        val now = System.currentTimeMillis()
        val currentSpace = _activeSpaceId.value
        val currentSelId = _selectedTabId.value
        val currentSelSession = getSession(currentSelId)

        val reuseCurrentTab = !openInBackground &&
            !forceNewTab &&
            currentSelId != null &&
            currentSelSession != null &&
            currentSelSession.isPrivate == privateMode

        val tabId = if (reuseCurrentTab) {
            currentSelId!!
        } else {
            UUID.randomUUID().toString()
        }

        val session = getOrCreateRegisteredSession(
            tabId = tabId,
            isPrivate = privateMode,
            spaceId = currentSpace,
            createdAtEpochMs = now,
        )
        session.loadUrl(trimmedUrl)

        if (!openInBackground) {
            _selectedTabId.value = tabId
            _isPrivateAperture.value = privateMode
            _overlaySheet.value = ActiveOverlaySheet.None
            FocalMediaBridge.onSelectedTabChanged(tabId)
        }

        val domain = extractCleanDomain(trimmedUrl)
        if (privateMode) {
            upsertPrivateTabInMemory(
                tabId = tabId,
                spaceId = currentSpace,
                url = trimmedUrl,
                domain = domain,
                title = domain.ifBlank { trimmedUrl },
                nowEpochMs = now,
            )
        } else if (!reuseCurrentTab) {
            persistedNonPrivateTabIds.add(tabId)
            viewModelScope.launch(Dispatchers.IO) {
                database.tabMetadataDao().upsertTab(
                    TabMetadataEntity(
                        id = tabId,
                        spaceId = currentSpace,
                        url = trimmedUrl,
                        domain = domain,
                        title = domain.ifBlank { trimmedUrl },
                        lastActiveEpochMs = now,
                        createdAtEpochMs = now,
                    ),
                )
            }
        }
    }

    fun selectTab(tabId: String, activeSpaceTabs: List<TabMetadataEntity>) {
        val now = System.currentTimeMillis()
        val privateMeta = synchronized(privateTabLock) { privateTabMap[tabId] }
        if (privateMeta != null) {
            val session = getOrCreateRegisteredSession(
                tabId = tabId,
                isPrivate = true,
                spaceId = privateMeta.spaceId,
                createdAtEpochMs = privateMeta.createdAtEpochMs,
            )
            if (session.state.value.url.isBlank() || session.state.value.url == "about:blank") {
                session.loadUrl(privateMeta.url)
            }
            upsertPrivateTabInMemory(
                tabId = privateMeta.id,
                spaceId = privateMeta.spaceId,
                url = privateMeta.url,
                domain = privateMeta.domain,
                title = privateMeta.title,
                nowEpochMs = now,
                hasUnsentFormDraft = privateMeta.hasUnsentFormDraft,
                blockedTrackersCount = privateMeta.blockedTrackersCount,
            )
            _selectedTabId.value = tabId
            _isPrivateAperture.value = true
            _overlaySheet.value = ActiveOverlaySheet.None
            FocalMediaBridge.onSelectedTabChanged(tabId)
            return
        }

        val meta = activeSpaceTabs.firstOrNull { it.id == tabId }
        if (meta != null) {
            persistedNonPrivateTabIds.add(meta.id)
            val alreadyLive = getSession(meta.id) != null
            val session = getOrCreateRegisteredSession(
                tabId = meta.id,
                isPrivate = false,
                spaceId = meta.spaceId,
                createdAtEpochMs = meta.createdAtEpochMs,
            )
            if (!alreadyLive) {
                session.loadUrl(meta.url)
            }
            _selectedTabId.value = meta.id
            _isPrivateAperture.value = false
            _overlaySheet.value = ActiveOverlaySheet.None
            FocalMediaBridge.onSelectedTabChanged(meta.id)
            viewModelScope.launch(Dispatchers.IO) {
                database.tabMetadataDao().touchTab(meta.id, now)
            }
        }
    }

    fun stepToAdjacentTab(delta: Int, activeSpaceTabs: List<TabMetadataEntity>) {
        if (_isPrivateAperture.value) {
            val pTabs = _privateTabs.value
            if (pTabs.size <= 1) return
            val currentIdx = pTabs.indexOfFirst { it.id == _selectedTabId.value }.coerceAtLeast(0)
            val nextMeta = pTabs[(currentIdx + delta).mod(pTabs.size)]
            selectTab(nextMeta.id, activeSpaceTabs)
        } else {
            if (activeSpaceTabs.size <= 1) return
            val currentIdx = activeSpaceTabs.indexOfFirst { it.id == _selectedTabId.value }.coerceAtLeast(0)
            val nextMeta = activeSpaceTabs[(currentIdx + delta).mod(activeSpaceTabs.size)]
            selectTab(nextMeta.id, activeSpaceTabs)
        }
    }

    fun closeTab(tabId: String, activeSpaceTabs: List<TabMetadataEntity>) {
        sessionJobs.remove(tabId)?.cancel()
        val disposedSession = removeSessionInternal(tabId)
        disposedSession?.dispose()
        FocalMediaBridge.clearIfOwnedBy(tabId)

        val wasPrivate = removePrivateTabInMemory(tabId) || disposedSession?.isPrivate == true
        if (wasPrivate) {
            val remainingPrivate = _privateTabs.value
            if (remainingPrivate.isEmpty()) {
                browserEngine.clearEphemeralData()
            }
            if (_selectedTabId.value == tabId) {
                val nextId = remainingPrivate.firstOrNull()?.id
                _selectedTabId.value = nextId
                FocalMediaBridge.onSelectedTabChanged(nextId)
            }
        } else {
            persistedNonPrivateTabIds.remove(tabId)
            viewModelScope.launch(Dispatchers.IO) {
                database.tabMetadataDao().deleteTab(tabId)
            }
            if (_selectedTabId.value == tabId) {
                val nextId = activeSpaceTabs.firstOrNull { it.id != tabId }?.id
                _selectedTabId.value = nextId
                FocalMediaBridge.onSelectedTabChanged(nextId)
            }
        }
    }

    fun closeAllPrivateTabs(fallbackRegularTabs: List<TabMetadataEntity>) {
        val privateSessionIds = synchronized(sessionLock) {
            sessionMap.filterValues { it.isPrivate }.keys.toList()
        }
        val privateMetaIds = synchronized(privateTabLock) {
            privateTabMap.keys.toList()
        }
        val allPrivateIds = (privateSessionIds + privateMetaIds).toSet()

        for (id in allPrivateIds) {
            sessionJobs.remove(id)?.cancel()
            removeSessionInternal(id)?.dispose()
            FocalMediaBridge.clearIfOwnedBy(id)
        }
        synchronized(privateTabLock) {
            privateTabMap.clear()
            _privateTabs.value = persistentListOf()
        }
        browserEngine.clearEphemeralData()

        _isPrivateAperture.value = false
        val fallbackId = fallbackRegularTabs.firstOrNull()?.id
        _selectedTabId.value = fallbackId
        FocalMediaBridge.onSelectedTabChanged(fallbackId)
        _overlaySheet.value = ActiveOverlaySheet.None
    }

    private fun getOrCreateRegisteredSession(
        tabId: String,
        isPrivate: Boolean,
        spaceId: String,
        createdAtEpochMs: Long,
    ): EngineSession {
        synchronized(sessionLock) {
            sessionMap[tabId]?.let { return it }
            val created = browserEngine.createSession(isPrivate = isPrivate, spaceId = spaceId)
            sessionMap[tabId] = created
            _liveSessions.value = LinkedHashMap(sessionMap)
            sessionJobs[tabId] = observeRegisteredSession(
                fixedTabId = tabId,
                session = created,
                fixedSpaceId = spaceId,
                fixedCreatedAtMs = createdAtEpochMs,
            )
            return created
        }
    }

    private fun observeRegisteredSession(
        fixedTabId: String,
        session: EngineSession,
        fixedSpaceId: String,
        fixedCreatedAtMs: Long,
    ): Job {
        return viewModelScope.launch {
            // 1. Track media across ALL live sessions so background playing tabs stay controllable (P1-11)
            launch {
                session.state
                    .map { it.activeMedia }
                    .distinctUntilChanged()
                    .collect { mediaInfo ->
                        FocalMediaBridge.updateActiveMedia(fixedTabId, mediaInfo)
                    }
            }

            if (session.isPrivate) {
                // 2a. Private Aperture metadata stays strictly in RAM (P0-3)
                launch {
                    combine(
                        session.state,
                        session.telemetry.map { it.blockedTrackersInSession }.distinctUntilChanged(),
                    ) { st, blocked ->
                        TabPersistenceProjection(
                            url = st.url,
                            domain = st.domain,
                            title = st.title,
                            hasUnsentFormDraft = st.hasUnsentFormDraft,
                            blockedTrackersCount = blocked.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                    }
                        .filter { it.url.isNotBlank() && it.url != "about:blank" }
                        .distinctUntilChanged()
                        .debounce(200L)
                        .collect { proj ->
                            upsertPrivateTabInMemory(
                                tabId = fixedTabId,
                                spaceId = fixedSpaceId,
                                url = proj.url,
                                domain = proj.domain,
                                title = proj.title.ifBlank { proj.domain.ifBlank { proj.url } },
                                nowEpochMs = System.currentTimeMillis(),
                                hasUnsentFormDraft = proj.hasUnsentFormDraft,
                                blockedTrackersCount = proj.blockedTrackersCount,
                            )
                        }
                }
            } else {
                // 2b. Immediate dirty-form guard sync so AutoArchiveTabsWorker never races (P1-2, P1-8)
                launch(Dispatchers.IO) {
                    session.state
                        .map { it.hasUnsentFormDraft }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect { dirty ->
                            if (persistedNonPrivateTabIds.contains(fixedTabId)) {
                                database.tabMetadataDao().updateUnsentFormDraft(fixedTabId, dirty)
                            }
                        }
                }

                // 2c. Debounced Room upsert bound to fixedTabId & preserving fixedCreatedAtMs (P1-1, P1-2)
                launch(Dispatchers.IO) {
                    combine(
                        session.state,
                        session.telemetry.map { it.blockedTrackersInSession }.distinctUntilChanged(),
                    ) { st, blocked ->
                        TabPersistenceProjection(
                            url = st.url,
                            domain = st.domain,
                            title = st.title,
                            hasUnsentFormDraft = st.hasUnsentFormDraft,
                            blockedTrackersCount = blocked.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                    }
                        .filter { it.url.isNotBlank() && it.url != "about:blank" }
                        .distinctUntilChanged()
                        .debounce(500L)
                        .collect { proj ->
                            persistedNonPrivateTabIds.add(fixedTabId)
                            database.tabMetadataDao().upsertTab(
                                TabMetadataEntity(
                                    id = fixedTabId,
                                    spaceId = fixedSpaceId,
                                    url = proj.url,
                                    domain = proj.domain,
                                    title = proj.title.ifBlank { proj.domain.ifBlank { proj.url } },
                                    lastActiveEpochMs = System.currentTimeMillis(),
                                    createdAtEpochMs = fixedCreatedAtMs,
                                    hasUnsentFormDraft = proj.hasUnsentFormDraft,
                                    blockedTrackersCount = proj.blockedTrackersCount,
                                ),
                            )
                        }
                }
            }
        }
    }

    private fun removeSessionInternal(tabId: String): EngineSession? {
        return synchronized(sessionLock) {
            val removed = sessionMap.remove(tabId)
            if (removed != null) {
                _liveSessions.value = LinkedHashMap(sessionMap)
            }
            removed
        }
    }

    private fun upsertPrivateTabInMemory(
        tabId: String,
        spaceId: String,
        url: String,
        domain: String,
        title: String,
        nowEpochMs: Long,
        hasUnsentFormDraft: Boolean = false,
        blockedTrackersCount: Int = 0,
    ) {
        synchronized(privateTabLock) {
            val existing = privateTabMap[tabId]
            privateTabMap[tabId] = TabMetadataEntity(
                id = tabId,
                spaceId = spaceId,
                url = url,
                domain = domain,
                title = title,
                lastActiveEpochMs = nowEpochMs,
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                hasUnsentFormDraft = hasUnsentFormDraft,
                blockedTrackersCount = blockedTrackersCount,
            )
            _privateTabs.value = privateTabMap.values
                .sortedByDescending { it.lastActiveEpochMs }
                .toImmutableList()
        }
    }

    private fun removePrivateTabInMemory(tabId: String): Boolean {
        return synchronized(privateTabLock) {
            val removed = privateTabMap.remove(tabId) != null
            if (removed) {
                _privateTabs.value = privateTabMap.values
                    .sortedByDescending { it.lastActiveEpochMs }
                    .toImmutableList()
            }
            removed
        }
    }

    private fun extractCleanDomain(url: String): String {
        return runCatching {
            Uri.parse(url).host?.removePrefix("www.").orEmpty()
        }.getOrDefault("")
    }

    override fun onCleared() {
        for ((_, job) in sessionJobs) {
            job.cancel()
        }
        sessionJobs.clear()

        val sessionsToDispose = synchronized(sessionLock) {
            val all = sessionMap.values.toList()
            sessionMap.clear()
            _liveSessions.value = emptyMap()
            all
        }
        for (session in sessionsToDispose) {
            session.dispose()
        }
        FocalMediaBridge.clearAll()
        super.onCleared()
    }

    companion object {
        fun factory(app: FocalApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BrowserSessionViewModel(
                        browserEngine = app.browserEngine,
                        database = app.database,
                        syncManager = app.syncManager,
                    ) as T
                }
            }
    }
}
