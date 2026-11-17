package app.focal.feature.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import app.focal.design.FocalTheme
import app.focal.design.shaders.SmokedGlassBox
import app.focal.engine.api.ActiveMediaSessionInfo
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class MediaMiniPlayerState(
    val sessionTabId: String,
    val media: ActiveMediaSessionInfo,
)

/**
 * Shared coordinator bridging WebView HTML5 `<video>` / `<audio>` playback across **all** live
 * tabs to both the floating Smoked Glass [MediaMiniPlayerDock] and Android's [FocalMediaSessionService].
 *
 * Switching away from a playing tab keeps its media session active in [activeMedia] and routes
 * Play/Pause and speed (`1.0×/1.25×/1.5×/2.0×`) commands directly to the owning background tab.
 */
object FocalMediaBridge {
    val SupportedRates: List<Float> = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    private val lock = Any()
    private val mediaByTabId = LinkedHashMap<String, ActiveMediaSessionInfo>()
    private var selectedTabId: String? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _activeMedia = MutableStateFlow<MediaMiniPlayerState?>(null)
    val activeMedia: StateFlow<MediaMiniPlayerState?> = _activeMedia.asStateFlow()

    var onTogglePlayPauseForTab: ((tabId: String, play: Boolean) -> Unit)? = null
    var onPlaybackRateForTab: ((tabId: String, rate: Float) -> Unit)? = null
    var onRequestPipForTab: ((tabId: String) -> Unit)? = null

    var onTogglePlayPauseCommand: ((Boolean) -> Unit)? = null
    var onPlaybackRateCommand: ((Float) -> Unit)? = null

    fun nextCycledSpeed(currentRate: Float): Float {
        val idx = SupportedRates.indexOfFirst { kotlin.math.abs(it - currentRate) < 0.05f }
        return if (idx < 0 || idx == SupportedRates.lastIndex) {
            SupportedRates.first()
        } else {
            SupportedRates[idx + 1]
        }
    }

    fun updateActiveMedia(tabId: String, info: ActiveMediaSessionInfo?) {
        val resolved = synchronized(lock) {
            if (info == null) {
                mediaByTabId.remove(tabId)
            } else {
                mediaByTabId.remove(tabId)
                mediaByTabId[tabId] = info
            }
            resolveEffectiveMediaLocked()
        }
        _activeMedia.value = resolved
    }

    fun onSelectedTabChanged(tabId: String?) {
        val resolved = synchronized(lock) {
            selectedTabId = tabId
            resolveEffectiveMediaLocked()
        }
        _activeMedia.value = resolved
    }

    fun clearIfOwnedBy(tabId: String) {
        val resolved = synchronized(lock) {
            mediaByTabId.remove(tabId)
            resolveEffectiveMediaLocked()
        }
        _activeMedia.value = resolved
    }

    fun clearAll() {
        synchronized(lock) {
            mediaByTabId.clear()
            selectedTabId = null
        }
        _activeMedia.value = null
    }

    fun dispatchTogglePlayPause(play: Boolean) {
        val target = _activeMedia.value ?: return
        val tabHandler = onTogglePlayPauseForTab
        if (tabHandler != null) {
            tabHandler(target.sessionTabId, play)
        } else {
            onTogglePlayPauseCommand?.invoke(play)
        }
    }

    fun dispatchPlaybackRate(rate: Float) {
        val target = _activeMedia.value ?: return
        val tabHandler = onPlaybackRateForTab
        if (tabHandler != null) {
            tabHandler(target.sessionTabId, rate)
        } else {
            onPlaybackRateCommand?.invoke(rate)
        }
    }

    fun dispatchRequestPip() {
        val target = _activeMedia.value ?: return
        onRequestPipForTab?.invoke(target.sessionTabId)
    }

    /**
     * Connects a Media3 [MediaController] to [FocalMediaSessionService] so Android automatically
     * binds the service and promotes/updates system media playback notifications on playback.
     */
    fun ensureServiceConnected(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val existing = controllerFuture
            if (existing != null && !existing.isCancelled) return
            try {
                val token = SessionToken(
                    appContext,
                    ComponentName(appContext, FocalMediaSessionService::class.java),
                )
                controllerFuture = MediaController.Builder(appContext, token).buildAsync()
            } catch (_: Throwable) {
                // Ignore in headless test environments without MediaSessionService binding
            }
        }
    }

    fun releaseServiceConnection() {
        val future = synchronized(lock) {
            val current = controllerFuture
            controllerFuture = null
            current
        }
        if (future != null) {
            MediaController.releaseFuture(future)
        }
    }

    private fun resolveEffectiveMediaLocked(): MediaMiniPlayerState? {
        if (mediaByTabId.isEmpty()) return null
        val selId = selectedTabId

        // 1. Prefer the currently visible tab if it is actively playing
        if (selId != null) {
            val selInfo = mediaByTabId[selId]
            if (selInfo != null && selInfo.isPlaying) {
                return MediaMiniPlayerState(sessionTabId = selId, media = selInfo)
            }
        }

        // 2. Otherwise keep any playing background tab pinned in MediaMiniPlayerDock
        val playingBg = mediaByTabId.entries.lastOrNull { it.value.isPlaying }
        if (playingBg != null) {
            return MediaMiniPlayerState(sessionTabId = playingBg.key, media = playingBg.value)
        }

        // 3. Fall back to paused media on the selected tab, or most recent paused media tab
        if (selId != null) {
            val selPaused = mediaByTabId[selId]
            if (selPaused != null) {
                return MediaMiniPlayerState(sessionTabId = selId, media = selPaused)
            }
        }
        val latestPaused = mediaByTabId.entries.lastOrNull() ?: return null
        return MediaMiniPlayerState(sessionTabId = latestPaused.key, media = latestPaused.value)
    }
}

