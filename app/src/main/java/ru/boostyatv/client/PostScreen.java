package ru.boostyatv.client;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** A post screen with text, media, reactions and comments. */
final class PostScreen {
    interface Host {
        Ui ui();
        BoostyApi api();
        WatchHistory history();
        boolean isTv();
        boolean busy();
        void replace(View view, boolean push);
        void back();
        void refreshLabels();
        void openPlayer(Post.Block block);
        <T> void readInBackground(Callable<T> request, Consumer<T> done, Consumer<Exception> failed);
    }

    private record ReactionTarget(String post, String comment) {}

    private final Host host;
    private final Ui ui;
    private final Context context;
    private final Post post;
    private final Map<ReactionTarget, Reactions> localReactions = new HashMap<>();
    private final Set<ReactionTarget> pendingReactions = new HashSet<>();
    private final LinearLayout screen;
    private final ScrollView scroll;
    private View firstVideo;

    PostScreen(Host host, Post post) {
        this.host = host;
        this.ui = host.ui();
        this.context = ui.context();
        this.post = post;
        this.screen = ui.column();
        this.scroll = new ScrollView(context);
        build();
    }

    View view() {
        return screen;
    }

    void focus() {
        if (!host.isTv()) return;
        if (firstVideo != null) firstVideo.requestFocus();
        else scroll.requestFocus();
    }

