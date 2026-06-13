package io.github.openweigh.ble.protocol

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.UserProfile
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Bluetooth SIG **Body Composition Service** (`0x181B`) decoder for the
 * **Body Composition Measurement** characteristic (`0x2A9C`).
 *
 * Packet layout (little-endian) per the SIG Body Composition Service spec:
 * ```
 * [0..1]  Flags (uint16, LE)
 *           bit0  : measurement unit            0 = SI (kg/m), 1 = Imperial (lb/in)
 *           bit1  : timestamp present
 *           bit2  : user id present
 *           bit3  : basal metabolism present
 *           bit4  : muscle percentage present
 *           bit5  : muscle mass present
 *           bit6  : fat free mass present
 *           bit7  : soft lean mass present
 *           bit8  : body water mass present
 *           bit9  : impedance present
 *           bit10 : weight present
 *           bit11 : height present
 *           bit12 : multiple packet measurement (continuation)  <-- multi-packet flag
 * [2..3]  Body Fat Percentage (uint16, *0.1 %)   -- ALWAYS present
 * then, in this exact order, each present only if its flag is set:
 *   if bit1 : Timestamp (7-byte Date Time)
 *   if bit2 : User ID (uint8)
 *   if bit3 : Basal Metabolism (uint16, kJ)            -> kcal = kJ / 4.184
 *   if bit4 : Muscle Percentage (uint16, *0.1 %)
 *   if bit5 : Muscle Mass (uint16; SI *0.005 kg, Imp *0.01 lb)
 *   if bit6 : Fat Free Mass (uint16; SI *0.005 kg, Imp *0.01 lb)   -> lean mass
 *   if bit7 : Soft Lean Mass (uint16; SI *0.005 kg, Imp *0.01 lb)
 *   if bit8 : Body Water Mass (uint16; SI *0.005 kg, Imp *0.01 lb)
 *   if bit9 : Impedance (uint16, *0.1 ohm)
 *   if bit10: Weight (uint16; SI *0.005 kg, Imp *0.01 lb)
 *   if bit11: Height (uint16; SI *0.001 m, Imp *0.1 in)
 * ```
 *
 * **Multi-packet handling:** Some scales split one logical measurement across two notifications.
 * When `bit12` (multiple-packet) is set, every fragment carries the same Flags + Body Fat % header
 * and a *disjoint* subset of the optional fields. We accumulate fragments keyed by the User ID (or
 * a singleton key when no user id is present) and only emit a [ScaleReading] once a fragment WITHOUT
 * the continuation bit arrives, merging all accumulated fields.
 */
class StandardBodyCompositionProtocol @Inject constructor() : ScaleProtocol {

    override val id: String = "bt-sig-body-composition"

    /**
     * Per-user accumulator of partial fields across multi-packet measurements.
     * Keyed by user id (0xFF / -1 when absent).
     */
    private val pending = HashMap<Int, MutableReading>()

    override fun matches(deviceName: String?, advertisedServices: List<UUID>): Boolean =
        advertisedServices.any { it == BODY_COMPOSITION_SERVICE }

    override fun characteristicsToSubscribe(): List<UUID> = listOf(BODY_COMPOSITION_MEASUREMENT_CHAR)

