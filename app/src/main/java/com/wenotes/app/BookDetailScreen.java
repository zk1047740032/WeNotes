package com.wenotes.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The single-book detail page. */
final class BookDetailScreen {
    private final BookNotesActivity activity;
    private final String bookId;
    private final SharedPreferences preferences;
    private final SharedPreferences detailsCache;
    private final NoteFavoriteStore favorites;
    private final CoverLoader covers;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Set<String> collapsed = new HashSet<>();
    private final GroupAdapter adapter = new GroupAdapter();
    private final ListView list;
    private final View header;
    private Map<String, FavoriteNote> saved = new HashMap<>();
    private List<BookNoteGroups> groups = Collections.emptyList();
    private BookDetails details = BookDetails.empty();
    private ShelfBook book;
    private boolean favoritesOnly, byTime, oldestFirst, introExpanded;
    private volatile boolean disposed;
    private long detailsGeneration = -1, requestedGeneration = -1;
    private int emptyText = R.string.loading_library;

    BookDetailScreen(BookNotesActivity activity, String bookId, Bundle state) {
        this.activity = activity;
        this.bookId = bookId == null ? "" : bookId;
        preferences = activity.getSharedPreferences("book_notes_view", Context.MODE_PRIVATE);
        detailsCache = activity.getSharedPreferences("book_details", Context.MODE_PRIVATE);
        favorites = new NoteFavoriteStore(activity);
        covers = new CoverLoader(activity);
        byTime = state == null ? preferences.getBoolean("by_time", false) : state.getBoolean("detail_by_time");
        oldestFirst = state == null ? preferences.getBoolean("oldest_first", false) : state.getBoolean("detail_oldest_first");
        if (state != null) {
            favoritesOnly = state.getBoolean("detail_favorites");
            introExpanded = state.getBoolean("detail_intro");
            ArrayList<String> folded = state.getStringArrayList("detail_collapsed");
            if (folded != null) collapsed.addAll(folded);
        }
        activity.setContentView(R.layout.activity_book_detail);
        list = find(R.id.note_list);
        header = LayoutInflater.from(activity).inflate(R.layout.view_book_detail_header, list, false);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);
        find(R.id.library_back).setOnClickListener(view -> activity.finish());
        find(R.id.all_notes_tab).setOnClickListener(view -> { favoritesOnly = false; show(activity.snapshot); });
        find(R.id.favorite_notes_tab).setOnClickListener(view -> { favoritesOnly = true; show(activity.snapshot); });
        find(R.id.book_detail_source_order).setOnClickListener(view -> selectOrder(false));
        find(R.id.book_detail_time_order).setOnClickListener(view -> selectOrder(true));
        find(R.id.book_detail_direction).setOnClickListener(view -> {
            oldestFirst = !oldestFirst;
            preferences.edit().putBoolean("oldest_first", oldestFirst).apply();
            show(activity.snapshot);
        });
        find(R.id.book_detail_expand).setOnClickListener(view -> {
            introExpanded = !introExpanded;
            bindBook();
        });
    }

    void save(Bundle state) {
        state.putBoolean("detail_by_time", byTime);
        state.putBoolean("detail_oldest_first", oldestFirst);
        state.putBoolean("detail_favorites", favoritesOnly);
        state.putBoolean("detail_intro", introExpanded);
        state.putStringArrayList("detail_collapsed", new ArrayList<>(collapsed));
    }

    void dispose() { disposed = true; reader.shutdownNow(); covers.dispose(); }

    private void selectOrder(boolean time) {
        byTime = time;
        preferences.edit().putBoolean("by_time", time).apply();
        show(activity.snapshot);
    }

    void show(LibrarySnapshot snapshot) {
        book = null;
        for (ShelfBook candidate : snapshot.books) if (candidate.id.equals(bookId)) { book = candidate; break; }
        long generation = activity.store.generation();
        if (detailsGeneration != generation) {
            detailsGeneration = generation;
            details = BookDetails.empty();
            try {
                JSONObject cached = new JSONObject(detailsCache.getString(bookId, "{}"));
                if (cached.optLong("generation", -1) == generation) details = BookDetails.fromJson(cached);
            } catch (Exception ignored) { /* Missing details never hide cached notes. */ }
        }
        saved = favorites.read();
        List<FavoriteNote> selected = new ArrayList<>();
        String title = book == null ? activity.getIntent().getStringExtra("book_title") : book.title;
        if (favoritesOnly) {
            for (FavoriteNote favorite : FavoriteNote.resolve(saved, snapshot)) {
                if (favorite.note.bookId.equals(bookId)) selected.add(favorite);
            }
        } else {
            for (BookNote note : snapshot.notes) if (note.bookId.equals(bookId)) {
                selected.add(new FavoriteNote(note, title == null ? "" : title, book == null ? "" : book.author));
            }
        }
        List<BookNote> candidates = new ArrayList<>();
        Map<BookNote, FavoriteNote> entries = new HashMap<>();
        for (FavoriteNote entry : selected) { candidates.add(entry.note); entries.put(entry.note, entry); }
        LibrarySnapshot order = new LibrarySnapshot(snapshot.books, candidates, snapshot.highlights, snapshot.complete);
        List<FavoriteNote> source = new ArrayList<>();
        for (BookNote note : NoteReadingOrder.forBook(order, bookId)) source.add(entries.get(note));
        groups = BookNoteGroups.build(source, byTime, oldestFirst, ZoneId.systemDefault());
        AppStyle.select(find(R.id.all_notes_tab), !favoritesOnly);
        AppStyle.select(find(R.id.favorite_notes_tab), favoritesOnly);
        AppStyle.select(find(R.id.book_detail_source_order), !byTime);
        AppStyle.select(find(R.id.book_detail_time_order), byTime);
        Button direction = find(R.id.book_detail_direction);
        direction.setVisibility(byTime ? View.VISIBLE : View.GONE);
        direction.setText(oldestFirst ? R.string.book_detail_oldest : R.string.book_detail_newest);
        text(R.id.book_detail_order_hint).setText(byTime ? R.string.book_detail_time_hint : R.string.book_detail_source_hint);
        text(R.id.notes_summary).setText(favoritesOnly ? activity.getString(R.string.favorite_notes_total, selected.size())
                : book == null ? "" : activity.getString(R.string.book_note_counts, book.highlightCount, book.reviewCount));
        TextView hint = text(R.id.notes_hint);
        hint.setVisibility(snapshot.complete || favoritesOnly ? View.GONE : View.VISIBLE);
        hint.setText(R.string.legacy_library_hint);
        emptyText = favoritesOnly ? R.string.empty_favorite_notes : book == null
                ? new SecretStore(activity).hasKey() ? R.string.sync_library_first : R.string.connect_first
                : "album".equals(book.kind) ? R.string.audio_notes_limit
                : "articles".equals(book.kind) ? R.string.articles_notes_limit
                : !snapshot.complete ? R.string.sync_library_first
                : book.bookmarkCount > 0 ? R.string.only_bookmarks : R.string.no_book_notes;
        bindBook();
        adapter.rebuild();
        if (book != null) {
            covers.loadMissing(Collections.singletonList(book), this::bindBook);
            fetchDetails(generation);
        }
    }

    private void bindBook() {
        String title = !details.title.isEmpty() ? details.title : book == null
                ? activity.getIntent().getStringExtra("book_title") : book.title;
        String author = !details.author.isEmpty() ? details.author : book == null ? "" : book.author;
        text(R.id.notes_book_title).setText(title);
        text(R.id.book_detail_author).setText(author);
        text(R.id.book_detail_author).setVisibility(author.isEmpty() ? View.GONE : View.VISIBLE);
        text(R.id.book_detail_category).setText(details.category.isEmpty()
                ? activity.getString(R.string.book_detail_category_unknown) : details.category);
        TextView intro = text(R.id.book_detail_intro);
        intro.setText(details.intro.isEmpty() ? activity.getString(R.string.book_detail_intro_unknown) : details.intro);
        intro.setMaxLines(introExpanded ? Integer.MAX_VALUE : 2);
        intro.setEllipsize(introExpanded ? null : TextUtils.TruncateAt.END);
        Button expand = find(R.id.book_detail_expand);
        expand.setVisibility(details.intro.isEmpty() ? View.GONE : View.VISIBLE);
        expand.setText(introExpanded ? R.string.book_detail_collapse : R.string.book_detail_expand);
        expand.setContentDescription(activity.getString(introExpanded ? R.string.book_detail_collapse : R.string.book_detail_expand));
        String percentage = details.progress < 0 ? activity.getString(R.string.book_detail_unknown)
                : activity.getString(R.string.book_detail_percent, details.progress);
        text(R.id.book_detail_progress).setText(percentage);
        text(R.id.book_detail_progress).setContentDescription(activity.getString(R.string.book_detail_progress_description, percentage));
        ((ProgressBar) find(R.id.book_detail_progress_bar)).setProgress(Math.max(0, details.progress));
        String duration = details.readingSeconds < 0 ? activity.getString(R.string.book_detail_unknown)
                : details.readingSeconds > 0 && details.readingSeconds < 60 ? activity.getString(R.string.book_detail_under_minute)
                : activity.getString(R.string.book_detail_duration_value, details.readingSeconds / 3600, details.readingSeconds / 60 % 60);
        SpannableString value = new SpannableString(duration);
        for (int i = 0; i < duration.length(); i++) if (!Character.isDigit(duration.charAt(i))) {
            value.setSpan(new RelativeSizeSpan(.58f), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text(R.id.book_detail_duration).setText(value);
        text(R.id.book_detail_highlights).setText(book == null ? activity.getString(R.string.book_detail_unknown) : String.valueOf(book.highlightCount));
        text(R.id.book_detail_thoughts).setText(book == null ? activity.getString(R.string.book_detail_unknown) : String.valueOf(book.reviewCount));
        TextView placeholder = text(R.id.book_detail_cover_placeholder);
        placeholder.setText(title);
        if (book != null) {
            ShelfBook coverBook = details.cover.isEmpty() ? book : new ShelfBook(book.id, book.title, book.author,
                    book.kind, book.onShelf, book.highlightCount, book.reviewCount, book.bookmarkCount, details.cover, book.lastReadAt);
            covers.bind(find(R.id.book_detail_cover), placeholder, coverBook);
        }
    }

    private void fetchDetails(long generation) {
        if (requestedGeneration == generation || !"book".equals(book.kind)
                || !new SecretStore(activity).hasKey()) return;
        requestedGeneration = generation;
        BookDetails previous = details;
        reader.execute(() -> {
            try {
                String key = new SecretStore(activity).read();
                ApiUpdateManager updates = new ApiUpdateManager(activity);
                updates.check(false);
                WeReadApi api = new WeReadApi(key, updates);
                BookDetails updated = previous;
                JSONObject parameters = new JSONObject().put("bookId", bookId);
                try { updated = updated.withInfo(api.call("/book/info", parameters)); }
                catch (WeReadApi.UpgradeRequiredException error) { throw error; }
                catch (Exception ignored) { /* Reading progress may still be available. */ }
                if (Thread.currentThread().isInterrupted()) return;
                try { updated = updated.withProgress(api.call("/book/getprogress", parameters)); }
                catch (WeReadApi.UpgradeRequiredException error) { throw error; }
                catch (Exception ignored) { /* Keep the last successfully cached reading data. */ }
                if (disposed || generation != activity.store.generation()
                        || !key.equals(new SecretStore(activity).read())) return;
                detailsCache.edit().putString(bookId, updated.toJson().put("generation", generation).toString()).apply();
                BookDetails result = updated;
                activity.runOnUiThread(() -> {
                    if (!disposed && generation == activity.store.generation()) { details = result; bindBook(); }
                });
            } catch (WeReadApi.UpgradeRequiredException error) {
                activity.store.setStatus(error.getMessage(), generation);
            } catch (Exception ignored) { /* Offline use keeps the book and notes readable. */ }
        });
    }

    private <T extends View> T find(int id) {
        T view = header == null ? null : header.findViewById(id);
        return view == null ? activity.findViewById(id) : view;
    }
    private TextView text(int id) { return find(id); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private GradientDrawable background(boolean top, boolean bottom) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(activity.getColor(R.color.surface));
        float t = top ? dp(20) : 0, b = bottom ? dp(20) : 0;
        drawable.setCornerRadii(new float[]{t,t,t,t,b,b,b,b});
        return drawable;
    }

    private void copy(BookNote note) {
        String value = note.quote.isEmpty() ? note.content : note.content.isEmpty() ? note.quote : note.quote + "\n\n" + note.content;
        if (value.isEmpty()) Toast.makeText(activity, R.string.no_text_content, Toast.LENGTH_SHORT).show();
        else {
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.book_notes), value));
            Toast.makeText(activity, R.string.note_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private final class Row {
        final int type;
        final BookNoteGroups group;
        final FavoriteNote entry;
        final boolean last;
        Row(int type, BookNoteGroups group, FavoriteNote entry, boolean last) {
            this.type = type; this.group = group; this.entry = entry; this.last = last;
        }
    }

    private final class GroupAdapter extends BaseAdapter {
        private final List<Row> rows = new ArrayList<>();
        private final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.CHINA);

        void rebuild() {
            rows.clear();
            String lastDate = "";
            for (BookNoteGroups group : groups) {
                if (byTime && !group.date.equals(lastDate)) { rows.add(new Row(0, group, null, false)); lastDate = group.date; }
                boolean folded = collapsed.contains(group.key);
                rows.add(new Row(1, group, null, folded));
                if (!folded) for (int i = 0; i < group.notes.size(); i++) {
                    rows.add(new Row(2, group, group.notes.get(i), i == group.notes.size() - 1));
                }
            }
            if (rows.isEmpty()) rows.add(new Row(3, null, null, true));
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 4; }
        @Override public int getItemViewType(int position) { return getItem(position).type; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return getItem(position).type == 1; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Row row = getItem(position);
            if (row.type == 0 || row.type == 3) {
                TextView label = recycled instanceof TextView ? (TextView) recycled : new TextView(activity);
                label.setTextColor(activity.getColor(R.color.text_secondary));
                label.setTextSize(row.type == 0 ? 12 : 14);
                label.setPadding(dp(row.type == 0 ? 4 : 16), dp(row.type == 0 ? 10 : 30), dp(4), dp(14));
                label.setText(row.type == 3 ? activity.getString(emptyText) : "unknown".equals(row.group.date)
                        ? activity.getString(R.string.book_detail_unknown_date)
                        : LocalDate.parse(row.group.date).format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)));
                return label;
            }
            View view = recycled == null ? LayoutInflater.from(activity).inflate(row.type == 1
                    ? R.layout.item_book_detail_group : R.layout.item_book_detail_note, parent, false) : recycled;
            view.setPadding(0, 0, 0, row.last ? dp(14) : 0);
            if (row.type == 1) {
                View body = view.findViewById(R.id.book_detail_group_body);
                body.setBackground(background(true, row.last));
                String chapter = row.group.chapter.isEmpty() ? activity.getString(R.string.general_notes) : row.group.chapter;
                ((TextView) view.findViewById(R.id.book_detail_group_title)).setText(chapter);
                ((TextView) view.findViewById(R.id.book_detail_group_count)).setText(activity.getString(R.string.book_detail_group_count, row.group.notes.size()));
                view.findViewById(R.id.book_detail_group_arrow).setRotation(row.last ? 0 : 180);
                body.setContentDescription(activity.getString(row.last ? R.string.book_detail_group_expand : R.string.book_detail_group_collapse, chapter, row.group.notes.size()));
                body.setFocusable(true);
                body.setOnClickListener(clicked -> {
                    if (!collapsed.add(row.group.key)) collapsed.remove(row.group.key);
                    rebuild();
                });
            } else {
                View body = view.findViewById(R.id.book_detail_note_body);
                body.setBackground(background(false, row.last));
                BookNote note = row.entry.note;
                int kind = "highlight".equals(note.kind) ? R.string.note_highlight : "thought".equals(note.kind)
                        ? R.string.note_thought : "chapter_review".equals(note.kind) ? R.string.note_chapter_review : R.string.note_review;
                ((TextView) view.findViewById(R.id.note_kind)).setText(kind);
                ((TextView) view.findViewById(R.id.note_date)).setText(note.createdAt > 0
                        ? (byTime ? time : date).format(new Date(note.createdAt * 1000L)) : "");
                TextView quote = view.findViewById(R.id.note_quote);
                quote.setText(note.quote);
                boolean pairedThought = "thought".equals(note.kind) && !note.quote.isEmpty()
                        && row.group.notes.stream().anyMatch(entry -> "highlight".equals(entry.note.kind)
                        && note.quote.equals(entry.note.quote));
                quote.setVisibility(note.quote.isEmpty() || pairedThought ? View.GONE : View.VISIBLE);
                TextView content = view.findViewById(R.id.note_content);
                content.setText(note.content.isEmpty() ? activity.getString(R.string.no_text_content) : note.content);
                content.setVisibility(note.content.isEmpty() && "highlight".equals(note.kind) ? View.GONE : View.VISIBLE);
                ImageButton favorite = view.findViewById(R.id.note_favorite);
                boolean starred = saved.containsKey(FavoriteNote.key(note));
                favorite.setImageResource(starred ? R.drawable.ic_book_favorite : R.drawable.ic_book_favorite_outline);
                favorite.setVisibility(note.id.isEmpty() || note.bookId.isEmpty() ? View.GONE : View.VISIBLE);
                favorite.setContentDescription(activity.getString(starred ? R.string.unfavorite_note : R.string.favorite_note));
                favorite.setOnClickListener(clicked -> {
                    try { favorites.toggle(row.entry); show(activity.snapshot); }
                    catch (IllegalStateException error) { Toast.makeText(activity, R.string.favorite_save_failed, Toast.LENGTH_SHORT).show(); }
                });
                body.setOnLongClickListener(clicked -> { copy(note); return true; });
            }
            return view;
        }
    }
}
