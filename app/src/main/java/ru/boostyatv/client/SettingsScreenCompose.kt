package ru.boostyatv.client

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreenView(
    options: PlaybackOptions,
    oledEnabled: Boolean,
    updating: Boolean,
    onToggleOled: (Boolean) -> Unit,
    onScanTvCode: () -> Unit,
    onOpenAbout: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val isTv = BoostyTheme.isTv

    var qualityIndex by remember { mutableIntStateOf(options.qualityIndex()) }
    var selectedPlayer by remember { mutableStateOf(options.player()) }
    var showQualitySheet by remember { mutableStateOf(false) }
    var showPlayerSheet by remember { mutableStateOf(false) }

    val qualityLabels = remember(context) { PlaybackOptions.qualityLabels(context).toList() }
    val allPlayers = remember { PlaybackOptions.App.values().toList() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = stringResource(R.string.settings_title),
            showBack = false,
            onBack = {},
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isTv) 32.dp else 16.dp,
                    vertical = 4.dp,
                ),
        ) {
            Column(
                modifier = if (isTv) Modifier.widthIn(max = 640.dp) else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoostySettingsSection(stringResource(R.string.settings_section_playback))

                BoostySettingsRow(
                    iconRes = R.drawable.ic_tune,
                    title = stringResource(R.string.settings_quality),
                    subtitle = stringResource(R.string.settings_quality_hint),
                    value = qualityLabels.getOrElse(qualityIndex) { qualityLabels.first() },
                    onClick = { showQualitySheet = true },
                )

                BoostySettingsRow(
                    iconRes = R.drawable.ic_play,
                    title = stringResource(R.string.settings_player),
                    subtitle = stringResource(R.string.settings_player_hint),
                    value = selectedPlayer.label(context),
                    onClick = { showPlayerSheet = true },
                )

                BoostySettingsSection(stringResource(R.string.settings_section_appearance))

                BoostySettingsRow(
                    iconRes = R.drawable.ic_palette,
                    title = stringResource(R.string.settings_oled_title),
                    subtitle = stringResource(R.string.settings_oled_hint),
                    onClick = {
                        if (updating) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.update_in_progress_wait),
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            onToggleOled(!oledEnabled)
                        }
                    },
                    trailing = { BoostySwitch(checked = oledEnabled) },
                )

                if (!isTv) {
                    BoostySettingsSection(stringResource(R.string.settings_section_tv))

                    BoostySettingsRow(
                        iconRes = R.drawable.ic_tv,
                        title = stringResource(R.string.settings_connect_tv),
                        subtitle = stringResource(R.string.settings_connect_tv_hint),
                        onClick = onScanTvCode,
                    )
                }

                BoostySettingsSection(stringResource(R.string.settings_section_app))

                BoostySettingsRow(
                    iconRes = R.drawable.ic_info,
                    title = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.settings_about_hint),
                    onClick = onOpenAbout,
                )

                BoostySettingsRow(
                    iconRes = R.drawable.ic_refresh,
                    title = stringResource(R.string.about_check_updates),
                    subtitle = stringResource(R.string.settings_check_updates_hint),
                    onClick = onCheckUpdates,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showQualitySheet) {
        val selectedFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
        BoostyOverlaySheet(
            title = stringResource(R.string.settings_quality),
            onDismiss = { showQualitySheet = false },
        ) {
            qualityLabels.forEachIndexed { index, label ->
                val isSelected = index == qualityIndex
                BoostySheetOption(
                    label = label,
                    selected = isSelected,
                    focusRequester = if (isSelected) selectedFocus else null,
                    onClick = {
                        options.setQuality(index)
                        qualityIndex = index
                        showQualitySheet = false
                    },
                )
            }
        }
    }

    if (showPlayerSheet) {
        val selectedFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
        BoostyOverlaySheet(
            title = stringResource(R.string.settings_player),
            onDismiss = { showPlayerSheet = false },
        ) {
            allPlayers.forEach { app ->
                val isSelected = app == selectedPlayer
                BoostySheetOption(
                    label = app.label(context),
                    selected = isSelected,
                    focusRequester = if (isSelected) selectedFocus else null,
                    onClick = {
                        options.setPlayer(app)
                        selectedPlayer = app
                        showPlayerSheet = false
                    },
                )
            }
        }
    }
}
