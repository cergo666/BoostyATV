package ru.boostyatv.client;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class VideoDownloaderTest {

    @Test
    public void filtersAdaptiveStreamsAndSortsDescendingByHeight() {
        List<Post.Source> sources = List.of(
            new Post.Source("hls", "https://cdn.example/master.m3u8"),
            new Post.Source("dash", "https://cdn.example/manifest.mpd"),
            new Post.Source("low", "https://cdn.example/360.mp4"),
            new Post.Source("full_hd", "https://cdn.example/1080.mp4"),
            new Post.Source("high", "https://cdn.example/720.mp4"),
            new Post.Source("ultra_hd", "https://cdn.example/2160.mp4"),
            new Post.Source("full_hd", "https://cdn.example/1080_dup.mp4")
        );

        List<VideoDownloader.Option> options = VideoDownloader.INSTANCE.availableOptions(sources);
        assertEquals(4, options.size());
        assertEquals(2160, options.get(0).getHeight());
        assertEquals("2160p (4K)", options.get(0).getLabel());
        assertEquals(1080, options.get(1).getHeight());
        assertEquals("https://cdn.example/1080.mp4", options.get(1).getUrl());
        assertEquals(720, options.get(2).getHeight());
        assertEquals(360, options.get(3).getHeight());
    }

    @Test
    public void sanitizesUnsafeCharactersInFileName() {
        String name = VideoDownloader.INSTANCE.sanitizeFileName("Выпуск #1: тест/видео*?", "1080p");
        assertTrue(name.endsWith("_1080p.mp4"));
        assertEquals("Выпуск #1 тест видео_1080p.mp4", name);

        String fallback = VideoDownloader.INSTANCE.sanitizeFileName("   ///   ", "720p");
        assertEquals("boosty_video_720p.mp4", fallback);
    }
}
