package com.wenotes.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small projections of the existing cache; unknown dates never become zero-reading days. */
final class ReadingCardData {
    static final class Week {
        final LocalDate today;
        final LocalDate monday;
        final Long[] seconds = new Long[7];
        long totalSeconds;
        int knownDays;
        int bestDay = -1;

        Week(ReadingStatistics statistics, LocalDate today) {
            this.today = today;
            monday = ReadingStatistics.weekStart(today);
            if (statistics == null) return;
            for (int i = 0; i < today.getDayOfWeek().getValue(); i++) {
                seconds[i] = statistics.secondsOn(monday.plusDays(i));
                if (seconds[i] == null) continue;
                knownDays++;
                totalSeconds += seconds[i];
                if (seconds[i] > 0 && (bestDay < 0 || seconds[i] > seconds[bestDay])) bestDay = i;
            }
        }

        boolean complete() { return knownDays == today.getDayOfWeek().getValue(); }
        long averageSeconds() { return knownDays == 0 ? 0 : totalSeconds / knownDays; }

        double chartStepHours() {
            if (bestDay < 0) return 2;
            double required = Math.max(0.5, seconds[bestDay] / 3600.0 / 3);
            double magnitude = Math.pow(10, Math.floor(Math.log10(required)));
            double fraction = required / magnitude;
            return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * magnitude;
        }

        int chartIntervals() {
            if (bestDay < 0) return 1;
            return (int) Math.floor(seconds[bestDay] / (chartStepHours() * 3600)) + 1;
        }
    }

    static List<ShelfBook> recentBooks(List<ShelfBook> books, long now) {
        List<ShelfBook> recent = new ArrayList<>();
        for (ShelfBook book : books) {
            if (!"articles".equals(book.kind) && book.lastReadAt > 0 && book.lastReadAt <= now) recent.add(book);
        }
        recent.sort(Comparator.comparingLong((ShelfBook book) -> book.lastReadAt).reversed()
                .thenComparing(book -> book.id));
        return new ArrayList<>(recent.subList(0, Math.min(3, recent.size())));
    }

    static List<BookNote> recentNotes(List<BookNote> notes, long now) {
        List<BookNote> recent = new ArrayList<>();
        for (BookNote note : notes) {
            if (note.createdAt <= 0 || note.createdAt > now || preview(note).isEmpty()) continue;
            if ("highlight".equals(note.kind) || "thought".equals(note.kind)
                    || "review".equals(note.kind) || "chapter_review".equals(note.kind)) recent.add(note);
        }
        recent.sort(Comparator.comparingLong((BookNote note) -> note.createdAt).reversed()
                .thenComparing(note -> note.bookId).thenComparing(note -> note.id));
        return new ArrayList<>(recent.subList(0, Math.min(3, recent.size())));
    }

    static String preview(BookNote note) {
        return ("highlight".equals(note.kind) || note.content.trim().isEmpty()
                ? note.quote : note.content).trim();
    }
}
