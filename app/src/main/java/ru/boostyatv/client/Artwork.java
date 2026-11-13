package ru.boostyatv.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Image loader with a two-level cache: decoded bitmaps in memory and the
 * original bytes on disk. The disk layer keeps feed and post covers instant
 * across restarts and after the process is recreated by the system.
 */
final class Artwork {
    private static final int MAX_CACHE = 24 * 1024 * 1024;
    private static final long MAX_DISK_CACHE = 64L * 1024 * 1024;
    private static final long FAILURE_TTL_MS = 60_000;
    private static final int MAX_FAILURES = 128;
    private final ExecutorService workers = AppExecutors.network("artwork", 4);
    private final Map<String, CompletableFuture<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> failures = new ConcurrentHashMap<>();
    private final File disk;
    private final LruCache<String, Bitmap> cache = new LruCache<>(cacheSize()) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    Artwork(Context context) {
        File base = context.getCacheDir();
        disk = new File(base, "artwork");
        if (!disk.isDirectory() && !disk.mkdirs()) {
            Log.d("BoostyATV", "Artwork disk cache is unavailable");
        }
    }

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
            key -> CompletableFuture.supplyAsync(() -> load(key), workers));
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

    /** Memory miss: try the disk copy first, then download and remember it. */
    private Bitmap load(String url) {
        File file = diskFile(url);
        byte[] bytes = read(file);
        if (bytes != null) {
            Bitmap bitmap = decode(bytes);
            if (bitmap != null) return bitmap;
            if (!file.delete()) Log.d("BoostyATV", "Cannot drop corrupt artwork " + url);
        }
        byte[] downloaded = download(url);
        if (downloaded == null) return null;
        Bitmap bitmap = decode(downloaded);
        if (bitmap != null) {
            write(file, downloaded);
            trimDisk();
        }
        return bitmap;
    }

    private File diskFile(String url) {
        return new File(disk, sha256(url) + ".img");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte part : hash) builder.append(String.format(Locale.ROOT, "%02x", part));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static byte[] read(File file) {
        if (!file.isFile()) return null;
        try (InputStream stream = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) output.write(chunk, 0, count);
            return output.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void write(File file, byte[] bytes) {
        if (!disk.isDirectory()) return;
        File temp = new File(disk, file.getName() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
        } catch (Exception e) {
            Log.d("BoostyATV", "Artwork disk write failed", e);
            if (!temp.delete()) Log.d("BoostyATV", "Cannot remove " + temp.getName());
            return;
        }
        if (!temp.renameTo(file)) {
            if (!temp.delete()) Log.d("BoostyATV", "Cannot remove " + temp.getName());
        }
    }

    /** Keeps the on-disk preview cache within its budget, dropping oldest entries first. */
    private void trimDisk() {
        File[] files = disk.listFiles();
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= MAX_DISK_CACHE) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long target = MAX_DISK_CACHE * 3 / 4;
        for (File file : files) {
            if (total <= target) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1080
            || options.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static byte[] download(String url) {
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
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    if (output.size() + count > 6 * 1024 * 1024) return null;
                    output.write(chunk, 0, count);
                }
                return output.toByteArray();
            }
        } catch (Exception e) {
            Log.d("BoostyATV", "Artwork fetch failed for " + url, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
