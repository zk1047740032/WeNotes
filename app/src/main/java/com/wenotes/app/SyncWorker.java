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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.time.Instant;
import java.time.ZoneId;

public final class SyncWorker extends Worker {
    private static final String NOW = "sync_now";
    private static final String PERIODIC = "sync_periodic";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static void schedule(Context context) {
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SyncWorker.class, 6, TimeUnit.HOURS)
                .setInitialDelay(6, TimeUnit.HOURS).setConstraints(network).build();
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
        long generation = store.generation();
        try {
            String key = new SecretStore(context).read();
            if (key.isEmpty()) {
                store.setStatus("请先在应用内设置 API Key", generation);
                return Result.failure();
            }
            store.setStatus("正在检查同步适配更新…", generation);
            ApiUpdateManager updates = new ApiUpdateManager(context);
            updates.check(false);
            if (isStopped() || generation != store.generation()) return Result.failure();
            WeReadApi api = new WeReadApi(key, updates);
            LibrarySnapshot snapshot = new LibrarySync(api,
                    () -> isStopped() || generation != store.generation(),
                    message -> store.setStatus(message, generation)).fetch();
            ReadingStatistics statistics = store.readLibrary().readingStatistics;
            boolean statisticsUpdated = false;
            store.setStatus("正在同步本周的阅读统计…", generation);
            try {
                statistics = ReadingStatistics.fetch(api, Instant.now().getEpochSecond(), ZoneId.systemDefault(),
                        () -> isStopped() || generation != store.generation());
                statisticsUpdated = true;
            } catch (WeReadApi.UpgradeRequiredException | CancellationException | InterruptedException error) {
                throw error;
            } catch (Exception ignored) {
                // Statistics are optional: preserve both existing durations and successfully fetched notes.
            }
            snapshot = snapshot.withReadingStatistics(statistics);
            if (isStopped() || !key.equals(new SecretStore(context).read())
                    || !store.replace(snapshot, generation)) return Result.failure();
            long shelfCount = snapshot.books.stream().filter(book -> book.onShelf).count();
            store.setStatus("同步完成 · " + shelfCount + " 个书架条目 · "
                    + snapshot.notes.size() + " 条笔记内容"
                    + (statisticsUpdated ? "" : " · 阅读统计暂未更新"), generation);
            HighlightWidgetProvider.renderAll(context);
            return Result.success();
        } catch (Exception error) {
            if (isStopped() || generation != store.generation()) return Result.failure();
            String message = error.getMessage();
            store.setStatus("同步失败：" + (message == null || message.isEmpty()
                    ? error.getClass().getSimpleName() : message), generation);
            HighlightWidgetProvider.renderAll(context);
            return Result.failure();
        }
    }
}
