package ru.boostyatv.client;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Recent playback launches on this device. Posts are reopened with fresh media URLs. */
final class HistoryScreen {
    private HistoryScreen() {}

    static View build(FeedScreen.Host host) {
        Ui ui = host.ui();
        Context context = host.context();
        LinearLayout screen = ui.column();
        screen.addView(ui.toolbar(context.getString(R.string.nav_history), false, () -> {}));
        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setPadding(ui.dp(38), ui.dp(8), ui.dp(38), ui.dp(24));
        LinearLayout list = ui.column();
        scroll.addView(list);
        screen.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        for (WatchHistory.RecentPost post : host.history().recentPosts()) {
            LinearLayout item = ui.row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
            item.setBackground(ui.control(ui.surface, ui.focusedSurface, 12));
            item.addView(ui.icon(R.drawable.ic_play, 24, Ui.ACCENT));
            LinearLayout labels = ui.column();
            TextView title = ui.label(post.title().isEmpty()
                ? context.getString(R.string.post_untitled) : post.title(), 17, Ui.INK, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            labels.addView(ui.label(post.author().isEmpty() ? post.slug() : post.author(), 14, Ui.MUTED, false));
            TextView error = ui.label("", 14, Ui.ACCENT, false);
            error.setVisibility(View.GONE);
            labels.addView(error);
            ui.weighted(item, labels, 16, Ui.WRAP);
            ui.makeAction(item, () -> host.perform(() -> host.api().post(post.slug(), post.id()), error, host::showPost));
            ui.margin(list, item, Ui.MATCH, Ui.WRAP, 8);
        }
        if (list.getChildCount() == 0) {
            list.addView(ui.label(context.getString(R.string.history_empty), 17, Ui.MUTED, false));
        }
        return screen;
    }
}