    private void build() {
        LinearLayout bar = ui.toolbar(context.getString(R.string.post_title), true, host::back);
        screen.addView(bar);
        LinearLayout article = ui.column();
        boolean tv = host.isTv();
        int horizontal = tv ? Math.max(38, (context.getResources().getConfiguration().screenWidthDp - 760) / 2) : 20;
        article.setPadding(ui.dp(horizontal), ui.dp(10), ui.dp(horizontal), ui.dp(30));
        scroll.addView(article);
        screen.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        String subtitle = post.author();
        if (post.published() > 0) {
            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                .format(new Date(post.published() * 1000L));
            subtitle = subtitle.isEmpty() ? date : subtitle + "  ·  " + date;
        }
        article.addView(ui.label(subtitle, 15, Ui.ACCENT, true));
        ui.margin(article, ui.label(post.displayTitle(context), tv ? 33 : 28, Ui.INK, true),
            Ui.MATCH, Ui.WRAP, 8);
        if (!post.allowed()) {
            ui.margin(article, ui.label(context.getString(R.string.post_access_required), 16, Ui.ACCENT, false),
                Ui.MATCH, Ui.WRAP, 16);
        }
        for (Post.Block block : post.blocks()) {
            switch (block.kind()) {
                case "text" -> {
                    TextView body = ui.label(block.text(), tv ? 18 : 16, Ui.INK, false);
                    body.setLineSpacing(ui.dp(4), 1f);
                    ui.margin(article, body, Ui.MATCH, Ui.WRAP, 14);
                }
                case "image" -> {
                    ImageView photo = ui.image(block.image(), ImageView.ScaleType.FIT_CENTER);
                    photo.setBackground(ui.shape(ui.surface, 12, 0));
                    photo.setClipToOutline(true);
                    ui.margin(article, photo, Ui.MATCH, ui.dp(tv ? 290 : 230), 14);
                }
                case "video" -> {
                    if (!block.image().isEmpty()) {
                        FrameLayout preview = ui.videoPreview(tv);
                        preview.addView(ui.image(block.image(), ImageView.ScaleType.FIT_CENTER),
                            new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
                        preview.setBackground(ui.shape(ui.surface, 12, 0));
                        preview.setClipToOutline(true);
                        ui.margin(article, preview, Ui.MATCH, Ui.WRAP, 14);
                    }
                    if (block.sources().isEmpty()) {
                        ui.margin(article, ui.label(context.getString(R.string.post_video_unavailable),
                            15, Ui.MUTED, false), Ui.MATCH, Ui.WRAP, 8);
                    } else {
                        String name = block.text().isEmpty()
                            ? context.getString(R.string.post_watch_video)
                            : context.getString(R.string.post_video_play_with_title, block.text());
                        TextView play = ui.button(name, true, () -> host.openPlayer(block));
                        Ui.onAttach(play, () -> {
                            long position = host.history().get(block).resume();
                            play.setText(position > 0
                                ? context.getString(R.string.post_continue_watching,
                                    TimeFormat.format(context, position)) : name);
                        });
                        ui.margin(article, play, Ui.MATCH, ui.dp(49), 8);
                        if (firstVideo == null) firstVideo = play;
                    }
                }
            }
        }
        if (post.allowed() && !post.reactionsDisabled() && !post.slug().isEmpty()) {
            addReactions(article, post, "", post.reactions());
        }
        boolean hasComments = post.allowed() && !post.commentsDenied() && !post.slug().isEmpty();
        LinearLayout comments = ui.column();
        if (hasComments) {
            TextView title = ui.label(context.getString(R.string.post_comments), 22, Ui.INK, true);
            title.setFocusable(tv);
            comments.addView(title);
            ui.margin(article, comments, Ui.MATCH, Ui.WRAP, 22);
            bar.addView(ui.button(context.getString(R.string.post_comments), false, () -> {
                if (tv) title.requestFocus();
                scroll.smoothScrollTo(0, comments.getTop());
            }));
        }
        if (hasComments) loadComments(comments, post, 0, "");
    }

    private void loadComments(LinearLayout list, Post post, long parent, String offset) {
        TextView more = ui.button(context.getString(R.string.loading), false, () -> {});
        more.setClickable(false);
        more.setFocusable(false);
        ui.margin(list, more, Ui.MATCH, ui.dp(48), 10);
        host.readInBackground(() -> host.api().comments(post, parent, offset), page -> {
            list.removeView(more);
            for (BoostyApi.Comment comment : page.items()) {
                renderComment(list, post, comment, parent == 0);
            }
            if (!page.next().isEmpty()) {
                TextView next = ui.button(context.getString(R.string.loading_more), false, () -> {});
                next.setOnClickListener(view -> {
                    if (host.busy()) return;
                    list.removeView(next);
                    loadComments(list, post, parent, page.next());
                });
                ui.margin(list, next, Ui.MATCH, ui.dp(48), 10);
            } else if (page.items().isEmpty() && offset.isEmpty()) {
                ui.margin(list, ui.label(parent == 0
                        ? context.getString(R.string.post_no_comments)
                        : context.getString(R.string.post_no_replies),
                    16, Ui.MUTED, false), Ui.MATCH, Ui.WRAP, 8);
            }
        }, failure -> {
            more.setText(context.getString(R.string.error_load_failed));
            ui.makeAction(more, () -> {
                list.removeView(more);
                loadComments(list, post, parent, offset);
            });
        });
    }

    private void renderComment(LinearLayout list, Post post, BoostyApi.Comment comment, boolean showReplies) {
        LinearLayout item = ui.column();
        item.setFocusable(host.isTv());
        item.setBackground(ui.control(ui.surface, ui.focusedSurface, 12));
        item.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        String author = comment.author().isEmpty()
            ? context.getString(R.string.post_author_unknown) : comment.author();
        if (comment.created() > 0) {
            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            author += "  ·  " + DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                .format(new Date(comment.created() * 1000L));
        }
        item.addView(ui.label(author, 14, Ui.ACCENT, true));
        for (Post.Block block : comment.blocks()) {
            if (block.kind().equals("text")) {
                ui.margin(item, ui.label(block.text(), 16, Ui.INK, false), Ui.MATCH, Ui.WRAP, 7);
            } else if (block.kind().equals("image")) {
                ui.margin(item, ui.image(block.image(), ImageView.ScaleType.FIT_CENTER),
                    Ui.MATCH, ui.dp(140), 7);
            }
        }
        if (!post.reactionsDisabled() && !comment.reactionId().isEmpty()) {
            addReactions(item, post, comment.reactionId(), comment.reactions());
        }
        if (showReplies && comment.id() > 0 && comment.replyCount() > 0) {
            LinearLayout replies = ui.column();
            replies.setPadding(ui.dp(12), 0, 0, 0);
            replies.setVisibility(View.GONE);
            String expand = context.getString(R.string.post_replies, comment.replyCount());
            TextView toggle = ui.button(expand, false, () -> {});
            ui.makeAction(toggle, () -> {
                boolean opening = replies.getVisibility() != View.VISIBLE;
                replies.setVisibility(opening ? View.VISIBLE : View.GONE);
                toggle.setText(opening ? context.getString(R.string.post_collapse_replies) : expand);
                if (opening && replies.getChildCount() == 0) loadComments(replies, post, comment.id(), "");
            });
            ui.margin(item, toggle, Ui.WRAP, ui.dp(42), 8);
            item.addView(replies);
        }
        ui.margin(list, item, Ui.MATCH, Ui.WRAP, 8);
    }

    private void addReactions(LinearLayout section, Post post, String comment, Reactions initial) {
        ReactionTarget target = new ReactionTarget(post.id(), comment);
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView change = ui.button(context.getString(R.string.post_reaction_button), false, () -> {});
        line.addView(change, new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(42)));
        TextView counts = ui.label("", 15, Ui.MUTED, false);
        counts.setPadding(ui.dp(12), 0, 0, 0);
        line.addView(counts, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Runnable redraw = () -> {
            Reactions state = localReactions.getOrDefault(target, initial);
            List<String> totals = new ArrayList<>();
            String selected = context.getString(R.string.post_reaction_button);
            for (Reactions.Type type : Reactions.Type.values()) {
                int count = state.count(type);
                if (count > 0) totals.add(type.emoji + " " + count);
                if (type.code.equals(state.selected())) selected = type.emoji + " " + type.label(context);
            }
            change.setText(selected);
            counts.setText(String.join("   ", totals));
            change.setEnabled(!pendingReactions.contains(target));
        };
        redraw.run();
        Ui.onAttach(change, redraw);
        ui.makeAction(change, () -> {
            if (!pendingReactions.add(target)) return;
            redraw.run();
            host.readInBackground(() -> host.api().reactionTypes(post.slug()), types -> {
                pendingReactions.remove(target);
                host.refreshLabels();
                if (change.isAttachedToWindow()) showReactionPicker(post, comment, initial, target, types, redraw);
            }, failure -> {
                pendingReactions.remove(target);
                host.refreshLabels();
                Toast.makeText(context, context.getString(R.string.post_reaction_load_failed),
                    Toast.LENGTH_LONG).show();
            });
        });
        ui.margin(section, line, Ui.MATCH, Ui.WRAP, 10);
    }

    private void showReactionPicker(Post post, String comment, Reactions initial, ReactionTarget target,
                                    List<Reactions.Type> types, Runnable redraw) {
        Reactions state = localReactions.getOrDefault(target, initial);
        if (types.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.post_reaction_disabled),
                Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = new String[types.size() + 1];
        int checked = -1;
        for (int i = 0; i < types.size(); i++) {
            Reactions.Type type = types.get(i);
            options[i] = type.emoji + "  " + type.label(context);
            if (type.code.equals(state.selected())) checked = i;
        }
        options[types.size()] = context.getString(R.string.post_reaction_remove);
        new AlertDialog.Builder(context).setTitle(context.getString(R.string.post_reaction_dialog_title))
            .setSingleChoiceItems(options, checked, (dialog, index) -> {
                dialog.dismiss();
                String selected = index == types.size() ? "" : types.get(index).code;
                if (index < types.size() && selected.equals(state.selected())) selected = "";
                String choice = selected;
                if (!pendingReactions.add(target)) return;
                redraw.run();
                host.readInBackground(() -> {
                    host.api().setReaction(post, comment, choice);
                    return true;
                }, done -> {
                    localReactions.put(target, state.withSelected(choice));
                    pendingReactions.remove(target);
                    redraw.run();
                    host.refreshLabels();
                }, failure -> {
                    pendingReactions.remove(target);
                    redraw.run();
                    host.refreshLabels();
                    Toast.makeText(context, context.getString(R.string.post_reaction_save_failed),
                        Toast.LENGTH_LONG).show();
                });
            }).setNegativeButton(context.getString(R.string.cancel), null).show();
    }
}
