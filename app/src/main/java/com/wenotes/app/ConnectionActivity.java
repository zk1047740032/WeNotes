package com.wenotes.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The My page keeps WeRead connection and AI credentials in separate tabs. */
public final class ConnectionActivity extends Activity {
    private static final int WEREAD = 0;
    private static final int AI = 1;
    private int selectedTab;
    private EditText keyField;
    private EditText aiKeyField;
    private TextView keyStatus;
    private TextView aiKeyStatus;
    private TextView syncStatus;
    private HighlightStore store;
    private SharedPreferences statePrefs;
    private SharedPreferences updatePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener updateListener =
            (prefs, key) -> runOnUiThread(this::refreshUpdates);
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener =
            (prefs, key) -> runOnUiThread(this::refreshUi);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_connection);
        AppNavigation.bind(this, 3);
        keyField = findViewById(R.id.api_key);
        aiKeyField = findViewById(R.id.ai_api_key);
        keyStatus = findViewById(R.id.key_status);
        aiKeyStatus = findViewById(R.id.ai_key_status);
        syncStatus = findViewById(R.id.sync_status);
        store = new HighlightStore(this);
        statePrefs = getSharedPreferences("highlight_state", Context.MODE_PRIVATE);
        updatePrefs = getSharedPreferences(ApiUpdateManager.PREFS, Context.MODE_PRIVATE);
        findViewById(R.id.check_updates).setOnClickListener(view -> checkUpdates());
        findViewById(R.id.open_app_update).setOnClickListener(view -> openAppUpdate());
        findViewById(R.id.save_sync).setOnClickListener(view -> saveAndSync());
        findViewById(R.id.clear_data).setOnClickListener(view -> clearData());
        findViewById(R.id.open_key_help).setOnClickListener(view -> openKeyHelp());
        findViewById(R.id.weread_tab).setOnClickListener(view -> selectTab(WEREAD));
        findViewById(R.id.ai_tab).setOnClickListener(view -> selectTab(AI));
        findViewById(R.id.save_ai_key).setOnClickListener(view -> saveAiKey());
        findViewById(R.id.clear_ai_key).setOnClickListener(view -> clearAiKey());
        selectTab(state == null ? WEREAD : state.getInt("selected_tab", WEREAD));
        refreshUi();
        refreshUpdates();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected_tab", selectedTab);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectTab(WEREAD);
    }

    private void selectTab(int tab) {
        selectedTab = tab == AI ? AI : WEREAD;
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        findViewById(R.id.weread_panel).setVisibility(selectedTab == WEREAD ? View.VISIBLE : View.GONE);
        findViewById(R.id.ai_panel).setVisibility(selectedTab == AI ? View.VISIBLE : View.GONE);
        styleTab(findViewById(R.id.weread_tab), selectedTab == WEREAD);
        styleTab(findViewById(R.id.ai_tab), selectedTab == AI);
    }

    private void styleTab(Button button, boolean selected) {
        AppStyle.select(button, selected);
    }

    @Override
    protected void onStart() {
        super.onStart();
        statePrefs.registerOnSharedPreferenceChangeListener(stateListener);
        updatePrefs.registerOnSharedPreferenceChangeListener(updateListener);
        ApiUpdateManager.checkAsync(this, false, null);
        refreshUi();
        refreshUpdates();
    }

    @Override
    protected void onStop() {
        statePrefs.unregisterOnSharedPreferenceChangeListener(stateListener);
        updatePrefs.unregisterOnSharedPreferenceChangeListener(updateListener);
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

    private void saveAiKey() {
        String entered = aiKeyField.getText().toString().trim();
        if (entered.isEmpty()) {
            aiKeyField.setError(getString(R.string.key_required));
            return;
        }
        try {
            SecretStore.forAi(this).save(entered);
            aiKeyField.setText("");
            refreshUi();
            Toast.makeText(this, R.string.ai_key_save_success, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            aiKeyStatus.setText(getString(R.string.save_key_failed, error.getClass().getSimpleName()));
        }
    }

    private void clearAiKey() {
        try {
            SecretStore.forAi(this).clear();
            aiKeyField.setText("");
            aiKeyField.setError(null);
            refreshUi();
        } catch (Exception error) {
            aiKeyStatus.setText(getString(R.string.clear_data_failed, error.getClass().getSimpleName()));
        }
    }

    private void refreshUi() {
        keyStatus.setText(new SecretStore(this).hasKey() ? R.string.key_saved : R.string.key_not_saved);
        boolean hasAiKey = SecretStore.forAi(this).hasKey();
        aiKeyStatus.setText(hasAiKey ? R.string.ai_key_saved : R.string.key_not_saved);
        findViewById(R.id.clear_ai_key).setVisibility(hasAiKey ? View.VISIBLE : View.GONE);
        long synced = store.lastSync();
        String status = ApiUpdateManager.readableSyncStatus(store.status());
        if (synced != 0) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(synced));
            status = getString(R.string.sync_status_with_time, status, time);
        }
        syncStatus.setText(status);
    }

    private void refreshUpdates() {
        ApiUpdateManager.Snapshot updates = new ApiUpdateManager(this).snapshot();
        ((TextView) findViewById(R.id.update_status)).setText(updates.description(this));
        findViewById(R.id.open_app_update).setVisibility(updates.appUpdateAvailable() ? View.VISIBLE : View.GONE);
    }

    private void checkUpdates() {
        Button button = findViewById(R.id.check_updates);
        button.setEnabled(false);
        button.setText(R.string.checking_updates);
        ApiUpdateManager.checkAsync(this, true, () -> {
            if (isFinishing() || isDestroyed()) return;
            button.setEnabled(true);
            button.setText(R.string.check_updates);
            refreshUpdates();
        });
    }

    private void openAppUpdate() {
        ApiUpdateManager.Snapshot updates = new ApiUpdateManager(this).snapshot();
        if (!updates.appUpdateAvailable()) return;
        try {
            startActivity(new Intent(this, AppUpdateActivity.class));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }
}
