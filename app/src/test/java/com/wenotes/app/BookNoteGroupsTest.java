package com.wenotes.app;

import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class BookNoteGroupsTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test public void sourceModeKeepsChaptersTogetherAndPreservesSourcePositionOrder() {
        List<FavoriteNote> source = Arrays.asList(note("early", 1, "第一章", "2026-10-15T12:00:00Z"),
                note("late", 1, "第一章", "2026-10-11T12:00:00Z"), note("next", 2, "第二章", "2026-10-13T12:00:00Z"));
        List<BookNoteGroups> groups = BookNoteGroups.build(source, false, false, ZONE);
        assertEquals(2, groups.size());
        assertEquals(Arrays.asList("early", "late"), ids(groups.get(0)));
        assertEquals("", groups.get(0).date);
        assertEquals("early", source.get(0).note.id);
    }

    @Test public void timeModeGroupsLocalDatesAndSameDayChaptersInBothDirections() {
        List<FavoriteNote> source = Arrays.asList(note("a", 1, "第一章", "2026-10-11T15:59:00Z"),
                note("b", 1, "第一章", "2026-10-11T16:01:00Z"),
                note("c", 2, "第二章", "2026-10-13T12:00:00Z"),
                note("d", 2, "第二章", "2026-10-13T12:01:00Z"));
        List<BookNoteGroups> latest = BookNoteGroups.build(source, true, false, ZONE);
        assertEquals(3, latest.size());
        assertEquals("2026-10-13", latest.get(0).date);
        assertEquals(Arrays.asList("d", "c"), ids(latest.get(0)));
        assertEquals("2026-10-12", latest.get(1).date);
        List<BookNoteGroups> oldest = BookNoteGroups.build(source, true, true, ZONE);
        assertEquals("2026-10-11", oldest.get(0).date);
        assertEquals(Arrays.asList("c", "d"), ids(oldest.get(2)));
    }

    @Test public void unknownTimesAreRetainedAtTheEndAndChapterNamesDoNotMerge() {
        FavoriteNote unknown = new FavoriteNote(new BookNote("unknown", "b", "review", "", Long.MAX_VALUE, "", "想法", 0), "书", "");
        List<FavoriteNote> source = Arrays.asList(unknown, note("a", 1, "第一章", "2026-10-13T12:00:00Z"),
                note("b", 1, "另一章", "2026-10-13T12:01:00Z"));
        for (boolean oldest : new boolean[]{false,true}) {
            List<BookNoteGroups> groups = BookNoteGroups.build(source, true, oldest, ZONE);
            assertEquals(3, groups.size());
            assertEquals("unknown", groups.get(2).date);
            assertEquals("unknown", groups.get(2).notes.get(0).note.id);
        }
    }

    private static FavoriteNote note(String id, long chapter, String name, String timestamp) {
        return new FavoriteNote(new BookNote(id, "b", "highlight", name, chapter, id, "",
                Instant.parse(timestamp).getEpochSecond()), "书", "作者");
    }
    private static List<String> ids(BookNoteGroups group) {
        return group.notes.stream().map(entry -> entry.note.id).collect(Collectors.toList());
    }
}
