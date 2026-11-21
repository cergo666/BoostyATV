package ru.boostyatv.client

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchStrategy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal interface FeedBackend {
    fun loadPosts(
        author: String?,
        offset: String,
        query: String,
        tags: String,
        onSuccess: (BoostyApi.Page) -> Unit,
        onError: (Exception) -> Unit,
    )

    fun loadTags(
        author: String,
        onSuccess: (List<BoostyApi.Tag>) -> Unit,
        onError: (String) -> Unit,
    )
}

@OptIn(ExperimentalFoundationApi::class)
private object TvPivotBringIntoViewSpec : BringIntoViewSpec {
    private const val PARENT_FRACTION = 0.22f
    private const val CHILD_FRACTION = 0.0f

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val leadingEdgeOfItemRequestingFocus = offset
        val trailingEdgeOfItemRequestingFocus = offset + size
        val sizeOfItemRequestingFocus = trailingEdgeOfItemRequestingFocus - leadingEdgeOfItemRequestingFocus
        val targetPosition = containerSize * PARENT_FRACTION - sizeOfItemRequestingFocus * CHILD_FRACTION
        return leadingEdgeOfItemRequestingFocus - targetPosition
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class FeedState(
    val author: String?,
    val title: String?,
    private val backend: FeedBackend,
) {
    val gridState = LazyGridState(
        prefetchStrategy = LazyGridPrefetchStrategy(nestedPrefetchItemCount = 4),
    )
    val visible = mutableStateListOf<Post>()
    private val loaded = ArrayList<Post>()
    private val seen = HashSet<String>()
    private val requested = HashSet<String>()

    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var next by mutableStateOf("")
        private set
    var videosOnly by mutableStateOf(false)
        private set
    var search by mutableStateOf("")
        private set
    var selectedTags by mutableStateOf<Set<String>>(emptySet())
        private set
    var channelTags by mutableStateOf<List<BoostyApi.Tag>?>(null)
    var linkedTag by mutableStateOf<BoostyApi.Tag?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var pendingScroll by mutableIntStateOf(-1)
    private var generation = 0
    private var initialized = false

    fun unfiltered(): Boolean = search.isEmpty() && selectedTags.isEmpty() && !videosOnly

    fun scrollPosition(): Int = gridState.firstVisibleItemIndex

    fun ensureLoaded(initialTag: BoostyApi.Tag? = null) {
        if (initialized) return
        initialized = true
        if (initialTag != null) {
            linkedTag = initialTag
            selectedTags = linkedSetOf(initialTag.id())
        }
        load("")
    }

    fun openTag(tag: BoostyApi.Tag) {
        initialized = true
        linkedTag = tag
        selectedTags = linkedSetOf(tag.id())
        reload(userPull = false)
    }

    fun reload(userPull: Boolean = false) {
        initialized = true
        pendingScroll = -1
        generation++
        loaded.clear()
        seen.clear()
        requested.clear()
        visible.clear()
        next = ""
        loading = false
        refreshing = userPull
        failed = false
        errorMessage = null
        load("")
    }

    fun setVideosOnlyFilter(onlyVideos: Boolean) {
        if (videosOnly == onlyVideos) return
        videosOnly = onlyVideos
        visible.clear()
        for (post in loaded) {
            if (matches(post)) visible.add(post)
        }
        maybeLoadMore(lastVisibleIndex = 0)
    }

    fun applySearch(query: String) {
        search = query.trim()
        reload(userPull = false)
    }

    fun applyTags(tags: Set<String>) {
        selectedTags = LinkedHashSet(tags)
        reload(userPull = false)
    }

    fun clearFilters() {
        search = ""
        selectedTags = emptySet()
        reload(userPull = false)
    }

    fun loadTagsIfNeeded(onReady: (List<BoostyApi.Tag>) -> Unit) {
        val slug = author ?: return
        val cached = channelTags
        if (cached != null) {
            onReady(cached)
            return
        }
        errorMessage = null
        backend.loadTags(
            author = slug,
            onSuccess = { tags ->
                channelTags = tags
                onReady(tags)
            },
            onError = { message ->
                errorMessage = message
            },
        )
    }

    fun retry() {
        if (failed) load(next)
    }

    fun maybeLoadMore(lastVisibleIndex: Int, prefetchThreshold: Int = 8) {
        if (loading || failed || next.isEmpty()) return
        if (pendingScroll > visible.size || visible.isEmpty() || lastVisibleIndex >= visible.size - prefetchThreshold) {
            load(next)
        }
    }

    private fun matches(post: Post): Boolean = FeedFilter.matches(post, search, videosOnly)

    fun load(offset: String) {
        if (loading || !requested.add(offset)) return
        loading = true
        failed = false
        val requestGeneration = generation
        val query = search
        val tagsParam = selectedTags.joinToString(",")
        backend.loadPosts(
            author = author,
            offset = offset,
            query = query,
            tags = tagsParam,
            onSuccess = { page ->
                if (requestGeneration != generation) return@loadPosts
                for (post in page.posts()) {
                    if (seen.add(post.id())) {
                        loaded.add(post)
                        if (matches(post)) visible.add(post)
                    }
                }
                next = if (requested.contains(page.next())) "" else page.next()
                loading = false
                refreshing = false
                if (visible.isEmpty() && next.isNotEmpty()) {
                    load(next)
                } else if (pendingScroll > visible.size && next.isNotEmpty()) {
                    load(next)
                }
            },
            onError = {
                if (requestGeneration != generation) return@loadPosts
                requested.remove(offset)
                next = offset
                loading = false
                refreshing = false
                failed = true
            },
        )
    }

    fun activeFiltersSummary(context: Context): String {
        val parts = ArrayList<String>()
        if (search.isNotEmpty()) {
            parts.add(context.getString(R.string.feed_filter_search, search))
        }
        val knownTags = channelTags
        if (knownTags != null) {
            for (tag in knownTags) {
                if (selectedTags.contains(tag.id())) {
                    parts.add(context.getString(R.string.feed_filter_tag, tag.name()))
                }
            }
        } else {
            val linked = linkedTag
            if (linked != null && selectedTags.contains(linked.id())) {
                parts.add(context.getString(R.string.feed_filter_tag, linked.name()))
            }
        }
        return parts.joinToString("  ·  ")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun FeedScreenView(
    state: FeedState,
    watchHistory: WatchHistory,
    progressRevision: Int,
    savedPosts: List<SavedPostsStore.Item> = emptyList(),
    onBack: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenRecentPost: (WatchHistory.RecentPost) -> Unit,
    onOpenSavedPost: (SavedPostsStore.Item) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenWatchLater: () -> Unit = {},
    onScanQr: () -> Unit,
    authorsState: AuthorsState? = null,
    onOpenAuthor: (slug: String, name: String) -> Unit = { _, _ -> },
    onFocusArtwork: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val isHome = state.author == null

    var showSearchDialog by remember { mutableStateOf(false) }
    var showTagsSheet by remember { mutableStateOf(false) }
    var showNoTagsNotice by remember { mutableStateOf(false) }

    LaunchedEffect(isHome, authorsState) {
        if (isHome && authorsState != null) {
            authorsState.ensureLoaded()
        }
    }

    val continueWatching = remember(progressRevision, isHome, state.videosOnly, state.search, state.selectedTags) {
        if (!isHome || !state.unfiltered()) {
            emptyList()
        } else {
            watchHistory.recentPosts().filter { recent ->
                val p = watchHistory.progressForVideo(recent.videoId(), recent.durationMs())
                p.percentage() in 1..96
            }.take(10)
        }
    }

    LaunchedEffect(state.pendingScroll, state.visible.size, state.next) {
        val target = state.pendingScroll
        if (target >= 0 && (target <= state.visible.size || state.next.isEmpty())) {
            val safeIndex = target.coerceAtMost(state.visible.size)
            state.gridState.scrollToItem(safeIndex)
            state.pendingScroll = -1
        }
    }

    val artwork = LocalArtwork.current
    val lastVisibleIndex by remember(state) {
        derivedStateOf {
            state.gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
    }
    LaunchedEffect(lastVisibleIndex, state.visible.size, state.next, state.loading, isTv) {
        val threshold = if (isTv) 12 else 8
        state.maybeLoadMore(lastVisibleIndex, prefetchThreshold = threshold)
    }

    // Proactively warm disk+RAM artwork cache ahead of the viewport (YouTube/SmartTube style)
    LaunchedEffect(lastVisibleIndex, state.visible.size, artwork) {
        if (artwork == null || state.visible.isEmpty()) return@LaunchedEffect
        val ahead = if (isTv) 12 else 6
        val start = (lastVisibleIndex - 2).coerceAtLeast(0)
        val end = (lastVisibleIndex + ahead).coerceAtMost(state.visible.lastIndex)
        for (idx in start..end) {
            val post = state.visible[idx]
            val cover = post.cover()
            if (cover.isNotEmpty()) artwork.prefetch(cover, Artwork.THUMBNAIL)
            val avatar = post.authorAvatar()
            if (avatar.isNotEmpty()) artwork.prefetch(avatar, Artwork.AVATAR)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = if (isHome) {
                stringResource(if (isTv) R.string.feed_subscription_feed else R.string.app_name)
            } else {
                state.title ?: state.author.orEmpty()
            },
            showBack = !isHome,
            onBack = onBack,
            actions = {
                if (isHome) {
                    BoostyIconButton(
                        iconRes = R.drawable.ic_bookmark,
                        contentDescription = stringResource(R.string.feed_watch_later),
                        onClick = onOpenWatchLater,
                    )
                    if (isTv) {
                        BoostyIconButton(
                            iconRes = R.drawable.ic_refresh,
                            contentDescription = stringResource(R.string.account_refresh_feed),
                            onClick = { state.reload(userPull = false) },
                        )
                    } else {
                        BoostyIconButton(
                            iconRes = R.drawable.ic_history,
                            contentDescription = stringResource(R.string.nav_history),
                            onClick = onOpenHistory,
                        )
                        BoostyIconButton(
                            iconRes = R.drawable.ic_qr,
                            contentDescription = stringResource(R.string.feed_qr_description),
                            onClick = onScanQr,
                        )
                    }
                }
            },
        )

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val widthDp = maxWidth.value.toInt()
            val columns = if (isTv) {
                when {
                    widthDp >= 1020 -> 4
                    widthDp >= 640 -> 3
                    else -> 2
                }
            } else {
                when {
                    widthDp >= 760 -> 3
                    widthDp >= 560 -> 2
                    else -> 1
                }
            }

            val gridContent: @Composable () -> Unit = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = state.gridState,
                    contentPadding = PaddingValues(
                        start = if (isTv) 32.dp else 16.dp,
                        end = if (isTv) 32.dp else 16.dp,
                        top = 4.dp,
                        bottom = if (isTv) 32.dp else 20.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isTv) 24.dp else 18.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isTv) Modifier.focusRestorer().focusGroup() else Modifier),
                ) {
                    item(key = "feed_header", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (isHome) {
                                BoostySegmentedControl(
                                    options = listOf(
                                        stringResource(R.string.feed_all_posts),
                                        stringResource(R.string.feed_videos_only),
                                    ),
                                    selectedIndex = if (state.videosOnly) 1 else 0,
                                    onSelect = { idx -> state.setVideosOnlyFilter(idx == 1) },
                                )
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BoostyActionChip(
                                        text = stringResource(R.string.feed_channel_search),
                                        iconRes = R.drawable.ic_search,
                                        active = state.search.isNotEmpty(),
                                        onClick = { showSearchDialog = true },
                                    )
                                    BoostyActionChip(
                                        text = stringResource(R.string.feed_channel_tags),
                                        iconRes = R.drawable.ic_tag,
                                        active = state.selectedTags.isNotEmpty(),
                                        onClick = {
                                            state.loadTagsIfNeeded { tags ->
                                                if (tags.isEmpty()) showNoTagsNotice = true
                                                else showTagsSheet = true
                                            }
                                        },
                                    )
                                }
                                val summary = state.activeFiltersSummary(context)
                                if (summary.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(
                                            text = summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Text(
                                            text = stringResource(R.string.feed_tags_reset),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = palette.muted,
                                            modifier = Modifier
                                                .clip(PillShape)
                                                .clickable { state.clearFilters() }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            state.errorMessage?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.danger,
                                )
                            }
                        }
                    }

                    if (isHome && state.unfiltered() && authorsState != null && authorsState.items.isNotEmpty()) {
                        item(key = "home_channels", contentType = "channels", span = { GridItemSpan(maxLineSpan) }) {
                            HomeChannelsShelf(
                                authors = authorsState.items,
                                onOpenAuthor = onOpenAuthor,
                            )
                        }
                    }

                    if (continueWatching.isNotEmpty()) {
                        item(key = "continue_watching", contentType = "continue", span = { GridItemSpan(maxLineSpan) }) {
                            ContinueWatchingShelf(
                                items = continueWatching,
                                watchHistory = watchHistory,
                                onOpenRecent = onOpenRecentPost,
                                onFocusArtwork = onFocusArtwork,
                            )
                        }
                    }

                    if (isHome && state.unfiltered() && savedPosts.isNotEmpty()) {
                        item(key = "watch_later", contentType = "watch_later", span = { GridItemSpan(maxLineSpan) }) {
                            WatchLaterShelf(
                                items = savedPosts,
                                watchHistory = watchHistory,
                                onOpenSaved = onOpenSavedPost,
                                onFocusArtwork = onFocusArtwork,
                            )
                        }
                    }

                    if (state.visible.isEmpty() && state.loading) {
                        items(count = columns * 2, key = { "skeleton_$it" }, contentType = { "skeleton" }) {
                            PostCardSkeleton(isTv = isTv)
                        }
                    } else {
                        items(
                            items = state.visible,
                            key = { it.id() },
                            contentType = { "post" },
                        ) { post ->
                            val progressPercent = remember(post.id(), progressRevision) {
                                post.watchProgressPercent(watchHistory)
                            }
                            PostFeedCard(
                                post = post,
                                progressPercent = progressPercent,
                                onClick = { onOpenPost(post) },
                                onFocusArtwork = onFocusArtwork,
                            )
                        }
                    }

                    item(key = "feed_footer", contentType = "footer", span = { GridItemSpan(maxLineSpan) }) {
                        val footerText = when {
                            state.failed -> stringResource(R.string.error_load_failed)
                            state.loading && state.visible.isNotEmpty() -> stringResource(R.string.feed_loading_publications)
                            !state.loading && state.next.isEmpty() && state.visible.isEmpty() -> stringResource(R.string.no_more)
                            else -> null
                        }
                        if (footerText != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.failed) {
                                    BoostyButton(
                                        text = footerText,
                                        onClick = { state.retry() },
                                        primary = false,
                                    )
                                } else {
                                    Text(
                                        text = footerText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isTv) {
                @OptIn(ExperimentalFoundationApi::class)
                CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivotBringIntoViewSpec) {
                    gridContent()
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { state.reload(userPull = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridContent()
                }
            }
        }
    }

    if (showSearchDialog) {
        BoostyTextInputDialog(
            title = stringResource(R.string.feed_search_dialog_title),
            initialValue = state.search,
            placeholder = stringResource(R.string.feed_search_dialog_hint),
            confirmLabel = stringResource(R.string.feed_search_dialog_find),
            onConfirm = { query ->
                showSearchDialog = false
                state.applySearch(query)
            },
            onReset = {
                showSearchDialog = false
                state.applySearch("")
            },
            onDismiss = { showSearchDialog = false },
        )
    }

    if (showTagsSheet) {
        val tags = state.channelTags.orEmpty()
        var draft by remember(showTagsSheet, state.selectedTags) {
            mutableStateOf<Set<String>>(state.selectedTags.toSet())
        }
        BoostyOverlaySheet(
            title = stringResource(R.string.feed_tags_dialog_title),
            onDismiss = { showTagsSheet = false },
            footer = {
                BoostyButton(
                    text = stringResource(R.string.feed_tags_reset),
                    onClick = {
                        showTagsSheet = false
                        state.applyTags(emptySet())
                    },
                    primary = false,
                )
                BoostyButton(
                    text = stringResource(R.string.feed_tags_apply),
                    onClick = {
                        showTagsSheet = false
                        state.applyTags(draft)
                    },
                    primary = true,
                )
            },
        ) {
            tags.forEach { tag ->
                val checked = draft.contains(tag.id())
                BoostySheetOption(
                    label = "#${tag.name()}",
                    selected = checked,
                    onClick = {
                        val nextDraft = LinkedHashSet(draft)
                        if (checked) nextDraft.remove(tag.id()) else nextDraft.add(tag.id())
                        draft = nextDraft
                    },
                )
            }
        }
    }

    if (showNoTagsNotice) {
        BoostyConfirmDialog(
            title = stringResource(R.string.feed_tags_dialog_title),
            message = stringResource(R.string.feed_tags_no_tags),
            confirmLabel = stringResource(R.string.ok),
            dismissLabel = null,
            onConfirm = { showNoTagsNotice = false },
            onDismiss = { showNoTagsNotice = false },
        )
    }
}

@Composable
private fun WatchLaterShelf(
    items: List<SavedPostsStore.Item>,
    watchHistory: WatchHistory,
    onOpenSaved: (SavedPostsStore.Item) -> Unit,
    onFocusArtwork: (String) -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.feed_watch_later),
            style = MaterialTheme.typography.titleLarge,
            color = palette.ink,
        )
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = if (isTv) 10.dp else 2.dp,
                vertical = if (isTv) 12.dp else 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            items(items, key = { "saved_${it.slug}_${it.id}" }, contentType = { "watch_later_card" }) { saved ->
                val progress = watchHistory.progressForVideo(saved.videoId, saved.durationMs)
                CompactVideoCard(
                    title = saved.title.ifEmpty { stringResource(R.string.post_untitled) },
                    author = saved.author.ifEmpty { saved.slug },
                    coverUrl = saved.cover,
                    durationMs = progress.duration().takeIf { it > 0L } ?: saved.durationMs,
                    progressPercent = progress.percentage(),
                    onClick = { onOpenSaved(saved) },
                    modifier = Modifier.width(if (isTv) 268.dp else 224.dp),
                    onFocusArtwork = onFocusArtwork,
                )
            }
        }
    }
}

