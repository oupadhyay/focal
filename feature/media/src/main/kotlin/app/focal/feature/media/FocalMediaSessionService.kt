package app.focal.feature.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import app.focal.design.FocalMotion
import app.focal.design.FocalTheme
import app.focal.design.focalSheetEntrance
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
import kotlin.math.abs
import kotlin.math.max

@Immutable
data class MediaMiniPlayerState(
    val sessionTabId: String,
    val media: ActiveMediaSessionInfo,
)

/**
 * Pure swipe-to-dismiss decision & visual curve rules for [MediaMiniPlayerDock].
 *
 * Kept pure JVM so left/right thresholds, flick velocity gating, and visual fade/scale curves
 * are exhaustively unit-testable without an Android runtime.
 */
object MediaMiniPlayerSwipePhysics {
    const val MIN_DISMISS_DISTANCE_DP = 72f
    const val DISMISS_WIDTH_FRACTION = 0.35f
    const val FLING_VELOCITY_DP_PER_SEC = 650f
    const val MIN_FLING_DISPLACEMENT_DP = 16f

    fun dismissThresholdPx(widthPx: Int, minThresholdPx: Float): Float {
        return max(minThresholdPx, widthPx * DISMISS_WIDTH_FRACTION)
    }

    fun shouldDismiss(
        offsetPx: Float,
        velocityPxPerSec: Float,
        widthPx: Int,
        minThresholdPx: Float,
        flingVelocityPxPerSec: Float,
        minFlingOffsetPx: Float,
    ): Boolean {
        val threshold = dismissThresholdPx(widthPx, minThresholdPx)
        if (abs(offsetPx) >= threshold) return true
        val alignedFling = (offsetPx * velocityPxPerSec) >= 0f
        return alignedFling &&
            abs(offsetPx) >= minFlingOffsetPx &&
            abs(velocityPxPerSec) >= flingVelocityPxPerSec
    }

    fun dismissExitTargetPx(
        offsetPx: Float,
        velocityPxPerSec: Float,
        widthPx: Int,
        extraMarginPx: Float,
    ): Float {
        val direction = when {
            offsetPx < 0f -> -1f
            offsetPx > 0f -> 1f
            velocityPxPerSec < 0f -> -1f
            else -> 1f
        }
        return direction * (widthPx.coerceAtLeast(1) + extraMarginPx)
    }

    fun dragAlpha(offsetPx: Float, thresholdPx: Float): Float {
        val safeThreshold = thresholdPx.coerceAtLeast(1f)
        val fraction = (abs(offsetPx) / safeThreshold).coerceIn(0f, 1.4f)
        return (1f - fraction * 0.58f).coerceIn(0.18f, 1f)
    }

    fun dragScale(offsetPx: Float, thresholdPx: Float): Float {
        val safeThreshold = thresholdPx.coerceAtLeast(1f)
        val fraction = (abs(offsetPx) / safeThreshold).coerceIn(0f, 1f)
        return 1f - (fraction * 0.04f)
    }
}

/**
 * Shared coordinator bridging WebView HTML5 `<video>` / `<audio>` playback across **all** live
 * tabs to both the floating Smoked Glass [MediaMiniPlayerDock] and Android's [FocalMediaSessionService].
 *
 * Switching away from a playing tab keeps its media session active in [activeMedia] and routes
 * Play/Pause and speed (`1.0×/1.25×/1.5×/2.0×`) commands directly to the owning background tab.
 *
 * Users can swipe away the floating [MediaMiniPlayerDock] via [dismissMiniPlayer] to uncover page
 * content without interrupting background playback or Android's system media notification:
 * [activeMedia] stays live for [FocalMediaSessionService] while [dockMedia] hides the dismissed
 * session until the track/page changes, media ends and restarts, or a paused session resumes play.
 */
object FocalMediaBridge {
    val SupportedRates: List<Float> = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    private data class DismissedMediaSignature(
        val domain: String,
        val title: String,
        val dismissedWhilePlaying: Boolean,
    )

    private val lock = Any()
    private val mediaByTabId = LinkedHashMap<String, ActiveMediaSessionInfo>()
    private val dismissedByTabId = HashMap<String, DismissedMediaSignature>()
    private var selectedTabId: String? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _activeMedia = MutableStateFlow<MediaMiniPlayerState?>(null)
    val activeMedia: StateFlow<MediaMiniPlayerState?> = _activeMedia.asStateFlow()

