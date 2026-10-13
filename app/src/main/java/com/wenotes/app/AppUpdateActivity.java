package com.wenotes.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateActivity extends Activity {
    private static final int INSTALL_PERMISSION = 41;
    private static final long REMIND_INTERVAL = 24L * 60 * 60 * 1000;
    private static final ExecutorService VERIFIER = Executors.newSingleThreadExecutor();
    private SharedPreferences downloadPrefs;
    private SharedPreferences metadataPrefs;
    private boolean checking;
    private boolean verifying;
    private ApkUpdateSpec selected;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> runOnUiThread(this::refresh);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_app_update);
        downloadPrefs = AppUpdateManager.prefs(this);
        metadataPrefs = getSharedPreferences(ApiUpdateManager.PREFS, Context.MODE_PRIVATE);
        ((TextView) findViewById(R.id.app_current_version)).setText(getString(R.string.app_update_current_version, BuildConfig.VERSION_NAME));
        findViewById(R.id.update_back).setOnClickListener(view -> finish());
        findViewById(R.id.app_check_update).setOnClickListener(view -> check(true));
        findViewById(R.id.app_update_action).setOnClickListener(view -> performAction());
        findViewById(R.id.app_cancel_download).setOnClickListener(view -> AppUpdateManager.cancel(this));
        refresh();
        check(false);
    }

    @Override protected void onStart() {
        super.onStart();
        downloadPrefs.registerOnSharedPreferenceChangeListener(listener);
        metadataPrefs.registerOnSharedPreferenceChangeListener(listener);
        refresh();
    }

    @Override protected void onStop() {
        downloadPrefs.unregisterOnSharedPreferenceChangeListener(listener);
        metadataPrefs.unregisterOnSharedPreferenceChangeListener(listener);
        super.onStop();
    }

    private void check(boolean force) {
        checking = true;
        refresh();
        ApiUpdateManager.checkAsync(this, force, () -> {
            if (isFinishing() || isDestroyed()) return;
            checking = false;
            refresh();
        });
    }

    private void refresh() {
        if (isDestroyed()) return;
        ApiUpdateManager.Snapshot snapshot = new ApiUpdateManager(this).snapshot();
        UpdateConfiguration config = snapshot.configuration;
        String state = downloadPrefs.getString("status", "");
        boolean busy = AppUpdateManager.busy(this);
        ApkUpdateSpec saved = AppUpdateManager.savedSpec(this);
        selected = config.appVersionCode > BuildConfig.VERSION_CODE ? config.apk : null;
        if (saved != null && saved.versionCode > BuildConfig.VERSION_CODE && (busy || "ready".equals(state))) selected = saved;
        boolean same = selected != null && saved != null && selected.sha256.equals(saved.sha256);
        boolean ready = same && "ready".equals(state) && AppUpdateManager.file(this, selected).isFile();
        TextView status = findViewById(R.id.app_update_status);
        if (selected != null) {
            status.setText(getString(R.string.app_update_found, selected.versionName, size(selected.size)));
        } else if (snapshot.appUpdateAvailable()) {
            status.setText(getString(R.string.app_update_found_legacy, config.appVersionName));
        } else {
            int message = checking ? R.string.checking_updates
                    : metadataPrefs.getBoolean("app_check_ok", snapshot.checked) ? R.string.app_update_latest
                    : R.string.app_update_check_unavailable;
            status.setText(message);
        }
        TextView notes = findViewById(R.id.app_release_notes);
        boolean showNotes = snapshot.appUpdateAvailable() && !config.releaseNotes.isEmpty()
                && (selected == null || selected.versionCode == config.appVersionCode);
        notes.setVisibility(showNotes ? View.VISIBLE : View.GONE);
        notes.setText(config.releaseNotes);
        ProgressBar progress = findViewById(R.id.app_download_progress);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(!"downloading".equals(state));
        progress.setProgress(downloadPrefs.getInt("progress", 0));
        TextView downloadStatus = findViewById(R.id.app_download_status);
        downloadStatus.setVisibility(busy || ready || (same && "failed".equals(state)) || verifying ? View.VISIBLE : View.GONE);
        if (verifying || "verifying".equals(state)) downloadStatus.setText(R.string.app_update_verifying);
        else if ("downloading".equals(state)) downloadStatus.setText(getString(R.string.app_update_progress, downloadPrefs.getInt("progress", 0)));
        else if ("waiting".equals(state)) downloadStatus.setText(R.string.app_update_waiting);
        else if (ready) downloadStatus.setText(R.string.app_update_ready);
        else if (same && "failed".equals(state)) downloadStatus.setText(AppUpdateManager.errorMessage(downloadPrefs.getString("error", "")));
        Button action = findViewById(R.id.app_update_action);
        action.setVisibility(selected != null || snapshot.appUpdateAvailable() ? View.VISIBLE : View.GONE);
        action.setEnabled(!busy && !verifying);
        action.setText(ready ? R.string.app_update_install : same && "failed".equals(state)
                ? R.string.app_update_retry : selected != null ? R.string.app_update_download : R.string.open_app_update);
        findViewById(R.id.app_cancel_download).setVisibility(busy ? View.VISIBLE : View.GONE);
        Button check = findViewById(R.id.app_check_update);
        check.setEnabled(!checking);
        check.setText(checking ? R.string.checking_updates : R.string.check_updates);
    }

    private void performAction() {
        if (selected == null) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(new ApiUpdateManager(this).snapshot().configuration.updateUrl))); }
            catch (ActivityNotFoundException error) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show(); }
            return;
        }
        ApkUpdateSpec saved = AppUpdateManager.savedSpec(this);
        if (saved != null && saved.sha256.equals(selected.sha256)
                && "ready".equals(downloadPrefs.getString("status", "")) && AppUpdateManager.file(this, selected).isFile()) {
            requestInstall();
            return;
        }
        try { AppUpdateManager.download(this, selected); }
        catch (Exception error) { showError(error); }
        refresh();
    }

    @SuppressWarnings("deprecation")
    private void requestInstall() {
        if (getPackageManager().canRequestPackageInstalls()) { installDownloaded(); return; }
        new AlertDialog.Builder(this).setTitle(R.string.app_update_permission_title)
                .setMessage(R.string.app_update_permission_message)
                .setNegativeButton(R.string.app_update_later, null)
                .setPositiveButton(R.string.app_update_open_settings, (dialog, which) -> {
                    try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION); }
                    catch (ActivityNotFoundException error) { Toast.makeText(this, R.string.app_update_installer_unavailable, Toast.LENGTH_LONG).show(); }
                }).show();
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != INSTALL_PERMISSION) return;
        if (getPackageManager().canRequestPackageInstalls()) installDownloaded();
        else Toast.makeText(this, R.string.app_update_permission_denied, Toast.LENGTH_LONG).show();
    }

    private void installDownloaded() {
        ApkUpdateSpec spec = AppUpdateManager.savedSpec(this);
        if (spec == null || verifying) return;
        verifying = true;
        refresh();
        Context app = getApplicationContext();
        VERIFIER.execute(() -> {
            File file = AppUpdateManager.file(app, spec);
            Exception failure = null;
            try { ApkPackageVerifier.verify(app, file, spec); }
            catch (Exception error) { failure = error; }
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                verifying = false;
                if (finalFailure != null) {
                    downloadPrefs.edit().putString("status", "failed").putString("error",
                            finalFailure instanceof UpdateException ? ((UpdateException) finalFailure).kind : "package").apply();
                    if (file.exists()) file.delete();
                    showError(finalFailure);
                    refresh();
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
                    Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    install.setClipData(ClipData.newRawUri("update", uri));
                    startActivity(install);
                } catch (ActivityNotFoundException | SecurityException error) {
                    Toast.makeText(this, R.string.app_update_installer_unavailable, Toast.LENGTH_LONG).show();
                }
                refresh();
            });
        });
    }

    private void showError(Exception error) {
        Toast.makeText(this, AppUpdateManager.errorMessage(error instanceof UpdateException
                ? ((UpdateException) error).kind : "network"), Toast.LENGTH_LONG).show();
    }

    private static String size(long bytes) { return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024)); }

    static void offer(Activity activity) {
        ApiUpdateManager.Snapshot snapshot = new ApiUpdateManager(activity).snapshot();
        if (!snapshot.appUpdateAvailable() || AppUpdateManager.busy(activity)) return;
        SharedPreferences state = AppUpdateManager.prefs(activity);
        long now = System.currentTimeMillis();
        if (state.getInt("reminded_code", 0) == snapshot.configuration.appVersionCode
                && now - state.getLong("reminded_at", 0) >= 0
                && now - state.getLong("reminded_at", 0) < REMIND_INTERVAL) return;
        state.edit().putInt("reminded_code", snapshot.configuration.appVersionCode).putLong("reminded_at", now).apply();
        String message = snapshot.configuration.releaseNotes;
        if (snapshot.configuration.apk != null) message = size(snapshot.configuration.apk.size) + "\n\n" + message;
        new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.app_update_offer_title, snapshot.configuration.appVersionName))
                .setMessage(message.isEmpty() ? activity.getString(R.string.app_update_offer_message) : message)
                .setNegativeButton(R.string.app_update_later, null)
                .setPositiveButton(R.string.app_update_now, (dialog, which) -> activity.startActivity(new Intent(activity, AppUpdateActivity.class)))
                .show();
    }

}
