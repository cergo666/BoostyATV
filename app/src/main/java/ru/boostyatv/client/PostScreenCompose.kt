package ru.boostyatv.client

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal interface PostBackend {
    fun loadComments(
        post: Post,
        parent: Long,
        offset: String,
        onSuccess: (BoostyApi.Comments) -> Unit,
        onError: () -> Unit,
    )

    fun loadReactionTypes(
        slug: String,
        onSuccess: (List<Reactions.Type>) -> Unit,
        onError: () -> Unit,
    )

    fun setReaction(
        post: Post,
        commentId: String,
        choice: String,
        onSuccess: () -> Unit,
        onError: () -> Unit,
    )
}

private data class ReactionPickerRequest(
    val commentId: String,
    val initial: Reactions,
    val types: List<Reactions.Type>,
)

private data class CommentsFeed(
    val items: List<BoostyApi.Comment> = emptyList(),
    val next: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
    val loadedOnce: Boolean = false,
)

@Composable
internal fun PostScreenView(
    post: Post,
    backend: PostBackend,
    watchHistory: WatchHistory,
    progressRevision: Int,
    onBack: () -> Unit,
    onOpenPlayer: (Post, Post.Block, Boolean) -> Unit,
    onOpenTag: (Post, BoostyApi.Tag) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val scope = rememberCoroutineScope()

    val hasComments = post.allowed() && !post.commentsDenied() && post.slug().isNotEmpty()
    val localReactions = remember(post.id()) { mutableStateMapOf<String, Reactions>() }
    val pendingReactions = remember(post.id()) { mutableStateMapOf<String, Boolean>() }
    var reactionPicker by remember(post.id()) { mutableStateOf<ReactionPickerRequest?>(null) }

    var rootComments by remember(post.id()) { mutableStateOf(CommentsFeed()) }
    val replyThreads = remember(post.id()) { mutableStateMapOf<Long, CommentsFeed>() }
    val expandedReplies = remember(post.id()) { mutableStateMapOf<Long, Boolean>() }

    val primaryPlayFocus = remember(post.id()) { FocusRequester() }
    val phoneListState = rememberLazyListState()

    fun fetchComments(parent: Long, offset: String) {
        if (parent == 0L) {
            if (rootComments.loading) return
            rootComments = rootComments.copy(loading = true, failed = false)
        } else {
            val current = replyThreads[parent] ?: CommentsFeed()
            if (current.loading) return
            replyThreads[parent] = current.copy(loading = true, failed = false)
        }
        backend.loadComments(
            post = post,
            parent = parent,
            offset = offset,
            onSuccess = { page ->
                if (parent == 0L) {
                    rootComments = CommentsFeed(
                        items = rootComments.items + page.items(),
                        next = page.next(),
                        loading = false,
                        failed = false,
                        loadedOnce = true,
                    )
                } else {
                    val current = replyThreads[parent] ?: CommentsFeed()
                    replyThreads[parent] = CommentsFeed(
                        items = current.items + page.items(),
                        next = page.next(),
                        loading = false,
                        failed = false,
                        loadedOnce = true,
                    )
                }
            },
            onError = {
                if (parent == 0L) {
                    rootComments = rootComments.copy(loading = false, failed = true)
                } else {
                    val current = replyThreads[parent] ?: CommentsFeed()
                    replyThreads[parent] = current.copy(loading = false, failed = true)
                }
            },
        )
    }

    fun requestReactionPicker(commentId: String, initial: Reactions) {
        if (pendingReactions[commentId] == true) return
        pendingReactions[commentId] = true
        backend.loadReactionTypes(
            slug = post.slug(),
            onSuccess = { types ->
                pendingReactions.remove(commentId)
                if (types.isEmpty()) {
                    Toast.makeText(context, resources.getString(R.string.post_reaction_disabled), Toast.LENGTH_SHORT).show()
                } else {
                    reactionPicker = ReactionPickerRequest(commentId, initial, types)
                }
            },
            onError = {
                pendingReactions.remove(commentId)
                Toast.makeText(context, resources.getString(R.string.post_reaction_load_failed), Toast.LENGTH_LONG).show()
            },
        )
    }

    LaunchedEffect(post.id(), hasComments) {
        if (hasComments && !rootComments.loadedOnce && !rootComments.loading) {
            fetchComments(0L, "")
        }
        if (isTv) {
            runCatching { primaryPlayFocus.requestFocus() }
        }
    }

    val featuredIndex = remember(post.id(), isTv) {
        if (!isTv) -1 else post.blocks().indexOfFirst { it.sources().isNotEmpty() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // Atmospheric hero backdrop from post cover
        if (post.cover().isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isTv) 320.dp else 240.dp),
            ) {
                BoostyArtworkImage(
                    url = post.cover(),
                    size = Artwork.THUMBNAIL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.22f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    palette.background.copy(alpha = 0.25f),
                                    palette.background.copy(alpha = 0.85f),
                                    palette.background,
                                ),
                            )
                        )
                )
            }
        }

        if (isTv) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                val articleModifier = if (hasComments) {
                    Modifier.weight(3f).fillMaxHeight()
                } else {
                    Modifier.fillMaxWidth().widthIn(max = 820.dp).fillMaxHeight()
                }
                Box(
                    modifier = if (hasComments) articleModifier else Modifier.fillMaxSize(),
                    contentAlignment = if (hasComments) Alignment.TopStart else Alignment.TopCenter,
                ) {
                    Column(
                        modifier = (if (hasComments) Modifier.fillMaxSize() else Modifier.widthIn(max = 820.dp).fillMaxHeight())
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PostHeaderRow(post = post, showBack = true, onBack = onBack)
                        Spacer(Modifier.height(14.dp))
                        PostArticleContent(
                            post = post,
                            featuredIndex = featuredIndex,
                            watchHistory = watchHistory,
                            progressRevision = progressRevision,
                            primaryPlayFocus = primaryPlayFocus,
                            localReactions = localReactions,
                            pendingReactions = pendingReactions,
                            onOpenPlayer = onOpenPlayer,
                            onOpenTag = onOpenTag,
                            onOpenReactionPicker = ::requestReactionPicker,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }

                if (hasComments) {
                    Column(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxHeight()
                            .clip(SheetShape)
                            .background(palette.surface.copy(alpha = 0.92f))
                            .border(1.dp, palette.hairline, SheetShape)
                            .padding(18.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.post_comments),
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.ink,
                        )
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (rootComments.loading && rootComments.items.isEmpty()) {
                                items(4) { CommentSkeleton() }
                            } else if (rootComments.items.isEmpty() && !rootComments.loading && !rootComments.failed) {
                                item {
                                    Text(
                                        text = stringResource(R.string.post_no_comments),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.muted,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            } else {
                                items(rootComments.items, key = { it.id() }) { comment ->
                                    CommentCard(
                                        post = post,
                                        comment = comment,
                                        showReplies = true,
                                        expanded = expandedReplies[comment.id()] == true,
                                        replyFeed = replyThreads[comment.id()],
                                        localReactions = localReactions,
                                        pendingReactions = pendingReactions,
                                        onToggleReplies = {
                                            val opening = expandedReplies[comment.id()] != true
                                            expandedReplies[comment.id()] = opening
                                            val existing = replyThreads[comment.id()]
                                            if (opening && (existing == null || (!existing.loadedOnce && !existing.loading))) {
                                                fetchComments(comment.id(), "")
                                            }
                                        },
                                        onLoadMoreReplies = { nextOffset -> fetchComments(comment.id(), nextOffset) },
                                        onRetryReplies = { fetchComments(comment.id(), "") },
                                        onOpenReactionPicker = ::requestReactionPicker,
                                    )
                                }
                            }
                            if (rootComments.failed) {
                                item {
                                    BoostyButton(
                                        text = stringResource(R.string.error_load_failed),
                                        onClick = { fetchComments(0L, rootComments.next) },
                                        primary = false,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else if (rootComments.next.isNotEmpty()) {
                                item {
                                    BoostyButton(
                                        text = if (rootComments.loading) {
                                            stringResource(R.string.loading)
                                        } else {
                                            stringResource(R.string.loading_more)
                                        },
                                        onClick = { fetchComments(0L, rootComments.next) },
                                        primary = false,
                                        enabled = !rootComments.loading,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenToolbar(
                    title = stringResource(R.string.post_title),
                    showBack = true,
                    onBack = onBack,
                    actions = {
                        if (hasComments) {
                            BoostyButton(
                                text = stringResource(R.string.post_comments),
                                onClick = {
                                    scope.launch { phoneListState.animateScrollToItem(1) }
                                },
                                primary = false,
                            )
                        }
                    },
                )

                LazyColumn(
                    state = phoneListState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    item(key = "post_article") {
                        Column {
                            PostHeaderRow(post = post, showBack = false, onBack = onBack)
                            Spacer(Modifier.height(14.dp))
                            PostArticleContent(
                                post = post,
                                featuredIndex = -1,
                                watchHistory = watchHistory,
                                progressRevision = progressRevision,
                                primaryPlayFocus = primaryPlayFocus,
                                localReactions = localReactions,
                                pendingReactions = pendingReactions,
                                onOpenPlayer = onOpenPlayer,
                                onOpenTag = onOpenTag,
                                onOpenReactionPicker = ::requestReactionPicker,
                            )
                        }
                    }

                    if (hasComments) {
                        item(key = "comments_header") {
                            Text(
                                text = stringResource(R.string.post_comments),
                                style = MaterialTheme.typography.headlineMedium,
                                color = palette.ink,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        if (rootComments.loading && rootComments.items.isEmpty()) {
                            items(3) { CommentSkeleton() }
                        } else if (rootComments.items.isEmpty() && !rootComments.loading && !rootComments.failed) {
                            item {
                                Text(
                                    text = stringResource(R.string.post_no_comments),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.muted,
                                )
                            }
                        } else {
                            items(rootComments.items, key = { it.id() }) { comment ->
                                CommentCard(
                                    post = post,
                                    comment = comment,
                                    showReplies = true,
                                    expanded = expandedReplies[comment.id()] == true,
                                    replyFeed = replyThreads[comment.id()],
                                    localReactions = localReactions,
                                    pendingReactions = pendingReactions,
                                    onToggleReplies = {
                                        val opening = expandedReplies[comment.id()] != true
                                        expandedReplies[comment.id()] = opening
                                        val existing = replyThreads[comment.id()]
                                        if (opening && (existing == null || (!existing.loadedOnce && !existing.loading))) {
                                            fetchComments(comment.id(), "")
                                        }
                                    },
                                    onLoadMoreReplies = { nextOffset -> fetchComments(comment.id(), nextOffset) },
                                    onRetryReplies = { fetchComments(comment.id(), "") },
                                    onOpenReactionPicker = ::requestReactionPicker,
                                )
                            }
                        }
                        if (rootComments.failed) {
                            item {
                                BoostyButton(
                                    text = stringResource(R.string.error_load_failed),
                                    onClick = { fetchComments(0L, rootComments.next) },
                                    primary = false,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else if (rootComments.next.isNotEmpty()) {
                            item {
                                BoostyButton(
                                    text = if (rootComments.loading) {
                                        stringResource(R.string.loading)
                                    } else {
                                        stringResource(R.string.loading_more)
                                    },
                                    onClick = { fetchComments(0L, rootComments.next) },
                                    primary = false,
                                    enabled = !rootComments.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    reactionPicker?.let { request ->
        val currentState = localReactions[request.commentId] ?: request.initial
        BoostyOverlaySheet(
            title = stringResource(R.string.post_reaction_dialog_title),
            onDismiss = { reactionPicker = null },
        ) {
            request.types.forEach { type ->
                val isSelected = type.code == currentState.selected()
                BoostySheetOption(
                    label = "${type.emoji}  ${type.label(context)}",
                    selected = isSelected,
                    onClick = {
                        reactionPicker = null
                        val choice = if (isSelected) "" else type.code
                        if (pendingReactions[request.commentId] == true) return@BoostySheetOption
                        pendingReactions[request.commentId] = true
                        backend.setReaction(
                            post = post,
                            commentId = request.commentId,
                            choice = choice,
                            onSuccess = {
                                localReactions[request.commentId] = currentState.withSelected(choice)
                                pendingReactions.remove(request.commentId)
                            },
                            onError = {
                                pendingReactions.remove(request.commentId)
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.post_reaction_save_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    },
                )
            }
            if (currentState.selected().isNotEmpty()) {
                BoostySheetOption(
                    label = stringResource(R.string.post_reaction_remove),
                    danger = true,
                    onClick = {
                        reactionPicker = null
                        if (pendingReactions[request.commentId] == true) return@BoostySheetOption
                        pendingReactions[request.commentId] = true
                        backend.setReaction(
                            post = post,
                            commentId = request.commentId,
                            choice = "",
                            onSuccess = {
                                localReactions[request.commentId] = currentState.withSelected("")
                                pendingReactions.remove(request.commentId)
                            },
                            onError = {
                                pendingReactions.remove(request.commentId)
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.post_reaction_save_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PostHeaderRow(
    post: Post,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showBack) {
                BoostyIconButton(
                    iconRes = R.drawable.ic_back,
                    contentDescription = stringResource(R.string.nav_back),
                    onClick = onBack,
                    containerColor = palette.surfaceElevated.copy(alpha = 0.8f),
                )
            }
            Text(
                text = post.displayTitle(context),
                style = if (isTv) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BoostyAvatar(url = post.authorAvatar(), size = 30.dp)
            val meta = post.displayMetadata(context)
            val subtitle = when {
                post.author().isEmpty() -> meta
                meta.isEmpty() -> post.author()
                else -> "${post.author()}  ·  $meta"
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                )
            }
        }
    }
}

@Composable
private fun PostArticleContent(
    post: Post,
    featuredIndex: Int,
    watchHistory: WatchHistory,
    progressRevision: Int,
    primaryPlayFocus: FocusRequester,
    localReactions: Map<String, Reactions>,
    pendingReactions: Map<String, Boolean>,
    onOpenPlayer: (Post, Post.Block, Boolean) -> Unit,
    onOpenTag: (Post, BoostyApi.Tag) -> Unit,
    onOpenReactionPicker: (String, Reactions) -> Unit,
) {
    val palette = BoostyTheme.palette
    var assignedPrimaryFocus = false

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!post.allowed()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(palette.accentSoft)
                    .border(1.dp, palette.accent.copy(alpha = 0.4f), CardShape)
                    .padding(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.post_access_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.accent,
                )
            }
        }

        if (featuredIndex >= 0) {
            val block = post.blocks()[featuredIndex]
            assignedPrimaryFocus = true
            VideoBlockCard(
                post = post,
                block = block,
                watchHistory = watchHistory,
                progressRevision = progressRevision,
                priority = true,
                playFocusRequester = primaryPlayFocus,
                onOpenPlayer = onOpenPlayer,
            )
        }

        if (post.tags().isNotEmpty() && post.slug().isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(post.tags(), key = { it.id() }) { tag ->
                    BoostyActionChip(
                        text = "#${tag.name()}",
                        iconRes = R.drawable.ic_tag,
                        onClick = { onOpenTag(post, tag) },
                    )
                }
            }
        }

        post.blocks().forEachIndexed { index, block ->
            if (index == featuredIndex) return@forEachIndexed
            when (block.kind()) {
                "text" -> {
                    Text(
                        text = block.text(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.ink.copy(alpha = 0.92f),
                    )
                }
                "image" -> {
                    BoostyArtworkImage(
                        url = block.image(),
                        size = Artwork.PREVIEW,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = if (BoostyTheme.isTv) 320.dp else 280.dp)
                            .clip(CardShape)
                            .border(1.dp, palette.hairline, CardShape),
                    )
                }
                "video" -> {
                    val usePrimaryFocus = !assignedPrimaryFocus && block.sources().isNotEmpty()
                    if (usePrimaryFocus) assignedPrimaryFocus = true
                    VideoBlockCard(
                        post = post,
                        block = block,
                        watchHistory = watchHistory,
                        progressRevision = progressRevision,
                        priority = usePrimaryFocus,
                        playFocusRequester = if (usePrimaryFocus) primaryPlayFocus else null,
                        onOpenPlayer = onOpenPlayer,
                    )
                }
            }
        }

        if (post.allowed() && !post.reactionsDisabled() && post.slug().isNotEmpty()) {
            ReactionsBar(
                reactions = localReactions[""] ?: post.reactions(),
                pending = pendingReactions[""] == true,
                onClick = { onOpenReactionPicker("", post.reactions()) },
            )
        }
    }
}

@Composable
private fun VideoBlockCard(
    post: Post,
    block: Post.Block,
    watchHistory: WatchHistory,
    progressRevision: Int,
    priority: Boolean,
    playFocusRequester: FocusRequester?,
    onOpenPlayer: (Post, Post.Block, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val progress = remember(block.videoId(), progressRevision) { watchHistory.get(block) }
    val resumeMs = progress.resume()
    val durationLabel = remember(block.durationMs(), progress.duration()) {
        formatDurationShort(progress.duration().takeIf { it > 0L } ?: block.durationMs())
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (block.image().isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (isTv) 290.dp else 240.dp)
                    .aspectRatio(16f / 9f)
                    .clip(CardShape)
                    .border(1.dp, palette.hairline, CardShape),
            ) {
                BoostyArtworkImage(
                    url = block.image(),
                    size = Artwork.PREVIEW,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    priority = priority,
                    placeholderIcon = R.drawable.ic_play,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA08090D)))
                        )
                )
                if (durationLabel.isNotEmpty()) {
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.ink,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 10.dp, bottom = if (progress.percentage() > 0) 11.dp else 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.badgeBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                if (progress.percentage() > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .align(Alignment.BottomStart)
                            .background(Color(0x55FFFFFF)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((progress.percentage() / 100f).coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .background(palette.accent),
                        )
                    }
                }
            }
        }

        if (block.sources().isEmpty()) {
            Text(
                text = stringResource(R.string.post_video_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
        } else {
            val downloadOptions = remember(block.videoId(), block.sources()) {
                VideoDownloader.availableOptions(block.sources())
            }
            var showDownloadSheet by remember { mutableStateOf(false) }
            val videoTitle = remember(block.text(), post.id()) {
                block.text().ifBlank { post.displayTitle(context) }
            }
            val defaultPlayLabel = stringResource(if (isTv) R.string.post_play else R.string.post_play_from_start)
            val playLabel = if (resumeMs > 0L) {
                stringResource(R.string.post_continue_watching, TimeFormat.format(context, resumeMs))
            } else {
                defaultPlayLabel
            }
            if (isTv) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BoostyButton(
                        text = playLabel,
                        iconRes = R.drawable.ic_play,
                        primary = true,
                        onClick = { onOpenPlayer(post, block, false) },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (playFocusRequester != null) Modifier.focusRequester(playFocusRequester) else Modifier),
                    )
                    if (resumeMs > 0L) {
                        BoostyButton(
                            text = stringResource(R.string.post_play_from_start),
                            primary = false,
                            onClick = { onOpenPlayer(post, block, true) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (downloadOptions.isNotEmpty()) {
                        BoostyButton(
                            text = stringResource(R.string.post_download),
                            iconRes = R.drawable.ic_download,
                            primary = false,
                            onClick = { showDownloadSheet = true },
                        )
                    }
                }
            } else {
                BoostyButton(
                    text = playLabel,
                    iconRes = R.drawable.ic_play,
                    primary = true,
                    onClick = { onOpenPlayer(post, block, false) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (resumeMs > 0L || downloadOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (resumeMs > 0L) {
                            BoostyButton(
                                text = stringResource(R.string.post_play_from_start),
                                primary = false,
                                onClick = { onOpenPlayer(post, block, true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (downloadOptions.isNotEmpty()) {
                            BoostyButton(
                                text = stringResource(R.string.post_download),
                                iconRes = R.drawable.ic_download,
                                primary = false,
                                onClick = { showDownloadSheet = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (showDownloadSheet) {
                BoostyOverlaySheet(
                    title = stringResource(R.string.post_download_title),
                    subtitle = videoTitle,
                    onDismiss = { showDownloadSheet = false },
                ) {
                    downloadOptions.forEach { option ->
                        BoostySheetOption(
                            label = option.label,
                            iconRes = R.drawable.ic_download,
                            onClick = {
                                showDownloadSheet = false
                                val started = VideoDownloader.enqueue(context, videoTitle, option)
                                val message = if (started) {
                                    resources.getString(R.string.post_download_started, option.label)
                                } else {
                                    resources.getString(R.string.post_download_failed)
                                }
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionsBar(
    reactions: Reactions,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val palette = BoostyTheme.palette
    val ranked = remember(reactions) { reactions.rankedTypes() }
    val selectedType = remember(reactions) {
        Reactions.Type.values().firstOrNull { it.code == reactions.selected() }
    }
    val buttonLabel = if (selectedType != null) {
        "${selectedType.emoji} ${selectedType.label(context)}"
    } else {
        stringResource(R.string.post_reaction_button)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoostyButton(
            text = buttonLabel,
            primary = false,
            enabled = !pending,
            onClick = onClick,
        )
        if (ranked.isNotEmpty()) {
            Text(
                text = ranked.take(6).joinToString("   ") { "${it.emoji}\u00a0${reactions.count(it)}" },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CommentCard(
    post: Post,
    comment: BoostyApi.Comment,
    showReplies: Boolean,
    expanded: Boolean,
    replyFeed: CommentsFeed?,
    localReactions: Map<String, Reactions>,
    pendingReactions: Map<String, Boolean>,
    onToggleReplies: () -> Unit,
    onLoadMoreReplies: (String) -> Unit,
    onRetryReplies: () -> Unit,
    onOpenReactionPicker: (String, Reactions) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }

    val bg by animateColorAsState(
        targetValue = when {
            focused -> palette.surfaceFocused
            isTv -> palette.background.copy(alpha = 0.72f)
            else -> palette.surface
        },
        animationSpec = tween(140),
        label = "commentBg",
    )

    val authorHeader = remember(comment.id(), configuration) {
        val name = comment.author().ifEmpty { resources.getString(R.string.post_author_unknown) }
        if (comment.created() > 0L) {
            val locale = configuration.locales[0]
            "$name  ·  ${DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(comment.created() * 1000L))}"
        } else {
            name
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(bg)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) palette.focusRing else palette.hairline,
                shape = ControlShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .then(if (isTv) Modifier.focusable() else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BoostyAvatar(url = comment.authorAvatar(), size = if (isTv) 32.dp else 28.dp)
            Text(
                text = authorHeader,
                style = MaterialTheme.typography.titleSmall,
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        comment.blocks().forEach { block ->
            if (block.kind() == "text") {
                Text(
                    text = block.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.ink.copy(alpha = 0.92f),
                )
            } else if (block.kind() == "image") {
                BoostyArtworkImage(
                    url = block.image(),
                    size = Artwork.THUMBNAIL,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(ControlShape),
                )
            }
        }

        if (!post.reactionsDisabled() && comment.reactionId().isNotEmpty()) {
            ReactionsBar(
                reactions = localReactions[comment.reactionId()] ?: comment.reactions(),
                pending = pendingReactions[comment.reactionId()] == true,
                onClick = { onOpenReactionPicker(comment.reactionId(), comment.reactions()) },
            )
        }

        if (showReplies && comment.id() > 0L && comment.replyCount() > 0) {
            BoostyButton(
                text = if (expanded) {
                    stringResource(R.string.post_collapse_replies)
                } else {
                    stringResource(R.string.post_replies, comment.replyCount())
                },
                primary = false,
                onClick = onToggleReplies,
            )
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val feed = replyFeed ?: CommentsFeed()
                    if (feed.loading && feed.items.isEmpty()) {
                        CommentSkeleton()
                    } else if (feed.items.isEmpty() && !feed.failed && feed.loadedOnce) {
                        Text(
                            text = stringResource(R.string.post_no_replies),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                        )
                    } else {
                        feed.items.forEach { reply ->
                            CommentCard(
                                post = post,
                                comment = reply,
                                showReplies = false,
                                expanded = false,
                                replyFeed = null,
                                localReactions = localReactions,
                                pendingReactions = pendingReactions,
                                onToggleReplies = {},
                                onLoadMoreReplies = {},
                                onRetryReplies = {},
                                onOpenReactionPicker = onOpenReactionPicker,
                            )
                        }
                    }
                    if (feed.failed) {
                        BoostyButton(
                            text = stringResource(R.string.error_load_failed),
                            primary = false,
                            onClick = onRetryReplies,
                        )
                    } else if (feed.next.isNotEmpty()) {
                        BoostyButton(
                            text = if (feed.loading) stringResource(R.string.loading) else stringResource(R.string.loading_more),
                            primary = false,
                            enabled = !feed.loading,
                            onClick = { onLoadMoreReplies(feed.next) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(BoostyTheme.palette.surfaceElevated.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShimmerBox(modifier = Modifier.size(28.dp), shape = PillShape)
            ShimmerBox(modifier = Modifier.width(120.dp).height(12.dp), shape = RoundedCornerShape(6.dp))
        }
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(12.dp), shape = RoundedCornerShape(6.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.65f).height(12.dp), shape = RoundedCornerShape(6.dp))
    }
}
