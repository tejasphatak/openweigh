package io.github.openweigh.drive

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.StateFlow

/**
 * Flavor-agnostic contract for the optional cloud identity used by [CloudBackup].
 *
 * The `play` flavor implements this with Credential Manager + Google Identity Services
 * ([GoogleAuthManager]); the `foss` flavor provides an unsupported no-op so the GMS-free build
 * links without Google Play Services. The UI uses [isSupported] to hide the cloud section entirely
 * in the foss flavor.
 */
interface CloudAccountManager {

    /** A signed-in cloud account. */
    data class Account(
        val idToken: String?,
        val email: String?,
        val displayName: String?,
    )

    /** Result of an incremental authorization request. */
    sealed interface ScopeRequest {
        /** Scopes already granted; [accessToken] is ready for use. */
        data class Granted(val accessToken: String) : ScopeRequest

        /**
         * The user must approve the scopes on a consent screen. Launch [intentSender] (e.g. via
         * `StartIntentSenderForResult`), then pass the returned data [Intent] to [completeScopeRequest].
         */
        data class NeedsConsent(val intentSender: IntentSender) : ScopeRequest

        data class Failed(val cause: Throwable) : ScopeRequest
    }

    /** Whether cloud sign-in/backup is available in this build (false in the foss flavor). */
    val isSupported: Boolean

    /** The currently signed-in account, or null. Reactive. */
    val currentAccount: StateFlow<Account?>

    /** True once the backup scopes are authorized and an access token is held. */
    val isBackupAuthorized: Boolean

    suspend fun signIn(activityContext: Context): Account
    suspend fun signOut()
    suspend fun requestBackupScopes(): ScopeRequest
    fun completeScopeRequest(data: Intent?): String?
}
