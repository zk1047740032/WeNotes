package com.wenotes.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.GridView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exercises the real cover entry and reader with synthetic notes; never replaces the user's cache. */
final class NoteReaderChecks {
    private static final String BOOK = "__note_reader_check__";
    private final Instrumentation instrumentation;
    private final Context context;
    private BookshelfActivity shelf;
    private NoteReaderActivity activity;
    private NoteReaderView reader;
    private int count;
    private long downTime;
    private long eventTime;

    private NoteReaderChecks(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
    }

    static int run(Instrumentation instrumentation) throws Throwable {
        NoteReaderChecks checks = new NoteReaderChecks(instrumentation);
        try { checks.run(); return checks.count; }
        finally {
            checks.onMain(() -> {
                if (checks.activity != null) checks.activity.finish();
                if (checks.shelf != null) checks.shelf.finish();
            });
            instrumentation.waitForIdleSync();
            checks.context.getSharedPreferences("note_reading_positions", Context.MODE_PRIVATE).edit().remove(BOOK).commit();
        }
    }

    private void run() throws Throwable {
        shelf = (BookshelfActivity) instrumentation.startActivitySync(new Intent(context, BookshelfActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        await("initial shelf", () -> ((TextView) shelf.findViewById(R.id.shelf_summary)).length() > 0);
        LibrarySnapshot fixture = fixture();
        prepareShelf(fixture);
        Instrumentation.ActivityMonitor notesMonitor = instrumentation.addMonitor(BookNotesActivity.class.getName(), null, false);
        pressCover(false);
        Activity notes = instrumentation.waitForMonitorWithTimeout(notesMonitor, 5000);
        instrumentation.removeMonitor(notesMonitor);
        require(notes instanceof BookNotesActivity, "Cover tap no longer opens the existing notes screen");
        await("notes screen entry", () -> notes.hasWindowFocus()
                && shelf.getWindow().getDecorView().getWindowVisibility() != View.VISIBLE);
        onMain(() -> {
            BookNotesActivity screen = (BookNotesActivity) notes;
            screen.snapshot = fixture;
            screen.showLibrary();
        });
        instrumentation.waitForIdleSync();
        save(instrumentation.getUiAutomation().takeScreenshot(), "book-notes-cards.png");
        onMain(notes::finish);
        instrumentation.waitForIdleSync();
        await("shelf reload after notes", () -> shelf.snapshot != fixture);
        count++;

        prepareShelf(fixture);
        Instrumentation.ActivityMonitor readerMonitor = instrumentation.addMonitor(NoteReaderActivity.class.getName(), null, false);
        pressCover(true);
        activity = (NoteReaderActivity) instrumentation.waitForMonitorWithTimeout(readerMonitor, 5000);
        instrumentation.removeMonitor(readerMonitor);
        require(activity != null, "Cover long press did not open the reader");
        reader = activity.findViewById(R.id.note_reader);
        await("initial reader cache", () -> !context.getString(R.string.loading_library).contentEquals(reader.getContentDescription()));
        onMain(() -> {
            activity.snapshot = fixture;
            activity.showLibrary();
            View controls = (View) activity.findViewById(R.id.library_back).getParent();
            if (controls.getVisibility() == View.VISIBLE) reader.performClick();
        });
        await("reader pagination", () -> reader.pageCount() > 2);
        count++;
        onMain(() -> {
            NoteReaderDocument document = reader.document();
            StringBuilder restored = new StringBuilder();
            int expectedStart = 0;
            for (NoteReaderDocument.Page page : document.pages) {
                require(page.start == expectedStart, "Pagination skipped or duplicated text");
                restored.append(document.layout.getText().subSequence(page.start, page.end));
                expectedStart = page.end;
                if (page.end < document.layout.getText().length()) {
                    require(!Character.isHighSurrogate(document.layout.getText().charAt(page.end - 1)), "A page split an emoji");
                }
            }
            require(restored.toString().contentEquals(document.layout.getText()), "A long note was truncated");
            require(restored.indexOf("长笔记结束🙂") >= 0, "The final part of a long note disappeared");
            require(restored.indexOf("前面的原文") < restored.indexOf("后面的原文"), "Reader is sorted by creation time");
            require(restored.indexOf("第二章") > restored.indexOf("后面的原文"), "Chapter order changed");
            count++;
            checkAppearance(document);
        });
        save("reader-page.png");
        swipe(-0.55f, false);
        assertPage(1);
        save("reader-continuation.png");
        swipe(0.55f, false);
        assertPage(0);
        swipe(-0.45f, true);
        assertPage(0);
        swipe(-0.035f, false);
        assertPage(0);
        swipe(0.55f, false);
        assertPage(0); // A reader does not wrap from its first page to its last.
        onMain(() -> {
            float x = reader.getWidth() * 0.5f;
            send(MotionEvent.ACTION_DOWN, x, reader.getHeight() * 0.7f);
            send(MotionEvent.ACTION_MOVE, x, reader.getHeight() * 0.25f);
            send(MotionEvent.ACTION_UP, x, reader.getHeight() * 0.25f);
        });
        assertPage(0);
        onMain(() -> {
            send(MotionEvent.ACTION_DOWN, reader.getWidth() * 0.8f, reader.getHeight() * 0.75f);
            send(MotionEvent.ACTION_MOVE, reader.getWidth() * 0.42f, reader.getHeight() * 0.65f);
            require(reader.turnProgress() > 0.3f, "The paper did not follow the finger");
            Bitmap preview = Bitmap.createBitmap(reader.getWidth(), reader.getHeight(), Bitmap.Config.ARGB_8888);
            reader.draw(new Canvas(preview));
            save(preview, "reader-curl.png");
            send(MotionEvent.ACTION_CANCEL, reader.getWidth() * 0.42f, reader.getHeight() * 0.65f);
        });
        SystemClock.sleep(450);
        assertPage(0);
        onMain(() -> require(reader.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null),
                "Accessible next page action was rejected"));
        SystemClock.sleep(450);
        assertPage(1);
        onMain(() -> require(reader.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null),
                "Accessible previous page action was rejected"));
        SystemClock.sleep(450);
        assertPage(0);

        onMain(() -> {
            // Rebinding after a successful sync cancels an unfinished turn and retains the visible text.
            send(MotionEvent.ACTION_DOWN, reader.getWidth() * 0.8f, reader.getHeight() * 0.7f);
            send(MotionEvent.ACTION_MOVE, reader.getWidth() * 0.35f, reader.getHeight() * 0.7f);
            Bundle state = new Bundle();
            activity.onSaveInstanceState(state);
            require(state.getInt("reader_offset") == reader.selectedOffset(), "Activity did not save the reading position");
            activity.showLibrary();
            require(reader.turnProgress() == 0, "Sync left a stale page turn active");
        });
        await("pagination after sync", () -> reader.pageCount() > 2);
        assertPage(0);

        onMain(() -> {
            NoteReaderDocument document = reader.document();
            int offset = document.pages.get(document.pages.size() / 2).start;
            NoteReaderView restored = new NoteReaderView(context);
            restored.restoreOffset(offset);
            ViewGroup parent = (ViewGroup) reader.getParent();
            parent.addView(restored, new ViewGroup.LayoutParams(-1, -1));
            restored.bind("阅读测试", NoteReadingOrder.forBook(fixture, BOOK));
            restored.setTag(offset);
            reader.setTag(restored);
        });
        NoteReaderView restored = (NoteReaderView) reader.getTag();
        try {
            await("restored pagination", () -> restored.pageCount() > 0);
            onMain(() -> {
                int offset = (Integer) restored.getTag();
                NoteReaderDocument.Page page = restored.document().pages.get(restored.selectedPage());
                require(page.start <= offset && page.end > offset, "Restoring a reader lost its text position");
                // A width change must repaginate and keep the same text visible.
                restored.setTag(restored.selectedOffset());
                restored.measure(View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
                restored.layout(0, 0, 1500, 900);
            });
            await("resized pagination", () -> restored.pageCount() > 0);
            onMain(() -> {
                int offset = (Integer) restored.getTag();
                NoteReaderDocument.Page page = restored.document().pages.get(restored.selectedPage());
                require(page.start <= offset && page.end > offset, "Resizing lost the reading position");
                count++;
            });
        } finally {
            onMain(() -> { ((ViewGroup) restored.getParent()).removeView(restored); restored.dispose(); reader.setTag(null); });
        }
        NoteReaderView[] last = new NoteReaderView[1];
        onMain(() -> {
            last[0] = new NoteReaderView(context);
            last[0].restoreOffset(Integer.MAX_VALUE);
            ((ViewGroup) reader.getParent()).addView(last[0], new ViewGroup.LayoutParams(-1, -1));
            last[0].bind("阅读测试", NoteReadingOrder.forBook(fixture, BOOK));
        });
        try {
            await("last page restoration", () -> last[0].pageCount() > 0);
            onMain(() -> {
                require(last[0].selectedPage() == last[0].pageCount() - 1, "An outdated saved position did not clamp to the last page");
                require(!last[0].performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null),
                        "The final page wrapped to the first");
                count++;
            });
        } finally {
            onMain(() -> { ((ViewGroup) last[0].getParent()).removeView(last[0]); last[0].dispose(); });
        }
        onMain(() -> {
            reader.performClick();
            require(((View) activity.findViewById(R.id.library_back).getParent()).getVisibility() == View.VISIBLE,
                    "A center tap did not expose the return control");
            count++;
        });
    }

