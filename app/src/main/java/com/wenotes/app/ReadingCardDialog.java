package com.wenotes.app;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.core.view.ViewCompat;

import java.time.Instant;

/** A bounded reading card that grows from, and returns to, its home-card bounds. */
final class ReadingCardDialog extends Dialog {
    private final View source;
    private final LibrarySnapshot library;
    private final int page;
    private final Rect origin = new Rect();
    private final Rect expanded = new Rect();
    private FrameLayout overlay;
    private ScrollView card;
    private ValueAnimator transition;
    private boolean closing;

    ReadingCardDialog(Context context, View source, LibrarySnapshot library, int page) {
        super(context);
        this.source = source;
        this.library = library;
        this.page = page;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        overlay = new FrameLayout(getContext());
        overlay.setOnClickListener(view -> cancel());
        card = new ScrollView(getContext());
        card.setBackgroundResource(R.drawable.card_background);
        card.setClipToOutline(true);
        card.setElevation(dp(8));
        card.setFillViewport(true);
        card.setVerticalScrollBarEnabled(true);
        ViewCompat.setAccessibilityPaneTitle(card, getContext().getString(page == 0
                ? R.string.reading_comparison_title : R.string.reading_timeline_expanded));
        card.addView(new ReadingCardContent(getContext(), page, library,
                Instant.now().getEpochSecond(), null, true));
        // Consume taps inside the card, including its empty state.
        card.setOnClickListener(view -> {});
        overlay.addView(card, new FrameLayout.LayoutParams(1, 1));
        setContentView(overlay);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0);
            window.setWindowAnimations(0);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        overlay.post(() -> {
            if (!isShowing() || closing) return;
            int[] sourcePosition = new int[2];
            int[] overlayPosition = new int[2];
            source.getLocationOnScreen(sourcePosition);
            overlay.getLocationOnScreen(overlayPosition);
            origin.set(sourcePosition[0] - overlayPosition[0], sourcePosition[1] - overlayPosition[1],
                    sourcePosition[0] - overlayPosition[0] + source.getWidth(),
                    sourcePosition[1] - overlayPosition[1] + source.getHeight());
            int width = Math.min(source.getWidth() + dp(16), overlay.getWidth() - dp(32));
            int height = Math.round(overlay.getHeight() * 0.78f);
            if (page == 0) {
                View content = card.getChildAt(0);
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                height = Math.min(height, content.getMeasuredHeight());
            }
            int left = (overlay.getWidth() - width) / 2;
            int top = (overlay.getHeight() - height) / 2;
            expanded.set(left, top, left + width, top + height);
            frostBackground(overlayPosition);
            source.setVisibility(View.INVISIBLE);
            animateBounds(origin, expanded, false);
        });
    }

    @Override public void dismiss() {
        if (closing) return;
        closing = true;
        if (card == null || expanded.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            dismissImmediately();
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
        Rect current = new Rect(params.leftMargin, params.topMargin,
                params.leftMargin + params.width, params.topMargin + params.height);
        animateBounds(current, origin, true);
    }

    void dismissImmediately() {
        closing = true;
        if (transition != null) transition.cancel();
        source.setVisibility(View.VISIBLE);
        super.dismiss();
        if (overlay != null) overlay.setBackground(null);
    }

    private void frostBackground(int[] overlayPosition) {
        // A small snapshot keeps blur inexpensive and works on every supported Android version.
        int width = Math.max(1, (overlay.getWidth() + 5) / 6);
        int height = Math.max(1, (overlay.getHeight() + 5) / 6);
        Bitmap backdrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(backdrop);
        canvas.drawColor(getContext().getColor(R.color.background));
        canvas.save();
        canvas.scale((float) width / overlay.getWidth(), (float) height / overlay.getHeight());
        View home = source.getRootView();
        int[] homePosition = new int[2];
        home.getLocationOnScreen(homePosition);
        canvas.translate(homePosition[0] - overlayPosition[0], homePosition[1] - overlayPosition[1]);
        home.draw(canvas);
        canvas.restore();
        int[] pixels = new int[width * height];
        int[] buffer = new int[pixels.length];
        backdrop.getPixels(pixels, 0, width, 0, 0, width, height);
        int radius = Math.max(1, dp(12) / 6);
        // Three box passes approximate a soft Gaussian blur; clamp edges to avoid dark borders.
        for (int pass = 0; pass < 3; pass++) {
            blur(pixels, buffer, width, height, radius, true);
            blur(buffer, pixels, width, height, radius, false);
        }
        backdrop.setPixels(pixels, 0, width, 0, 0, width, height);
        canvas.drawColor(Color.argb(72, 255, 255, 255));
        BitmapDrawable background = new BitmapDrawable(getContext().getResources(), backdrop);
        background.setFilterBitmap(true);
        background.setGravity(Gravity.FILL);
        overlay.setBackground(background);
    }

    private static void blur(int[] input, int[] output, int width, int height, int radius, boolean horizontal) {
        int length = horizontal ? width : height;
        int lines = horizontal ? height : width;
        int step = horizontal ? 1 : width;
        int count = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * width : line;
            int red = 0, green = 0, blue = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = input[start + Math.max(0, Math.min(length - 1, i)) * step];
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
            }
            for (int i = 0; i < length; i++) {
                output[start + i * step] = Color.rgb(red / count, green / count, blue / count);
                int leaving = input[start + Math.max(0, i - radius) * step];
                int entering = input[start + Math.min(length - 1, i + radius + 1) * step];
                red += Color.red(entering) - Color.red(leaving);
                green += Color.green(entering) - Color.green(leaving);
                blue += Color.blue(entering) - Color.blue(leaving);
            }
        }
    }

    private void animateBounds(Rect from, Rect to, boolean dismissAfter) {
        if (transition != null) transition.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            place(to);
            if (dismissAfter) dismissImmediately();
            return;
        }
        transition = ValueAnimator.ofFloat(0, 1);
        transition.setDuration(dismissAfter ? 180 : 260);
        transition.setInterpolator(new DecelerateInterpolator());
        transition.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            place(new Rect(blend(from.left, to.left, progress), blend(from.top, to.top, progress),
                    blend(from.right, to.right, progress), blend(from.bottom, to.bottom, progress)));
            if (dismissAfter && progress == 1) dismissImmediately();
        });
        place(from);
        transition.start();
    }

    private void place(Rect bounds) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(bounds.width(), bounds.height(),
                Gravity.TOP | Gravity.LEFT);
        params.leftMargin = bounds.left;
        params.topMargin = bounds.top;
        card.setLayoutParams(params);
    }

    private int blend(int from, int to, float progress) { return Math.round(from + (to - from) * progress); }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
