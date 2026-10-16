package com.wenotes.app;

import org.json.JSONException;
import org.json.JSONObject;

final class BookNote {
    final String id;
    final String bookId;
    final String kind;
    final String chapter;
    final long chapterOrder;
    final String quote;
    final String content;
    final long createdAt;
    final String range;

    BookNote(String id, String bookId, String kind, String chapter, long chapterOrder,
             String quote, String content, long createdAt) {
        this(id, bookId, kind, chapter, chapterOrder, quote, content, createdAt, "");
    }

    BookNote(String id, String bookId, String kind, String chapter, long chapterOrder,
             String quote, String content, long createdAt, String range) {
        this.id = id;
        this.bookId = bookId;
        this.kind = kind;
        this.chapter = chapter;
        this.chapterOrder = chapterOrder;
        this.quote = quote;
        this.content = content;
        this.createdAt = createdAt;
        this.range = range;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("bookId", bookId).put("kind", kind)
                .put("chapter", chapter).put("chapterOrder", chapterOrder)
                .put("quote", quote).put("content", content).put("createdAt", createdAt).put("range", range);
    }

    static BookNote fromJson(JSONObject json) {
        return new BookNote(json.optString("id"), json.optString("bookId"),
                json.optString("kind", "highlight"), json.optString("chapter"),
                json.optLong("chapterOrder", Long.MAX_VALUE), json.optString("quote"),
                json.optString("content"), json.optLong("createdAt"), json.optString("range"));
    }
}