    private void prepareShelf(LibrarySnapshot fixture) throws Throwable {
        onMain(() -> { shelf.snapshot = fixture; shelf.showLibrary(); });
        await("attached shelf cover", () -> {
            GridView grid = shelf.findViewById(R.id.book_list);
            if (grid.getChildCount() != 1 || grid.getAdapter().getCount() != 1) return false;
            TextView title = grid.getChildAt(0).findViewById(R.id.book_title);
            return fixture.books.get(0).title.contentEquals(title.getText());
        });
    }

    private void pressCover(boolean hold) throws Throwable {
        float[] location = new float[2];
        onMain(() -> {
            GridView grid = shelf.findViewById(R.id.book_list);
            View cover = grid.getChildAt(0).findViewById(R.id.book_cover_frame);
            int[] origin = new int[2];
            cover.getLocationOnScreen(origin);
            location[0] = origin[0] + cover.getWidth() * 0.5f;
            location[1] = origin[1] + cover.getHeight() * 0.35f;
        });
        long start = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, location[0], location[1], 0);
        instrumentation.sendPointerSync(down);
        down.recycle();
        SystemClock.sleep(hold ? ViewConfiguration.getLongPressTimeout() + 200 : 70);
        MotionEvent up = MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, location[0], location[1], 0);
        instrumentation.sendPointerSync(up);
        up.recycle();
    }

    private LibrarySnapshot fixture() {
        List<BookNote> notes = new ArrayList<>();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) longText.append("阅读让零散的经验连成线索，也让熟悉的日常出现新的意义。这是一段需要跨页保留的笔记🙂。\n");
        notes.add(new BookNote("late", BOOK, "highlight", "第一章 阅读与生活", 1, "后面的原文", "", 1, "200-5"));
        notes.add(new BookNote("early", BOOK, "highlight", "第一章 阅读与生活", 1,
                "前面的原文：生活中的每一处细节，都值得被认真阅读。", "", 999, "10-5"));
        notes.add(new BookNote("short-thought", BOOK, "thought", "第一章 阅读与生活", 1,
                "真正重要的，不是读过多少书，而是让阅读在生活中继续。",
                "读完以后，我想每天留十分钟，回看一条划线，再记下一个能在生活里实践的小行动。", 998, "20-8"));
        notes.add(new BookNote("thought", BOOK, "thought", "第一章 阅读与生活", 1,
                "让阅读在生活中继续。", longText + "长笔记结束🙂", 998, "40-8"));
        notes.add(new BookNote("second", BOOK, "chapter_review", "第二章 把想法留下", 2, "", "把读过的文字，变成自己的思考。", 0));
        return new LibrarySnapshot(Collections.singletonList(new ShelfBook(BOOK, "阅读，是一场缓慢的相遇", "阅读测试",
                "book", false, 2, 3, 0, "", System.currentTimeMillis() / 1000)), notes, Collections.emptyList(), true);
    }

    private void checkAppearance(NoteReaderDocument document) {
        String text = document.layout.getText().toString();
        TextPaint highlight = paintAt(document, text.indexOf("前面的原文"));
        TextPaint original = paintAt(document, text.indexOf("真正重要的"));
        TextPaint thought = paintAt(document, text.indexOf("读完以后"));
        require(highlight.getTypeface().equals(original.getTypeface())
                && highlight.getTextSize() == original.getTextSize() && highlight.getColor() == original.getColor(),
                "The note's source quote does not match the highlight typography");
        require(thought.getColor() == context.getColor(R.color.reader_thought)
                && thought.getTextSize() < original.getTextSize(), "The personal thought is not visually distinct");
        require(text.contains("\n笔记\n") && !text.contains("划线想法"), "Notes are not explicitly labeled as notes");
        Bitmap first = Bitmap.createBitmap(reader.getWidth(), reader.getHeight(), Bitmap.Config.ARGB_8888);
        reader.draw(new Canvas(first));
        require(first.getPixel(5, 5) == Color.WHITE, "Reader paper is not white");
        count++;

        int plainNote = text.lastIndexOf("\n笔记\n") + 1;
        int plainThought = text.lastIndexOf("\n我的想法\n") + 1;
        int labelBottom = document.layout.getLineBottom(document.layout.getLineForOffset(plainNote));
        int bubbleTop = document.layout.getLineTop(document.layout.getLineForOffset(plainThought)) - dp(17);
        require(bubbleTop > labelBottom, "A note without a source quote overlaps its category label");
        count++;

        int middle = document.pages.size() / 2;
        NoteReaderDocument.Page page = document.pages.get(middle);
        Bitmap continuation = Bitmap.createBitmap(reader.getWidth(), reader.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(continuation);
        canvas.drawColor(Color.WHITE);
        int width = reader.getWidth() - reader.getPaddingLeft() - reader.getPaddingRight() - dp(56);
        int height = reader.getHeight() - reader.getPaddingTop() - reader.getPaddingBottom() - dp(120);
        int left = reader.getPaddingLeft() + dp(28);
        int top = reader.getPaddingTop() + dp(64);
        document.drawPage(canvas, page, left, top, width, height);
        int edge = continuation.getPixel(left + dp(29), top + height / 2);
        int outline = context.getColor(R.color.reader_bubble_outline);
        require(Math.abs(Color.red(edge) - Color.red(outline)) < 10
                && Math.abs(Color.green(edge) - Color.green(outline)) < 10, "A continued thought lost its bubble border");
        require(paintAt(document, page.start).getColor() == thought.getColor(), "A continued thought lost its text style");
        save(continuation, "reader-bubble-continuation.png");
        canvas.drawColor(Color.WHITE);
        document.drawPage(canvas, document.pages.get(document.pageForOffset(plainNote)), left, top, width, height);
        save(continuation, "reader-text-only-note.png");
        count++;
    }

    private TextPaint paintAt(NoteReaderDocument document, int offset) {
        require(offset >= 0, "Styled test text is missing");
        TextPaint paint = new TextPaint(document.layout.getPaint());
        Spanned text = (Spanned) document.layout.getText();
        for (CharacterStyle span : text.getSpans(offset, offset + 1, CharacterStyle.class)) span.updateDrawState(paint);
        return paint;
    }

    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private void swipe(float fraction, boolean cancel) throws Throwable {
        onMain(() -> {
            float x = reader.getWidth() * 0.75f;
            float y = reader.getHeight() * 0.7f;
            send(MotionEvent.ACTION_DOWN, x, y);
            send(MotionEvent.ACTION_MOVE, x + reader.getWidth() * fraction, y);
            send(cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, x + reader.getWidth() * fraction, y);
        });
        SystemClock.sleep(450);
    }

    private void send(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) { downTime = SystemClock.uptimeMillis(); eventTime = downTime; }
        else eventTime += 300;
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        reader.dispatchTouchEvent(event);
        event.recycle();
    }

    private void assertPage(int expected) throws Throwable {
        onMain(() -> { require(reader.selectedPage() == expected, "Expected page " + expected + ", got " + reader.selectedPage()); count++; });
    }

    private void save(String name) throws Throwable {
        onMain(() -> {
            Bitmap screenshot = Bitmap.createBitmap(reader.getWidth(), reader.getHeight(), Bitmap.Config.ARGB_8888);
            reader.draw(new Canvas(screenshot));
            save(screenshot, name);
        });
    }

    private void save(Bitmap bitmap, String name) {
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Could not save reader preview");
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private interface Condition { boolean ready(); }

    private void await(String stage, Condition condition) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean[] ready = {false};
        while (SystemClock.uptimeMillis() < deadline) {
            onMain(() -> ready[0] = condition.ready());
            if (ready[0]) return;
            SystemClock.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + stage);
    }

    private void onMain(Runnable action) throws Throwable {
        Throwable[] error = new Throwable[1];
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw error[0];
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
