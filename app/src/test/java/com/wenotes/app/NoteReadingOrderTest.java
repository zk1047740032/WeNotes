package com.wenotes.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class NoteReadingOrderTest {
    @Test
    public void chaptersAndNumericSourcePositionsTakePrecedenceOverCreationTime() {
        BookNote late = note("late", 1, "100-20", 1, "highlight", "后面的原文");
        BookNote early = note("early", 1, "20-3", 999, "highlight", "前面的原文");
        BookNote thought = note("thought", 1, "20-3", 0, "thought", "前面的原文");
        BookNote nextChapter = note("next", 2, "0-1", 0, "highlight", "第二章");
        BookNote bookReview = note("review", Long.MAX_VALUE, "", 0, "review", "");
        BookNote anotherBook = new BookNote("other", "other-book", "highlight", "第一章", 1, "别的书", "", 0, "0-1");
        LibrarySnapshot snapshot = snapshot(Arrays.asList(nextChapter, late, bookReview, thought, anotherBook, early));
        assertEquals(Arrays.asList("early", "thought", "late", "next", "review"), ids(NoteReadingOrder.forBook(snapshot, "b")));
        assertEquals("next", snapshot.notes.get(0).id); // Reading must not reorder the shared cache.
    }

    @Test
    public void oldCacheRecoversPositionsByHighlightIdentityAndQuotedText() {
        BookNote late = note("late", 1, "", 1, "highlight", "后文");
        BookNote early = note("early", 1, "", 100, "highlight", "前文");
        BookNote thought = note("review", 1, "", 200, "thought", "前文");
        Highlight lateHighlight = new Highlight("late", "b", "书", "", "第一章", "后文", "", "300-5", 1, 1);
        Highlight earlyHighlight = new Highlight("early", "b", "书", "", "第一章", "前文", "", "20-5", 1, 100);
        LibrarySnapshot snapshot = new LibrarySnapshot(Collections.emptyList(), Arrays.asList(late, thought, early),
                Arrays.asList(lateHighlight, earlyHighlight), true);
        assertEquals(Arrays.asList("early", "review", "late"), ids(NoteReadingOrder.forBook(snapshot, "b")));
    }

    @Test
    public void sourceRangeSurvivesCacheAndMissingRangesRemainCompatible() throws Exception {
        BookNote note = note("n", 1, "9223372036854775800-3", 12, "thought", "原文🙂");
        BookNote restored = BookNote.fromJson(new JSONObject(note.toJson().toString()));
        assertEquals(note.range, restored.range);
        assertEquals(note.quote, restored.quote);
        JSONObject old = note.toJson();
        old.remove("range");
        assertEquals("", BookNote.fromJson(old).range);
        assertEquals(note.content, BookNote.fromJson(old).content);
    }

    @Test
    public void unknownAndMalformedPositionsFollowLocatedNotesWithoutDroppingContent() {
        BookNote malformed = note("malformed", 1, "invalid", 2, "thought", "保留的笔记");
        BookNote negative = note("negative", 1, "-1-5", 3, "thought", "保留的笔记二");
        BookNote valid = note("valid", 1, "10-3", 100, "highlight", "有位置");
        assertEquals(Arrays.asList("valid", "malformed", "negative"),
                ids(NoteReadingOrder.forBook(snapshot(Arrays.asList(malformed, valid, negative)), "b")));
    }

    @Test
    public void syncStoresRangesAndAttachesAnUnlocatedThoughtToItsMatchingQuote() throws Exception {
        LibrarySnapshot snapshot = new LibrarySync((name, params) -> {
            if (name.equals("/shelf/sync")) return new JSONObject("{\"books\":[]}");
            if (name.equals("/user/notebooks")) return new JSONObject("{\"books\":[{\"bookId\":\"b\",\"noteCount\":1,\"reviewCount\":1}]}");
            if (name.equals("/book/bookmarklist")) return new JSONObject("{\"chapters\":[{\"chapterUid\":10,\"chapterIdx\":1,\"title\":\"第一章\"}],\"updated\":[{\"bookmarkId\":\"h\",\"markText\":\"原文\",\"chapterUid\":10,\"range\":\"25-8\"}]}");
            return new JSONObject("{\"reviews\":[{\"review\":{\"reviewId\":\"r\",\"abstract\":\"原文\",\"content\":\"感想\"}}]}");
        }, () -> false, message -> { }).fetch();
        LibrarySnapshot restored = LibrarySnapshot.fromJson(snapshot.toJson());
        assertEquals("25-8", restored.notes.get(0).range);
        assertEquals("25-8", restored.notes.get(1).range);
        assertEquals(1, restored.notes.get(1).chapterOrder);
        assertEquals("感想", restored.notes.get(1).content);
    }

    private static BookNote note(String id, long chapter, String range, long time, String kind, String quote) {
        return new BookNote(id, "b", kind, "第一章", chapter, quote, "thought".equals(kind) ? "想法" : "", time, range);
    }

    private static LibrarySnapshot snapshot(List<BookNote> notes) {
        return new LibrarySnapshot(Collections.emptyList(), notes, Collections.emptyList(), true);
    }

    private static List<String> ids(List<BookNote> notes) {
        return notes.stream().map(note -> note.id).collect(Collectors.toList());
    }
}
