package com.wenotes.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FavoriteNoteTest {
    private static BookNote note(String bookId, String id, String kind, String quote, String content) {
        return new BookNote(id, bookId, kind, "第一章", 1, quote, content, 123);
    }

    @Test
    public void noteIdsRemainIndependentAcrossBooksAndKindsCanChange() {
        BookNote first = note("ab", "c", "highlight", "原文", "");
        BookNote second = note("a", "bc", "highlight", "另一条原文", "");
        BookNote sameIdInAnotherBook = note("a", "c", "highlight", "第三条原文", "");
        assertNotEquals(FavoriteNote.key(first), FavoriteNote.key(second));
        assertNotEquals(FavoriteNote.key(first), FavoriteNote.key(sameIdInAnotherBook));
        assertEquals(FavoriteNote.key(first), FavoriteNote.key(note("ab", "c", "thought", "原文", "新想法")));
    }

    @Test
    public void savedQuoteIdentitySurvivesEditsWithoutMatchingOtherQuotes() {
        BookNote original = note("ab", "c", "highlight", "旧原文", "");
        Map<String, FavoriteNote> saved = new LinkedHashMap<>();
        saved.put(FavoriteNote.key(original), new FavoriteNote(original, "旧书名", "作者"));
        Highlight edited = new Highlight("c", "ab", "新书名", "作者", "第二章",
                "更新后的原文", "新增想法", "", 2, 456);
        assertTrue(saved.containsKey(FavoriteNote.key(edited.bookId, edited.id)));
        assertFalse(saved.containsKey(FavoriteNote.key("a", "bc")));
        assertFalse(saved.containsKey(FavoriteNote.key("ab", "another-note")));
    }

    @Test
    public void editedNotesAndRenamedBooksUseCurrentSnapshotWithoutAddingFavorites() {
        FavoriteNote saved = new FavoriteNote(note("b", "n", "review", "", "旧想法"), "旧书名", "旧作者");
        Map<String, FavoriteNote> favorites = new LinkedHashMap<>();
        favorites.put(FavoriteNote.key(saved.note), saved);
        BookNote edited = note("b", "n", "chapter_review", "", "更新后的章节想法");
        LibrarySnapshot snapshot = new LibrarySnapshot(Arrays.asList(
                new ShelfBook("b", "新书名", "新作者", "book", true, 0, 2, 0)),
                Arrays.asList(edited, note("b", "other", "review", "", "未收藏")), new ArrayList<>(), true);
        List<FavoriteNote> resolved = FavoriteNote.resolve(favorites, snapshot);
        assertEquals(1, resolved.size());
        assertEquals("更新后的章节想法", resolved.get(0).note.content);
        assertEquals("chapter_review", resolved.get(0).note.kind);
        assertEquals("新书名", resolved.get(0).bookTitle);
        assertEquals("新作者", resolved.get(0).bookAuthor);
    }

    @Test
    public void savedNotesRemainReadableWhenAbsentFromSnapshot() throws Exception {
        FavoriteNote saved = FavoriteNote.fromJson(new FavoriteNote(
                note("b", "n", "thought", "第一行\n第二行🙂", "自己的想法"), "书名", "作者").toJson());
        Map<String, FavoriteNote> favorites = new LinkedHashMap<>();
        favorites.put(FavoriteNote.key(saved.note), saved);
        FavoriteNote resolved = FavoriteNote.resolve(favorites, LibrarySnapshot.empty()).get(0);
        assertEquals("第一行\n第二行🙂", resolved.note.quote);
        assertEquals("自己的想法", resolved.note.content);
        assertEquals("第一章", resolved.note.chapter);
        assertEquals(123, resolved.note.createdAt);
        assertEquals("书名", resolved.bookTitle);
        assertEquals("作者", resolved.bookAuthor);
    }

    @Test
    public void swipedQuoteUsesTheOriginalNoteIdentityAndKind() {
        Highlight highlight = new Highlight("n", "b", "书名", "作者", "第一章",
                "原文", "关联想法", "", 1, 123);
        BookNote original = note("b", "n", "highlight", "原文", "");
        LibrarySnapshot snapshot = new LibrarySnapshot(new ArrayList<>(), Arrays.asList(
                note("another-book", "n", "thought", "别的书", ""), original),
                Arrays.asList(highlight), true);
        FavoriteNote favorite = FavoriteNote.fromHighlight(highlight, snapshot);
        assertSame(original, favorite.note);
        assertEquals(FavoriteNote.key(original), FavoriteNote.key(favorite.note));
        assertEquals("书名", favorite.bookTitle);
        assertEquals("作者", favorite.bookAuthor);
    }

    @Test
    public void swipedQuoteWithoutMatchingNoteKeepsReadableContent() throws Exception {
        Highlight highlight = new Highlight("n", "b", "书名", "作者", "第一章",
                "原文", "自己的想法", "", 0, 123);
        FavoriteNote favorite = FavoriteNote.fromJson(
                FavoriteNote.fromHighlight(highlight, LibrarySnapshot.empty()).toJson());
        assertEquals("n", favorite.note.id);
        assertEquals("b", favorite.note.bookId);
        assertEquals("原文", favorite.note.quote);
        assertEquals("自己的想法", favorite.note.content);
        assertEquals("thought", favorite.note.kind);
        assertEquals(Long.MAX_VALUE, favorite.note.chapterOrder);
    }
}
