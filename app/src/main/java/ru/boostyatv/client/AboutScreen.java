package ru.boostyatv.client;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** The "About" screen: branding, version, update check and project link. */
final class AboutScreen {
    interface Host {
        Ui ui();
        boolean isTv();
        UpdateController updates();
        void back();
    }

    private final Host host;
    private final Ui ui;
    private final LinearLayout screen;
    private final TextView status;
    private final TextView check;
    private final TextView project;

    AboutScreen(Host host) {
        this.host = host;
        this.ui = host.ui();
        screen = ui.column();
        screen.addView(ui.toolbar(ui.context().getString(R.string.about_title), true, host::back));
        ScrollView scroll = new ScrollView(ui.context());
        scroll.setFillViewport(true);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        FrameLayout center = new FrameLayout(ui.context());
        center.addView(content, new FrameLayout.LayoutParams(
            host.isTv() ? ui.dp(560) : Ui.MATCH, Ui.WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(center, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout hero = ui.column();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(22));
        android.graphics.drawable.GradientDrawable card =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xff39252e, ui.surface});
        card.setCornerRadius(ui.dp(24));
        hero.setBackground(card);
        ImageView icon = new ImageView(ui.context());
        icon.setImageResource(R.drawable.app_icon);
        icon.setBackground(ui.shape(ui.background, 18, 0));
        icon.setClipToOutline(true);
        hero.addView(icon, new LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)));
        ui.margin(hero, ui.label(ui.context().getString(R.string.app_name), 29, Ui.INK, true),
            Ui.WRAP, Ui.WRAP, 12);
        ui.margin(hero, ui.label(ui.context().getString(
            R.string.about_version, BuildConfig.VERSION_NAME), 15, Ui.ACCENT, true), Ui.WRAP, Ui.WRAP, 4);
        ui.margin(hero, ui.label(ui.context().getString(R.string.about_subtitle), 14, Ui.MUTED, false),
            Ui.WRAP, Ui.WRAP, 8);
        content.addView(hero, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        status = ui.label("", 15, Ui.MUTED, false);
        check = ui.button(ui.context().getString(R.string.about_check_updates), true,
            () -> host.updates().check(status));
        if (!BuildConfig.DEBUG) {
            ui.margin(content, check, Ui.MATCH, ui.dp(52), 16);
            ui.margin(content, status, Ui.MATCH, Ui.WRAP, 6);
        }
        if (host.updates().isDownloading()) {
            status.setText(ui.context().getString(R.string.update_downloading));
        } else if (host.updates().isUpdating()) {
            status.setText(ui.context().getString(R.string.about_updates_checking));
        }
        ui.margin(content, ui.label(ui.context().getString(R.string.about_author_title), 13,
            Ui.MUTED, false), Ui.MATCH, Ui.WRAP, 16);
        ui.margin(content, ui.label(ui.context().getString(R.string.about_author), 17,
            Ui.INK, true), Ui.MATCH, Ui.WRAP, 4);
        project = ui.button(ui.context().getString(R.string.about_github), false,
            () -> openProjectLink(AppRelease.PROJECT));
        ui.margin(content, project, Ui.MATCH, ui.dp(48), 12);
        screen.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
    }

    View view() {
        return screen;
    }

    void focus() {
        if (host.isTv()) (BuildConfig.DEBUG ? project : check).requestFocus();
    }

    private void openProjectLink(String url) {
        try {
            ui.context().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ui.context(), url, Toast.LENGTH_LONG).show();
        }
    }
}
