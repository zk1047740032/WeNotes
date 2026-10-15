package com.wenotes.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Uses an in-memory fixture; never writes the user's library. */
final class ReadingTimelineChecks {
    static int run(Instrumentation instrumentation) throws Throwable {
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ReadingCardsView[] cards = new ReadingCardsView[1];
        QuoteCardLayout[] front = new QuoteCardLayout[1];
        try {
            onMain(instrumentation, () -> {
                List<BookNote> notes = new ArrayList<>();
                for (int i = 1; i <= 35; i++) notes.add(new BookNote("note-" + i, "book", "highlight", "", 0,
                        "第" + i + "条笔记。这是一段用于检查展开后完整显示与上下滚动的文字。\n"
                                + "长按卡片，阅读最近的笔记，保留书中的思考和日常阅读足迹。\n第三行文字也应完整显示。", "", i));
                cards[0] = activity.findViewById(R.id.reading_cards);
                Bundle state = new Bundle();
                state.putInt("reading_cards_page", 2);
                cards[0].restoreSelection(state);
                cards[0].bind(new LibrarySnapshot(Collections.singletonList(new ShelfBook("book", "人类大历史",
                        "尤瓦尔·赫拉利", "book", false, 35, 0, 0)), notes, Collections.emptyList(), true), null);
                QuoteStackLayout stack = (QuoteStackLayout) cards[0].getChildAt(0);
                front[0] = (QuoteCardLayout) stack.getChildAt(2);
                ((ScrollView) activity.findViewById(R.id.home_scroll)).fullScroll(View.FOCUS_DOWN);
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(350);
            long down = SystemClock.uptimeMillis();
            touch(instrumentation, front[0], down, MotionEvent.ACTION_DOWN, 60, 80);
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150);
            touch(instrumentation, front[0], down, MotionEvent.ACTION_UP, 60, 80);
            SystemClock.sleep(450);
            ScrollView[] expanded = new ScrollView[1];
            Field field = ReadingCardsView.class.getDeclaredField("expandedCard");
            field.setAccessible(true);
            ReadingCardDialog dialog = (ReadingCardDialog) field.get(cards[0]);
            require(dialog != null && dialog.isShowing(), "Long press did not open timeline");
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            try (FileOutputStream output = new FileOutputStream(new File(
                    activity.getExternalFilesDir(null), "reading-timeline-expanded.png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            } finally { screenshot.recycle(); }
            onMain(instrumentation, () -> {
                FrameLayout windowContent = dialog.getWindow().getDecorView().findViewById(android.R.id.content);
                FrameLayout overlay = (FrameLayout) windowContent.getChildAt(0);
                expanded[0] = (ScrollView) overlay.getChildAt(0);
                require(expanded[0].getWidth() < overlay.getWidth(), "Card fills screen width");
                require(expanded[0].getHeight() < overlay.getHeight() * 0.85f, "Card fills screen height");
                require(expanded[0].getTop() > 0, "No top margin");
                LinearLayout content = (LinearLayout) expanded[0].getChildAt(0);
                require(content.getChildCount() == 30, "Expanded card did not show thirty notes");
                LinearLayout first = (LinearLayout) ((LinearLayout) content.getChildAt(0)).getChildAt(1);
                TextView preview = (TextView) first.getChildAt(2);
                require(preview.getLineCount() > 2, "Expanded note is truncated");
            });
            long scrollDown = SystemClock.uptimeMillis();
            touch(instrumentation, expanded[0], scrollDown, MotionEvent.ACTION_DOWN, 80, 500);
            touch(instrumentation, expanded[0], scrollDown, MotionEvent.ACTION_MOVE, 80, 100);
            touch(instrumentation, expanded[0], scrollDown, MotionEvent.ACTION_UP, 80, 100);
            instrumentation.waitForIdleSync();
            onMain(instrumentation, () -> {
                require(expanded[0].getScrollY() > 0, "Vertical swipe did not scroll timeline");
                expanded[0].fling(0);
                expanded[0].scrollTo(0, expanded[0].getChildAt(0).getHeight());
                require(!expanded[0].canScrollVertically(1), "Cannot reach last note");
                expanded[0].scrollTo(0, 0);
                require(expanded[0].getScrollY() == 0, "Timeline cannot scroll up");
                ((View) expanded[0].getParent()).performClick();
            });
            SystemClock.sleep(300);
            onMain(instrumentation, () -> {
                require(!dialog.isShowing(), "Outside tap did not dismiss card");
                require(front[0].getVisibility() == View.VISIBLE, "Dismiss left home card hidden");
                require(cards[0].selectedPage() == 2, "Dismiss changed card selection");
                require(front[0].performLongClick(), "Cannot reopen timeline");
            });
            SystemClock.sleep(350);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(300);
            onMain(instrumentation, () -> require(front[0].getVisibility() == View.VISIBLE,
                    "Back did not restore home card"));
            return 10;
        } finally {
            onMain(instrumentation, activity::finish);
        }
    }

    private static void touch(Instrumentation instrumentation, View target, long down, int action, float x, float y) throws Throwable {
        onMain(instrumentation, () -> {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            target.dispatchTouchEvent(event);
            event.recycle();
        });
    }

    private static void onMain(Instrumentation instrumentation, Runnable action) throws Throwable {
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
