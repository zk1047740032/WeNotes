package com.wenotes.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/** Exercises real rendering, home long-press, style selection and the gallery save path. */
final class QuoteShareChecks {
    private static final String TEXT = "其实地上本没有路，走的人多了，也便成了路。";
    private final Instrumentation instrumentation;
    private final Context context;
    private int count;

    private QuoteShareChecks(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
    }

    static int run(Instrumentation instrumentation) throws Throwable {
        return new QuoteShareChecks(instrumentation).run();
    }

    private int run() throws Throwable {
        for (int style : new int[]{QuoteImageRenderer.BLACK_WHITE, QuoteImageRenderer.BLUE_MAGAZINE}) {
            Bitmap image = QuoteImageRenderer.render(TEXT, "鲁迅", "故乡", style);
            require(image.getWidth() == 1080 && image.getHeight() >= 1440, "Unexpected export dimensions");
            require(image.getPixel(0, 0) == (style == 0 ? Color.WHITE : Color.rgb(40, 86, 204)),
                    "Wrong style background");
            write(image, style == 0 ? "black-white.png" : "blue-magazine.png");
            image.recycle();
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < 28; i++) longText.append(TEXT).append('\n');
            Bitmap longImage = QuoteImageRenderer.render(longText.toString(), "鲁迅", "故乡", style);
            require(longImage.getHeight() > 1440, "Long quote did not expand the image");
            write(longImage, style == 0 ? "long-black-white.png" : "long-blue-magazine.png");
            longImage.recycle();
            count += 2;
        }
        require("《故乡》".equals(QuoteImageRenderer.source("", "故乡")), "Blank author left a separator");
        require("鲁迅".equals(QuoteImageRenderer.source("鲁迅", "")), "Blank title left empty brackets");
        count++;

        Activity home = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(QuoteShareActivity.class.getName(), null, false);
        Activity screen = null;
        try {
            instrumentation.waitForIdleSync();
            onMain(() -> {
                try {
                    // Fixture is in memory only; leave the user's library and credentials untouched.
                    Field current = MainActivity.class.getDeclaredField("currentHighlight");
                    current.setAccessible(true);
                    current.set(home, new Highlight("share-check", "share-book", "故乡", "鲁迅", "", TEXT, "", "", 0, 0));
                    TextView quote = home.findViewById(R.id.current_quote);
                    quote.setText(TEXT);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            instrumentation.waitForIdleSync();
            int[] position = new int[2];
            onMain(() -> home.findViewById(R.id.current_quote).getLocationOnScreen(position));
            long down = SystemClock.uptimeMillis();
            sendTouch(down, MotionEvent.ACTION_DOWN, position[0] + 32, position[1] + 32);
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250L);
            sendTouch(down, MotionEvent.ACTION_UP, position[0] + 32, position[1] + 32);
            screen = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            require(screen instanceof QuoteShareActivity, "Long-press on selectable text did not open the share preview");
            count++;
            Activity previewScreen = screen;
            awaitPreview(previewScreen, QuoteImageRenderer.BLACK_WHITE);
            Bitmap first = bitmap(previewScreen);
            require(TEXT.equals(previewScreen.getIntent().getStringExtra(QuoteShareActivity.EXTRA_TEXT)),
                    "Long-press did not use the current sentence");
            require(first.sameAs(QuoteImageRenderer.render(TEXT, "鲁迅", "故乡", 0)), "Preview differs from export");
            count++;
            writeScreen("black-white-screen.png");
            onMain(() -> previewScreen.findViewById(R.id.quote_style_blue_magazine).performClick());
            awaitPreview(previewScreen, QuoteImageRenderer.BLUE_MAGAZINE);
            writeScreen("blue-magazine-screen.png");
            count++;
            onMain(() -> {
                previewScreen.findViewById(R.id.quote_style_black_white).performClick();
                previewScreen.findViewById(R.id.quote_style_blue_magazine).performClick();
            });
            awaitPreview(previewScreen, QuoteImageRenderer.BLUE_MAGAZINE);
            count++;

            Set<String> before = galleryImages();
            onMain(() -> {
                require(previewScreen.findViewById(R.id.quote_image_save).performClick(), "Save was rejected");
                require(!previewScreen.findViewById(R.id.quote_image_save).isEnabled(), "Duplicate save remains enabled");
            });
            long deadline = SystemClock.uptimeMillis() + 10000;
            boolean[] saved = new boolean[1];
            do {
                onMain(() -> saved[0] = context.getString(R.string.quote_image_saved)
                        .contentEquals(((TextView) previewScreen.findViewById(R.id.quote_image_status)).getText()));
                if (!saved[0]) SystemClock.sleep(50);
            } while (!saved[0] && SystemClock.uptimeMillis() < deadline);
            require(saved[0], "Save did not finish successfully");
            Set<String> created = galleryImages();
            created.removeAll(before);
            require(created.size() == 1, "Save did not create exactly one gallery image");
            Uri uri = Uri.parse(created.iterator().next());
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                Bitmap savedImage = BitmapFactory.decodeStream(input);
                require(savedImage != null && savedImage.sameAs(bitmap(previewScreen)),
                        "Gallery image differs from the selected preview");
                savedImage.recycle();
            } finally {
                context.getContentResolver().delete(uri, null, null);
            }
            count++;
        } finally {
            instrumentation.removeMonitor(monitor);
            Activity closing = screen;
            onMain(() -> { if (closing != null) closing.finish(); home.finish(); });
        }
        return count;
    }

    private void awaitPreview(Activity activity, int style) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean[] ready = new boolean[1];
        do {
            onMain(() -> ready[0] = activity.findViewById(R.id.quote_image_save).isEnabled()
                    && activity.findViewById(style == 0 ? R.id.quote_style_black_white : R.id.quote_style_blue_magazine).isSelected());
            if (!ready[0]) SystemClock.sleep(50);
        } while (!ready[0] && SystemClock.uptimeMillis() < deadline);
        require(ready[0], "Preview did not finish");
        require(bitmap(activity).getPixel(0, 0) == (style == 0 ? Color.WHITE : Color.rgb(40, 86, 204)),
                "A stale render replaced the selected style");
        instrumentation.waitForIdleSync();
    }

    private Bitmap bitmap(Activity activity) throws Throwable {
        Bitmap[] image = new Bitmap[1];
        onMain(() -> image[0] = ((BitmapDrawable) ((ImageView) activity.findViewById(R.id.quote_image_preview))
                .getDrawable()).getBitmap());
        return image[0];
    }

    private Set<String> galleryImages() {
        Set<String> images = new HashSet<>();
        try (Cursor cursor = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                new String[]{"句子_%"}, null)) {
            require(cursor != null, "Gallery query failed");
            while (cursor.moveToNext()) images.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getString(0)).toString());
        }
        return images;
    }

    private void sendTouch(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(event);
        event.recycle();
    }

    private void writeScreen(String name) throws Exception {
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        require(screenshot != null, "Screenshot failed");
        write(screenshot, name);
        screenshot.recycle();
    }

    private void write(Bitmap image, String name) throws Exception {
        File folder = new File(context.getExternalFilesDir(null), "quote-share-checks");
        require(folder.isDirectory() || folder.mkdirs(), "Cannot create preview folder");
        try (FileOutputStream output = new FileOutputStream(new File(folder, name))) {
            require(image.compress(Bitmap.CompressFormat.PNG, 100, output), "PNG encoding failed");
        }
    }

    private void onMain(Runnable action) throws Throwable {
        Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw failure[0];
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
