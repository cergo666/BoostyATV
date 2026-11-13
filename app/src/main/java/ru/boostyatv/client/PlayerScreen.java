package ru.boostyatv.client;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;

/**
 * The built-in player: MediaController against {@link PlaybackService},
 * track/quality menus, stream statistics and hand-off to external players.
 */
final class PlayerScreen {
    interface Host {
        Ui ui();
        boolean isTv();
        WatchHistory history();
        PlaybackOptions options();
        void replace(View view, boolean push);
        void back();
        void syncWatchProgress();
        void refreshLabels();
    }

    private final ComponentActivity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> externalPlayer;
    private final ActivityResultLauncher<String> notificationPermission;

    private MediaController player;
    private ListenableFuture<MediaController> pendingController;
    private PlayerView playerView;
    private TextView streamStats;
    private float playbackSpeed = 1f;
    private Post.Block video;
    private Post.Block externalVideo;
    private PlaybackOptions.App externalApp;
    private List<Post.Source> playbackSources;
    private int sourceIndex;
    private long playbackPosition;
    private boolean resumePlayback = true;
    private boolean playbackStarted;
    private long lastWrittenTime = -1;
    private int revealedByKey = KeyEvent.KEYCODE_UNKNOWN;
    private AlertDialog errorDialog;
    private Format videoFormat;
    private Format audioFormat;
    private boolean reconnectAttempted;

