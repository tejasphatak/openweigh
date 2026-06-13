package io.github.openweigh.drive

import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.data.repo.Measurement
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts the local measurement store to/from portable formats for Drive:
 *
 * - **JSON snapshot** ([toJsonSnapshot] / [fromJsonSnapshot]) — a versioned, lossless dump of every
 *   field, used for the hidden appDataFolder backup and restore. Round-trips exactly.
 * - **CSV** ([toCsv] / [fromCsv]) — a human-friendly export the user can open in a spreadsheet.
 *
 * Uses `org.json` (bundled with Android) to avoid an extra dependency. All numbers are stored in
 * canonical units (kilograms, percent, kcal); timestamps are epoch milliseconds plus an ISO-8601
 * string for readability.
 */
@Singleton
class BackupSerializer @Inject constructor() {

    // ---- JSON snapshot --------------------------------------------------------------------------

    /**
     * Serialize all [measurements] to a versioned JSON snapshot string. Newest-first ordering is
     * not required (restore is order-independent), but we preserve whatever order is passed in.
     */
    fun toJsonSnapshot(measurements: List<Measurement>): String {
        val root = JSONObject()
        root.put(KEY_VERSION, SNAPSHOT_VERSION)
        root.put(KEY_EXPORTED_AT, Instant.now().toString())
        root.put(KEY_COUNT, measurements.size)
        val arr = JSONArray()
        for (m in measurements) {
            arr.put(m.toJson())
        }
        root.put(KEY_MEASUREMENTS, arr)
        return root.toString()
    }

