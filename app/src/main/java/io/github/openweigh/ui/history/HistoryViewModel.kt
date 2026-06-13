package io.github.openweigh.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.openweigh.data.db.UserProfileDao
import io.github.openweigh.data.repo.Measurement
import io.github.openweigh.data.repo.MeasurementRepository
import io.github.openweigh.ui.common.Formatting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/**
 * Backs the History screen. Observes all measurements, groups them by local calendar day for the
 * card list, and projects two chronological series (weight + body fat %) for the Canvas trend chart.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    repository: MeasurementRepository,
    profileDao: UserProfileDao,
) : ViewModel() {

    val state: StateFlow<HistoryUiState> =
        combine(repository.observeAll(), profileDao.observe()) { measurements, profile ->
            buildState(measurements, profile?.heightCm)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(loading = true),
        )

    private fun buildState(measurements: List<Measurement>, heightCm: Double?): HistoryUiState {
        if (measurements.isEmpty()) return HistoryUiState(loading = false, isEmpty = true)

        // Repository emits newest-first; preserve that for the grouped list.
        val groups = measurements
            .groupBy { Formatting.localDate(it.timestamp) }
            .toSortedMap(compareByDescending { it })
            .map { (day, items) -> DayGroup(day, items) }

        // Chart wants oldest→newest.
        val chrono = measurements.sortedBy { it.epochMillis }
        val weightSeries = chrono.map { ChartPoint(it.epochMillis, it.reading.weightKg) }
        val fatSeries = chrono.mapNotNull { m ->
            m.reading.bodyFatPercent?.let { ChartPoint(m.epochMillis, it) }
        }

        return HistoryUiState(
            loading = false,
            isEmpty = false,
            groups = groups,
            weightSeries = weightSeries,
            bodyFatSeries = fatSeries,
            latestWeightKg = measurements.firstOrNull()?.reading?.weightKg,
            heightCm = heightCm,
        )
    }
}

/** A single (x = epochMillis, y = value) point for the Canvas chart. */
data class ChartPoint(val epochMillis: Long, val value: Double)

/** Measurements taken on one calendar day, newest first within the day. */
data class DayGroup(val day: LocalDate, val measurements: List<Measurement>)

data class HistoryUiState(
    val loading: Boolean = false,
    val isEmpty: Boolean = false,
    val groups: List<DayGroup> = emptyList(),
    val weightSeries: List<ChartPoint> = emptyList(),
    val bodyFatSeries: List<ChartPoint> = emptyList(),
    val latestWeightKg: Double? = null,
    val heightCm: Double? = null,
)
