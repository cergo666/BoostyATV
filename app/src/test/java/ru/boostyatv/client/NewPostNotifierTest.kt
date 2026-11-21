package ru.boostyatv.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewPostNotifierTest {
    @Test
    fun collectsEveryNewVideoAcrossPagesBeforeTheCheckpoint() {
        val pages = mapOf(
            "" to BoostyApi.Page(listOf(video("five", 15), video("four", 14), video("three", 13)), "second"),
            "second" to BoostyApi.Page(listOf(video("three", 13), video("two", 12), video("one", 11)), "third"),
            "third" to BoostyApi.Page(listOf(video("seen", 10), video("older", 9)), "unused"),
        )
        val scan = NewPostNotifier.scan(NewPostNotifier.Checkpoint("seen", 10), pages::getValue) { false }!!
        assertEquals(listOf("five", "four", "three", "two", "one"), scan.fresh.map { it.id() })
        assertEquals(NewPostNotifier.Checkpoint("five", 15), scan.latest)
    }

    @Test
    fun canResumeByDateWhenTheSavedPostWasRemoved() {
        val pages = mapOf(
            "" to BoostyApi.Page(listOf(video("older", 9), video("newer", 12)), "unused"),
        )
        val scan = NewPostNotifier.scan(NewPostNotifier.Checkpoint("missing", 10), pages::getValue) { false }!!
        assertEquals(listOf("newer"), scan.fresh.map { it.id() })
    }

    @Test
    fun aNewCheckpointDoesNotSendTheExistingFeed() {
        val scan = NewPostNotifier.scan(NewPostNotifier.Checkpoint("", 0), {
            BoostyApi.Page(listOf(video("latest", 20)), "")
        }) { false }!!
        assertEquals(emptyList<Post>(), scan.fresh)
        assertEquals(NewPostNotifier.Checkpoint("latest", 20), scan.latest)
    }

    @Test
    fun aRepeatedOffsetCannotCommitAnIncompleteResult() {
        val scan = NewPostNotifier.scan(NewPostNotifier.Checkpoint("seen", 10), {
            BoostyApi.Page(listOf(video("latest", 20)), "next")
        }) { false }
        assertNull(scan)
    }

    private fun video(id: String, time: Long): Post = Post(
        id, id, "Author", "author", "", time, true, "",
        listOf(Post.Block("video", "", "", listOf(Post.Source("mp4", "https://example.test/video.mp4")))),
        false, false, null,
    )
}
