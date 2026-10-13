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
        return key(note.bookId, note.id);
    }

    static String key(String bookId, String noteId) {
        // Length-prefix the book ID so IDs from different books cannot collide.
        return bookId.length() + ":" + bookId + noteId;
    }

    static FavoriteNote fromHighlight(Highlight highlight, LibrarySnapshot snapshot) {
        for (BookNote note : snapshot.notes) {
            if (note.id.equals(highlight.id) && note.bookId.equals(highlight.bookId)) {
                return new FavoriteNote(note, highlight.title, highlight.author);
            }
        }
        long order = highlight.chapterUid == 0 ? Long.MAX_VALUE : highlight.chapterUid;
        BookNote note = new BookNote(highlight.id, highlight.bookId,
                highlight.thought.isEmpty() ? "highlight" : "thought", highlight.chapter,
                order, highlight.text, highlight.thought, highlight.createdAt);
        return new FavoriteNote(note, highlight.title, highlight.author);
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
