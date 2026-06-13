package io.github.openweigh.ble

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.Sex
import io.github.openweigh.ble.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * JVM unit tests for [BodyMetricsEstimator]. These assert sanity ranges and the contracts:
 * estimates are finite, plausible, flagged estimated=true, and device-provided values are
 * never overwritten.
 */
class BodyMetricsEstimatorTest {

    private val estimator = BodyMetricsEstimator()

    private fun profile(
        heightCm: Double = 178.0,
        sex: Sex = Sex.MALE,
        age: Int = 35
    ) = UserProfile(
        heightCm = heightCm,
        sex = sex,
        birthDate = LocalDate.now().minusYears(age.toLong())
    )

    private fun reading(
        weightKg: Double = 80.0,
        impedance: Double? = null,
        bodyFat: Double? = null,
        lean: Double? = null,
        water: Double? = null
    ) = ScaleReading(
        timestamp = Instant.now(),
        weightKg = weightKg,
        bodyFatPercent = bodyFat,
        leanMassKg = lean,
        bodyWaterMassKg = water,
        impedanceOhm = impedance
    )

    @Test
    fun nullProfile_returnsInputUnchanged() {
        val r = reading()
        assertTrue(estimator.enrich(r, null) === r)
    }

    @Test
    fun nonPositiveDimensions_returnInputUnchanged() {
        assertTrue(estimator.enrich(reading(weightKg = 0.0), profile()).let { it.estimated }.not())
        val zeroHeight = estimator.enrich(reading(), profile(heightCm = 0.0))
        assertFalse(zeroHeight.estimated)
    }

    @Test
    fun deurenbergPath_noImpedance_estimatesPlausibleFields() {
        val out = estimator.enrich(reading(weightKg = 80.0), profile())
        assertTrue("should be flagged estimated", out.estimated)

        val bf = out.bodyFatPercent
        assertNotNull(bf)
        assertTrue("body fat finite", bf!!.isFinite())
        assertTrue("body fat plausible: $bf", bf in 2.0..75.0)

        val lean = out.leanMassKg
        assertNotNull(lean)
        assertTrue("lean finite", lean!!.isFinite())
        assertTrue("lean below weight: $lean", lean > 0.0 && lean < out.weightKg)

        val water = out.bodyWaterMassKg
        assertNotNull(water)
        assertTrue("water finite", water!!.isFinite())
        assertTrue("water plausible: $water", water > 0.0 && water < out.weightKg)
    }

    @Test
    fun impedancePath_estimatesLeanMassFromBia() {
        val out = estimator.enrich(reading(weightKg = 80.0, impedance = 500.0), profile())
        assertTrue(out.estimated)
        val lean = out.leanMassKg
        assertNotNull(lean)
        // BIA FFM is coerced into [weight*0.40, weight*0.95].
        assertTrue("lean in coerced range: $lean", lean!! in (80.0 * 0.40)..(80.0 * 0.95))
        val bf = out.bodyFatPercent
        assertNotNull(bf)
        assertTrue(bf!! in 2.0..75.0)
    }

    @Test
    fun femalePath_alsoPlausible() {
        val out = estimator.enrich(reading(weightKg = 65.0), profile(heightCm = 165.0, sex = Sex.FEMALE))
        assertTrue(out.estimated)
        assertTrue(out.bodyFatPercent!! in 2.0..75.0)
        assertTrue(out.leanMassKg!! > 0.0 && out.leanMassKg!! < out.weightKg)
        assertTrue(out.bodyWaterMassKg!! > 0.0 && out.bodyWaterMassKg!! < out.weightKg)
    }

    @Test
    fun deviceProvidedValues_areNotOverwritten() {
        val provided = reading(weightKg = 80.0, bodyFat = 18.5, lean = 65.0, water = 45.0)
        val out = estimator.enrich(provided, profile())
        // Nothing left to derive -> returned unchanged, not flagged estimated.
        assertEquals(18.5, out.bodyFatPercent!!, 1e-9)
        assertEquals(65.0, out.leanMassKg!!, 1e-9)
        assertEquals(45.0, out.bodyWaterMassKg!!, 1e-9)
        assertFalse(out.estimated)
    }
}
