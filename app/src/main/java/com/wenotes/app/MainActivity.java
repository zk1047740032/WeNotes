package com.wenotes.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private EditText keyField;
    private TextView keyStatus;
    private TextView quoteView;
    private TextView thoughtView;
    private TextView bookView;
    private TextView syncStatus;
    private HighlightStore store;
    private SharedPreferences statePrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener =
            (prefs, key) -> runOnUiThread(this::refreshUi);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        keyField = findViewById(R.id.api_key);
        keyStatus = findViewById(R.id.key_status);
        quoteView = findViewById(R.id.current_quote);
        thoughtView = findViewById(R.id.current_thought);
        bookView = findViewById(R.id.current_book);
        syncStatus = findViewById(R.id.sync_status);
        store = new HighlightStore(this);
        statePrefs = getSharedPreferences("highlight_state", Context.MODE_PRIVATE);
        Button save = findViewById(R.id.save_sync);
        Button next = findViewById(R.id.next_quote);
        Button clear = findViewById(R.id.clear_data);
        save.setOnClickListener(view -> saveAndSync());
        next.setOnClickListener(view -> {
            store.next(store.read().size());
            HighlightWidgetProvider.renderAll(this);
            refreshUi();
        });
        clear.setOnClickListener(view -> clearData());
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

    private void saveAndSync() {
        String entered = keyField.getText().toString().trim();
        SecretStore secrets = new SecretStore(this);
        try {
            String previous = secrets.read();
            if (entered.isEmpty() && previous.isEmpty()) {
                keyField.setError("请输入 API Key");
                return;
            }
            if (!entered.isEmpty()) {
                if (!entered.startsWith("wrk-")) {
                    keyField.setError("API Key 应以 wrk- 开头");
                    return;
                }
                if (!entered.equals(previous)) {
                    SyncWorker.cancel(this);
                    store.clear();
                    secrets.save(entered);
                }
                keyField.setText("");
            }
            store.setStatus("等待网络，准备同步…");
            SyncWorker.schedule(this);
            SyncWorker.syncNow(this);
            HighlightWidgetProvider.renderAll(this);
            refreshUi();
        } catch (Exception error) {
            syncStatus.setText("无法保存密钥：" + error.getClass().getSimpleName());
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
            syncStatus.setText("清除失败：" + error.getClass().getSimpleName());
        }
    }

    private void refreshUi() {
        keyStatus.setText(new SecretStore(this).hasKey()
                ? "已连接 · 留空并点击“保存并同步”可再次同步" : "尚未连接");
        List<Highlight> highlights = store.read();
        if (highlights.isEmpty()) {
            quoteView.setText("同步后，这里会显示你的划线。");
            thoughtView.setVisibility(View.GONE);
            bookView.setText("");
        } else {
            Highlight highlight = highlights.get(store.index(highlights.size()));
            quoteView.setText("“" + highlight.text + "”");
            bookView.setText("《" + highlight.title + "》"
                    + (highlight.chapter.isEmpty() ? "" : " · " + highlight.chapter));
            if (highlight.thought.isEmpty()) {
                thoughtView.setVisibility(View.GONE);
            } else {
                thoughtView.setText("我的想法：" + highlight.thought);
                thoughtView.setVisibility(View.VISIBLE);
            }
        }
        String status = store.status();
        long synced = store.lastSync();
        if (synced != 0) {
            status += "\n上次同步：" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    .format(new Date(synced));
        }
        syncStatus.setText(status);
    }
}