@Composable
private fun HomeChannelsShelf(
    authors: List<BoostyApi.Author>,
    onOpenAuthor: (slug: String, name: String) -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.feed_your_channels),
            style = MaterialTheme.typography.titleLarge,
            color = palette.ink,
        )
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = if (isTv) 10.dp else 2.dp,
                vertical = if (isTv) 10.dp else 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            items(authors, key = { it.slug() }, contentType = { "channel_chip" }) { author ->
                HomeChannelItem(
                    author = author,
                    onClick = { onOpenAuthor(author.slug(), author.name()) },
                )
            }
        }
    }
}

@Composable
private fun HomeChannelItem(
    author: BoostyApi.Author,
    onClick: () -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.08f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "channelChipScale",
    )
    val avatarSize = if (isTv) 60.dp else 52.dp
    Column(
        modifier = Modifier
            .width(if (isTv) 92.dp else 74.dp)
            .zIndex(if (focused) 2f else 0f)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (focused && isTv) 14.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = palette.accent.copy(alpha = 0.6f),
                    spotColor = Color.White.copy(alpha = 0.4f),
                )
                .clip(CircleShape)
                .border(
                    width = if (focused) 2.5.dp else 1.dp,
                    color = if (focused) palette.focusRing else palette.hairline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BoostyAvatar(url = author.image(), size = avatarSize)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = author.name(),
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Color.White else palette.ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ContinueWatchingShelf(
    items: List<WatchHistory.RecentPost>,
    watchHistory: WatchHistory,
    onOpenRecent: (WatchHistory.RecentPost) -> Unit,
    onFocusArtwork: (String) -> Unit,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.feed_continue_watching),
            style = MaterialTheme.typography.titleLarge,
            color = palette.ink,
        )
        LazyRow(
            contentPadding = PaddingValues(
                horizontal = if (isTv) 10.dp else 2.dp,
                vertical = if (isTv) 12.dp else 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            items(items, key = { "${it.slug()}_${it.id()}" }, contentType = { "continue_card" }) { recent ->
                val progress = watchHistory.progressForVideo(recent.videoId(), recent.durationMs())
                CompactVideoCard(
                    title = recent.title().ifEmpty { stringResource(R.string.post_untitled) },
                    author = recent.author().ifEmpty { recent.slug() },
                    coverUrl = recent.cover(),
                    durationMs = progress.duration().takeIf { it > 0L } ?: recent.durationMs(),
                    progressPercent = progress.percentage(),
                    onClick = { onOpenRecent(recent) },
                    modifier = Modifier.width(if (isTv) 268.dp else 224.dp),
                    onFocusArtwork = onFocusArtwork,
                )
            }
        }
    }
}

