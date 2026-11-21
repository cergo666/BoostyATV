package ru.boostyatv.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRetryTest {
    @Test
    fun retryUsesTheSameVideoAndQualityWithAFreshUrl() {
        val first = Post.Block(
            "video", "", "",
            listOf(Post.Source("high", "https://example.test/other.mp4")),
            "other-video", 0, 0,
        )
        val companion = Post.Block(
            "video", "", "",
            listOf(
                Post.Source("full_hd", "https://example.test/hd.mp4"),
                Post.Source("high", "https://example.test/refreshed.mp4"),
            ),
            "requested-video", 0, 0,
        )
        val post = Post(
            "post", "Title", "Author", "author", "", 0, true, "",
            listOf(first, companion), false, false, null,
        )
        val reference = VideoDownloader.Reference("author", "post", "requested-video", 0, "720p")
        assertEquals("https://example.test/refreshed.mp4", reference.resolve(post)?.url)
        assertNull(reference.copy(qualityTag = "360p").resolve(post))
    }
}
