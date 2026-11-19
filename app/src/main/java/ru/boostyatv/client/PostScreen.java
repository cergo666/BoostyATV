package ru.boostyatv.client;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
        void openPlayer(Post post, Post.Block block, boolean fromStart);
        void showTag(Post post, BoostyApi.Tag tag);
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
        boolean tv = host.isTv();
        LinearLayout bar = tv ? null : ui.toolbar(context.getString(R.string.post_title), true, host::back);
        if (!tv) screen.addView(bar);
        else scroll.setVerticalScrollBarEnabled(false);
        LinearLayout article = ui.column();
        boolean hasComments = post.allowed() && !post.commentsDenied() && !post.slug().isEmpty();
        LinearLayout comments = ui.column();
        int horizontal = tv && !hasComments
            ? Math.max(48, (context.getResources().getConfiguration().screenWidthDp - 760) / 2) : tv ? 0 : 20;
        article.setPadding(ui.dp(horizontal), ui.dp(tv ? (hasComments ? 0 : 27) : 10),
            ui.dp(horizontal), ui.dp(30));
        scroll.addView(article);
        if (tv && hasComments) {
            LinearLayout panels = ui.row();
            panels.setPadding(ui.dp(48), ui.dp(27), ui.dp(48), ui.dp(27));
            panels.addView(scroll, new LinearLayout.LayoutParams(0, Ui.MATCH, 3));
            addCommentsPanel(panels, comments);
            screen.addView(panels, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        } else {
            screen.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        }

        TextView heading = ui.label(post.displayTitle(context), 28, Ui.INK, true);
        if (tv) {
            LinearLayout title = ui.row();
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.addView(ui.iconButton(R.drawable.ic_back, context.getString(R.string.nav_back), host::back),
                new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            ui.weighted(title, heading, 12, Ui.WRAP);
            article.addView(title);
        } else article.addView(heading);
        String metadata = post.displayMetadata(context);
        String subtitle = post.author().isEmpty() ? metadata
            : metadata.isEmpty() ? post.author() : post.author() + "  ·  " + metadata;
        if (!subtitle.isEmpty()) ui.margin(article, ui.label(subtitle, 15, Ui.MUTED, false), Ui.MATCH, Ui.WRAP, 4);
        if (!post.allowed()) {
            ui.margin(article, ui.label(context.getString(R.string.post_access_required), 16, Ui.ACCENT, false),
                Ui.MATCH, Ui.WRAP, 16);
        }
        int featured = -1;
        if (tv) {
            for (int i = 0; i < post.blocks().size(); i++) {
                if (!post.blocks().get(i).sources().isEmpty()) {
                    featured = i;
                    addVideo(article, post.blocks().get(i));
                    break;
                }
            }
        }
        addTags(article);
        for (int i = 0; i < post.blocks().size(); i++) {
            if (i != featured) addBlock(article, post.blocks().get(i));
        }
        if (post.allowed() && !post.reactionsDisabled() && !post.slug().isEmpty()) {
            addReactions(article, post, "", post.reactions());
        }
        if (hasComments && !tv) {
            TextView title = ui.label(context.getString(R.string.post_comments), 22, Ui.INK, true);
            comments.addView(title);
            ui.margin(article, comments, Ui.MATCH, Ui.WRAP, 22);
            bar.addView(ui.button(context.getString(R.string.post_comments), false,
                () -> scroll.smoothScrollTo(0, comments.getTop())));
        }
        if (hasComments) loadComments(comments, post, 0, "");
    }

    private void addCommentsPanel(LinearLayout panels, LinearLayout comments) {
        LinearLayout panel = ui.column();
        panel.setBackground(ui.shape(ui.surface, Ui.RADIUS_MEDIA, 0));
        panel.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(8));
        panel.addView(ui.label(context.getString(R.string.post_comments), 21, Ui.INK, true));
        ScrollView commentScroll = new ScrollView(context);
        commentScroll.addView(comments);
        panel.addView(commentScroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.MATCH, 2);
        params.leftMargin = ui.dp(24);
        panels.addView(panel, params);
    }

    private void addTags(LinearLayout article) {
        if (post.tags().isEmpty() || post.slug().isEmpty()) return;
        HorizontalScrollView strip = new HorizontalScrollView(context);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setFocusable(false);
        LinearLayout chips = ui.row();
        strip.addView(chips);
        for (BoostyApi.Tag tag : post.tags()) {
            TextView chip = ui.button("#" + tag.name(), false, () -> host.showTag(post, tag));
            chip.setMaxWidth(ui.dp(280));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(44));
            params.rightMargin = ui.dp(8);
            chips.addView(chip, params);
        }
        ui.margin(article, strip, Ui.MATCH, Ui.WRAP, 10);
    }

    private void addBlock(LinearLayout article, Post.Block block) {
        switch (block.kind()) {
            case "text" -> {
                TextView body = ui.label(block.text(), host.isTv() ? 18 : 16, Ui.INK, false);
                body.setLineSpacing(ui.dp(4), 1f);
                ui.margin(article, body, Ui.MATCH, Ui.WRAP, 14);
            }
            case "image" -> {
                ImageView photo = ui.image(block.image(), ImageView.ScaleType.FIT_CENTER);
                photo.setBackground(ui.shape(ui.surface, Ui.RADIUS_MEDIA, 0));
                photo.setClipToOutline(true);
                ui.margin(article, photo, Ui.MATCH, ui.dp(host.isTv() ? 290 : 230), 14);
            }
            case "video" -> addVideo(article, block);
        }
    }

    private void addVideo(LinearLayout article, Post.Block block) {
        if (!block.image().isEmpty()) {
            FrameLayout preview = ui.videoPreview(host.isTv() ? 270 : 320);
            preview.addView(firstVideo == null && !block.sources().isEmpty() ? ui.featuredImage(block.image())
                    : ui.image(block.image(), ImageView.ScaleType.FIT_CENTER),
                new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            preview.setBackground(ui.shape(ui.surface, Ui.RADIUS_MEDIA, 0));
            preview.setClipToOutline(true);
            ui.margin(article, preview, Ui.MATCH, Ui.WRAP, 12);
        }
        if (block.sources().isEmpty()) {
            ui.margin(article, ui.label(context.getString(R.string.post_video_unavailable),
                15, Ui.MUTED, false), Ui.MATCH, Ui.WRAP, 8);
            return;
        }
        String startLabel = context.getString(R.string.post_play_from_start);
        String playLabel = host.isTv() ? context.getString(R.string.post_play) : startLabel;
        TextView play = ui.button(playLabel, true,
            () -> host.openPlayer(post, block, false));
        TextView restart = ui.button(startLabel, false, () -> host.openPlayer(post, block, true));
        restart.setVisibility(View.GONE);
        Ui.onAttach(play, () -> {
            long position = host.history().get(block).resume();
            play.setText(position > 0 ? context.getString(R.string.post_continue_watching,
                TimeFormat.format(context, position)) : playLabel);
            restart.setVisibility(position > 0 ? View.VISIBLE : View.GONE);
        });
        if (host.isTv()) {
            LinearLayout actions = ui.row();
            actions.addView(play, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            ui.weighted(actions, restart, 8, ui.dp(48));
            ui.margin(article, actions, Ui.MATCH, Ui.WRAP, 8);
        } else {
            ui.margin(article, play, Ui.MATCH, ui.dp(49), 8);
            ui.margin(article, restart, Ui.MATCH, ui.dp(44), 6);
        }
        if (firstVideo == null) firstVideo = play;
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
        item.setBackground(ui.control(host.isTv() ? ui.background : ui.surface, ui.focusedSurface, 12));
        item.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        String author = comment.author().isEmpty()
            ? context.getString(R.string.post_author_unknown) : comment.author();
        if (comment.created() > 0) {
            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            author += "  ·  " + DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                .format(new Date(comment.created() * 1000L));
        }
        LinearLayout authorRow = ui.row();
        authorRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.Avatar avatar = ui.new Avatar(host.isTv() ? 36 : 30);
        avatar.bind(comment.authorAvatar());
        authorRow.addView(avatar);
        TextView authorName = ui.label(author, 14, Ui.ACCENT, true);
        authorName.setPaddingRelative(ui.dp(10), 0, 0, 0);
        authorRow.addView(authorName, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        item.addView(authorRow);
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
            for (Reactions.Type type : state.rankedTypes()) {
                totals.add(type.emoji + "\u00a0" + state.count(type));
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
