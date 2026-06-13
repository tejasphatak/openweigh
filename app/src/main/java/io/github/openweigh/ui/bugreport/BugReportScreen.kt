package io.github.openweigh.ui.bugreport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Where to file issues. Update this to your fork's repository before publishing; the primary
 * "Share" action works regardless (it just opens the system share sheet with the report text).
 */
private const val ISSUES_URL = "https://github.com/openweigh/openweigh/issues/new"

/**
 * "Report a problem" screen. Shows a privacy-reviewed diagnostic report the user can describe,
 * then **Share** (system share sheet), **Copy**, or open a prefilled **GitHub issue**. Nothing is
 * transmitted automatically — the user is always in control of where the report goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportScreen(
    onNavigateUp: () -> Unit,
    viewModel: BugReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report a problem") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Describe what went wrong, then share the report. It includes app/device info and " +
                    "recent in-app logs to help diagnose the issue — no account, email, or measurement " +
                    "values are included. Nothing is sent until you choose to share it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("What happened?") },
                placeholder = { Text("Steps to reproduce, what you expected, what occurred…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.diagnostics,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }

                Button(
                    onClick = {
                        shareReport(context, viewModel.fullReport())
                        viewModel.onReportShared()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("  Share report")
                }

                FilledTonalButton(
                    onClick = {
                        copyReport(context, viewModel.fullReport())
                        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text("  Copy to clipboard")
                }

                TextButton(
                    onClick = {
                        openGitHubIssue(context, viewModel.fullReport())
                        viewModel.onReportShared()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null)
                    Text("  Open a GitHub issue")
                }
            }
        }
    }
}

private fun shareReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "OpenWeigh bug report")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(send, "Share bug report"))
}

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("OpenWeigh bug report", report))
}

private fun openGitHubIssue(context: Context, report: String) {
    val uri = Uri.parse(ISSUES_URL).buildUpon()
        .appendQueryParameter("title", "Bug: ")
        .appendQueryParameter("body", report)
        .build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(context, "No browser available; use Share instead.", Toast.LENGTH_SHORT).show() }
}
