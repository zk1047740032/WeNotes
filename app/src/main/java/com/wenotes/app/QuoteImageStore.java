package com.wenotes.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** Publishes a completed PNG to the user's Pictures collection. */
final class QuoteImageStore {
    private QuoteImageStore() { }

    @SuppressWarnings("deprecation") // Android 8/9 require an absolute path and storage permission.
    static Uri save(Context context, Bitmap image) throws IOException {
        String name = "句子_" + UUID.randomUUID() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/句子");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "句子");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create picture folder");
            values.put(MediaStore.Images.Media.DATA, new File(folder, name).getAbsolutePath());
        }
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create image entry");
        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null || !image.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Cannot write image");
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (resolver.update(uri, ready, null, null) != 1) throw new IOException("Cannot publish image");
            }
            return uri;
        } catch (IOException | RuntimeException error) {
            try { resolver.delete(uri, null, null); }
            catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
}
