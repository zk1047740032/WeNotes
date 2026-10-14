package com.wenotes.app;

import android.app.Instrumentation;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

/** Checks how the real home layout positions statistics throughout a card transition. */
final class QuoteStackChecks {
    private final Context context;
    private int count;

    private QuoteStackChecks(Context context) { this.context = context; }

    static int run(Instrumentation instrumentation) throws Throwable {
        QuoteStackChecks checks = new QuoteStackChecks(instrumentation.getTargetContext());
        Throwable[] error = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                for (int cards : new int[]{2, 3}) {
                    checks.checkTransition(cards, 600, 300);
                    checks.checkTransition(cards, 300, 600);
                }
                checks.checkTransition(3, 400, 400);
            } catch (Throwable failure) {
                error[0] = failure;
            }
        });
        if (error[0] != null) throw error[0];
        return checks.count;
    }

    private void checkTransition(int cards, int fromDp, int toDp) {
        View home = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
        QuoteStackLayout stack = home.findViewById(R.id.quote_stack);
        View front = home.findViewById(R.id.module_quote);
        View next = home.findViewById(R.id.quote_next_card);
        View statistics = home.findViewById(R.id.reading_cards);
        front.setMinimumHeight(dp(fromDp));
        next.setMinimumHeight(dp(toDp));
        stack.setCardCount(cards);
        layout(home);
        int originalTop = statistics.getTop();
        int originalCardHeight = front.getHeight();
        stack.setDragProgress(1);
        layout(home);
        int nextCardHeight = next.getHeight();
        int targetTop = originalTop + nextCardHeight - originalCardHeight;

        for (float progress : new float[]{0, 0.25f, 0.5f, 0.75f, 1, 0.75f, 0.5f, 0.25f, 0}) {
            stack.setDragProgress(progress);
            layout(home);
            int expectedTop = Math.round(originalTop + (targetTop - originalTop) * progress);
            require(Math.abs(statistics.getTop() - expectedTop) <= 1,
                    fromDp + " -> " + toDp + " at " + progress + ": statistics top "
                            + statistics.getTop() + ", expected " + expectedTop);
            require(front.getHeight() == originalCardHeight, "Outgoing card changed its natural height");
        }

        // Committing a swipe must preserve the final position reached by the animation.
        stack.setDragProgress(1);
        layout(home);
        int beforeCommit = statistics.getTop();
        front.setMinimumHeight(dp(toDp));
        stack.contentChanged();
        stack.resetStack();
        layout(home);
        require(statistics.getTop() == beforeCommit, "Statistics jumped when the next card became active");
        count++;
    }

    private void layout(View home) {
        home.measure(View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(1600), View.MeasureSpec.EXACTLY));
        home.layout(0, 0, home.getMeasuredWidth(), home.getMeasuredHeight());
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
