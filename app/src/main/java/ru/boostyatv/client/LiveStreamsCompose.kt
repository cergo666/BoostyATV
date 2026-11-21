package ru.boostyatv.client

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Date

internal const val LIVE_BLOCK = "live"

@Composable
internal fun LiveStreamsShelf(
    streams: List<LiveStream>,
    onOpen: (LiveStream) -> Unit,
    onFocusArtwork: (String) -> Unit,
) {
    val context = LocalContext.current
    val isTv = BoostyTheme.isTv
    val palette = BoostyTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.live_shelf),
            style = MaterialTheme.typography.titleLarge,
            color = palette.ink,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = if (isTv) 10.dp else 2.dp, vertical = if (isTv) 12.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            items(streams, key = { it.slug() }) { stream ->
                val status = if (stream.online()) {
                    stringResource(R.string.live_title)
                } else {
                    val time = Date(stream.publishTime() * 1000L)
                    stringResource(
                        R.string.live_scheduled,
                        DateFormat.getDateFormat(context).format(time),
                        DateFormat.getTimeFormat(context).format(time),
                    )
                }
                CompactVideoCard(
                    title = stream.title().ifBlank { stream.author() },
                    author = listOfNotNull(
                        status,
                        stream.author(),
                        stringResource(R.string.live_subscription).takeUnless { stream.allowed() },
                    ).joinToString(" · "),
                    coverUrl = stream.cover(),
                    durationMs = 0L,
                    progressPercent = 0,
                    onClick = { onOpen(stream) },
                    modifier = Modifier.width(if (isTv) 280.dp else 236.dp),
                    onFocusArtwork = onFocusArtwork,
                )
            }
        }
    }
}
