package io.github.openweigh.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.openweigh.ble.model.ScaleReading
import io.github.openweigh.data.repo.Measurement
import io.github.openweigh.ui.detail.DetailContent
import io.github.openweigh.ui.detail.DetailUiState
import io.github.openweigh.ui.measure.LiveReadoutContent
import io.github.openweigh.ui.onboarding.OnboardingScreen
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

/**
 * Generates the Play / F-Droid store screenshots by rendering the real Compose UI headlessly with
 * Roborazzi + Robolectric (no emulator). PNGs land in `build/screenshots/` and are copied into
 * `fastlane/metadata/.../images/phoneScreenshots/` by the build script.
 *
 * Runs only under a Roborazzi record task (`./gradlew recordRoborazziPlayDebug`); skipped in normal
 * CI test runs via the [assumeTrue] guard below.
 */
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
            OpenWeighTheme {
                OnboardingScreen(onFinished = {})
            }
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
                        LiveReadoutContent(
                            reading = sampleReading,
                            deviceName = "Mi Body Scale 2",
                            onSave = {},
                        )
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

    private companion object {
        const val SHOT_DIR = "build/screenshots/"

        val sampleReading = ScaleReading(
            timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
            weightKg = 82.4,
            bodyFatPercent = 18.3,
            leanMassKg = 58.2,
            bodyWaterMassKg = 42.1,
            boneMassKg = 3.4,
            basalMetabolismKcal = 1720,
            impedanceOhm = 512.0,
            sourceDevice = "Mi Body Scale 2",
        )

        val sampleMeasurement = Measurement(
            id = "sample-1",
            epochMillis = 1_700_000_000_000L,
            reading = sampleReading,
        )
    }
}
