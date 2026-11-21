package ru.boostyatv.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun HistoryScreenView(
    watchHistory: WatchHistory,
    progressRevision: Int,
    errorText: String?,
    onBack: () -> Unit = {},
    onOpenRecent: (WatchHistory.RecentPost) -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val recent = remember(progressRevision) { watchHistory.recentPosts() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = stringResource(R.string.nav_history),
            showBack = !isTv,
            onBack = onBack,
        )

        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.danger,
                modifier = Modifier.padding(horizontal = if (isTv) 32.dp else 16.dp, vertical = 6.dp),
            )
        }

        if (recent.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .clip(SheetShape)
                        .background(palette.surface)
                        .border(1.dp, palette.hairline, SheetShape)
                        .padding(horizontal = 32.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_history),
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.muted,
                    )
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val widthDp = maxWidth.value.toInt()
                val columns = if (isTv) {
                    when {
                        widthDp >= 1020 -> 4
                        widthDp >= 640 -> 3
                        else -> 2
                    }
                } else {
                    if (widthDp >= 600) 2 else 1
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = if (isTv) 32.dp else 16.dp,
                        end = if (isTv) 32.dp else 16.dp,
                        top = 6.dp,
                        bottom = if (isTv) 32.dp else 20.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isTv) 22.dp else 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(recent, key = { "${it.slug()}_${it.id()}" }) { item ->
                        val progress = watchHistory.progressForVideo(item.videoId(), item.durationMs())
                        CompactVideoCard(
                            title = item.title().ifEmpty { stringResource(R.string.post_untitled) },
                            author = item.author().ifEmpty { item.slug() },
                            coverUrl = item.cover(),
                            durationMs = progress.duration().takeIf { it > 0L } ?: item.durationMs(),
                            progressPercent = progress.percentage(),
                            onClick = { onOpenRecent(item) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
