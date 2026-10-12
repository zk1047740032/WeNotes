package com.wenotes.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads the private cache off the UI thread and follows successful syncs. */
abstract class LibraryActivity extends Activity {
    protected HighlightStore store;
    protected LibrarySnapshot snapshot = LibrarySnapshot.empty();
    private SharedPreferences prefs;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private int requestVersion;
    private boolean active;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) ->
            runOnUiThread(() -> {
                if (!active) return;
                if (key == null || "last_sync".equals(key) || "generation".equals(key)) loadLibrary();
                showSyncStatus();
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new HighlightStore(this);
        prefs = getSharedPreferences("highlight_state", Context.MODE_PRIVATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        prefs.registerOnSharedPreferenceChangeListener(listener);
        loadLibrary();
        showSyncStatus();
    }

    @Override
    protected void onStop() {
        active = false;
        requestVersion++;
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        reader.shutdownNow();
        super.onDestroy();
    }

    private void loadLibrary() {
        int version = ++requestVersion;
        reader.execute(() -> {
            LibrarySnapshot loaded = store.readLibrary();
            runOnUiThread(() -> {
                if (!active || version != requestVersion) return;
                snapshot = loaded;
                showLibrary();
            });
        });
    }

    protected void sync() {
        if (!new SecretStore(this).hasKey()) {
            startActivity(new Intent(this, ConnectionActivity.class));
            return;
        }
        store.setStatus(getString(R.string.waiting_sync));
        SyncWorker.schedule(this);
        SyncWorker.syncNow(this);
    }

    private void showSyncStatus() {
        TextView status = findViewById(R.id.library_sync_status);
        if (status != null) status.setText(ApiUpdateManager.readableSyncStatus(store.status()));
    }

    protected abstract void showLibrary();
}
