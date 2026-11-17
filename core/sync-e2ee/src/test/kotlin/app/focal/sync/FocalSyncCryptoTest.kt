package app.focal.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FocalSyncCryptoTest {

    @Test
    fun `SAS_WORDS has exact 64 optical nature words`() {
        assertEquals(64, FocalSyncCrypto.SAS_WORDS.size)
        assertEquals("amber", FocalSyncCrypto.SAS_WORDS.first())
        assertEquals("obsidian", FocalSyncCrypto.SAS_WORDS.last())
        assertEquals("focal", FocalSyncCrypto.SAS_WORDS[39])
    }

    @Test
    fun `pairing URI roundtrip and HKDF key derivation produce valid 64-char hex roomId and SAS`() {
        val secret = ByteArray(32) { idx -> (idx * 7 + 3).toByte() }
        val uri = FocalSyncCrypto.formatPairingUri(secret)
        assertTrue(uri.startsWith("focal-pair://v1?secret="))
        assertTrue(!uri.contains("=" .repeat(2)))

        val parsed = FocalSyncCrypto.parsePairingUri(uri)
        assertArrayEquals(secret, parsed.secret)

        val keys1 = FocalSyncCrypto.deriveFocalKeys(secret)
        val keys2 = FocalSyncCrypto.deriveFocalKeys(parsed.secret)
        assertEquals(keys1, keys2)
        assertEquals(64, keys1.roomId.length)
        assertTrue(FocalSyncCrypto.isValidRoomId(keys1.roomId))
        assertEquals(32, keys1.symKey.size)
        assertEquals(3, keys1.sasWords.size)
        assertTrue(keys1.sasDigits in 0..99)
    }

    @Test
    fun `AES-256-GCM encrypt and decrypt roundtrip enforces roomId AAD and replay protection`() {
        val secret = FocalSyncCrypto.generatePairingSecret()
        val keys = FocalSyncCrypto.deriveFocalKeys(secret)
        val tracker = ReplayTracker()

        val envelope = EncryptedEnvelope(
            seq = 1L,
            senderId = "desktop-ext-1",
            timestampMs = 1_760_000_000_000L,
            event = SyncEvent.TabHandoffEvent(
                url = "https://developer.android.com/develop/ui/views/graphics/agsl",
                title = "AGSL: Android Graphics Shading Language",
                strippedParams = listOf("utm_source", "fbclid"),
                spaceId = "work",
                sourceDeviceName = "Linux Workstation",
            ),
        )

        val wireFrame = FocalSyncCrypto.encryptEnvelope(envelope, keys.symKey, keys.roomId)
        val decrypted = FocalSyncCrypto.decryptEnvelope(wireFrame, keys.symKey, keys.roomId, tracker)
        assertEquals(envelope, decrypted)

        // Duplicate sequence number must be rejected by ReplayTracker
        try {
            FocalSyncCrypto.decryptEnvelope(wireFrame, keys.symKey, keys.roomId, tracker)
            fail("Expected FocalReplayException on duplicate sequence number")
        } catch (_: FocalReplayException) {
            // Expected
        }

        // Mismatched roomId AAD must fail GCM authentication without mutating ReplayTracker
        val wrongRoomId = "0".repeat(64)
        val nextEnvelope = envelope.copy(seq = 2L)
        val wireFrame2 = FocalSyncCrypto.encryptEnvelope(nextEnvelope, keys.symKey, keys.roomId)
        try {
            FocalSyncCrypto.decryptEnvelope(wireFrame2, keys.symKey, wrongRoomId, tracker)
            fail("Expected FocalCryptoException on wrong AAD")
        } catch (_: FocalCryptoException) {
            assertEquals(1L, tracker.getHighestSeq("desktop-ext-1"))
        }
    }

    @Test
    fun `parsePairingUri enforces wss or https and only permits ws localhost when allowInsecureLocalhost is true`() {
        val secret = FocalSyncCrypto.generatePairingSecret()
        val insecureRemoteUri = FocalSyncCrypto.formatPairingUri(secret, "ws://evil.example.com")
        try {
            FocalSyncCrypto.parsePairingUri(insecureRemoteUri)
            fail("Expected FocalCryptoException for cleartext ws:// remote relay")
        } catch (_: FocalCryptoException) {
            // Expected
        }

        val insecureLocalUri = FocalSyncCrypto.formatPairingUri(secret, "ws://127.0.0.1:8787")
        try {
            FocalSyncCrypto.parsePairingUri(insecureLocalUri, allowInsecureLocalhost = false)
            fail("Expected FocalCryptoException when allowInsecureLocalhost is false")
        } catch (_: FocalCryptoException) {
            // Expected
        }

        val allowedLocal = FocalSyncCrypto.parsePairingUri(
            uri = insecureLocalUri,
            allowInsecureLocalhost = true,
        )
        assertEquals("ws://127.0.0.1:8787", allowedLocal.relayUrl)

        val tlsRemoteUri = FocalSyncCrypto.formatPairingUri(secret, "wss://sync.example.org")
        val preview = FocalSyncCrypto.previewPairingUri(tlsRemoteUri)
        assertEquals("sync.example.org", preview.relayHost)
        assertTrue(preview.isCustomRelay)
        assertTrue(preview.sas.contains(" · "))
    }

    @Test
    fun `SyncRelaySessionManager persists pairing keys, deviceId, replayTracker, and SpacesSnapshotEvent`() {
        val store = InMemorySyncStateStore()
        val manager1 = SyncRelaySessionManager(
            stateStore = store,
            autoConnectRestoredSession = false,
        )
        val keys = manager1.bootstrapPairingSession(connectRelay = false)
        val savedDeviceId = manager1.deviceId

        val snapshotEvent = SyncEvent.SpacesSnapshotEvent(
            spaces = listOf(
                SpaceSyncModel(
                    id = "work",
                    label = "Work",
                    bookmarks = listOf(
                        SpaceBookmarkItem(
                            id = "bm-1",
                            title = "AGSL Docs",
                            url = "https://developer.android.com",
                            pinned = true,
                            addedAtMs = 1_760_000_000_000L,
                        ),
                    ),
                ),
            ),
            updatedAtMs = 1_760_000_123_000L,
        )
        val incomingWire = FocalSyncCrypto.encryptEnvelope(
            envelope = EncryptedEnvelope(
                seq = 5L,
                senderId = "desktop-peer-1",
                timestampMs = 1_760_000_123_000L,
                event = snapshotEvent,
            ),
            symKey = keys.symKey,
            roomId = keys.roomId,
        )
        manager1.handleIncomingEncryptedWireFrame(incomingWire)
        assertEquals(snapshotEvent, manager1.state.value.latestSpacesSnapshot)

        // Simulate app restart using the same SyncStateStore
        val manager2 = SyncRelaySessionManager(
            stateStore = store,
            autoConnectRestoredSession = false,
        )
        assertEquals(savedDeviceId, manager2.deviceId)
        assertEquals(SyncConnectionStatus.PAIRING_READY, manager2.state.value.status)
        assertEquals(keys.sas, manager2.state.value.sasVerificationCode)

        // Replaying seq=5 after restart must still be rejected by restored ReplayTracker
        val staleHandoffWire = FocalSyncCrypto.encryptEnvelope(
            envelope = EncryptedEnvelope(
                seq = 5L,
                senderId = "desktop-peer-1",
                timestampMs = 1_760_000_200_000L,
                event = SyncEvent.TabHandoffEvent(
                    url = "https://replayed.example.com",
                    title = "Replayed",
                ),
            ),
            symKey = keys.symKey,
            roomId = keys.roomId,
        )
        manager2.handleIncomingEncryptedWireFrame(staleHandoffWire)
        assertEquals(null, manager2.state.value.incomingHandoff)
    }
}
