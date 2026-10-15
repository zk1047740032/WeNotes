package com.wenotes.app;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public final class ReadingCardDataTest {
    @Test public void chartAxisFitsLongDaysAndUsesReadableHourSteps() {
        LocalDate monday = LocalDate.of(2026, 10, 12);
        assertEquals(2, new ReadingCardData.Week(null, monday).chartStepHours(), 0);
        for (long seconds : new long[]{20, 1800, 49 * 60, 3600, 12600, 21600, 21601, 86400}) {
            ReadingCardData.Week week = new ReadingCardData.Week(
                    new ReadingStatistics(Map.of(monday, seconds), 0), monday);
            double step = week.chartStepHours();
            double topSeconds = step * week.chartIntervals() * 3600;
            assertTrue("Top tick must be above the highest bar", topSeconds > seconds);
            assertTrue("Axis left an extra empty interval", topSeconds - step * 3600 <= seconds);
            assertTrue("Axis labels became too small", step >= 0.5);
        }
        ReadingCardData.Week example = new ReadingCardData.Week(
                new ReadingStatistics(Map.of(monday, 12600L), 0), monday);
        assertEquals(2, example.chartStepHours(), 0);
        ReadingCardData.Week current = new ReadingCardData.Week(
                new ReadingStatistics(Map.of(monday, 49 * 60L), 0), monday);
        assertEquals(1, current.chartStepHours() * current.chartIntervals(), 0);
    }

    @Test public void weekDistinguishesUnknownZeroAndFutureDates() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        ReadingStatistics statistics = new ReadingStatistics(Map.of(
                today.minusDays(2), 120L, today.minusDays(1), 0L,
                today.plusDays(1), 999L, today.minusDays(3), 999L), 0);
        ReadingCardData.Week week = new ReadingCardData.Week(statistics, today);
        assertEquals(120, week.totalSeconds);
        assertEquals(2, week.knownDays);
        assertEquals(60, week.averageSeconds());
        assertEquals(Long.valueOf(0), week.seconds[1]);
        assertNull(week.seconds[2]);
        assertNull(week.seconds[3]);
        assertFalse(week.complete());
        assertEquals(0, week.bestDay);
    }

    @Test public void emptyWeekHasNoLongestDayAndCompleteZeroWeekIsKnown() {
        LocalDate monday = LocalDate.of(2026, 10, 12);
        ReadingCardData.Week missing = new ReadingCardData.Week(null, monday);
        assertFalse(missing.complete());
        assertEquals(-1, missing.bestDay);
        assertEquals(0, missing.averageSeconds());
        ReadingCardData.Week zero = new ReadingCardData.Week(new ReadingStatistics(Map.of(monday, 0L), 0), monday);
        assertTrue(zero.complete());
        assertEquals(-1, zero.bestDay);
    }

    @Test public void weekAcrossYearBoundaryUsesElapsedDaysAndStableLongestTie() {
        LocalDate today = LocalDate.of(2027, 1, 1);
        ReadingCardData.Week week = new ReadingCardData.Week(new ReadingStatistics(Map.of(
                LocalDate.of(2026, 12, 28), 300L, LocalDate.of(2026, 12, 29), 300L,
                LocalDate.of(2026, 12, 30), 0L, LocalDate.of(2026, 12, 31), 0L, today, 0L), 0), today);
        assertTrue(week.complete());
        assertEquals(120, week.averageSeconds());
        assertEquals(0, week.bestDay);
    }

    @Test public void recentBooksExcludeUnknownFutureAndArticlesWithoutMutatingInput() {
        List<ShelfBook> books = Arrays.asList(book("old", "book", 1), book("new", "book", 90),
                book("future", "book", 101), book("unread", "book", 0), book("article", "articles", 100),
                book("middle", "book", 50), book("older", "book", 20));
        assertEquals(Arrays.asList("new", "middle", "older"), ReadingCardData.recentBooks(books, 100)
                .stream().map(book -> book.id).collect(Collectors.toList()));
        assertEquals("old", books.get(0).id);
        assertTrue(ReadingCardData.recentBooks(Collections.emptyList(), 100).isEmpty());
    }

    @Test public void timelineUsesActualNoteDatesAndThoughtContent() {
        BookNote thought = note("thought", "thought", "原文", " 我的想法 ", 90);
        BookNote highlight = note("highlight", "highlight", " 划线原文 ", "", 80);
        List<BookNote> notes = Arrays.asList(note("missing", "highlight", "文字", "", 0),
                thought, highlight, note("future", "highlight", "文字", "", 101),
                note("empty", "thought", " ", " ", 100), note("bookmark", "bookmark", "文字", "", 100),
                note("review", "review", "", "书评", 70), note("old", "highlight", "旧划线", "", 1));
        assertEquals(Arrays.asList("thought", "highlight", "review"), ReadingCardData.recentNotes(notes, 100)
                .stream().map(note -> note.id).collect(Collectors.toList()));
        assertEquals("我的想法", ReadingCardData.preview(thought));
        assertEquals("划线原文", ReadingCardData.preview(highlight));
        assertEquals("原文", ReadingCardData.preview(note("fallback", "thought", "原文", "", 1)));
    }

    @Test public void expandedTimelineKeepsLatestThirtyValidNotesAcrossBooks() {
        List<BookNote> notes = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            notes.add(new BookNote("note-" + i, "book-" + (i % 2), "highlight", "", 0,
                    "笔记" + i, "", i));
        }
        notes.add(note("future", "highlight", "未来", "", 101));
        notes.add(note("unknown", "highlight", "无日期", "", 0));
        notes.add(note("empty", "thought", " ", " ", 100));
        notes.add(note("unsupported", "bookmark", "书签", "", 100));
        List<BookNote> expanded = ReadingCardData.recentNotes(notes, 100, 30);
        assertEquals(30, expanded.size());
        assertEquals("note-40", expanded.get(0).id);
        assertEquals("note-11", expanded.get(29).id);
        assertEquals(3, ReadingCardData.recentNotes(notes, 100).size());
        assertEquals("note-1", notes.get(0).id);
        assertEquals(2, ReadingCardData.recentNotes(notes.subList(0, 2), 100, 30).size());
        assertTrue(ReadingCardData.recentNotes(Collections.emptyList(), 100, 30).isEmpty());
    }

    private static ShelfBook book(String id, String kind, long lastRead) {
        return new ShelfBook(id, id, "", kind, true, 0, 0, 0, "", lastRead);
    }

    private static BookNote note(String id, String kind, String quote, String content, long created) {
        return new BookNote(id, "book", kind, "", 0, quote, content, created);
    }
}
