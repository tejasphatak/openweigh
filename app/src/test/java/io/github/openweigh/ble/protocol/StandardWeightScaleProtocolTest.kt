package io.github.openweigh.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * JVM unit tests for [StandardWeightScaleProtocol] (Weight Measurement char 0x2A9D).
 * Builds raw little-endian payloads by hand and asserts on the decoded [io.github.openweigh.ble.model.ScaleReading].
 */
class StandardWeightScaleProtocolTest {

    private val proto = StandardWeightScaleProtocol()
    private val weightChar = StandardWeightScaleProtocol.WEIGHT_MEASUREMENT_CHAR

    @Test
    fun matches_onAdvertisedWeightScaleService() {
        assertTrue(
            proto.matches(
                deviceName = "Any Scale",
                advertisedServices = listOf(StandardWeightScaleProtocol.WEIGHT_SCALE_SERVICE)
            )
        )
        assertFalse(
            proto.matches(
                deviceName = "Any Scale",
                advertisedServices = listOf(uuid16(0x180A)) // Device Information, unrelated
            )
        )
        assertFalse(proto.matches(deviceName = null, advertisedServices = emptyList()))
    }

    @Test
    fun characteristicsToSubscribe_isTheWeightMeasurementChar() {
        assertEquals(listOf(weightChar), proto.characteristicsToSubscribe())
    }

    @Test
    fun decode_siWeight_80kg() {
        // flags=0x00 (SI). 80.000 kg / 0.005 = 16000 = 0x3E80 -> LE bytes 0x80, 0x3E.
        val value = byteArrayOf(0x00, 0x80.toByte(), 0x3E)
        val readings = proto.decode(weightChar, value, profile = null)
        assertEquals(1, readings.size)
        assertEquals(80.0, readings[0].weightKg, 1e-9)
        // No body-composition fields from the plain weight characteristic.
        assertEquals(null, readings[0].bodyFatPercent)
        assertEquals(false, readings[0].estimated)
    }

    @Test
    fun decode_imperialWeight_convertsToKg() {
        // flags=0x01 (imperial). 200.00 lb / 0.01 = 20000 = 0x4E20 -> LE 0x20, 0x4E.
        val value = byteArrayOf(0x01, 0x20, 0x4E)
        val readings = proto.decode(weightChar, value, profile = null)
        assertEquals(1, readings.size)
        // 20000 * 0.01 lb = 200 lb -> * 0.45359237 kg.
        assertEquals(200.0 * 0.45359237, readings[0].weightKg, 1e-6)
    }

    @Test
    fun decode_sentinelWeight_yieldsEmpty() {
        // 0xFFFF "measurement unsuccessful".
        val value = byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte())
        assertTrue(proto.decode(weightChar, value, profile = null).isEmpty())
    }

    @Test
    fun decode_tooShort_yieldsEmpty() {
        assertTrue(proto.decode(weightChar, byteArrayOf(0x00, 0x80.toByte()), null).isEmpty())
        assertTrue(proto.decode(weightChar, byteArrayOf(), null).isEmpty())
    }

    @Test
    fun decode_wrongCharacteristic_yieldsEmpty() {
        val other = UUID.fromString("00002a9c-0000-1000-8000-00805f9b34fb")
        val value = byteArrayOf(0x00, 0x80.toByte(), 0x3E)
        assertTrue(proto.decode(other, value, profile = null).isEmpty())
    }

    @Test
    fun decode_withAllOptionalFields_consumesTrailingBytesWithoutError() {
        // flags = timestamp(0x02) | userId(0x04) | bmi+height(0x08) = 0x0E. SI weight.
        // weight 80.000 kg -> 16000 -> 0x80,0x3E
        // timestamp: year 2026 = 0x07E2 -> LE 0xE2,0x07 ; month 6, day 13, hour 10, min 30, sec 0
        // userId: 1
        // BMI uint16 (0.1): 240 -> 24.0 ; height uint16: 1750
        val value = byteArrayOf(
            0x0E,                       // flags
            0x80.toByte(), 0x3E,        // weight = 16000
            0xE2.toByte(), 0x07,        // year 2026
            0x06, 0x0D, 0x0A, 0x1E, 0x00, // month=6 day=13 hour=10 min=30 sec=0
            0x01,                       // user id
            0xF0.toByte(), 0x00,        // BMI = 240
            0xD6.toByte(), 0x06         // height = 1750
        )
        val readings = proto.decode(weightChar, value, profile = null)
        assertEquals(1, readings.size)
        assertEquals(80.0, readings[0].weightKg, 1e-9)
    }
}
