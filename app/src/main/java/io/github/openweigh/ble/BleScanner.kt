package io.github.openweigh.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.openweigh.ble.protocol.ProtocolRegistry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A device found during a BLE scan.
 *
 * [isLikelyScale] is true when a registered [io.github.openweigh.ble.protocol.ScaleProtocol]
 * recognized this device from its advertisement (standard `0x181D`/`0x181B` service, or a
 * proprietary name/manufacturer match); [matchedProtocolId] names that protocol. The picker uses
 * these to label and rank scales above generic devices.
 */
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<UUID> = emptyList(),
    val isLikelyScale: Boolean = false,
    val matchedProtocolId: String? = null,
)

/**
 * Wraps [BluetoothLeScanner] and exposes discovered scales as a cold [Flow].
 *
 * BLE scales are inconsistent advertisers: standard ones advertise the Weight Scale (`0x181D`) /
 * Body Composition (`0x181B`) service UUID, but many proprietary scales (Xiaomi, Yunmai, …) don't
 * advertise any service UUID and only expose it after connecting. So we scan **unfiltered** at the
 * radio level and classify each result in software via the [ProtocolRegistry]: a device is a
 * "likely scale" if any protocol recognizes its advertisement.
 *
 * By default the flow hides nameless/unrecognized devices (the "unknown" flood) and surfaces only
 * recognized scales plus named devices. Pass `showAll = true` to surface everything as an escape
 * hatch for an unadvertised scale not yet matched by a protocol.
 *
 * The returned flow is cold: scanning starts on collection and stops (scanner cleaned up) on cancel.
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val protocolRegistry: ProtocolRegistry,
) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    /** True if Bluetooth hardware is present and currently enabled. */
    val isBluetoothEnabled: Boolean
        get() = bluetoothManager?.adapter?.isEnabled == true

    /** True if the runtime permissions needed to scan have been granted. */
    val hasScanPermission: Boolean
        get() = hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    /**
     * Begin scanning. Emits a [DiscoveredDevice] per device (deduplicated by MAC address).
     *
     * @param showAll when false (default), only recognized scales and named devices are reported,
     * hiding the nameless "unknown" devices. When true, every nearby device is reported.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun scan(showAll: Boolean = false): Flow<DiscoveredDevice> = callbackFlow {
        val scanner: BluetoothLeScanner? = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner unavailable (adapter off or absent)"))
            return@callbackFlow
        }

        val seen = HashSet<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emit(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { emit(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: code=$errorCode"))
            }

            private fun emit(result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                val scanRecord = result.scanRecord
                val name = scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
                val serviceUuids = scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                val manufacturerIds = manufacturerCompanyIds(result)

                val matchedId = protocolRegistry.recognize(name, serviceUuids, manufacturerIds)
                val likelyScale = matchedId != null

                // Hide the nameless/unrecognized "unknown" flood unless the user asked for all.
                if (!showAll && !likelyScale && name.isNullOrBlank()) return

                // Forward the first sighting only (avoid flooding the picker). Not adding to `seen`
                // until we actually forward means a device that first appears nameless can still be
                // surfaced once it advertises a name / is recognized.
                if (!seen.add(address)) return

                trySend(
                    DiscoveredDevice(
                        name = name,
                        address = address,
                        rssi = result.rssi,
                        serviceUuids = serviceUuids,
                        isLikelyScale = likelyScale,
                        matchedProtocolId = matchedId,
                    )
                )
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Empty filter list = scan everything; classification/filtering happens in software so that
        // proprietary scales (which don't advertise a service UUID) still appear.
        runCatching {
            scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        }.onFailure { close(it); return@callbackFlow }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /** Company IDs from the advertisement's manufacturer-specific data (used for proprietary matching). */
    private fun manufacturerCompanyIds(result: ScanResult): List<Int> {
        val msd = result.scanRecord?.manufacturerSpecificData ?: return emptyList()
        return buildList { for (i in 0 until msd.size()) add(msd.keyAt(i)) }
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
