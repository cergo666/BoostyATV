package ru.boostyatv.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostPlaylistTest {
    @Test
    fun queueUsesOnlyPlayableBlocksInPublicationOrder() {
        val first = video("first")
        val last = video("last")
        val missing = Post.Block("video", "", "", emptyList())
        val text = Post.Block("text", "A description", "", emptyList())
        val post = post(listOf(text, first, missing, last))

        val beginning = PostPlaylist.open(post, first)!!
        assertEquals(listOf(first, last), beginning.videos)
        assertNull(beginning.previousIndex)
        assertEquals(1, beginning.nextIndex)
        val end = PostPlaylist.open(post, last)!!
        assertEquals(0, end.previousIndex)
        assertNull(end.nextIndex)
        assertNull(PostPlaylist.open(post, missing))
        assertNull(PostPlaylist.open(post(listOf(first), false), first))
    }

    @Test
    fun restoreMatchesTheVideoIdAfterThePostChanges() {
        val first = video("one")
        val second = video("two")
        assertEquals(0, PostPlaylist.restore(post(listOf(second, first)), 1, "two")?.currentIndex)
        assertNull(PostPlaylist.restore(post(listOf(first)), 1, "two"))
    }

    private fun video(id: String) = Post.Block(
        "video", id, "", listOf(Post.Source("mp4", "https://example.test/$id.mp4")), id, 0L, 0L,
    )

    private fun post(blocks: List<Post.Block>, allowed: Boolean = true) = Post(
        "post", "Compilation", "Author", "author", "", 0L, allowed, "", blocks, true, true, null,
    )
}