/**
 * AndroidX Media3 [MediaSessionService] exposing Focal's background/foreground tab audio
 * and video sessions to Android system media notifications, Bluetooth headsets, and lockscreen.
 */
@UnstableApi
class FocalMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var bridgePlayer: WebEngineBridgePlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val player = WebEngineBridgePlayer(Looper.getMainLooper())
        bridgePlayer = player

        val builder = MediaSession.Builder(this, player)
            .setId("focal_browser_media_session")
        packageManager?.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setSessionActivity(pendingIntent)
        }
        mediaSession = builder.build()

        serviceScope.launch {
            FocalMediaBridge.activeMedia.collect {
                bridgePlayer?.notifyBridgeStateChanged()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        bridgePlayer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

@UnstableApi
private class WebEngineBridgePlayer(
    looper: Looper,
) : SimpleBasePlayer(looper) {

    fun notifyBridgeStateChanged() {
        invalidateState()
    }

    override fun getState(): State {
        val currentState = FocalMediaBridge.activeMedia.value
        val current = currentState?.media
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
            )
            .build()

        val metadata = MediaMetadata.Builder()
            .setTitle(current?.title ?: "Focal Media")
            .setArtist(current?.domain ?: "Focal Browser")
            .build()

        val itemId = currentState?.sessionTabId ?: "focal_active_web_media"
        val itemData = MediaItemData.Builder(itemId)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(itemId)
                    .setMediaMetadata(metadata)
                    .build(),
            )
            .setMediaMetadata(metadata)
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(
                current?.isPlaying == true,
                PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackState(
                if (current != null) STATE_READY else STATE_IDLE,
            )
            .setPlaybackParameters(PlaybackParameters(current?.playbackRate ?: 1.0f))
            .setPlaylist(if (current != null) listOf(itemData) else emptyList())
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        FocalMediaBridge.dispatchTogglePlayPause(playWhenReady)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        FocalMediaBridge.dispatchPlaybackRate(playbackParameters.speed)
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}

/**
 * Compact Smoked Glass Floating Mini-Player (`Play/Pause`, title, `1.0x/1.25x/1.5x/2.0x` speed pill,
 * and `PiP` pop-out trigger) docked `8.dp` above Focal's 3-part bottom bar.
 */
@Composable
fun MediaMiniPlayerDock(
    mediaInfo: ActiveMediaSessionInfo,
    onTogglePlayPause: (Boolean) -> Unit,
    onCyclePlaybackRate: (Float) -> Unit,
    onEnterPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val barTint = rememberUpdatedState(colors.effectiveBarGlass)
    val dockShape = RoundedCornerShape(22.dp)

    SmokedGlassBox(
        shape = dockShape,
        glassTintState = barTint,
        borderSpecular = colors.borderSpecular,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Play / Pause Circular Control
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.surfacePillActive)
                    .clickable { onTogglePlayPause(!mediaInfo.isPlaying) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (mediaInfo.isPlaying) "❚❚" else "▶",
                    style = typography.tabBadgeNumeral,
                    color = colors.inkOnPillActive,
                )
            }

            // Title & Origin Domain
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = mediaInfo.title.ifBlank { "Playing Media" },
                    style = typography.controlPillLabel,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaInfo.domain,
                    style = typography.captionMetadata,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Speed Cycle Pill (`1.0× / 1.25× / 1.5× / 2.0×` in Allo Crisp tabular numerals)
            val formattedRate = if (mediaInfo.playbackRate % 1f == 0f) {
                String.format("%.1f×", mediaInfo.playbackRate)
            } else {
                String.format("%.2f×", mediaInfo.playbackRate).trimEnd('0')
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .border(1.dp, colors.borderSpecular, CircleShape)
                    .clickable {
                        onCyclePlaybackRate(FocalMediaBridge.nextCycledSpeed(mediaInfo.playbackRate))
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formattedRate,
                    style = typography.tabBadgeNumeral,
                    color = colors.inkPrimary,
                )
            }

            // PiP Trigger Pill
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .border(1.dp, colors.borderSpecular, CircleShape)
                    .clickable(onClick = onEnterPictureInPicture)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "PiP ⧉",
                    style = typography.tabBadgeNumeral,
                    color = colors.inkPrimary,
                )
            }
        }
    }
}
