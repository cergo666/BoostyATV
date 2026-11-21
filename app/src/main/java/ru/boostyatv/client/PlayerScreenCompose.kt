package ru.boostyatv.client

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID

internal interface PlayerHostCallbacks {
    val isTv: Boolean
    val watchHistory: WatchHistory
    val playbackOptions: PlaybackOptions
    fun onSyncWatchProgress()
    fun onProgressUpdated()
    fun onPlayerOpened()
    fun onPlayerClosed()
}

private enum class PlayerMenuPage {
    MAIN,
    QUALITY,
    SPEED,
    AUDIO,
    SUBTITLES,
    FRAME_SIZE,
}

internal data class TrackChoice(
    val label: String,
    val selected: Boolean,
    val group: Tracks.Group?,
    val trackIndex: Int,
)

@Stable
@OptIn(UnstableApi::class)
internal class PlayerSessionController(
    private val activity: ComponentActivity,
    private val callbacks: PlayerHostCallbacks,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val externalPlayer: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val item = externalVideo
        val app = externalApp
        externalVideo = null
        externalApp = null
        if (item == null || app == null) return@registerForActivityResult
        val progress = app.result(result.resultCode, result.data, item.durationMs())
        if (progress != null) {
            recordProgress(item, progress.position(), progress.duration())
            callbacks.onProgressUpdated()
        }
    }
    private val notificationPermission: ActivityResultLauncher<String> = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    // Exposed for MediaController tests & Compose
    @JvmField
    var player: MediaController? = null
    private var pendingController: ListenableFuture<MediaController>? = null

    var active by mutableStateOf(false)
        private set
    var videoTitle by mutableStateOf("")
        private set
    var videoAuthor by mutableStateOf("")
        private set
    var videoAuthorSlug by mutableStateOf("")
        private set
    var isLive by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var currentPositionMs by mutableLongStateOf(0L)
        private set
    var bufferedPositionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var playbackSpeed by mutableFloatStateOf(1f)
        private set
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        private set
    var showStreamStats by mutableStateOf(false)
        private set
    var streamStatsText by mutableStateOf("")
        private set
    var playbackError by mutableStateOf(false)
        private set
    var controllerRevision by mutableIntStateOf(0)
        private set
    var tracksRevision by mutableIntStateOf(0)
        private set

    private var video: Post.Block? = null
    private var externalVideo: Post.Block? = null
    private var externalApp: PlaybackOptions.App? = null
    private var playbackSources: List<Post.Source> = emptyList()
    private var sourceIndex = 0
    private var playbackPosition = 0L
    private var resumePlayback = true
    private var playbackStarted = false
    private var lastWrittenTime = -1L
    private var videoFormat: Format? = null
    private var audioFormat: Format? = null
    private var reconnectAttempted = false
    private var mediaId: String = ""

    private val savePlaybackTick = object : Runnable {
        override fun run() {
            val controller = player ?: return
            if (controller.isPlaying) savePlayback()
            handler.postDelayed(this, 15_000L)
        }
    }

    private val uiProgressTick = object : Runnable {
        override fun run() {
            val controller = player ?: return
            currentPositionMs = controller.currentPosition.coerceAtLeast(0L)
            bufferedPositionMs = controller.bufferedPosition.coerceAtLeast(0L)
            val d = controller.duration
            durationMs = if (d > 0L) d else (video?.durationMs() ?: 0L)
            if (showStreamStats) {
                streamStatsText = buildStreamStatsText(controller)
            }
            handler.postDelayed(this, 500L)
        }
    }

    fun hasVideo(): Boolean = video != null

    fun open(post: Post, block: Post.Block, fromStart: Boolean) {
        val live = block.kind() == LIVE_BLOCK
        val app = callbacks.playbackOptions.player()
        val position = if (fromStart || live) 0L else callbacks.watchHistory.get(block).resume()
        if (!live && app != PlaybackOptions.App.BUILT_IN && launchExternal(block, app, position)) return
        video = block
        isLive = live
        videoTitle = block.text().ifBlank { post.displayTitle(activity) }
        videoAuthor = post.author()
        videoAuthorSlug = post.slug()
        mediaId = UUID.randomUUID().toString()
        reconnectAttempted = false
        playbackError = false
        requestNotificationPermission()
        playbackSpeed = if (isLive) 1f else callbacks.playbackOptions.speedFor(videoAuthorSlug)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        videoFormat = null
        audioFormat = null
        playbackSources = callbacks.playbackOptions.sources(block)
        sourceIndex = 0
        playbackPosition = position
        currentPositionMs = position
        durationMs = block.durationMs()
        lastWrittenTime = -1L
        playbackStarted = false
        resumePlayback = true
        active = true
        fullscreen(true)
        callbacks.onPlayerOpened()
        startPlayer()
    }

    fun restoreState(savedState: Bundle?) {
        if (savedState != null && savedState.containsKey("external_player")) {
            externalApp = PlaybackOptions.App.valueOf(savedState.getString("external_player")!!)
            externalVideo = Post.Block(
                "video",
                "",
                "",
                emptyList(),
                savedState.getString("external_video", ""),
                0,
                savedState.getLong("external_duration"),
            )
        }
        if (savedState == null || !savedState.containsKey("builtin_media_id")) return
        val urls = savedState.getStringArrayList("builtin_urls")
        val types = savedState.getStringArrayList("builtin_types")
        if (urls == null || types == null || urls.isEmpty() || urls.size != types.size) return
        val sources = ArrayList<Post.Source>(urls.size)
        for (i in urls.indices) sources.add(Post.Source(types[i], urls[i]))
        val restoredTitle = savedState.getString("builtin_title", "")
        isLive = savedState.getBoolean("builtin_live")
        video = Post.Block(
            if (isLive) LIVE_BLOCK else "video",
            restoredTitle,
            "",
            sources,
            savedState.getString("builtin_video_id", ""),
            0,
            savedState.getLong("builtin_duration"),
        )
        videoTitle = restoredTitle
        videoAuthor = savedState.getString("builtin_author", "")
        videoAuthorSlug = savedState.getString("builtin_author_slug", "")
        mediaId = savedState.getString("builtin_media_id", "")
        playbackSources = sources
        sourceIndex = savedState.getInt("builtin_source").coerceIn(0, sources.lastIndex)
        playbackPosition = savedState.getLong("builtin_position")
        currentPositionMs = playbackPosition
        durationMs = savedState.getLong("builtin_duration")
        resumePlayback = savedState.getBoolean("builtin_playing")
        playbackSpeed = savedState.getFloat("builtin_speed", 1f)
        playbackError = false
        active = true
        fullscreen(true)
        callbacks.onPlayerOpened()
    }

    fun saveState(out: Bundle) {
        val currentVideo = video
        if (currentVideo != null) {
            val urls = ArrayList<String>(playbackSources.size)
            val types = ArrayList<String>(playbackSources.size)
            for (source in playbackSources) {
                urls.add(source.url())
                types.add(source.type())
            }
            out.putStringArrayList("builtin_urls", urls)
            out.putStringArrayList("builtin_types", types)
            out.putString("builtin_media_id", mediaId)
            out.putString("builtin_video_id", currentVideo.videoId())
            out.putString("builtin_title", videoTitle)
            out.putString("builtin_author", videoAuthor)
            out.putString("builtin_author_slug", videoAuthorSlug)
            out.putBoolean("builtin_live", isLive)
            out.putLong("builtin_duration", currentVideo.durationMs())
            out.putInt("builtin_source", sourceIndex)
            out.putLong("builtin_position", player?.currentPosition ?: playbackPosition)
            out.putBoolean("builtin_playing", player?.playWhenReady ?: resumePlayback)
            out.putFloat("builtin_speed", playbackSpeed)
        }
        val extVideo = externalVideo
        val extApp = externalApp
        if (extVideo != null && extApp != null) {
            out.putString("external_player", extApp.name)
            out.putString("external_video", extVideo.videoId())
            out.putLong("external_duration", extVideo.durationMs())
        }
    }

    fun start() {
        if (video == null) return
        if (player == null) startPlayer() else scheduleTicks()
    }

    fun stop() {
        savePlayback()
        handler.removeCallbacks(savePlaybackTick)
        handler.removeCallbacks(uiProgressTick)
    }

    fun destroy() {
        handler.removeCallbacks(savePlaybackTick)
        handler.removeCallbacks(uiProgressTick)
        releaseController(!activity.isChangingConfigurations)
    }

    fun close() {
        releaseController(true)
        video = null
        active = false
        isLive = false
        isPlaying = false
        playbackError = false
        showStreamStats = false
        playbackSources = emptyList()
        fullscreen(false)
        callbacks.onPlayerClosed()
    }

    fun togglePlayPause() {
        val controller = player ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (isLive) controller.seekToDefaultPosition()
            else if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0L)
            controller.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (isLive) return
        val controller = player ?: return
        val maxDuration = if (controller.duration > 0L) controller.duration else Long.MAX_VALUE
        val target = (controller.currentPosition + deltaMs).coerceIn(0L, maxDuration)
        seekTo(target)
    }

    fun seekTo(timeMs: Long) {
        if (isLive) return
        val controller = player ?: return
        val clamped = timeMs.coerceAtLeast(0L)
        controller.seekTo(clamped)
        currentPositionMs = clamped
        val currentVideo = video
        if (clamped == 0L && playbackStarted && currentVideo != null &&
            callbacks.watchHistory.get(currentVideo).position() > 0L
        ) {
            lastWrittenTime = 0L
            val d = if (controller.duration > 0L) controller.duration else currentVideo.durationMs()
            recordProgress(currentVideo, 0L, d)
        }
    }

    fun setSpeed(speed: Float) {
        if (isLive) return
        playbackSpeed = speed
        callbacks.playbackOptions.setSpeedFor(videoAuthorSlug, speed)
        player?.setPlaybackSpeed(speed)
    }

    fun setQualityIndex(index: Int) {
        callbacks.playbackOptions.setQuality(index)
        val controller = player ?: return
        val currentVideo = video ?: return
        playbackPosition = controller.currentPosition
        resumePlayback = controller.playWhenReady
        playbackSources = callbacks.playbackOptions.sources(currentVideo)
        sourceIndex = 0
        applyQualityLimit(controller)
        prepareSource(controller)
    }

    fun setFrameResizeMode(mode: Int) {
        resizeMode = mode
    }

    fun toggleStreamStats() {
        showStreamStats = !showStreamStats
        val controller = player
        if (showStreamStats && controller != null) {
            streamStatsText = buildStreamStatsText(controller)
        }
    }

    fun tracksFor(type: Int): List<TrackChoice> {
        val controller = player ?: return emptyList()
        val tracks = controller.currentTracks
        val params = controller.trackSelectionParameters
        val result = ArrayList<TrackChoice>()
        val isDisabled = params.disabledTrackTypes.contains(type)
        val hasOverride = params.overrides.values.any { it.type == type }

        if (type == C.TRACK_TYPE_TEXT) {
            result.add(
                TrackChoice(
                    label = activity.getString(R.string.player_subtitles_off),
                    selected = isDisabled || tracks.groups.none { g ->
                        g.type == type && (0 until g.length).any { g.isTrackSelected(it) }
                    },
                    group = null,
                    trackIndex = -1,
                )
            )
        } else {
            result.add(
                TrackChoice(
                    label = activity.getString(R.string.player_track_auto),
                    selected = !hasOverride && !isDisabled,
                    group = null,
                    trackIndex = -2,
                )
            )
        }

        var counter = 1
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val label = formatTrackLabel(format, counter++)
                val isSelected = !isDisabled && group.isTrackSelected(i) &&
                    (type == C.TRACK_TYPE_TEXT || hasOverride)
                result.add(TrackChoice(label = label, selected = isSelected, group = group, trackIndex = i))
            }
        }
        return result
    }

    fun selectTrack(type: Int, choice: TrackChoice) {
        val controller = player ?: return
        val builder = controller.trackSelectionParameters.buildUpon()
        when {
            choice.trackIndex == -1 -> {
                builder.clearOverridesOfType(type)
                builder.setTrackTypeDisabled(type, true)
            }
            choice.trackIndex == -2 -> {
                builder.clearOverridesOfType(type)
                builder.setTrackTypeDisabled(type, false)
            }
            choice.group != null -> {
                builder.setTrackTypeDisabled(type, false)
                builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndex))
            }
        }
        controller.trackSelectionParameters = builder.build()
        tracksRevision++
    }

    fun releaseController(stopService: Boolean) {
        handler.removeCallbacks(savePlaybackTick)
        handler.removeCallbacks(uiProgressTick)
        pendingController?.cancel(true)
        pendingController = null
        val controller = player
        if (controller == null) {
            if (stopService) activity.stopService(Intent(activity, PlaybackService::class.java))
            return
        }
        savePlayback()
        if (playbackStarted) playbackPosition = controller.currentPosition
        resumePlayback = controller.playWhenReady
        controller.release()
        player = null
        controllerRevision++
        if (stopService) activity.stopService(Intent(activity, PlaybackService::class.java))
    }

    private fun startPlayer() {
        if (video == null || player != null || pendingController != null) return
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val future = MediaController.Builder(activity, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    activity.runOnUiThread { handleDisconnected(controller) }
                }
            })
            .buildAsync()
        pendingController = future
        Futures.addCallback(
            future,
            object : FutureCallback<MediaController> {
                override fun onSuccess(controller: MediaController) {
                    if (pendingController !== future) {
                        controller.release()
                        return
                    }
                    attachController(controller)
                }

                override fun onFailure(failure: Throwable) {
                    activity.runOnUiThread {
                        if (pendingController !== future) return@runOnUiThread
                        pendingController = null
                        if (video == null || player != null || activity.isFinishing || activity.isDestroyed) {
                            return@runOnUiThread
                        }
                        playbackError = true
                    }
                }
            },
            { command -> handler.post(command) },
        )
    }

    private fun attachController(controller: MediaController) {
        pendingController = null
        if (activity.isDestroyed || activity.isFinishing || video == null || player != null) {
            controller.release()
            return
        }
        player = controller
        val current = controller.currentMediaItem
        val continuing = current != null && mediaId == current.mediaId &&
            controller.playbackState != Player.STATE_IDLE
        playbackStarted = continuing && (controller.playbackState == Player.STATE_READY ||
            controller.playbackState == Player.STATE_ENDED)
        videoFormat = selectedFormat(controller.currentTracks, C.TRACK_TYPE_VIDEO)
        audioFormat = selectedFormat(controller.currentTracks, C.TRACK_TYPE_AUDIO)
        isPlaying = controller.isPlaying
        isBuffering = controller.playbackState == Player.STATE_BUFFERING
        controller.setPlaybackSpeed(playbackSpeed)
        applyQualityLimit(controller)
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) playbackStarted = true
                else if (state == Player.STATE_ENDED) {
                    if (isLive) playbackError = true else savePlayback()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing && controller.playbackState != Player.STATE_BUFFERING) {
                    savePlayback()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                videoFormat = selectedFormat(tracks, C.TRACK_TYPE_VIDEO)
                audioFormat = selectedFormat(tracks, C.TRACK_TYPE_AUDIO)
                tracksRevision++
            }

            override fun onPlayerError(error: PlaybackException) {
                if (video == null || player == null) return
                if (playbackStarted) playbackPosition = controller.currentPosition
                resumePlayback = controller.playWhenReady
                if (++sourceIndex < playbackSources.size) {
                    prepareSource(controller)
                } else {
                    sourceIndex = (playbackSources.size - 1).coerceAtLeast(0)
                    playbackError = true
                }
            }
        })
        if (!continuing) prepareSource(controller)
        else if (isLive && controller.playbackState == Player.STATE_ENDED) playbackError = true
        controllerRevision++
        scheduleTicks()
    }

    private fun handleDisconnected(controller: MediaController) {
        if (player !== controller) return
        if (playbackStarted) playbackPosition = controller.currentPosition
        resumePlayback = controller.playWhenReady
        player = null
        controllerRevision++
        handler.removeCallbacks(savePlaybackTick)
        handler.removeCallbacks(uiProgressTick)
        if (video == null || activity.isFinishing || activity.isDestroyed) return
        if (reconnectAttempted) {
            playbackError = true
            return
        }
        reconnectAttempted = true
        startPlayer()
    }

    private fun applyQualityLimit(controller: MediaController) {
        val height = PlaybackOptions.HEIGHTS[callbacks.playbackOptions.qualityIndex()]
        controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, if (height == 0) Int.MAX_VALUE else height)
            .build()
    }

    private fun prepareSource(controller: MediaController) {
        if (playbackSources.isEmpty()) {
            playbackError = true
            return
        }
        val source = playbackSources[sourceIndex]
        val item = MediaItem.Builder().setMediaId(mediaId).setUri(source.url())
        if (source.type().contains("hls")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        else if (source.type().contains("dash")) item.setMimeType(MimeTypes.APPLICATION_MPD)
        if (isLive) controller.setMediaItem(item.build())
        else controller.setMediaItem(item.build(), playbackPosition)
        controller.playWhenReady = resumePlayback
        controller.prepare()
    }

    private fun scheduleTicks() {
        handler.removeCallbacks(savePlaybackTick)
        handler.removeCallbacks(uiProgressTick)
        if (video == null || player == null) return
        handler.postDelayed(savePlaybackTick, 15_000L)
        handler.post(uiProgressTick)
    }

    private fun savePlayback() {
        val controller = player ?: return
        val currentVideo = video ?: return
        if (isLive || !playbackStarted) return
        val duration = if (controller.duration > 0L) controller.duration else currentVideo.durationMs()
        val position = if (controller.playbackState == Player.STATE_ENDED && duration > 0L) {
            duration
        } else {
            controller.currentPosition
        }
        val seconds = position / 1000L
        if (seconds == lastWrittenTime || (seconds == 0L && lastWrittenTime < 0L)) return
        lastWrittenTime = seconds
        recordProgress(currentVideo, position, duration)
    }

    private fun recordProgress(item: Post.Block, position: Long, duration: Long) {
        if (item.videoId().isEmpty()) return
        callbacks.watchHistory.save(item, position, duration)
        callbacks.onSyncWatchProgress()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launchExternal(block: Post.Block, app: PlaybackOptions.App, position: Long): Boolean {
        val source = callbacks.playbackOptions.sources(block)[0]
        val intent = app.intent(source, block.text(), position)
        if (app == PlaybackOptions.App.VIMU && intent.resolveActivity(activity.packageManager) == null) {
            intent.setPackage(PlaybackOptions.VIMU_RUSTORE)
        }
        return try {
            if (app == PlaybackOptions.App.JUST_PLUS) {
                JustPlusPlayback.configure(activity, callbacks.watchHistory, intent, block, position)
                activity.startActivity(intent)
            } else if (app == PlaybackOptions.App.OTHER) {
                if (activity.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
                    throw ActivityNotFoundException()
                }
                activity.startActivity(
                    Intent.createChooser(intent, activity.getString(R.string.player_external_chooser))
                )
            } else {
                externalVideo = block
                externalApp = app
                externalPlayer.launch(intent)
            }
            true
        } catch (_: ActivityNotFoundException) {
            externalVideo = null
            externalApp = null
            Toast.makeText(activity, activity.getString(R.string.player_external_not_found), Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun buildStreamStatsText(controller: MediaController): String {
        val none = activity.getString(R.string.player_stats_none)
        val v = videoFormat
        val a = audioFormat
        val vInfo = if (v == null) none else listOf(
            if (v.width > 0 && v.height > 0) "${v.width}×${v.height}" else none,
            if (v.frameRate > 0f) String.format(Locale.ROOT, "%.1f fps", v.frameRate) else none,
            codecName(v),
            bitrateLabel(v.bitrate),
        ).joinToString(" · ")
        val aInfo = if (a == null) none else listOf(
            codecName(a),
            if (a.channelCount > 0) activity.getString(R.string.player_stats_channels, a.channelCount) else none,
            if (a.sampleRate > 0) activity.getString(R.string.player_stats_khz, a.sampleRate / 1000f) else none,
            bitrateLabel(a.bitrate),
        ).joinToString(" · ")
        val buffered = (controller.bufferedPosition - controller.currentPosition).coerceAtLeast(0L)
        return "${activity.getString(R.string.player_stats_video)}  $vInfo\n" +
            "${activity.getString(R.string.player_stats_audio)}  $aInfo\n" +
            "${activity.getString(R.string.player_stats_buffer)}  ${activity.getString(R.string.player_stats_seconds, buffered / 1000f)}"
    }

    private fun formatTrackLabel(format: Format, index: Int): String {
        val parts = ArrayList<String>()
        if (!format.label.isNullOrBlank()) parts.add(format.label!!)
        val lang = format.language
        if (!lang.isNullOrBlank() && lang != "und") {
            val locale = Locale.forLanguageTag(lang)
            val display = locale.displayName.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            if (display.isNotBlank() && parts.none { it.equals(display, ignoreCase = true) }) {
                parts.add(display)
            }
        }
        if (format.channelCount > 0) {
            parts.add(activity.getString(R.string.player_stats_channels, format.channelCount))
        }
        return if (parts.isEmpty()) "#$index" else parts.joinToString(" · ")
    }

    private fun codecName(format: Format): String {
        format.codecs?.let { return it }
        format.sampleMimeType?.let { return it.replaceFirst(Regex("^(video|audio)/"), "") }
        return activity.getString(R.string.player_stats_none)
    }

    private fun bitrateLabel(bps: Int): String {
        return if (bps > 0) {
            activity.getString(R.string.player_stats_kbps, Math.round(bps / 1000f))
        } else {
            activity.getString(R.string.player_stats_none)
        }
    }

    private fun selectedFormat(tracks: Tracks, type: Int): Format? {
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun fullscreen(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = activity.window.insetsController ?: return
            if (enabled) {
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            activity.window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                0
            }
        }
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun PlayerScreenView(
    controller: PlayerSessionController,
    options: PlaybackOptions,
    inPictureInPicture: Boolean = false,
    onPlayNext: (() -> Unit)? = null,
    onRetryLive: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var menuPage by remember { mutableStateOf<PlayerMenuPage?>(null) }
    var showSeekTimeDialog by remember { mutableStateOf(false) }
    var seekError by remember { mutableStateOf<String?>(null) }
    var transientFeedback by remember { mutableStateOf<String?>(null) }
    var feedbackNonce by remember { mutableIntStateOf(0) }
    var accumulatedSeekSec by remember { mutableIntStateOf(0) }
    var containerWidthPx by remember { mutableIntStateOf(1) }

    val rootFocus = remember { FocusRequester() }
    val playButtonFocus = remember { FocusRequester() }

    fun bumpControls() {
        controlsVisible = true
        interactionNonce++
    }

    fun showFeedback(text: String) {
        transientFeedback = text
        feedbackNonce++
    }

    fun stepSeek(deltaSec: Int) {
        if (controller.isLive) return
        controller.seekBy(deltaSec * 1000L)
        accumulatedSeekSec = if (
            (accumulatedSeekSec > 0 && deltaSec > 0) || (accumulatedSeekSec < 0 && deltaSec < 0)
        ) {
            accumulatedSeekSec + deltaSec
        } else {
            deltaSec
        }
        val sign = if (accumulatedSeekSec > 0) "+" else "−"
        showFeedback("$sign${kotlin.math.abs(accumulatedSeekSec)} с")
    }

    // Auto-hide HUD while playing
    LaunchedEffect(controlsVisible, interactionNonce, controller.isPlaying, menuPage, showSeekTimeDialog) {
        if (controlsVisible && controller.isPlaying && menuPage == null && !showSeekTimeDialog) {
            delay(if (isTv) 7000L else 4500L)
            controlsVisible = false
        }
    }

    LaunchedEffect(feedbackNonce) {
        if (transientFeedback != null) {
            delay(950L)
            transientFeedback = null
            accumulatedSeekSec = 0
        }
    }

    LaunchedEffect(controlsVisible, menuPage, showSeekTimeDialog, controller.isBuffering) {
        if (isTv && menuPage == null && !showSeekTimeDialog) {
            if (controlsVisible && !controller.isBuffering) {
                withFrameNanos { }
                runCatching { playButtonFocus.requestFocus() }
            } else if (!controlsVisible) {
                runCatching { rootFocus.requestFocus() }
            }
        }
    }

    BackHandler(enabled = isTv && controlsVisible && menuPage == null && !showSeekTimeDialog && !controller.playbackError) {
        controlsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                val code = native.keyCode
                if (menuPage != null || showSeekTimeDialog) return@onPreviewKeyEvent false

                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (code) {
                        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                            if (native.repeatCount == 0) {
                                bumpControls()
                                menuPage = PlayerMenuPage.MAIN
                            }
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            controller.togglePlayPause()
                            bumpControls()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            controller.player?.play()
                            bumpControls()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            controller.player?.pause()
                            bumpControls()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (controller.isLive) return@onPreviewKeyEvent true
                            controller.seekBy(-10_000L)
                            showFeedback(resources.getString(R.string.player_seek_back_short))
                            bumpControls()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (controller.isLive) return@onPreviewKeyEvent true
                            controller.seekBy(10_000L)
                            showFeedback(resources.getString(R.string.player_seek_forward_short))
                            bumpControls()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!controlsVisible) {
                                val step = when {
                                    native.repeatCount >= 8 -> -30
                                    native.repeatCount >= 3 -> -15
                                    else -> -10
                                }
                                stepSeek(step)
                                bumpControls()
                                return@onPreviewKeyEvent true
                            }
                            interactionNonce++
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!controlsVisible) {
                                val step = when {
                                    native.repeatCount >= 8 -> 30
                                    native.repeatCount >= 3 -> 15
                                    else -> 10
                                }
                                stepSeek(step)
                                bumpControls()
                                return@onPreviewKeyEvent true
                            }
                            interactionNonce++
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (!controlsVisible) {
                                bumpControls()
                                return@onPreviewKeyEvent true
                            }
                            interactionNonce++
                        }
                    }
                }
                false
            }
            .onSizeChanged { containerWidthPx = it.width.coerceAtLeast(1) }
            .pointerInput(isTv, inPictureInPicture) {
                if (isTv || inPictureInPicture) return@pointerInput
                detectTapGestures(
                    onTap = {
                        if (controlsVisible) controlsVisible = false else bumpControls()
                    },
                    onDoubleTap = { offset ->
                        val isLeft = offset.x < containerWidthPx * 0.45f
                        val isRight = offset.x > containerWidthPx * 0.55f
                        when {
                            isLeft -> stepSeek(-10)
                            isRight -> stepSeek(10)
                            else -> controller.togglePlayPause()
                        }
                    },
                )
            },
    ) {
        // Video surface via Media3 PlayerView without default legacy chrome
        val revision = controller.controllerRevision
        val resize = controller.resizeMode
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    setKeepContentOnPlayerReset(true)
                    resizeMode = resize
                    player = controller.player
                }
            },
            update = { view ->
                if (view.player !== controller.player) {
                    view.player = controller.player
                }
                if (view.resizeMode != resize) {
                    view.resizeMode = resize
                }
                @Suppress("UNUSED_EXPRESSION")
                revision
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Buffering spinner
        if (controller.isBuffering) {
            CircularProgressIndicator(
                color = palette.accent,
                strokeWidth = 3.5.dp,
                modifier = Modifier
                    .size(46.dp)
                    .align(Alignment.Center),
            )
        }

        // Transient seek badge (-10s / +10s)
        AnimatedVisibility(
            visible = transientFeedback != null,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(PillShape)
                    .background(Color(0xCC0D0E12))
                    .border(1.dp, palette.hairline, PillShape)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    text = transientFeedback.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                )
            }
        }

        // Stream stats monospace badge
        if (controller.showStreamStats && controller.streamStatsText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = if (isTv) 32.dp else 16.dp,
                        top = if (controlsVisible) (if (isTv) 86.dp else 72.dp) else 20.dp,
                    )
                    .clip(ControlShape)
                    .background(Color(0xD80D0E12))
                    .border(1.dp, palette.hairline, ControlShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = controller.streamStatsText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color.White,
                )
            }
        }

        // Cinema HUD Overlay
        AnimatedVisibility(
            visible = controlsVisible && !inPictureInPicture,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top gradient scrim + header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xCC07080B), Color(0x5507080B), Color.Transparent),
                            )
                        )
                        .padding(
                            start = if (isTv) 36.dp else 16.dp,
                            end = if (isTv) 36.dp else 16.dp,
                            top = if (isTv) 24.dp else 16.dp,
                            bottom = 32.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!isTv) {
                        BoostyIconButton(
                            iconRes = R.drawable.ic_back,
                            contentDescription = stringResource(R.string.nav_back),
                            onClick = onBack,
                            containerColor = Color(0x88151720),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (controller.videoTitle.isNotEmpty()) {
                            Text(
                                text = controller.videoTitle,
                                style = if (isTv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (controller.videoAuthor.isNotEmpty()) {
                            Text(
                                text = controller.videoAuthor,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (kotlin.math.abs(controller.playbackSpeed - 1f) > 0.01f) {
                        Box(
                            modifier = Modifier
                                .clip(PillShape)
                                .background(palette.accentSoft)
                                .border(1.dp, palette.accent.copy(alpha = 0.55f), PillShape)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = "${controller.playbackSpeed}×",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.accent,
                            )
                        }
                    }

                    BoostyIconButton(
                        iconRes = R.drawable.ic_settings,
                        contentDescription = stringResource(R.string.player_settings),
                        onClick = {
                            bumpControls()
                            menuPage = PlayerMenuPage.MAIN
                        },
                        containerColor = Color(0x88151720),
                    )
                }

                // Center Transport Controls
                if (!controller.isBuffering) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!controller.isLive) PlayerTransportButton(
                            iconRes = R.drawable.ic_replay_10,
                            contentDescription = stringResource(R.string.player_seek_back_10),
                            size = if (isTv) 54.dp else 48.dp,
                            iconSize = 26.dp,
                            primary = false,
                            onClick = {
                                controller.seekBy(-10_000L)
                                showFeedback(resources.getString(R.string.player_seek_back_short))
                                bumpControls()
                            },
                        )
                        PlayerTransportButton(
                            iconRes = if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                            contentDescription = stringResource(R.string.player_play_pause),
                            size = if (isTv) 68.dp else 62.dp,
                            iconSize = 32.dp,
                            primary = true,
                            focusRequester = playButtonFocus,
                            onClick = {
                                controller.togglePlayPause()
                                bumpControls()
                            },
                        )
                        if (!controller.isLive) PlayerTransportButton(
                            iconRes = R.drawable.ic_forward_10,
                            contentDescription = stringResource(R.string.player_seek_forward_10),
                            size = if (isTv) 54.dp else 48.dp,
                            iconSize = 26.dp,
                            primary = false,
                            onClick = {
                                stepSeek(10)
                                bumpControls()
                            },
                        )
                        if (onPlayNext != null) {
                            PlayerTransportButton(
                                iconRes = R.drawable.ic_skip_next,
                                contentDescription = stringResource(R.string.player_next_video),
                                size = if (isTv) 50.dp else 44.dp,
                                iconSize = 24.dp,
                                primary = false,
                                onClick = {
                                    bumpControls()
                                    onPlayNext()
                                },
                            )
                        }
                    }
                }

                // Bottom gradient scrim + SeekBar + timecodes
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0x7007080B), Color(0xD807080B)),
                            )
                        )
                        .padding(
                            start = if (isTv) 36.dp else 16.dp,
                            end = if (isTv) 36.dp else 16.dp,
                            top = 32.dp,
                            bottom = if (isTv) 28.dp else 20.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (controller.isLive) {
                        Text(
                            text = stringResource(R.string.live_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier
                                .clip(PillShape)
                                .background(Color(0xFFAE252B))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    } else {
                        PlayerSeekBar(
                            positionMs = controller.currentPositionMs,
                            bufferedMs = controller.bufferedPositionMs,
                            durationMs = controller.durationMs,
                            onSeek = { target ->
                                controller.seekTo(target)
                                bumpControls()
                            },
                            onStep = { delta ->
                                controller.seekBy(delta)
                                bumpControls()
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = TimeFormat.format(context, controller.currentPositionMs),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                            )
                            Text(
                                text = TimeFormat.format(context, controller.durationMs),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.72f),
                            )
                        }
                    }
                }
            }
        }
    }

    // Modern right-side Player Settings Sheet (replaces all legacy AlertDialogs)
    menuPage?.let { page ->
        PlayerSettingsOverlay(
            page = page,
            controller = controller,
            options = options,
            onNavigate = { menuPage = it },
            onOpenSeekTime = {
                menuPage = null
                seekError = null
                showSeekTimeDialog = true
            },
            onDismiss = {
                menuPage = null
                bumpControls()
            },
        )
    }

    if (showSeekTimeDialog) {
        BoostyTextInputDialog(
            title = stringResource(R.string.player_seek_time),
            initialValue = TimeFormat.format(context, controller.currentPositionMs),
            placeholder = stringResource(R.string.player_seek_hint),
            confirmLabel = stringResource(R.string.player_seek_go),
            errorText = seekError,
            keyboardType = KeyboardType.Text,
            onConfirm = { raw ->
                val parsed = TimeFormat.parse(raw)
                val max = controller.durationMs
                if (parsed < 0L || (max > 0L && parsed > max)) {
                    seekError = resources.getString(R.string.player_seek_error)
                } else {
                    seekError = null
                    showSeekTimeDialog = false
                    controller.seekTo(parsed)
                    bumpControls()
                }
            },
            onDismiss = {
                showSeekTimeDialog = false
                bumpControls()
            },
        )
    }

    if (controller.playbackError) {
        BoostyConfirmDialog(
            title = stringResource(R.string.player_settings),
            message = stringResource(if (controller.isLive) R.string.live_playback_failed else R.string.player_error_playback),
            confirmLabel = stringResource(if (onRetryLive != null) R.string.retry else R.string.player_error_go_to_post),
            dismissLabel = if (onRetryLive != null) stringResource(R.string.nav_back) else null,
            onConfirm = onRetryLive ?: onBack,
            onDismiss = onBack,
        )
    }
}

