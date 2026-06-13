package io.github.openweigh.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.openweigh.data.repo.Measurement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the AndroidX [HealthConnectClient] for OpenWeigh.
 *
 * Responsibilities:
 * - report SDK availability ([getSdkStatus], [isAvailable]) and route users to install / update
 *   Health Connect when it is missing (relevant on API < 34 where it ships as a separate app),
 * - own the canonical set of WRITE [permissions] this app requests,
 * - hand back the [PermissionController] [ActivityResultContract] so a screen can launch the
 *   permission flow and check [hasAllPermissions],
 * - write measurements ([writeMeasurement] / [writeAll]) via [HealthConnectMapper] using stable
 *   clientRecordIds so repeated writes are idempotent upserts.
 *
 * Constructed by Hilt; the [mapper] is also @Inject so no @Provides module is needed here.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapper: HealthConnectMapper
) {

    /**
     * The exact set of WRITE permissions OpenWeigh needs to export a full body-composition
     * reading. Order is irrelevant; this is the single source of truth for both the request
     * contract and the "already granted?" check.
     */
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class)
    )

    /**
     * Raw SDK status, one of [HealthConnectClient.SDK_UNAVAILABLE],
     * [HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED], or
     * [HealthConnectClient.SDK_AVAILABLE].
     */
    fun getSdkStatus(): Int = HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)

    /** True when Health Connect is installed, up to date, and usable. */
    fun isAvailable(): Boolean = getSdkStatus() == HealthConnectClient.SDK_AVAILABLE

    /** True when the provider is installed but needs an update before the SDK can be used. */
    fun isProviderUpdateRequired(): Boolean =
        getSdkStatus() == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    /**
     * The [HealthConnectClient], or null if the SDK is not available. Callers should gate on
     * [isAvailable] (or null-check the result) before using it.
     */
    fun clientOrNull(): HealthConnectClient? =
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null

    private fun requireClient(): HealthConnectClient =
        clientOrNull() ?: throw IllegalStateException(
            "Health Connect is not available (sdkStatus=${getSdkStatus()})."
        )

    // ---- Permissions ----------------------------------------------------------------------

    /**
     * ActivityResultContract that launches the Health Connect permission UI and returns the set
     * of permissions the user granted. Feed it [permissions] when launching.
     *
     * Usage in a Compose/Activity screen:
     * ```
     * val launcher = rememberLauncherForActivityResult(
     *     healthConnectManager.permissionContract()
     * ) { granted -> /* granted.containsAll(healthConnectManager.permissions) */ }
     * launcher.launch(healthConnectManager.permissions)
     * ```
     */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract(PROVIDER_PACKAGE)

    /** True when every permission in [permissions] has already been granted. */
    suspend fun hasAllPermissions(): Boolean {
        val client = clientOrNull() ?: return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    /** The subset of [permissions] still missing (empty == fully granted). */
    suspend fun missingPermissions(): Set<String> {
        val client = clientOrNull() ?: return permissions
        val granted = client.permissionController.getGrantedPermissions()
        return permissions - granted
    }

    // ---- Writing --------------------------------------------------------------------------

    /**
     * Write a single [measurement] to Health Connect. Maps it to one or more records (weight +
     * whatever composition fields are present) and upserts them by clientRecordId, so calling
     * this again with the same measurement id overwrites rather than duplicates.
     *
     * @return the record ids assigned by Health Connect (one per written record).
     * @throws IllegalStateException if Health Connect is unavailable.
     * @throws SecurityException if the required write permissions are not granted.
     */
    suspend fun writeMeasurement(measurement: Measurement): List<String> =
        writeRecords(mapper.toRecords(measurement))

    /**
     * Write many measurements in a single batch (used by Settings "backfill all"). Returns all
     * assigned record ids. An empty input is a no-op.
     */
    suspend fun writeAll(measurements: Collection<Measurement>): List<String> {
        if (measurements.isEmpty()) return emptyList()
        return writeRecords(mapper.toRecords(measurements))
    }

    private suspend fun writeRecords(records: List<Record>): List<String> {
        if (records.isEmpty()) return emptyList()
        val response = requireClient().insertRecords(records)
        return response.recordIdsList
    }

    // ---- Install / update routing ---------------------------------------------------------

    /**
     * An [Intent] that sends the user to install or update the Health Connect provider. On
     * API < 34 (and whenever the provider is missing/outdated) this opens the Play Store listing
     * with the onboarding referrer; the caller should `startActivity` it.
     */
    fun installOrUpdateIntent(): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setPackage(PLAY_STORE_PACKAGE)
            data = Uri.parse(
                "market://details" +
                    "?id=$PROVIDER_PACKAGE" +
                    "&url=healthconnect%3A%2F%2Fonboarding"
            )
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        }

    /** Send the user straight to the install/update flow. */
    fun launchInstallOrUpdate() {
        context.startActivity(
            installOrUpdateIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * An [Intent] to open Health Connect's own settings/management screen (e.g. so the user can
     * review or revoke the app's data permissions). Null if the SDK is unavailable.
     */
    fun manageDataIntent(): Intent? =
        if (isAvailable()) {
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        } else {
            null
        }

    companion object {
        /** The Health Connect provider package (the standalone app on API < 34). */
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
