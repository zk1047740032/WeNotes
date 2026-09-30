package com.wenotes.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

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
import java.util.concurrent.TimeUnit;

public final class SyncWorker extends Worker {
    private static final String NOW = "sync_now";
    private static final String PERIODIC = "sync_periodic";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static void schedule(Context context) {
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 6, TimeUnit.HOURS)
                .setInitialDelay(6, TimeUnit.HOURS)
                .setConstraints(network)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    static void syncNow(Context context) {
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(network).build();
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(NOW);
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        HighlightStore store = new HighlightStore(context);
        try {
            String key = new SecretStore(context).read();
            if (key.isEmpty()) {
                store.setStatus("请先在应用内设置 API Key");
                return Result.failure();
            }
            store.setStatus("正在读取笔记本…");
            WeReadApi api = new WeReadApi(key);
            List<JSONObject> notebooks = fetchNotebooks(api);
            LinkedHashMap<String, Highlight> highlights = new LinkedHashMap<>();
            for (int i = 0; i < notebooks.size(); i++) {
                if (isStopped()) return Result.failure();
                JSONObject entry = notebooks.get(i);
                store.setStatus("正在同步第 " + (i + 1) + "/" + notebooks.size() + " 本书…");
                readBook(api, entry, highlights);
            }
            List<Highlight> sorted = new ArrayList<>(highlights.values());
            sorted.sort(Comparator.comparingLong((Highlight h) -> h.createdAt).reversed()
                    .thenComparing(h -> h.id));
            if (isStopped() || !key.equals(new SecretStore(context).read())) return Result.failure();
            store.replace(sorted);
            store.setStatus("同步完成 · " + sorted.size() + " 条划线");
            HighlightWidgetProvider.renderAll(context);
            return Result.success();
        } catch (Exception error) {
            String message = error.getMessage();
            store.setStatus("同步失败：" + (message == null || message.isEmpty()
                    ? error.getClass().getSimpleName() : message));
            HighlightWidgetProvider.renderAll(context);
            return Result.failure();
        }
    }

    private static List<JSONObject> fetchNotebooks(WeReadApi api) throws Exception {
        List<JSONObject> books = new ArrayList<>();
        Set<Long> cursors = new HashSet<>();
        Long cursor = null;
        while (true) {
            JSONObject parameters = new JSONObject().put("count", 100);
            if (cursor != null) parameters.put("lastSort", cursor);
            JSONObject page = api.call("/user/notebooks", parameters);
            JSONArray entries = page.optJSONArray("books");
            if (entries == null) throw new IllegalStateException("笔记本列表格式不正确");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && (entry.optInt("noteCount") > 0 || entry.optInt("reviewCount") > 0)) {
                    books.add(entry);
                }
            }
            if (page.optInt("hasMore") != 1) return books;
            if (entries.length() == 0) throw new IllegalStateException("笔记本分页没有继续返回数据");
            JSONObject last = entries.optJSONObject(entries.length() - 1);
            if (last == null || !last.has("sort")) throw new IllegalStateException("笔记本缺少翻页游标");
            cursor = last.optLong("sort");
            if (!cursors.add(cursor)) throw new IllegalStateException("笔记本分页游标重复");
        }
    }

    private static void readBook(WeReadApi api, JSONObject entry,
                                 Map<String, Highlight> output) throws Exception {
        String bookId = entry.optString("bookId");
        if (bookId.isEmpty()) return;
        JSONObject book = entry.optJSONObject("book");
        String title = book == null ? "未知书籍" : book.optString("title", "未知书籍");
        String author = book == null ? "" : book.optString("author");
        Map<Long, String> chapters = new HashMap<>();
        List<Highlight> current = new ArrayList<>();

        if (entry.optInt("noteCount") > 0) {
            JSONObject page = api.call("/book/bookmarklist", new JSONObject().put("bookId", bookId));
            JSONArray chapterArray = page.optJSONArray("chapters");
            if (chapterArray != null) {
                for (int i = 0; i < chapterArray.length(); i++) {
                    JSONObject chapter = chapterArray.optJSONObject(i);
                    if (chapter != null) chapters.put(chapter.optLong("chapterUid"),
                            chapter.optString("title"));
                }
            }
            JSONArray marks = page.optJSONArray("updated");
            if (marks == null) throw new IllegalStateException("划线列表格式不正确");
            for (int i = 0; i < marks.length(); i++) {
                JSONObject mark = marks.optJSONObject(i);
                if (mark == null || mark.optInt("type", 1) != 1) continue;
                String text = mark.optString("markText").trim();
                if (text.isEmpty()) continue;
                String id = bookId + ":" + mark.optString("bookmarkId", String.valueOf(i));
                long chapterUid = mark.optLong("chapterUid");
                Highlight highlight = new Highlight(id, bookId, title, author,
                        chapters.getOrDefault(chapterUid, ""), text, "",
                        mark.optString("range"), chapterUid, mark.optLong("createTime"));
                output.put(id, highlight);
                current.add(highlight);
            }
        }

        if (entry.optInt("reviewCount") > 0) {
            Set<Long> cursors = new HashSet<>();
            long cursor = 0;
            while (true) {
                JSONObject params = new JSONObject().put("bookid", bookId)
                        .put("count", 100).put("synckey", cursor);
                JSONObject page = api.call("/review/list/mine", params);
                JSONArray reviews = page.optJSONArray("reviews");
                if (reviews == null) throw new IllegalStateException("想法列表格式不正确");
                for (int i = 0; i < reviews.length(); i++) {
                    JSONObject item = reviews.optJSONObject(i);
                    JSONObject review = item == null ? null : item.optJSONObject("review");
                    if (review == null) continue;
                    String thought = review.optString("content").trim();
                    if (thought.isEmpty()) continue;
                    String abstractText = review.optString("abstract").trim();
                    String range = review.optString("range");
                    long chapterUid = review.optLong("chapterUid");
                    Highlight match = findMatch(current, range, chapterUid, abstractText);
                    if (match != null) {
                        Highlight updated = output.get(match.id).withThought(thought);
                        output.put(match.id, updated);
                    } else if (!abstractText.isEmpty()) {
                        String reviewId = review.optString("reviewId", item.optString("reviewId", String.valueOf(i)));
                        String id = bookId + ":review:" + reviewId;
                        output.put(id, new Highlight(id, bookId, title, author,
                                chapters.getOrDefault(chapterUid, review.optString("chapterName")),
                                abstractText, thought, range, chapterUid, review.optLong("createTime")));
                    }
                }
                if (page.optInt("hasMore") != 1) break;
                long next = page.optLong("synckey");
                if (reviews.length() == 0 || next == cursor || !cursors.add(next)) {
                    throw new IllegalStateException("想法分页游标重复");
                }
                cursor = next;
            }
        }
    }

    private static Highlight findMatch(List<Highlight> highlights, String range,
                                       long chapterUid, String abstractText) {
        for (Highlight highlight : highlights) {
            if (!range.isEmpty() && range.equals(highlight.range)
                    && (chapterUid == 0 || chapterUid == highlight.chapterUid)) return highlight;
        }
        if (!abstractText.isEmpty()) {
            for (Highlight highlight : highlights) {
                if (abstractText.equals(highlight.text)) return highlight;
            }
        }
        return null;
    }
}
