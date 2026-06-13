package io.github.openweigh.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Type-safe navigation routes for OpenWeigh.
 *
 * [Destination] enumerates every screen reachable in the NavHost. Routes that take
 * arguments expose a [route] *pattern* (with `{placeholders}`) plus a helper to
 * build a concrete path.
 */
sealed class Destination(val route: String) {

    /** First-run permission walkthrough. */
    data object Onboarding : Destination("onboarding")

    // --- Bottom-nav top-level destinations ---
    data object Measure : Destination("measure")
    data object History : Destination("history")
    data object Settings : Destination("settings")

    /** Reading detail / edit screen. Takes a measurement id. */
    data object Detail : Destination("detail/{id}") {
        const val ARG_ID = "id"
        fun createRoute(id: String): String = "detail/$id"
    }

    /** Diagnostics / "report a problem" screen. */
    data object BugReport : Destination("bugreport")
}

/**
 * The three top-level destinations shown in the bottom [androidx.compose.material3.NavigationBar].
 * Order here is the order they appear in the bar.
 */
enum class TopLevelDestination(
    val destination: Destination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    MEASURE(
        destination = Destination.Measure,
        label = "Measure",
        selectedIcon = Icons.Filled.MonitorWeight,
        unselectedIcon = Icons.Outlined.MonitorWeight,
    ),
    HISTORY(
        destination = Destination.History,
        label = "History",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
    ),
    SETTINGS(
        destination = Destination.Settings,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
}
