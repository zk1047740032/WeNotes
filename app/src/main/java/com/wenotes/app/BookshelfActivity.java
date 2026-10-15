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
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BookshelfActivity extends LibraryActivity {
    private static final int RECENT = 0;
    private static final int FAVORITES = 2;
    private final List<ShelfBook> visibleBooks = new ArrayList<>();
    private final BookAdapter adapter = new BookAdapter();
    private int selectedTab;
    private RecentReading.Period selectedPeriod = RecentReading.Period.WEEK;
    private FavoriteStore favoriteStore;
    private CoverLoader coverLoader;
    private Map<String, ShelfBook> favoriteBooks = new LinkedHashMap<>();
    private final Map<String, ShelfBook> cachedBooks = new LinkedHashMap<>();
    private EditText search;
    private TextView summary;
    private TextView empty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_bookshelf);
        selectedTab = state == null ? RECENT : state.getInt("selected_tab", RECENT);
        if (selectedTab != FAVORITES) selectedTab = RECENT;
        if (state != null) {
            int period = state.getInt("selected_period", 0);
            RecentReading.Period[] periods = RecentReading.Period.values();
            if (period >= 0 && period < periods.length) selectedPeriod = periods[period];
        }
        favoriteStore = new FavoriteStore(this);
        coverLoader = new CoverLoader(this);
        search = findViewById(R.id.book_search);
        summary = findViewById(R.id.shelf_summary);
        empty = findViewById(R.id.library_empty);
        GridView list = findViewById(R.id.book_list);
        list.setNumColumns(getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 3);
        AppNavigation.bind(this, 1);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ShelfBook book = visibleBooks.get(position);
            startActivity(new Intent(this, BookNotesActivity.class).putExtra("book_id", book.id)
                    .putExtra("book_title", book.title));
        });
        findViewById(R.id.recent_tab).setOnClickListener(view -> selectTab(RECENT));
        findViewById(R.id.favorites_tab).setOnClickListener(view -> selectTab(FAVORITES));
        findViewById(R.id.recent_week).setOnClickListener(view -> selectPeriod(RecentReading.Period.WEEK));
        findViewById(R.id.recent_month).setOnClickListener(view -> selectPeriod(RecentReading.Period.MONTH));
        findViewById(R.id.recent_half_year).setOnClickListener(view -> selectPeriod(RecentReading.Period.HALF_YEAR));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { showLibrary(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected_tab", selectedTab);
        state.putInt("selected_period", selectedPeriod.ordinal());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openRecent();
    }

    @Override
    protected void onDestroy() {
        coverLoader.dispose();
        super.onDestroy();
    }

    void openRecent() { selectTab(RECENT); }

    @Override
    protected void showLibrary() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        favoriteBooks = favoriteStore.read();
        cachedBooks.clear();
        for (ShelfBook book : snapshot.books) cachedBooks.put(book.id, book);
        List<ShelfBook> candidates = snapshot.books;
        if (selectedTab == RECENT) {
            candidates = RecentReading.filter(snapshot.books, selectedPeriod,
                    Instant.now().getEpochSecond(), ZoneId.systemDefault());
        } else if (selectedTab == FAVORITES) {
            candidates = new ArrayList<>();
            for (ShelfBook favorite : favoriteBooks.values()) {
                candidates.add(cachedBooks.getOrDefault(favorite.id, favorite));
            }
        }
        visibleBooks.clear();
        int total = 0;
        for (ShelfBook book : candidates) {
            if (book.noteCount() <= 0) continue;
            total++;
            if (query.isEmpty() || (book.title + " " + book.author).toLowerCase(Locale.ROOT).contains(query)) {
                visibleBooks.add(book);
            }
        }
        String count = selectedTab == RECENT ? getString(R.string.recent_total, periodLabel(), total)
                : getString(R.string.favorites_total, total);
        summary.setText(selectedTab == RECENT && snapshot.complete && !snapshot.recentReadingAvailable
                ? getString(R.string.recent_reading_unavailable)
                : query.isEmpty() ? count : getString(R.string.filtered_count, count, visibleBooks.size()));
        if (selectedTab == FAVORITES) {
            empty.setText(query.isEmpty() ? R.string.empty_favorites : R.string.no_matching_books);
        } else if (!snapshot.complete) {
            empty.setText(new SecretStore(this).hasKey() ? R.string.sync_library_first : R.string.connect_first);
        } else if (selectedTab == RECENT && !snapshot.recentReadingAvailable) {
            empty.setText(R.string.sync_recent_reading_first);
        } else {
            empty.setText(!query.isEmpty() ? getString(R.string.no_matching_books)
                    : getString(R.string.no_recent_books, periodLabel()));
        }
        Button recentTab = findViewById(R.id.recent_tab);
        styleTab(recentTab, selectedTab == RECENT);
        styleTab(findViewById(R.id.favorites_tab), selectedTab == FAVORITES);
        findViewById(R.id.recent_periods).setVisibility(selectedTab == RECENT ? View.VISIBLE : View.GONE);
        styleTab(findViewById(R.id.recent_week), selectedPeriod == RecentReading.Period.WEEK);
        styleTab(findViewById(R.id.recent_month), selectedPeriod == RecentReading.Period.MONTH);
        styleTab(findViewById(R.id.recent_half_year), selectedPeriod == RecentReading.Period.HALF_YEAR);
        AppNavigation.bind(this, 1);
        coverLoader.loadMissing(snapshot.books, adapter::notifyDataSetChanged);
        TextView hint = findViewById(R.id.library_hint);
        hint.setVisibility(selectedTab == FAVORITES || snapshot.complete ? View.GONE : View.VISIBLE);
        hint.setText(snapshot.complete ? R.string.library_hint : R.string.legacy_library_hint);
        adapter.notifyDataSetChanged();
    }

    private void selectTab(int tab) {
        selectedTab = tab;
        showLibrary();
        ((GridView) findViewById(R.id.book_list)).setSelection(0);
    }

    private void selectPeriod(RecentReading.Period period) {
        selectedPeriod = period;
        showLibrary();
        ((GridView) findViewById(R.id.book_list)).setSelection(0);
    }

    private String periodLabel() {
        return getString(selectedPeriod == RecentReading.Period.MONTH ? R.string.recent_month
                : selectedPeriod == RecentReading.Period.HALF_YEAR ? R.string.recent_half_year : R.string.recent_week);
    }

    private void styleTab(Button button, boolean selected) {
        AppStyle.select(button, selected);
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
            TextView placeholder = view.findViewById(R.id.book_badge);
            placeholder.setText(book.title);
            coverLoader.bind((ImageView) view.findViewById(R.id.book_cover), placeholder, book);
            ((TextView) view.findViewById(R.id.book_title)).setText(book.title);
            TextView author = view.findViewById(R.id.book_author);
            author.setText(book.author);
            author.setVisibility(book.author.isEmpty() ? View.GONE : View.VISIBLE);
            String detail = !cachedBooks.containsKey(book.id) ? getString(R.string.book_not_cached)
                    : "album".equals(book.kind) ? getString(R.string.audio_unavailable)
                    : "articles".equals(book.kind) ? getString(R.string.articles_unavailable)
                    : getString(R.string.shelf_grid_notes, book.noteCount());
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
