package ru.boostyatv.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun PostPlaylistSheet(
    videos: List<Post.Block>,
    currentIndex: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusIndex = currentIndex ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusIndex)
    val focusRequester = remember { FocusRequester() }
    val isTv = BoostyTheme.isTv
    BoostyOverlaySheet(
        title = stringResource(R.string.post_playlist_title),
        subtitle = currentIndex?.let { stringResource(R.string.post_playlist_position, it + 1, videos.size) },
        onDismiss = onDismiss,
        scrollContent = false,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(videos) { index, video ->
                val title = video.text().ifBlank { stringResource(R.string.post_playlist_untitled, index + 1) }
                val duration = formatDurationShort(video.durationMs()).ifEmpty { null }
                BoostySheetOption(
                    label = title,
                    selected = index == currentIndex,
                    trailingText = duration,
                    focusRequester = focusRequester.takeIf { index == focusIndex },
                    onClick = { onSelect(index) },
                    leading = {
                        BoostyArtworkImage(
                            url = video.image(),
                            size = Artwork.THUMBNAIL,
                            contentDescription = null,
                            placeholderIcon = R.drawable.ic_play,
                            modifier = Modifier.size(width = 64.dp, height = 36.dp).clip(ControlShape),
                        )
                    },
                )
            }
        }
        LaunchedEffect(isTv) {
            if (isTv) {
                withFrameNanos { }
                runCatching { focusRequester.requestFocus() }
            }
        }
    }
}
