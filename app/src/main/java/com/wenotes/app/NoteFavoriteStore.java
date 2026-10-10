package com.wenotes.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.LinkedHashMap;
import java.util.Map;

final class NoteFavoriteStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    NoteFavoriteStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("note_favorites", Context.MODE_PRIVATE);
    }

    Map<String, FavoriteNote> read() {
        synchronized (LOCK) {
            Map<String, FavoriteNote> notes = new LinkedHashMap<>();
            try {
                JSONArray array = new JSONArray(prefs.getString("notes", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    FavoriteNote favorite = FavoriteNote.fromJson(array.getJSONObject(i));
                    if (!favorite.note.id.isEmpty() && !favorite.note.bookId.isEmpty()) {
                        notes.put(FavoriteNote.key(favorite.note), favorite);
                    }
                }
            } catch (JSONException ignored) {
                // A damaged preference must not prevent opening the notes page.
            }
            return notes;
        }
    }

    void toggle(FavoriteNote favorite) {
        if (favorite.note.id.isEmpty() || favorite.note.bookId.isEmpty()) return;
        synchronized (LOCK) {
            Map<String, FavoriteNote> notes = read();
            String key = FavoriteNote.key(favorite.note);
            if (notes.containsKey(key)) notes.remove(key);
            else notes.put(key, favorite);
            try {
                JSONArray array = new JSONArray();
                for (FavoriteNote saved : notes.values()) array.put(saved.toJson());
                prefs.edit().putString("notes", array.toString()).apply();
            } catch (JSONException error) {
                throw new IllegalStateException("Unable to save note favorites", error);
            }
        }
    }

    void clear() {
        synchronized (LOCK) {
            prefs.edit().clear().apply();
        }
    }
}
