package com.wenotes.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies the real long-press UI using temporary data, without replacing the persisted library. */
final class ReadingComparisonChecks {
    static int run(Instrumentation instrumentation) throws Throwable {
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ReadingCardsView[] cards = new ReadingCardsView[1];
        QuoteCardLayout[] front = new QuoteCardLayout[1];
        LocalDate today = LocalDate.now();
        LocalDate monday = ReadingStatistics.weekStart(today);
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        int[] current = {45, 65, 90, 50, 40, 70, 35}, previous = {35, 55, 70, 40, 500, 500, 500};
        for (int i = 0; i < 7; i++) {
            if (i < today.getDayOfWeek().getValue()) days.put(monday.plusDays(i), current[i] * 60L);
            days.put(monday.minusWeeks(1).plusDays(i), previous[i] * 60L);
        }
        try {
            onMain(instrumentation, () -> {
                cards[0] = activity.findViewById(R.id.reading_cards);
                cards[0].bind(LibrarySnapshot.empty().withReadingStatistics(new ReadingStatistics(days, 0)), null);
                front[0] = (QuoteCardLayout) ((QuoteStackLayout) cards[0].getChildAt(0)).getChildAt(2);
                ((ScrollView) activity.findViewById(R.id.home_scroll)).fullScroll(View.FOCUS_DOWN);
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(250);
            long down = SystemClock.uptimeMillis();
            onMain(instrumentation, () -> touch(front[0], down, MotionEvent.ACTION_DOWN));
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100);
            onMain(instrumentation, () -> touch(front[0], down, MotionEvent.ACTION_UP));
            SystemClock.sleep(400);
            ReadingCardDialog dialog = expanded(cards[0]);
            require(dialog != null && dialog.isShowing(), "Long press did not expand statistics");
            onMain(instrumentation, () -> {
                FrameLayout content = dialog.getWindow().getDecorView().findViewById(android.R.id.content);
                FrameLayout overlay = (FrameLayout) content.getChildAt(0);
                ScrollView card = (ScrollView) overlay.getChildAt(0);
                require(card.getWidth() < overlay.getWidth() && card.getHeight() < overlay.getHeight() * 0.85f,
                        "Statistics card fills the screen");
                require(overlay.getBackground() instanceof BitmapDrawable, "Frosted background is missing");
                require((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0,
                        "Dark dim overlay returned");
                String text = textOf(card);
                require(text.contains("本周同期") && text.contains("上周同期") && text.contains("%"),
                        "Comparison details are missing");
                require(findChart(card) != null, "Paired chart is missing");
                require(front[0].getVisibility() == View.INVISIBLE, "Original card is duplicated");
            });
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            try (FileOutputStream output = new FileOutputStream(new File(activity.getExternalFilesDir(null),
                    "reading-comparison-expanded.png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            } finally { screenshot.recycle(); }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(300);
            onMain(instrumentation, () -> {
                require(front[0].getVisibility() == View.VISIBLE && cards[0].selectedPage() == 0,
                        "Back did not restore statistics card");
                Map<LocalDate, Long> oldCache = new LinkedHashMap<>(days);
                oldCache.entrySet().removeIf(entry -> entry.getKey().isBefore(monday));
                cards[0].bind(LibrarySnapshot.empty().withReadingStatistics(new ReadingStatistics(oldCache, 0)), null);
                require(front[0].performLongClick(), "Cannot reopen statistics card with old cache");
            });
            SystemClock.sleep(350);
            ReadingCardDialog missing = expanded(cards[0]);
            onMain(instrumentation, () -> {
                String text = textOf(missing.getWindow().getDecorView());
                require(text.contains("待同步") && !text.contains("%"), "Old cache fabricated a percentage");
                cards[0].bind(LibrarySnapshot.empty(), null);
                require(!missing.isShowing() && front[0].getVisibility() == View.VISIBLE,
                        "Data refresh left the old dialog or a hidden card");
                require(front[0].performLongClick(), "Empty statistics card cannot expand");
            });
            SystemClock.sleep(350);
            ReadingCardDialog empty = expanded(cards[0]);
            onMain(instrumentation, () -> {
                require(textOf(empty.getWindow().getDecorView()).contains("待同步"), "Empty state is missing");
                FrameLayout content = empty.getWindow().getDecorView().findViewById(android.R.id.content);
                content.getChildAt(0).performClick();
            });
            SystemClock.sleep(300);
            require(!empty.isShowing(), "Outside tap did not close comparison");
            return 10;
        } finally {
            onMain(instrumentation, activity::finish);
        }
    }

    private static ReadingCardDialog expanded(ReadingCardsView cards) throws Exception {
        Field field = ReadingCardsView.class.getDeclaredField("expandedCard");
        field.setAccessible(true);
        return (ReadingCardDialog) field.get(cards);
    }

    private static String textOf(View view) {
        if (view instanceof TextView) return ((TextView) view).getText().toString() + "\n";
        StringBuilder text = new StringBuilder();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            text.append(textOf(((ViewGroup) view).getChildAt(i)));
        }
        return text.toString();
    }

    private static View findChart(View view) {
        if (view instanceof ReadingComparisonChart) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View chart = findChart(((ViewGroup) view).getChildAt(i));
            if (chart != null) return chart;
        }
        return null;
    }

    private static void touch(View target, long down, int action) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 60, 80, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
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
