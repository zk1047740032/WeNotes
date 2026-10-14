package com.wenotes.app;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.time.Instant;
import java.time.LocalDate;

/** Reading cards use the same stack, gesture handling and animations as the quote card. */
public final class ReadingCardsView extends FrameLayout {
    private static final int[] TITLES = {R.string.reading_rhythm_title, R.string.reading_recent_title,
            R.string.reading_timeline_title};
    private final ReadingCardContent[] pages = new ReadingCardContent[3];
    private final QuoteStackLayout stack;
    private final QuoteCardLayout front;
    private final LinearLayout next;
    private LibrarySnapshot library;
    private LocalDate today;
    private int selectedPage;

    public ReadingCardsView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        stack = new QuoteStackLayout(context, null);
        addView(stack, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View back = new View(context);
        back.setBackgroundResource(R.drawable.quote_stack_background);
        back.setElevation(dp(1));
        back.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        stack.addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        next = new LinearLayout(context);
        next.setOrientation(LinearLayout.VERTICAL);
        next.setBackgroundResource(R.drawable.card_background);
        next.setElevation(dp(2));
        next.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        next.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        stack.addView(next, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        front = new QuoteCardLayout(context, null);
        front.setOrientation(LinearLayout.VERTICAL);
        front.setBackgroundResource(R.drawable.card_background);
        front.setElevation(dp(6));
        front.setFocusable(true);
        front.setSwipeEnabled(false);
        stack.addView(front, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        stack.setCards(front, next, back);
        stack.setCardCount(pages.length);
        front.setSwipeListener(new QuoteCardLayout.SwipeListener() {
            @Override public boolean onBeforeSwipe(boolean left) { return library != null; }

            @Override public void onAdvance() {
                selectedPage = (selectedPage + 1) % pages.length;
                showSelection();
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            }
        });
        showSelection();
    }

    void bind(LibrarySnapshot snapshot, CoverLoader covers) {
        LocalDate current = LocalDate.now();
        if (snapshot == library && current.equals(today)) return;
        resetSwipe();
        library = snapshot;
        today = current;
        long now = Instant.now().getEpochSecond();
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new ReadingCardContent(getContext(), i, snapshot, now, covers);
            pages[i].setMinimumHeight(dp(240));
            addIndicator(pages[i], i);
        }
        showSelection();
        front.setSwipeEnabled(true);
    }

    void refreshCovers() { if (pages[1] != null) pages[1].refreshCovers(); }

    void saveSelection(Bundle state) { state.putInt("reading_cards_page", selectedPage); }

    void restoreSelection(Bundle state) {
        resetSwipe();
        if (state != null) selectedPage = Math.max(0, Math.min(2, state.getInt("reading_cards_page", 0)));
        showSelection();
    }

    int selectedPage() { return selectedPage; }

    void resetSwipe() { front.resetSwipe(); }

    private void showSelection() {
        front.removeAllViews();
        next.removeAllViews();
        if (pages[selectedPage] != null) {
            front.addView(pages[selectedPage]);
            next.addView(pages[(selectedPage + 1) % pages.length]);
        }
        setContentDescription(getContext().getString(R.string.reading_card_position,
                getContext().getString(TITLES[selectedPage]), selectedPage + 1, pages.length));
        stack.contentChanged();
    }

    private void addIndicator(ReadingCardContent page, int selected) {
        LinearLayout indicator = new LinearLayout(getContext());
        indicator.setGravity(Gravity.CENTER);
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i = 0; i < pages.length; i++) {
            View dot = new View(getContext());
            dot.setBackground(ReadingCardContent.background(getContext(),
                    i == selected ? R.color.primary : R.color.outline, 3));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(6), dp(6));
            params.setMarginStart(dp(4));
            params.setMarginEnd(dp(4));
            indicator.addView(dot, params);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(6));
        params.topMargin = dp(16);
        page.addView(indicator, params);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth > 0 && oldWidth != width) resetSwipe();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (library == null) return;
        info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setItemCount(pages.length);
        event.setFromIndex(selectedPage);
        event.setToIndex(selectedPage);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return front.performSwipe(true);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return front.performSwipe(false);
        return super.performAccessibilityAction(action, arguments);
    }

    @Override protected void onDetachedFromWindow() {
        resetSwipe();
        super.onDetachedFromWindow();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
