package ru.boostyatv.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class FeedFilterTest {
    private static Post post(String title, boolean video) {
        Post.Block block = video
            ? new Post.Block("video", "", "", List.of(new Post.Source("hls", "https://cdn.example.com/m.m3u8")))
            : new Post.Block("text", "hello", "", List.of());
        return new Post("id", title, "author", "slug", 0, true, "", List.of(block), false, false,
            new Reactions("", new java.util.LinkedHashMap<>()));
    }

    @Test public void emptyQueryMatchesEverything() {
        assertTrue(FeedFilter.matches(post("Drawings", false), "", false));
        assertTrue(FeedFilter.matches(post("Stream", true), "", false));
    }

    @Test public void queryMatchesOnlyVideoTitles() {
        assertTrue(FeedFilter.matches(post("Evening stream", true), "stream", false));
        assertFalse(FeedFilter.matches(post("Evening drawings", false), "stream", false));
    }

    @Test public void queryIsCaseInsensitive() {
        assertTrue(FeedFilter.matches(post("Evening Stream", true), "sTrEaM", false));
    }

    @Test public void videosOnlyHidesTextPosts() {
        assertTrue(FeedFilter.matches(post("Stream", true), "", true));
        assertFalse(FeedFilter.matches(post("Drawings", false), "", true));
        assertFalse(FeedFilter.matches(post("Stream notes", false), "stream", true));
    }
}
