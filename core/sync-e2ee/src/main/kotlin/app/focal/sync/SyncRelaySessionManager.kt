package app.focal.sync

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SyncConnectionStatus {
    UNPAIRED,
    PAIRING_READY,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

data class SyncSessionUiState(
    val status: SyncConnectionStatus = SyncConnectionStatus.UNPAIRED,
    val pairingUri: String? = null,
    val sasVerificationCode: String? = null,
    val roomIdShort: String? = null,
    val peerDeviceName: String? = null,
    val incomingHandoff: SyncEvent.TabHandoffEvent? = null,
    val desktopPresenceTabs: List<PresenceTabItem> = emptyList(),
    val latestSpacesSnapshot: SyncEvent.SpacesSnapshotEvent? = null,
)

@Serializable
data class PersistedSyncSessionState(
    val deviceId: String,
    val secretBase64Url: String? = null,
    val relayBaseUrl: String? = null,
    val outboundSeq: Long = 1L,
    val replaySnapshot: Map<String, Long> = emptyMap(),
)

interface SyncStateStore {
    fun load(): PersistedSyncSessionState?
    fun save(state: PersistedSyncSessionState)
    fun clear()
}

class InMemorySyncStateStore(
    initialState: PersistedSyncSessionState? = null,
) : SyncStateStore {
    @Volatile
    private var snapshot: PersistedSyncSessionState? = initialState

    override fun load(): PersistedSyncSessionState? = snapshot

    override fun save(state: PersistedSyncSessionState) {
        snapshot = state
    }

    override fun clear() {
        snapshot = null
    }
}

/**
 * Hardware-backed `AndroidKeyStore` (`AES-256-GCM`) encrypted `MODE_PRIVATE` preference store
 * persisting pairing secret, `deviceId`, `outboundSeq`, and `ReplayTracker` state across restarts.
 */
class AndroidKeyStoreSyncStateStore(
    context: Context,
) : SyncStateStore {

    companion object {
        private const val PREFS_NAME = "focal_sync_e2ee_secure_prefs"
        private const val KEY_PAYLOAD = "encrypted_session_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "focal_sync_session_aes256_v1"
    }

    private val appContext = context.applicationContext
    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    override fun load(): PersistedSyncSessionState? {
        val encoded = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val packed = FocalSyncCrypto.base64UrlToBytes(encoded)
            if (packed.size < 1 + 12 + 16) return null
            val ivLen = packed[0].toInt() and 0xFF
            if (ivLen !in 12..16 || packed.size <= 1 + ivLen + 16) return null
            val iv = packed.copyOfRange(1, 1 + ivLen)
            val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext)
            FocalSyncCrypto.wireJson.decodeFromString<PersistedSyncSessionState>(
                String(plaintext, StandardCharsets.UTF_8),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun save(state: PersistedSyncSessionState) {
        try {
            val plaintext = FocalSyncCrypto.wireJson
                .encodeToString(state)
                .toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            val packed = ByteArray(1 + iv.size + ciphertext.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)

            prefs.edit()
                .putString(KEY_PAYLOAD, FocalSyncCrypto.bytesToBase64Url(packed))
                .apply()
        } catch (_: Exception) {
            // Preserve in-memory operation if keystore is temporarily unavailable
        }
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}

/**
 * Coordinates Focal's Zero-Account E2EE WebSocket relay session (`Focal Bridge`).
 * Routes opaque `base64url(12B IV || ciphertext || 16B tag)` frames over `/v1/rooms/{roomId}`,
 * persists cryptographic session + anti-replay state across app restarts, and reconnects with
 * bounded exponential backoff.
 */
class SyncRelaySessionManager(
    context: Context? = null,
    private val defaultRelayBaseUrl: String = "wss://relay.focal.app",
    deviceId: String? = null,
    private val stateStore: SyncStateStore = if (context != null) {
        AndroidKeyStoreSyncStateStore(context)
    } else {
        InMemorySyncStateStore()
    },
    private val autoConnectRestoredSession: Boolean = context != null,
    private val allowInsecureLocalhost: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private companion object {
        const val PERSIST_DEBOUNCE_MS = 250L
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val usesAsyncPersistence: Boolean = stateStore !is InMemorySyncStateStore
    private val stateLock = Any()
    private val persistMutex = Mutex()
    private val replayTracker = ReplayTracker()
    private val outboundSeq = AtomicLong(1L)
    private val connectionGeneration = AtomicLong(0L)
    private val reconnectAttempt = AtomicInteger(0)

    private val explicitDeviceId: String? = deviceId?.takeIf { it.isNotBlank() }

    @Volatile
    private var _deviceId: String = explicitDeviceId ?: "android-${UUID.randomUUID().toString().take(8)}"

    val deviceId: String
        get() = _deviceId

    @Volatile
    private var stateMutatedByUser: Boolean = false

    @Volatile
    private var activeSecretBase64Url: String? = null

    @Volatile
    private var activeRelayBaseUrl: String = defaultRelayBaseUrl

    @Volatile
    private var activeKeys: DerivedFocalKeys? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var manualDisconnect: Boolean = false

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var debouncedPersistJob: Job? = null

    private val _state = MutableStateFlow(SyncSessionUiState())
    val state: StateFlow<SyncSessionUiState> = _state.asStateFlow()

    private val _incomingSpacesSnapshots = MutableSharedFlow<SyncEvent.SpacesSnapshotEvent>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    val incomingSpacesSnapshots: SharedFlow<SyncEvent.SpacesSnapshotEvent> =
        _incomingSpacesSnapshots.asSharedFlow()

    private val initJob: Job

    init {
        if (!usesAsyncPersistence) {
            val restored = stateStore.load()
            val restoredDeviceId = restored?.deviceId?.takeIf { it.isNotBlank() }
            if (explicitDeviceId == null && restoredDeviceId != null) {
                _deviceId = restoredDeviceId
            }
            if (restored != null) {
                val pendingAutoConnect = applyRestoredStateLocked(restored)
                if (pendingAutoConnect != null) {
                    connectWebSocket(pendingAutoConnect.first, pendingAutoConnect.second)
                }
            } else {
                persistCurrentState(immediate = true)
            }
            initJob = CompletableDeferred(Unit)
        } else {
            // Defer AndroidKeyStore / SharedPreferences hydration and first-launch TEE key
            // generation strictly off the main thread during Application.onCreate().
            initJob = scope.launch(Dispatchers.IO) {
                val restored = stateStore.load()
                var pendingAutoConnect: Pair<String, DerivedFocalKeys>? = null
                var initialSnapshotToSave: PersistedSyncSessionState? = null
                synchronized(stateLock) {
                    if (stateMutatedByUser) return@launch
                    val restoredDeviceId = restored?.deviceId?.takeIf { it.isNotBlank() }
                    if (explicitDeviceId == null && restoredDeviceId != null) {
                        _deviceId = restoredDeviceId
                    }
                    if (restored != null) {
                        pendingAutoConnect = applyRestoredStateLocked(restored)
                    } else {
                        initialSnapshotToSave = buildPersistedSnapshotLocked()
                    }
                }
                initialSnapshotToSave?.let { snapshot ->
                    persistMutex.withLock {
                        if (!stateMutatedByUser) {
                            stateStore.save(snapshot)
                        }
                    }
                }
                pendingAutoConnect?.let { (relayUrl, keys) ->
                    connectWebSocket(relayUrl, keys)
                }
            }
        }
    }

    /** Suspends until asynchronous KeyStore/disk state hydration completes. */
    suspend fun awaitInitialized() {
        initJob.join()
    }

    val currentOutboundSeq: Long
        get() = outboundSeq.get()

    /**
     * Generates a fresh 32-byte secret + `focal-pair://v1?secret=...` QR payload and SAS words.
     */
    fun bootstrapPairingSession(connectRelay: Boolean = false): DerivedFocalKeys {
        val secret = FocalSyncCrypto.generatePairingSecret()
        val secretB64 = FocalSyncCrypto.bytesToBase64Url(secret)
        val uri = FocalSyncCrypto.formatPairingUri(secret)
        val keys = FocalSyncCrypto.deriveFocalKeys(secret)
        synchronized(stateLock) {
            stateMutatedByUser = true
            activeSecretBase64Url = secretB64
            activeRelayBaseUrl = defaultRelayBaseUrl
            activeKeys = keys
            replayTracker.clear()
            outboundSeq.set(1L)
        }
        persistCurrentState(immediate = true)

        _state.value = SyncSessionUiState(
            status = SyncConnectionStatus.PAIRING_READY,
            pairingUri = uri,
            sasVerificationCode = keys.sas,
            roomIdShort = keys.roomId.take(12),
        )

        if (connectRelay) {
            connectWebSocket(defaultRelayBaseUrl, keys)
        }
        return keys
    }

    /**
     * Joins an existing room after explicit user SAS confirmation (`P0-6`).
     */
    fun joinFromPairingUri(pairingUri: String, connectRelay: Boolean = true): DerivedFocalKeys {
        val parsed = FocalSyncCrypto.parsePairingUri(
            uri = pairingUri,
            allowInsecureLocalhost = allowInsecureLocalhost,
        )
        val keys = FocalSyncCrypto.deriveFocalKeys(parsed.secret)
        val effectiveRelay = parsed.relayUrl ?: defaultRelayBaseUrl
        synchronized(stateLock) {
            stateMutatedByUser = true
            activeSecretBase64Url = parsed.secretBase64Url
            activeRelayBaseUrl = effectiveRelay
            activeKeys = keys
            replayTracker.clear()
            outboundSeq.set(1L)
        }
        persistCurrentState(immediate = true)

        _state.update {
            it.copy(
                status = SyncConnectionStatus.PAIRING_READY,
                pairingUri = FocalSyncCrypto.formatPairingUri(parsed.secret, parsed.relayUrl),
                sasVerificationCode = keys.sas,
                roomIdShort = keys.roomId.take(12),
            )
        }

        if (connectRelay) {
            connectWebSocket(effectiveRelay, keys)
        }
        return keys
    }

    fun sendEvent(event: SyncEvent): Boolean {
        val keys = activeKeys ?: return false
        val ws = webSocket ?: return false
        val nextSeq = outboundSeq.getAndIncrement()
        persistCurrentState(immediate = false)
        val envelope = EncryptedEnvelope(
            seq = nextSeq,
            senderId = deviceId,
            timestampMs = System.currentTimeMillis(),
            event = event,
        )
        val wireFrame = FocalSyncCrypto.encryptEnvelope(
            envelope = envelope,
            symKey = keys.symKey,
            roomId = keys.roomId,
        )
        return ws.send(wireFrame)
    }

    fun publishPresenceTabs(
        tabs: List<PresenceTabItem>,
        deviceName: String = defaultAndroidDeviceName(),
    ): Boolean {
        return sendEvent(
            SyncEvent.PresenceTabsEvent(
                deviceName = deviceName,
                platform = "android",
                tabs = tabs,
            ),
        )
    }

    fun publishSpacesSnapshot(
        spaces: List<SpaceSyncModel>,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        return sendEvent(
            SyncEvent.SpacesSnapshotEvent(
                spaces = spaces,
                updatedAtMs = updatedAtMs,
            ),
        )
    }

    fun dismissIncomingHandoff() {
        _state.update { it.copy(incomingHandoff = null) }
    }

    fun handleIncomingEncryptedWireFrame(wireFrame: String) {
        val keys = activeKeys ?: return
        try {
            val envelope = FocalSyncCrypto.decryptEnvelope(
                wireFrame = wireFrame,
                symKey = keys.symKey,
                roomId = keys.roomId,
                replayTracker = replayTracker,
            )
            persistCurrentState(immediate = false)
            if (envelope.senderId == deviceId) return

            when (val ev = envelope.event) {
                is SyncEvent.TabHandoffEvent -> {
                    _state.update {
                        it.copy(
                            incomingHandoff = ev,
                            peerDeviceName = ev.sourceDeviceName ?: it.peerDeviceName,
                        )
                    }
                }
                is SyncEvent.PresenceTabsEvent -> {
                    if (ev.platform == "desktop") {
                        _state.update {
                            it.copy(
                                peerDeviceName = ev.deviceName,
                                desktopPresenceTabs = ev.tabs,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(peerDeviceName = ev.deviceName)
                        }
                    }
                }
                is SyncEvent.SpacesSnapshotEvent -> {
                    _state.update { it.copy(latestSpacesSnapshot = ev) }
                    _incomingSpacesSnapshots.tryEmit(ev)
                }
            }
        } catch (_: FocalCryptoException) {
            // Silently drop unauthenticated or replayed relay frames
        }
    }

    private fun applyRestoredStateLocked(
        restored: PersistedSyncSessionState,
    ): Pair<String, DerivedFocalKeys>? {
        outboundSeq.set(restored.outboundSeq.coerceAtLeast(1L))
        replayTracker.restore(restored.replaySnapshot)
        val savedSecretB64 = restored.secretBase64Url
        if (!savedSecretB64.isNullOrBlank()) {
            try {
                val secretBytes = FocalSyncCrypto.base64UrlToBytes(savedSecretB64)
                if (secretBytes.size == FocalSyncCrypto.SECRET_BYTE_LENGTH) {
                    val keys = FocalSyncCrypto.deriveFocalKeys(secretBytes)
                    val relayUrl = restored.relayBaseUrl?.takeIf { it.isNotBlank() } ?: defaultRelayBaseUrl
                    activeSecretBase64Url = savedSecretB64
                    activeRelayBaseUrl = relayUrl
                    activeKeys = keys
                    val customRelay = relayUrl.takeIf {
                        !it.trimEnd('/').equals(defaultRelayBaseUrl.trimEnd('/'), ignoreCase = true)
                    }
                    _state.value = SyncSessionUiState(
                        status = SyncConnectionStatus.PAIRING_READY,
                        pairingUri = FocalSyncCrypto.formatPairingUri(secretBytes, customRelay),
                        sasVerificationCode = keys.sas,
                        roomIdShort = keys.roomId.take(12),
                    )
                    if (autoConnectRestoredSession && !manualDisconnect) {
                        return relayUrl to keys
                    }
                }
            } catch (_: Exception) {
                // Ignore corrupted persisted secret and start unpaired
            }
        }
        return null
    }

    private fun connectWebSocket(relayBaseUrl: String, keys: DerivedFocalKeys) {
        manualDisconnect = false
        activeRelayBaseUrl = relayBaseUrl
        reconnectAttempt.set(0)
        reconnectJob?.cancel()
        openWebSocketInternal(relayBaseUrl, keys)
    }

    private fun openWebSocketInternal(relayBaseUrl: String, keys: DerivedFocalKeys) {
        val generation = connectionGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            webSocket?.cancel()
            _state.update { it.copy(status = SyncConnectionStatus.CONNECTING) }
            val wsBase = relayBaseUrl.trimEnd('/')
                .replaceFirst("^https://".toRegex(RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst("^http://".toRegex(RegexOption.IGNORE_CASE), "ws://")
            val request = Request.Builder()
                .url("$wsBase/v1/rooms/${keys.roomId}")
                .build()

            webSocket = okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (connectionGeneration.get() != generation) return
                        reconnectAttempt.set(0)
                        _state.update { it.copy(status = SyncConnectionStatus.CONNECTED) }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (connectionGeneration.get() != generation) return
                        handleIncomingEncryptedWireFrame(text.trim())
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (connectionGeneration.get() != generation) return
                        _state.update { it.copy(status = SyncConnectionStatus.DISCONNECTED) }
                        if (!manualDisconnect && code != 1000) {
                            scheduleExponentialBackoffReconnect(generation)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (connectionGeneration.get() != generation) return
                        _state.update { it.copy(status = SyncConnectionStatus.DISCONNECTED) }
                        if (!manualDisconnect) {
                            scheduleExponentialBackoffReconnect(generation)
                        }
                    }
                },
            )
        }
    }

    private fun scheduleExponentialBackoffReconnect(expectedGeneration: Long) {
        val attempt = reconnectAttempt.getAndIncrement().coerceAtMost(5)
        val baseDelayMs = (1_000L shl attempt).coerceAtMost(30_000L)
        val jitterMs = (System.nanoTime() and 0xFFL)
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(baseDelayMs + jitterMs)
            if (!manualDisconnect && connectionGeneration.get() == expectedGeneration) {
                val keys = activeKeys ?: return@launch
                openWebSocketInternal(activeRelayBaseUrl, keys)
            }
        }
    }

    fun disconnect() {
        val hadPendingPersist: Boolean
        synchronized(stateLock) {
            manualDisconnect = true
            hadPendingPersist = debouncedPersistJob?.isActive == true
        }
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client closed")
        webSocket = null
        if (hadPendingPersist && usesAsyncPersistence) {
            persistCurrentState(immediate = true)
        }
        if (activeKeys != null) {
            _state.update { it.copy(status = SyncConnectionStatus.DISCONNECTED) }
        }
    }

    fun unpair() {
        disconnect()
        synchronized(stateLock) {
            stateMutatedByUser = true
            activeKeys = null
            activeSecretBase64Url = null
            replayTracker.clear()
            outboundSeq.set(1L)
        }
        persistCurrentState(immediate = true)
        _state.value = SyncSessionUiState(status = SyncConnectionStatus.UNPAIRED)
    }

    private fun persistCurrentState(immediate: Boolean = false) {
        if (!usesAsyncPersistence) {
            stateStore.save(buildPersistedSnapshot())
            return
        }
        if (immediate) {
            synchronized(stateLock) {
                debouncedPersistJob?.cancel()
                debouncedPersistJob = null
            }
            scope.launch(Dispatchers.IO) {
                initJob.join()
                persistMutex.withLock {
                    stateStore.save(buildPersistedSnapshot())
                }
            }
        } else {
            synchronized(stateLock) {
                debouncedPersistJob?.cancel()
                debouncedPersistJob = scope.launch(Dispatchers.IO) {
                    delay(PERSIST_DEBOUNCE_MS)
                    initJob.join()
                    persistMutex.withLock {
                        stateStore.save(buildPersistedSnapshot())
                    }
                }
            }
        }
    }

    private fun buildPersistedSnapshot(): PersistedSyncSessionState = synchronized(stateLock) {
        buildPersistedSnapshotLocked()
    }

    private fun buildPersistedSnapshotLocked(): PersistedSyncSessionState {
        return PersistedSyncSessionState(
            deviceId = _deviceId,
            secretBase64Url = activeSecretBase64Url,
            relayBaseUrl = activeRelayBaseUrl.takeIf { activeSecretBase64Url != null },
            outboundSeq = outboundSeq.get(),
            replaySnapshot = replayTracker.snapshot(),
        )
    }

    private fun defaultAndroidDeviceName(): String {
        return try {
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "Focal Android"
        } catch (_: Throwable) {
            "Focal Android"
        }
    }
}
