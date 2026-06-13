package io.github.openweigh.ble

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.Sex
import io.github.openweigh.ble.model.UserProfile
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Fills in body-composition fields the scale did not report, using weight, (optional) impedance,
 * and the user's [UserProfile]. Any reading that gains a derived field is returned with
 * [ScaleReading.estimated] = true.
 *
 * These are population-regression estimates (BIA / anthropometric), NOT medical measurements:
 *  - With impedance: a Kyle/Janssen-style bioelectrical-impedance regression for fat-free mass.
 *  - Without impedance: the Deurenberg body-fat equation from BMI, age and sex.
 *
 * The estimator never overwrites a value the device already provided; it only fills nulls.
 */
@Singleton
class BodyMetricsEstimator @Inject constructor() {

    /**
     * @return a reading with missing composition fields estimated where possible. If [profile] is
     * null or nothing can be derived, the input is returned unchanged.
     */
    fun enrich(reading: ScaleReading, profile: UserProfile?): ScaleReading {
        if (profile == null) return reading
        val heightM = profile.heightCm / 100.0
        if (heightM <= 0.0 || reading.weightKg <= 0.0) return reading

        val age = ageYears(profile.birthDate)
        val sexFactor = if (profile.sex == Sex.MALE) 1 else 0

        // Fat-free mass: prefer an impedance regression when impedance is available.
        var leanMass = reading.leanMassKg
        if (leanMass == null) {
            leanMass = reading.impedanceOhm?.let { impedance ->
                estimateFatFreeMassBia(
                    heightCm = profile.heightCm,
                    weightKg = reading.weightKg,
                    impedanceOhm = impedance,
                    age = age,
                    sexMale = profile.sex == Sex.MALE
                )
            }
        }

        // Body fat %: derive from lean mass if we have it, else from the Deurenberg BMI equation.
        var bodyFat = reading.bodyFatPercent
        if (bodyFat == null) {
            bodyFat = if (leanMass != null && leanMass < reading.weightKg) {
                ((reading.weightKg - leanMass) / reading.weightKg) * 100.0
            } else {
                val bmi = reading.weightKg / (heightM * heightM)
                // Deurenberg (1991): BF% = 1.20*BMI + 0.23*age - 10.8*sex - 5.4
                1.20 * bmi + 0.23 * age - 10.8 * sexFactor - 5.4
            }
        }

        // If we have body fat but no lean mass, back out lean mass.
        if (leanMass == null && bodyFat != null) {
            leanMass = reading.weightKg * (1.0 - bodyFat / 100.0)
        }

        // Total body water: Watson formula (liters ~= kg of water).
        var bodyWater = reading.bodyWaterMassKg
        if (bodyWater == null) {
            bodyWater = estimateBodyWater(
                heightCm = profile.heightCm,
                weightKg = reading.weightKg,
                age = age,
                sexMale = profile.sex == Sex.MALE
            )
        }

        val anyDerived = (bodyFat != null && reading.bodyFatPercent == null) ||
            (leanMass != null && reading.leanMassKg == null) ||
            (bodyWater != null && reading.bodyWaterMassKg == null)

        if (!anyDerived) return reading

        return reading.copy(
            bodyFatPercent = bodyFat?.let { clampPercent(it) } ?: reading.bodyFatPercent,
            leanMassKg = leanMass?.let { roundKg(it) } ?: reading.leanMassKg,
            bodyWaterMassKg = bodyWater?.let { roundKg(it) } ?: reading.bodyWaterMassKg,
            estimated = true
        )
    }

    /**
     * Fat-free mass from single-frequency BIA (Kyle et al., 2001, adult validation):
     * FFM = -4.104 + 0.518*H^2/R + 0.231*W + 0.130*X + 4.229*sex
     * where reactance X is unknown from a 2-lead scale, so it is dropped (its term is small),
     * H in cm, R in ohms, W in kg, sex = 1 male / 0 female.
     */
    private fun estimateFatFreeMassBia(
        heightCm: Double,
        weightKg: Double,
        impedanceOhm: Double,
        age: Int,
        sexMale: Boolean
    ): Double {
        if (impedanceOhm <= 0.0) return weightKg * if (sexMale) 0.80 else 0.74
        val sex = if (sexMale) 1.0 else 0.0
        val ffm = -4.104 +
            0.518 * (heightCm * heightCm) / impedanceOhm +
            0.231 * weightKg +
            4.229 * sex
        // Guard against nonsense; FFM can't exceed weight.
        return ffm.coerceIn(weightKg * 0.40, weightKg * 0.95)
    }

    /**
     * Total body water (Watson formula), liters (~= kg):
     * Male:   2.447 - 0.09516*age + 0.1074*height(cm) + 0.3362*weight(kg)
     * Female: -2.097 + 0.1069*height(cm) + 0.2466*weight(kg)
     */
    private fun estimateBodyWater(
        heightCm: Double,
        weightKg: Double,
        age: Int,
        sexMale: Boolean
    ): Double = if (sexMale) {
        2.447 - 0.09516 * age + 0.1074 * heightCm + 0.3362 * weightKg
    } else {
        -2.097 + 0.1069 * heightCm + 0.2466 * weightKg
    }

    private fun ageYears(birthDate: LocalDate): Int =
        Period.between(birthDate, LocalDate.now()).years.coerceIn(0, 120)

    private fun clampPercent(v: Double): Double = (v.coerceIn(2.0, 75.0) * 10).roundToInt() / 10.0

    private fun roundKg(v: Double): Double = (v * 100).roundToInt() / 100.0
}
