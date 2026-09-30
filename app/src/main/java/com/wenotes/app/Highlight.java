package com.wenotes.app;

import org.json.JSONException;
import org.json.JSONObject;

final class Highlight {
    final String id;
    final String bookId;
    final String title;
    final String author;
    final String chapter;
    final String text;
    final String thought;
    final String range;
    final long chapterUid;
    final long createdAt;

    Highlight(String id, String bookId, String title, String author, String chapter,
              String text, String thought, String range, long chapterUid, long createdAt) {
        this.id = id;
        this.bookId = bookId;
        this.title = title;
        this.author = author;
        this.chapter = chapter;
        this.text = text;
        this.thought = thought;
        this.range = range;
        this.chapterUid = chapterUid;
        this.createdAt = createdAt;
    }

    Highlight withThought(String addition) {
        String combined = thought.isEmpty() ? addition : thought + "\n" + addition;
        return new Highlight(id, bookId, title, author, chapter, text, combined,
                range, chapterUid, createdAt);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("bookId", bookId);
        json.put("title", title);
        json.put("author", author);
        json.put("chapter", chapter);
        json.put("text", text);
        json.put("thought", thought);
        json.put("range", range);
        json.put("chapterUid", chapterUid);
        json.put("createdAt", createdAt);
        return json;
    }

    static Highlight fromJson(JSONObject json) {
        return new Highlight(json.optString("id"), json.optString("bookId"),
                json.optString("title"), json.optString("author"),
                json.optString("chapter"), json.optString("text"),
                json.optString("thought"), json.optString("range"),
                json.optLong("chapterUid"), json.optLong("createdAt"));
    }
}
