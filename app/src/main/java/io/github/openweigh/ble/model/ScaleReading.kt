package io.github.openweigh.ble.model

import java.time.Instant

/**
 * A single decoded measurement from a BLE weight/body-composition scale.
 *
 * All body-composition fields are optional: a plain weight-only scale populates just
 * [weightKg]; a body-composition scale fills in whatever the device reports and leaves the
 * rest `null`. Decoders MUST null any optional field that is absent from the GATT packet.
 *
 * @property timestamp when the reading was taken (device clock if provided, otherwise capture time).
 * @property weightKg body weight in kilograms (always present).
 * @property bodyFatPercent body fat as a percentage (0..100), if reported.
 * @property leanMassKg fat-free / lean body mass in kilograms, if reported.
 * @property bodyWaterMassKg total body water mass in kilograms, if reported.
 * @property boneMassKg bone mass in kilograms, if reported.
 * @property basalMetabolismKcal basal metabolic rate in kilocalories per day, if reported.
 * @property impedanceOhm bioelectrical impedance in ohms, if reported.
 * @property sourceDevice human-readable name/address of the originating scale.
 * @property estimated true if any composition field was derived in-app rather than read from the device.
 */
data class ScaleReading(
    val timestamp: Instant,
    val weightKg: Double,
    val bodyFatPercent: Double? = null,
    val leanMassKg: Double? = null,
    val bodyWaterMassKg: Double? = null,
    val boneMassKg: Double? = null,
    val basalMetabolismKcal: Int? = null,
    val impedanceOhm: Double? = null,
    val sourceDevice: String? = null,
    val estimated: Boolean = false
)
