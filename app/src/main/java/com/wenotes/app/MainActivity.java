package com.wenotes.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private TextView quoteView;
    private TextView thoughtView;
    private TextView bookView;
    private HighlightStore store;
    private SharedPreferences statePrefs;
    private HomeModuleSorter moduleSorter;
    private List<Highlight> cachedHighlights = new ArrayList<>();
    private long loadedSync = -1L;
    private long loadedGeneration = -1L;
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener =
            (prefs, key) -> runOnUiThread(this::refreshUi);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        AppNavigation.bind(this, 0);
        moduleSorter = new HomeModuleSorter(this);
        quoteView = findViewById(R.id.current_quote);
        thoughtView = findViewById(R.id.current_thought);
        bookView = findViewById(R.id.current_book);
        store = new HighlightStore(this);
        statePrefs = getSharedPreferences("highlight_state", Context.MODE_PRIVATE);
        Button next = findViewById(R.id.next_quote);
        next.setOnClickListener(view -> {
            store.next(store.read().size());
            HighlightWidgetProvider.renderAll(this);
            refreshUi();
        });
        findViewById(R.id.open_connection).setOnClickListener(view ->
                startActivity(new Intent(this, ConnectionActivity.class)));
        findViewById(R.id.open_shelf).setOnClickListener(view ->
                startActivity(new Intent(this, BookshelfActivity.class)));
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

    @Override
    protected void onDestroy() {
        moduleSorter.dispose();
        super.onDestroy();
    }

    private void refreshUi() {
        long synced = store.lastSync();
        long generation = store.generation();
        if (synced != loadedSync || generation != loadedGeneration) {
            cachedHighlights = store.read();
            loadedSync = synced;
            loadedGeneration = generation;
        }
        List<Highlight> highlights = cachedHighlights;
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
    }
}
