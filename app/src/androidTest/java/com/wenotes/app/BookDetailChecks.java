package com.wenotes.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

/** Synthetic single-book data only: never replaces the user's library or credentials. */
final class BookDetailChecks {
    private static final String BOOK = "__book_detail_layout_check__";
    private final Instrumentation instrumentation;
    private final Context context;
    private BookNotesActivity screen;
    private int count;

    private BookDetailChecks(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
    }

    static int run(Instrumentation instrumentation) throws Throwable {
        BookDetailChecks checks = new BookDetailChecks(instrumentation);
        SharedPreferences preferences = checks.context.getSharedPreferences("book_notes_view", Context.MODE_PRIVATE);
        boolean hadTime = preferences.contains("by_time"), hadDirection = preferences.contains("oldest_first");
        boolean previousTime = preferences.getBoolean("by_time", false), previousDirection = preferences.getBoolean("oldest_first", false);
        try { checks.run(); return checks.count; }
        finally {
            checks.onMain(() -> { if (checks.screen != null) checks.screen.finish(); });
            checks.context.getSharedPreferences("book_details", Context.MODE_PRIVATE).edit().remove(BOOK).commit();
            SharedPreferences.Editor editor = preferences.edit();
            if (hadTime) editor.putBoolean("by_time", previousTime); else editor.remove("by_time");
            if (hadDirection) editor.putBoolean("oldest_first", previousDirection); else editor.remove("oldest_first");
            editor.commit();
            checks.clearFixtureFavorites();
        }
    }

