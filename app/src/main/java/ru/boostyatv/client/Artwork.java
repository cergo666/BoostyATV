package ru.boostyatv.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Image loader with a two-level cache: decoded bitmaps in memory and the
 * original bytes on disk. The disk layer keeps feed and post covers instant
 * across restarts and after the process is recreated by the system.
 */
final class Artwork {
    static final int AVATAR = 128;
    static final int THUMBNAIL = 480;
    static final int PREVIEW = 1080;
    private static final int MAX_CACHE = 96 * 1024 * 1024;
    private static final long MAX_DISK_CACHE = 160L * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final long DISK_TOUCH_INTERVAL_MS = 60 * 60 * 1000L;
    private static final long FAILURE_TTL_MS = 60_000;
    private static final int MAX_FAILURES = 128;
    private static final Object[] DISK_LOCKS = new Object[32];
    private static final Object TRIM_LOCK = new Object();
    static {
        Arrays.setAll(DISK_LOCKS, ignored -> new Object());
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = AppExecutors.network("artwork",
        Math.max(4, Math.min(6, Runtime.getRuntime().availableProcessors())));
    private final ExecutorService featured = AppExecutors.network("featured-artwork", 2);
    private record Request(String url, int size) {}
    private final Map<Request, CompletableFuture<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> failures = new ConcurrentHashMap<>();
    private final File disk;
    private final LruCache<Request, Bitmap> cache = new LruCache<>(cacheSize()) {
        @Override protected int sizeOf(Request key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final LruCache<String, Integer> ambientColors = new LruCache<>(128);
    private long spaceBeforeTrim;

    Artwork(Context context) {
        File base = context.getCacheDir();
        disk = new File(base, "artwork");
        if (!disk.isDirectory() && !disk.mkdirs()) {
            Log.d("BoostyATV", "Artwork disk cache is unavailable");
        }
    }

    Bitmap cached(String url, int size) {
        if (url == null || url.isEmpty()) return null;
        Bitmap exact = cache.get(new Request(url, size));
        if (exact != null) return exact;
        if (size == PREVIEW) return cache.get(new Request(url, THUMBNAIL));
        return null;
    }

    /** Warms the disk and memory cache ahead of scroll without waking the main thread. */
    void prefetch(String url, int size) {
        if (url == null || url.isEmpty() || workers.isShutdown()) return;
        Request request = new Request(url, size);
        if (cache.get(request) != null) return;
        Long failedAt = failures.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) return;
        CompletableFuture<Bitmap> future = inFlight.computeIfAbsent(request,
            key -> CompletableFuture.supplyAsync(() -> loadAndCache(key), workers));
        future.whenComplete((bitmap, ignored) -> inFlight.remove(request, future));
    }

    void loadBitmap(String url, int size, boolean priority, Consumer<Bitmap> onResult) {
        if (url == null || url.isEmpty()) return;
        Request request = new Request(url, size);
        Bitmap existing = cache.get(request);
        if (existing != null) {
            onResult.accept(existing);
            return;
        }
        Long failedAt = failures.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) return;
        if (workers.isShutdown()) return;
        CompletableFuture<Bitmap> future = inFlight.computeIfAbsent(request,
            key -> CompletableFuture.supplyAsync(() -> loadAndCache(key), priority ? featured : workers));
        future.thenAccept(bitmap -> {
            if (bitmap == null) return;
            main.post(() -> onResult.accept(bitmap));
        });
        future.whenComplete((bitmap, error) -> inFlight.remove(request, future));
    }

    Integer cachedAmbientColor(String url) {
        if (url == null || url.isEmpty()) return null;
        return ambientColors.get(url);
    }

    void loadAmbientColor(String url, Consumer<Integer> onResult) {
        if (url == null || url.isEmpty()) return;
        Integer cached = ambientColors.get(url);
        if (cached != null) {
            onResult.accept(cached);
            return;
        }
        loadBitmap(url, THUMBNAIL, false, bitmap -> {
            if (workers.isShutdown()) return;
            try {
                workers.execute(() -> {
                    int color = extractAmbientColor(bitmap);
                    ambientColors.put(url, color);
                    main.post(() -> onResult.accept(color));
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Loader closed while scrolling away.
            }
        });
    }

    private static int extractAmbientColor(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return 0xFFFF7A4D;
        float[] hsv = new float[3];
        double sumX = 0.0;
        double sumY = 0.0;
        double totalWeight = 0.0;
        float sumS = 0f;
        float sumV = 0f;
        int count = 0;
        int samples = 12;
        for (int iy = 0; iy < samples; iy++) {
            int y = Math.min(height - 1, (iy * height) / samples + height / (samples * 2));
            for (int ix = 0; ix < samples; ix++) {
                int x = Math.min(width - 1, (ix * width) / samples + width / (samples * 2));
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 128) continue;
                Color.colorToHSV(pixel, hsv);
                if (hsv[2] < 0.15f || hsv[1] < 0.12f) continue;
                double weight = hsv[1] * (0.4 + 0.6 * hsv[2]);
                double rad = Math.toRadians(hsv[0]);
                sumX += Math.cos(rad) * weight;
                sumY += Math.sin(rad) * weight;
                totalWeight += weight;
                sumS += hsv[1];
                sumV += hsv[2];
                count++;
            }
        }
        if (count == 0 || totalWeight <= 0.001) return 0xFFFF7A4D;
        float hue = (float) Math.toDegrees(Math.atan2(sumY, sumX));
        if (hue < 0f) hue += 360f;
        float saturation = Math.max(0.45f, Math.min(0.78f, sumS / count));
        float value = Math.max(0.55f, Math.min(0.80f, sumV / count));
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }

    void close() {
        workers.shutdownNow();
        featured.shutdownNow();
        inFlight.clear();
        failures.clear();
        cache.evictAll();
        ambientColors.evictAll();
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
        return (int) Math.min(MAX_CACHE, Math.max(8L * 1024 * 1024, max / 4));
    }

    /** Memory miss: try the disk copy first, then download and remember it. */
    private Bitmap loadAndCache(Request request) {
        String url = request.url();
        Bitmap bitmap;
        synchronized (DISK_LOCKS[Math.floorMod(url.hashCode(), DISK_LOCKS.length)]) {
            bitmap = load(url, request.size());
        }
        if (bitmap == null) rememberFailure(url);
        else {
            failures.remove(url);
            cache.put(request, bitmap);
        }
        return bitmap;
    }

    private Bitmap load(String url, int size) {
        File file = diskFile(url);
        if (file.isFile()) {
            Bitmap bitmap = decode(file, null, size);
            if (bitmap != null) {
                long now = System.currentTimeMillis();
                if (now - file.lastModified() >= DISK_TOUCH_INTERVAL_MS) file.setLastModified(now);
                return bitmap;
            }
            if (!file.delete()) Log.d("BoostyATV", "Cannot drop corrupt artwork");
        }
        if (Post.https(url).isEmpty()) return null;
        byte[] downloaded = download(url);
        if (downloaded == null) return null;
        Bitmap bitmap = decode(null, downloaded, size);
        if (bitmap != null) write(file, downloaded);
        return bitmap;
    }

    private File diskFile(String url) {
        return new File(disk, sha256(url) + ".img");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return hex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            hex[i * 2] = digits[(bytes[i] & 0xff) >>> 4];
            hex[i * 2 + 1] = digits[bytes[i] & 0x0f];
        }
        return new String(hex);
    }

    private void write(File file, byte[] bytes) {
        if (!disk.isDirectory() && !disk.mkdirs() && !disk.isDirectory()) return;
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
            return;
        }
        trimDisk(bytes.length);
    }

