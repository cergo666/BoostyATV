package ru.boostyatv.client;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * A feed of posts rendered as a grid. The screen owns the adapter, paging and
 * filters; {@link Host} provides access to the shared activity services.
 */
final class FeedScreen {
    interface Host {
        Context context();
        Ui ui();
        BoostyApi api();
        WatchHistory history();
        boolean isTv();
        boolean busy();
        void replace(View view, boolean push);
        void showPost(Post post);
        void showFeed(String slug, String name, boolean push);
        void syncWatchProgress();
        <T> void perform(Callable<T> request, TextView error, Consumer<T> done);
        <T> void readInBackground(Callable<T> request, Consumer<T> done, Consumer<Exception> failed);
    }

    private static final int HEADER = 0;
    private static final int POST = 1;
    private static final int FOOTER = 2;

    private final Host host;
    private final Ui ui;
    private final Context context;
    private final boolean tv;
    private final RecyclerView list;
    private final SwipeRefreshLayout refresh;
    private final GridLayoutManager layout;
    private final Adapter rows = new Adapter();
    private final LinearLayout header;
    private final TextView error;
    private final String author;
    private final String title;
    private final Set<String> seen = new HashSet<>();
    private final Set<String> requested = new HashSet<>();
    private final List<Post> visible = new ArrayList<>();
    private final List<Post> loaded = new ArrayList<>();
    private final Set<String> selectedTags = new LinkedHashSet<>();
    private int columns;
    private boolean loading;
    private boolean failed;
    private String next = "";
    private int generation;
    private boolean videosOnly;
    private String search = "";
    private List<BoostyApi.Tag> channelTags;
    private TextView filterSummary;
    private int pendingScroll = -1;

