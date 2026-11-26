package app.focal.feature.tabs

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.focal.database.FocalDatabase
import app.focal.database.TabMetadataEntity
import java.util.concurrent.TimeUnit

/**
 * Background 24h Tab Auto-Tidy Worker (`WorkManager`).
 *
 * Moves idle tabs (`lastActiveEpochMs <= now - 24h`) into the zero-RAM Tidied Tray while
 * **strictly excluding** any tab with `hasUnsentFormDraft == true` (`<input>`, `<textarea>`,
 * or `[contenteditable]` modified by the user). Also prunes archived metadata older than 30 days.
 */
class AutoArchiveTabsWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val nowMs = System.currentTimeMillis()
            val archiveCutoffMs = nowMs - TabMetadataEntity.AUTO_ARCHIVE_WINDOW_MS
            val purgeCutoffMs = nowMs - TabMetadataEntity.ARCHIVE_RETENTION_MS

            val tabDao = FocalDatabase.getInstance(applicationContext).tabMetadataDao()
            tabDao.autoArchiveIdleTabsExcludingDrafts(
                cutoffEpochMs = archiveCutoffMs,
                nowEpochMs = nowMs,
            )
            tabDao.purgeExpiredArchives(olderThanEpochMs = purgeCutoffMs)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "focal_auto_archive_tabs_24h"

        fun schedulePeriodicSweep(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoArchiveTabsWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setInitialDelay(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
