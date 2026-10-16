package com.wenotes.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BookDetailsTest {
    @Test public void progressIsAnIntegerPercentageAndDurationIsSeconds() throws Exception {
        BookDetails details = BookDetails.empty().withInfo(new JSONObject()
                .put("title", "书籍").put("category", "历史 · 非虚构").put("intro", "简介"))
                .withProgress(new JSONObject("{\"book\":{\"progress\":1,\"recordReadingTime\":3661}}"));
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
        BookDetails previous = BookDetails.empty().withProgress(new JSONObject("{\"book\":{\"progress\":62,\"recordReadingTime\":46080}}"));
        BookDetails partial = previous.withProgress(new JSONObject("{\"book\":{\"progress\":101,\"recordReadingTime\":-2}}"));
        assertEquals(62, partial.progress);
        assertEquals(46080, partial.readingSeconds);
        assertEquals(62, previous.withProgress(new JSONObject()).progress);
    }

    @Test public void aRealUnreadBookRetainsZeroRatherThanUnknown() throws Exception {
        BookDetails details = BookDetails.empty().withProgress(new JSONObject("{\"book\":{\"progress\":0,\"recordReadingTime\":0}}"));
        assertEquals(0, details.progress);
        assertEquals(0, details.readingSeconds);
    }
}
