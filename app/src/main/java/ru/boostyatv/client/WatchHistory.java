package ru.boostyatv.client;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class WatchHistory {
    private static final String PENDING = ".pending";
    private static final String EXTERNAL = ".external";
    private static final String ORDER = "order";
    private static final int MAX_ENTRIES = 500;
    private static final Object LOCK = new Object();
    record Pending(String id, long position) {}
    record Order(List<String> ids, List<String> evicted) {}

    static Order nextOrder(String raw, String id, int limit) {
        List<String> ids = new ArrayList<>();
        if (!raw.isEmpty()) java.util.Collections.addAll(ids, raw.split(","));
        ids.removeIf(id::equals);
        ids.add(id);
        List<String> evicted = new ArrayList<>();
        while (ids.size() > limit) evicted.add(ids.remove(0));
        return new Order(ids, evicted);
    }

    record Progress(long position, long duration) {
        long resume() { return duration > 0 && position >= duration - 1000 ? 0 : position; }

        int percentage() {
            if (duration <= 0 || position <= 0) return 0;
            return Math.max(1, (int) Math.min(100, position * 100.0 / duration));
        }
    }

    private final SharedPreferences preferences;

    WatchHistory(Context context) {
        preferences = context.getSharedPreferences("watch_progress", Context.MODE_PRIVATE);
    }

    Progress get(Post.Block video) {
        if (video.videoId().isEmpty()) return new Progress(video.positionMs(), video.durationMs());
        return new Progress(preferences.getLong(video.videoId() + ".position", video.positionMs()),
            preferences.getLong(video.videoId() + ".duration", video.durationMs()));
    }

    void save(Post.Block video, long position, long duration) {
        if (video.videoId().isEmpty()) return;
        synchronized (LOCK) {
            touch(video.videoId());
            preferences.edit().remove(video.videoId() + EXTERNAL)
                .putLong(video.videoId() + ".position", position)
                .putLong(video.videoId() + ".duration", duration)
                .putLong(video.videoId() + PENDING, position).apply();
        }
    }

    void registerExternal(String id, String nonce) {
        if (id.isEmpty()) return;
        synchronized (LOCK) {
            preferences.edit().putString(id + EXTERNAL, nonce).apply();
        }
    }

    void saveExternal(String id, String nonce, Progress progress) {
        synchronized (LOCK) {
            if (nonce == null || nonce.isEmpty() || !nonce.equals(preferences.getString(id + EXTERNAL, ""))) return;
            touch(id);
            preferences.edit().putLong(id + ".position", progress.position())
                .putLong(id + ".duration", progress.duration())
                .putLong(id + PENDING, progress.position()).apply();
        }
    }

    private void touch(String id) {
        String raw = preferences.getString(ORDER, "");
        if (raw.equals(id) || raw.endsWith("," + id)) return;
        Order order = nextOrder(raw, id, MAX_ENTRIES);
        android.content.SharedPreferences.Editor editor = preferences.edit()
            .putString(ORDER, String.join(",", order.ids()));
        for (String old : order.evicted()) {
            editor.remove(old + ".position").remove(old + ".duration")
                .remove(old + PENDING).remove(old + EXTERNAL);
        }
        editor.apply();
    }

    void listen(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    void stopListening(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    List<Pending> pending() {
        List<Pending> result = new ArrayList<>();
        preferences.getAll().forEach((key, value) -> {
            if (key.endsWith(PENDING) && value instanceof Long position) {
                result.add(new Pending(key.substring(0, key.length() - PENDING.length()), position));
            }
        });
        return result;
    }

    void confirmSync(Pending sent) {
        synchronized (LOCK) {
            String key = sent.id() + PENDING;
            if (preferences.getLong(key, -1) == sent.position()) preferences.edit().remove(key).apply();
        }
    }

    void clear() {
        synchronized (LOCK) {
            preferences.edit().clear().apply();
        }
    }
}
