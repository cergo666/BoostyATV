package ru.boostyatv.client;

import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** The account tab: profile card and the actions that used to live in a dialog. */
final class AccountScreen {
    interface Host {
        Ui ui();
        boolean isTv();
        BoostyApi api();
        void back();
        void clearSession();
        <T> void readInBackground(Callable<T> request, Consumer<T> done, Consumer<Exception> failed);
    }

    private final Host host;
    private final Ui ui;
    private final LinearLayout screen;
    private final TextView name;
    private final TextView subtitle;
    private final FrameLayout avatar;

    AccountScreen(Host host) {
        this.host = host;
        this.ui = host.ui();
        screen = ui.column();
        screen.addView(ui.toolbar(ui.context().getString(R.string.nav_account), true, host::back));

        ScrollView scroll = new ScrollView(ui.context());
        scroll.setFillViewport(true);
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(host.isTv() ? 38 : 16), ui.dp(4),
            ui.dp(host.isTv() ? 38 : 16), ui.dp(24));

        LinearLayout profile = ui.row();
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        profile.setBackground(ui.shape(ui.surface, 22, 0));
        avatar = new FrameLayout(ui.context());
        avatar.setBackground(ui.shape(Ui.ICON_SURFACE, 28, 0));
        avatar.setClipToOutline(true);
        ImageView logo = new ImageView(ui.context());
        logo.setImageResource(R.drawable.app_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(logo, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        profile.addView(avatar, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));

        LinearLayout text = ui.column();
        text.setPadding(ui.dp(16), 0, 0, 0);
        name = ui.label(ui.context().getString(R.string.account_profile_loading), 20, Ui.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        subtitle = ui.label(ui.context().getString(R.string.account_profile_signed_in), 14, Ui.MUTED, false);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setPadding(0, ui.dp(4), 0, 0);
        text.addView(subtitle);
        profile.addView(text, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        content.addView(profile, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        content.addView(ui.settingsSection(ui.context().getString(R.string.account_section_session)));
        content.addView(ui.settingsRow(R.drawable.ic_logout,
            ui.context().getString(R.string.account_logout),
            ui.context().getString(R.string.account_logout_hint), "", this::confirmLogout), rowParams());

        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        host.readInBackground(() -> host.api().currentUser(), this::showProfile, failure -> {});
    }

    View view() {
        return screen;
    }

    void focus() {
        if (host.isTv()) screen.requestFocus();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        params.topMargin = ui.dp(8);
        return params;
    }

    private void showProfile(BoostyApi.Profile profile) {
        if (!profile.name().isEmpty()) name.setText(profile.name());
        else name.setText(ui.context().getString(R.string.app_name));
        if (!profile.email().isEmpty()) {
            subtitle.setText(profile.email());
        } else {
            subtitle.setText(ui.context().getString(R.string.account_profile_signed_in));
        }
        if (!Post.https(profile.avatar()).isEmpty()) {
            avatar.removeAllViews();
            ImageView image = ui.image(profile.avatar(), ImageView.ScaleType.CENTER_CROP);
            avatar.addView(image, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(ui.context())
            .setMessage(ui.context().getString(R.string.account_logout_confirm))
            .setNegativeButton(ui.context().getString(R.string.cancel), null)
            .setPositiveButton(ui.context().getString(R.string.account_logout_button),
                (confirm, button) -> host.readInBackground(() -> {
                    host.api().signOut();
                    return true;
                }, done -> host.clearSession(), failure -> host.clearSession()))
            .show();
    }
}
