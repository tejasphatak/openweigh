package io.github.openweigh.sync

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the sync subsystem.
 *
 * [SyncCoordinator] is `@Singleton` + `@Inject constructor`, and [BackupWorker] is a `@HiltWorker`
 * instantiated by the `HiltWorkerFactory` (wired in `BleScaleApp` as a `Configuration.Provider`),
 * so neither needs an explicit `@Provides`. This module exists as the subsystem's designated place
 * for any future sync-specific bindings, keeping them out of other agents' modules per the DI
 * conventions. It intentionally declares no bindings today.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule
