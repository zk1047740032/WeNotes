package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LibrarySnapshot {
    final List<ShelfBook> books;
    final List<BookNote> notes;
    final List<Highlight> highlights;
    final boolean complete;

    LibrarySnapshot(List<ShelfBook> books, List<BookNote> notes, List<Highlight> highlights,
                    boolean complete) {
        this.books = books;
        this.notes = notes;
        this.highlights = highlights;
        this.complete = complete;
    }

    static LibrarySnapshot empty() {
        return new LibrarySnapshot(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false);
    }

    JSONObject toJson() throws JSONException {
        JSONArray bookArray = new JSONArray();
        JSONArray noteArray = new JSONArray();
        JSONArray highlightArray = new JSONArray();
        for (ShelfBook book : books) bookArray.put(book.toJson());
        for (BookNote note : notes) noteArray.put(note.toJson());
        for (Highlight highlight : highlights) highlightArray.put(highlight.toJson());
        return new JSONObject().put("version", 2).put("complete", complete)
                .put("books", bookArray).put("notes", noteArray).put("highlights", highlightArray);
    }

    static LibrarySnapshot fromJson(Object value) throws JSONException {
        if (value instanceof JSONArray) return fromLegacy((JSONArray) value);
        if (!(value instanceof JSONObject)) throw new JSONException("无效的本地笔记缓存");
        JSONObject json = (JSONObject) value;
        List<ShelfBook> books = new ArrayList<>();
        List<BookNote> notes = new ArrayList<>();
        List<Highlight> highlights = new ArrayList<>();
        JSONArray array = json.getJSONArray("books");
        for (int i = 0; i < array.length(); i++) books.add(ShelfBook.fromJson(array.getJSONObject(i)));
        array = json.getJSONArray("notes");
        for (int i = 0; i < array.length(); i++) notes.add(BookNote.fromJson(array.getJSONObject(i)));
        array = json.getJSONArray("highlights");
        for (int i = 0; i < array.length(); i++) highlights.add(Highlight.fromJson(array.getJSONObject(i)));
        return new LibrarySnapshot(books, notes, highlights, json.optBoolean("complete"));
    }

    private static LibrarySnapshot fromLegacy(JSONArray array) throws JSONException {
        List<Highlight> highlights = new ArrayList<>();
        List<BookNote> notes = new ArrayList<>();
        Map<String, ShelfBook> books = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            Highlight highlight = Highlight.fromJson(array.getJSONObject(i));
            highlights.add(highlight);
            ShelfBook previous = books.get(highlight.bookId);
            books.put(highlight.bookId, new ShelfBook(highlight.bookId, highlight.title,
                    highlight.author, "book", false, previous == null ? 1 : previous.highlightCount + 1,
                    (previous == null ? 0 : previous.reviewCount) + (highlight.thought.isEmpty() ? 0 : 1), 0));
            long order = highlight.chapterUid == 0 ? Long.MAX_VALUE : highlight.chapterUid;
            notes.add(new BookNote(highlight.id, highlight.bookId, "highlight", highlight.chapter,
                    order, highlight.text, "", highlight.createdAt));
            if (!highlight.thought.isEmpty()) {
                notes.add(new BookNote(highlight.id + ":thought", highlight.bookId, "thought",
                        highlight.chapter, order, highlight.text, highlight.thought, highlight.createdAt));
            }
        }
        return new LibrarySnapshot(new ArrayList<>(books.values()), notes, highlights, false);
    }
}
