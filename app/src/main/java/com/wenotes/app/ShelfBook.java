package com.wenotes.app;

import org.json.JSONException;
import org.json.JSONObject;

final class ShelfBook {
    final String id;
    final String title;
    final String author;
    final String kind;
    final boolean onShelf;
    final int highlightCount;
    final int reviewCount;
    final int bookmarkCount;

    ShelfBook(String id, String title, String author, String kind, boolean onShelf,
              int highlightCount, int reviewCount, int bookmarkCount) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.kind = kind;
        this.onShelf = onShelf;
        this.highlightCount = highlightCount;
        this.reviewCount = reviewCount;
        this.bookmarkCount = bookmarkCount;
    }

    int noteCount() {
        return highlightCount + reviewCount + bookmarkCount;
    }

    ShelfBook withNotebook(JSONObject entry) {
        JSONObject info = entry.optJSONObject("book");
        return new ShelfBook(id, info == null ? title : info.optString("title", title),
                info == null ? author : info.optString("author", author), kind, onShelf,
                entry.optInt("noteCount"), entry.optInt("reviewCount"), entry.optInt("bookmarkCount"));
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("author", author)
                .put("kind", kind).put("onShelf", onShelf).put("highlightCount", highlightCount)
                .put("reviewCount", reviewCount).put("bookmarkCount", bookmarkCount);
    }

    static ShelfBook fromJson(JSONObject json) {
        return new ShelfBook(json.optString("id"), json.optString("title", "未知书籍"),
                json.optString("author"), json.optString("kind", "book"), json.optBoolean("onShelf"),
                json.optInt("highlightCount"), json.optInt("reviewCount"), json.optInt("bookmarkCount"));
    }
}
