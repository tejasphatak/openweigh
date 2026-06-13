package io.github.openweigh.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import io.github.openweigh.ui.bugreport.BugReportScreen
import io.github.openweigh.ui.detail.DetailScreen
import io.github.openweigh.ui.history.HistoryScreen
import io.github.openweigh.ui.measure.MeasureScreen
import io.github.openweigh.ui.onboarding.OnboardingScreen
import io.github.openweigh.ui.settings.SettingsScreen

/**
 * Standard Material motion duration for forward/back screen transitions.
 */
private const val MOTION_DURATION = 350

/**
 * App shell: a [Scaffold] with a bottom [NavigationBar] for the three top-level
 * destinations and a [NavHost] that owns every route. Material shared-axis style
 * motion (horizontal slide + fade) is applied across destinations; the detail
 * route slides up over its parent.
 *
 * The bottom bar is hidden on routes that are full-screen flows (onboarding, detail).
 */
@Composable
fun AppRoot(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in TopLevelDestination.entries.map { it.destination.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                OpenWeighNavigationBar(navController = navController, backStackEntry = backStackEntry)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Measure.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(MOTION_DURATION),
                ) + fadeIn(tween(MOTION_DURATION))
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(MOTION_DURATION),
                ) + fadeOut(tween(MOTION_DURATION))
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(MOTION_DURATION),
                ) + fadeIn(tween(MOTION_DURATION))
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(MOTION_DURATION),
                ) + fadeOut(tween(MOTION_DURATION))
            },
        ) {
            composable(Destination.Onboarding.route) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Destination.Measure.route) {
                            popUpTo(Destination.Onboarding.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(Destination.Measure.route) {
                MeasureScreen(
                    onReadingClick = { id ->
                        navController.navigate(Destination.Detail.createRoute(id))
                    },
                )
            }

            composable(Destination.History.route) {
                HistoryScreen(
                    onReadingClick = { id ->
                        navController.navigate(Destination.Detail.createRoute(id))
                    },
                )
            }

            composable(Destination.Settings.route) {
                SettingsScreen(
                    onOpenOnboarding = {
                        navController.navigate(Destination.Onboarding.route)
                    },
                    onOpenBugReport = {
                        navController.navigate(Destination.BugReport.route)
                    },
                )
            }

            composable(Destination.BugReport.route) {
                BugReportScreen(onNavigateUp = { navController.navigateUp() })
            }

            composable(
                route = Destination.Detail.route,
                arguments = listOf(navArgument(Destination.Detail.ARG_ID) { type = NavType.StringType }),
                enterTransition = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Up,
                        animationSpec = tween(MOTION_DURATION),
                    ) + fadeIn(tween(MOTION_DURATION))
                },
                popExitTransition = {
                    slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.Down,
                        animationSpec = tween(MOTION_DURATION),
                    ) + fadeOut(tween(MOTION_DURATION))
                },
            ) { entry ->
                val id = entry.arguments?.getString(Destination.Detail.ARG_ID).orEmpty()
                DetailScreen(
                    id = id,
                    onNavigateUp = { navController.navigateUp() },
                )
            }
        }
    }
}

@Composable
private fun OpenWeighNavigationBar(
    navController: NavHostController,
    backStackEntry: androidx.navigation.NavBackStackEntry?,
) {
    NavigationBar {
        val currentDestination = backStackEntry?.destination
        TopLevelDestination.entries.forEach { topLevel ->
            val selected = currentDestination?.hierarchy?.any {
                it.route == topLevel.destination.route
            } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(topLevel.destination.route) {
                        // Pop up to the start destination to avoid building up a large back stack.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) topLevel.selectedIcon else topLevel.unselectedIcon,
                        contentDescription = topLevel.label,
                    )
                },
                label = { Text(topLevel.label) },
                alwaysShowLabel = true,
            )
        }
    }
}
