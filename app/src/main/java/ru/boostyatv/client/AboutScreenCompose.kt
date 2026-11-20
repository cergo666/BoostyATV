package ru.boostyatv.client

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun AboutScreenView(
    updateStatus: String,
    isUpdating: Boolean,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val primaryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (isTv) runCatching { primaryFocus.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = stringResource(R.string.about_title),
            showBack = true,
            onBack = onBack,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isTv) 32.dp else 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = if (isTv) Modifier.widthIn(max = 560.dp) else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SheetShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    palette.accent.copy(alpha = 0.22f),
                                    palette.surface,
                                )
                            )
                        )
                        .border(1.dp, palette.hairline, SheetShape)
                        .padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CardShape)
                            .border(1.dp, palette.hairline, CardShape),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        color = palette.ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(PillShape)
                            .background(palette.accentSoft)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.accent,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                        textAlign = TextAlign.Center,
                    )
                }

                if (!BuildConfig.DEBUG) {
                    BoostyButton(
                        text = stringResource(R.string.about_check_updates),
                        iconRes = R.drawable.ic_refresh,
                        primary = true,
                        enabled = !isUpdating,
                        onClick = onCheckUpdates,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocus),
                    )
                    if (updateStatus.isNotEmpty()) {
                        Text(
                            text = updateStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(palette.surface)
                        .border(1.dp, palette.hairline, CardShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_author_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                    Text(
                        text = stringResource(R.string.about_author),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.ink,
                    )
                }

                BoostyButton(
                    text = stringResource(R.string.about_github),
                    primary = false,
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppRelease.PROJECT)))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, AppRelease.PROJECT, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (BuildConfig.DEBUG) Modifier.focusRequester(primaryFocus) else Modifier),
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
