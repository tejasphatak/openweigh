package io.github.openweigh

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.openweigh.diag.CrashReporter
import io.github.openweigh.sync.BackupWorker
import javax.inject.Inject

/**
 * Application entry point; bootstraps Hilt's dependency graph.
 *
 * Also implements [Configuration.Provider] so WorkManager uses the Hilt-aware
 * [HiltWorkerFactory], enabling `@HiltWorker` `CoroutineWorker`s (e.g. the Drive backup worker)
 * to receive injected dependencies via on-demand initialization.
 */
@HiltAndroidApp
class BleScaleApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to private storage so the bug-report screen can surface the
        // last crash across restarts. No data leaves the device.
        CrashReporter.install(this)
        // Durable, network-constrained fallback that backs the store up to Drive's appDataFolder.
        // Idempotent (KEEP) and a no-op until the user enables/authorizes Drive backup.
        BackupWorker.enqueuePeriodic(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