    /** Keeps the on-disk preview cache within its budget, dropping oldest entries first. */
    private void trimDisk(int written) {
        synchronized (TRIM_LOCK) {
            spaceBeforeTrim -= written;
            if (spaceBeforeTrim > 0) return;
            File[] files = disk.listFiles((dir, name) -> name.endsWith(".img"));
            if (files == null || files.length == 0) return;
            long total = 0;
            for (File file : files) total += file.length();
            if (total > MAX_DISK_CACHE) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                long target = MAX_DISK_CACHE * 3 / 4;
                for (File file : files) {
                    if (total <= target) break;
                    long size = file.length();
                    if (file.delete()) total -= size;
                }
            }
            spaceBeforeTrim = MAX_DISK_CACHE - total;
        }
    }

    private static Bitmap decode(File file, byte[] bytes, int size) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        decodeImage(file, bytes, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        int longest = Math.max(options.outWidth, options.outHeight);
        while (longest / options.inSampleSize > size) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return decodeImage(file, bytes, options);
    }

    private static Bitmap decodeImage(File file, byte[] bytes, BitmapFactory.Options options) {
        return file == null ? BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options)
            : BitmapFactory.decodeFile(file.getPath(), options);
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
                    if (output.size() + count > MAX_IMAGE_BYTES) return null;
                    output.write(chunk, 0, count);
                }
                return output.toByteArray();
            }
        } catch (Exception e) {
            Log.d("BoostyATV", "Artwork fetch failed", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
