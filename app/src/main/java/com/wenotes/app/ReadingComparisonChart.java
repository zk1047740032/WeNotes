package com.wenotes.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Paired daily bars with one shared minute axis and no future-day comparisons. */
@SuppressLint("ViewConstructor")
final class ReadingComparisonChart extends View {
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private final ReadingCardData.Comparison comparison;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bars = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labels = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final double stepMinutes;
    private final int intervals;
    private final int currentColor;
    private final int previousColor;
    private final int labelColor;

    ReadingComparisonChart(Context context, ReadingCardData.Comparison comparison) {
        super(context);
        this.comparison = comparison;
        currentColor = context.getColor(R.color.primary);
        previousColor = context.getColor(R.color.reading_comparison_previous);
        labelColor = context.getColor(R.color.text_secondary);
        grid.setColor(context.getColor(R.color.reading_chart_grid));
        grid.setStrokeWidth(dp(0.6f));
        grid.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        double maximum = 0;
        for (int i = 0; i < comparison.elapsedDays; i++) {
            Long current = comparison.current.seconds[i], previous = comparison.previous.seconds[i];
            if (current != null) maximum = Math.max(maximum, current / 60.0);
            if (previous != null) maximum = Math.max(maximum, previous / 60.0);
        }
        double required = Math.max(30, maximum / 3);
        double magnitude = Math.pow(10, Math.floor(Math.log10(required)));
        double fraction = required / magnitude;
        stepMinutes = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 3 ? 3 : fraction <= 5 ? 5 : 10)
                * magnitude;
        intervals = Math.max(3, (int) Math.floor(maximum / stepMinutes) + 1);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Reserve room for labels as system font size increases.
        int height = Math.round(dp(160) + sp(44));
        setMeasuredDimension(resolveSize(MeasureSpec.getSize(widthSpec), widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        labels.setTextSize(sp(10));
        labels.setColor(labelColor);
        String maximumLabel = Long.toString(Math.round(stepMinutes * intervals));
        float left = labels.measureText(maximumLabel) + dp(7);
        float right = getWidth() - dp(2);
        float top = sp(18);
        float bottom = getHeight() - sp(32);
        float slot = (right - left) / comparison.elapsedDays;
        labels.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= intervals; i++) {
            float y = bottom - (bottom - top) * i / intervals;
            canvas.drawLine(left, y, right, y, grid);
            canvas.drawText(Long.toString(Math.round(stepMinutes * i)), left - dp(6), y + sp(3), labels);
        }
        DateTimeFormatter dates = DateTimeFormatter.ofPattern("MM.dd", Locale.CHINA);
        labels.setTextAlign(Paint.Align.CENTER);
        float barWidth = Math.min(dp(22), slot * 0.28f);
        float gap = Math.min(dp(5), slot * 0.08f);
        for (int i = 0; i < comparison.elapsedDays; i++) {
            float center = left + slot * (i + 0.5f);
            drawBar(canvas, comparison.previous.seconds[i], center - (barWidth + gap) / 2,
                    barWidth, top, bottom, slot, false);
            drawBar(canvas, comparison.current.seconds[i], center + (barWidth + gap) / 2,
                    barWidth, top, bottom, slot, true);
            labels.setColor(labelColor);
            labels.setTextSize(sp(comparison.elapsedDays > 5 ? 9 : 10));
            canvas.drawText(WEEKDAYS[i], center, bottom + sp(16), labels);
            labels.setTextSize(sp(9));
            canvas.drawText(dates.format(comparison.current.monday.plusDays(i)), center, bottom + sp(29), labels);
        }
    }

    private void drawBar(Canvas canvas, Long seconds, float center, float width, float top, float bottom,
                         float slot, boolean current) {
        float height = seconds == null || seconds == 0 ? 0
                : Math.max(dp(2), (float) (seconds / 60.0 / (stepMinutes * intervals) * (bottom - top)));
        bars.setShader(current && height > 0 ? new LinearGradient(0, bottom - height, 0, bottom,
                getContext().getColor(R.color.reading_bar_bottom), getContext().getColor(R.color.reading_bar_top),
                Shader.TileMode.CLAMP) : null);
        bars.setColor(current ? currentColor : previousColor);
        if (height > 0) {
            bar.set(center - width / 2, bottom - height, center + width / 2, bottom);
            canvas.drawRoundRect(bar, dp(3), dp(3), bars);
        }
        labels.setColor(current ? currentColor : labelColor);
        labels.setTextSize(sp(comparison.elapsedDays > 5 ? 8 : 10));
        String value = seconds == null ? "—" : seconds > 0 && seconds < 60 ? "<1" : Long.toString(seconds / 60);
        // Long values stay available in the accessibility description when seven pairs are crowded.
        if (labels.measureText(value) < slot * 0.44f) {
            canvas.drawText(value, center, bottom - height - dp(5), labels);
        }
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
