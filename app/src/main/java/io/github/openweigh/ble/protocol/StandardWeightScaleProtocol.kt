package io.github.openweigh.ble.protocol

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.UserProfile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Bluetooth SIG **Weight Scale Service** (`0x181D`) decoder for the
 * **Weight Measurement** characteristic (`0x2A9D`).
 *
 * Packet layout (little-endian) per the SIG Weight Scale Service spec:
 * ```
 * [0]      Flags (uint8)
 *            bit0 : measurement unit          0 = SI (kg), 1 = Imperial (lb)
 *            bit1 : timestamp present
 *            bit2 : user id present
 *            bit3 : BMI and height present
 * [1..2]   Weight (uint16, LE)  -> * 0.005 kg  (SI)  or  * 0.01 lb  (Imperial)
 * [..]     if bit1: Timestamp (7 bytes: year u16, month u8, day u8, hour u8, min u8, sec u8)
 * [..]     if bit2: User ID (uint8)
 * [..]     if bit3: BMI (uint16, *0.1)  then Height (uint16; *0.001 m SI, or *0.1 in Imperial)
 * ```
 * A weight value of `0xFFFF` means "measurement unsuccessful / unknown" and yields no reading.
 */
class StandardWeightScaleProtocol @Inject constructor() : ScaleProtocol {

    override val id: String = "bt-sig-weight-scale"

    override fun matches(deviceName: String?, advertisedServices: List<UUID>): Boolean =
        advertisedServices.any { it == WEIGHT_SCALE_SERVICE }

    override fun characteristicsToSubscribe(): List<UUID> = listOf(WEIGHT_MEASUREMENT_CHAR)

    override fun decode(
        characteristic: UUID,
        value: ByteArray,
        profile: UserProfile?
    ): List<ScaleReading> {
        if (characteristic != WEIGHT_MEASUREMENT_CHAR) return emptyList()
        if (value.size < 3) return emptyList()

        val r = LeReader(value)
        val flags = r.u8()
        val imperial = flags and 0x01 != 0
        val hasTimestamp = flags and 0x02 != 0
        val hasUserId = flags and 0x04 != 0
        val hasBmiHeight = flags and 0x08 != 0

        val rawWeight = r.u16()
        if (rawWeight == 0xFFFF) return emptyList()

        val weightKg = if (imperial) {
            rawWeight * LB_RESOLUTION * LB_TO_KG
        } else {
            rawWeight * KG_RESOLUTION
        }

        var timestamp = Instant.now()
        if (hasTimestamp) {
            r.dateTime()?.let { timestamp = it }
        }
        if (hasUserId) r.u8()
        if (hasBmiHeight) {
            // BMI + height are present but BMI is derived in-app, not a stored field; skip.
            r.u16() // BMI
            r.u16() // height
        }

        return listOf(
            ScaleReading(
                timestamp = timestamp,
                weightKg = weightKg
            )
        )
    }

    companion object {
        val WEIGHT_SCALE_SERVICE: UUID = uuid16(0x181D)
        val WEIGHT_MEASUREMENT_CHAR: UUID = uuid16(0x2A9D)

        private const val KG_RESOLUTION = 0.005
        private const val LB_RESOLUTION = 0.01
        private const val LB_TO_KG = 0.45359237
    }
}

/** Little-endian sequential reader over a GATT payload. Returns 0 past the end (caller-guarded). */
internal class LeReader(private val bytes: ByteArray) {
    var pos: Int = 0
        private set

    fun remaining(): Int = bytes.size - pos

    fun u8(): Int {
        if (pos >= bytes.size) return 0
        return bytes[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        val lo = u8()
        val hi = u8()
        return lo or (hi shl 8)
    }

    fun s16(): Int {
        val v = u16()
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    fun u24(): Int {
        val b0 = u8()
        val b1 = u8()
        val b2 = u8()
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    fun u32(): Long {
        val b0 = u8().toLong()
        val b1 = u8().toLong()
        val b2 = u8().toLong()
        val b3 = u8().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /** Reads a 7-byte SIG "Date Time" (org.bluetooth.characteristic.date_time). */
    fun dateTime(): Instant? {
        if (remaining() < 7) {
            // consume whatever is left to keep alignment; cannot build a timestamp
            while (remaining() > 0) u8()
            return null
        }
        val year = u16()
        val month = u8()
        val day = u8()
        val hour = u8()
        val minute = u8()
        val second = u8()
        if (year == 0 || month == 0 || day == 0) return null
        return runCatching {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        }.getOrNull()
    }
}

/** Build a 128-bit UUID from a 16-bit SIG short id using the Bluetooth Base UUID. */
internal fun uuid16(short: Int): UUID =
    UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))
