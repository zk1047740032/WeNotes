package com.wenotes.app;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source order groups chapters; time order groups dates, then chapters within each date. */
final class BookNoteGroups {
    final String key;
    final String date;
    final String chapter;
    final List<FavoriteNote> notes = new ArrayList<>();

    private BookNoteGroups(String key, String date, String chapter) {
        this.key = key;
        this.date = date;
        this.chapter = chapter;
    }

    static List<BookNoteGroups> build(List<FavoriteNote> sourceOrder, boolean byTime,
                                      boolean oldestFirst, ZoneId zone) {
        List<FavoriteNote> ordered = new ArrayList<>(sourceOrder);
        if (byTime) {
            Comparator<FavoriteNote> time = Comparator.comparingLong(entry -> entry.note.createdAt);
            if (!oldestFirst) time = time.reversed();
            ordered.sort(Comparator.comparingInt((FavoriteNote entry) -> entry.note.createdAt <= 0 ? 1 : 0)
                    .thenComparing(time).thenComparing(entry -> entry.note.id));
        }
        Map<String, BookNoteGroups> groups = new LinkedHashMap<>();
        for (FavoriteNote entry : ordered) {
            BookNote note = entry.note;
            String date = !byTime ? "" : note.createdAt <= 0 ? "unknown"
                    : Instant.ofEpochSecond(note.createdAt).atZone(zone).toLocalDate().toString();
            String key = date + ":" + note.chapterOrder + ":" + note.chapter;
            BookNoteGroups group = groups.get(key);
            if (group == null) {
                group = new BookNoteGroups(key, date, note.chapter);
                groups.put(key, group);
            }
            group.notes.add(entry);
        }
        return new ArrayList<>(groups.values());
    }
}
