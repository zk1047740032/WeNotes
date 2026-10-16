package com.wenotes.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Uses the source position rather than the time a reader happened to make a note. */
final class NoteReadingOrder {
    private NoteReadingOrder() { }

    static List<BookNote> forBook(LibrarySnapshot snapshot, String bookId) {
        Map<String, Highlight> byId = new HashMap<>();
        Map<String, Highlight> byQuote = new HashMap<>();
        for (Highlight highlight : snapshot.highlights) {
            if (!highlight.bookId.equals(bookId)) continue;
            byId.put(highlight.id, highlight);
            byQuote.putIfAbsent(quoteKey(highlight.chapter, highlight.text), highlight);
        }
        List<BookNote> notes = new ArrayList<>();
        Map<String, Long> positions = new HashMap<>();
        for (BookNote note : snapshot.notes) {
            if (!note.bookId.equals(bookId)) continue;
            notes.add(note);
            long position = position(note.range);
            if (position == Long.MAX_VALUE) {
                Highlight highlight = byId.get(note.id);
                if (highlight == null && !note.quote.isEmpty()) {
                    highlight = byQuote.get(quoteKey(note.chapter, note.quote));
                }
                if (highlight != null) position = position(highlight.range);
            }
            positions.put(note.id, position);
        }
        notes.sort(Comparator.comparingLong((BookNote note) -> note.chapterOrder)
                .thenComparing(note -> note.chapter)
                .thenComparingLong(note -> positions.get(note.id))
                .thenComparingInt(note -> "highlight".equals(note.kind) ? 0 : 1)
                .thenComparingLong(note -> note.createdAt).thenComparing(note -> note.id));
        return notes;
    }

    private static String quoteKey(String chapter, String quote) {
        return chapter.length() + ":" + chapter + quote;
    }

    private static long position(String range) {
        String value = range.trim();
        int separator = value.indexOf('-');
        if (separator <= 0) return Long.MAX_VALUE;
        try {
            long start = Long.parseLong(value.substring(0, separator).trim());
            long length = Long.parseLong(value.substring(separator + 1).trim());
            return start >= 0 && length >= 0 ? start : Long.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
