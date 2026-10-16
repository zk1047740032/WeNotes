package com.wenotes.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BookDetailsTest {
    @Test public void progressIsAnIntegerPercentageAndDurationIsSeconds() throws Exception {
        BookDetails details = BookDetails.empty().withInfo(new JSONObject()
                .put("title", "书籍").put("category", "历史 · 非虚构").put("intro", "简介"))
                .withProgress(new JSONObject("{\"book\":{\"progress\":1,\"readingTime\":3661,\"recordReadingTime\":0}}"));
        assertEquals(1, details.progress);
        assertEquals(3661, details.readingSeconds);
        assertEquals("历史 · 非虚构", details.category);
        BookDetails restored = BookDetails.fromJson(new JSONObject(details.toJson().toString()));
        assertEquals(1, restored.progress);
        assertEquals(3661, restored.readingSeconds);
        assertEquals("简介", restored.intro);
    }

    @Test public void unavailableValuesRemainUnknownAndDoNotEraseCachedProgress() throws Exception {
        assertEquals(-1, BookDetails.empty().progress);
        assertEquals(-1, BookDetails.fromJson(new JSONObject()).readingSeconds);
        BookDetails previous = BookDetails.empty().withProgress(new JSONObject("{\"book\":{\"progress\":62,\"readingTime\":46080}}"));
        BookDetails partial = previous.withProgress(new JSONObject("{\"book\":{\"progress\":101,\"readingTime\":-2}}"));
        assertEquals(62, partial.progress);
        assertEquals(46080, partial.readingSeconds);
        assertEquals(62, previous.withProgress(new JSONObject()).progress);
    }

    @Test public void aRealUnreadBookRetainsZeroRatherThanUnknown() throws Exception {
        BookDetails details = BookDetails.empty().withProgress(new JSONObject("{\"book\":{\"progress\":0,\"readingTime\":0,\"recordReadingTime\":900}}"));
        assertEquals(0, details.progress);
        assertEquals(0, details.readingSeconds);
    }

    @Test public void recordedReadingIsNotUsedAsTotalReadingTime() throws Exception {
        assertEquals(-1, BookDetails.empty().withProgress(new JSONObject(
                "{\"book\":{\"progress\":30,\"recordReadingTime\":120}}")).readingSeconds);
        BookDetails previous = new BookDetails("书籍", "作者", "", "", "", 30, 3661);
        assertEquals(3661, previous.withProgress(new JSONObject(
                "{\"book\":{\"recordReadingTime\":0}}")).readingSeconds);
        assertEquals(3661, previous.withProgress(new JSONObject(
                "{\"book\":{\"readingTime\":null,\"recordReadingTime\":0}}")).readingSeconds);
    }

    @Test public void oldRecordedTimeCacheIsDiscardedWithoutLosingBookDetails() throws Exception {
        BookDetails restored = BookDetails.fromJson(new JSONObject()
                .put("title", "书籍").put("author", "作者").put("intro", "简介")
                .put("progress", 30).put("readingSeconds", 0));
        assertEquals(-1, restored.readingSeconds);
        assertEquals(30, restored.progress);
        assertEquals("书籍", restored.title);
        assertEquals("作者", restored.author);
        assertEquals("简介", restored.intro);
        BookDetails updated = restored.withProgress(new JSONObject(
                "{\"book\":{\"readingTime\":3661,\"recordReadingTime\":0}}"));
        assertEquals(3661, BookDetails.fromJson(new JSONObject(updated.toJson().toString())).readingSeconds);
    }
}
