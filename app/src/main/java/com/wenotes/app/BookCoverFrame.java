package com.wenotes.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

/** Keeps covers in book proportions as grid columns resize. */
public final class BookCoverFrame extends FrameLayout {
    private final GestureDetector gestures;
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rimBounds = new RectF();
    private final float density;
    private String bookId = "";
    private String favoriteActionLabel;
    private Runnable toggleFavorite;

    public BookCoverFrame(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        rim.setStyle(Paint.Style.STROKE);
        setClipToOutline(true);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                performClick();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) { return true; }

            @Override public boolean onDoubleTapEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP && toggleFavorite != null) {
                    toggleFavorite.run();
                }
                return true;
            }

            @Override public void onLongPress(MotionEvent event) {
                setPressed(false);
                performLongClick();
            }
        });
    }

    void bindFavorite(String id, boolean saved, String actionLabel, Runnable toggle) {
        if (!bookId.equals(id)) cancelGestures();
        bookId = id;
        favoriteActionLabel = actionLabel;
        toggleFavorite = toggle;
        setActivated(saved);
        int inset = saved ? Math.round(6 * density) : 0;
        if (getPaddingLeft() != inset) setPadding(inset, inset, inset, inset);
        invalidate();
    }

    // GestureDetector confirms single taps before calling performClick above.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        setPressed(event.getActionMasked() == MotionEvent.ACTION_DOWN);
        gestures.onTouchEvent(event);
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void cancelGestures() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        gestures.onTouchEvent(cancel);
        cancel.recycle();
        setPressed(false);
    }

    @Override protected void onDetachedFromWindow() {
        cancelGestures();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        rim.setShader(new LinearGradient(0, 0, width, height,
                getContext().getColor(R.color.favorite_gold_light),
                getContext().getColor(R.color.favorite_gold_dark), Shader.TileMode.CLAMP));
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!isActivated()) return;
        // Two fine gold lines with a paper-colored gap, inside the existing cover bounds.
        rim.setAlpha(255);
        rim.setStrokeWidth(density);
        rimBounds.set(density, density, getWidth() - density, getHeight() - density);
        canvas.drawRoundRect(rimBounds, 5 * density, 5 * density, rim);
        rim.setAlpha(190);
        rim.setStrokeWidth(0.75f * density);
        rimBounds.inset(3 * density, 3 * density);
        canvas.drawRoundRect(rimBounds, 2 * density, 2 * density, rim);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (toggleFavorite != null) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    R.id.action_toggle_book_favorite, favoriteActionLabel));
        }
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.id.action_toggle_book_favorite && toggleFavorite != null && isEnabled()) {
            toggleFavorite.run();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int height = Math.round(MeasureSpec.getSize(widthSpec) * 1.42f);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
