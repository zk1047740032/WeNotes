package com.wenotes.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Data;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** One persisted update download, owned by WorkManager rather than an Activity. */
final class AppUpdateManager {
    static final String PREFS = "apk_update_state";
    private static final String WORK = "download-app-update";
    private static final Object LOCK = new Object();

    private AppUpdateManager() { }

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static File file(Context context, ApkUpdateSpec spec) {
        return new File(new File(context.getFilesDir(), "app-updates"), spec.sha256 + ".apk");
    }

    static ApkUpdateSpec savedSpec(Context context) {
        try { return ApkUpdateSpec.parse(new JSONObject(prefs(context).getString("spec", ""))); }
        catch (Exception ignored) { return null; }
    }

    static void download(Context context, ApkUpdateSpec spec) throws Exception {
        if (spec.versionCode <= BuildConfig.VERSION_CODE) throw new UpdateException("package");
        if (!ApkPackageVerifier.signerMatches(context, spec)) throw new UpdateException("signature");
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ApkDownloadWorker.class)
                .setInputData(new Data.Builder().putString("spec", spec.toJson().toString()).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS).build();
        synchronized (LOCK) {
            if (!prefs(context).edit().putString("spec", spec.toJson().toString())
                    .putString("work_id", request.getId().toString()).putString("status", "waiting")
                    .putInt("progress", 0).remove("error").commit()) throw new UpdateException("storage");
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request);
        }
    }

    static void cancel(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().putString("work_id", "").putString("status", "cancelled").apply();
            WorkManager.getInstance(context).cancelUniqueWork(WORK);
        }
    }

    static void status(Context context, String workId, String status, int progress, String error) {
        synchronized (LOCK) {
            SharedPreferences state = prefs(context);
            if (!workId.equals(state.getString("work_id", ""))) return;
            state.edit().putString("status", status).putInt("progress", progress).putString("error", error).apply();
        }
    }

    static boolean busy(Context context) {
        String status = prefs(context).getString("status", "");
        return "waiting".equals(status) || "downloading".equals(status) || "verifying".equals(status);
    }

    static void ready(Context context, String workId, File target) {
        synchronized (LOCK) {
            SharedPreferences state = prefs(context);
            if (!workId.equals(state.getString("work_id", ""))) return;
            state.edit().putString("status", "ready").putInt("progress", 100).remove("error").commit();
            File[] oldFiles = target.getParentFile().listFiles();
            if (oldFiles != null) for (File old : oldFiles) {
                if (!old.equals(target) && old.getName().endsWith(".apk")) old.delete();
            }
        }
    }

    static int errorMessage(String kind) {
        if ("signature".equals(kind)) return R.string.app_update_signature_error;
        if ("integrity".equals(kind) || "package".equals(kind) || "address".equals(kind)) {
            return R.string.app_update_integrity_error;
        }
        if ("storage".equals(kind)) return R.string.app_update_storage_error;
        return R.string.app_update_download_error;
    }
}
