package com.wenotes.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

/** Lets the card follow and tilt with a swipe, including swipes on selectable text. */
public final class QuoteCardLayout extends LinearLayout {
    interface SwipeListener {
        boolean onBeforeSwipe(boolean left);
        default void onSwipeDirectionChanged(boolean left) {}
        void onAdvance(boolean left);
    }

    private final int touchSlop;
    private final float swipeDistance;
    private final float density;
    private SwipeListener swipeListener;
    private QuoteStackLayout stack;
    private float downX;
    private float downY;
    private boolean tracking;
    private boolean swiping;
    private boolean swipeEnabled = true;
    private boolean settling;
    private int animationGeneration;
    private ValueAnimator favoriteSweep;

    public QuoteCardLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        density = getResources().getDisplayMetrics().density;
        swipeDistance = Math.max(touchSlop * 2, 48 * density);
        setClickable(true);
    }

    void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    void setStack(QuoteStackLayout stack) {
        this.stack = stack;
    }

    void setSwipeEnabled(boolean enabled) {
        if (swipeEnabled == enabled) return;
        swipeEnabled = enabled;
        if (!enabled) resetSwipe();
    }

    void playFavoriteSweep() {
        cancelFavoriteSweep();
        if (!ValueAnimator.areAnimatorsEnabled()
                || !(getBackground() instanceof QuoteCardBackground)) return;
        QuoteCardBackground background = (QuoteCardBackground) getBackground();
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        favoriteSweep = animator;
        animator.setDuration(460);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> background.setFavoriteSweep((float) value.getAnimatedValue()));
        animator.start();
    }

    private void cancelFavoriteSweep() {
        if (favoriteSweep != null) {
            favoriteSweep.cancel();
            favoriteSweep = null;
        }
        if (getBackground() instanceof QuoteCardBackground) {
            ((QuoteCardBackground) getBackground()).setFavoriteSweep(-1);
        }
    }

    boolean performSwipe(boolean left) {
        if (!swipeEnabled || settling || swipeListener == null) return false;
        settling = true;
        int generation = ++animationGeneration;
        if (!swipeListener.onBeforeSwipe(left)) {
            if (generation == animationGeneration) animateHome(generation);
            return false;
        }
        if (generation != animationGeneration) return false;
        if (stack != null) {
            float screenWidth = Math.max(getWidth(), getRootView().getWidth());
            // A tilted, tall card extends farther sideways than its unrotated bounds.
            float distance = screenWidth + (float) (getHeight() * Math.sin(Math.toRadians(14)) / 2);
            float exitY = getTranslationY() * distance
                    / Math.max(swipeDistance, Math.abs(getTranslationX()));
            exitY = Math.max(-160 * density, Math.min(160 * density, exitY));
            long duration = favoriteSweep != null ? 300 : 220;
            long delay = favoriteSweep != null ? 160 : 0;
            animate().translationX(left ? -distance : distance).translationY(exitY).rotation(left ? -14 : 14)
                    .translationZ(0).alpha(1).setDuration(duration).setStartDelay(delay)
                    .setInterpolator(new DecelerateInterpolator())
                    .withStartAction(() -> stack.animateProgress(1, duration, new DecelerateInterpolator()))
                    .withEndAction(() -> {
                        if (generation != animationGeneration) return;
                        stack.setDragProgress(1);
                        swipeListener.onAdvance(left);
                        if (generation != animationGeneration) return;
                        finishAnimation(generation);
                    }).start();
        } else {
            swipeListener.onAdvance(left);
            if (generation == animationGeneration) animateHome(generation);
        }
        return true;
    }

    private void animateHome(int generation) {
        cancelFavoriteSweep();
        if (stack != null) stack.animateProgress(0, 280, new OvershootInterpolator(0.75f));
        animate().translationX(0).translationY(0).rotation(0)
                .scaleX(1).scaleY(1).translationZ(0).alpha(1).setDuration(280).setStartDelay(0)
                .setInterpolator(new OvershootInterpolator(0.75f))
                .withEndAction(() -> finishAnimation(generation)).start();
    }

    private void finishAnimation(int generation) {
        if (generation != animationGeneration) return;
        cancelFavoriteSweep();
        resetPose();
        if (stack != null) stack.resetStack();
        settling = false;
    }

    private void resetPose() {
        setTranslationX(0);
        setTranslationY(0);
        setRotation(0);
        setScaleX(1);
        setScaleY(1);
        setTranslationZ(0);
        setAlpha(1);
    }

    void resetSwipe() {
        animationGeneration++;
        settling = false;
        tracking = false;
        swiping = false;
        animate().cancel();
        animate().setStartDelay(0).withStartAction(null);
        animate().withEndAction(null);
        cancelFavoriteSweep();
        resetPose();
        if (stack != null) stack.resetStack();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        resetSwipe();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (settling) return true;
        if (!swipeEnabled) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX();
            downY = event.getRawY();
            tracking = true;
            swiping = false;
            // Decide the direction before the surrounding ScrollView takes the gesture.
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            tracking = false;
            getParent().requestDisallowInterceptTouchEvent(false);
        } else if (action == MotionEvent.ACTION_MOVE && tracking && !swiping) {
            float dx = Math.abs(event.getRawX() - downX);
            float dy = Math.abs(event.getRawY() - downY);
            if (event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
                // Leave long-press text selection and its handles to the TextView.
                tracking = false;
            } else if (dy > touchSlop && dy >= dx) {
                tracking = false;
                getParent().requestDisallowInterceptTouchEvent(false);
            } else if (dx > touchSlop && dx > dy) {
                swiping = true;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        }

        if (tracking && swiping && action == MotionEvent.ACTION_MOVE) {
            // Screen coordinates remain stable while this view moves under the finger.
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            if (swipeListener != null) swipeListener.onSwipeDirectionChanged(dx < 0);
            float progress = Math.min(1, Math.abs(dx) / Math.max(1, getWidth()));
            float verticalLimit = Math.min(getHeight() * 0.3f, 120 * density);
            setTranslationX(dx);
            setTranslationY(Math.max(-verticalLimit, Math.min(verticalLimit, dy)));
            setRotation(14 * Math.max(-1, Math.min(1, dx / Math.max(1, getWidth()))));
            setScaleX(1 - 0.015f * progress);
            setScaleY(1 - 0.015f * progress);
            setTranslationZ(4 * density * progress);
            setAlpha(1);
            if (stack != null) stack.setDragProgress(Math.min(1, progress / 0.65f));
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean consumed = swiping;
            boolean complete = tracking && swiping && action == MotionEvent.ACTION_UP
                    && Math.abs(event.getRawX() - downX) >= swipeDistance
                    && Math.abs(event.getRawX() - downX) > Math.abs(event.getRawY() - downY);
            tracking = false;
            swiping = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (complete) {
                performSwipe(event.getRawX() < downX);
            } else if (consumed) {
                settling = true;
                animateHome(++animationGeneration);
            }
            return consumed || super.dispatchTouchEvent(event);
        }
        return swiping || super.dispatchTouchEvent(event);
    }
}
