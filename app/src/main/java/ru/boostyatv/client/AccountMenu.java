package ru.boostyatv.client;

import android.app.AlertDialog;
import android.widget.TextView;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** The "⋯" account menu: refresh, settings, about and sign-out. */
final class AccountMenu {
    interface Host {
        Ui ui();
        BoostyApi api();
        boolean busy();
        void refreshFeed();
        void showSettings();
        void showAbout();
        <T> void readInBackground(Callable<T> request, Consumer<T> done, Consumer<Exception> failed);
        void clearSession();
    }

    private AccountMenu() {}

    static void show(Host host) {
        if (host.busy()) return;
        Ui ui = host.ui();
        new AlertDialog.Builder(ui.context())
            .setItems(new String[]{ui.context().getString(R.string.account_refresh_feed),
                ui.context().getString(R.string.account_settings),
                ui.context().getString(R.string.account_about),
                ui.context().getString(R.string.account_logout)}, (dialog, item) -> {
                if (item == 0) {
                    host.refreshFeed();
                } else if (item == 1) {
                    host.showSettings();
                } else if (item == 2) {
                    host.showAbout();
                } else {
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
            }).show();
    }
}
