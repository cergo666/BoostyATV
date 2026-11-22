package ru.boostyatv.client

internal class PostPlaylist private constructor(
    val post: Post,
    val videos: List<Post.Block>,
    val currentIndex: Int,
) {
    val previousIndex: Int? get() = (currentIndex - 1).takeIf { it in videos.indices }
    val nextIndex: Int? get() = (currentIndex + 1).takeIf { it in videos.indices }

    companion object {
        fun videos(post: Post): List<Post.Block> =
            if (post.allowed()) post.blocks().filter { it.sources().isNotEmpty() } else emptyList()

        fun open(post: Post, block: Post.Block): PostPlaylist? {
            val videos = videos(post)
            val index = videos.indexOfFirst { it === block }.takeIf { it >= 0 } ?: videos.indexOf(block)
            return if (index >= 0) PostPlaylist(post, videos, index) else null
        }

        fun restore(post: Post, savedIndex: Int, videoId: String): PostPlaylist? {
            val videos = videos(post)
            val index = when {
                videos.getOrNull(savedIndex)?.videoId() == videoId -> savedIndex
                videoId.isNotEmpty() -> videos.indexOfFirst { it.videoId() == videoId }
                else -> -1
            }
            return if (index >= 0) PostPlaylist(post, videos, index) else null
        }
    }
}
