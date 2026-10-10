package com.wenotes.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps a readable local copy when a saved note is absent from a later sync. */
final class FavoriteNote {
    final BookNote note;
    final String bookTitle;
    final String bookAuthor;

    FavoriteNote(BookNote note, String bookTitle, String bookAuthor) {
        this.note = note;
        this.bookTitle = bookTitle;
        this.bookAuthor = bookAuthor;
    }

    static String key(BookNote note) {
        // Length-prefix the book ID so IDs from different books cannot collide.
        return note.bookId.length() + ":" + note.bookId + note.id;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("note", note.toJson())
                .put("bookTitle", bookTitle).put("bookAuthor", bookAuthor);
    }

    static FavoriteNote fromJson(JSONObject json) throws JSONException {
        return new FavoriteNote(BookNote.fromJson(json.getJSONObject("note")),
                json.optString("bookTitle", "未知书籍"), json.optString("bookAuthor"));
    }

    static List<FavoriteNote> resolve(Map<String, FavoriteNote> saved, LibrarySnapshot snapshot) {
        Map<String, BookNote> currentNotes = new LinkedHashMap<>();
        Map<String, ShelfBook> currentBooks = new LinkedHashMap<>();
        for (BookNote note : snapshot.notes) currentNotes.put(key(note), note);
        for (ShelfBook book : snapshot.books) currentBooks.put(book.id, book);
        List<FavoriteNote> result = new ArrayList<>();
        for (FavoriteNote favorite : saved.values()) {
            BookNote note = currentNotes.getOrDefault(key(favorite.note), favorite.note);
            ShelfBook book = currentBooks.get(note.bookId);
            result.add(new FavoriteNote(note, book == null ? favorite.bookTitle : book.title,
                    book == null ? favorite.bookAuthor : book.author));
        }
        return result;
    }
}
