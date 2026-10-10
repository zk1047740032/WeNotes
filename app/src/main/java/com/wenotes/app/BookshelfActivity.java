package com.wenotes.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BookshelfActivity extends LibraryActivity {
    private static final int SHELF = 0;
    private static final int NOTEBOOKS = 1;
    private static final int FAVORITES = 2;
    private final List<ShelfBook> visibleBooks = new ArrayList<>();
    private final BookAdapter adapter = new BookAdapter();
    private int selectedTab;
    private FavoriteStore favoriteStore;
    private Map<String, ShelfBook> favoriteBooks = new LinkedHashMap<>();
    private final Map<String, ShelfBook> cachedBooks = new LinkedHashMap<>();
    private EditText search;
    private TextView summary;
    private TextView empty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_bookshelf);
        selectedTab = state == null ? SHELF : state.getInt("selected_tab", SHELF);
        favoriteStore = new FavoriteStore(this);
        search = findViewById(R.id.book_search);
        summary = findViewById(R.id.shelf_summary);
        empty = findViewById(R.id.library_empty);
        ListView list = findViewById(R.id.book_list);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ShelfBook book = visibleBooks.get(position);
            startActivity(new Intent(this, BookNotesActivity.class).putExtra("book_id", book.id)
                    .putExtra("book_title", book.title));
        });
        findViewById(R.id.library_back).setOnClickListener(view -> finish());
        findViewById(R.id.library_refresh).setOnClickListener(view -> sync());
        findViewById(R.id.shelf_tab).setOnClickListener(view -> selectTab(SHELF));
        findViewById(R.id.notebooks_tab).setOnClickListener(view -> selectTab(NOTEBOOKS));
        findViewById(R.id.favorites_tab).setOnClickListener(view -> selectTab(FAVORITES));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { showLibrary(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected_tab", selectedTab);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void showLibrary() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        favoriteBooks = favoriteStore.read();
        cachedBooks.clear();
        for (ShelfBook book : snapshot.books) cachedBooks.put(book.id, book);
        List<ShelfBook> candidates = snapshot.books;
        if (selectedTab == FAVORITES) {
            candidates = new ArrayList<>();
            for (ShelfBook favorite : favoriteBooks.values()) {
                candidates.add(cachedBooks.getOrDefault(favorite.id, favorite));
            }
        }
        visibleBooks.clear();
        int total = 0;
        for (ShelfBook book : candidates) {
            if (selectedTab == SHELF && !book.onShelf
                    || selectedTab == NOTEBOOKS && book.noteCount() == 0) continue;
            total++;
            if (query.isEmpty() || (book.title + " " + book.author).toLowerCase(Locale.ROOT).contains(query)) {
                visibleBooks.add(book);
            }
        }
        String count = getString(selectedTab == FAVORITES ? R.string.favorites_total
                : selectedTab == NOTEBOOKS ? R.string.notebooks_total : R.string.shelf_total, total);
        summary.setText(query.isEmpty() ? count : getString(R.string.filtered_count, count, visibleBooks.size()));
        if (selectedTab == FAVORITES) {
            empty.setText(query.isEmpty() ? R.string.empty_favorites : R.string.no_matching_books);
        } else if (!snapshot.complete) {
            empty.setText(new SecretStore(this).hasKey() ? R.string.sync_library_first : R.string.connect_first);
        } else {
            empty.setText(!query.isEmpty() ? R.string.no_matching_books
                    : selectedTab == NOTEBOOKS ? R.string.no_notebooks : R.string.empty_shelf);
        }
        Button shelfTab = findViewById(R.id.shelf_tab);
        Button notesTab = findViewById(R.id.notebooks_tab);
        styleTab(shelfTab, selectedTab == SHELF);
        styleTab(notesTab, selectedTab == NOTEBOOKS);
        styleTab(findViewById(R.id.favorites_tab), selectedTab == FAVORITES);
        TextView hint = findViewById(R.id.library_hint);
        hint.setVisibility(selectedTab == FAVORITES ? View.GONE : View.VISIBLE);
        hint.setText(snapshot.complete ? R.string.library_hint : R.string.legacy_library_hint);
        adapter.notifyDataSetChanged();
    }

    private void selectTab(int tab) {
        selectedTab = tab;
        showLibrary();
        ((ListView) findViewById(R.id.book_list)).setSelection(0);
    }

    private void styleTab(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.button_background : R.drawable.secondary_button_background);
        button.setTextColor(getColor(selected ? R.color.surface : R.color.primary_dark));
    }

    private final class BookAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleBooks.size(); }
        @Override public ShelfBook getItem(int position) { return visibleBooks.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView == null
                    ? LayoutInflater.from(BookshelfActivity.this).inflate(R.layout.item_book, parent, false) : convertView;
            ShelfBook book = getItem(position);
            ((TextView) view.findViewById(R.id.book_badge)).setText("album".equals(book.kind)
                    ? getString(R.string.audio_badge) : "articles".equals(book.kind)
                    ? getString(R.string.articles_badge) : book.title.isEmpty() ? "书" : book.title.substring(0, book.title.offsetByCodePoints(0, 1)));
            ((TextView) view.findViewById(R.id.book_title)).setText(book.title);
            TextView author = view.findViewById(R.id.book_author);
            author.setText(book.author);
            author.setVisibility(book.author.isEmpty() ? View.GONE : View.VISIBLE);
            String detail = !cachedBooks.containsKey(book.id) ? getString(R.string.book_not_cached)
                    : "album".equals(book.kind) ? getString(R.string.audio_unavailable)
                    : "articles".equals(book.kind) ? getString(R.string.articles_unavailable)
                    : getString(R.string.book_note_counts, book.highlightCount, book.reviewCount, book.bookmarkCount);
            ((TextView) view.findViewById(R.id.book_detail)).setText(detail);
            ImageButton favorite = view.findViewById(R.id.book_favorite);
            boolean saved = favoriteBooks.containsKey(book.id);
            favorite.setVisibility("articles".equals(book.kind) || book.id.isEmpty() ? View.GONE : View.VISIBLE);
            favorite.setImageResource(saved ? R.drawable.ic_book_favorite : R.drawable.ic_book_favorite_outline);
            favorite.setContentDescription(getString(saved ? R.string.unfavorite_book_description
                    : R.string.favorite_book_description, book.title));
            favorite.setOnClickListener(button -> {
                try {
                    favoriteStore.toggle(book);
                    showLibrary();
                } catch (IllegalStateException error) {
                    Toast.makeText(BookshelfActivity.this, R.string.favorite_save_failed, Toast.LENGTH_SHORT).show();
                }
            });
            return view;
        }
    }
}