@Composable
internal fun PostFeedCard(
    post: Post,
    progressPercent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusArtwork: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val isTv = BoostyTheme.isTv
    val fallbackAuthor = stringResource(R.string.post_app_name)
    val videoSuffix = stringResource(R.string.post_video_suffix)

    val titleText = remember(post.id(), post.title()) { post.displayTitle(context) }
    val authorText = remember(post.id(), post.author(), fallbackAuthor) {
        post.author().ifEmpty { fallbackAuthor }
    }
    val details = remember(post.id(), post.published(), post.views()) {
        post.displayMetadata(context)
    }
    val subtitleText = remember(authorText, details) {
        if (details.isEmpty()) authorText else "$authorText  ·  $details"
    }
    val durationLabel = remember(post.id()) { formatDurationShort(post.primaryDurationMs()) }
    val description = remember(titleText, authorText, details, videoSuffix, post.id()) {
        buildString {
            append(titleText)
            append(", ")
            append(authorText)
            if (details.isNotEmpty()) {
                append(", ")
                append(details)
            }
            if (post.hasVideo()) append(videoSuffix)
        }
    }

    if (isTv) {
        TvPostFeedCard(
            coverUrl = post.cover(),
            hasVideo = post.hasVideo(),
            authorAvatar = post.authorAvatar(),
            titleText = titleText,
            subtitleText = subtitleText,
            durationLabel = durationLabel,
            description = description,
            progressPercent = progressPercent,
            onClick = onClick,
            onFocusArtwork = onFocusArtwork,
            modifier = modifier,
        )
    } else {
        PhonePostFeedCard(
            coverUrl = post.cover(),
            hasVideo = post.hasVideo(),
            authorAvatar = post.authorAvatar(),
            titleText = titleText,
            subtitleText = subtitleText,
            durationLabel = durationLabel,
            description = description,
            progressPercent = progressPercent,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvPostFeedCard(
    coverUrl: String,
    hasVideo: Boolean,
    authorAvatar: String,
    titleText: String,
    subtitleText: String,
    durationLabel: String,
    description: String,
    progressPercent: Int,
    onClick: () -> Unit,
    onFocusArtwork: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BoostyTheme.palette
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.055f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "cardScale",
    )
    val elevated = focused || scale > 1.001f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (elevated) Modifier.zIndex(2f) else Modifier)
            .semantics { contentDescription = description }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && coverUrl.isNotEmpty()) onFocusArtwork(coverUrl)
            }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .then(
                    if (elevated) {
                        Modifier
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .shadow(
                                elevation = if (focused) 18.dp else 0.dp,
                                shape = CardShape,
                                ambientColor = palette.accent.copy(alpha = 0.6f),
                                spotColor = Color.White.copy(alpha = 0.45f),
                            )
                    } else {
                        Modifier
                    }
                )
                .clip(CardShape)
                .border(
                    width = if (focused) 2.5.dp else 1.dp,
                    color = if (focused) palette.focusRing else palette.hairline,
                    shape = CardShape,
                ),
        ) {
            VideoCoverArtwork(
                coverUrl = coverUrl,
                hasVideo = hasVideo,
                durationLabel = durationLabel,
                progressPercent = progressPercent,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            BoostyAvatar(
                url = authorAvatar,
                size = 28.dp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) Color.White else palette.ink,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) palette.ink.copy(alpha = 0.78f) else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PhonePostFeedCard(
    coverUrl: String,
    hasVideo: Boolean,
    authorAvatar: String,
    titleText: String,
    subtitleText: String,
    durationLabel: String,
    description: String,
    progressPercent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BoostyTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .clip(CardShape)
            .background(palette.surface)
            .border(1.dp, palette.hairline, CardShape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            VideoCoverArtwork(
                coverUrl = coverUrl,
                hasVideo = hasVideo,
                durationLabel = durationLabel,
                progressPercent = progressPercent,
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BoostyAvatar(
                url = authorAvatar,
                size = 34.dp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CompactVideoCard(
    title: String,
    author: String,
    coverUrl: String,
    durationMs: Long,
    progressPercent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusArtwork: (String) -> Unit = {},
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.05f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "compactScale",
    )
    val durationLabel = remember(durationMs) { formatDurationShort(durationMs) }

    Column(
        modifier = modifier
            .zIndex(if (focused) 2f else 0f)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && coverUrl.isNotEmpty()) onFocusArtwork(coverUrl)
            }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (focused && isTv) 16.dp else 0.dp,
                    shape = CardShape,
                    ambientColor = palette.accent.copy(alpha = 0.6f),
                    spotColor = Color.White.copy(alpha = 0.45f),
                )
                .clip(CardShape)
                .border(
                    width = if (focused) 2.5.dp else 1.dp,
                    color = if (focused) palette.focusRing else palette.hairline,
                    shape = CardShape,
                ),
        ) {
            VideoCoverArtwork(
                coverUrl = coverUrl,
                hasVideo = true,
                durationLabel = durationLabel,
                progressPercent = progressPercent,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Color.White else palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = author,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun VideoCoverArtwork(
    coverUrl: String,
    hasVideo: Boolean,
    durationLabel: String,
    progressPercent: Int,
) {
    val palette = BoostyTheme.palette
    Box(modifier = Modifier.fillMaxSize()) {
        BoostyArtworkImage(
            url = coverUrl,
            size = Artwork.THUMBNAIL,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholderIcon = if (hasVideo) R.drawable.ic_play else R.drawable.ic_subscriptions,
            modifier = Modifier.fillMaxSize(),
        )
        // Subtle bottom vignette so duration badge & progress line stay crisp on bright frames
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0x9908090D)),
                    )
                )
        )
        if (hasVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = if (progressPercent > 0) 9.dp else 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.badgeBg)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(11.dp),
                )
                if (durationLabel.isNotEmpty()) {
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.ink,
                    )
                }
            }
        }
        if (hasVideo && progressPercent > 0) {
            val fraction = (progressPercent / 100f).coerceIn(0.02f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .align(Alignment.BottomStart)
                    .background(Color(0x55FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(palette.accent),
                )
            }
        }
    }
}

@Composable
private fun PostCardSkeleton(isTv: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = CardShape,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerBox(
                modifier = Modifier.size(if (isTv) 28.dp else 34.dp),
                shape = PillShape,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.54f)
                        .height(11.dp),
                    shape = RoundedCornerShape(6.dp),
                )
            }
        }
    }
}