    /**
     * Parse a JSON snapshot produced by [toJsonSnapshot] back into [Measurement]s. Unknown/missing
     * optional fields are tolerated (null'd). Throws [org.json.JSONException] on malformed input.
     */
    fun fromJsonSnapshot(json: String): List<Measurement> {
        val root = JSONObject(json)
        val arr = root.optJSONArray(KEY_MEASUREMENTS) ?: return emptyList()
        val out = ArrayList<Measurement>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(measurementFromJson(arr.getJSONObject(i)))
        }
        return out
    }

    private fun Measurement.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("epochMillis", epochMillis)
        put("timestamp", timestamp.toString())
        put("weightKg", reading.weightKg)
        putOpt("bodyFatPercent", reading.bodyFatPercent)
        putOpt("leanMassKg", reading.leanMassKg)
        putOpt("bodyWaterMassKg", reading.bodyWaterMassKg)
        putOpt("boneMassKg", reading.boneMassKg)
        putOpt("basalMetabolismKcal", reading.basalMetabolismKcal)
        putOpt("impedanceOhm", reading.impedanceOhm)
        putOpt("source", reading.sourceDevice)
        put("estimated", reading.estimated)
        put("syncedHealthConnect", syncedHealthConnect)
        put("syncedDrive", syncedDrive)
    }

    private fun measurementFromJson(o: JSONObject): Measurement {
        val epochMillis = o.getLong("epochMillis")
        val reading = ScaleReading(
            timestamp = Instant.ofEpochMilli(epochMillis),
            weightKg = o.getDouble("weightKg"),
            bodyFatPercent = o.optDoubleOrNull("bodyFatPercent"),
            leanMassKg = o.optDoubleOrNull("leanMassKg"),
            bodyWaterMassKg = o.optDoubleOrNull("bodyWaterMassKg"),
            boneMassKg = o.optDoubleOrNull("boneMassKg"),
            basalMetabolismKcal = o.optIntOrNull("basalMetabolismKcal"),
            impedanceOhm = o.optDoubleOrNull("impedanceOhm"),
            sourceDevice = o.optStringOrNull("source"),
            estimated = o.optBoolean("estimated", false)
        )
        return Measurement(
            id = o.getString("id"),
            epochMillis = epochMillis,
            reading = reading,
            syncedHealthConnect = o.optBoolean("syncedHealthConnect", false),
            syncedDrive = o.optBoolean("syncedDrive", false)
        )
    }

    // ---- CSV ------------------------------------------------------------------------------------

    /** Serialize [measurements] to RFC-4180-ish CSV with a header row. */
    fun toCsv(measurements: List<Measurement>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\r\n")
        for (m in measurements) {
            val r = m.reading
            val cols = listOf(
                m.id,
                m.timestamp.toString(),
                m.epochMillis.toString(),
                r.weightKg.toString(),
                r.bodyFatPercent?.toString().orEmpty(),
                r.leanMassKg?.toString().orEmpty(),
                r.bodyWaterMassKg?.toString().orEmpty(),
                r.boneMassKg?.toString().orEmpty(),
                r.basalMetabolismKcal?.toString().orEmpty(),
                r.impedanceOhm?.toString().orEmpty(),
                r.sourceDevice.orEmpty(),
                r.estimated.toString()
            )
            sb.append(cols.joinToString(",") { csvEscape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * Parse CSV produced by [toCsv] back into [Measurement]s. Tolerant of blank optional cells and
     * trailing newlines; ignores rows that lack the required id/weight. Header row is required.
     */
    fun fromCsv(csv: String): List<Measurement> {
        val rows = parseCsvRows(csv)
        if (rows.isEmpty()) return emptyList()
        // Skip header.
        val out = ArrayList<Measurement>(rows.size - 1)
        for (i in 1 until rows.size) {
            val c = rows[i]
            if (c.size < 12) continue
            val id = c[0]
            if (id.isBlank()) continue
            val weight = c[3].toDoubleOrNull() ?: continue
            val epochMillis = c[2].toLongOrNull()
                ?: runCatching { Instant.parse(c[1]).toEpochMilli() }.getOrNull()
                ?: continue
            val reading = ScaleReading(
                timestamp = Instant.ofEpochMilli(epochMillis),
                weightKg = weight,
                bodyFatPercent = c[4].toDoubleOrNull(),
                leanMassKg = c[5].toDoubleOrNull(),
                bodyWaterMassKg = c[6].toDoubleOrNull(),
                boneMassKg = c[7].toDoubleOrNull(),
                basalMetabolismKcal = c[8].toIntOrNull(),
                impedanceOhm = c[9].toDoubleOrNull(),
                sourceDevice = c[10].ifBlank { null },
                estimated = c[11].toBooleanStrictOrNull() ?: false
            )
            out.add(Measurement(id = id, epochMillis = epochMillis, reading = reading))
        }
        return out
    }

    private fun csvEscape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /** Minimal RFC-4180 row/field parser handling quotes, escaped quotes, and embedded newlines. */
    private fun parseCsvRows(csv: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        val n = csv.length
        while (i < n) {
            val ch = csv[i]
            when {
                inQuotes -> when {
                    ch == '"' && i + 1 < n && csv[i + 1] == '"' -> { field.append('"'); i++ }
                    ch == '"' -> inQuotes = false
                    else -> field.append(ch)
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> { row.add(field.toString()); field = StringBuilder() }
                ch == '\r' -> { /* swallow; handle on \n */ }
                ch == '\n' -> {
                    row.add(field.toString()); field = StringBuilder()
                    rows.add(row); row = ArrayList()
                }
                else -> field.append(ch)
            }
            i++
        }
        // Flush trailing field/row if the input didn't end on a newline.
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filter { it.size > 1 || (it.size == 1 && it[0].isNotBlank()) }
    }

    companion object {
        const val SNAPSHOT_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_EXPORTED_AT = "exportedAt"
        private const val KEY_COUNT = "count"
        private const val KEY_MEASUREMENTS = "measurements"

        const val CSV_HEADER =
            "id,timestamp,epochMillis,weightKg,bodyFatPercent,leanMassKg,bodyWaterMassKg," +
                "boneMassKg,basalMetabolismKcal,impedanceOhm,source,estimated"
    }
}

// --- org.json null-aware helpers ------------------------------------------------------------------

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
