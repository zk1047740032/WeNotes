package com.wenotes.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

public final class ApkDownloadWorker extends Worker {
    public ApkDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String id = getId().toString();
        try {
            ApkUpdateSpec spec = ApkUpdateSpec.parse(new JSONObject(getInputData().getString("spec")));
            if (!id.equals(AppUpdateManager.prefs(context).getString("work_id", ""))) return Result.failure();
            if (!ApkPackageVerifier.signerMatches(context, spec)) throw new UpdateException("signature");
            File target = AppUpdateManager.file(context, spec);
            File directory = target.getParentFile();
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new UpdateException("storage");
            AppUpdateManager.status(context, id, "downloading", 0, "");
            if (!target.isFile()) {
                // Separate partial files prevent a cancelled worker from racing its replacement.
                File partial = new File(directory, id + ".part");
                ApkDownloader.download(spec, partial, target,
                        percent -> AppUpdateManager.status(context, id, "downloading", percent, ""), this::isStopped);
            }
            if (isStopped()) return Result.failure();
            AppUpdateManager.status(context, id, "verifying", 100, "");
            try { ApkPackageVerifier.verify(context, target, spec); }
            catch (Exception invalid) {
                if (!target.delete()) target.deleteOnExit();
                throw invalid;
            }
            if (isStopped()) return Result.failure();
            AppUpdateManager.ready(context, id, target);
            return Result.success();
        } catch (UpdateException error) {
            if (!isStopped()) AppUpdateManager.status(context, id, "failed", 0, error.kind);
            return Result.failure();
        } catch (IOException network) {
            if (isStopped()) return Result.failure();
            boolean retry = getRunAttemptCount() < 2;
            AppUpdateManager.status(context, id, retry ? "waiting" : "failed", 0, "network");
            return retry ? Result.retry() : Result.failure();
        } catch (Exception invalid) {
            AppUpdateManager.status(context, id, "failed", 0, "package");
            return Result.failure();
        }
    }

    @Override public void onStopped() {
        AppUpdateManager.status(getApplicationContext(), getId().toString(), "waiting", 0, "");
    }
}
