package ru.boostyatv.client;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.common.C;
import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.HashMap;
import java.util.Map;

/**
 * Hosts the ExoPlayer and its media session so the system can show the
 * playback notification and route headset or remote media buttons even while
 * the activity is recreated or in the background.
 */
public final class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession session;

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override public void onCreate() {
        super.onCreate();
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", BoostyApi.ORIGIN);
        headers.put("Referer", BoostyApi.ORIGIN + "/");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build();
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        Intent launch = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.isPlaying()) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override public void onDestroy() {
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