    private final Runnable savePlaybackTick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (player.isPlaying()) savePlayback();
            handler.postDelayed(this, 15_000);
        }
    };

    private final Runnable refreshStreamStats = new Runnable() {
        @Override public void run() {
            if (player == null || streamStats == null || streamStats.getVisibility() != View.VISIBLE) return;
            streamStats.setText(streamStatsText());
            handler.postDelayed(this, 1000);
        }
    };

    PlayerScreen(ComponentActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        externalPlayer = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Post.Block item = externalVideo;
                PlaybackOptions.App app = externalApp;
                externalVideo = null;
                externalApp = null;
                if (item == null || app == null) return;
                WatchHistory.Progress progress = app.result(result.getResultCode(), result.getData(),
                    item.durationMs());
                if (progress != null) {
                    recordProgress(item, progress.position(), progress.duration());
                    host.refreshLabels();
                }
            });
        notificationPermission = activity.registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {});
    }

    void restoreState(Bundle savedState) {
        if (savedState != null && savedState.containsKey("external_player")) {
            externalApp = PlaybackOptions.App.valueOf(savedState.getString("external_player"));
            externalVideo = new Post.Block("video", "", "", java.util.Collections.emptyList(),
                savedState.getString("external_video", ""), 0, savedState.getLong("external_duration"));
        }
    }

    void saveState(Bundle out) {
        if (externalVideo != null && externalApp != null) {
            out.putString("external_player", externalApp.name());
            out.putString("external_video", externalVideo.videoId());
            out.putLong("external_duration", externalVideo.durationMs());
        }
    }

    boolean hasVideo() {
        return video != null;
    }

    void start() {
        if (video == null) return;
        if (player == null) startPlayer();
        else {
            if (playerView != null && playerView.getPlayer() != player) playerView.setPlayer(player);
            scheduleTicks();
        }
    }

    void stop() {
        savePlayback();
        handler.removeCallbacks(savePlaybackTick);
        handler.removeCallbacks(refreshStreamStats);
    }

    void destroy() {
        handler.removeCallbacks(savePlaybackTick);
        handler.removeCallbacks(refreshStreamStats);
        releaseController(!activity.isChangingConfigurations());
    }

    private void scheduleTicks() {
        handler.removeCallbacks(savePlaybackTick);
        handler.removeCallbacks(refreshStreamStats);
        if (video == null || player == null) return;
        handler.postDelayed(savePlaybackTick, 15_000);
        if (streamStats != null && streamStats.getVisibility() == View.VISIBLE) {
            handler.post(refreshStreamStats);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return;
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    void open(Post.Block block) {
        PlaybackOptions.App app = host.options().player();
        if (app != PlaybackOptions.App.BUILT_IN && launchExternal(block, app)) return;
        video = block;
        reconnectAttempted = false;
        requestNotificationPermission();
        playbackSpeed = 1f;
        videoFormat = null;
        audioFormat = null;
        playbackSources = host.options().sources(block);
        sourceIndex = 0;
        playbackPosition = host.history().get(block).resume();
        lastWrittenTime = -1;
        playbackStarted = false;
        resumePlayback = true;
        Ui ui = host.ui();
        boolean tv = host.isTv();
        FrameLayout screen = new FrameLayout(activity) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return handlePlayerKey(event) || super.dispatchKeyEvent(event);
            }
        };
        playerView = new PlayerView(activity);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setControllerShowTimeoutMs(tv ? 8000 : 5000);
        playerView.setControllerAnimationEnabled(!tv);
        playerView.setFocusable(true);
        playerView.setFocusableInTouchMode(true);
        screen.addView(playerView, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        View settings = playerView.findViewById(androidx.media3.ui.R.id.exo_settings);
        settings.setContentDescription(activity.getString(R.string.player_settings));
        settings.setBackground(ui.control(0x44111111, 0xff454545, 24));
        settings.setOnClickListener(view -> showVideoMenu());
        View play = playerView.findViewById(androidx.media3.ui.R.id.exo_play_pause);
        play.setBackground(ui.control(0x80111111, 0xff454545, 32));

        streamStats = ui.label("", tv ? 15 : 12, Color.WHITE, false);
        streamStats.setTypeface(android.graphics.Typeface.MONOSPACE);
        streamStats.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        streamStats.setBackground(ui.shape(0xdb141414, 10, 0));
        streamStats.setVisibility(View.GONE);
        FrameLayout.LayoutParams statsPosition = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP,
            Gravity.TOP | Gravity.START);
        statsPosition.setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0);
        screen.addView(streamStats, statsPosition);
        PlayerView controls = playerView;
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (tv && visibility != View.VISIBLE && controls.isAttachedToWindow()) controls.requestFocus();
        });
        host.replace(screen, true);
        fullscreen(true);
        startPlayer();
        playerView.showController();
        if (tv) playerView.requestFocus();
    }

    void close() {
        releaseController(true);
        video = null;
        playerView = null;
        streamStats = null;
        playbackSources = null;
        revealedByKey = KeyEvent.KEYCODE_UNKNOWN;
        fullscreen(false);
    }

    private boolean launchExternal(Post.Block block, PlaybackOptions.App app) {
        Post.Source source = host.options().sources(block).get(0);
        long position = host.history().get(block).resume();
        Intent intent = app.intent(source, block.text(), position);
        if (app == PlaybackOptions.App.VIMU && intent.resolveActivity(activity.getPackageManager()) == null) {
            intent.setPackage(PlaybackOptions.VIMU_RUSTORE);
        }
        try {
            if (app == PlaybackOptions.App.JUST_PLUS) {
                JustPlusPlayback.configure(activity, host.history(), intent, block, position);
                activity.startActivity(intent);
            } else if (app == PlaybackOptions.App.OTHER) {
                if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                    throw new ActivityNotFoundException();
                }
                activity.startActivity(Intent.createChooser(intent,
                    activity.getString(R.string.player_external_chooser)));
            } else {
                externalVideo = block;
                externalApp = app;
                externalPlayer.launch(intent);
            }
            return true;
        } catch (ActivityNotFoundException e) {
            externalVideo = null;
            externalApp = null;
            Toast.makeText(activity, activity.getString(R.string.player_external_not_found),
                Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void startPlayer() {
        if (video == null || player != null || pendingController != null) return;
        SessionToken token = new SessionToken(activity,
            new android.content.ComponentName(activity, PlaybackService.class));
        pendingController = new MediaController.Builder(activity, token)
            .setListener(new MediaController.Listener() {
                @Override public void onDisconnected(MediaController controller) {
                    activity.runOnUiThread(() -> handleDisconnected(controller));
                }
            })
            .buildAsync();
        Futures.addCallback(pendingController, new FutureCallback<>() {
            @Override public void onSuccess(MediaController controller) {
                activity.runOnUiThread(() -> attachController(controller));
            }

            @Override public void onFailure(Throwable failure) {
                activity.runOnUiThread(() -> {
                    pendingController = null;
                    if (video == null || player != null || activity.isFinishing()
                        || activity.isDestroyed()) return;
                    showPlaybackError();
                });
            }
        }, command -> handler.post(command));
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void attachController(MediaController controller) {
        pendingController = null;
        if (activity.isDestroyed() || activity.isFinishing()) {
            controller.release();
            activity.stopService(new Intent(activity, PlaybackService.class));
            return;
        }
        if (video == null || player != null) {
            controller.release();
            return;
        }
        player = controller;
        reconnectAttempted = false;
        playerView.setPlayer(controller);
        controller.setPlaybackSpeed(playbackSpeed);
        applyQualityLimit();
        controller.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) playbackStarted = true;
                else if (state == Player.STATE_ENDED) savePlayback();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing && playerView.isControllerFullyVisible()) playerView.showController();
                else if (!playing && player != null && player.getPlaybackState() != Player.STATE_BUFFERING) {
                    savePlayback();
                }
            }

            @Override public void onTracksChanged(Tracks tracks) {
                videoFormat = selectedFormat(tracks, C.TRACK_TYPE_VIDEO);
                audioFormat = selectedFormat(tracks, C.TRACK_TYPE_AUDIO);
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (video == null || player == null) return;
                if (playbackStarted) playbackPosition = player.getCurrentPosition();
                resumePlayback = player.getPlayWhenReady();
                if (++sourceIndex < playbackSources.size()) {
                    prepareSource();
                } else {
                    sourceIndex = playbackSources.size() - 1;
                    showPlaybackError();
                }
            }
        });
        prepareSource();
        scheduleTicks();
    }

    private void handleDisconnected(MediaController controller) {
        if (player != controller) return;
        player = null;
        handler.removeCallbacks(savePlaybackTick);
        handler.removeCallbacks(refreshStreamStats);
        if (playerView != null) playerView.setPlayer(null);
        if (video == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (reconnectAttempted) {
            showPlaybackError();
            return;
        }
        reconnectAttempted = true;
        startPlayer();
    }

    private void showPlaybackError() {
        if (errorDialog != null) errorDialog.dismiss();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        errorDialog = new AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.player_error_playback))
            .setPositiveButton(activity.getString(R.string.player_error_go_to_post),
                (dialog, which) -> host.back())
            .setOnCancelListener(dialog -> host.back()).show();
    }

    private void applyQualityLimit() {
        int height = PlaybackOptions.HEIGHTS[host.options().qualityIndex()];
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setMaxVideoSize(Integer.MAX_VALUE, height == 0 ? Integer.MAX_VALUE : height).build());
    }

    private void prepareSource() {
        Post.Source source = playbackSources.get(sourceIndex);
        MediaItem.Builder item = new MediaItem.Builder().setUri(source.url());
        if (source.type().contains("hls")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (source.type().contains("dash")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        player.setMediaItem(item.build(), playbackPosition);
        player.setPlayWhenReady(resumePlayback);
        player.prepare();
    }

    private void releaseController(boolean stopService) {
        handler.removeCallbacks(refreshStreamStats);
        if (errorDialog != null) {
            errorDialog.dismiss();
            errorDialog = null;
        }
        if (pendingController != null) {
            pendingController.cancel(true);
            pendingController = null;
        }
        handler.removeCallbacks(savePlaybackTick);
        if (player == null) {
            if (stopService) activity.stopService(new Intent(activity, PlaybackService.class));
            return;
        }
        savePlayback();
        if (playbackStarted) playbackPosition = player.getCurrentPosition();
        resumePlayback = player.getPlayWhenReady();
        if (playerView != null) playerView.setPlayer(null);
        player.release();
        player = null;
        if (stopService) activity.stopService(new Intent(activity, PlaybackService.class));
    }

    private void savePlayback() {
        if (player == null || video == null || !playbackStarted) return;
        long duration = player.getDuration() > 0 ? player.getDuration() : video.durationMs();
        long position = player.getPlaybackState() == Player.STATE_ENDED && duration > 0
            ? duration : player.getCurrentPosition();
        long seconds = position / 1000;
        if (seconds == lastWrittenTime || (seconds == 0 && lastWrittenTime < 0)) return;
        lastWrittenTime = seconds;
        recordProgress(video, position, duration);
    }

    private void recordProgress(Post.Block item, long position, long duration) {
        if (item.videoId().isEmpty()) return;
        host.history().save(item, position, duration);
        host.syncWatchProgress();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void showVideoMenu() {
        if (player == null) return;
        playerView.showController();
        String[] items = {
            activity.getString(R.string.player_menu_quality,
                PlaybackOptions.qualityLabels(activity)[host.options().qualityIndex()]),
            activity.getString(R.string.player_menu_speed, String.valueOf(player.getPlaybackParameters().speed)),
            activity.getString(R.string.player_audio_track), activity.getString(R.string.player_subtitles),
            activity.getString(R.string.player_frame_size), activity.getString(R.string.player_seek_time),
            activity.getString(R.string.player_from_start),
            streamStats.getVisibility() == View.VISIBLE
                ? activity.getString(R.string.player_hide_stats)
                : activity.getString(R.string.player_stream_stats)
        };
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.player_settings))
            .setItems(items, (dialog, item) -> {
                if (player == null) return;
                switch (item) {
                    case 0 -> chooseQuality();
                    case 1 -> chooseSpeed();
                    case 2 -> showTracks(C.TRACK_TYPE_AUDIO, activity.getString(R.string.player_audio_track));
                    case 3 -> showTracks(C.TRACK_TYPE_TEXT, activity.getString(R.string.player_subtitles));
                    case 4 -> choosePictureSize();
                    case 5 -> showSeekDialog();
                    case 6 -> seekPlayback(0);
                    case 7 -> toggleStreamStats();
                }
            }).show();
    }

    private void chooseQuality() {
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.settings_quality))
            .setSingleChoiceItems(PlaybackOptions.qualityLabels(activity), host.options().qualityIndex(),
                (dialog, index) -> {
                    host.options().setQuality(index);
                    dialog.dismiss();
                    if (player != null) {
                        playbackPosition = player.getCurrentPosition();
                        resumePlayback = player.getPlayWhenReady();
                        playbackSources = host.options().sources(video);
                        sourceIndex = 0;
                        prepareSource();
                    }
                }).setNegativeButton(activity.getString(R.string.cancel), null).show();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void showTracks(int type, String title) {
        if (!player.getCurrentTracks().isTypeSupported(type)) {
            Toast.makeText(activity, activity.getString(R.string.player_no_tracks), Toast.LENGTH_SHORT).show();
            return;
        }
        new TrackSelectionDialogBuilder(activity, title, player, type)
            .setShowDisableOption(type == C.TRACK_TYPE_TEXT).build().show();
    }

    private void chooseSpeed() {
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = {activity.getString(R.string.player_speed_05),
            activity.getString(R.string.player_speed_075), activity.getString(R.string.player_speed_normal),
            activity.getString(R.string.player_speed_125), activity.getString(R.string.player_speed_150),
            activity.getString(R.string.player_speed_175), activity.getString(R.string.player_speed_200)};
        int selected = -1;
        float current = player.getPlaybackParameters().speed;
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(speeds[i] - current) < 0.001f) selected = i;
        }
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.player_speed))
            .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                if (player != null) {
                    playbackSpeed = speeds[index];
                    player.setPlaybackSpeed(playbackSpeed);
                }
                dialog.dismiss();
            }).setNegativeButton(activity.getString(R.string.cancel), null).show();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void choosePictureSize() {
        int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL};
        int selected = playerView.getResizeMode() == modes[1] ? 1
            : playerView.getResizeMode() == modes[2] ? 2 : 0;
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.player_frame_size))
            .setSingleChoiceItems(new String[]{activity.getString(R.string.player_frame_fit),
                activity.getString(R.string.player_frame_zoom), activity.getString(R.string.player_frame_fill)},
                selected, (dialog, index) -> {
                    if (playerView != null) playerView.setResizeMode(modes[index]);
                    dialog.dismiss();
                }).setNegativeButton(activity.getString(R.string.cancel), null).show();
    }

    private void toggleStreamStats() {
        handler.removeCallbacks(refreshStreamStats);
        boolean enable = streamStats.getVisibility() != View.VISIBLE;
        streamStats.setVisibility(enable ? View.VISIBLE : View.GONE);
        if (enable) refreshStreamStats.run();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private String streamStatsText() {
        Format videoInfo0 = videoFormat;
        Format audioInfo0 = audioFormat;
        String none = activity.getString(R.string.player_stats_none);
        String videoInfo = videoInfo0 == null ? none : String.join(" · ",
            videoInfo0.width > 0 && videoInfo0.height > 0
                ? videoInfo0.width + "×" + videoInfo0.height : none,
            videoInfo0.frameRate > 0 ? String.format(Locale.ROOT, "%.1f fps", videoInfo0.frameRate) : none,
            codec(videoInfo0), bitrate(videoInfo0.bitrate));
        String audioInfo = audioInfo0 == null ? none : String.join(" · ", codec(audioInfo0),
            audioInfo0.channelCount > 0
                ? activity.getString(R.string.player_stats_channels, audioInfo0.channelCount) : none,
            audioInfo0.sampleRate > 0
                ? activity.getString(R.string.player_stats_khz, audioInfo0.sampleRate / 1000f) : none,
            bitrate(audioInfo0.bitrate));
        long buffered = Math.max(0, player.getBufferedPosition() - player.getCurrentPosition());
        return activity.getString(R.string.player_stats_video) + "  " + videoInfo
            + "\n" + activity.getString(R.string.player_stats_audio) + "  " + audioInfo
            + "\n" + activity.getString(R.string.player_stats_buffer) + "  "
            + activity.getString(R.string.player_stats_seconds, buffered / 1000f);
    }

    private static Format selectedFormat(Tracks tracks, int type) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) continue;
            for (int index = 0; index < group.length; index++) {
                if (group.isTrackSelected(index)) return group.getTrackFormat(index);
            }
        }
        return null;
    }

    private String codec(Format format) {
        if (format.codecs != null) return format.codecs;
        if (format.sampleMimeType != null) return format.sampleMimeType.replaceFirst("^(video|audio)/", "");
        return activity.getString(R.string.player_stats_none);
    }

    private String bitrate(int bitsPerSecond) {
        return bitsPerSecond > 0
            ? activity.getString(R.string.player_stats_kbps, Math.round(bitsPerSecond / 1000f))
            : activity.getString(R.string.player_stats_none);
    }

    private void showSeekDialog() {
        if (player == null) return;
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(activity.getString(R.string.player_seek_hint));
        input.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setText(TimeFormat.format(activity, player.getCurrentPosition()));
        input.selectAll();
        Ui ui = host.ui();
        LinearLayout field = ui.column();
        field.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        field.addView(input, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.player_seek_time)).setView(field)
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .setPositiveButton(activity.getString(R.string.player_seek_go), null).create();
        dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(button -> {
                if (player == null) {
                    dialog.dismiss();
                    return;
                }
                long time = TimeFormat.parse(input.getText().toString());
                if (time < 0 || (player.getDuration() > 0 && time > player.getDuration())) {
                    input.setError(activity.getString(R.string.player_seek_error));
                    return;
                }
                seekPlayback(time);
                dialog.dismiss();
            }));
        dialog.show();
    }

    private void seekPlayback(long time) {
        player.seekTo(time);
        if (time == 0 && playbackStarted && host.history().get(video).position() > 0) {
            lastWrittenTime = 0;
            recordProgress(video, 0, player.getDuration() > 0 ? player.getDuration() : video.durationMs());
        }
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private boolean handlePlayerKey(KeyEvent event) {
        if (player == null || playerView == null) return false;
        int code = event.getKeyCode();
        if (code == revealedByKey && event.getAction() == KeyEvent.ACTION_UP) {
            revealedByKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showVideoMenu();
            return true;
        }
        if (playerView.dispatchMediaKeyEvent(event)) {
            playerView.showController();
            return true;
        }
        boolean direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (direction && event.getAction() == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible()) {
            revealedByKey = code;
            playerView.showController();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack();
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) player.seekForward();
            return true;
        }
        return false;
    }

    private void fullscreen(boolean active) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller == null) return;
            if (active) {
                controller.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else controller.show(WindowInsets.Type.systemBars());
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(active
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        }
        if (active) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
