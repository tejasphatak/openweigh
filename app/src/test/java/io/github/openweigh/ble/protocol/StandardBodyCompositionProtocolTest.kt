package io.github.openweigh.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [StandardBodyCompositionProtocol] (Body Composition Measurement char 0x2A9C).
 *
 * Packet header is: Flags (uint16 LE) then Body Fat % (uint16 *0.1, always present). Each present
 * optional field follows in spec order. A fresh protocol instance is used per test because the
 * decoder carries cross-fragment accumulator state.
 */
class StandardBodyCompositionProtocolTest {

    private val char = StandardBodyCompositionProtocol.BODY_COMPOSITION_MEASUREMENT_CHAR

    private fun u16le(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun newProto() = StandardBodyCompositionProtocol()

    @Test
    fun matches_onBodyCompositionService() {
        assertTrue(
            newProto().matches(null, listOf(StandardBodyCompositionProtocol.BODY_COMPOSITION_SERVICE))
        )
        assertTrue(newProto().matches(null, emptyList()).not())
    }

    @Test
    fun subscribesToTheMeasurementChar() {
        assertEquals(listOf(char), newProto().characteristicsToSubscribe())
    }

    @Test
    fun decode_bodyFatAndWeight() {
        // flags: weight present (bit10) = 0x0400. Body fat 25.0% -> 250. Weight 80.000 kg -> 16000.
        val value = u16le(0x0400) + u16le(250) + u16le(16000)
        val r = newProto().decode(char, value, null)
        assertEquals(1, r.size)
        assertEquals(25.0, r[0].bodyFatPercent!!, 1e-9)
        assertEquals(80.0, r[0].weightKg, 1e-9)
    }

    @Test
    fun decode_leanWaterBasalImpedance() {
        // flags: basal(bit3 0x08) | fatFree(bit6 0x40) | bodyWater(bit8 0x100) |
        //        impedance(bit9 0x200) | weight(bit10 0x400) = 0x0748.
        // body fat 20.0% -> 200
        // basal kJ 6276 -> kcal = round(6276/4.184) = 1500
        // fat-free mass 12000 * 0.005 = 60.0 kg
        // body water 8000 * 0.005 = 40.0 kg
        // impedance 5000 * 0.1 = 500.0 ohm
        // weight 16000 * 0.005 = 80.0 kg
        val value = u16le(0x0748) +
            u16le(200) +    // body fat
            u16le(6276) +   // basal kJ
            u16le(12000) +  // fat free mass
            u16le(8000) +   // body water
            u16le(5000) +   // impedance
            u16le(16000)    // weight
        val r = newProto().decode(char, value, null)
        assertEquals(1, r.size)
        val reading = r[0]
        assertEquals(20.0, reading.bodyFatPercent!!, 1e-9)
        assertEquals(1500, reading.basalMetabolismKcal)
        assertEquals(60.0, reading.leanMassKg!!, 1e-9)
        assertEquals(40.0, reading.bodyWaterMassKg!!, 1e-9)
        assertEquals(500.0, reading.impedanceOhm!!, 1e-9)
        assertEquals(80.0, reading.weightKg, 1e-9)
    }

    @Test
    fun decode_noWeight_yieldsEmpty() {
        // Body fat present, no weight flag -> impl cannot build a primary reading.
        val value = u16le(0x0000) + u16le(250)
        assertTrue(newProto().decode(char, value, null).isEmpty())
    }

    @Test
    fun decode_sentinelsYieldNullFields() {
        // flags: fatFree | weight = 0x0440. Body fat 0xFFFF -> null. fat-free 0xFFFF -> null.
        // weight present and valid so a reading is still emitted.
        val value = u16le(0x0440) +
            u16le(0xFFFF) + // body fat unknown
            u16le(0xFFFF) + // fat-free unknown
            u16le(16000)    // weight 80.0
        val r = newProto().decode(char, value, null)
        assertEquals(1, r.size)
        assertNull(r[0].bodyFatPercent)
        assertNull(r[0].leanMassKg)
        assertEquals(80.0, r[0].weightKg, 1e-9)
    }

    @Test
    fun decode_multiPacket_mergesAcrossFragments() {
        val proto = newProto()
        // Fragment 1: continuation(bit12 0x1000) + fatFree(bit6 0x40). No weight yet.
        // body fat 20.0% -> 200, fat-free 60.0 kg -> 12000.
        val frag1 = u16le(0x1000 or 0x0040) + u16le(200) + u16le(12000)
        assertTrue(proto.decode(char, frag1, null).isEmpty()) // continuation -> nothing yet

        // Fragment 2 (final, no continuation): weight(bit10 0x400). body fat 0xFFFF (already have it).
        // weight 80.0 kg -> 16000.
        val frag2 = u16le(0x0400) + u16le(0xFFFF) + u16le(16000)
        val r = proto.decode(char, frag2, null)
        assertEquals(1, r.size)
        // Merged: lean mass from frag1, weight from frag2, body fat from frag1.
        assertNotNull(r[0].leanMassKg)
        assertEquals(60.0, r[0].leanMassKg!!, 1e-9)
        assertEquals(80.0, r[0].weightKg, 1e-9)
        assertEquals(20.0, r[0].bodyFatPercent!!, 1e-9)
    }

    @Test
    fun decode_tooShortOrWrongChar_yieldsEmpty() {
        assertTrue(newProto().decode(char, byteArrayOf(0x00, 0x00, 0x00), null).isEmpty())
        val other = StandardWeightScaleProtocol.WEIGHT_MEASUREMENT_CHAR
        assertTrue(newProto().decode(other, u16le(0x0400) + u16le(250) + u16le(16000), null).isEmpty())
    }
}
