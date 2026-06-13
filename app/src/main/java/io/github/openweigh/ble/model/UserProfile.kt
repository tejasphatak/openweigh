package io.github.openweigh.ble.model

import java.time.LocalDate

/** Biological sex, used for body-composition derivation and Health Connect records. */
enum class Sex { MALE, FEMALE }

/**
 * The user's static profile, used to derive metrics that require demographics (e.g. BMI,
 * impedance-based body composition) and to populate Health Connect records.
 *
 * @property heightCm height in centimeters.
 * @property sex biological sex.
 * @property birthDate date of birth (age is derived at read time).
 */
data class UserProfile(
    val heightCm: Double,
    val sex: Sex,
    val birthDate: LocalDate
)
