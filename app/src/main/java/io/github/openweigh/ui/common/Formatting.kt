package io.github.openweigh.ui.common

import io.github.openweigh.ble.model.ScaleReading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Shared, dependency-free formatting helpers used by every screen so numbers/dates read alike. */
object Formatting {

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayHeaderFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())

    fun weight(kg: Double): String = String.format(Locale.US, "%.1f", kg)

    fun percent(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"

    fun kgOrDash(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f kg", it) } ?: "—"

    fun kcalOrDash(value: Int?): String =
        value?.let { "$it kcal" } ?: "—"

    fun ohmsOrDash(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f Ω", it) } ?: "—"

    fun time(instant: Instant): String =
        timeFormatter.format(instant.atZone(ZoneId.systemDefault()))

    fun date(instant: Instant): String =
        dateFormatter.format(instant.atZone(ZoneId.systemDefault()))

    fun dateTime(instant: Instant): String = "${date(instant)} · ${time(instant)}"

    fun localDate(instant: Instant): LocalDate =
        instant.atZone(ZoneId.systemDefault()).toLocalDate()

    /** A friendly day header: "Today", "Yesterday", or the formatted date. */
    fun dayHeader(day: LocalDate): String {
        val today = LocalDate.now()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> dayHeaderFormatter.format(day)
        }
    }

    /** Body Mass Index from a reading + height; null if height is unknown/zero. */
    fun bmi(weightKg: Double, heightCm: Double?): Double? {
        if (heightCm == null || heightCm <= 0.0) return null
        val m = heightCm / 100.0
        return weightKg / (m * m)
    }

    fun bmiString(reading: ScaleReading, heightCm: Double?): String =
        bmi(reading.weightKg, heightCm)?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
}