    private val _dockMedia = MutableStateFlow<MediaMiniPlayerState?>(null)
    val dockMedia: StateFlow<MediaMiniPlayerState?> = _dockMedia.asStateFlow()

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
        val (resolvedActive, resolvedDock) = synchronized(lock) {
            if (info == null) {
                mediaByTabId.remove(tabId)
                dismissedByTabId.remove(tabId)
            } else {
                val dismissed = dismissedByTabId[tabId]
                if (dismissed != null) {
                    val trackChanged = dismissed.domain != info.domain || dismissed.title != info.title
                    val pausedResumedPlaying = !dismissed.dismissedWhilePlaying && info.isPlaying
                    if (trackChanged || pausedResumedPlaying) {
                        dismissedByTabId.remove(tabId)
                    }
                }
                mediaByTabId.remove(tabId)
                mediaByTabId[tabId] = info
            }
            resolveEffectiveMediaLocked(excludeDismissed = false) to
                resolveEffectiveMediaLocked(excludeDismissed = true)
        }
        _activeMedia.value = resolvedActive
        _dockMedia.value = resolvedDock
    }

    /**
     * Hides the currently shown floating [MediaMiniPlayerDock] pill (or the specified [tabId])
     * without stopping background playback or removing Android's Media3 notification controls.
     */
    fun dismissMiniPlayer(tabId: String? = null) {
        val resolvedDock = synchronized(lock) {
            val target = if (tabId != null) {
                mediaByTabId[tabId]?.let { MediaMiniPlayerState(sessionTabId = tabId, media = it) }
            } else {
                resolveEffectiveMediaLocked(excludeDismissed = true)
            }
            if (target != null) {
                dismissedByTabId[target.sessionTabId] = DismissedMediaSignature(
                    domain = target.media.domain,
                    title = target.media.title,
                    dismissedWhilePlaying = target.media.isPlaying,
                )
            }
            resolveEffectiveMediaLocked(excludeDismissed = true)
        }
        _dockMedia.value = resolvedDock
    }

    fun onSelectedTabChanged(tabId: String?) {
        val (resolvedActive, resolvedDock) = synchronized(lock) {
            selectedTabId = tabId
            resolveEffectiveMediaLocked(excludeDismissed = false) to
                resolveEffectiveMediaLocked(excludeDismissed = true)
        }
        _activeMedia.value = resolvedActive
        _dockMedia.value = resolvedDock
    }

    fun clearIfOwnedBy(tabId: String) {
        val (resolvedActive, resolvedDock) = synchronized(lock) {
            mediaByTabId.remove(tabId)
            dismissedByTabId.remove(tabId)
            resolveEffectiveMediaLocked(excludeDismissed = false) to
                resolveEffectiveMediaLocked(excludeDismissed = true)
        }
        _activeMedia.value = resolvedActive
        _dockMedia.value = resolvedDock
    }

    fun clearAll() {
        synchronized(lock) {
            mediaByTabId.clear()
            dismissedByTabId.clear()
            selectedTabId = null
        }
        _activeMedia.value = null
        _dockMedia.value = null
    }

    fun dispatchTogglePlayPause(play: Boolean) {
        val target = _dockMedia.value ?: _activeMedia.value ?: return
        val tabHandler = onTogglePlayPauseForTab
        if (tabHandler != null) {
            tabHandler(target.sessionTabId, play)
        } else {
            onTogglePlayPauseCommand?.invoke(play)
        }
    }

    fun dispatchPlaybackRate(rate: Float) {
        val target = _dockMedia.value ?: _activeMedia.value ?: return
        val tabHandler = onPlaybackRateForTab
        if (tabHandler != null) {
            tabHandler(target.sessionTabId, rate)
        } else {
            onPlaybackRateCommand?.invoke(rate)
        }
    }

    fun dispatchRequestPip() {
        val target = _dockMedia.value ?: _activeMedia.value ?: return
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

    private fun resolveEffectiveMediaLocked(excludeDismissed: Boolean): MediaMiniPlayerState? {
        if (mediaByTabId.isEmpty()) return null
        val selId = selectedTabId
        fun isAllowed(tabId: String): Boolean = !excludeDismissed || !dismissedByTabId.containsKey(tabId)

        // 1. Prefer the currently visible tab if it is actively playing
        if (selId != null && isAllowed(selId)) {
            val selInfo = mediaByTabId[selId]
            if (selInfo != null && selInfo.isPlaying) {
                return MediaMiniPlayerState(sessionTabId = selId, media = selInfo)
            }
        }

        // 2. Otherwise keep any playing background tab pinned in MediaMiniPlayerDock
        val playingBg = mediaByTabId.entries.lastOrNull { isAllowed(it.key) && it.value.isPlaying }
        if (playingBg != null) {
            return MediaMiniPlayerState(sessionTabId = playingBg.key, media = playingBg.value)
        }

        // 3. Fall back to paused media on the selected tab, or most recent paused media tab
        if (selId != null && isAllowed(selId)) {
            val selPaused = mediaByTabId[selId]
            if (selPaused != null) {
                return MediaMiniPlayerState(sessionTabId = selId, media = selPaused)
            }
        }
        val latestPaused = mediaByTabId.entries.lastOrNull { isAllowed(it.key) } ?: return null
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
 *
 * Supports smooth horizontal swipe-away (left or right) with critically-damped snap-back on
 * cancel and zero interruption to background audio or Android Media3 system controls.
 */
@Composable
fun MediaMiniPlayerDock(
    mediaInfo: ActiveMediaSessionInfo,
    onTogglePlayPause: (Boolean) -> Unit,
    onCyclePlaybackRate: (Float) -> Unit,
    onEnterPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
    isFromBackgroundTab: Boolean = false,
    onFocusMediaTab: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = FocalTheme.colors
    val typography = FocalTheme.typography
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val barTint = rememberUpdatedState(colors.effectiveBarGlass)
    val latestOnDismiss = rememberUpdatedState(onDismiss)
    val dockShape = RoundedCornerShape(24.dp)

    val mediaIdentityKey = "${mediaInfo.domain}:${mediaInfo.title}"
    val swipeOffsetAnim = remember { Animatable(0f) }
    val dismissAlphaAnim = remember { Animatable(1f) }
    var liveDragOffsetPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var pillWidthPx by remember { mutableIntStateOf(0) }
    var isDismissing by remember { mutableStateOf(false) }
    var passedThresholdHaptic by remember { mutableStateOf(false) }

    LaunchedEffect(mediaIdentityKey) {
        isDismissing = false
        passedThresholdHaptic = false
        liveDragOffsetPx = 0f
        swipeOffsetAnim.snapTo(0f)
        dismissAlphaAnim.snapTo(1f)
    }

    val minThresholdPx = with(density) { MediaMiniPlayerSwipePhysics.MIN_DISMISS_DISTANCE_DP.dp.toPx() }
    val flingVelocityPx = with(density) { MediaMiniPlayerSwipePhysics.FLING_VELOCITY_DP_PER_SEC.dp.toPx() }
    val minFlingOffsetPx = with(density) { MediaMiniPlayerSwipePhysics.MIN_FLING_DISPLACEMENT_DP.dp.toPx() }
    val extraExitMarginPx = with(density) { 96.dp.toPx() }

    val draggableState = rememberDraggableState { delta ->
        if (isDismissing) return@rememberDraggableState
        liveDragOffsetPx += delta
        val currentOffset = liveDragOffsetPx + swipeOffsetAnim.value
        val thresholdPx = MediaMiniPlayerSwipePhysics.dismissThresholdPx(pillWidthPx, minThresholdPx)
        val nowPast = abs(currentOffset) >= thresholdPx
        if (nowPast != passedThresholdHaptic) {
            passedThresholdHaptic = nowPast
            if (nowPast) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    val subtitleText = remember(isFromBackgroundTab, mediaInfo.domain) {
        if (isFromBackgroundTab) {
            if (mediaInfo.domain.isBlank()) "Other tab" else "Other tab · ${mediaInfo.domain}"
        } else {
            mediaInfo.domain
        }
    }

    SmokedGlassBox(
        shape = dockShape,
        glassTintState = barTint,
        borderSpecular = colors.borderSpecular,
        modifier = modifier
            .testTag("media_mini_player_dock")
            .onSizeChanged { pillWidthPx = it.width }
            .focalSheetEntrance(riseDp = 10.dp, key = mediaIdentityKey)
            .graphicsLayer {
                val offsetX = liveDragOffsetPx + swipeOffsetAnim.value
                val thresholdPx = MediaMiniPlayerSwipePhysics.dismissThresholdPx(pillWidthPx, minThresholdPx)
                val scale = MediaMiniPlayerSwipePhysics.dragScale(offsetX, thresholdPx)
                translationX = offsetX
                scaleX = scale
                scaleY = scale
                alpha = MediaMiniPlayerSwipePhysics.dragAlpha(offsetX, thresholdPx) * dismissAlphaAnim.value
            }
            .shadow(
                elevation = 14.dp,
                shape = dockShape,
                ambientColor = Color.Black.copy(alpha = 0.32f),
                spotColor = Color.Black.copy(alpha = 0.42f),
            )
            .semantics {
                if (onDismiss != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(label = "Dismiss mini-player") {
                            onDismiss.invoke()
                            true
                        },
                    )
                }
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = onDismiss != null && !isDismissing,
                onDragStarted = {
                    val existing = swipeOffsetAnim.value
                    if (existing != 0f) {
                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            liveDragOffsetPx += existing
                            swipeOffsetAnim.snapTo(0f)
                        }
                    }
                },
                onDragStopped = { velocity ->
                    if (isDismissing) return@draggable
                    val currentOffset = liveDragOffsetPx + swipeOffsetAnim.value
                    val shouldDismiss = MediaMiniPlayerSwipePhysics.shouldDismiss(
                        offsetPx = currentOffset,
                        velocityPxPerSec = velocity,
                        widthPx = pillWidthPx,
                        minThresholdPx = minThresholdPx,
                        flingVelocityPxPerSec = flingVelocityPx,
                        minFlingOffsetPx = minFlingOffsetPx,
                    )
                    if (shouldDismiss) {
                        isDismissing = true
                        val exitTarget = MediaMiniPlayerSwipePhysics.dismissExitTargetPx(
                            offsetPx = currentOffset,
                            velocityPxPerSec = velocity,
                            widthPx = pillWidthPx.takeIf { it > 0 } ?: with(density) { 260.dp.roundToPx() },
                            extraMarginPx = extraExitMarginPx,
                        )
                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            swipeOffsetAnim.snapTo(currentOffset)
                            liveDragOffsetPx = 0f
                            launch {
                                dismissAlphaAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                                )
                            }
                            swipeOffsetAnim.animateTo(
                                targetValue = exitTarget,
                                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                            )
                            latestOnDismiss.value?.invoke()
                        }
                    } else {
                        passedThresholdHaptic = false
                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            swipeOffsetAnim.snapTo(currentOffset)
                            liveDragOffsetPx = 0f
                            swipeOffsetAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = FocalMotion.tabScrubSnapSpring(),
                            )
                        }
                    }
                },
            ),
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
                    .clickable(enabled = !isDismissing) { onTogglePlayPause(!mediaInfo.isPlaying) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (mediaInfo.isPlaying) app.focal.design.FocalGlyphs.Pause else app.focal.design.FocalGlyphs.Play,
                    contentDescription = if (mediaInfo.isPlaying) "Pause" else "Play",
                    tint = colors.inkOnPillActive,
                    modifier = Modifier.size(14.dp),
                )
            }

            // Title & Origin Domain (tap to jump straight back to background playing tab when applicable)
            val titleModifier = if (isFromBackgroundTab && onFocusMediaTab != null) {
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Switch to playing tab" }
                    .clickable(enabled = !isDismissing, role = Role.Button, onClick = onFocusMediaTab)
                    .padding(horizontal = 2.dp, vertical = 1.dp)
            } else {
                Modifier.weight(1f, fill = false)
            }
            Column(modifier = titleModifier) {
                Text(
                    text = mediaInfo.title.ifBlank { "Playing Media" },
                    style = typography.controlPillLabel,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleText,
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
                    .clickable(enabled = !isDismissing) {
                        onCyclePlaybackRate(FocalMediaBridge.nextCycledSpeed(mediaInfo.playbackRate))
                    }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
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
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.surfacePillSubtle)
                    .clickable(enabled = !isDismissing, onClick = onEnterPictureInPicture),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = app.focal.design.FocalGlyphs.Pip,
                    contentDescription = "Picture in Picture",
                    tint = colors.inkPrimary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
