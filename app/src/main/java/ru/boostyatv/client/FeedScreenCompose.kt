package ru.boostyatv.client

import android.content.Context
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Stable
internal class FeedState(
    val author: String?,
    val title: String?,
    private val backend: FeedBackend,
) {
    val gridState = LazyGridState()
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

    fun maybeLoadMore(lastVisibleIndex: Int) {
        if (loading || failed || next.isEmpty()) return
        if (pendingScroll > visible.size || visible.isEmpty() || lastVisibleIndex >= visible.size - 3) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedScreenView(
    state: FeedState,
    watchHistory: WatchHistory,
    progressRevision: Int,
    onBack: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenRecentPost: (WatchHistory.RecentPost) -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onRequestLogout: () -> Unit,
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val isHome = state.author == null

    var showSearchDialog by remember { mutableStateOf(false) }
    var showTagsSheet by remember { mutableStateOf(false) }
    var showNoTagsNotice by remember { mutableStateOf(false) }
    var showAccountMenu by remember { mutableStateOf(false) }

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

    val lastVisibleIndex by remember(state) {
        derivedStateOf {
            state.gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
    }
    LaunchedEffect(lastVisibleIndex, state.visible.size, state.next, state.loading) {
        state.maybeLoadMore(lastVisibleIndex)
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
                        iconRes = R.drawable.ic_refresh,
                        contentDescription = stringResource(R.string.account_refresh_feed),
                        onClick = { state.reload(userPull = false) },
                    )
                    if (!isTv) {
                        BoostyIconButton(
                            iconRes = R.drawable.ic_qr,
                            contentDescription = stringResource(R.string.feed_qr_description),
                            onClick = onScanQr,
                        )
                        BoostyIconButton(
                            iconRes = R.drawable.ic_more,
                            contentDescription = stringResource(R.string.feed_menu),
                            onClick = { showAccountMenu = true },
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
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "feed_header", span = { GridItemSpan(maxLineSpan) }) {
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

                    if (continueWatching.isNotEmpty()) {
                        item(key = "continue_watching", span = { GridItemSpan(maxLineSpan) }) {
                            ContinueWatchingShelf(
                                items = continueWatching,
                                watchHistory = watchHistory,
                                onOpenRecent = onOpenRecentPost,
                            )
                        }
                    }

                    if (state.visible.isEmpty() && state.loading) {
                        items(count = columns * 2, key = { "skeleton_$it" }) {
                            PostCardSkeleton(isTv = isTv)
                        }
                    } else {
                        items(
                            items = state.visible,
                            key = { it.id() },
                        ) { post ->
                            val progressPercent = remember(post.id(), progressRevision) {
                                post.watchProgressPercent(watchHistory)
                            }
                            PostFeedCard(
                                post = post,
                                progressPercent = progressPercent,
                                onClick = { onOpenPost(post) },
                            )
                        }
                    }

                    item(key = "feed_footer", span = { GridItemSpan(maxLineSpan) }) {
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
                gridContent()
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
            mutableStateOf(LinkedHashSet(state.selectedTags))
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

    if (showAccountMenu) {
        BoostyOverlaySheet(
            title = stringResource(R.string.feed_menu),
            onDismiss = { showAccountMenu = false },
        ) {
            BoostySheetOption(
                label = stringResource(R.string.account_refresh_feed),
                iconRes = R.drawable.ic_refresh,
                onClick = {
                    showAccountMenu = false
                    state.reload(userPull = false)
                },
            )
            BoostySheetOption(
                label = stringResource(R.string.account_settings),
                iconRes = R.drawable.ic_settings,
                onClick = {
                    showAccountMenu = false
                    onOpenSettings()
                },
            )
            BoostySheetOption(
                label = stringResource(R.string.account_about),
                iconRes = R.drawable.ic_info,
                onClick = {
                    showAccountMenu = false
                    onOpenAbout()
                },
            )
            BoostySheetOption(
                label = stringResource(R.string.account_logout),
                iconRes = R.drawable.ic_logout,
                danger = true,
                onClick = {
                    showAccountMenu = false
                    onRequestLogout()
                },
            )
        }
    }
}

@Composable
private fun ContinueWatchingShelf(
    items: List<WatchHistory.RecentPost>,
    watchHistory: WatchHistory,
    onOpenRecent: (WatchHistory.RecentPost) -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            items(items, key = { "${it.slug()}_${it.id()}" }) { recent ->
                val progress = watchHistory.progressForVideo(recent.videoId(), recent.durationMs())
                CompactVideoCard(
                    title = recent.title().ifEmpty { stringResource(R.string.post_untitled) },
                    author = recent.author().ifEmpty { recent.slug() },
                    coverUrl = recent.cover(),
                    durationMs = progress.duration().takeIf { it > 0L } ?: recent.durationMs(),
                    progressPercent = progress.percentage(),
                    onClick = { onOpenRecent(recent) },
                    modifier = Modifier.width(if (isTv) 268.dp else 224.dp),
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
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.055f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "cardScale",
    )
    val titleText = post.displayTitle(context)
    val authorText = post.author().ifEmpty { stringResource(R.string.post_app_name) }
    val details = post.displayMetadata(context)
    val durationLabel = remember(post.id()) { formatDurationShort(post.primaryDurationMs()) }
    val description = buildString {
        append(titleText)
        append(", ")
        append(authorText)
        if (details.isNotEmpty()) {
            append(", ")
            append(details)
        }
        if (post.hasVideo()) append(context.getString(R.string.post_video_suffix))
    }

    if (isTv) {
        // Modern borderless 10-foot card: focus ring & zoom live on the 16:9 poster
        Column(
            modifier = modifier
                .fillMaxWidth()
                .zIndex(if (focused) 2f else 0f)
                .semantics { contentDescription = description }
                .onFocusChanged { focused = it.isFocused }
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
                    .clip(CardShape)
                    .border(
                        width = if (focused) 2.5.dp else 1.dp,
                        color = if (focused) palette.focusRing else palette.hairline,
                        shape = CardShape,
                    ),
            ) {
                VideoCoverArtwork(
                    coverUrl = post.cover(),
                    hasVideo = post.hasVideo(),
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
                    url = post.authorAvatar(),
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
                        text = if (details.isEmpty()) authorText else "$authorText  ·  $details",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) palette.ink.copy(alpha = 0.78f) else palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    } else {
        // Clean full-bleed phone card
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
                    coverUrl = post.cover(),
                    hasVideo = post.hasVideo(),
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
                    url = post.authorAvatar(),
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
                        text = if (details.isEmpty()) authorText else "$authorText  ·  $details",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
            .onFocusChanged { focused = it.isFocused }
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