    private void run() throws Throwable {
        clearFixtureFavorites();
        BookDetails metadata = new BookDetails("人类大历史", "尤瓦尔·赫拉利", "",
                "从人类的起源出发，重新理解文明如何走到今天。这是一段用于检查简介展开与收起、文字换行及阅读数据排版的示例内容。沿着认知、农业与科学的线索，回望人类共同走过的漫长旅程。", "历史 · 非虚构", 62, 46080);
        context.getSharedPreferences("book_details", Context.MODE_PRIVATE).edit().putString(BOOK,
                metadata.toJson().put("generation", new HighlightStore(context).generation()).toString()).commit();
        context.getSharedPreferences("book_notes_view", Context.MODE_PRIVATE).edit().putBoolean("by_time", false).putBoolean("oldest_first", false).commit();
        screen = (BookNotesActivity) instrumentation.startActivitySync(new Intent(context, BookNotesActivity.class)
                .putExtra("book_id", BOOK).putExtra("book_title", "人类大历史").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (int i = 0; i < 100; i++) {
            boolean[] ready = {false};
            onMain(() -> ready[0] = "人类大历史".contentEquals(((TextView) screen.findViewById(R.id.notes_book_title)).getText()));
            if (ready[0]) break;
            SystemClock.sleep(40);
        }
        LibrarySnapshot fixture = fixture();
        onMain(() -> {
            screen.snapshot = fixture;
            screen.showLibrary();
            require("历史 · 非虚构".contentEquals(text(R.id.book_detail_category).getText()), "Category tag missing");
            require("62%".contentEquals(text(R.id.book_detail_progress).getText()), "Progress percentage incorrect");
            require("12时48分".contentEquals(text(R.id.book_detail_duration).getText()), "Duration units incorrect");
            count += 3;
            require(adapter().getCount() == 7, "Source mode must contain two chapter headers and four notes");
            require(quoteAt(2).equals("前面的原文"), "Source order used creation time");
            require(adapter().getView(6, null, list()).findViewById(R.id.note_quote).getVisibility() == View.GONE,
                    "A thought should not duplicate the highlight already in its chapter group");
            count += 2;
            screen.findViewById(R.id.book_detail_expand).performClick();
            require(text(R.id.book_detail_intro).getMaxLines() == Integer.MAX_VALUE, "Intro did not expand");
            screen.findViewById(R.id.book_detail_expand).performClick();
            require(text(R.id.book_detail_intro).getMaxLines() == 2, "Intro did not collapse");
            count++;
        });
        instrumentation.waitForIdleSync();
        save("balanced-book-detail.png");
        onMain(() -> {
            View group = adapter().getView(1, null, list());
            group.findViewById(R.id.book_detail_group_body).performClick();
            require(adapter().getCount() == 5, "Chapter did not fold as a group");
            group = adapter().getView(1, null, list());
            group.findViewById(R.id.book_detail_group_body).performClick();
            require(adapter().getCount() == 7, "Chapter did not expand");
            screen.findViewById(R.id.book_detail_time_order).performClick();
            require(adapter().getCount() == 11, "Time mode must group three dates and their chapters");
            require(quoteAt(3).equals("前面的原文"), "Latest note is not first");
            screen.findViewById(R.id.book_detail_direction).performClick();
            require(quoteAt(3).equals("后面的原文"), "Oldest-first mode failed");
            screen.findViewById(R.id.book_detail_source_order).performClick();
            count += 4;
            // Update from an offscreen note: ListView detaches its header while scrolling.
            list().setSelection(6);
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            screen.showLibrary();
            View note = adapter().getView(6, null, list());
            note.findViewById(R.id.note_favorite).performClick();
            require(new NoteFavoriteStore(context).read().containsKey(FavoriteNote.key(BOOK, BOOK + "thought")),
                    "The thought favorite was not saved");
            screen.showLibrary();
            list().setSelection(0);
            count++;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            screen.findViewById(R.id.favorite_notes_tab).performClick();
            require(adapter().getCount() == 3, "Favorite filtering expected 3 rows but found " + adapter().getCount());
            require(adapter().getView(2, null, list()).findViewById(R.id.note_quote).getVisibility() == View.VISIBLE,
                    "A saved thought must retain its quote when the related highlight is filtered out");
            screen.findViewById(R.id.all_notes_tab).performClick();
            require(adapter().getCount() == 7, "Returning from favorites lost notes");
            count += 2;
        });
    }

    private LibrarySnapshot fixture() {
        ShelfBook book = new ShelfBook(BOOK, "人类大历史", "尤瓦尔·赫拉利", "fixture", false, 3, 1, 0);
        return new LibrarySnapshot(Collections.singletonList(book), Arrays.asList(
                note("late", 1, "90-4", "2026-10-11T12:00:00Z", "后面的原文", ""),
                note("early", 1, "10-4", "2026-10-15T12:00:00Z", "前面的原文", ""),
                note("next", 2, "1-4", "2026-10-13T12:00:00Z", "第二章的原文，用来检查同章划线和想法归组。", ""),
                note("thought", 2, "1-4", "2026-10-13T12:01:00Z", "第二章的原文，用来检查同章划线和想法归组。", "我的想法：共同的故事，使大规模合作成为可能。")
        ), Collections.emptyList(), true);
    }
    private void clearFixtureFavorites() {
        NoteFavoriteStore store = new NoteFavoriteStore(context);
        for (FavoriteNote entry : store.read().values()) if (BOOK.equals(entry.note.bookId)) store.toggle(entry);
        // Flush cleanup before the instrumentation process exits or another APK is installed.
        context.getSharedPreferences("note_favorites", Context.MODE_PRIVATE).edit().commit();
    }
    private BookNote note(String id, int chapter, String range, String date, String quote, String content) {
        return new BookNote(BOOK + id, BOOK, content.isEmpty() ? "highlight" : "thought", chapter == 1
                ? "第01章　人类：一种也没什么特别的动物" : "第02章　知善恶树", chapter,
                quote, content, Instant.parse(date).getEpochSecond(), range);
    }
    private TextView text(int id) { return screen.findViewById(id); }
    private ListView list() { return screen.findViewById(R.id.note_list); }
    private ListAdapter adapter() { return list().getAdapter(); }
    private String quoteAt(int position) { return ((TextView) adapter().getView(position, null, list()).findViewById(R.id.note_quote)).getText().toString(); }
    private void onMain(Runnable action) throws Throwable {
        Throwable[] failure = {null};
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw failure[0];
    }
    private void save(String name) throws Exception {
        File file = new File(context.getExternalFilesDir(null), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            instrumentation.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