@Composable
private fun PlayerTransportButton(
    iconRes: Int,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    primary: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && isTv) 1.12f else 1f,
        animationSpec = tween(130, easing = FastOutSlowInEasing),
        label = "transportScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            primary && focused -> Color(0xFFFF936B)
            primary -> palette.accent
            focused -> Color(0xCC272B3B)
            else -> Color(0x9912141B)
        },
        animationSpec = tween(130),
        label = "transportBg",
    )
    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color(0x33FFFFFF),
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (primary) palette.accentOnColor else Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun PlayerSeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onStep: (Long) -> Unit,
) {
    val palette = BoostyTheme.palette
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    var barWidthPx by remember { mutableIntStateOf(1) }

    val playedFraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val trackHeight by animateDpAsState(
        targetValue = if (focused) 8.dp else 5.dp,
        animationSpec = tween(130),
        label = "seekTrackH",
    )
    val thumbSize by animateDpAsState(
        targetValue = if (focused) 18.dp else 13.dp,
        animationSpec = tween(130),
        label = "seekThumb",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onStep(-10_000L)
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onStep(10_000L)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0L) {
                        val frac = (offset.x / barWidthPx).coerceIn(0f, 1f)
                        onSeek((durationMs * frac).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { change, _ ->
                    if (durationMs > 0L) {
                        val frac = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                        onSeek((durationMs * frac).toLong())
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(PillShape)
                .background(Color(0x40FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedFraction)
                    .fillMaxHeight()
                    .background(Color(0x66FFFFFF)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedFraction)
                    .fillMaxHeight()
                    .background(palette.accent),
            )
        }

        val thumbOffsetDp = with(density) {
            ((barWidthPx * playedFraction) - (thumbSize.toPx() / 2f))
                .coerceIn(0f, (barWidthPx - thumbSize.toPx()).coerceAtLeast(0f))
                .toDp()
        }
        Box(
            modifier = Modifier
                .offset(x = thumbOffsetDp)
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (focused) Color.White else palette.accent)
                .border(if (focused) 2.dp else 0.dp, palette.accent, CircleShape),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSettingsOverlay(
    page: PlayerMenuPage,
    controller: PlayerSessionController,
    options: PlaybackOptions,
    onNavigate: (PlayerMenuPage) -> Unit,
    onOpenSeekTime: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val qualityLabels = remember(context) { PlaybackOptions.qualityLabels(context).toList() }
    val speeds = remember { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f) }
    val speedLabels = remember(context) {
        listOf(
            resources.getString(R.string.player_speed_05),
            resources.getString(R.string.player_speed_075),
            resources.getString(R.string.player_speed_normal),
            resources.getString(R.string.player_speed_125),
            resources.getString(R.string.player_speed_150),
            resources.getString(R.string.player_speed_175),
            resources.getString(R.string.player_speed_200),
        )
    }
    val frameModes = remember(context) {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to resources.getString(R.string.player_frame_fit),
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to resources.getString(R.string.player_frame_zoom),
            AspectRatioFrameLayout.RESIZE_MODE_FILL to resources.getString(R.string.player_frame_fill),
        )
    }

    val title = when (page) {
        PlayerMenuPage.MAIN -> stringResource(R.string.player_settings)
        PlayerMenuPage.QUALITY -> stringResource(R.string.settings_quality)
        PlayerMenuPage.SPEED -> stringResource(R.string.player_speed)
        PlayerMenuPage.AUDIO -> stringResource(R.string.player_audio_track)
        PlayerMenuPage.SUBTITLES -> stringResource(R.string.player_subtitles)
        PlayerMenuPage.FRAME_SIZE -> stringResource(R.string.player_frame_size)
    }

    val firstFocus = remember(page) { FocusRequester() }
    LaunchedEffect(page) { runCatching { firstFocus.requestFocus() } }

    BoostyOverlaySheet(
        title = title,
        onDismiss = { if (page == PlayerMenuPage.MAIN) onDismiss() else onNavigate(PlayerMenuPage.MAIN) },
        footer = if (page != PlayerMenuPage.MAIN) {
            {
                BoostyButton(
                    text = stringResource(R.string.nav_back),
                    primary = false,
                    onClick = { onNavigate(PlayerMenuPage.MAIN) },
                )
            }
        } else null,
    ) {
        when (page) {
            PlayerMenuPage.MAIN -> {
                BoostySheetOption(
                    label = stringResource(R.string.settings_quality),
                    trailingText = qualityLabels.getOrElse(options.qualityIndex()) { qualityLabels.first() },
                    iconRes = R.drawable.ic_tune,
                    focusRequester = firstFocus,
                    onClick = { onNavigate(PlayerMenuPage.QUALITY) },
                )
                if (!controller.isLive) BoostySheetOption(
                    label = stringResource(R.string.player_speed),
                    trailingText = "${controller.playbackSpeed}×",
                    iconRes = R.drawable.ic_speed,
                    onClick = { onNavigate(PlayerMenuPage.SPEED) },
                )
                BoostySheetOption(
                    label = stringResource(R.string.player_audio_track),
                    iconRes = R.drawable.ic_audiotrack,
                    onClick = {
                        if (controller.tracksFor(C.TRACK_TYPE_AUDIO).size <= 1) {
                            Toast.makeText(context, resources.getString(R.string.player_no_tracks), Toast.LENGTH_SHORT).show()
                        } else {
                            onNavigate(PlayerMenuPage.AUDIO)
                        }
                    },
                )
                BoostySheetOption(
                    label = stringResource(R.string.player_subtitles),
                    iconRes = R.drawable.ic_subtitles,
                    onClick = {
                        if (controller.tracksFor(C.TRACK_TYPE_TEXT).size <= 1) {
                            Toast.makeText(context, resources.getString(R.string.player_no_tracks), Toast.LENGTH_SHORT).show()
                        } else {
                            onNavigate(PlayerMenuPage.SUBTITLES)
                        }
                    },
                )
                BoostySheetOption(
                    label = stringResource(R.string.player_frame_size),
                    trailingText = frameModes.firstOrNull { it.first == controller.resizeMode }?.second,
                    iconRes = R.drawable.ic_aspect_ratio,
                    onClick = { onNavigate(PlayerMenuPage.FRAME_SIZE) },
                )
                if (!controller.isLive) BoostySheetOption(
                    label = stringResource(R.string.player_seek_time),
                    iconRes = R.drawable.ic_history,
                    onClick = onOpenSeekTime,
                )
                if (!controller.isLive) BoostySheetOption(
                    label = stringResource(R.string.player_from_start),
                    iconRes = R.drawable.ic_replay_10,
                    onClick = {
                        controller.seekTo(0L)
                        onDismiss()
                    },
                )
                BoostySheetOption(
                    label = stringResource(
                        if (controller.showStreamStats) R.string.player_hide_stats else R.string.player_stream_stats
                    ),
                    iconRes = R.drawable.ic_stats,
                    selected = controller.showStreamStats,
                    onClick = {
                        controller.toggleStreamStats()
                        onDismiss()
                    },
                )
            }

            PlayerMenuPage.QUALITY -> {
                val selectedIdx = options.qualityIndex()
                qualityLabels.forEachIndexed { idx, label ->
                    BoostySheetOption(
                        label = label,
                        selected = idx == selectedIdx,
                        focusRequester = if (idx == selectedIdx) firstFocus else null,
                        onClick = {
                            controller.setQualityIndex(idx)
                            onDismiss()
                        },
                    )
                }
            }

            PlayerMenuPage.SPEED -> {
                speeds.forEachIndexed { idx, value ->
                    val selected = kotlin.math.abs(value - controller.playbackSpeed) < 0.001f
                    BoostySheetOption(
                        label = speedLabels[idx],
                        selected = selected,
                        focusRequester = if (selected) firstFocus else null,
                        onClick = {
                            controller.setSpeed(value)
                            onDismiss()
                        },
                    )
                }
            }

            PlayerMenuPage.AUDIO -> {
                val tracks = remember(controller.tracksRevision) { controller.tracksFor(C.TRACK_TYPE_AUDIO) }
                tracks.forEachIndexed { idx, track ->
                    BoostySheetOption(
                        label = track.label,
                        selected = track.selected,
                        focusRequester = if (track.selected || idx == 0) firstFocus else null,
                        onClick = {
                            controller.selectTrack(C.TRACK_TYPE_AUDIO, track)
                            onDismiss()
                        },
                    )
                }
            }

            PlayerMenuPage.SUBTITLES -> {
                val tracks = remember(controller.tracksRevision) { controller.tracksFor(C.TRACK_TYPE_TEXT) }
                tracks.forEachIndexed { idx, track ->
                    BoostySheetOption(
                        label = track.label,
                        selected = track.selected,
                        focusRequester = if (track.selected || idx == 0) firstFocus else null,
                        onClick = {
                            controller.selectTrack(C.TRACK_TYPE_TEXT, track)
                            onDismiss()
                        },
                    )
                }
            }

            PlayerMenuPage.FRAME_SIZE -> {
                frameModes.forEachIndexed { idx, (mode, label) ->
                    val selected = mode == controller.resizeMode
                    BoostySheetOption(
                        label = label,
                        selected = selected,
                        focusRequester = if (selected || idx == 0) firstFocus else null,
                        onClick = {
                            controller.setFrameResizeMode(mode)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}
