package com.wenotes.app;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Measures the active card naturally and raises the actual next quote underneath it. */
public final class QuoteStackLayout extends FrameLayout {
    private final float density;
    private QuoteCardLayout front;
    private View next;
    private View back;
    private int cardCount;
    private int nextNaturalHeight;
    private int naturalWidth = -1;
    private boolean contentChanged = true;
    private float progress;
    private ValueAnimator progressAnimator;

    public QuoteStackLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        front = findViewById(R.id.module_quote);
        next = findViewById(R.id.quote_next_card);
        back = findViewById(R.id.quote_stack_back);
        front.setStack(this);
    }

    void setCardCount(int count) {
        if (cardCount == count) return;
        cardCount = count;
        next.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        back.setVisibility(count > 2 ? View.VISIBLE : View.GONE);
        if (count <= 1) resetStack();
        requestLayout();
    }

    boolean hasNextCard() {
        return cardCount > 1;
    }

    void contentChanged() {
        contentChanged = true;
        requestLayout();
    }

    void setDragProgress(float value) {
        cancelProgressAnimation();
        updateProgress(value);
    }

    void animateProgress(float target, long duration, TimeInterpolator interpolator) {
        cancelProgressAnimation();
        if (!hasNextCard()) return;
        ValueAnimator animator = ValueAnimator.ofFloat(progress, target);
        progressAnimator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(value -> {
            if (progressAnimator == animator) updateProgress((float) value.getAnimatedValue());
        });
        animator.start();
    }

    void resetStack() {
        cancelProgressAnimation();
        updateProgress(0);
    }

    private void cancelProgressAnimation() {
        if (progressAnimator == null) return;
        ValueAnimator animator = progressAnimator;
        progressAnimator = null;
        animator.cancel();
    }

    private void updateProgress(float value) {
        progress = Math.max(0, Math.min(1, value));
        applyLayers();
        requestLayout();
    }

    private void applyLayers() {
        if (next == null || back == null) return;
        next.setPivotX(next.getMeasuredWidth() / 2f);
        next.setPivotY(front.getMeasuredHeight());
        next.setScaleX(0.95f + 0.05f * progress);
        next.setScaleY(0.95f + 0.05f * progress);
        next.setTranslationY(12 * density * (1 - progress));
        next.setAlpha(0.86f + 0.14f * progress);
        back.setPivotX(back.getMeasuredWidth() / 2f);
        back.setPivotY(front.getMeasuredHeight());
        back.setScaleX(0.90f + 0.05f * progress);
        back.setScaleY(0.90f + 0.05f * progress);
        back.setTranslationY((24 - 12 * progress) * density);
        back.setAlpha(0.68f + 0.12f * progress);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (front == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        measureChildWithMargins(front, widthMeasureSpec, 0, heightMeasureSpec, 0);
        int width = front.getMeasuredWidth();
        int frontHeight = front.getMeasuredHeight();
        int layerHeight = frontHeight;
        if (hasNextCard()) {
            int exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            if (contentChanged || naturalWidth != width) {
                next.measure(exactWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                nextNaturalHeight = next.getMeasuredHeight();
                naturalWidth = width;
                contentChanged = false;
            }
            layerHeight = Math.round(frontHeight + (nextNaturalHeight - frontHeight) * progress);
            int exactHeight = MeasureSpec.makeMeasureSpec(layerHeight, MeasureSpec.EXACTLY);
            next.measure(exactWidth, exactHeight);
            back.measure(exactWidth, exactHeight);
        }
        int tail = hasNextCard() ? Math.round((cardCount > 2 ? 24 : 12) * density) : 0;
        // Follow the transition in both directions while the outgoing card keeps its natural size.
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(layerHeight + tail, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (front == null) return;
        front.layout(0, 0, front.getMeasuredWidth(), front.getMeasuredHeight());
        if (hasNextCard()) {
            next.layout(0, 0, next.getMeasuredWidth(), next.getMeasuredHeight());
            back.layout(0, 0, back.getMeasuredWidth(), back.getMeasuredHeight());
        }
        applyLayers();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelProgressAnimation();
        super.onDetachedFromWindow();
    }
}
