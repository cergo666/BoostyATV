package ru.boostyatv.client;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.UUID;

public final class JustPlusPlayback extends BroadcastReceiver {
    private static final String VIDEO = "boostyatv_video";
    private static final String DURATION = "boostyatv_duration";

    static void configure(Context context, WatchHistory history, Intent intent, Post.Block video, long position) {
        String nonce = UUID.randomUUID().toString();
        Intent report = new Intent(context, JustPlusPlayback.class)
            .setData(Uri.parse("boostyatv://player-result/" + nonce))
            .putExtra(VIDEO, video.videoId()).putExtra(DURATION, video.durationMs());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent callback = PendingIntent.getBroadcast(context, 0, report, flags);
        history.registerExternal(video.videoId(), nonce);

        String url = intent.getDataString();
        Bundle item = new Bundle();
        item.putString("uri", url);
        item.putString("title", video.text());
        item.putLong("position_ms", position);
        if (!video.image().isEmpty()) item.putString("thumbnail", video.image());
        ArrayList<Bundle> qualities = new ArrayList<>();
        PlaybackOptions.qualityVariants(video.sources()).forEach((name, source) -> {
            Bundle quality = new Bundle();
            quality.putString("label", PlaybackOptions.AUTO.equals(name)
                ? context.getString(R.string.player_auto) : name);
            quality.putString("uri", source);
            quality.putBoolean("selected", source.equals(url));
            qualities.add(quality);
        });
        if (qualities.size() > 1) item.putParcelableArrayList("qualities", qualities);
        Bundle playlist = new Bundle();
        playlist.putParcelableArray("items", new Bundle[]{item});
        playlist.putStringArray("headers", new String[]{"Origin", BoostyApi.ORIGIN, "Referer", BoostyApi.ORIGIN + "/"});
        playlist.putBoolean("ask_resume", false);
        playlist.putParcelable("result_callback", callback);
        playlist.putInt("report_interval_sec", 30);
        intent.putExtra("playlist", playlist);
    }

    @Override public void onReceive(Context context, Intent report) {
        Uri data = report.getData();
        String videoId = report.getStringExtra(VIDEO);
        if (data == null || videoId == null || videoId.isEmpty()) return;
        WatchHistory.Progress progress = progress(report, report.getLongExtra(DURATION, 0));
        if (progress != null) {
            new WatchHistory(context).saveExternal(videoId, data.getLastPathSegment(), progress);
        }
    }

    static WatchHistory.Progress progress(Intent result, long fallbackDuration) {
        String reason = result.getStringExtra("end_by");
        if (!"user".equals(reason) && !"completion".equals(reason) && !"interval".equals(reason)) return null;
        Bundle extras = result.getExtras();
        if (extras == null || extras.getInt("index", -1) != 0) return null;
        long duration = milliseconds(extras, "duration");
        if (duration <= 0) duration = fallbackDuration;
        long position = milliseconds(extras, "position");
        if ("completion".equals(reason) && duration > 0) position = duration;
        if (position < 0) return null;
        return new WatchHistory.Progress(duration > 0 ? Math.min(position, duration) : position, duration);
    }

    private static long milliseconds(Bundle extras, String key) {
        Object value = extras.get(key + "_ms");
        if (value instanceof Number ms && ms.longValue() >= 0) return ms.longValue();
        return extras.getInt(key + "_sec", -1) * 1000L;
    }
}
