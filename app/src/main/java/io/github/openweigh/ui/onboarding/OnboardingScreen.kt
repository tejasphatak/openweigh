package io.github.openweigh.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import kotlinx.coroutines.launch

/**
 * First-run permission walkthrough — one step per page in a [HorizontalPager]:
 *  1. Bluetooth      (required) — requests BLE scan/connect runtime permissions.
 *  2. Health Connect (optional) — explains the export; user connects later in Settings.
 *  3. Google         (optional) — explains Drive backup; user signs in later in Settings.
 *
 * Onboarding is intentionally VM-less: each page does at most one launcher call, and the
 * heavier connect/sign-in flows live in Settings. [onFinished] advances the app to Measure.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    val pages = remember { onboardingPages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    var bluetoothGranted by remember { mutableStateOf(false) }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        bluetoothGranted = result.values.all { it }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                OnboardingPage(
                    page = pages[page],
                    actionDone = page == 0 && bluetoothGranted,
                    onAction = {
                        when (page) {
                            0 -> bluetoothLauncher.launch(blePermissions())
                            else -> { /* optional steps are handled in Settings */ }
                        }
                    },
                )
            }

            PagerIndicator(
                count = pages.size,
                selected = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onFinished) { Text("Skip") }

                val isLast = pagerState.currentPage == pages.lastIndex
                Button(onClick = {
                    if (isLast) {
                        onFinished()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }) {
                    Text(if (isLast) "Get started" else "Next")
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(
    page: OnboardingPageData,
    actionDone: Boolean,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (page.optional) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Optional — you can set this up later in Settings.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        page.actionLabel?.let { label ->
            Spacer(Modifier.height(24.dp))
            AnimatedContent(
                targetState = actionDone,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "action",
            ) { done ->
                if (done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Granted", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(onClick = onAction) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun PagerIndicator(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

private data class OnboardingPageData(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val actionLabel: String?,
    val optional: Boolean,
)

private fun onboardingPages(): List<OnboardingPageData> = listOf(
    OnboardingPageData(
        icon = Icons.Outlined.Bluetooth,
        title = "Connect your scale",
        body = "OpenWeigh talks to standard Bluetooth weight & body-composition scales. " +
            "Grant Bluetooth access so we can find and read your scale.",
        actionLabel = "Allow Bluetooth",
        optional = false,
    ),
    OnboardingPageData(
        icon = Icons.Outlined.FavoriteBorder,
        title = "Sync to Health Connect",
        body = "Export weight, body fat, lean mass and more to Android Health Connect so your " +
            "other health apps can use them.",
        actionLabel = null,
        optional = true,
    ),
    OnboardingPageData(
        icon = Icons.Outlined.CloudUpload,
        title = "Back up to your Drive",
        body = "Keep an encrypted-by-Google backup in your own Google Drive. There's no app " +
            "server — your data only ever goes to your account.",
        actionLabel = null,
        optional = true,
    ),
)

/** Runtime BLE permissions vary by API level (NEARBY on 31+, LOCATION before). */
private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
