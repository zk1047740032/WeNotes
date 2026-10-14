package com.wenotes.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The local Monday–Sunday week below the quote stack, using cached statistics through today. */
public final class ReadingCalendarView extends LinearLayout {
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("MM.dd", Locale.CHINA);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};
    private LibrarySnapshot library;
    private LocalDate today;
    private LocalDate selected;
    private LinearLayout dates;
    private LinearLayout detail;

    public ReadingCalendarView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOrientation(VERTICAL);
        setPaddingRelative(dp(16), dp(20), dp(16), dp(20));
        setBackground(background(R.color.surface, 22));
        setElevation(dp(2));
    }

    void bind(LibrarySnapshot snapshot) {
        LocalDate current = LocalDate.now();
        if (library == snapshot && current.equals(today)) return;
        library = snapshot;
        boolean followToday = selected == null || selected.equals(today);
        today = current;
        if (followToday || selected.isBefore(ReadingStatistics.weekStart(today)) || selected.isAfter(today)) selected = today;
        render();
    }

    void saveSelection(Bundle state) {
        if (selected != null) state.putString("reading_calendar_day", selected.toString());
    }

    void restoreSelection(Bundle state) {
        if (state == null) return;
        String saved = state.getString("reading_calendar_day");
        if (saved != null) selected = LocalDate.parse(saved);
    }

    private void render() {
        removeAllViews();
        ReadingStatistics statistics = library.readingStatistics;
        int known = statistics == null ? 0 : statistics.knownDays(today);
        LocalDate monday = ReadingStatistics.weekStart(today);
        int elapsed = today.getDayOfWeek().getValue();
        List<ShelfBook> books = ReadingStatistics.recentBooks(library.books,
                Instant.now().getEpochSecond(), ZoneId.systemDefault());
        LinearLayout summary = row();
        String duration = known == 0 ? "—" : duration(statistics.totalSeconds(today));
        summary.addView(summary(duration, getContext().getString(known == elapsed
                ? R.string.reading_calendar_reading : R.string.reading_calendar_synced_time)));
        LayoutParams booksParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        booksParams.setMarginStart(dp(16));
        summary.addView(summary(library.recentReadingAvailable ? String.valueOf(books.size()) : "—",
                getContext().getString(R.string.reading_calendar_books)), booksParams);
        LayoutParams daysParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        daysParams.setMarginStart(dp(16));
        summary.addView(summary(known == 0 ? "—" : String.valueOf(statistics.readingDays(today)),
                getContext().getString(R.string.reading_calendar_days)), daysParams);
        summary.addView(new View(getContext()), new LayoutParams(0, 0, 1));
        TextView range = text(RANGE.format(monday) + " — " + RANGE.format(monday.plusDays(6)), 11, R.color.text_secondary);
        range.setSingleLine(true);
        LayoutParams rangeParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rangeParams.setMarginStart(dp(12));
        summary.addView(range, rangeParams);
        addView(summary);

        dates = row();
        dates.setBaselineAligned(false);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            boolean future = date.isAfter(today);
            Long seconds = statistics == null || future ? null : statistics.secondsOn(date);
            boolean active = date.equals(selected);
            LinearLayout button = new LinearLayout(getContext());
            button.setOrientation(VERTICAL);
            button.setGravity(android.view.Gravity.CENTER);
            button.setPadding(dp(2), 0, dp(2), 0);
            TextView weekday = text(WEEKDAYS[date.getDayOfWeek().getValue() - 1], 11, R.color.text_secondary);
            weekday.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            if (date.equals(today)) {
                weekday.setTextColor(getContext().getColor(R.color.primary));
                GradientDrawable dot = background(R.color.primary, 2);
                dot.setBounds(0, 0, dp(4), dp(4));
                weekday.setCompoundDrawablePadding(dp(2));
                weekday.setCompoundDrawablesRelative(null, null, null, dot);
            }
            button.addView(weekday, new LayoutParams(LayoutParams.MATCH_PARENT, dp(23)));
            LinearLayout tile = new LinearLayout(getContext()) {
                @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                            MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY));
                }
            };
            tile.setOrientation(VERTICAL);
            tile.setGravity(android.view.Gravity.CENTER);
            tile.setBackground(dateBackground(date, seconds, active));
            TextView day = text(String.valueOf(date.getDayOfMonth()), 14,
                    seconds != null && seconds > 3600 ? android.R.color.white
                            : future ? R.color.text_secondary : R.color.text_primary);
            day.setGravity(android.view.Gravity.CENTER);
            tile.addView(day);
            LayoutParams tileParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            tileParams.topMargin = dp(5);
            button.addView(tile, tileParams);
            weekday.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            button.setSelected(active);
            button.setTag(date);
            button.setEnabled(!future);
            button.setFocusable(!future);
            button.setContentDescription(getContext().getString(R.string.reading_calendar_day_description,
                    date.toString(), date.equals(today) ? getContext().getString(R.string.reading_calendar_today) : "",
                    future ? getContext().getString(R.string.reading_calendar_future)
                            : seconds == null ? getContext().getString(R.string.reading_calendar_unknown)
                            : duration(seconds)));
            button.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.Button.class.getName());
                }
            });
            button.setOnClickListener(view -> selectDate(date));
            dates.addView(button, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        addView(dates, spaced(16));

        detail = new LinearLayout(getContext());
        detail.setOrientation(VERTICAL);
        detail.setPadding(dp(14), dp(13), dp(14), dp(13));
        detail.setBackground(background(R.color.background, 16));
        detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bindDetail();
        addView(detail, spaced(16));

        TextView bookLabel = text(getContext().getString(R.string.reading_calendar_recent_books), 11, R.color.text_secondary);
        addView(bookLabel, spaced(14));
        String bookNames;
        if (!library.recentReadingAvailable) bookNames = getContext().getString(R.string.reading_calendar_books_pending);
        else if (books.isEmpty()) bookNames = getContext().getString(R.string.reading_calendar_no_books);
        else {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < Math.min(3, books.size()); i++) {
                if (i > 0) names.append(" · ");
                names.append(books.get(i).title);
            }
            if (books.size() > 3) names.append(getContext().getString(R.string.reading_calendar_more_books, books.size()));
            bookNames = names.toString();
        }
        addView(text(bookNames, 12, R.color.text_secondary), spaced(4));
        if (statistics != null) {
            String notice = known < elapsed ? getContext().getString(R.string.reading_calendar_partial, known, elapsed)
                    : getContext().getString(R.string.reading_calendar_includes_listening);
            addView(text(notice, 11, R.color.text_hint), spaced(8));
        }
    }

    private void selectDate(LocalDate date) {
        selected = date;
        for (int i = 0; i < dates.getChildCount(); i++) {
            LinearLayout button = (LinearLayout) dates.getChildAt(i);
            LocalDate day = (LocalDate) button.getTag();
            boolean active = day.equals(selected);
            Long seconds = library.readingStatistics == null || day.isAfter(today)
                    ? null : library.readingStatistics.secondsOn(day);
            LinearLayout tile = (LinearLayout) button.getChildAt(1);
            tile.setBackground(dateBackground(day, seconds, active));
            button.setSelected(active);
        }
        bindDetail();
    }

    private void bindDetail() {
        detail.removeAllViews();
        ReadingStatistics statistics = library.readingStatistics;
        LinearLayout header = row();
        header.addView(text(DAY.format(selected) + " · " + (selected.equals(today)
                ? getContext().getString(R.string.reading_calendar_today)
                : "周" + WEEKDAYS[selected.getDayOfWeek().getValue() - 1]), 13, R.color.text_primary),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        Long seconds = statistics == null ? null : statistics.secondsOn(selected);
        TextView time = text(seconds == null ? "—" : duration(seconds), 13, R.color.primary);
        time.setId(R.id.reading_calendar_selected_time);
        header.addView(time);
        detail.addView(header);
        if (seconds == null) {
            detail.addView(text(getContext().getString(statistics == null ? R.string.reading_calendar_sync_first
                    : R.string.reading_calendar_day_pending), 12, R.color.text_secondary), spaced(7));
        } else if (seconds == 0) {
            detail.addView(text(getContext().getString(R.string.reading_calendar_no_reading), 12,
                    R.color.text_secondary), spaced(7));
        }
    }

    private TextView summary(String value, String label) {
        String content = value + " " + label;
        SpannableString styled = new SpannableString(content);
        styled.setSpan(new AbsoluteSizeSpan(18, true), 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new TypefaceSpan("sans-serif-medium"), 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(getContext().getColor(R.color.text_primary)), 0,
                value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView text = text(content, 11, R.color.text_secondary);
        text.setText(styled);
        return text;
    }

    private String duration(long seconds) {
        if (seconds > 0 && seconds < 60) return getContext().getString(R.string.reading_calendar_under_minute);
        long minutes = seconds / 60;
        if (minutes >= 60) return getContext().getString(R.string.reading_calendar_hours_minutes,
                minutes / 60, minutes % 60);
        return getContext().getString(R.string.reading_calendar_minutes, minutes);
    }

    private GradientDrawable dateBackground(LocalDate date, Long seconds, boolean active) {
        int color;
        if (date.isAfter(today)) color = R.color.reading_calendar_future_surface;
        else if (seconds == null) color = R.color.search_surface;
        else if (seconds == 0) color = R.color.reading_heat_0;
        else if (seconds <= 15 * 60) color = R.color.reading_heat_1;
        else if (seconds <= 30 * 60) color = R.color.reading_heat_2;
        else if (seconds <= 60 * 60) color = R.color.reading_heat_3;
        else color = R.color.primary;
        GradientDrawable tile = background(color, 10);
        if (active) tile.setStroke(dp(2), getContext().getColor(R.color.primary));
        return tile;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(getContext().getColor(color));
        text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(3), 1f);
        return text;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return row;
    }

    private LayoutParams spaced(int margin) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(margin);
        return params;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getContext().getColor(color));
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
