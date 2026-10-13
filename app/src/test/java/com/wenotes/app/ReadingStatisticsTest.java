package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ReadingStatisticsTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static long at(LocalDate date) { return date.atStartOfDay(ZONE).toEpochSecond(); }

    @Test public void calendarUsesDailySecondsAndExcludesOtherDaysAndMonthlyTotal() throws Exception {
        JSONObject times = new JSONObject();
        int[] minutes = {18, 0, 48, 31, 0, 70, 55};
        for (int i = 0; i < minutes.length; i++) times.put(String.valueOf(at(TODAY.minusDays(6 - i))), minutes[i] * 60);
        times.put(String.valueOf(at(TODAY.minusDays(7))), 100000);
        times.put(String.valueOf(at(TODAY.plusDays(1))), 200000);
        ReadingStatistics result = ReadingStatistics.fetch((name, params) -> {
            assertEquals("/readdata/detail", name);
            assertEquals("monthly", params.getString("mode"));
            assertEquals(YearMonth.from(TODAY), YearMonth.from(java.time.Instant.ofEpochSecond(
                    params.getLong("baseTime")).atZone(ZONE)));
            return new JSONObject().put("readTimes", times).put("totalReadTime", 999999)
                    .put("readLongest", new JSONArray());
        }, at(TODAY) + 3600, ZONE, () -> false);
        assertEquals(125 * 60, result.totalSeconds(TODAY));
        assertEquals(2, result.readingDays(TODAY));
        assertEquals(2, result.knownDays(TODAY));
        assertEquals(Long.valueOf(55 * 60), result.secondsOn(TODAY));
        assertNull(result.secondsOn(TODAY.minusDays(7)));
        assertNull(result.secondsOn(TODAY.minusDays(2)));
        assertNull(result.secondsOn(TODAY.plusDays(1)));
    }

    @Test public void sevenDaysAcrossNewYearFetchBothMonthsAndMergeByDate() throws Exception {
        LocalDate today = LocalDate.of(2027, 1, 3);
        List<YearMonth> months = new ArrayList<>();
        ReadingStatistics result = ReadingStatistics.fetch((name, params) -> {
            YearMonth month = YearMonth.from(java.time.Instant.ofEpochSecond(params.getLong("baseTime")).atZone(ZONE));
            months.add(month);
            LocalDate date = month.getMonthValue() == 12 ? LocalDate.of(2026, 12, 31) : today;
            return new JSONObject().put("readTimes", new JSONObject().put(String.valueOf(at(date)), 90));
        }, at(today) + 3600, ZONE, () -> false);
        assertEquals(Arrays.asList(YearMonth.of(2026, 12), YearMonth.of(2027, 1)), months);
        assertEquals(180, result.totalSeconds(today));
        assertEquals(7, result.knownDays(today));
    }

    @Test public void dailyBucketsUseLocalDatesAcrossDaylightSavingChange() throws Exception {
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate today = LocalDate.of(2026, 3, 10);
        JSONObject times = new JSONObject();
        for (int i = 0; i < 7; i++) times.put(String.valueOf(today.minusDays(i).atStartOfDay(zone).toEpochSecond()), 60);
        ReadingStatistics result = ReadingStatistics.fetch((name, params) -> new JSONObject().put("readTimes", times),
                today.atTime(12, 0).atZone(zone).toEpochSecond(), zone, () -> false);
        assertEquals(2, result.readingDays(today));
        assertEquals(120, result.totalSeconds(today));
        assertEquals(2, result.days.size());
    }

    @Test public void missingOrInvalidDailyPayloadIsNotTreatedAsSevenZeroDays() {
        assertThrows(org.json.JSONException.class, () -> ReadingStatistics.fetch((name, params) ->
                new JSONObject().put("totalReadTime", 600), at(TODAY), ZONE, () -> false));
        assertThrows(org.json.JSONException.class, () -> ReadingStatistics.fetch((name, params) ->
                new JSONObject().put("readTimes", new JSONObject().put(String.valueOf(at(TODAY)), -1)),
                at(TODAY), ZONE, () -> false));
    }

    @Test public void cancelledStatisticsDoNotMakeAnyMoreRequests() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> ReadingStatistics.fetch((name, params) -> {
            calls.incrementAndGet(); return new JSONObject().put("readTimes", new JSONObject());
        }, at(TODAY), ZONE, () -> true));
        assertEquals(0, calls.get());
        assertThrows(CancellationException.class, () -> ReadingStatistics.fetch((name, params) -> {
            calls.incrementAndGet(); return new JSONObject().put("readTimes", new JSONObject());
        }, at(LocalDate.of(2027, 1, 3)), ZONE, () -> calls.get() > 0));
        assertEquals(1, calls.get());
    }

    @Test public void unknownStaleDaysStayUnknownAndSubMinuteDoesNotCountAsReadingDay() throws Exception {
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        days.put(TODAY, 59L);
        days.put(TODAY.minusDays(1), 60L);
        days.put(TODAY.minusDays(2), 0L);
        ReadingStatistics result = ReadingStatistics.fromJson(new ReadingStatistics(days, at(TODAY)).toJson());
        assertEquals(Long.valueOf(0), result.secondsOn(TODAY.minusDays(2)));
        assertNull(result.secondsOn(TODAY.plusDays(1)));
        assertEquals(2, result.knownDays(TODAY));
        assertEquals(1, result.readingDays(TODAY));
        assertEquals(0, result.knownDays(TODAY.plusDays(9)));
    }

    @Test public void calendarBookRangeStartsAtMidnightAndCountsListeningButNotArticles() {
        long now = at(TODAY) + 12 * 3600;
        long start = at(ReadingStatistics.weekStart(TODAY));
        List<ShelfBook> books = Arrays.asList(book("boundary", "book", start),
                book("before", "book", start - 1), book("heard", "album", now - 1),
                book("articles", "articles", now), book("future", "book", now + 1), book("unread", "book", 0));
        List<ShelfBook> recent = ReadingStatistics.recentBooks(books, now, ZONE);
        assertEquals(2, recent.size());
        assertEquals("heard", recent.get(0).id);
        assertEquals("boundary", recent.get(1).id);
    }

    @Test public void cacheRoundTripAndOldOrCorruptStatsKeepBookCache() throws Exception {
        Map<LocalDate, Long> days = new LinkedHashMap<>(); days.put(TODAY, 90L);
        LibrarySnapshot library = new LibrarySnapshot(Arrays.asList(book("kept", "book", at(TODAY))),
                new ArrayList<>(), new ArrayList<>(), true).withReadingStatistics(new ReadingStatistics(days, at(TODAY)));
        LibrarySnapshot restored = LibrarySnapshot.fromJson(new JSONObject(library.toJson().toString()));
        assertEquals(Long.valueOf(90), restored.readingStatistics.secondsOn(TODAY));
        assertTrue(restored.recentReadingAvailable);
        JSONObject old = library.toJson().put("version", 3); old.remove("readingStatistics");
        assertNull(LibrarySnapshot.fromJson(old).readingStatistics);
        JSONObject damaged = library.toJson();
        damaged.getJSONObject("readingStatistics").getJSONArray("days").getJSONObject(0).put("date", "bad date");
        restored = LibrarySnapshot.fromJson(damaged);
        assertNull(restored.readingStatistics);
        assertEquals("kept", restored.books.get(0).id);
        assertTrue(restored.complete);
    }

    private static ShelfBook book(String id, String kind, long lastRead) {
        return new ShelfBook(id, id, "", kind, true, 0, 0, 0, "", lastRead);
    }

    @Test public void mondayStartsANewWeekEvenWhenTheCacheContainsPreviousWeekAndFutureDays() {
        LocalDate monday = LocalDate.of(2026, 10, 12);
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        days.put(monday.minusDays(1), 600L);
        days.put(monday, 120L);
        days.put(monday.plusDays(1), 900L);
        ReadingStatistics statistics = new ReadingStatistics(days, at(monday));
        assertEquals(monday, ReadingStatistics.weekStart(monday));
        assertEquals(monday, ReadingStatistics.weekStart(monday.plusDays(6)));
        assertEquals(monday.plusDays(7), ReadingStatistics.weekStart(monday.plusDays(7)));
        assertEquals(120, statistics.totalSeconds(monday));
        assertEquals(1, statistics.knownDays(monday));
        assertEquals(1, statistics.readingDays(monday));
        assertEquals(0, statistics.knownDays(monday.plusDays(7)));
    }

    @Test public void sundayIncludesTheFullWeekButNotNextMonday() throws Exception {
        LocalDate sunday = LocalDate.of(2026, 10, 18);
        JSONObject times = new JSONObject().put(String.valueOf(at(sunday.minusDays(6))), 60)
                .put(String.valueOf(at(sunday)), 120).put(String.valueOf(at(sunday.plusDays(1))), 999);
        ReadingStatistics result = ReadingStatistics.fetch((name, params) -> new JSONObject().put("readTimes", times),
                at(sunday) + 3600, ZONE, () -> false);
        assertEquals(7, result.knownDays(sunday));
        assertEquals(2, result.readingDays(sunday));
        assertEquals(180, result.totalSeconds(sunday));
        assertNull(result.secondsOn(sunday.plusDays(1)));
    }
}
