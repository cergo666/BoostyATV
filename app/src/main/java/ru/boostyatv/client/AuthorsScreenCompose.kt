package ru.boostyatv.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal interface AuthorsBackend {
    fun loadAuthors(
        offset: Int,
        onSuccess: (BoostyApi.Authors) -> Unit,
        onError: (String) -> Unit,
    )
}

@Stable
internal class AuthorsState(
    private val backend: AuthorsBackend,
) {
    val items = mutableStateListOf<BoostyApi.Author>()
    var searchQuery by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var nextOffset by mutableIntStateOf(0)
        private set
    var hasMore by mutableStateOf(false)
        private set
    private var initialized = false
    private var generation = 0

    fun ensureLoaded() {
        if (initialized) return
        initialized = true
        load(0)
    }

    fun reload() {
        generation++
        loading = false
        initialized = true
        items.clear()
        nextOffset = 0
        hasMore = false
        errorMessage = null
        load(0)
    }

    fun loadMore() {
        if (loading || !hasMore) return
        load(nextOffset)
    }

    fun retry() {
        if (loading) return
        load(if (items.isEmpty()) 0 else nextOffset)
    }

    private fun load(offset: Int) {
        if (loading) return
        loading = true
        errorMessage = null
        val requestGeneration = generation
        backend.loadAuthors(
            offset = offset,
            onSuccess = { page ->
                if (requestGeneration != generation) return@loadAuthors
                if (offset == 0) items.clear()
                val knownSlugs = items.mapTo(HashSet()) { it.slug() }
                items.addAll(page.items().filter { knownSlugs.add(it.slug()) })
                hasMore = page.next() > offset
                if (hasMore) nextOffset = page.next()
                loading = false
            },
            onError = { message ->
                if (requestGeneration != generation) return@loadAuthors
                errorMessage = message
                loading = false
            },
        )
    }
}

@Composable
internal fun AuthorsScreenView(
    state: AuthorsState,
    onOpenAuthor: (slug: String, name: String) -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val searchQuery = state.searchQuery
    var showSearchDialog by remember { mutableStateOf(false) }

    val filteredItems = remember(state.items.toList(), searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) state.items.toList()
        else state.items.filter {
            it.name().contains(q, ignoreCase = true) || it.slug().contains(q, ignoreCase = true)
        }
    }

    LaunchedEffect(state) {
        state.ensureLoaded()
    }

    LaunchedEffect(searchQuery, state.nextOffset, state.hasMore, state.loading, state.errorMessage) {
        if (searchQuery.isNotBlank() && state.errorMessage == null) state.loadMore()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = stringResource(if (isTv) R.string.nav_channels else R.string.feed_subscriptions),
            showBack = false,
            onBack = {},
            subtitle = if (searchQuery.isNotBlank()) stringResource(R.string.feed_filter_search, searchQuery) else null,
            actions = {
                BoostyIconButton(
                    iconRes = R.drawable.ic_search,
                    contentDescription = stringResource(R.string.authors_search_title),
                    onClick = { showSearchDialog = true },
                )
                if (isTv) {
                    BoostyIconButton(
                        iconRes = R.drawable.ic_refresh,
                        contentDescription = stringResource(R.string.account_refresh_feed),
                        onClick = { state.reload() },
                    )
                }
            },
        )

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val widthDp = maxWidth.value.toInt()
            val columns = if (isTv) {
                when {
                    widthDp >= 1040 -> 5
                    widthDp >= 780 -> 4
                    else -> 3
                }
            } else {
                if (widthDp >= 640) 2 else 1
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = if (isTv) 32.dp else 16.dp,
                    end = if (isTv) 32.dp else 16.dp,
                    top = 6.dp,
                    bottom = if (isTv) 32.dp else 20.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.errorMessage?.let { message ->
                    item(key = "authors_error", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CardShape)
                                .background(palette.danger.copy(alpha = 0.12f))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.danger,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            BoostyButton(
                                text = stringResource(R.string.retry),
                                onClick = { state.retry() },
                                primary = false,
                            )
                        }
                    }
                }

                if (filteredItems.isEmpty() && (state.loading ||
                        (searchQuery.isNotBlank() && state.hasMore && state.errorMessage == null))) {
                    items(count = if (isTv) columns * 2 else 6, key = { "author_skel_$it" }) {
                        AuthorSkeleton(isTv = isTv)
                    }
                } else if (filteredItems.isEmpty() && state.errorMessage == null) {
                    item(key = "authors_empty", span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(if (searchQuery.isBlank()) R.string.feed_no_subscriptions else R.string.authors_search_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.muted,
                            )
                        }
                    }
                } else {
                    items(
                        items = filteredItems,
                        key = { it.slug() },
                        contentType = { "author" },
                    ) { author ->
                        if (isTv) {
                            TvChannelCard(
                                author = author,
                                onClick = { onOpenAuthor(author.slug(), author.name()) },
                            )
                        } else {
                            PhoneChannelRow(
                                author = author,
                                onClick = { onOpenAuthor(author.slug(), author.name()) },
                            )
                        }
                    }
                }

                if (searchQuery.isNotBlank() && filteredItems.isNotEmpty() && state.hasMore && state.errorMessage == null) {
                    item(key = "authors_search_loading", span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.loading), color = palette.muted, modifier = Modifier.padding(12.dp))
                    }
                }
                if (state.hasMore && searchQuery.isBlank() && state.errorMessage == null) {
                    item(key = "authors_more", span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BoostyButton(
                                text = if (state.loading) {
                                    stringResource(R.string.loading)
                                } else {
                                    stringResource(R.string.loading_more)
                                },
                                onClick = { state.loadMore() },
                                primary = false,
                                enabled = !state.loading,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearchDialog) {
        BoostyTextInputDialog(
            title = stringResource(R.string.authors_search_title),
            initialValue = searchQuery,
            placeholder = stringResource(R.string.authors_search_hint),
            confirmLabel = stringResource(R.string.feed_search_dialog_find),
            onConfirm = { query ->
                state.searchQuery = query.trim()
                showSearchDialog = false
            },
            onReset = {
                state.searchQuery = ""
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false },
        )
    }
}

@Composable
private fun TvChannelCard(
    author: BoostyApi.Author,
    onClick: () -> Unit,
) {
    val palette = BoostyTheme.palette
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.055f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "channelCardScale",
    )
    val bg by animateColorAsState(
        targetValue = if (focused) palette.surfaceFocused else palette.surface,
        animationSpec = tween(140),
        label = "channelCardBg",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) palette.focusRing else palette.hairline,
                shape = CardShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoostyAvatar(
            url = author.image(),
            size = 76.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = author.name(),
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Color.White else palette.ink,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PhoneChannelRow(
    author: BoostyApi.Author,
    onClick: () -> Unit,
) {
    val palette = BoostyTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(palette.surface)
            .border(1.dp, palette.hairline, CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoostyAvatar(url = author.image(), size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            text = author.name(),
            style = MaterialTheme.typography.titleMedium,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AuthorSkeleton(isTv: Boolean) {
    if (isTv) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(BoostyTheme.palette.surface)
                .padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShimmerBox(modifier = Modifier.size(76.dp), shape = PillShape)
            Spacer(Modifier.height(12.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.7f).height(14.dp), shape = RoundedCornerShape(6.dp))
            Spacer(Modifier.height(6.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.45f).height(12.dp), shape = RoundedCornerShape(6.dp))
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(BoostyTheme.palette.surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerBox(modifier = Modifier.size(46.dp), shape = PillShape)
            Spacer(Modifier.width(14.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(16.dp), shape = RoundedCornerShape(6.dp))
        }
    }
}
