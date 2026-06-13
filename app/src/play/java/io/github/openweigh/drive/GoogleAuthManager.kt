package io.github.openweigh.drive

import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.openweigh.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `play`-flavor [CloudAccountManager]. Owns Google identity for OpenWeigh's optional Drive
 * features. There is **no app backend**: this uses Credential Manager ("Sign in with Google")
 * purely to identify the user, then Google Identity Services'
 * [com.google.android.gms.auth.api.identity.AuthorizationClient] to request Drive OAuth scopes
 * **incrementally** (the user grants Drive access only when they turn a backup/export feature on).
 *
 * Everything here is best-effort and optional; the app is fully functional offline with no Google
 * account. The `foss` flavor swaps this out for [NoOpCloudAccountManager].
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) : CloudAccountManager {

    private val credentialManager: CredentialManager = CredentialManager.create(context)
    private val authorizationClient = Identity.getAuthorizationClient(context)

    private val _currentAccount = MutableStateFlow<CloudAccountManager.Account?>(null)
    override val currentAccount: StateFlow<CloudAccountManager.Account?> = _currentAccount.asStateFlow()

    private val _authorizedScopes = MutableStateFlow<Set<String>>(emptySet())
    /** Drive OAuth scopes the user has granted so far. Reactive. */
    val authorizedScopes: StateFlow<Set<String>> = _authorizedScopes.asStateFlow()

    @Volatile
    private var cachedAccessToken: String? = null

    override val isSupported: Boolean get() = true

    override val isBackupAuthorized: Boolean
        get() = cachedAccessToken != null && _authorizedScopes.value.containsAll(BACKUP_SCOPES)

    override suspend fun signIn(activityContext: Context): CloudAccountManager.Account {
        check(serverClientId.isNotBlank()) {
            "No GOOGLE_SERVER_CLIENT_ID configured; Drive features are disabled in this build."
        }
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = credentialManager.getCredential(activityContext, request)
        val account = response.credential.toAccountOrNull()
            ?: throw IllegalStateException("Unexpected credential type from Credential Manager.")
        _currentAccount.value = account
        return account
    }

    /** Request the Drive backup scopes (`drive.appdata` + `drive.file`) incrementally. */
    override suspend fun requestBackupScopes(): CloudAccountManager.ScopeRequest = requestScopes(BACKUP_SCOPES)

    /** Request only the visible-export scope (`drive.file`). */
    suspend fun requestExportScopes(): CloudAccountManager.ScopeRequest = requestScopes(setOf(SCOPE_DRIVE_FILE))

    private suspend fun requestScopes(scopes: Set<String>): CloudAccountManager.ScopeRequest {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(scopes.map { Scope(it) })
                .build()
            val result = authorizationClient.authorize(request).await()
            handleAuthorizationResult(result, scopes)
        } catch (t: Throwable) {
            CloudAccountManager.ScopeRequest.Failed(t)
        }
    }

    override fun completeScopeRequest(data: Intent?): String? {
        if (data == null) return null
        return try {
            val result = authorizationClient.getAuthorizationResultFromIntent(data)
            when (val handled = handleAuthorizationResult(result, _authorizedScopes.value)) {
                is CloudAccountManager.ScopeRequest.Granted -> handled.accessToken
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleAuthorizationResult(
        result: AuthorizationResult,
        requestedScopes: Set<String>
    ): CloudAccountManager.ScopeRequest {
        val pendingIntent = result.pendingIntent
        if (result.accessToken == null && pendingIntent != null) {
            return CloudAccountManager.ScopeRequest.NeedsConsent(pendingIntent.intentSender)
        }
        val token = result.accessToken
            ?: return CloudAccountManager.ScopeRequest.Failed(IllegalStateException("No access token returned."))
        cachedAccessToken = token
        val granted = result.grantedScopes?.toSet()?.takeIf { it.isNotEmpty() } ?: requestedScopes
        _authorizedScopes.value = _authorizedScopes.value + granted
        return CloudAccountManager.ScopeRequest.Granted(token)
    }

    /** The current OAuth access token for Drive REST calls, or null if not yet authorized. */
    val accessToken: String?
        get() = cachedAccessToken

    /** Same as [accessToken]; convenience accessor named for the contract. */
    fun authorizedAccessTokenOrNull(): String? = cachedAccessToken

    /**
     * Re-authorize silently if possible, returning a fresh access token. Used by the background
     * [io.github.openweigh.sync.BackupWorker] which has no UI to show a consent screen — if consent
     * is newly required it returns null and the backup is skipped until the user opens the app.
     */
    suspend fun refreshAccessTokenOrNull(): String? {
        if (cachedAccessToken != null) return cachedAccessToken
        return when (val req = requestBackupScopes()) {
            is CloudAccountManager.ScopeRequest.Granted -> req.accessToken
            else -> null
        }
    }

    override suspend fun signOut() {
        cachedAccessToken = null
        _authorizedScopes.value = emptySet()
        _currentAccount.value = null
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private val serverClientId: String
        get() = BuildConfig.GOOGLE_SERVER_CLIENT_ID

    private fun Credential.toAccountOrNull(): CloudAccountManager.Account? {
        if (this is CustomCredential &&
            type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val cred = GoogleIdTokenCredential.createFrom(data)
            return CloudAccountManager.Account(
                idToken = cred.idToken,
                email = cred.id,
                displayName = cred.displayName
            )
        }
        return null
    }

    companion object {
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        /** The scopes needed for hidden backup + visible export. */
        val BACKUP_SCOPES: Set<String> = setOf(SCOPE_DRIVE_APPDATA, SCOPE_DRIVE_FILE)
    }
}
