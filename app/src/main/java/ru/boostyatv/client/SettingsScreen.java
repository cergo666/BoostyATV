package ru.boostyatv.client;

import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

/** The settings screen: playback defaults, theme and app actions. */
final class SettingsScreen {
    interface Host {
        Ui ui();
        boolean isTv();
        PlaybackOptions options();
        UpdateController updates();
        boolean updating();
        void replace(View view, boolean push);
        void scanTvCode();
        void reopenSettings();
        void showAbout();
        void back();
    }

    private final Host host;
    private final Ui ui;
    private final LinearLayout screen;
    private final LinearLayout qualityRow;
    private final LinearLayout playerRow;

    SettingsScreen(Host host) {
        this.host = host;
        this.ui = host.ui();
        PlaybackOptions playback = host.options();
        screen = ui.column();
        screen.addView(ui.toolbar(ui.context().getString(R.string.settings_title), true, host::back));

        ScrollView scroll = new ScrollView(ui.context());
        scroll.setFillViewport(true);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(host.isTv() ? 38 : 16), ui.dp(4),
            ui.dp(host.isTv() ? 38 : 16), ui.dp(24));

        content.addView(ui.settingsSection(ui.context().getString(R.string.settings_section_playback)));
        qualityRow = ui.settingsRow(R.drawable.ic_tune,
            ui.context().getString(R.string.settings_quality),
            ui.context().getString(R.string.settings_quality_hint),
            "", this::chooseQuality);
        content.addView(qualityRow, rowParams());
        playerRow = ui.settingsRow(R.drawable.ic_play,
            ui.context().getString(R.string.settings_player),
            ui.context().getString(R.string.settings_player_hint),
            "", this::choosePlayer);
        content.addView(playerRow, rowParams());
        Ui.onAttach(qualityRow, this::refreshQuality);
        Ui.onAttach(playerRow, this::refreshPlayer);

        content.addView(ui.settingsSection(ui.context().getString(R.string.settings_section_appearance)));
        content.addView(ui.settingsToggle(R.drawable.ic_palette,
            ui.context().getString(R.string.settings_oled_title),
            ui.context().getString(R.string.settings_oled_hint), playback.oled(), enabled -> {
                if (!host.updating()) {
                    playback.setOled(enabled);
                    host.reopenSettings();
                } else {
                    Toast.makeText(ui.context(), ui.context().getString(R.string.update_in_progress_wait),
                        Toast.LENGTH_LONG).show();
                }
            }), rowParams());

        content.addView(ui.settingsSection(ui.context().getString(R.string.settings_section_tv)));
        content.addView(ui.settingsRow(R.drawable.ic_tv,
            ui.context().getString(R.string.settings_connect_tv),
            ui.context().getString(R.string.settings_connect_tv_hint), "", host::scanTvCode),
            rowParams());

        content.addView(ui.settingsSection(ui.context().getString(R.string.settings_section_app)));
        content.addView(ui.settingsRow(R.drawable.ic_info,
            ui.context().getString(R.string.about_title),
            ui.context().getString(R.string.settings_about_hint), "", host::showAbout),
            rowParams());
        content.addView(ui.settingsRow(R.drawable.ic_refresh,
            ui.context().getString(R.string.about_check_updates),
            ui.context().getString(R.string.settings_check_updates_hint), "",
            () -> host.updates().check(null)), rowParams());

        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
    }

    View view() {
        return screen;
    }

    void focus() {
        qualityRow.requestFocus();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        params.topMargin = ui.dp(8);
        return params;
    }

    private void refreshQuality() {
        ui.setSettingsValue(qualityRow,
            PlaybackOptions.qualityLabels(ui.context())[host.options().qualityIndex()]);
    }

    private void refreshPlayer() {
        ui.setSettingsValue(playerRow, host.options().player().label(ui.context()));
    }

    private void chooseQuality() {
        PlaybackOptions playback = host.options();
        new AlertDialog.Builder(ui.context()).setTitle(ui.context().getString(R.string.settings_quality))
            .setSingleChoiceItems(PlaybackOptions.qualityLabels(ui.context()), playback.qualityIndex(),
                (dialog, index) -> {
                    playback.setQuality(index);
                    dialog.dismiss();
                    refreshQuality();
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
                refreshPlayer();
                dialog.dismiss();
            }).setNegativeButton(ui.context().getString(R.string.cancel), null).show();
    }
}
