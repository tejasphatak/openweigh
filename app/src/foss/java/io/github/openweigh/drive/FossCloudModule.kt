package io.github.openweigh.drive

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `foss`-flavor Hilt bindings: wires the cloud interfaces to no-op implementations so the GMS-free
 * build contains no Google Play Services dependency.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FossCloudModule {

    @Binds
    @Singleton
    abstract fun bindCloudBackup(impl: NoOpCloudBackup): CloudBackup

    @Binds
    @Singleton
    abstract fun bindCloudAccountManager(impl: NoOpCloudAccountManager): CloudAccountManager
}
