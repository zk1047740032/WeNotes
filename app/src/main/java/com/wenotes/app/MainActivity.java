package com.wenotes.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class MainActivity extends Activity {
    private QuoteCardLayout quoteCard;
    private QuoteStackLayout quoteStack;
    private View nextCard;
    private HighlightStore store;
    private NoteFavoriteStore favoriteStore;
    private Highlight currentHighlight;
    private SharedPreferences statePrefs;
    private LibrarySnapshot cachedLibrary = LibrarySnapshot.empty();
    private long loadedSync = -1L;
    private long loadedGeneration = -1L;
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener =
            (prefs, key) -> runOnUiThread(this::refreshUi);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        ApiUpdateManager.checkAsync(this, false, null);
        AppNavigation.bind(this, 0);
        store = new HighlightStore(this);
        favoriteStore = new NoteFavoriteStore(this);
        statePrefs = getSharedPreferences("highlight_state", Context.MODE_PRIVATE);
        quoteCard = findViewById(R.id.module_quote);
        quoteStack = findViewById(R.id.quote_stack);
        nextCard = findViewById(R.id.quote_next_card);
        ((TextView) nextCard.findViewById(R.id.current_quote)).setTextIsSelectable(false);
        ((TextView) nextCard.findViewById(R.id.current_thought)).setTextIsSelectable(false);
        quoteCard.setSwipeListener(new QuoteCardLayout.SwipeListener() {
            @Override
            public boolean onBeforeSwipe(boolean left) {
                return left || favoriteQuote();
            }

            @Override
            public void onAdvance() {
                nextQuote();
            }
        });
        quoteCard.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (currentHighlight == null) return;
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_next_quote, getString(R.string.next_quote)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_favorite_quote, getString(R.string.favorite_quote_and_next)));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (currentHighlight != null && action == R.id.action_next_quote) {
                    return quoteCard.performSwipe(true);
                }
                if (currentHighlight != null && action == R.id.action_favorite_quote) {
                    return quoteCard.performSwipe(false);
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
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
        quoteCard.resetSwipe();
        super.onStop();
    }

    private void nextQuote() {
        if (cachedLibrary.highlights.isEmpty()) return;
        store.next(cachedLibrary.highlights.size());
        HighlightWidgetProvider.renderAll(this);
        refreshUi();
    }

    private boolean favoriteQuote() {
        if (currentHighlight == null) return false;
        try {
            boolean added = favoriteStore.add(FavoriteNote.fromHighlight(currentHighlight, cachedLibrary));
            Toast.makeText(this, added ? R.string.quote_saved : R.string.quote_already_saved,
                    Toast.LENGTH_SHORT).show();
            return true;
        } catch (IllegalStateException error) {
            Toast.makeText(this, R.string.favorite_save_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void refreshUi() {
        long synced = store.lastSync();
        long generation = store.generation();
        if (synced != loadedSync || generation != loadedGeneration) {
            cachedLibrary = store.readLibrary();
            loadedSync = synced;
            loadedGeneration = generation;
        }
        List<Highlight> highlights = cachedLibrary.highlights;
        quoteCard.setSwipeEnabled(!highlights.isEmpty());
        quoteStack.setCardCount(highlights.size());
        currentHighlight = null;
        if (highlights.isEmpty()) {
            bindQuote(quoteCard, null);
        } else {
            int index = store.index(highlights.size());
            currentHighlight = highlights.get(index);
            bindQuote(quoteCard, currentHighlight);
            if (highlights.size() > 1) bindQuote(nextCard, highlights.get((index + 1) % highlights.size()));
        }
        quoteStack.contentChanged();
    }

    private void bindQuote(View card, Highlight highlight) {
        TextView quoteView = card.findViewById(R.id.current_quote);
        TextView thoughtView = card.findViewById(R.id.current_thought);
        View thoughtPanel = card.findViewById(R.id.current_thought_panel);
        TextView bookView = card.findViewById(R.id.current_book);
        if (highlight == null) {
            quoteView.setText(R.string.empty_quote);
            thoughtPanel.setVisibility(View.GONE);
            bookView.setText("");
        } else {
            quoteView.setText(highlight.text);
            bookView.setText(highlight.chapter.isEmpty() ? getString(R.string.quote_book, highlight.title)
                    : getString(R.string.quote_book_chapter, highlight.title, highlight.chapter));
            if (highlight.thought.isEmpty()) {
                thoughtPanel.setVisibility(View.GONE);
            } else {
                thoughtView.setText(highlight.thought);
                thoughtPanel.setVisibility(View.VISIBLE);
            }
        }
    }
}
