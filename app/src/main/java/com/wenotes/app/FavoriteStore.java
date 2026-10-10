package com.wenotes.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Local book favorites survive snapshot replacement, but are cleared with account data. */
final class FavoriteStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    FavoriteStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("book_favorites", Context.MODE_PRIVATE);
    }

    Map<String, ShelfBook> read() {
        synchronized (LOCK) {
            Map<String, ShelfBook> books = new LinkedHashMap<>();
            try {
                JSONArray array = new JSONArray(prefs.getString("books", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    ShelfBook book = ShelfBook.fromJson(array.getJSONObject(i));
                    if (!book.id.isEmpty()) books.put(book.id, book);
                }
            } catch (JSONException ignored) {
                // A damaged preference must not prevent opening the library.
            }
            return books;
        }
    }

    void toggle(ShelfBook book) {
        if (book.id.isEmpty() || "articles".equals(book.kind)) return;
        synchronized (LOCK) {
            Map<String, ShelfBook> books = read();
            if (books.containsKey(book.id)) books.remove(book.id);
            else books.put(book.id, book);
            try {
                JSONArray array = new JSONArray();
                for (ShelfBook favorite : books.values()) {
                    // Preserve identity and title if a later sync removes the book from the shelf.
                    // Note counts always come from the current snapshot rather than stale metadata.
                    array.put(new JSONObject().put("id", favorite.id).put("title", favorite.title)
                            .put("author", favorite.author).put("kind", favorite.kind));
                }
                prefs.edit().putString("books", array.toString()).apply();
            } catch (JSONException error) {
                throw new IllegalStateException("Unable to save book favorites", error);
            }
        }
    }

    void clear() {
        synchronized (LOCK) {
            prefs.edit().clear().apply();
        }
    }
}
