package ru.boostyatv.client

import android.app.DownloadManager
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun DownloadsScreenView(
    retrying: Set<Long>,
    onBack: () -> Unit,
    onRetry: (VideoDownloader.Entry) -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? LifecycleOwner
    val palette = BoostyTheme.palette
    val horizontal = if (BoostyTheme.isTv) 32.dp else 16.dp
    var entries by remember { mutableStateOf<List<VideoDownloader.Entry>?>(null) }
    var queryFailed by remember { mutableStateOf(false) }

    LaunchedEffect(activity) {
        activity?.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val result = withContext(Dispatchers.IO) { runCatching { VideoDownloader.list(context) } }
                queryFailed = result.isFailure
                result.getOrNull()?.let { entries = it }
                delay(1000)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(title = stringResource(R.string.downloads_title), showBack = true, onBack = onBack)
        val downloads = entries.orEmpty()
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(when {
                        queryFailed -> R.string.downloads_load_failed
                        entries == null -> R.string.loading
                        else -> R.string.downloads_empty
                    }),
                    color = if (queryFailed) palette.danger else palette.muted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = horizontal, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (queryFailed) item(key = "error") {
                    Text(stringResource(R.string.downloads_load_failed), color = palette.danger)
                }
                items(downloads, key = { it.id }) { entry ->
                    val failed = entry.status == DownloadManager.STATUS_FAILED
                    val running = entry.status == DownloadManager.STATUS_RUNNING
                    val complete = entry.status == DownloadManager.STATUS_SUCCESSFUL
                    val status = when (entry.status) {
                        DownloadManager.STATUS_RUNNING -> R.string.download_running
                        DownloadManager.STATUS_PAUSED -> when (entry.reason) {
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> R.string.download_wait_network
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> R.string.download_wait_wifi
                            else -> R.string.download_paused
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> R.string.download_complete
                        DownloadManager.STATUS_FAILED -> when (entry.reason) {
                            DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.download_no_space
                            DownloadManager.ERROR_DEVICE_NOT_FOUND,
                            DownloadManager.ERROR_FILE_ERROR,
                            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> R.string.download_file_error
                            DownloadManager.ERROR_HTTP_DATA_ERROR,
                            DownloadManager.ERROR_CANNOT_RESUME,
                            DownloadManager.ERROR_TOO_MANY_REDIRECTS,
                            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> R.string.download_network_error
                            else -> R.string.download_failed
                        }
                        else -> R.string.download_queued
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().background(palette.surface, CardShape).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            entry.title.ifEmpty { stringResource(R.string.post_untitled) },
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.ink,
                        )
                        val description = if (failed && entry.reason in 400..599) {
                            stringResource(R.string.download_http_error, entry.reason)
                        } else stringResource(status)
                        val bytes = if (entry.total > 0 && (running || complete || entry.bytes > 0)) {
                            stringResource(
                                R.string.download_bytes,
                                Formatter.formatShortFileSize(context, entry.bytes.coerceAtLeast(0)),
                                Formatter.formatShortFileSize(context, entry.total),
                            )
                        } else if (entry.bytes > 0) Formatter.formatShortFileSize(context, entry.bytes) else ""
                        Text(
                            listOf(entry.quality, description, bytes).filter { it.isNotBlank() }.joinToString(" · "),
                            color = if (failed) palette.danger else palette.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (running) {
                            if (entry.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (entry.bytes.toDouble() / entry.total).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = palette.accent,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.accent)
                            }
                        }
                        if (complete) {
                            BoostyButton(
                                text = stringResource(R.string.download_open),
                                iconRes = R.drawable.ic_play,
                                onClick = {
                                    if (!VideoDownloader.open(context, entry.id)) {
                                        Toast.makeText(context, R.string.download_open_failed, Toast.LENGTH_LONG).show()
                                    }
                                },
                                primary = false,
                            )
                        }
                        if (failed) {
                            val isRetrying = entry.id in retrying
                            BoostyButton(
                                text = stringResource(if (isRetrying) R.string.loading else R.string.retry),
                                onClick = { onRetry(entry) },
                                enabled = !isRetrying,
                                primary = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
