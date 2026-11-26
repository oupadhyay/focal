package app.focal

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import app.focal.adblock.AdblockController
import app.focal.adblock.NativeAdblockController
import app.focal.ai.LocalSummarizer
import app.focal.ai.OnDeviceSummarizer
import app.focal.database.FocalDatabase
import app.focal.database.FocalDatabaseDefaults
import app.focal.engine.api.BrowserEngine
import app.focal.engine.webview.WebViewBrowserEngine
import app.focal.feature.tabs.AutoArchiveTabsWorker
import app.focal.sync.SyncRelaySessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-level container initializing Focal's 120Hz runtime graph:
 * - Native Rust `AdblockController` (`libfocal_adblock.so` + serialized DAT cache on `Dispatchers.IO`)
 * - `WebViewBrowserEngine` (`WarmWebViewPool` background Chromium warmup deferred past first-frame focus)
 * - Room `FocalDatabase` seeded with `Personal`, `Work`, and `Reading` Spaces
 * - 100% On-Device `LocalSummarizer` (`AICore` / `LiteRT-LM` / Offline RAM + `ParagraphGrounder`)
 * - Zero-Account E2EE `SyncRelaySessionManager` (lazy off-main-thread AndroidKeyStore hydration)
 * - `AutoArchiveTabsWorker` (lazy on-demand `WorkManager` via `Configuration.Provider`, deferred 10s post-launch)
 */
class FocalApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.WARN)
            .build()

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var adblockController: AdblockController
        private set

    lateinit var database: FocalDatabase
        private set

    lateinit var browserEngine: BrowserEngine
        private set

    lateinit var localSummarizer: LocalSummarizer
        private set

    lateinit var syncManager: SyncRelaySessionManager
        private set

    override fun onCreate() {
        super.onCreate()

        // Avoid synchronous main-thread rule compilation in Application.onCreate() (P2-2)
        val nativeAdblock = NativeAdblockController(autoLoadNativeLibrary = false)
        adblockController = nativeAdblock
        database = FocalDatabase.getInstance(this)
        browserEngine = WebViewBrowserEngine(
            context = this,
            adblockController = adblockController,
        ).also { it.warmup() }

        localSummarizer = OnDeviceSummarizer(appContext = this)
        syncManager = SyncRelaySessionManager(context = this)

        applicationScope.launch(Dispatchers.IO) {
            launch {
                FocalDatabaseDefaults.seedIfEmpty(database)
            }
            launch {
                // Keep Frame 1 + FocusEvent + Gboard reveal 100% uncontested on low-core devices
                delay(450L)
                nativeAdblock.initializeOrLoadCache(File(cacheDir, "focal_adblock_engine.dat"))
            }
            launch {
                // Defer WorkManager DB init & JobScheduler registration well past cold launch
                delay(10_000L)
                runCatching {
                    AutoArchiveTabsWorker.schedulePeriodicSweep(this@FocalApplication)
                }
            }
        }
    }
}
