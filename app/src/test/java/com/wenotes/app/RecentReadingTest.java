package com.wenotes.app;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class RecentReadingTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static long time(String value) {
        return ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]").toEpochSecond();
    }

    private static ShelfBook book(String id, String kind, long lastReadAt, int notes) {
        return new ShelfBook(id, id, "", kind, true, notes, 0, 0, "", lastReadAt);
    }

    private static List<String> ids(List<ShelfBook> books) {
        return books.stream().map(book -> book.id).collect(Collectors.toList());
    }

    @Test
    public void weekUsesReadingHistoryAndSortsNewestFirstWithoutChangingTheSource() {
        long now = time("2026-10-10T12:00:00");
        long start = time("2026-10-03T12:00:00");
        List<ShelfBook> books = Arrays.asList(
                book("old-with-notes", "book", start - 1, 10),
                book("week-boundary", "book", start, 0),
                book("unknown-with-notes", "book", 0, 10),
                book("unread", "book", -1, 0),
                book("yesterday-no-notes", "book", now - 86400, 0),
                book("album", "album", now - 1, 0),
                book("articles", "articles", now, 0),
                book("now", "book", now, 0),
                book("future", "book", now + 1, 0));

        assertEquals(Arrays.asList("now", "album", "yesterday-no-notes", "week-boundary"),
                ids(RecentReading.filter(books, RecentReading.Period.WEEK, now, ZONE)));
        assertEquals("old-with-notes", books.get(0).id);
        assertEquals(9, books.size());
    }

    @Test
    public void monthAndHalfYearUseRollingCalendarMonthsIncludingLeapDay() {
        long now = time("2024-03-31T12:00:00");
        long monthStart = time("2024-02-29T12:00:00");
        long halfYearStart = time("2023-09-30T12:00:00");
        List<ShelfBook> books = Arrays.asList(
                book("before-half-year", "book", halfYearStart - 1, 0),
                book("half-year-boundary", "book", halfYearStart, 0),
                book("before-month", "book", monthStart - 1, 0),
                book("month-boundary", "book", monthStart, 0));

        assertEquals(Arrays.asList("month-boundary"),
                ids(RecentReading.filter(books, RecentReading.Period.MONTH, now, ZONE)));
        assertEquals(Arrays.asList("month-boundary", "before-month", "half-year-boundary"),
                ids(RecentReading.filter(books, RecentReading.Period.HALF_YEAR, now, ZONE)));
    }

    @Test
    public void weekKeepsDeviceLocalTimeAcrossDaylightSavingChange() {
        ZoneId zone = ZoneId.of("America/New_York");
        long now = ZonedDateTime.of(2026, 3, 10, 12, 0, 0, 0, zone).toEpochSecond();
        long expected = ZonedDateTime.of(2026, 3, 3, 12, 0, 0, 0, zone).toEpochSecond();
        assertEquals(expected, RecentReading.Period.WEEK.startAt(now, zone));
    }
}
