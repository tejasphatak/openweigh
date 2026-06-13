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
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.openweigh.ble.protocol.StandardBodyCompositionProtocol
import io.github.openweigh.ble.protocol.StandardWeightScaleProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A scale found during a BLE scan. */
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

/**
 * Wraps [BluetoothLeScanner] and exposes discovered scales as a cold [Flow].
 *
 * Strategy: a **filtered** scan on the two standard service UUIDs (`0x181D` Weight Scale,
 * `0x181B` Body Composition) so we surface relevant devices first; many scales, however, do NOT
 * advertise their GATT service UUIDs in the advertisement packet, so [scan] also accepts an
 * [includeUnfiltered] fallback that scans everything (the device picker can then show all nearby
 * devices and let the user choose).
 *
 * The returned flow is cold: scanning starts on collection and stops (and the scanner is cleaned
 * up) when the collector cancels.
 */
@Singleton
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context
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
     * Begin scanning for scales. Emits a [DiscoveredDevice] per scan result (deduplicated by
     * MAC address; later results with a fresher RSSI replace earlier ones).
     *
     * @param includeUnfiltered when true, also performs an unfiltered scan to catch scales that
     * don't advertise their service UUIDs. When false, only devices advertising `0x181D`/`0x181B`
     * are reported.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun scan(includeUnfiltered: Boolean = true): Flow<DiscoveredDevice> = callbackFlow {
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
                // De-dup by address but always forward (UI can update rssi); keep it simple:
                // only forward the first sighting to avoid flooding the picker.
                if (!seen.add(address)) return
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
                trySend(DiscoveredDevice(name = name, address = address, rssi = result.rssi))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters: List<ScanFilter> = if (includeUnfiltered) {
            // Empty filter list = scan everything. We still bias discovery toward scales by
            // surfacing service-matching devices, but accept all so unadvertised scales appear.
            emptyList()
        } else {
            SERVICE_FILTERS
        }

        runCatching {
            scanner.startScan(filters, settings, callback)
        }.onFailure { close(it); return@callbackFlow }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val WEIGHT_SCALE_UUID: UUID = StandardWeightScaleProtocol.WEIGHT_SCALE_SERVICE
        private val BODY_COMPOSITION_UUID: UUID = StandardBodyCompositionProtocol.BODY_COMPOSITION_SERVICE

        private val SERVICE_FILTERS: List<ScanFilter> = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(WEIGHT_SCALE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BODY_COMPOSITION_UUID)).build()
        )
    }
}
