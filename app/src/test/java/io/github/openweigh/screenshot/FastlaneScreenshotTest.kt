package io.github.openweigh.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.ble.model.Sex
import io.github.openweigh.data.repo.Measurement
import io.github.openweigh.ui.detail.DetailContent
import io.github.openweigh.ui.detail.DetailUiState
import io.github.openweigh.ui.history.ChartPoint
import io.github.openweigh.ui.history.DayGroup
import io.github.openweigh.ui.history.HistoryContent
import io.github.openweigh.ui.history.HistoryUiState
import io.github.openweigh.ui.measure.LiveReadoutContent
import io.github.openweigh.ui.onboarding.OnboardingScreen
import io.github.openweigh.ui.settings.GoogleDriveCard
import io.github.openweigh.ui.settings.HealthConnectCard
import io.github.openweigh.ui.settings.ProfileCard
import io.github.openweigh.ui.settings.SettingsUiState
import io.github.openweigh.ui.settings.SupportCard
import io.github.openweigh.ui.theme.OpenWeighTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/**
 * Generates the Play / F-Droid store screenshots by rendering the real Compose UI headlessly with
 * Roborazzi + Robolectric (no emulator). PNGs land in `build/screenshots/` and are copied into
 * `fastlane/metadata/.../images/phoneScreenshots/` by the build script.
 *
 * The `screenshot` package is excluded from normal/CI unit-test runs; include it with `-Pscreenshots`
 * (see app/build.gradle.kts), e.g. `./gradlew recordRoborazziPlayDebug -Pscreenshots`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class FastlaneScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun onlyInRecordMode() {
        assumeTrue("screenshot generation runs only in record mode", java.lang.Boolean.getBoolean("roborazzi.test.record"))
    }

    @Test
    fun onboarding() {
        composeRule.setContent {
            OpenWeighTheme { OnboardingScreen(onFinished = {}) }
        }
        composeRule.onRoot().captureRoboImage(SHOT_DIR + "1_onboarding.png")
    }

    @Test
    fun measureLiveReadout() {
        composeRule.setContent {
            OpenWeighTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LiveReadoutContent(reading = sampleReading, deviceName = "Mi Body Scale 2", onSave = {})
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage(SHOT_DIR + "2_measure.png")
    }

    @Test
    fun measurementDetail() {
        composeRule.setContent {
            OpenWeighTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DetailContent(
                        measurement = sampleMeasurement,
                        state = DetailUiState(
                            loading = false,
                            measurement = sampleMeasurement,
                            heightCm = 178.0,
                            healthConnectAvailable = true,
                        ),
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        onEditWeight = {},
                        onEditBodyFat = {},
                        onSaveEdit = {},
                        onCancelEdit = {},
                        onExportHealth = {},
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage(SHOT_DIR + "3_detail.png")
    }

    @Test
    fun history() {
        composeRule.setContent {
            OpenWeighTheme { HistoryContent(state = sampleHistoryState) }
        }
        composeRule.onRoot().captureRoboImage(SHOT_DIR + "4_history.png")
    }

    @Test
    fun settings() {
        composeRule.setContent {
            OpenWeighTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProfileCard(state = sampleSettingsState, onHeightChange = {}, onSexChange = {}, onBirthDateChange = {}, onSave = {})
                        HealthConnectCard(state = sampleSettingsState, onConnect = {}, onInstall = {}, onBackfill = {})
                        GoogleDriveCard(state = sampleSettingsState, onSignIn = {}, onSignOut = {}, onBackupToggle = {}, onExportNow = {}, onRestore = {})
                        SupportCard(onReportProblem = {})
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage(SHOT_DIR + "5_settings.png")
    }

    private companion object {
        const val SHOT_DIR = "build/screenshots/"
        const val DAY_MS = 86_400_000L
        const val BASE = 1_700_000_000_000L

        val sampleReading = ScaleReading(
            timestamp = Instant.ofEpochMilli(BASE),
            weightKg = 82.4,
            bodyFatPercent = 18.3,
            leanMassKg = 58.2,
            bodyWaterMassKg = 42.1,
            boneMassKg = 3.4,
            basalMetabolismKcal = 1720,
            impedanceOhm = 512.0,
            sourceDevice = "Mi Body Scale 2",
        )

        val sampleMeasurement = Measurement(id = "sample-1", epochMillis = BASE, reading = sampleReading)

        private fun reading(weightKg: Double, fat: Double, epoch: Long) = ScaleReading(
            timestamp = Instant.ofEpochMilli(epoch),
            weightKg = weightKg,
            bodyFatPercent = fat,
            leanMassKg = weightKg * 0.7,
            bodyWaterMassKg = weightKg * 0.51,
            sourceDevice = "Mi Body Scale 2",
        )

        private fun measurement(id: String, weightKg: Double, fat: Double, epoch: Long) =
            Measurement(id = id, epochMillis = epoch, reading = reading(weightKg, fat, epoch))

        val sampleHistoryState = HistoryUiState(
            loading = false,
            isEmpty = false,
            groups = listOf(
                DayGroup(
                    day = LocalDate.of(2024, 11, 14),
                    measurements = listOf(
                        measurement("m5", 82.4, 18.3, BASE),
                        measurement("m4", 82.9, 18.6, BASE - 3_600_000L),
                    ),
                ),
                DayGroup(
                    day = LocalDate.of(2024, 11, 13),
                    measurements = listOf(measurement("m3", 83.1, 18.9, BASE - DAY_MS)),
                ),
                DayGroup(
                    day = LocalDate.of(2024, 11, 11),
                    measurements = listOf(measurement("m2", 83.6, 19.2, BASE - 3 * DAY_MS)),
                ),
            ),
            weightSeries = listOf(
                ChartPoint(BASE - 3 * DAY_MS, 83.6),
                ChartPoint(BASE - DAY_MS, 83.1),
                ChartPoint(BASE - 3_600_000L, 82.9),
                ChartPoint(BASE, 82.4),
            ),
            bodyFatSeries = listOf(
                ChartPoint(BASE - 3 * DAY_MS, 19.2),
                ChartPoint(BASE - DAY_MS, 18.9),
                ChartPoint(BASE - 3_600_000L, 18.6),
                ChartPoint(BASE, 18.3),
            ),
            latestWeightKg = 82.4,
            heightCm = 178.0,
        )

        val sampleSettingsState = SettingsUiState(
            heightCm = "178",
            sex = Sex.MALE,
            birthDate = LocalDate.of(1990, 5, 20),
            profileSaved = true,
            healthConnectAvailable = true,
            healthConnectConnected = true,
            cloudSupported = true,
            googleSignedIn = true,
            googleEmail = "alex@example.com",
            backupAuthorized = true,
            backupEnabled = true,
        )
    }
}
