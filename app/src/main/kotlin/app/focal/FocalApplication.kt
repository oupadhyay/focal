package app.focal

import android.app.Application
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
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-level container initializing Focal's 120Hz runtime graph:
 * - Native Rust `AdblockController` (`libfocal_adblock.so` + serialized DAT cache on `Dispatchers.IO`)
 * - `WebViewBrowserEngine` (`WarmWebViewPool` scheduled on idle after first frame)
 * - Room `FocalDatabase` seeded with `Personal`, `Work`, and `Reading` Spaces
 * - 100% On-Device `LocalSummarizer` (`AICore` / `LiteRT-LM` / Offline RAM + `ParagraphGrounder`)
 * - Zero-Account E2EE `SyncRelaySessionManager` (AndroidKeyStore-encrypted persistent session)
 * - `AutoArchiveTabsWorker` (`WorkManager` 24h sweep protecting unsent form drafts)
 */
class FocalApplication : Application() {

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
                nativeAdblock.initializeOrLoadCache(File(cacheDir, "focal_adblock_engine.dat"))
            }
            launch {
                FocalDatabaseDefaults.seedIfEmpty(database)
            }
            launch {
                runCatching {
                    AutoArchiveTabsWorker.schedulePeriodicSweep(this@FocalApplication)
                }
            }
        }
    }
}
