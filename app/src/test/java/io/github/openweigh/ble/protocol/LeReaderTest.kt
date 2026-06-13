package io.github.openweigh.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * JVM unit tests for the file-internal little-endian [LeReader] used by the SIG decoders.
 * Reachable from the test source set because the test lives in the same package.
 */
class LeReaderTest {

    @Test
    fun u8_readsBytesSequentiallyAndMasksSign() {
        val r = LeReader(byteArrayOf(0x01, 0x7F, 0x80.toByte(), 0xFF.toByte()))
        assertEquals(0x01, r.u8())
        assertEquals(0x7F, r.u8())
        assertEquals(0x80, r.u8())
        assertEquals(0xFF, r.u8())
    }

    @Test
    fun u16_littleEndian() {
        // 0x3E80 stored LE as 0x80, 0x3E.
        val r = LeReader(byteArrayOf(0x80.toByte(), 0x3E))
        assertEquals(0x3E80, r.u16())
    }

    @Test
    fun s16_signedLittleEndian() {
        // 0xFFFF -> -1
        assertEquals(-1, LeReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte())).s16())
        // 0x8000 -> -32768
        assertEquals(-32768, LeReader(byteArrayOf(0x00, 0x80.toByte())).s16())
        // 0x7FFF -> 32767
        assertEquals(32767, LeReader(byteArrayOf(0xFF.toByte(), 0x7F)).s16())
    }

    @Test
    fun u24_littleEndian() {
        // 0x123456 stored LE as 0x56, 0x34, 0x12.
        val r = LeReader(byteArrayOf(0x56, 0x34, 0x12))
        assertEquals(0x123456, r.u24())
    }

    @Test
    fun u32_littleEndianUnsigned() {
        // 0xFFFFFFFF -> 4294967295 (must be unsigned in a Long).
        val r = LeReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(4294967295L, r.u32())
        // 0x01020304 stored LE as 0x04,0x03,0x02,0x01.
        val r2 = LeReader(byteArrayOf(0x04, 0x03, 0x02, 0x01))
        assertEquals(0x01020304L, r2.u32())
    }

    @Test
    fun readingPastEnd_returnsZero() {
        val r = LeReader(byteArrayOf(0x05))
        assertEquals(0x05, r.u8())
        assertEquals(0, r.u8())   // past end
        assertEquals(0, r.u16())  // past end
        assertEquals(0L, r.u32()) // past end
        assertEquals(0, r.remaining())
    }

    @Test
    fun dateTime_parsesValidSigDateTime() {
        // year 2026 = 0x07EA -> LE 0xEA,0x07 ; month 6, day 13, hour 10, min 30, sec 15.
        val r = LeReader(byteArrayOf(0xEA.toByte(), 0x07, 0x06, 0x0D, 0x0A, 0x1E, 0x0F))
        val instant = r.dateTime()
        val expected = ZonedDateTime.of(2026, 6, 13, 10, 30, 15, 0, ZoneId.systemDefault()).toInstant()
        assertEquals(expected, instant)
    }

    @Test
    fun dateTime_invalidZeroFields_returnNull() {
        // year 0 -> invalid per spec.
        assertNull(LeReader(byteArrayOf(0x00, 0x00, 0x06, 0x0D, 0x0A, 0x1E, 0x0F)).dateTime())
        // month 0 -> invalid.
        assertNull(LeReader(byteArrayOf(0xE2.toByte(), 0x07, 0x00, 0x0D, 0x0A, 0x1E, 0x0F)).dateTime())
        // day 0 -> invalid.
        assertNull(LeReader(byteArrayOf(0xE2.toByte(), 0x07, 0x06, 0x00, 0x0A, 0x1E, 0x0F)).dateTime())
    }

    @Test
    fun dateTime_outOfRangeFields_returnNull() {
        // month 13 -> LocalDateTime.of throws -> runCatching -> null.
        assertNull(LeReader(byteArrayOf(0xE2.toByte(), 0x07, 0x0D, 0x0D, 0x0A, 0x1E, 0x0F)).dateTime())
    }

    @Test
    fun dateTime_tooShort_returnsNullAndDrainsRemaining() {
        val r = LeReader(byteArrayOf(0xE2.toByte(), 0x07, 0x06))
        assertNull(r.dateTime())
        assertEquals(0, r.remaining())
    }
}
