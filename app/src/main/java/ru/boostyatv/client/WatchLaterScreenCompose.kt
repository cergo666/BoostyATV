package ru.boostyatv.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun WatchLaterScreenView(
    items: List<SavedPostsStore.Item>,
    watchHistory: WatchHistory,
    onBack: () -> Unit,
    onOpen: (SavedPostsStore.Item) -> Unit,
    onRemove: (SavedPostsStore.Item) -> Unit,
) {
    val isTv = BoostyTheme.isTv
    val horizontal = if (isTv) 32.dp else 16.dp
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = stringResource(R.string.feed_watch_later),
            showBack = true,
            onBack = onBack,
        )
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.watch_later_empty), color = BoostyTheme.palette.muted)
            }
        } else {
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val columns = when {
                    isTv && maxWidth >= 1020.dp -> 4
                    isTv && maxWidth >= 640.dp -> 3
                    isTv || maxWidth >= 600.dp -> 2
                    else -> 1
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(horizontal = horizontal, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { "${it.slug}/${it.id}" }) { item ->
                        val progress = watchHistory.progressForVideo(item.videoId, item.durationMs)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactVideoCard(
                                title = item.title.ifEmpty { stringResource(R.string.post_untitled) },
                                author = item.author.ifEmpty { item.slug },
                                coverUrl = item.cover,
                                durationMs = progress.duration().takeIf { it > 0L } ?: item.durationMs,
                                progressPercent = progress.percentage(),
                                onClick = { onOpen(item) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            BoostyButton(
                                text = stringResource(R.string.watch_later_remove),
                                iconRes = R.drawable.ic_bookmark_filled,
                                onClick = { onRemove(item) },
                                primary = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
