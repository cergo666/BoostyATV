package ru.boostyatv.client;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

final class Artwork {
    private static final int MAX_CACHE = 24 * 1024 * 1024;
    private static final long FAILURE_TTL_MS = 60_000;
    private static final int MAX_FAILURES = 128;
    private final ExecutorService workers = AppExecutors.network("artwork", 4);
    private final Map<String, CompletableFuture<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> failures = new ConcurrentHashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<>(cacheSize()) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    void bind(ImageView view, String url) {
        Object previous = view.getTag(R.id.artwork_url);
        view.setTag(R.id.artwork_url, url);
        if (!url.equals(previous)) view.setImageDrawable(null);
        if (Post.https(url).isEmpty()) {
            return;
        }
        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        Long failedAt = failures.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) {
            return;
        }
        if (workers.isShutdown()) return;
        CompletableFuture<Bitmap> future = inFlight.computeIfAbsent(url,
            key -> CompletableFuture.supplyAsync(() -> fetch(key), workers));
        future.thenAccept(bitmap -> {
            if (bitmap == null) {
                rememberFailure(url);
                return;
            }
            failures.remove(url);
            cache.put(url, bitmap);
            view.post(() -> {
                if (url.equals(view.getTag(R.id.artwork_url))) view.setImageBitmap(bitmap);
            });
        });
        future.whenComplete((bitmap, error) -> inFlight.remove(url, future));
    }

    void close() {
        workers.shutdownNow();
        inFlight.clear();
        failures.clear();
        cache.evictAll();
    }

    private void rememberFailure(String url) {
        failures.put(url, System.currentTimeMillis());
        if (failures.size() <= MAX_FAILURES) return;
        long now = System.currentTimeMillis();
        failures.values().removeIf(attempt -> now - attempt >= FAILURE_TTL_MS);
        if (failures.size() > MAX_FAILURES) failures.clear();
    }

    private static int cacheSize() {
        long max = Runtime.getRuntime().maxMemory();
        return (int) Math.min(MAX_CACHE, Math.max(4L * 1024 * 1024, max / 8));
    }

    private Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(9_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "image/*");
            connection.setRequestProperty("User-Agent", "BoostyATV/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Referer", BoostyApi.ORIGIN + "/");
            if (connection.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    if (output.size() + count > 6 * 1024 * 1024) return null;
                    output.write(chunk, 0, count);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 1080
                || options.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception e) {
            Log.d("BoostyATV", "Artwork fetch failed for " + url, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
