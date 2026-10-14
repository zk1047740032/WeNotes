package com.wenotes.app;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

/** Exercises the shared stack and its reading content without changing the persisted cache. */
final class ReadingCardsChecks {
    private final Instrumentation instrumentation;
    private ReadingCardsView cards;
    private QuoteStackLayout stack;
    private QuoteCardLayout front;
    private View next;
    private int count;
    private long downTime;

    private ReadingCardsChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    static int run(Instrumentation instrumentation) throws Throwable {
        ReadingCardsChecks checks = new ReadingCardsChecks(instrumentation);
        checks.onMain(() -> {
            Context context = instrumentation.getTargetContext();
            checks.cards = new ReadingCardsView(context, null);
            new FrameLayout(context).addView(checks.cards);
            checks.cards.bind(LibrarySnapshot.empty(), null);
            checks.stack = (QuoteStackLayout) checks.cards.getChildAt(0);
            checks.front = (QuoteCardLayout) checks.stack.getChildAt(2);
            checks.next = checks.stack.getChildAt(1);
            checks.layout();
            require(checks.cards.selectedPage() == 0, "Rhythm is not the default card");
            checks.checkHeightTransition(false);
        });
        checks.swipe(-400, 40, false);
        checks.assertPage(1);
        checks.swipe(-400, 0, false);
        checks.assertPage(2);
        checks.onMain(() -> checks.checkHeightTransition(true));
        checks.swipe(-400, 0, false);
        checks.assertPage(0); // Both swipe directions reveal the next card, as on the quote stack.
        checks.swipe(400, 0, true);
        checks.assertPage(0);
        checks.swipe(-30, 0, false);
        checks.assertPage(0);
        checks.swipe(0, -400, false);
        checks.assertPage(0);
        checks.swipe(400, 0, false);
        checks.assertPage(1);
        checks.onMain(() -> {
            Bundle state = new Bundle();
            checks.cards.saveSelection(state);
            ReadingCardsView restored = new ReadingCardsView(instrumentation.getTargetContext(), null);
            restored.restoreSelection(state);
            restored.bind(LibrarySnapshot.empty(), null);
            require(restored.selectedPage() == 1, "Restoration lost selected card");
            QuoteStackLayout restoredStack = (QuoteStackLayout) restored.getChildAt(0);
            require(restoredStack.getChildAt(1).getImportantForAccessibility()
                    == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, "Hidden card is accessible");
            checks.count++;
            checks.cards.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null);
        });
        SystemClock.sleep(400);
        checks.assertPage(2);
        checks.onMain(() -> {
            checks.send(MotionEvent.ACTION_DOWN, 600, 300);
            checks.send(MotionEvent.ACTION_MOVE, 200, 300);
            checks.send(MotionEvent.ACTION_UP, 200, 300);
            checks.cards.resetSwipe();
        });
        SystemClock.sleep(400);
        checks.assertPage(2);
        checks.onMain(() -> {
            checks.send(MotionEvent.ACTION_DOWN, 600, 300);
            checks.send(MotionEvent.ACTION_MOVE, 200, 300);
            checks.cards.bind(LibrarySnapshot.empty(), null);
        });
        checks.assertPage(2);
        return checks.count;
    }

    private void checkHeightTransition(boolean growing) {
        stack.setDragProgress(0);
        layout();
        int start = cards.getMeasuredHeight();
        int frontHeight = front.getHeight();
        stack.setDragProgress(1);
        layout();
        int end = cards.getMeasuredHeight();
        require(growing ? end > start : end < start, "Fixture did not change card height");
        for (float progress : new float[]{0, 0.25f, 0.5f, 0.75f, 1, 0.5f, 0}) {
            stack.setDragProgress(progress);
            layout();
            int expected = Math.round(start + (end - start) * progress);
            require(Math.abs(cards.getMeasuredHeight() - expected) <= 1, "Reading stack height jumped");
            require(front.getHeight() == frontHeight, "Outgoing card lost its natural height");
            require(Math.abs(next.getScaleX() - (0.95f + 0.05f * progress)) < 0.001f,
                    "Next card did not scale with the quote animation");
        }
        stack.resetStack();
        count++;
    }

    private void swipe(float dx, float dy, boolean cancel) throws Throwable {
        onMain(() -> {
            send(MotionEvent.ACTION_DOWN, 450, 450);
            send(MotionEvent.ACTION_MOVE, 450 + dx, 450 + dy);
            if (Math.abs(dx) >= 400) {
                layout();
                require(Math.abs(front.getRotation()) > 0, "Front card did not tilt");
                require(front.getScaleX() < 1, "Front card did not follow the quote scale");
                require(next.getScaleX() > 0.95f && next.getTranslationX() == 0,
                        "Next card slid sideways instead of rising underneath");
            }
            send(cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, 450 + dx, 450 + dy);
        });
        SystemClock.sleep(400);
    }

    private void send(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent event = MotionEvent.obtain(downTime, now, action, x, y, 0);
        cards.dispatchTouchEvent(event);
        event.recycle();
    }

    private void assertPage(int expected) throws Throwable {
        onMain(() -> {
            layout();
            require(cards.selectedPage() == expected, "Expected card " + expected + ", got " + cards.selectedPage());
            require(front.getTranslationX() == 0 && front.getTranslationY() == 0 && front.getRotation() == 0,
                    "Settled card is not aligned");
            require(front.getScaleX() == 1 && Math.abs(next.getScaleX() - 0.95f) < 0.001f,
                    "Resting stack scales changed");
            count++;
        });
    }

    private void layout() {
        cards.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        cards.layout(0, 0, cards.getMeasuredWidth(), cards.getMeasuredHeight());
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
