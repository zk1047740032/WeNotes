package com.wenotes.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Content for the rhythm, recent-books and reading-timeline pages. */
@SuppressLint("ViewConstructor") // Built with cached data, never inflated from XML.
final class ReadingCardContent extends LinearLayout {
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};
    private final List<Runnable> coverBindings = new ArrayList<>();

    ReadingCardContent(Context context, int page, LibrarySnapshot library, long now, CoverLoader covers) {
        super(context);
        setOrientation(VERTICAL);
        setPaddingRelative(dp(16), dp(20), dp(16), dp(16));
        LocalDate today = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate();
        if (page == 0) rhythm(library.readingStatistics, today);
        else if (page == 1) recentBooks(library, now, today, covers);
        else timeline(library, now, today);
    }

    void refreshCovers() { for (Runnable binding : coverBindings) binding.run(); }

    private void rhythm(ReadingStatistics statistics, LocalDate today) {
        ReadingCardData.Week week = new ReadingCardData.Week(statistics, today);
        DateTimeFormatter range = DateTimeFormatter.ofPattern("MM.dd", Locale.CHINA);
        LinearLayout summary = row();
        summary.setBaselineAligned(false);
        summary.addView(text(week.knownDays == 0 ? "—" : duration(week.totalSeconds),
                28, R.color.text_primary, true), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView dates = text(range.format(week.monday) + " – " + range.format(week.monday.plusDays(6)),
                10, R.color.text_secondary, false);
        LayoutParams datesParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        datesParams.setMarginStart(dp(12));
        summary.addView(dates, datesParams);
        addView(summary, spaced(0));
        ReadingRhythmChart chart = new ReadingRhythmChart(getContext(), week);
        StringBuilder chartDescription = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            LocalDate day = week.monday.plusDays(i);
            Long seconds = week.seconds[i];
            if (i > 0) chartDescription.append("；");
            chartDescription.append(getContext().getString(R.string.reading_chart_day, day.toString(),
                    day.isAfter(today) ? getContext().getString(R.string.reading_future)
                            : seconds == null ? getContext().getString(R.string.reading_unknown) : duration(seconds)));
        }
        chart.setContentDescription(chartDescription);
        chart.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        addView(chart, spaced(20));
        divider(18);
        LinearLayout metrics = row();
        metrics.setBaselineAligned(false);
        metrics.addView(metric(week.knownDays == 0 ? "—" : duration(week.averageSeconds()),
                week.complete() ? R.string.reading_daily_average : R.string.reading_synced_average),
                new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        String best = week.bestDay < 0 ? "—" : getContext().getString(R.string.reading_best_day,
                WEEKDAYS[week.bestDay], duration(week.seconds[week.bestDay]));
        LinearLayout longest = metric(best, R.string.reading_longest_day);
        longest.setPaddingRelative(dp(12), 0, 0, 0);
        metrics.addView(longest, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(metrics, spaced(14));
        if (week.knownDays == 0) message(R.string.reading_statistics_pending);
        else if (!week.complete()) addView(text(getContext().getString(R.string.reading_statistics_partial,
                week.knownDays, today.getDayOfWeek().getValue()), 11, R.color.text_hint, false), spaced(12));
    }

    private void recentBooks(LibrarySnapshot library, long now, LocalDate today, CoverLoader covers) {
        List<ShelfBook> books = ReadingCardData.recentBooks(library.books, now);
        if (!library.recentReadingAvailable || books.isEmpty()) {
            message(library.recentReadingAvailable ? R.string.reading_no_recent_books : R.string.reading_books_pending);
            return;
        }
        LinearLayout shelf = row();
        shelf.setBaselineAligned(false);
        shelf.setGravity(Gravity.TOP);
        for (int i = 0; i < 3; i++) {
            LinearLayout column = new LinearLayout(getContext());
            column.setOrientation(VERTICAL);
            column.setPaddingRelative(dp(4), 0, dp(4), 0);
            shelf.addView(column, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            if (i >= books.size()) continue;
            ShelfBook book = books.get(i);
            BookCoverFrame frame = new BookCoverFrame(getContext(), null);
            frame.setBackground(background(getContext(), R.color.surface_tint, 6));
            frame.setElevation(dp(1));
            ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(image, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            TextView placeholder = text(book.title, 11, R.color.text_secondary, true);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setMaxLines(5);
            placeholder.setEllipsize(TextUtils.TruncateAt.END);
            placeholder.setPadding(dp(7), dp(7), dp(7), dp(7));
            placeholder.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(placeholder, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            column.addView(frame, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            if (covers != null) coverBindings.add(() -> covers.bind(image, placeholder, book));
            TextView title = text(book.title, 13, R.color.text_primary, true);
            title.setLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(title, spaced(10));
            TextView author = text(book.author, 11, R.color.text_secondary, false);
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(author, spaced(4));
            column.addView(text(date(book.lastReadAt, today, false), 11, R.color.text_hint, false), spaced(8));
        }
        addView(shelf, spaced(0));
        refreshCovers();
    }

    private void timeline(LibrarySnapshot library, long now, LocalDate today) {
        List<BookNote> notes = ReadingCardData.recentNotes(library.notes, now);
        if (notes.isEmpty()) {
            message(library.complete ? R.string.reading_no_notes : R.string.reading_notes_pending);
            return;
        }
        Map<String, ShelfBook> books = new HashMap<>();
        for (ShelfBook book : library.books) books.put(book.id, book);
        for (int i = 0; i < notes.size(); i++) {
            BookNote note = notes.get(i);
            LinearLayout entry = row();
            entry.setGravity(Gravity.TOP);
            entry.setBaselineAligned(false);
            LinearLayout rail = new LinearLayout(getContext());
            rail.setOrientation(VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            rail.setPadding(0, dp(4), 0, 0);
            View dot = new View(getContext());
            dot.setBackground(background(getContext(), R.color.primary, 4));
            rail.addView(dot, new LayoutParams(dp(8), dp(8)));
            if (i < notes.size() - 1) {
                View line = new View(getContext());
                line.setBackgroundColor(getContext().getColor(R.color.outline));
                LayoutParams lineParams = new LayoutParams(dp(2), 0, 1);
                lineParams.topMargin = dp(5);
                rail.addView(line, lineParams);
            }
            entry.addView(rail, new LayoutParams(dp(22), LayoutParams.MATCH_PARENT));
            LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(VERTICAL);
            content.setPaddingRelative(dp(4), 0, 0, i == notes.size() - 1 ? 0 : dp(18));
            String kind = getContext().getString("highlight".equals(note.kind) ? R.string.reading_highlight
                    : "review".equals(note.kind) ? R.string.reading_review : R.string.reading_thought);
            content.addView(text(date(note.createdAt, today, true) + " · " + kind, 11, R.color.text_secondary, false));
            ShelfBook book = books.get(note.bookId);
            TextView title = text(book == null ? getContext().getString(R.string.reading_unknown_book) : book.title,
                    14, R.color.text_primary, true);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(title, spaced(6));
            TextView preview = text(ReadingCardData.preview(note), 12, R.color.text_secondary, false);
            preview.setMaxLines(2);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            preview.setBackground(background(getContext(), R.color.background, 10));
            preview.setPadding(dp(10), dp(9), dp(10), dp(9));
            content.addView(preview, spaced(7));
            entry.addView(content, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            addView(entry, spaced(0));
        }
    }

    private String date(long seconds, LocalDate today, boolean withTime) {
        ZonedDateTime moment = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault());
        LocalDate day = moment.toLocalDate();
        if (day.equals(today) || day.equals(today.minusDays(1))) {
            String relative = getContext().getString(day.equals(today) ? R.string.reading_today : R.string.reading_yesterday);
            return withTime ? relative + " " + moment.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)) : relative;
        }
        return moment.format(DateTimeFormatter.ofPattern(day.getYear() == today.getYear() ? "M月d日" : "yyyy年M月d日", Locale.CHINA));
    }

    private String duration(long seconds) {
        if (seconds > 0 && seconds < 60) return getContext().getString(R.string.reading_under_minute);
        long minutes = seconds / 60;
        return minutes >= 60 ? getContext().getString(R.string.reading_hours_minutes, minutes / 60, minutes % 60)
                : getContext().getString(R.string.reading_minutes, minutes);
    }

    private LinearLayout metric(String value, int label) {
        LinearLayout metric = new LinearLayout(getContext());
        metric.setOrientation(VERTICAL);
        metric.addView(text(value, 17, R.color.text_primary, true));
        metric.addView(text(getContext().getString(label), 11, R.color.text_secondary, false), spaced(6));
        return metric;
    }

    private void message(int resource) {
        addView(text(getContext().getString(resource), 12, R.color.text_secondary, false), spaced(18));
    }

    private void divider(int margin) {
        View line = new View(getContext());
        line.setBackgroundColor(getContext().getColor(R.color.outline));
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(margin);
        addView(line, params);
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getContext().getColor(color));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LayoutParams spaced(int margin) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(margin);
        return params;
    }

    static GradientDrawable background(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(context.getColor(color));
        drawable.setCornerRadius(radius * context.getResources().getDisplayMetrics().density);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
