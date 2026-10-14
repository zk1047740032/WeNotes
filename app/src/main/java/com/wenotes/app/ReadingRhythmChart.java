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

import java.text.NumberFormat;
import java.util.Locale;

/** A seven-day chart with gradient bars, dashed guides and an hour axis on the right. */
@SuppressLint("ViewConstructor") // Created with the current week's cached data.
final class ReadingRhythmChart extends View {
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};
    private final ReadingCardData.Week week;
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] bars = new RectF[7];
    private final Shader[] gradients = new Shader[7];
    private final String[] axisLabels;
    private final double maximumSeconds;
    private float plotTop;
    private float plotBottom;
    private float plotRight;
    private float slotWidth;
    private float textCenterOffset;
    private float weekdayBaseline;

    ReadingRhythmChart(Context context, ReadingCardData.Week week) {
        super(context);
        this.week = week;
        gridPaint.setColor(context.getColor(R.color.reading_chart_grid));
        gridPaint.setStrokeWidth(dp(0.65f));
        gridPaint.setPathEffect(new DashPathEffect(new float[]{dp(2), dp(2)}, 0));
        labelPaint.setColor(context.getColor(R.color.reading_chart_label));
        labelPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        double step = week.chartStepHours();
        int intervals = week.chartIntervals();
        axisLabels = new String[intervals + 1];
        maximumSeconds = step * intervals * 3600;
        NumberFormat format = NumberFormat.getNumberInstance(Locale.CHINA);
        format.setGroupingUsed(false);
        format.setMaximumFractionDigits(2);
        for (int i = 0; i < axisLabels.length; i++) {
            axisLabels[i] = i == intervals ? "0" : format.format(step * (intervals - i)) + "h";
        }
        for (int i = 0; i < bars.length; i++) bars[i] = new RectF();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = Math.max(Math.round(dp(120)), Math.round(width * 0.40f));
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float labelHeight = metrics.descent - metrics.ascent;
        textCenterOffset = -(metrics.ascent + metrics.descent) / 2;
        plotTop = labelHeight / 2 + dp(2);
        plotBottom = Math.max(plotTop + dp(1), height - labelHeight - dp(14));
        float labelWidth = 0;
        for (String label : axisLabels) labelWidth = Math.max(labelWidth, labelPaint.measureText(label));
        plotRight = Math.max(dp(3), width - labelWidth - dp(16));
        slotWidth = (plotRight - dp(2)) / 7;
        weekdayBaseline = height - metrics.descent - dp(1);
        int topColor = getContext().getColor(R.color.reading_bar_top);
        int bottomColor = getContext().getColor(R.color.reading_bar_bottom);
        for (int i = 0; i < bars.length; i++) {
            Long seconds = week.seconds[i];
            if (seconds == null || seconds <= 0) {
                bars[i].setEmpty();
                gradients[i] = null;
                continue;
            }
            float center = dp(2) + slotWidth * (i + 0.5f);
            float barWidth = slotWidth * 0.73f;
            float barHeight = Math.max(dp(2), (float) (seconds / maximumSeconds * (plotBottom - plotTop)));
            barHeight = Math.min(plotBottom - plotTop, barHeight);
            bars[i].set(center - barWidth / 2, plotBottom - barHeight, center + barWidth / 2, plotBottom);
            gradients[i] = new LinearGradient(0, bars[i].top, 0, plotBottom,
                    topColor, bottomColor, Shader.TileMode.CLAMP);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i < axisLabels.length; i++) {
            float y = plotTop + (plotBottom - plotTop) * i / (axisLabels.length - 1);
            canvas.drawLine(dp(2), y, plotRight, y, gridPaint);
            canvas.drawText(axisLabels[i], getWidth() - dp(2), y + textCenterOffset, labelPaint);
        }
        labelPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < bars.length; i++) {
            if (!bars[i].isEmpty()) {
                barPaint.setShader(gradients[i]);
                canvas.drawRoundRect(bars[i], dp(6), dp(6), barPaint);
            }
            canvas.drawText(WEEKDAYS[i], dp(2) + slotWidth * (i + 0.5f), weekdayBaseline, labelPaint);
        }
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
