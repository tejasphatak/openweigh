package io.github.openweigh.drive

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.data.repo.Measurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM unit tests for [BackupSerializer] — CSV round-trip ONLY.
 *
 * The JSON snapshot path uses `org.json`, whose JVM unit-test stub throws on every method, so it
 * is intentionally NOT tested here (it requires Robolectric/instrumentation). CSV is pure Kotlin.
 */
class BackupSerializerTest {

    private val serializer = BackupSerializer()

    private fun measurement(
        id: String,
        epochMillis: Long,
        weightKg: Double,
        bodyFat: Double? = null,
        lean: Double? = null,
        water: Double? = null,
        bone: Double? = null,
        basal: Int? = null,
        impedance: Double? = null,
        source: String? = null,
        estimated: Boolean = false
    ) = Measurement(
        id = id,
        epochMillis = epochMillis,
        reading = ScaleReading(
            timestamp = Instant.ofEpochMilli(epochMillis),
            weightKg = weightKg,
            bodyFatPercent = bodyFat,
            leanMassKg = lean,
            bodyWaterMassKg = water,
            boneMassKg = bone,
            basalMetabolismKcal = basal,
            impedanceOhm = impedance,
            sourceDevice = source,
            estimated = estimated
        )
    )

    @Test
    fun csv_roundTripsAllFields() {
        val input = listOf(
            measurement(
                id = "id-001",
                epochMillis = 1_700_000_000_000L,
                weightKg = 80.5,
                bodyFat = 18.2,
                lean = 65.0,
                water = 45.5,
                bone = 3.1,
                basal = 1680,
                impedance = 512.0,
                source = "MyScale",
                estimated = true
            ),
            // A weight-only measurement: every optional field blank -> must stay null.
            measurement(
                id = "id-002",
                epochMillis = 1_700_000_600_000L,
                weightKg = 72.0
            )
        )

        val csv = serializer.toCsv(input)
        val out = serializer.fromCsv(csv)

        assertEquals(2, out.size)

        val first = out[0]
        assertEquals("id-001", first.id)
        assertEquals(1_700_000_000_000L, first.epochMillis)
        assertEquals(80.5, first.reading.weightKg, 1e-9)
        assertEquals(18.2, first.reading.bodyFatPercent!!, 1e-9)
        assertEquals(65.0, first.reading.leanMassKg!!, 1e-9)
        assertEquals(45.5, first.reading.bodyWaterMassKg!!, 1e-9)
        assertEquals(3.1, first.reading.boneMassKg!!, 1e-9)
        assertEquals(1680, first.reading.basalMetabolismKcal)
        assertEquals(512.0, first.reading.impedanceOhm!!, 1e-9)
        assertEquals("MyScale", first.reading.sourceDevice)
        assertTrue(first.reading.estimated)

        val second = out[1]
        assertEquals("id-002", second.id)
        assertEquals(72.0, second.reading.weightKg, 1e-9)
        assertNull(second.reading.bodyFatPercent)
        assertNull(second.reading.leanMassKg)
        assertNull(second.reading.bodyWaterMassKg)
        assertNull(second.reading.boneMassKg)
        assertNull(second.reading.basalMetabolismKcal)
        assertNull(second.reading.impedanceOhm)
        assertNull(second.reading.sourceDevice)
        assertTrue(second.reading.estimated.not())
    }

    @Test
    fun csv_quotesFieldsWithCommas() {
        // A source name containing a comma must survive the RFC-4180 quote round-trip.
        val input = listOf(
            measurement(id = "id-q", epochMillis = 1_700_000_000_000L, weightKg = 70.0, source = "Scale, Model X")
        )
        val out = serializer.fromCsv(serializer.toCsv(input))
        assertEquals(1, out.size)
        assertEquals("Scale, Model X", out[0].reading.sourceDevice)
    }

    @Test
    fun csv_emptyList_yieldsHeaderOnly_andParsesBackToEmpty() {
        val csv = serializer.toCsv(emptyList())
        assertTrue(csv.startsWith(BackupSerializer.CSV_HEADER))
        assertTrue(serializer.fromCsv(csv).isEmpty())
    }

    @Test
    fun csv_skipsRowsMissingIdOrWeight() {
        val header = BackupSerializer.CSV_HEADER
        // Row with blank id, and a row with non-numeric weight; both must be dropped.
        val csv = buildString {
            append(header).append("\r\n")
            append(",2023-11-14T22:13:20Z,1700000000000,80.0,,,,,,,,false").append("\r\n")
            append("id-x,2023-11-14T22:13:20Z,1700000000000,notanumber,,,,,,,,false").append("\r\n")
            append("id-ok,2023-11-14T22:13:20Z,1700000000000,75.0,,,,,,,,false").append("\r\n")
        }
        val out = serializer.fromCsv(csv)
        assertEquals(1, out.size)
        assertEquals("id-ok", out[0].id)
        assertEquals(75.0, out[0].reading.weightKg, 1e-9)
    }
}
