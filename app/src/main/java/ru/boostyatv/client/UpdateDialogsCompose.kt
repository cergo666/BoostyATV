package ru.boostyatv.client

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun UpdateDialogs(
    state: UpdateController.UiState,
    onAccept: () -> Unit,
    onDismissOffer: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val palette = BoostyTheme.palette
    val offer = state.offer()
    if (offer != null) {
        BoostyConfirmDialog(
            title = stringResource(R.string.about_update_available, offer.version()),
            message = offer.notes().ifBlank { stringResource(R.string.about_no_changelog) },
            confirmLabel = stringResource(R.string.update_install),
            dismissLabel = stringResource(R.string.update_install_later),
            onConfirm = onAccept,
            onDismiss = onDismissOffer,
        )
    } else if (state.downloading()) {
        BoostyOverlaySheet(
            title = stringResource(R.string.update_download_title),
            onDismiss = onCancelDownload,
            footer = {
                BoostyButton(
                    text = stringResource(R.string.cancel),
                    primary = false,
                    onClick = onCancelDownload,
                )
            },
        ) {
            val percent = state.downloadPercent()
            if (percent >= 0) {
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                    color = palette.accent,
                    trackColor = palette.surfaceElevated,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.update_download_progress, percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                )
            } else {
                LinearProgressIndicator(
                    color = palette.accent,
                    trackColor = palette.surfaceElevated,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}
