package com.wenotes.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class BookNotesActivity extends LibraryActivity {
    private String bookId;
    private final List<BookNote> notes = new ArrayList<>();
    private final NoteAdapter adapter = new NoteAdapter();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_book_notes);
        bookId = getIntent().getStringExtra("book_id");
        ((TextView) findViewById(R.id.notes_book_title)).setText(getIntent().getStringExtra("book_title"));
        ListView list = findViewById(R.id.note_list);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.library_empty));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            BookNote note = notes.get(position);
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
    }

    @Override
    protected void showLibrary() {
        ShelfBook book = null;
        for (ShelfBook candidate : snapshot.books) if (candidate.id.equals(bookId)) { book = candidate; break; }
        notes.clear();
        for (BookNote note : snapshot.notes) if (note.bookId.equals(bookId)) notes.add(note);
        notes.sort(Comparator.comparingLong((BookNote note) -> note.chapterOrder)
                .thenComparing(note -> note.chapter).thenComparingLong(note -> note.createdAt).thenComparing(note -> note.id));
        TextView empty = findViewById(R.id.library_empty);
        TextView summary = findViewById(R.id.notes_summary);
        TextView hint = findViewById(R.id.notes_hint);
        if (book == null) {
            summary.setText(R.string.book_not_cached);
            hint.setText(R.string.legacy_library_hint);
            empty.setText(new SecretStore(this).hasKey() ? R.string.sync_library_first : R.string.connect_first);
        } else {
            ((TextView) findViewById(R.id.notes_book_title)).setText(book.title);
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
        @Override public BookNote getItem(int position) { return notes.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView == null
                    ? LayoutInflater.from(BookNotesActivity.this).inflate(R.layout.item_note, parent, false) : convertView;
            BookNote note = getItem(position);
            TextView chapter = view.findViewById(R.id.note_chapter);
            chapter.setText(note.chapter.isEmpty() ? getString(R.string.general_notes) : note.chapter);
            boolean newChapter = position == 0 || note.chapterOrder != getItem(position - 1).chapterOrder
                    || !note.chapter.equals(getItem(position - 1).chapter);
            chapter.setVisibility(newChapter ? View.VISIBLE : View.GONE);
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
            return view;
        }
    }
}