    override fun decode(
        characteristic: UUID,
        value: ByteArray,
        profile: UserProfile?
    ): List<ScaleReading> {
        if (characteristic != BODY_COMPOSITION_MEASUREMENT_CHAR) return emptyList()
        if (value.size < 4) return emptyList()

        val r = LeReader(value)
        val flags = r.u16()

        val imperial = flags and (1 shl 0) != 0
        val hasTimestamp = flags and (1 shl 1) != 0
        val hasUserId = flags and (1 shl 2) != 0
        val hasBasal = flags and (1 shl 3) != 0
        val hasMusclePct = flags and (1 shl 4) != 0
        val hasMuscleMass = flags and (1 shl 5) != 0
        val hasFatFree = flags and (1 shl 6) != 0
        val hasSoftLean = flags and (1 shl 7) != 0
        val hasBodyWater = flags and (1 shl 8) != 0
        val hasImpedance = flags and (1 shl 9) != 0
        val hasWeight = flags and (1 shl 10) != 0
        val hasHeight = flags and (1 shl 11) != 0
        val multiPacket = flags and (1 shl 12) != 0

        val massResolution = if (imperial) LB_RESOLUTION * LB_TO_KG else KG_RESOLUTION

        // Body Fat Percentage — mandatory. 0xFFFF means unknown/unavailable.
        val rawFat = r.u16()
        val bodyFat = if (rawFat == 0xFFFF) null else rawFat * PERCENT_RESOLUTION

        var timestamp: Instant? = null
        if (hasTimestamp) timestamp = r.dateTime()

        val userId = if (hasUserId) r.u8() else -1

        var basalKcal: Int? = null
        if (hasBasal) {
            val kj = r.u16()
            if (kj != 0xFFFF) basalKcal = Math.round(kj / KJ_PER_KCAL).toInt()
        }
        if (hasMusclePct) r.u16() // muscle percentage — not a stored field

        // muscle mass — not a stored ScaleReading field; consume to stay aligned.
        if (hasMuscleMass) r.u16()

        var leanMass: Double? = null
        if (hasFatFree) {
            val raw = r.u16()
            if (raw != 0xFFFF) leanMass = raw * massResolution
        }
        if (hasSoftLean) r.u16() // soft lean mass — consume to stay aligned

        var bodyWater: Double? = null
        if (hasBodyWater) {
            val raw = r.u16()
            if (raw != 0xFFFF) bodyWater = raw * massResolution
        }

        var impedance: Double? = null
        if (hasImpedance) {
            val raw = r.u16()
            if (raw != 0xFFFF) impedance = raw * PERCENT_RESOLUTION // *0.1 ohm
        }

        var weight: Double? = null
        if (hasWeight) {
            val raw = r.u16()
            if (raw != 0xFFFF) weight = raw * massResolution
        }
        if (hasHeight) r.u16() // height — derived/profile, consume to stay aligned

        // Merge into the per-user accumulator.
        val acc = pending.getOrPut(userId) { MutableReading() }
        acc.merge(
            timestamp = timestamp,
            bodyFat = bodyFat,
            leanMass = leanMass,
            bodyWater = bodyWater,
            impedance = impedance,
            weight = weight,
            basalKcal = basalKcal
        )

        if (multiPacket) {
            // Continuation expected — wait for the final (non-continuation) fragment.
            return emptyList()
        }

        pending.remove(userId)
        return acc.toReadingOrEmpty()
    }

    /** Mutable cross-fragment accumulator. Later non-null values win. */
    private class MutableReading {
        var timestamp: Instant? = null
        var bodyFat: Double? = null
        var leanMass: Double? = null
        var bodyWater: Double? = null
        var impedance: Double? = null
        var weight: Double? = null
        var basalKcal: Int? = null

        fun merge(
            timestamp: Instant?,
            bodyFat: Double?,
            leanMass: Double?,
            bodyWater: Double?,
            impedance: Double?,
            weight: Double?,
            basalKcal: Int?
        ) {
            timestamp?.let { this.timestamp = it }
            bodyFat?.let { this.bodyFat = it }
            leanMass?.let { this.leanMass = it }
            bodyWater?.let { this.bodyWater = it }
            impedance?.let { this.impedance = it }
            weight?.let { this.weight = it }
            basalKcal?.let { this.basalKcal = it }
        }

        fun toReadingOrEmpty(): List<ScaleReading> {
            // Body Composition Measurement may not carry weight; without a weight we cannot
            // produce a primary reading on its own (weight comes from 0x2A9D or this packet).
            val w = weight ?: return emptyList()
            return listOf(
                ScaleReading(
                    timestamp = timestamp ?: Instant.now(),
                    weightKg = w,
                    bodyFatPercent = bodyFat,
                    leanMassKg = leanMass,
                    bodyWaterMassKg = bodyWater,
                    basalMetabolismKcal = basalKcal,
                    impedanceOhm = impedance
                )
            )
        }
    }

    companion object {
        val BODY_COMPOSITION_SERVICE: UUID = uuid16(0x181B)
        val BODY_COMPOSITION_MEASUREMENT_CHAR: UUID = uuid16(0x2A9C)

        private const val KG_RESOLUTION = 0.005
        private const val LB_RESOLUTION = 0.01
        private const val LB_TO_KG = 0.45359237
        private const val PERCENT_RESOLUTION = 0.1
        private const val KJ_PER_KCAL = 4.184
    }
}
