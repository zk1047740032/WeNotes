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
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BookshelfActivity extends LibraryActivity {
    private final List<ShelfBook> visibleBooks = new ArrayList<>();
    private final BookAdapter adapter = new BookAdapter();
    private boolean notebooks;
    private EditText search;
    private TextView summary;
    private TextView empty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_bookshelf);
        notebooks = state != null && state.getBoolean("notebooks");
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
        findViewById(R.id.shelf_tab).setOnClickListener(view -> { notebooks = false; showLibrary(); });
        findViewById(R.id.notebooks_tab).setOnClickListener(view -> { notebooks = true; showLibrary(); });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { showLibrary(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("notebooks", notebooks);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void showLibrary() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visibleBooks.clear();
        int total = 0;
        for (ShelfBook book : snapshot.books) {
            if (notebooks ? book.noteCount() == 0 : !book.onShelf) continue;
            total++;
            if (query.isEmpty() || (book.title + " " + book.author).toLowerCase(Locale.ROOT).contains(query)) {
                visibleBooks.add(book);
            }
        }
        String count = getString(notebooks ? R.string.notebooks_total : R.string.shelf_total, total);
        summary.setText(query.isEmpty() ? count : getString(R.string.filtered_count, count, visibleBooks.size()));
        if (!snapshot.complete) {
            empty.setText(new SecretStore(this).hasKey() ? R.string.sync_library_first : R.string.connect_first);
        } else {
            empty.setText(!query.isEmpty() ? R.string.no_matching_books
                    : notebooks ? R.string.no_notebooks : R.string.empty_shelf);
        }
        Button shelfTab = findViewById(R.id.shelf_tab);
        Button notesTab = findViewById(R.id.notebooks_tab);
        styleTab(shelfTab, !notebooks);
        styleTab(notesTab, notebooks);
        TextView hint = findViewById(R.id.library_hint);
        hint.setText(snapshot.complete ? R.string.library_hint : R.string.legacy_library_hint);
        adapter.notifyDataSetChanged();
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
            String detail = "album".equals(book.kind) ? getString(R.string.audio_unavailable)
                    : "articles".equals(book.kind) ? getString(R.string.articles_unavailable)
                    : getString(R.string.book_note_counts, book.highlightCount, book.reviewCount, book.bookmarkCount);
            ((TextView) view.findViewById(R.id.book_detail)).setText(detail);
            return view;
        }
    }
}
