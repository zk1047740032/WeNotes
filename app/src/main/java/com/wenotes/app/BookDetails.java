package com.wenotes.app;

import org.json.JSONException;
import org.json.JSONObject;

/** Optional book information; -1 distinguishes unavailable reading data from a real zero. */
final class BookDetails {
    final String title, author, cover, intro, category;
    final int progress;
    final long readingSeconds;

    BookDetails(String title, String author, String cover, String intro, String category,
                int progress, long readingSeconds) {
        this.title = title;
        this.author = author;
        this.cover = cover;
        this.intro = intro;
        this.category = category;
        this.progress = progress >= 0 && progress <= 100 ? progress : -1;
        this.readingSeconds = Math.max(-1, readingSeconds);
    }

    static BookDetails empty() { return new BookDetails("", "", "", "", "", -1, -1); }

    BookDetails withInfo(JSONObject info) {
        Object label = info.opt("category");
        return new BookDetails(info.optString("title", title), info.optString("author", author),
                info.optString("cover", cover), info.optString("intro", intro),
                label instanceof String ? ((String) label).trim() : category, progress, readingSeconds);
    }

    BookDetails withProgress(JSONObject response) {
        JSONObject book = response.optJSONObject("book");
        if (book == null) return this;
        int percentage = book.optInt("progress", -1);
        // recordReadingTime is a separate recorded/recitation metric, often zero.
        long seconds = book.optLong("readingTime", -1);
        return new BookDetails(title, author, cover, intro, category,
                percentage >= 0 && percentage <= 100 ? percentage : progress,
                seconds >= 0 ? seconds : readingSeconds);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("title", title).put("author", author).put("cover", cover)
                .put("intro", intro).put("category", category).put("progress", progress)
                .put("readingTime", readingSeconds);
    }

    static BookDetails fromJson(JSONObject json) {
        // Old readingSeconds caches used recordReadingTime, so refresh only that metric.
        return new BookDetails(json.optString("title"), json.optString("author"), json.optString("cover"),
                json.optString("intro"), json.optString("category"), json.optInt("progress", -1),
                json.optLong("readingTime", -1));
    }
}
