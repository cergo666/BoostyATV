package ru.boostyatv.client;

import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The settings screen: playback defaults, theme and TV pairing entry point. */
final class SettingsScreen {
    interface Host {
        Ui ui();
        boolean isTv();
        PlaybackOptions options();
        boolean updating();
        void replace(View view, boolean push);
        void scanTvCode();
        void reopenSettings();
        void back();
    }

    private final Host host;
    private final Ui ui;
    private final LinearLayout screen;
    private final LinearLayout options;
    private final TextView quality;
    private final TextView playerChoice;

    SettingsScreen(Host host) {
        this.host = host;
        this.ui = host.ui();
        PlaybackOptions playback = host.options();
        screen = ui.column();
        screen.addView(ui.toolbar(ui.context().getString(R.string.settings_title), true, host::back));
        options = ui.column();
        options.setPadding(ui.dp(host.isTv() ? 38 : 16), ui.dp(8), ui.dp(host.isTv() ? 38 : 16), 0);
        quality = ui.button("", false, this::chooseQuality);
        playerChoice = ui.button("", false, this::choosePlayer);
        Ui.onAttach(quality, () -> quality.setText(ui.context().getString(R.string.settings_quality_value,
            PlaybackOptions.qualityLabels(ui.context())[playback.qualityIndex()])));
        Ui.onAttach(playerChoice, () -> playerChoice.setText(ui.context().getString(
            R.string.settings_player_value, playback.player().label(ui.context()))));
        ui.margin(options, quality, Ui.MATCH, ui.dp(52), 8);
        ui.margin(options, playerChoice, Ui.MATCH, ui.dp(52), 8);
        ui.margin(options, ui.button(ui.context().getString(R.string.settings_theme_value,
            ui.context().getString(playback.oled()
                ? R.string.settings_theme_oled : R.string.settings_theme_dark)), false,
            this::chooseTheme), Ui.MATCH, ui.dp(52), 8);
        if (!host.isTv()) {
            ui.margin(options, ui.button(ui.context().getString(R.string.settings_connect_tv),
                false, host::scanTvCode), Ui.MATCH, ui.dp(52), 8);
        }
        screen.addView(options);
    }

    View view() {
        return screen;
    }

    void focus() {
        quality.requestFocus();
    }

    private void chooseTheme() {
        PlaybackOptions playback = host.options();
        if (host.updating()) {
            android.widget.Toast.makeText(ui.context(),
                ui.context().getString(R.string.update_in_progress_wait),
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(ui.context()).setTitle(ui.context().getString(R.string.settings_theme))
            .setSingleChoiceItems(new String[]{ui.context().getString(R.string.settings_theme_dark),
                ui.context().getString(R.string.settings_theme_oled)}, playback.oled() ? 1 : 0,
                (dialog, index) -> {
                    dialog.dismiss();
                    boolean oled = index == 1;
                    if (oled == playback.oled()) return;
                    playback.setOled(oled);
                    host.reopenSettings();
                }).setNegativeButton(ui.context().getString(R.string.cancel), null).show();
    }

    private void chooseQuality() {
        PlaybackOptions playback = host.options();
        new AlertDialog.Builder(ui.context()).setTitle(ui.context().getString(R.string.settings_quality))
            .setSingleChoiceItems(PlaybackOptions.qualityLabels(ui.context()), playback.qualityIndex(),
                (dialog, index) -> {
                    playback.setQuality(index);
                    dialog.dismiss();
                    quality.setText(ui.context().getString(R.string.settings_quality_value,
                        PlaybackOptions.qualityLabels(ui.context())[index]));
                }).setNegativeButton(ui.context().getString(R.string.cancel), null).show();
    }

    private void choosePlayer() {
        PlaybackOptions playback = host.options();
        PlaybackOptions.App[] apps = PlaybackOptions.App.values();
        String[] labels = java.util.Arrays.stream(apps)
            .map(app -> app.label(ui.context())).toArray(String[]::new);
        new AlertDialog.Builder(ui.context()).setTitle(ui.context().getString(R.string.settings_player))
            .setSingleChoiceItems(labels, playback.player().ordinal(), (dialog, index) -> {
                playback.setPlayer(apps[index]);
                playerChoice.setText(ui.context().getString(R.string.settings_player_value,
                    apps[index].label(ui.context())));
                dialog.dismiss();
            }).setNegativeButton(ui.context().getString(R.string.cancel), null).show();
    }
}