    FeedScreen(Host host, String author, String title) {
        this.host = host;
        this.ui = host.ui();
        this.context = host.context();
        this.tv = host.isTv();
        this.author = author;
        this.title = title;
        int width = context.getResources().getConfiguration().screenWidthDp;
        columns = columnsFor(width);
        header = ui.column();
        error = ui.label("", 15, Ui.ACCENT, false);
        buildHeader();

        list = new RecyclerView(context);
        list.setClipToPadding(false);
        list.setPadding(ui.dp(tv ? 38 : 16), ui.dp(8), ui.dp(tv ? 38 : 16), ui.dp(24));
        layout = new GridLayoutManager(context, columns);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return position == 0 || position == visible.size() + 1 ? columns : 1;
            }
        });
        list.setLayoutManager(layout);
        list.setItemAnimator(null);
        list.setAdapter(rows);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                view.post(FeedScreen.this::maybeLoadMore);
            }
        });
        list.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) list.post(FeedScreen.this::updateColumns);
        });
        Ui.onAttach(list, () -> list.post(this::maybeLoadMore));

        refresh = new SwipeRefreshLayout(context);
        refresh.setColorSchemeColors(Ui.ACCENT);
        refresh.setProgressBackgroundColorSchemeColor(ui.surface);
        refresh.setDistanceToTriggerSync(ui.dp(72));
        refresh.addView(list, new SwipeRefreshLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        refresh.setOnRefreshListener(this::reload);
    }

    RecyclerView list() { return list; }
    View container() { return refresh; }
    String author() { return author; }
    String title() { return title; }
    int scrollPosition() { return layout.findFirstVisibleItemPosition(); }
    void scrollTo(int position) { pendingScroll = position; }
    boolean unfiltered() { return search.isEmpty() && selectedTags.isEmpty() && !videosOnly; }

    private void buildHeader() {
        if (author == null) {
            LinearLayout filters = ui.segmented();
            TextView all = ui.segment(context.getString(R.string.feed_all_posts), () -> {});
            TextView videos = ui.segment(context.getString(R.string.feed_videos_only), () -> {});
            all.setSelected(true);
            filters.addView(all, new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(34)));
            filters.addView(videos, new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(34)));
            all.setOnClickListener(view -> {
                if (host.busy() || !videosOnly) return;
                ui.selectSegment(all, true);
                ui.selectSegment(videos, false);
                switchFilter(false);
            });
            videos.setOnClickListener(view -> {
                if (host.busy() || videosOnly) return;
                ui.selectSegment(videos, true);
                ui.selectSegment(all, false);
                switchFilter(true);
            });
            header.addView(filters, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        } else {
            LinearLayout filters = ui.row();
            LinearLayout search = ui.actionChip(R.drawable.ic_search,
                context.getString(R.string.feed_channel_search), this::searchChannel);
            LinearLayout tags = ui.actionChip(R.drawable.ic_tag,
                context.getString(R.string.feed_channel_tags), this::chooseTags);
            filters.addView(search, new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(40)));
            LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(40));
            tagParams.leftMargin = ui.dp(8);
            filters.addView(tags, tagParams);
            header.addView(filters);
            filterSummary = ui.label("", 14, Ui.MUTED, false);
            filterSummary.setVisibility(View.GONE);
            ui.margin(header, filterSummary, Ui.MATCH, Ui.WRAP, 6);
        }
        header.addView(error);
        error.setVisibility(View.GONE);
    }

    private int columnsFor(int widthDp) {
        return tv ? (widthDp >= 920 ? 4 : 3) : (widthDp >= 760 ? 3 : widthDp >= 560 ? 2 : 1);
    }

    private void updateColumns() {
        if (list.getWidth() == 0) return;
        int desired = columnsFor(Math.round(list.getWidth() / context.getResources().getDisplayMetrics().density));
        if (desired == columns) return;
        columns = desired;
        layout.setSpanCount(columns);
        rows.notifyDataSetChanged();
    }

    private void searchChannel() {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(context.getString(R.string.feed_search_dialog_hint));
        input.setText(search);
        input.setSelection(input.length());
        LinearLayout form = ui.column();
        form.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        form.addView(input, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        new AlertDialog.Builder(context).setTitle(context.getString(R.string.feed_search_dialog_title))
            .setView(form)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .setNeutralButton(context.getString(R.string.feed_tags_reset), (dialog, which) -> {
                search = "";
                reload();
            })
            .setPositiveButton(context.getString(R.string.feed_search_dialog_find), (dialog, which) -> {
                search = input.getText().toString().trim();
                reload();
            }).show();
    }

    private void chooseTags() {
        if (channelTags != null) showTags();
        else host.perform(() -> host.api().tags(author), error, tags -> {
            channelTags = tags;
            showTags();
        });
    }

    private void showTags() {
        if (channelTags.isEmpty()) {
            new AlertDialog.Builder(context).setMessage(context.getString(R.string.feed_tags_no_tags))
                .setPositiveButton(context.getString(R.string.ok), null).show();
            return;
        }
        Set<String> draft = new LinkedHashSet<>(selectedTags);
        String[] names = channelTags.stream().map(BoostyApi.Tag::name).toArray(String[]::new);
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) checked[i] = draft.contains(channelTags.get(i).id());
        new AlertDialog.Builder(context).setTitle(context.getString(R.string.feed_tags_dialog_title))
            .setMultiChoiceItems(names, checked, (dialog, which, enabled) -> {
                String id = channelTags.get(which).id();
                if (enabled) draft.add(id);
                else draft.remove(id);
            })
            .setNegativeButton(context.getString(R.string.cancel), null)
            .setNeutralButton(context.getString(R.string.feed_tags_reset), (dialog, which) -> {
                selectedTags.clear();
                reload();
            })
            .setPositiveButton(context.getString(R.string.feed_tags_apply), (dialog, which) -> {
                selectedTags.clear();
                selectedTags.addAll(draft);
                reload();
            }).show();
    }

    void reload() {
        List<String> active = new ArrayList<>();
        if (!search.isEmpty()) active.add(context.getString(R.string.feed_filter_search, search));
        if (channelTags != null) {
            for (BoostyApi.Tag tag : channelTags) {
                if (selectedTags.contains(tag.id())) {
                    active.add(context.getString(R.string.feed_filter_tag, tag.name()));
                }
            }
        }
        if (filterSummary != null) {
            filterSummary.setText(String.join("  ·  ", active));
            filterSummary.setVisibility(active.isEmpty() ? View.GONE : View.VISIBLE);
        }
        generation++;
        loaded.clear();
        seen.clear();
        requested.clear();
        visible.clear();
        next = "";
        loading = false;
        failed = false;
        rows.notifyDataSetChanged();
        list.scrollToPosition(0);
        load("");
    }

    private void switchFilter(boolean value) {
        videosOnly = value;
        visible.clear();
        for (Post post : loaded) if (matches(post)) visible.add(post);
        rows.notifyDataSetChanged();
        list.scrollToPosition(0);
        list.post(this::maybeLoadMore);
    }

    private boolean matches(Post post) {
        return FeedFilter.matches(post, search, videosOnly);
    }

    private void maybeLoadMore() {
        if (!list.isAttachedToWindow() || loading || failed || next.isEmpty()) return;
        if (visible.isEmpty() || layout.findLastVisibleItemPosition() >= visible.size()) load(next);
    }

    void load(String offset) {
        if (loading || !requested.add(offset)) return;
        loading = true;
        failed = false;
        rows.notifyItemChanged(Math.max(0, rows.getItemCount() - 1));
        int requestGeneration = generation;
        String query = search;
        String tags = String.join(",", selectedTags);
        host.readInBackground(() -> host.api().posts(author, offset, query, tags), page -> {
            if (requestGeneration != generation) return;
            int previous = visible.size();            for (Post post : page.posts()) {
                if (seen.add(post.id())) {
                    loaded.add(post);
                    if (matches(post)) visible.add(post);
                }
            }
            next = requested.contains(page.next()) ? "" : page.next();
            loading = false;
            refresh.setRefreshing(false);
            int added = visible.size() - previous;
            if (added > 0) rows.notifyItemRangeInserted(previous + 1, added);
            rows.notifyItemChanged(Math.max(0, rows.getItemCount() - 1));
            if (pendingScroll >= 0) {
                layout.scrollToPosition(pendingScroll);
                pendingScroll = -1;
            }
            list.post(this::maybeLoadMore);
        }, failure -> {
            if (requestGeneration != generation) return;
            requested.remove(offset);
            next = offset;
            loading = false;
            failed = true;
            refresh.setRefreshing(false);
            rows.notifyItemChanged(Math.max(0, rows.getItemCount() - 1));
        });
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return visible.size() + 2; }

        @Override public int getItemViewType(int position) {
            if (position == 0) return HEADER;
            return position == visible.size() + 1 ? FOOTER : POST;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View item;
            if (type == HEADER) {
                if (header.getParent() instanceof ViewGroup group) group.removeView(header);
                item = header;
            } else if (type == FOOTER) item = ui.label("", 14, Ui.MUTED, false);
            else item = new PostCard(host, ui);
            if (type == FOOTER) {
                TextView footer = (TextView) item;
                footer.setGravity(Gravity.CENTER);
                footer.setPadding(ui.dp(16), ui.dp(22), ui.dp(16), ui.dp(28));
                footer.setOnClickListener(clicked -> {
                    if (failed) load(next);
                });
            }
            item.setLayoutParams(new RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP));
            return new RecyclerView.ViewHolder(item) {};
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == 0) return;
            if (getItemViewType(position) == FOOTER) {
                TextView footer = (TextView) holder.itemView;
                footer.setText(failed ? context.getString(R.string.error_load_failed)
                    : loading ? context.getString(R.string.loading)
                    : !next.isEmpty() ? context.getString(R.string.feed_loading_publications)
                    : context.getString(R.string.no_more));
                footer.setTextColor(failed ? Ui.ACCENT : Ui.MUTED);
                footer.setVisibility(failed || loading || !next.isEmpty() || visible.isEmpty()
                    ? View.VISIBLE : View.GONE);
                footer.setFocusable(failed);
                footer.setClickable(failed);
                return;
            }
            ((PostCard) holder.itemView).bind(visible.get(position - 1));
        }
    }

    /** A single post card that can be rebound without rebuilding its view tree. */
    private static final class PostCard extends LinearLayout {
        private final Host host;
        private final Ui ui;
        private final TextView placeholder;
        private final ImageView image;
        private final FrameLayout playBadge;
        private final ProgressBar progress;
        private final TextView author;
        private final TextView title;
        private final Runnable refreshProgress = this::updateProgress;
        private Post post;

        PostCard(Host host, Ui ui) {
            super(host.context());
            this.host = host;
            this.ui = ui;
            setOrientation(VERTICAL);
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            setBackground(ui.control(ui.surface, ui.focusedSurface, Ui.RADIUS_CARD));
            setClipToOutline(true);
            if (host.isTv()) {
                StateListDrawable outline = new StateListDrawable();
                outline.addState(new int[]{android.R.attr.state_focused},
                    ui.shape(Color.TRANSPARENT, Ui.RADIUS_MEDIA, Ui.INK));
                outline.addState(new int[]{}, ui.shape(Color.TRANSPARENT, Ui.RADIUS_MEDIA, 0));
                setForeground(outline);
            }

            FrameLayout cover = ui.videoPreview(host.isTv());
            cover.setBackground(ui.shape(0xff302633, Ui.RADIUS_MEDIA, 0));
            cover.setClipToOutline(true);
            image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setVisibility(GONE);
            cover.addView(image, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            placeholder = ui.label("", 35, Ui.ACCENT, true);
            placeholder.setGravity(Gravity.CENTER);
            cover.addView(placeholder, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            playBadge = new FrameLayout(getContext());
            playBadge.setBackground(ui.shape(0xd91a141b, Ui.RADIUS_PILL, 0));
            ImageView playIcon = ui.icon(R.drawable.ic_play, 16, Ui.INK);
            playBadge.addView(playIcon, new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER));
            playBadge.setContentDescription(getContext().getString(R.string.placeholder_video));
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40),
                Gravity.BOTTOM | Gravity.END);
            corner.setMargins(0, 0, ui.dp(10), ui.dp(10));
            cover.addView(playBadge, corner);
            progress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff382d36));
            cover.addView(progress, new FrameLayout.LayoutParams(Ui.MATCH, ui.dp(5), Gravity.BOTTOM));
            Ui.onAttach(progress, refreshProgress);
            addView(cover, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

            LinearLayout meta = ui.column();
            meta.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14));
            title = ui.label("", host.isTv() ? 17 : 16, Ui.INK, true);
            title.setMaxLines(2);
            title.setLineSpacing(ui.dp(2), 1f);
            title.setEllipsize(TextUtils.TruncateAt.END);
            meta.addView(title);
            LinearLayout authorRow = ui.row();
            authorRow.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(getContext());
            dot.setBackground(ui.shape(Ui.ACCENT, Ui.RADIUS_PILL, 0));
            authorRow.addView(dot, new LinearLayout.LayoutParams(ui.dp(5), ui.dp(5)));
            author = ui.label("", 12, Ui.MUTED, false);
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            author.setPadding(ui.dp(7), 0, 0, 0);
            authorRow.addView(author, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            ui.margin(meta, authorRow, Ui.MATCH, Ui.WRAP, 6);
            addView(meta);
            ui.makeAction(this, () -> host.showPost(post));
        }

        void bind(Post post) {
            this.post = post;
            boolean hasCover = !post.cover().isEmpty();
            image.setVisibility(hasCover ? VISIBLE : GONE);
            placeholder.setText(post.hasVideo() ? getContext().getString(R.string.placeholder_video)
                : getContext().getString(R.string.placeholder_image));
            placeholder.setVisibility(hasCover ? GONE : VISIBLE);
            if (hasCover) ui.bindImage(image, post.cover(), ImageView.ScaleType.FIT_CENTER);
            playBadge.setVisibility(post.hasVideo() ? VISIBLE : GONE);
            progress.setVisibility(post.hasVideo() ? VISIBLE : GONE);
            title.setText(post.displayTitle(getContext()));
            author.setText(post.author().isEmpty()
                ? getContext().getString(R.string.post_app_name) : post.author());
            setContentDescription(post.displayTitle(getContext()) + ", " + author.getText()
                + (post.hasVideo() ? getContext().getString(R.string.post_video_suffix) : ""));
            updateProgress();
        }

        private void updateProgress() {
            if (post == null) return;
            int percent = post.blocks().stream().filter(block -> !block.sources().isEmpty())
                .mapToInt(block -> host.history().get(block).percentage()).max().orElse(0);
            progress.setProgress(percent);
            progress.setContentDescription(getContext().getString(R.string.player_progress_watched, percent));
            progress.setVisibility(percent == 0 || !post.hasVideo() ? GONE : VISIBLE);
        }
    }
}
