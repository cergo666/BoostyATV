package ru.boostyatv.client;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class PlaybackOptionsTest {
    @Test public void qualityVariantsPreferKnownHeightsAndAutoStreams() {
        LinkedHashMap<String, String> variants = PlaybackOptions.qualityVariants(List.of(
            new Post.Source("full_hd", "https://cdn.example.com/1080.mp4"),
            new Post.Source("hls", "https://cdn.example.com/master.m3u8"),
            new Post.Source("unknown", "https://cdn.example.com/unknown.mp4"),
            new Post.Source("high", "https://cdn.example.com/720.mp4")));
        assertEquals(List.of("1080p", "auto", "720p"), List.copyOf(variants.keySet()));
        assertEquals("https://cdn.example.com/1080.mp4", variants.get("1080p"));
    }

    @Test public void qualityTableAndLabelsStayInSync() {
        assertEquals(PlaybackOptions.HEIGHTS.length, PlaybackOptions.QUALITY.length);
        assertEquals(PlaybackOptions.AUTO, PlaybackOptions.QUALITY[0]);
    }
}
