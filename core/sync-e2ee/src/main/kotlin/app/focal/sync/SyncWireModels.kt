package app.focal.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Shared E2EE JSON payload models matching `desktop-extension/src/crypto.ts` byte-for-byte.
 */
@Serializable
data class EncryptedEnvelope(
    val seq: Long,
    val senderId: String,
    val timestampMs: Long,
    val event: SyncEvent,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface SyncEvent {

    @Serializable
    @SerialName("tab_handoff")
    data class TabHandoffEvent(
        val url: String,
        val title: String,
        val strippedParams: List<String>? = null,
        val spaceId: String? = null,
        val sourceDeviceName: String? = null,
    ) : SyncEvent

    @Serializable
    @SerialName("presence_tabs")
    data class PresenceTabsEvent(
        val deviceName: String,
        val platform: String, // "desktop" | "android"
        val tabs: List<PresenceTabItem>,
    ) : SyncEvent

    @Serializable
    @SerialName("spaces_snapshot")
    data class SpacesSnapshotEvent(
        val spaces: List<SpaceSyncModel>,
        val updatedAtMs: Long,
    ) : SyncEvent
}

@Serializable
data class PresenceTabItem(
    val id: String,
    val url: String,
    val title: String,
    val spaceId: String? = null,
    val active: Boolean? = null,
    val lastActiveMs: Long? = null,
)

@Serializable
data class SpaceBookmarkItem(
    val id: String,
    val title: String,
    val url: String,
    val pinned: Boolean? = null,
    val addedAtMs: Long,
)

@Serializable
data class SpaceSyncModel(
    val id: String, // "personal" | "work" | "reading"
    val label: String,
    val bookmarks: List<SpaceBookmarkItem>,
)
