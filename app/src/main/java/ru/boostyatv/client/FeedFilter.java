package ru.boostyatv.client;

import java.util.Locale;

/** Pure feed filtering rules, kept out of the screen for testability. */
final class FeedFilter {
    private FeedFilter() {}

    static boolean matches(Post post, String query, boolean videosOnly) {
        return (query.isEmpty() || (post.hasVideo()
            && post.title().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))))
            && (!videosOnly || post.hasVideo());
    }
}
