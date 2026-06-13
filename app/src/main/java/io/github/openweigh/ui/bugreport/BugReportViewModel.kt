package io.github.openweigh.ui.bugreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.openweigh.diag.DiagnosticsCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the bug-report screen: collects the device/app diagnostics once on open and assembles the
 * final shareable report (the user's description + the collected diagnostics). No transmission
 * happens here — the screen fires an Android share/issue intent only when the user taps it.
 */
@HiltViewModel
class BugReportViewModel @Inject constructor(
    private val collector: DiagnosticsCollector,
) : ViewModel() {

    private val _state = MutableStateFlow(BugReportUiState())
    val state: StateFlow<BugReportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val diagnostics = collector.collect()
            _state.update { it.copy(diagnostics = diagnostics, loading = false) }
        }
    }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value) }

    /** The full shareable report: the user's description followed by the collected diagnostics. */
    fun fullReport(): String {
        val current = _state.value
        val what = current.description.ifBlank { "(no description provided)" }
        return "## What happened\n$what\n\n${current.diagnostics}"
    }

    /** Clear the persisted crash once the user has shared/dismissed it. */
    fun onReportShared() = collector.clearLastCrash()
}

data class BugReportUiState(
    val description: String = "",
    val diagnostics: String = "",
    val loading: Boolean = true,
)
