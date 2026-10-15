package com.wenotes.app;

import org.junit.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class ReadingComparisonTest {
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);

    @Test public void comparisonUsesMatchingWeekdaysAndExcludesTheRestOfLastWeek() {
        Map<LocalDate, Long> days = example();
        days.put(THURSDAY.minusWeeks(1).plusDays(1), 99999L);
        days.put(THURSDAY.plusDays(1), 99999L);
        ReadingCardData.Comparison result = comparison(days, THURSDAY);
        assertTrue(result.complete());
        assertEquals(4, result.elapsedDays);
        assertEquals(250 * 60, result.current.totalSeconds);
        assertEquals(200 * 60, result.previous.totalSeconds);
        assertEquals(Long.valueOf(50 * 60), result.differenceSeconds());
        assertEquals(25, result.changePercent(), 0.001);
        assertEquals(2, result.biggestChangeDay);
        assertEquals(Long.valueOf(20 * 60), result.differenceOn(2));
        assertNull(result.current.seconds[4]);
        assertNull(result.previous.seconds[4]);
    }

    @Test public void oldAndPartialCachesNeverImplyZeroOrAPercentage() {
        Map<LocalDate, Long> days = example();
        days.remove(THURSDAY.minusWeeks(1));
        ReadingCardData.Comparison result = comparison(days, THURSDAY);
        assertFalse(result.complete());
        assertEquals(3, result.previous.knownDays);
        assertNull(result.differenceSeconds());
        assertNull(result.changePercent());
        assertNull(result.differenceOn(3));
        days.entrySet().removeIf(entry -> entry.getKey().isBefore(ReadingStatistics.weekStart(THURSDAY)));
        result = comparison(days, THURSDAY);
        assertEquals(0, result.previous.knownDays);
        assertEquals(-1, result.biggestChangeDay);
        assertNull(result.changePercent());
        assertFalse(new ReadingCardData.Comparison(null, THURSDAY).complete());
    }

    @Test public void zeroBaselineNewReadingAndEqualDaysHaveNoInvalidPercentages() {
        Map<LocalDate, Long> days = example();
        days.replaceAll((day, seconds) -> 0L);
        ReadingCardData.Comparison zero = comparison(days, THURSDAY);
        assertTrue(zero.complete());
        assertEquals(Long.valueOf(0), zero.differenceSeconds());
        assertNull(zero.changePercent());
        assertEquals(-1, zero.biggestChangeDay);
        days.put(THURSDAY, 30L);
        ReadingCardData.Comparison started = comparison(days, THURSDAY);
        assertEquals(Long.valueOf(30), started.differenceSeconds());
        assertNull(started.changePercent());
        days.put(THURSDAY.minusWeeks(1), 30L);
        assertEquals(0, comparison(days, THURSDAY).changePercent(), 0.001);
    }

    @Test public void lowerTotalsUseNegativeChangesAndMostChangedDay() {
        Map<LocalDate, Long> days = example();
        days.put(THURSDAY, 0L);
        ReadingCardData.Comparison equal = comparison(days, THURSDAY);
        assertEquals(Long.valueOf(0), equal.differenceSeconds());
        assertEquals(0, equal.changePercent(), 0.001);
        assertEquals(3, equal.biggestChangeDay);
        days.put(THURSDAY.minusWeeks(1), 80 * 60L);
        ReadingCardData.Comparison less = comparison(days, THURSDAY);
        assertEquals(Long.valueOf(-40 * 60), less.differenceSeconds());
        assertEquals(-100.0 / 6, less.changePercent(), 0.001);
        assertEquals(Long.valueOf(-80 * 60), less.differenceOn(3));
    }

    @Test public void mondayAndSundayUseOneAndSevenDaysAcrossYearBoundary() {
        LocalDate sunday = LocalDate.of(2027, 1, 3);
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        for (int i = 0; i < 14; i++) days.put(sunday.minusDays(i), 60L);
        ReadingCardData.Comparison full = comparison(days, sunday);
        assertTrue(full.complete());
        assertEquals(7, full.elapsedDays);
        assertEquals(420, full.current.totalSeconds);
        assertEquals(420, full.previous.totalSeconds);
        ReadingCardData.Comparison monday = comparison(days, sunday.minusDays(6));
        assertEquals(1, monday.elapsedDays);
        assertEquals(60, monday.current.totalSeconds);
        assertEquals(60, monday.previous.totalSeconds);
    }

    private static Map<LocalDate, Long> example() {
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        int[] current = {45, 65, 90, 50}, previous = {35, 55, 70, 40};
        LocalDate monday = ReadingStatistics.weekStart(THURSDAY);
        for (int i = 0; i < 4; i++) {
            days.put(monday.plusDays(i), current[i] * 60L);
            days.put(monday.minusWeeks(1).plusDays(i), previous[i] * 60L);
        }
        return days;
    }

    private static ReadingCardData.Comparison comparison(Map<LocalDate, Long> days, LocalDate today) {
        return new ReadingCardData.Comparison(new ReadingStatistics(days, 0), today);
    }
}
