package com.wenotes.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class LibrarySync {
    interface Api {
        JSONObject call(String name, JSONObject parameters) throws Exception;
    }

    private final Api api;
    private final BooleanSupplier stopped;
    private final Consumer<String> progress;

    LibrarySync(Api api, BooleanSupplier stopped, Consumer<String> progress) {
        this.api = api;
        this.stopped = stopped;
        this.progress = progress;
    }

    private JSONObject call(String name, JSONObject parameters) throws Exception {
        if (stopped.getAsBoolean()) throw new CancellationException();
        JSONObject result = api.call(name, parameters);
        if (stopped.getAsBoolean()) throw new CancellationException();
        return result;
    }

    LibrarySnapshot fetch() throws Exception {
        progress.accept("正在读取书架…");
        Map<String, ShelfBook> books = readShelf(call("/shelf/sync", new JSONObject()));
        progress.accept("正在读取笔记本…");
        List<JSONObject> notebooks = fetchNotebooks();
        Map<String, Highlight> highlights = new LinkedHashMap<>();
        Map<String, BookNote> notes = new LinkedHashMap<>();
        for (int i = 0; i < notebooks.size(); i++) {
            JSONObject entry = notebooks.get(i);
            String id = entry.getString("bookId");
            ShelfBook book = books.get(id);
            if (book == null) book = new ShelfBook(id, "未知书籍", "", "book", false, 0, 0, 0);
            book = book.withNotebook(entry);
            books.put(id, book);
            progress.accept("正在同步第 " + (i + 1) + "/" + notebooks.size() + " 本书的笔记…");
            readBook(book, highlights, notes);
        }
        List<Highlight> sorted = new ArrayList<>(highlights.values());
        sorted.sort(Comparator.comparingLong((Highlight h) -> h.createdAt).reversed().thenComparing(h -> h.id));
        return new LibrarySnapshot(new ArrayList<>(books.values()), new ArrayList<>(notes.values()), sorted, true);
    }

    static Map<String, ShelfBook> readShelf(JSONObject page) throws Exception {
        Map<String, ShelfBook> books = new LinkedHashMap<>();
        JSONArray array = page.getJSONArray("books");
        for (int i = 0; i < array.length(); i++) {
            JSONObject book = array.getJSONObject(i);
            String id = book.getString("bookId");
            books.put(id, new ShelfBook(id, book.optString("title", "未知书籍"),
                    book.optString("author"), "book", true, 0, 0, 0, book.optString("cover")));
        }
        array = page.optJSONArray("albums");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject info = array.getJSONObject(i).getJSONObject("albumInfo");
                String id = "album:" + info.getString("albumId");
                books.put(id, new ShelfBook(id, info.optString("name", "有声书"),
                        info.optString("authorName"), "album", true, 0, 0, 0, info.optString("cover")));
            }
        }
        JSONObject mp = page.optJSONObject("mp");
        if (mp != null && mp.length() > 0) {
            books.put("articles", new ShelfBook("articles", "文章收藏", "", "articles", true, 0, 0, 0));
        }
        return books;
    }

    private List<JSONObject> fetchNotebooks() throws Exception {
        Map<String, JSONObject> books = new LinkedHashMap<>();
        Set<Long> cursors = new HashSet<>();
        Long cursor = null;
        while (true) {
            JSONObject parameters = new JSONObject().put("count", 100);
            if (cursor != null) parameters.put("lastSort", cursor);
            JSONObject page = call("/user/notebooks", parameters);
            JSONArray entries = page.getJSONArray("books");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                books.put(entry.getString("bookId"), entry);
            }
            if (page.optInt("hasMore") != 1) return new ArrayList<>(books.values());
            if (entries.length() == 0) throw new IllegalStateException("笔记本分页没有继续返回数据");
            cursor = entries.getJSONObject(entries.length() - 1).getLong("sort");
            if (!cursors.add(cursor)) throw new IllegalStateException("笔记本分页游标重复");
        }
    }

    private void readBook(ShelfBook book, Map<String, Highlight> output,
                          Map<String, BookNote> notes) throws Exception {
        Map<Long, String> chapters = new HashMap<>();
        Map<Long, Long> chapterOrder = new HashMap<>();
        List<Highlight> current = new ArrayList<>();
        if (book.highlightCount > 0 || book.reviewCount > 0) {
            JSONObject page = call("/book/bookmarklist", new JSONObject().put("bookId", book.id));
            JSONArray chapterArray = page.optJSONArray("chapters");
            if (chapterArray != null) {
                for (int i = 0; i < chapterArray.length(); i++) {
                    JSONObject chapter = chapterArray.getJSONObject(i);
                    long uid = chapter.optLong("chapterUid");
                    chapters.put(uid, chapter.optString("title"));
                    chapterOrder.put(uid, chapter.optLong("chapterIdx", uid));
                }
            }
            JSONArray marks = page.getJSONArray("updated");
            for (int i = 0; i < marks.length(); i++) {
                JSONObject mark = marks.getJSONObject(i);
                if (mark.optInt("type", 1) != 1) continue;
                String text = mark.optString("markText").trim();
                if (text.isEmpty()) continue;
                String id = book.id + ":highlight:" + mark.getString("bookmarkId");
                long uid = mark.optLong("chapterUid");
                String chapter = chapters.getOrDefault(uid, "");
                Highlight highlight = new Highlight(id, book.id, book.title, book.author, chapter,
                        text, "", mark.optString("range"), uid, mark.optLong("createTime"));
                output.put(id, highlight);
                current.add(highlight);
                notes.put(id, new BookNote(id, book.id, "highlight", chapter,
                        chapterOrder.getOrDefault(uid, uid == 0 ? Long.MAX_VALUE : uid),
                        text, "", highlight.createdAt));
            }
        }
        if (book.reviewCount == 0) return;
        Set<Long> cursors = new HashSet<>();
        long cursor = 0;
        while (true) {
            JSONObject params = new JSONObject().put("bookid", book.id).put("count", 100).put("synckey", cursor);
            JSONObject page = call("/review/list/mine", params);
            JSONArray reviews = page.getJSONArray("reviews");
            for (int i = 0; i < reviews.length(); i++) {
                JSONObject item = reviews.getJSONObject(i);
                JSONObject review = item.getJSONObject("review");
                String thought = review.optString("content").trim();
                String quote = review.optString("abstract").trim();
                String range = review.optString("range");
                long uid = review.optLong("chapterUid");
                String chapter = chapters.getOrDefault(uid, review.optString("chapterName"));
                String reviewId = review.optString("reviewId", item.optString("reviewId"));
                if (reviewId.isEmpty()) throw new IllegalStateException("想法缺少唯一标识");
                String id = book.id + ":review:" + reviewId;
                Highlight match = findMatch(current, range, uid, quote);
                // Reviews can repeat across pages; never append the same thought to the widget twice.
                if (notes.containsKey(id)) continue;
                if (match != null) {
                    if (!thought.isEmpty()) output.put(match.id, output.get(match.id).withThought(thought));
                    if (quote.isEmpty()) quote = match.text;
                    if (chapter.isEmpty()) chapter = match.chapter;
                    if (uid == 0) uid = match.chapterUid;
                } else if (!quote.isEmpty()) {
                    output.put(id, new Highlight(id, book.id, book.title, book.author, chapter,
                            quote, thought, range, uid, review.optLong("createTime")));
                }
                String kind = !quote.isEmpty() || !range.isEmpty() ? "thought"
                        : uid != 0 || !chapter.isEmpty() ? "chapter_review" : "review";
                notes.put(id, new BookNote(id, book.id, kind, chapter,
                        chapterOrder.getOrDefault(uid, uid == 0 ? Long.MAX_VALUE : uid),
                        quote, thought, review.optLong("createTime")));
            }
            if (page.optInt("hasMore") != 1) break;
            long next = page.getLong("synckey");
            if (reviews.length() == 0 || next == cursor || !cursors.add(next)) {
                throw new IllegalStateException("想法分页游标重复");
            }
            cursor = next;
        }
    }

    private static Highlight findMatch(List<Highlight> highlights, String range, long uid, String quote) {
        for (Highlight highlight : highlights) {
            if (!range.isEmpty() && range.equals(highlight.range)
                    && (uid == 0 || uid == highlight.chapterUid)) return highlight;
        }
        if (!quote.isEmpty()) {
            for (Highlight highlight : highlights) {
                if (quote.equals(highlight.text) && (uid == 0 || uid == highlight.chapterUid)) return highlight;
            }
        }
        return null;
    }
}
