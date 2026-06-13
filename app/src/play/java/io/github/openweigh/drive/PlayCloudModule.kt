package io.github.openweigh.drive

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `play`-flavor Hilt bindings: wires the cloud interfaces in `main` to their real Google Drive /
 * Google Identity Services implementations. The HTTP machinery `@Provides` lives in [DriveModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayCloudModule {

    @Binds
    @Singleton
    abstract fun bindCloudBackup(impl: DriveBackupService): CloudBackup

    @Binds
    @Singleton
    abstract fun bindCloudAccountManager(impl: GoogleAuthManager): CloudAccountManager
}
