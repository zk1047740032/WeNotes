package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LibrarySyncTest {
    private static JSONObject json(String text) throws Exception { return new JSONObject(text); }

    @Test
    public void shelfIncludesBooksAlbumsAndArticleCollection() throws Exception {
        assertEquals(3, LibrarySync.readShelf(json("{\"books\":[{\"bookId\":\"b\",\"title\":\"书\"}],"
                + "\"albums\":[{\"albumInfo\":{\"albumId\":\"a\",\"name\":\"听书\"}}],\"mp\":{\"title\":\"收藏\"}}" )).size());
        assertEquals(0, LibrarySync.readShelf(json("{\"books\":[],\"albums\":[],\"mp\":{}}" )).size());
    }

    @Test
    public void fetchesAllPagesAndKeepsStandaloneReviewsOutOfWidget() throws Exception {
        List<String> calls = new ArrayList<>();
        LibrarySync.Api api = (name, params) -> {
            calls.add(name);
            switch (name) {
                case "/shelf/sync": return json("{\"books\":[{\"bookId\":\"b1\",\"title\":\"书一\"},{\"bookId\":\"empty\",\"title\":\"无笔记\"}]}");
                case "/user/notebooks":
                    assertEquals(100, params.getInt("count"));
                    assertFalse(params.has("params"));
                    if (!params.has("lastSort")) return json("{\"hasMore\":1,\"books\":[{\"bookId\":\"b1\",\"sort\":100,\"noteCount\":1,\"reviewCount\":3,\"bookmarkCount\":2,\"book\":{\"title\":\"书一\"}}]}");
                    assertEquals(100, params.getLong("lastSort"));
                    return json("{\"hasMore\":0,\"books\":[{\"bookId\":\"removed\",\"sort\":99,\"bookmarkCount\":1,\"book\":{\"title\":\"已移出书架\"}}]}");
                case "/book/bookmarklist":
                    assertEquals("b1", params.getString("bookId"));
                    return json("{\"chapters\":[{\"chapterUid\":10,\"chapterIdx\":1,\"title\":\"第一章\"}],\"updated\":[{\"bookmarkId\":\"m\",\"markText\":\"原文\",\"chapterUid\":10,\"range\":\"1-3\",\"createTime\":10}]}");
                case "/review/list/mine":
                    assertEquals("b1", params.getString("bookid"));
                    if (params.getLong("synckey") == 0) return json("{\"hasMore\":1,\"synckey\":55,\"reviews\":[{\"review\":{\"reviewId\":\"r1\",\"content\":\"划线想法\",\"range\":\"1-3\",\"chapterUid\":10}}]}");
                    assertEquals(55, params.getLong("synckey"));
                    return json("{\"hasMore\":0,\"reviews\":[{\"review\":{\"reviewId\":\"r1\",\"content\":\"划线想法\",\"range\":\"1-3\",\"chapterUid\":10}},{\"review\":{\"reviewId\":\"r2\",\"content\":\"整本书评\"}},{\"review\":{\"reviewId\":\"r3\",\"content\":\"章节想法\",\"chapterUid\":10}}]}");
                default: throw new AssertionError(name);
            }
        };
        LibrarySnapshot result = new LibrarySync(api, () -> false, message -> { }).fetch();
        assertTrue(result.complete);
        assertEquals(3, result.books.size());
        assertEquals(2, result.books.stream().filter(book -> book.onShelf).count());
        assertEquals(6, result.books.get(0).noteCount());
        assertFalse(result.books.get(2).onShelf);
        assertEquals(1, result.books.get(2).bookmarkCount);
        assertEquals(4, result.notes.size());
        assertEquals("thought", result.notes.get(1).kind);
        assertEquals("原文", result.notes.get(1).quote);
        assertEquals("review", result.notes.get(2).kind);
        assertEquals("chapter_review", result.notes.get(3).kind);
        assertEquals(1, result.highlights.size());
        assertEquals("划线想法", result.highlights.get(0).thought);
        assertEquals(6, calls.size());
    }

    @Test
    public void reviewsOnlyBooksStillLoadUnmatchedQuotesAndPlainThoughts() throws Exception {
        LibrarySnapshot result = new LibrarySync((name, params) -> {
            if (name.equals("/shelf/sync")) return json("{\"books\":[]}");
            if (name.equals("/user/notebooks")) return json("{\"books\":[{\"bookId\":\"b\",\"reviewCount\":2}]}");
            if (name.equals("/book/bookmarklist")) return json("{\"updated\":[]}");
            return json("{\"reviews\":[{\"review\":{\"reviewId\":\"r1\",\"content\":\"想法\",\"abstract\":\"没有划线的原文\"}},{\"review\":{\"reviewId\":\"r2\",\"content\":\"普通想法\"}}]}");
        }, () -> false, message -> { }).fetch();
        assertEquals(2, result.notes.size());
        assertEquals(1, result.highlights.size());
        assertEquals("没有划线的原文", result.highlights.get(0).text);
    }

    @Test
    public void rejectsRepeatedNotebookCursor() {
        assertThrows(IllegalStateException.class, () -> new LibrarySync((name, params) ->
                json(name.equals("/shelf/sync") ? "{\"books\":[]}"
                        : "{\"hasMore\":1,\"books\":[{\"bookId\":\"b\",\"sort\":100,\"bookmarkCount\":1}]}"),
                () -> false, message -> { }).fetch());
    }

    @Test
    public void retainsReviewsWithoutTextAndDoesNotAppendBlankWidgetThoughts() throws Exception {
        LibrarySnapshot result = new LibrarySync((name, params) -> {
            if (name.equals("/shelf/sync")) return json("{\"books\":[]}");
            if (name.equals("/user/notebooks")) return json("{\"books\":[{\"bookId\":\"b\",\"noteCount\":1,\"reviewCount\":3}]}");
            if (name.equals("/book/bookmarklist")) return json("{\"updated\":[{\"bookmarkId\":\"m\",\"markText\":\"原文\",\"range\":\"1-3\"}]}");
            return json("{\"reviews\":[{\"review\":{\"reviewId\":\"r1\",\"content\":\"文字想法\",\"range\":\"1-3\"}},{\"review\":{\"reviewId\":\"r2\",\"content\":\"\",\"range\":\"1-3\"}},{\"review\":{\"reviewId\":\"r3\",\"content\":\"\",\"star\":100}}]}");
        }, () -> false, message -> { }).fetch();
        assertEquals(4, result.notes.size());
        assertEquals("", result.notes.get(3).content);
        assertEquals("文字想法", result.highlights.get(0).thought);
    }

    @Test
    public void rejectsRepeatedReviewCursor() {
        assertThrows(IllegalStateException.class, () -> new LibrarySync((name, params) -> {
            if (name.equals("/shelf/sync")) return json("{\"books\":[]}");
            if (name.equals("/user/notebooks")) return json("{\"books\":[{\"bookId\":\"b\",\"reviewCount\":1}]}");
            if (name.equals("/book/bookmarklist")) return json("{\"updated\":[]}");
            return json("{\"hasMore\":1,\"synckey\":1,\"reviews\":[{\"review\":{\"reviewId\":\"r\",\"content\":\"想法\"}}]}");
        }, () -> false, message -> { }).fetch());
    }

    @Test
    public void stopsBeforeMakingAnyRequest() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new LibrarySync((name, params) -> {
            calls.incrementAndGet();
            return new JSONObject();
        }, () -> true, message -> { }).fetch());
        assertEquals(0, calls.get());
    }

    @Test
    public void networkFailureDoesNotProduceAPartialSnapshot() {
        assertThrows(java.io.IOException.class, () -> new LibrarySync((name, params) -> {
            if (name.equals("/shelf/sync")) return json("{\"books\":[{\"bookId\":\"b\"}]}");
            throw new java.io.IOException("offline");
        }, () -> false, message -> { }).fetch());
    }

    @Test
    public void oldCacheKeepsHighlightsAndMarksLibraryAsIncomplete() throws Exception {
        Highlight original = new Highlight("old", "b", "原来的书", "作者", "第一章", "原文", "想法", "1-2", 1, 5);
        LibrarySnapshot result = LibrarySnapshot.fromJson(new JSONArray().put(original.toJson()));
        assertFalse(result.complete);
        assertEquals("原文", result.highlights.get(0).text);
        assertEquals("想法", result.highlights.get(0).thought);
        assertEquals(2, result.notes.size());
        assertEquals(2, result.books.get(0).noteCount());
        assertFalse(result.books.get(0).onShelf);
        LibrarySnapshot restored = LibrarySnapshot.fromJson(new JSONObject(result.toJson().toString()));
        assertEquals(result.notes.get(1).content, restored.notes.get(1).content);
        assertEquals(result.books.get(0).title, restored.books.get(0).title);
    }
}
