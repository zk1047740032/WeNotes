package com.wenotes.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

/** Real touch input and persistence checks using one disposable book, without changing the library cache. */
final class BookshelfFavoriteChecks {
    private static final String ID = "__bookshelf_favorite_check__";
    private final Instrumentation instrumentation;
    private final Context context;
    private final FavoriteStore favorites;
    private BookshelfActivity shelf;
    private int count;

    private BookshelfFavoriteChecks(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
        favorites = new FavoriteStore(context);
    }

    static int run(Instrumentation instrumentation) throws Throwable {
        BookshelfFavoriteChecks checks = new BookshelfFavoriteChecks(instrumentation);
        try { checks.run(); return checks.count; }
        finally {
            if (checks.favorites.read().containsKey(ID)) checks.favorites.toggle(checks.favorites.read().get(ID));
            checks.onMain(() -> { if (checks.shelf != null) checks.shelf.finish(); });
        }
    }

    private void run() throws Throwable {
        shelf = (BookshelfActivity) instrumentation.startActivitySync(new Intent(context, BookshelfActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> ((TextView) shelf.findViewById(R.id.shelf_summary)).length() > 0);
        ShelfBook book = new ShelfBook(ID, "双层藏书框测试", "手势验证", "book", true, 1, 0, 0,
                "", System.currentTimeMillis() / 1000);
        onMain(() -> {
            shelf.snapshot = new LibrarySnapshot(Collections.singletonList(book), Collections.emptyList(), Collections.emptyList(), true);
            shelf.showLibrary();
        });
        await(() -> grid().getChildCount() == 1 && cover().getWidth() > 0);
        Instrumentation.ActivityMonitor notes = instrumentation.addMonitor(BookNotesActivity.class.getName(), null, true);
        Instrumentation.ActivityMonitor reader = instrumentation.addMonitor(NoteReaderActivity.class.getName(), null, true);
        try {
            onMain(() -> {
                require(!cover().isActivated(), "An unsaved book has a gold frame");
                require(cover().getChildCount() == 2, "The cover still has an overlay action");
                for (int i = 0; i < cover().getChildCount(); i++) {
                    require(!(cover().getChildAt(i) instanceof ImageButton), "The star button is still visible");
                }
            }); count++;

            tap(60); settle();
            require(notes.getHits() == 1 && reader.getHits() == 0, "A single tap did not open only the notes screen"); count++;
            doubleTap();
            await(() -> cover().isActivated() && favorites.read().containsKey(ID));
            settle();
            require(notes.getHits() == 1 && reader.getHits() == 0, "Double tap also opened a screen"); count++;

            onMain(() -> {
                Bitmap image = Bitmap.createBitmap(cover().getWidth(), cover().getHeight(), Bitmap.Config.ARGB_8888);
                cover().draw(new Canvas(image));
                int bands = 0;
                boolean previousGold = false;
                int end = Math.round(5.5f * context.getResources().getDisplayMetrics().density);
                for (int y = 0; y < end; y++) {
                    int color = image.getPixel(image.getWidth() / 2, y);
                    boolean gold = Color.red(color) - Color.blue(color) > 18 && Color.green(color) - Color.blue(color) > 6;
                    if (gold && !previousGold) bands++;
                    previousGold = gold;
                }
                require(bands == 2, "The saved cover does not have two separate gold lines: " + bands);
                try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "bookshelf-gold-frame.png"))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, output);
                } catch (Exception error) { throw new AssertionError(error); }
                image.recycle();
            }); count++;

            onMain(shelf::showLibrary);
            await(() -> cover().isActivated());
            require(new FavoriteStore(context).read().containsKey(ID), "Saved state disappeared after rebinding"); count++;

            drag(); settle();
            require(favorites.read().containsKey(ID) && notes.getHits() == 1, "Scrolling toggled a favorite or opened notes"); count++;

            doubleTap();
            await(() -> !cover().isActivated() && !favorites.read().containsKey(ID));
            require(notes.getHits() == 1, "Unfavoriting opened notes"); count++;

            tap(ViewConfiguration.getLongPressTimeout() + 150); settle();
            require(reader.getHits() == 1 && notes.getHits() == 1 && !favorites.read().containsKey(ID),
                    "Long press conflicted with opening notes or favorites"); count++;

            doubleTap();
            await(() -> favorites.read().containsKey(ID));
            onMain(() -> shelf.findViewById(R.id.favorites_tab).performClick());
            await(() -> grid().getChildCount() == 1 && cover().isActivated());
            doubleTap();
            await(() -> grid().getAdapter().getCount() == 0 && !favorites.read().containsKey(ID)); count++;

            onMain(shelf::openRecent);
            await(() -> grid().getChildCount() == 1);
            onMain(() -> {
                AccessibilityNodeInfo info = cover().createAccessibilityNodeInfo();
                require(info.getActionList().stream().anyMatch(a -> a.getId() == R.id.action_toggle_book_favorite),
                        "Favorite action is unavailable to accessibility services");
                require(cover().performAccessibilityAction(R.id.action_toggle_book_favorite, null), "Accessibility action failed");
            });
            await(() -> favorites.read().containsKey(ID) && cover().isActivated()); count++;
            onMain(() -> cover().performAccessibilityAction(R.id.action_toggle_book_favorite, null));
            await(() -> !favorites.read().containsKey(ID) && !cover().isActivated()); count++;

            // A recycled cell must not open its new book because of the previous book's pending tap.
            tap(60);
            onMain(() -> cover().bindFavorite("__recycled_book__", false, "收藏", null));
            settle();
            require(notes.getHits() == 1, "A pending tap leaked into a recycled cell"); count++;
        } finally {
            instrumentation.removeMonitor(notes);
            instrumentation.removeMonitor(reader);
        }
    }

    private GridView grid() { return shelf.findViewById(R.id.book_list); }
    private BookCoverFrame cover() { return grid().getChildAt(0).findViewById(R.id.book_cover_frame); }
    private void doubleTap() throws Throwable { tap(55); SystemClock.sleep(65); tap(55); }
    private void settle() { SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 120); instrumentation.waitForIdleSync(); }

    private void tap(long duration) throws Throwable {
        float[] point = point();
        long down = SystemClock.uptimeMillis();
        send(down, MotionEvent.ACTION_DOWN, point[0], point[1]);
        SystemClock.sleep(duration);
        send(down, MotionEvent.ACTION_UP, point[0], point[1]);
    }

    private void drag() throws Throwable {
        float[] point = point();
        long down = SystemClock.uptimeMillis();
        send(down, MotionEvent.ACTION_DOWN, point[0], point[1]);
        for (int step = 1; step <= 6; step++) {
            SystemClock.sleep(25);
            send(down, MotionEvent.ACTION_MOVE, point[0], point[1] - step * 18);
        }
        send(down, MotionEvent.ACTION_UP, point[0], point[1] - 108);
    }

    private float[] point() throws Throwable {
        float[] point = new float[2];
        onMain(() -> {
            int[] location = new int[2];
            cover().getLocationOnScreen(location);
            point[0] = location[0] + cover().getWidth() * .5f;
            point[1] = location[1] + cover().getHeight() * .5f;
        });
        return point;
    }

    private void send(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        instrumentation.sendPointerSync(event);
        event.recycle();
    }

    private interface Condition { boolean ready(); }
    private void await(Condition condition) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 6000;
        boolean[] ready = {false};
        while (SystemClock.uptimeMillis() < deadline) {
            onMain(() -> ready[0] = condition.ready());
            if (ready[0]) return;
            SystemClock.sleep(30);
        }
        throw new AssertionError("Timed out after " + count + " bookshelf checks");
    }

    private void onMain(Runnable action) throws Throwable {
        Throwable[] error = new Throwable[1];
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw error[0];
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
