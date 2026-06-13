package io.github.openweigh.drive

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `foss`-flavor [CloudAccountManager]: Google sign-in is unavailable in the GMS-free build.
 * [isSupported] is false (the Settings UI hides the cloud section accordingly), there is never a
 * signed-in account, and any attempt to authorize fails gracefully rather than crashing.
 */
@Singleton
class NoOpCloudAccountManager @Inject constructor() : CloudAccountManager {

    private val _currentAccount = MutableStateFlow<CloudAccountManager.Account?>(null)
    override val currentAccount: StateFlow<CloudAccountManager.Account?> = _currentAccount.asStateFlow()

    override val isSupported: Boolean = false
    override val isBackupAuthorized: Boolean = false

    override suspend fun signIn(activityContext: Context): CloudAccountManager.Account =
        throw UnsupportedOperationException("Google sign-in is not available in the F-Droid build.")

    override suspend fun signOut() = Unit

    override suspend fun requestBackupScopes(): CloudAccountManager.ScopeRequest =
        CloudAccountManager.ScopeRequest.Failed(
            UnsupportedOperationException("Cloud backup is not available in the F-Droid build."),
        )

    override fun completeScopeRequest(data: Intent?): String? = null
}
