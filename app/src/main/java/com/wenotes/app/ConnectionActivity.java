package com.wenotes.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Owns account configuration; the home screen only opens this page. */
public final class ConnectionActivity extends Activity {
    private EditText keyField;
    private TextView keyStatus;
    private TextView syncStatus;
    private HighlightStore store;
    private SharedPreferences statePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener =
            (prefs, key) -> runOnUiThread(this::refreshUi);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_connection);
        AppNavigation.bind(this, 3);
        keyField = findViewById(R.id.api_key);
        keyStatus = findViewById(R.id.key_status);
        syncStatus = findViewById(R.id.sync_status);
        store = new HighlightStore(this);
        statePrefs = getSharedPreferences("highlight_state", Context.MODE_PRIVATE);
        findViewById(R.id.connection_back).setOnClickListener(view -> finish());
        findViewById(R.id.save_sync).setOnClickListener(view -> saveAndSync());
        findViewById(R.id.clear_data).setOnClickListener(view -> clearData());
        findViewById(R.id.open_key_help).setOnClickListener(view -> openKeyHelp());
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        statePrefs.registerOnSharedPreferenceChangeListener(stateListener);
        refreshUi();
    }

    @Override
    protected void onStop() {
        statePrefs.unregisterOnSharedPreferenceChangeListener(stateListener);
        super.onStop();
    }

    private void openKeyHelp() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.key_help_url))));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndSync() {
        String entered = keyField.getText().toString().trim();
        SecretStore secrets = new SecretStore(this);
        try {
            String previous = secrets.read();
            if (entered.isEmpty() && previous.isEmpty()) {
                keyField.setError(getString(R.string.key_required));
                return;
            }
            if (!entered.isEmpty()) {
                if (!entered.startsWith("wrk-")) {
                    keyField.setError(getString(R.string.key_invalid_prefix));
                    return;
                }
                if (!entered.equals(previous)) {
                    SyncWorker.cancel(this);
                    store.clear();
                    secrets.save(entered);
                }
                keyField.setText("");
            }
            store.setStatus(getString(R.string.waiting_sync));
            SyncWorker.schedule(this);
            SyncWorker.syncNow(this);
            HighlightWidgetProvider.renderAll(this);
            refreshUi();
        } catch (Exception error) {
            syncStatus.setText(getString(R.string.save_key_failed, error.getClass().getSimpleName()));
        }
    }

    private void clearData() {
        try {
            SyncWorker.cancel(this);
            new SecretStore(this).clear();
            store.clear();
            keyField.setText("");
            HighlightWidgetProvider.renderAll(this);
            refreshUi();
        } catch (Exception error) {
            syncStatus.setText(getString(R.string.clear_data_failed, error.getClass().getSimpleName()));
        }
    }

    private void refreshUi() {
        keyStatus.setText(new SecretStore(this).hasKey() ? R.string.key_saved : R.string.key_not_saved);
        long synced = store.lastSync();
        String status = store.status();
        if (synced != 0) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(synced));
            status = getString(R.string.sync_status_with_time, status, time);
        }
        syncStatus.setText(status);
    }
}
