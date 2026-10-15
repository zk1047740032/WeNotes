package com.wenotes.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Adds a soft gold rim to saved quotes without changing their layout or shadow outline. */
final class QuoteCardBackground extends Drawable {
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sweepMatrix = new Matrix();
    private final Path cardClip = new Path();
    private final RectF cardBounds = new RectF();
    private final RectF rimBounds = new RectF();
    private final float density;
    private final float radius;
    private final int goldLight;
    private final int goldDark;
    private int alpha = 255;
    private boolean saved;
    private float sweepProgress = -1;
    private float sweepWidth;

    void setFavoriteSweep(float progress) {
        sweepProgress = progress;
        invalidateSelf();
    }

    QuoteCardBackground(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        radius = 20 * density;
        surface.setColor(context.getColor(R.color.surface));
        rim.setStyle(Paint.Style.STROKE);
        goldLight = context.getColor(R.color.favorite_gold_light);
        goldDark = context.getColor(R.color.favorite_gold_dark);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        cardBounds.set(bounds);
        rimBounds.set(bounds);
        rimBounds.inset(density, density);
        rim.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                goldLight, goldDark, Shader.TileMode.CLAMP));
        cardClip.reset();
        cardClip.addRoundRect(cardBounds, radius, radius, Path.Direction.CW);
        sweepWidth = Math.max(1, bounds.width() * 0.35f);
        int transparentGold = goldLight & 0x00ffffff;
        sweep.setShader(new LinearGradient(-sweepWidth, 0, sweepWidth, bounds.height() * 0.25f,
                new int[]{transparentGold, goldLight, transparentGold},
                new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        float rimOpacity = saved ? 1 : sweepProgress >= 0 ? 1 - sweepProgress : 0;
        if (rimOpacity > 0) {
            // Overlapping translucent strokes soften the outer edge without software blur.
            for (int step = 5; step >= 1; step--) {
                rim.setStrokeWidth((2 + step * 2) * density);
                rim.setAlpha(Math.round(alpha * rimOpacity * (6 - step) * 0.015f));
                canvas.drawRoundRect(rimBounds, radius - density, radius - density, rim);
            }
        }
        canvas.drawRoundRect(cardBounds, radius, radius, surface);
        if (rimOpacity > 0) {
            rim.setStrokeWidth(2 * density);
            rim.setAlpha(Math.round(alpha * rimOpacity));
            canvas.drawRoundRect(rimBounds, radius - density, radius - density, rim);
        }
        if (sweepProgress >= 0) {
            float fade = Math.min(1, Math.min(sweepProgress, 1 - sweepProgress) * 5);
            float position = saved ? sweepProgress : 1 - sweepProgress;
            sweepMatrix.setTranslate(cardBounds.left - sweepWidth
                    + (cardBounds.width() + 2 * sweepWidth) * position, 0);
            sweep.getShader().setLocalMatrix(sweepMatrix);
            int checkpoint = canvas.save();
            canvas.clipPath(cardClip);
            sweep.setStyle(Paint.Style.FILL);
            sweep.setAlpha(Math.round(alpha * 0.16f * fade));
            canvas.drawRect(cardBounds, sweep);
            sweep.setStyle(Paint.Style.STROKE);
            sweep.setStrokeWidth(3 * density);
            sweep.setAlpha(Math.round(alpha * fade));
            canvas.drawRoundRect(rimBounds, radius - density, radius - density, sweep);
            canvas.restoreToCount(checkpoint);
        }
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] states) {
        boolean activated = false;
        for (int state : states) if (state == android.R.attr.state_activated) activated = true;
        if (saved == activated) return false;
        saved = activated;
        invalidateSelf();
        return true;
    }

    @Override public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
        outline.setAlpha(alpha / 255f);
    }

    @Override public void setAlpha(int value) {
        alpha = value;
        surface.setAlpha(value);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        surface.setColorFilter(filter);
        rim.setColorFilter(filter);
        sweep.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
