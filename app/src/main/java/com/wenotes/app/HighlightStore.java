package com.wenotes.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class HighlightStore {
    private static final String FILE_NAME = "highlights.json";
    private static final String PREFS = "highlight_state";
    private static final Object LOCK = new Object();
    private final Context context;
    private final SharedPreferences prefs;

    HighlightStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Highlight> read() {
        return readLibrary().highlights;
    }

    LibrarySnapshot readLibrary() {
        synchronized (LOCK) {
            AtomicFile file = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
            if (!file.getBaseFile().exists()) return LibrarySnapshot.empty();
            try (FileInputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                return LibrarySnapshot.fromJson(new JSONTokener(
                        bytes.toString(StandardCharsets.UTF_8.name())).nextValue());
            } catch (Exception ignored) {
                // Leave the widget in its empty state if the cache cannot be read.
            }
            return LibrarySnapshot.empty();
        }
    }

    boolean replace(LibrarySnapshot snapshot, long generation) throws Exception {
        byte[] bytes = snapshot.toJson().toString().getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            if (generation != generation()) return false;
            AtomicFile file = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(bytes);
                file.finishWrite(output);
            } catch (Exception error) {
                if (output != null) file.failWrite(output);
                throw error;
            }
            int current = prefs.getInt("index", 0);
            List<Highlight> highlights = snapshot.highlights;
            prefs.edit().putInt("index", highlights.isEmpty() ? 0 : Math.min(current, highlights.size() - 1))
                    .putLong("last_sync", System.currentTimeMillis()).apply();
            return true;
        }
    }

    int index(int size) {
        if (size == 0) return 0;
        return Math.floorMod(prefs.getInt("index", 0), size);
    }

    synchronized void next(int size) {
        if (size == 0) return;
        prefs.edit().putInt("index", (index(size) + 1) % size)
                .putLong("last_rotate", System.currentTimeMillis()).apply();
    }

    synchronized void rotateIfDue(int size) {
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_rotate", 0L);
        if (size == 0) return;
        if (last == 0L) {
            prefs.edit().putLong("last_rotate", now).apply();
        } else if (now - last >= 25L * 60L * 1000L) {
            next(size);
        }
    }

    void setStatus(String message) {
        prefs.edit().putString("status", message).apply();
    }

    void setStatus(String message, long generation) {
        synchronized (LOCK) {
            if (generation == generation()) setStatus(message);
        }
    }

    long generation() {
        return prefs.getLong("generation", 0L);
    }

    String status() {
        return prefs.getString("status", "尚未同步");
    }

    long lastSync() {
        return prefs.getLong("last_sync", 0L);
    }

    void clear() {
        synchronized (LOCK) {
            long nextGeneration = generation() + 1;
            new AtomicFile(new File(context.getFilesDir(), FILE_NAME)).delete();
            new FavoriteStore(context).clear();
            new NoteFavoriteStore(context).clear();
            CoverLoader.clear(context);
            prefs.edit().clear().putLong("generation", nextGeneration).apply();
        }
    }
}
