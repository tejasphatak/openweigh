package io.github.openweigh.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.UserProfile
import io.github.openweigh.ble.protocol.ProtocolRegistry
import io.github.openweigh.ble.protocol.ScaleProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a single GATT connection to a scale and emits decoded [ScaleReading]s as a cold [Flow].
 *
 * Lifecycle per [connect] collection:
 *  1. `connectGatt` to the device address.
 *  2. on connect -> `discoverServices`.
 *  3. resolve a [ScaleProtocol] from the discovered services via [ProtocolRegistry].
 *  4. enable notifications/indications (write CCCD) on each characteristic the protocol wants,
 *     one at a time (Android serializes GATT ops; we queue CCCD writes).
 *  5. forward each notification through [ScaleProtocol.decode], enriching with [BodyMetricsEstimator]
 *     when the device omits composition fields, then emit.
 *  6. on collector cancel (or disconnect) -> disconnect + `close()` the GATT to free resources.
 */
@Singleton
class ScaleConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val protocolRegistry: ProtocolRegistry,
    private val estimator: BodyMetricsEstimator
) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    /**
     * Connect to [address] and stream readings. [profile] is passed to the decoder/estimator for
     * fields that need demographics. The flow completes (with an error) on connection failure or
     * when no protocol matches the device.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(address: String, profile: UserProfile?): Flow<ScaleReading> = callbackFlow {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            close(IllegalStateException("Bluetooth adapter unavailable"))
            return@callbackFlow
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            close(IllegalArgumentException("Invalid device address: $address"))
            return@callbackFlow
        }

        var gatt: BluetoothGatt? = null
        var protocol: ScaleProtocol? = null
        // CCCD write queue: GATT permits only one outstanding descriptor write at a time.
        val cccdQueue = ArrayDeque<BluetoothGattCharacteristic>()

        fun writeNextCccd(g: BluetoothGatt) {
            val ch = cccdQueue.poll() ?: return
            val descriptor = ch.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                // No CCCD on this characteristic; skip to the next.
                writeNextCccd(g)
                return
            }
            val indication = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val enableValue = if (indication) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            g.setCharacteristicNotification(ch, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, enableValue)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enableValue
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }

        fun handlePayload(charUuid: UUID, value: ByteArray) {
            val p = protocol ?: return
            val readings = runCatching { p.decode(charUuid, value, profile) }.getOrDefault(emptyList())
            readings.forEach { reading ->
                val enriched = estimator.enrich(reading, profile)
                    .copy(sourceDevice = reading.sourceDevice ?: address)
                trySend(enriched)
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            g.discoverServices()
                        } else {
                            close(IllegalStateException("Connect failed: status=$status"))
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        close(IllegalStateException("Disconnected: status=$status"))
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    close(IllegalStateException("Service discovery failed: status=$status"))
                    return
                }
                val serviceUuids = g.services.map { it.uuid }
                val resolved = protocolRegistry.resolve(deviceName(g), serviceUuids)
                if (resolved == null) {
                    close(IllegalStateException("No protocol matches device services"))
                    return
                }
                protocol = resolved

                // Locate every wanted characteristic across all services and queue CCCD writes.
                val wanted = resolved.characteristicsToSubscribe().toSet()
                g.services.forEach { service ->
                    service.characteristics.forEach { ch ->
                        if (ch.uuid in wanted) cccdQueue.add(ch)
                    }
                }
                if (cccdQueue.isEmpty()) {
                    close(IllegalStateException("Resolved protocol but no matching characteristics found"))
                    return
                }
                writeNextCccd(g)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                // Proceed to the next subscription regardless; one failure shouldn't block others.
                writeNextCccd(g)
            }

            // Android 13+ payload-carrying callback.
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handlePayload(characteristic.uuid, value)
            }

            // Pre-Android-13 callback (value read from the characteristic object).
            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                val value = characteristic.value ?: return
                handlePayload(characteristic.uuid, value)
            }

            private fun deviceName(g: BluetoothGatt): String? =
                runCatching { g.device?.name }.getOrNull()
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDeviceTransportLe)
        } else {
            device.connectGatt(context, false, callback)
        }

        if (gatt == null) {
            close(IllegalStateException("connectGatt returned null"))
            return@callbackFlow
        }

        awaitClose {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
        }
    }

    companion object {
        /** Client Characteristic Configuration Descriptor. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** LE-only transport constant (BluetoothDevice.TRANSPORT_LE). */
        private const val BluetoothDeviceTransportLe = 2
    }
}
