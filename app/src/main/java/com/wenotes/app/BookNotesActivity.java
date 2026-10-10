package com.wenotes.app;

import android.content.ClipData;
import android.content.ClipboardManager;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BookNotesActivity extends LibraryActivity {
    private String bookId;
    private boolean globalFavorites;
    private boolean favoritesOnly;
    private NoteFavoriteStore favoriteStore;
    private Map<String, FavoriteNote> favoriteNotes;
    private EditText search;
    private final List<FavoriteNote> notes = new ArrayList<>();
    private final NoteAdapter adapter = new NoteAdapter();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_book_notes);
        bookId = getIntent().getStringExtra("book_id");
        globalFavorites = getIntent().getBooleanExtra("favorite_notes", false);
        favoritesOnly = globalFavorites || state != null && state.getBoolean("favorites_only");
        favoriteStore = new NoteFavoriteStore(this);
        search = findViewById(R.id.note_search);
        search.setVisibility(globalFavorites ? View.VISIBLE : View.GONE);
        findViewById(R.id.note_tabs).setVisibility(globalFavorites ? View.GONE : View.VISIBLE);
        findViewById(R.id.notes_book_title).setVisibility(globalFavorites ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.notes_page_title)).setText(globalFavorites
                ? R.string.favorite_notes : R.string.book_notes);
        ((TextView) findViewById(R.id.notes_book_title)).setText(getIntent().getStringExtra("book_title"));
        ListView list = findViewById(R.id.note_list);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.library_empty));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            BookNote note = notes.get(position).note;
            String text = note.quote.isEmpty() ? note.content : note.content.isEmpty()
                    ? note.quote : note.quote + "\n\n" + note.content;
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.no_text_content, Toast.LENGTH_SHORT).show();
            } else {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.book_notes), text));
                Toast.makeText(this, R.string.note_copied, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        findViewById(R.id.library_back).setOnClickListener(view -> finish());
        findViewById(R.id.library_refresh).setOnClickListener(view -> sync());
        findViewById(R.id.all_notes_tab).setOnClickListener(view -> selectFavorites(false));
        findViewById(R.id.favorite_notes_tab).setOnClickListener(view -> selectFavorites(true));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { showLibrary(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("favorites_only", favoritesOnly);
        super.onSaveInstanceState(state);
    }

    private void selectFavorites(boolean selected) {
        favoritesOnly = selected;
        showLibrary();
        ((ListView) findViewById(R.id.note_list)).setSelection(0);
    }

    private void styleTab(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.tab_selected_background : R.drawable.text_button_background);
        button.setTextColor(getColor(selected ? R.color.primary : R.color.text_secondary));
    }

    @Override
    protected void showLibrary() {
        ShelfBook book = null;
        for (ShelfBook candidate : snapshot.books) if (candidate.id.equals(bookId)) { book = candidate; break; }
        favoriteNotes = favoriteStore.read();
        String query = globalFavorites ? search.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        notes.clear();
        int favoriteCount = 0;
        if (favoritesOnly) {
            for (FavoriteNote favorite : FavoriteNote.resolve(favoriteNotes, snapshot)) {
                if (!globalFavorites && !favorite.note.bookId.equals(bookId)) continue;
                favoriteCount++;
                String searchable = favorite.bookTitle + " " + favorite.bookAuthor + " "
                        + favorite.note.chapter + " " + favorite.note.quote + " " + favorite.note.content;
                if (query.isEmpty() || searchable.toLowerCase(Locale.ROOT).contains(query)) notes.add(favorite);
            }
        } else {
            for (BookNote note : snapshot.notes) {
                if (note.bookId.equals(bookId)) notes.add(new FavoriteNote(note,
                        book == null ? getIntent().getStringExtra("book_title") : book.title,
                        book == null ? "" : book.author));
            }
        }
        if (!globalFavorites) {
            notes.sort(Comparator.comparingLong((FavoriteNote favorite) -> favorite.note.chapterOrder)
                    .thenComparing(favorite -> favorite.note.chapter)
                    .thenComparingLong(favorite -> favorite.note.createdAt).thenComparing(favorite -> favorite.note.id));
        }
        TextView empty = findViewById(R.id.library_empty);
        TextView summary = findViewById(R.id.notes_summary);
        TextView hint = findViewById(R.id.notes_hint);
        styleTab(findViewById(R.id.all_notes_tab), !favoritesOnly);
        styleTab(findViewById(R.id.favorite_notes_tab), favoritesOnly);
        hint.setVisibility(favoritesOnly || snapshot.complete ? View.GONE : View.VISIBLE);
        if (book != null) ((TextView) findViewById(R.id.notes_book_title)).setText(book.title);
        if (favoritesOnly) {
            String count = getString(R.string.favorite_notes_total, favoriteCount);
            summary.setText(query.isEmpty() ? count : getString(R.string.filtered_count, count, notes.size()));
            hint.setText(getString(R.string.notes_loaded, notes.size()));
            empty.setText(query.isEmpty() ? R.string.empty_favorite_notes : R.string.no_matching_notes);
        } else if (book == null) {
            summary.setText(R.string.book_not_cached);
            hint.setText(R.string.legacy_library_hint);
            empty.setText(new SecretStore(this).hasKey() ? R.string.sync_library_first : R.string.connect_first);
        } else {
            summary.setText(getString(R.string.book_note_counts, book.highlightCount, book.reviewCount, book.bookmarkCount));
            hint.setText(!snapshot.complete ? getString(R.string.legacy_library_hint)
                    : getString(R.string.notes_loaded, notes.size()) + "\n" + getString(R.string.bookmark_limit));
            empty.setText("album".equals(book.kind) ? R.string.audio_notes_limit
                    : "articles".equals(book.kind) ? R.string.articles_notes_limit
                    : !snapshot.complete ? R.string.sync_library_first
                    : book.bookmarkCount > 0 ? R.string.only_bookmarks : R.string.no_book_notes);
        }
        adapter.notifyDataSetChanged();
    }

    private final class NoteAdapter extends BaseAdapter {
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        @Override public int getCount() { return notes.size(); }
        @Override public FavoriteNote getItem(int position) { return notes.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView == null
                    ? LayoutInflater.from(BookNotesActivity.this).inflate(R.layout.item_note, parent, false) : convertView;
            FavoriteNote entry = getItem(position);
            BookNote note = entry.note;
            TextView chapter = view.findViewById(R.id.note_chapter);
            chapter.setText(note.chapter.isEmpty() ? getString(R.string.general_notes) : note.chapter);
            boolean newChapter = globalFavorites || position == 0
                    || note.chapterOrder != getItem(position - 1).note.chapterOrder
                    || !note.chapter.equals(getItem(position - 1).note.chapter);
            chapter.setVisibility(newChapter ? View.VISIBLE : View.GONE);
            TextView source = view.findViewById(R.id.note_book);
            source.setText(entry.bookAuthor.isEmpty() ? "《" + entry.bookTitle + "》"
                    : getString(R.string.favorite_note_source, entry.bookTitle, entry.bookAuthor));
            source.setVisibility(globalFavorites ? View.VISIBLE : View.GONE);
            int kind = "highlight".equals(note.kind) ? R.string.note_highlight : "thought".equals(note.kind)
                    ? R.string.note_thought : "chapter_review".equals(note.kind)
                    ? R.string.note_chapter_review : R.string.note_review;
            ((TextView) view.findViewById(R.id.note_kind)).setText(kind);
            ((TextView) view.findViewById(R.id.note_date)).setText(note.createdAt > 0
                    ? dateFormat.format(new Date(note.createdAt * 1000L)) : "");
            TextView quote = view.findViewById(R.id.note_quote);
            quote.setText(note.quote);
            quote.setVisibility(note.quote.isEmpty() ? View.GONE : View.VISIBLE);
            TextView content = view.findViewById(R.id.note_content);
            boolean emptyReview = !"highlight".equals(note.kind) && note.content.isEmpty();
            content.setText(emptyReview ? getString(R.string.no_text_content) : note.content);
            content.setVisibility(note.content.isEmpty() && !emptyReview ? View.GONE : View.VISIBLE);
            ImageButton favorite = view.findViewById(R.id.note_favorite);
            boolean saved = favoriteNotes.containsKey(FavoriteNote.key(note));
            favorite.setVisibility(note.id.isEmpty() || note.bookId.isEmpty() ? View.GONE : View.VISIBLE);
            favorite.setImageResource(saved ? R.drawable.ic_book_favorite : R.drawable.ic_book_favorite_outline);
            String preview = note.quote.isEmpty() ? note.content : note.quote;
            if (preview.isEmpty()) preview = getString(kind);
            int previewLength = Math.min(40, preview.codePointCount(0, preview.length()));
            favorite.setContentDescription(getString(R.string.favorite_note_description,
                    getString(saved ? R.string.unfavorite_note : R.string.favorite_note),
                    preview.substring(0, preview.offsetByCodePoints(0, previewLength))));
            favorite.setOnClickListener(button -> {
                try {
                    favoriteStore.toggle(entry);
                    showLibrary();
                } catch (IllegalStateException error) {
                    Toast.makeText(BookNotesActivity.this, R.string.favorite_save_failed, Toast.LENGTH_SHORT).show();
                }
            });
            return view;
        }
    }
}
