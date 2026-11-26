package app.focal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Events delivered by Android's [PackageInstaller] while committing an in-app Focal self-update.
 */
sealed interface FocalInstallEvent {
    data class PendingUserAction(val confirmIntent: Intent) : FocalInstallEvent
    data object Success : FocalInstallEvent
    data object Cancelled : FocalInstallEvent
    data class Failed(val message: String) : FocalInstallEvent
}

/**
 * Process-local event bus bridging [FocalUpdateInstallReceiver] to [MainActivity] so the
 * confirmation sheet launches smoothly inside Focal's task and UI status stays synchronized.
 */
object FocalUpdateInstallStatusBus {
    private val _events = MutableSharedFlow<FocalInstallEvent>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val events: SharedFlow<FocalInstallEvent> = _events.asSharedFlow()

    fun emit(event: FocalInstallEvent): Boolean = _events.tryEmit(event)

    fun hasActiveCollectors(): Boolean = _events.subscriptionCount.value > 0
}

/**
 * Receives commit results from [PackageInstaller.Session.commit] for Focal's native in-app updater.
 *
 * Registered explicitly with `android:exported="false"` so Android 12–15 safely deliver
 * `EXTRA_STATUS` and `EXTRA_INTENT` via an explicit `FLAG_MUTABLE` PendingIntent.
 */
class FocalUpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val rawMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    val deliveredToActivity = FocalUpdateInstallStatusBus.hasActiveCollectors() &&
                        FocalUpdateInstallStatusBus.emit(FocalInstallEvent.PendingUserAction(confirmIntent))
                    if (!deliveredToActivity) {
                        runCatching {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(confirmIntent)
                        }
                    }
                } else {
                    FocalUpdateInstallStatusBus.emit(
                        FocalInstallEvent.Failed("System installer did not return a confirmation dialog"),
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                FocalReleaseUpdater.cleanupCachedUpdates(context)
                FocalUpdateInstallStatusBus.emit(FocalInstallEvent.Success)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                FocalUpdateInstallStatusBus.emit(FocalInstallEvent.Cancelled)
            }

            else -> {
                FocalUpdateInstallStatusBus.emit(
                    FocalInstallEvent.Failed(mapFailureMessage(status, rawMessage)),
                )
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "app.focal.action.UPDATE_INSTALL_RESULT"

        internal fun mapFailureMessage(status: Int, rawMessage: String): String {
            return when (status) {
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free storage to install update"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "Update signature conflicts with installed Focal build"
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Update package is not compatible with this device"
                PackageInstaller.STATUS_FAILURE_INVALID -> "Downloaded update package is invalid"
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "Update blocked by device security policy"
                else -> rawMessage.ifBlank { "Update installation failed (code $status)" }
            }
        }
    }
}
