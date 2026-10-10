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
            LibrarySnapshot snapshot = new LibrarySync(new WeReadApi(key),
                    () -> isStopped() || generation != store.generation(),
                    message -> store.setStatus(message, generation)).fetch();
            if (isStopped() || !key.equals(new SecretStore(context).read())
                    || !store.replace(snapshot, generation)) return Result.failure();
            long shelfCount = snapshot.books.stream().filter(book -> book.onShelf).count();
            store.setStatus("同步完成 · " + shelfCount + " 个书架条目 · "
                    + snapshot.notes.size() + " 条笔记内容", generation);
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
