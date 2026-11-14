package ru.boostyatv.client;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** The list of subscribed channels. */
final class AuthorsScreen {
    interface Host {
        Ui ui();
        BoostyApi api();
        boolean isTv();
        boolean busy();
        void replace(View view, boolean push);
        void showFeed(String slug, String name, boolean push);
        void back();
        <T> void perform(Callable<T> request, TextView error, Consumer<T> done);
        <T> void perform(Callable<T> request, TextView error, Consumer<T> done, Runnable failed);
    }

    private final Host host;
    private final Ui ui;
    private final LinearLayout screen;
    private final LinearLayout authors;
    private final TextView error;

    AuthorsScreen(Host host) {
        this.host = host;
        this.ui = host.ui();
        screen = ui.column();
        screen.addView(ui.toolbar(ui.context().getString(R.string.feed_subscriptions), true, host::back));
        ScrollView scroll = new ScrollView(ui.context());
        scroll.setPadding(ui.dp(host.isTv() ? 38 : 16), ui.dp(8), ui.dp(host.isTv() ? 38 : 16), ui.dp(24));
        authors = ui.column();
        scroll.addView(authors);
        screen.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        error = ui.label("", 15, Ui.ACCENT, false);
        error.setVisibility(View.GONE);
        authors.addView(error);
        load(0);
    }

    View view() {
        return screen;
    }

    private void load(int offset) {
        TextView more = ui.button(ui.context().getString(R.string.loading), false, () -> {});
        ui.margin(authors, more, Ui.MATCH, ui.dp(48), 8);
        more.setOnClickListener(view -> {
            if (host.busy()) return;
            authors.removeView(more);
            load(offset);
        });
        host.perform(() -> host.api().authors(offset), error, page -> {
            boolean focused = more.hasFocus();
            authors.removeView(more);
            View first = null;
            for (BoostyApi.Author author : page.items()) {
                LinearLayout item = ui.row();
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(ui.dp(12), 0, ui.dp(12), 0);
                item.setBackground(ui.control(ui.surface, ui.focusedSurface, 12));
                ImageView avatar = ui.image(author.image(), ImageView.ScaleType.CENTER_CROP);
                avatar.setBackground(ui.shape(0xff332b38, Ui.RADIUS_PILL, 0));
                avatar.setClipToOutline(true);
                item.addView(avatar, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
                TextView name = ui.label(author.name(), 17, Ui.INK, true);
                name.setPadding(ui.dp(14), 0, ui.dp(14), 0);
                item.addView(name, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
                item.addView(ui.label("›", 26, Ui.ACCENT, false));
                ui.makeAction(item, () -> host.showFeed(author.slug(), author.name(), true));
                ui.margin(authors, item, Ui.MATCH, ui.dp(64), 8);
                if (first == null) first = item;
            }
            if (page.next() >= 0) {
                TextView next = ui.button(ui.context().getString(R.string.loading_more), false, () -> {});
                next.setOnClickListener(view -> {
                    if (host.busy()) return;
                    authors.removeView(next);
                    load(page.next());
                });
                ui.margin(authors, next, Ui.MATCH, ui.dp(48), 10);
                if (first == null) first = next;
            } else if (offset == 0 && first == null) {
                ui.margin(authors, ui.label(ui.context().getString(R.string.feed_no_subscriptions),
                    17, Ui.MUTED, false), Ui.MATCH, Ui.WRAP, 10);
            }
            if (host.isTv() && (offset == 0 || focused) && first != null) first.requestFocus();
        }, () -> more.setText(ui.context().getString(R.string.retry)));
    }
}
