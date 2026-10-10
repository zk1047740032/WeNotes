package com.wenotes.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads public cover images without sending account credentials to image hosts. */
final class CoverLoader {
    private static final Object LOCK = new Object();
    private static final String PREFS = "book_cover_urls";
    private final Context context;
    private final SharedPreferences urls;
    private final HighlightStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Map<String, List<Target>> pending = new HashMap<>();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private boolean requestedIndex;
    private boolean disposed;
    private long loadedGeneration = -1;

    CoverLoader(Context context) {
        this.context = context.getApplicationContext();
        urls = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        store = new HighlightStore(this.context);
    }

    void loadMissing(List<ShelfBook> books, Runnable refreshed) {
        long currentGeneration = store.generation();
        if (loadedGeneration != currentGeneration) {
            loadedGeneration = currentGeneration;
            requestedIndex = false;
            memory.evictAll();
        }
        if (requestedIndex || disposed || books.isEmpty()) return;
        boolean missing = false;
        for (ShelfBook book : books) {
            if (book.onShelf && !"articles".equals(book.kind) && book.cover.isEmpty()
                    && urls.getString(book.id, "").isEmpty()) {
                missing = true;
                break;
            }
        }
        if (!missing || !new SecretStore(context).hasKey()) return;
        requestedIndex = true;
        long generation = store.generation();
        worker.execute(() -> {
            try {
                JSONObject page = new WeReadApi(new SecretStore(context).read()).call("/shelf/sync", new JSONObject());
                Map<String, ShelfBook> shelf = LibrarySync.readShelf(page);
                synchronized (LOCK) {
                    if (generation != store.generation()) return;
                    SharedPreferences.Editor editor = urls.edit();
                    for (ShelfBook book : shelf.values()) if (!book.cover.isEmpty()) editor.putString(book.id, book.cover);
                    editor.apply();
                }
                main.post(() -> { if (!disposed && generation == store.generation()) refreshed.run(); });
            } catch (Exception error) {
                if (error.getMessage() != null && error.getMessage().startsWith("接口要求更新")) {
                    store.setStatus(error.getMessage(), generation);
                }
                // Keep the readable book placeholder when offline or the account is unavailable.
            }
        });
    }

    void bind(ImageView image, TextView placeholder, ShelfBook book) {
        String url = book.cover.isEmpty() ? urls.getString(book.id, "") : book.cover;
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        image.setTag(url);
        image.setImageDrawable(null);
        placeholder.setVisibility(View.VISIBLE);
        if (!url.startsWith("https://") || disposed) return;
        Bitmap cached = memory.get(url);
        if (cached != null) {
            image.setImageBitmap(cached);
            placeholder.setVisibility(View.GONE);
            return;
        }
        Target target = new Target(image, placeholder);
        long generation = store.generation();
        String requestKey = generation + "|" + url;
        List<Target> targets = pending.get(requestKey);
        if (targets != null) { targets.add(target); return; }
        targets = new ArrayList<>();
        targets.add(target);
        pending.put(requestKey, targets);
        String requestUrl = url;
        worker.execute(() -> {
            Bitmap bitmap = null;
            try { bitmap = readImage(requestUrl, generation); } catch (Exception ignored) { }
            Bitmap result = bitmap;
            main.post(() -> {
                List<Target> waiting = pending.remove(requestKey);
                if (disposed || generation != store.generation() || waiting == null || result == null) return;
                memory.put(requestUrl, result);
                for (Target candidate : waiting) {
                    ImageView view = candidate.image.get();
                    TextView fallback = candidate.placeholder.get();
                    if (view != null && requestUrl.equals(view.getTag())) {
                        view.setImageBitmap(result);
                        if (fallback != null) fallback.setVisibility(View.GONE);
                    }
                }
            });
        });
    }

    private Bitmap readImage(String url, long generation) throws Exception {
        File directory = new File(context.getCacheDir(), "book_covers");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(generation + "-");
        for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        File file = new File(directory, name.toString());
        byte[] bytes;
        synchronized (LOCK) { bytes = file.isFile() ? Files.readAllBytes(file.toPath()) : null; }
        if (bytes == null) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            try {
                if (connection.getResponseCode() != 200) return null;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || output.size() + length > 2 * 1024 * 1024) return null;
                        output.write(buffer, 0, length);
                    }
                    bytes = output.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 400 || options.outHeight / options.inSampleSize > 600) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) { synchronized (LOCK) { file.delete(); } }
        if (bitmap != null) {
            synchronized (LOCK) {
                if (generation != store.generation()) return null;
                if (!file.isFile() && (directory.isDirectory() || directory.mkdirs())) {
                    File[] files = directory.listFiles();
                    if (files != null && files.length >= 180) {
                        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
                        files[0].delete();
                    }
                    try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
                }
            }
        }
        return bitmap;
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
            File[] files = new File(context.getCacheDir(), "book_covers").listFiles();
            if (files != null) for (File file : files) file.delete();
        }
    }

    void dispose() { disposed = true; worker.shutdownNow(); pending.clear(); memory.evictAll(); }

    private static final class Target {
        final WeakReference<ImageView> image;
        final WeakReference<TextView> placeholder;
        Target(ImageView image, TextView placeholder) {
            this.image = new WeakReference<>(image);
            this.placeholder = new WeakReference<>(placeholder);
        }
    }
}
