package ru.boostyatv.client;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Artwork {
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> cache = new LruCache<>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    void bind(ImageView view, String url) {
        if (Post.https(url).isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        workers.execute(() -> {
            Bitmap bitmap = fetch(url);
            if (bitmap == null) return;
            cache.put(url, bitmap);
            view.post(() -> view.setImageBitmap(bitmap));
        });
    }

    void close() {
        workers.shutdownNow();
        cache.evictAll();
    }

    private Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6_000);
            connection.setReadTimeout(9_000);
            connection.setInstanceFollowRedirects(false);
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
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
