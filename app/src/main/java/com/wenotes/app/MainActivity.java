package com.wenotes.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.ZonedDateTime;

public final class MainActivity extends Activity {
    private QuoteCardLayout quoteCard;
    private QuoteStackLayout quoteStack;
    private View nextCard;
    private ReadingCardsView readingCards;
    private HighlightStore store;
    private NoteFavoriteStore favoriteStore;
    private CoverLoader coverLoader;
    private Highlight currentHighlight;
    private SharedPreferences statePrefs;
    private LibrarySnapshot cachedLibrary = LibrarySnapshot.empty();
    private final Map<String, ShelfBook> cachedBooks = new HashMap<>();
    private long loadedSync = -1L;
    private long loadedGeneration = -1L;
    private boolean updateActive;
    private final Runnable calendarRollover = () -> {
        refreshUi();
        scheduleCalendarRollover();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener =
            (prefs, key) -> runOnUiThread(this::refreshUi);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        AppNavigation.bind(this, 0);
        store = new HighlightStore(this);
        favoriteStore = new NoteFavoriteStore(this);
        coverLoader = new CoverLoader(this);
        statePrefs = getSharedPreferences("highlight_state", Context.MODE_PRIVATE);
        quoteCard = findViewById(R.id.module_quote);
        quoteStack = findViewById(R.id.quote_stack);
        nextCard = findViewById(R.id.quote_next_card);
        readingCards = findViewById(R.id.reading_cards);
        readingCards.restoreSelection(state);
        quoteCard.setBackground(new QuoteCardBackground(this));
        nextCard.setBackground(new QuoteCardBackground(this));
        ((TextView) nextCard.findViewById(R.id.current_quote)).setTextIsSelectable(false);
        ((TextView) nextCard.findViewById(R.id.current_thought)).setTextIsSelectable(false);
        quoteCard.setOnLongClickListener(view -> shareQuote());
        quoteCard.findViewById(R.id.current_book_panel).setOnLongClickListener(view -> openQuoteBook());
        quoteCard.findViewById(R.id.current_quote).setOnLongClickListener(view -> shareQuote());
        quoteCard.findViewById(R.id.current_thought).setOnLongClickListener(view -> shareQuote());
        quoteCard.setSwipeListener(new QuoteCardLayout.SwipeListener() {
            @Override
            public boolean onBeforeSwipe(boolean left) {
                return updateQuoteFavoriteForSwipe(left);
            }

            @Override
            public void onAdvance(boolean left) {
                nextQuote();
            }
        });
        quoteCard.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (currentHighlight == null) return;
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, getString(R.string.quote_image_title)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_next_quote, getString(R.string.next_quote)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_favorite_quote, getString(quoteCard.isActivated()
                                ? R.string.unfavorite_quote_and_next : R.string.favorite_quote_and_next)));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (currentHighlight != null && action == R.id.action_next_quote) {
                    return quoteCard.performSwipe(!quoteCard.isActivated());
                }
                if (currentHighlight != null && action == R.id.action_favorite_quote) {
                    return quoteCard.performSwipe(quoteCard.isActivated());
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        scheduleCalendarRollover();
        updateActive = true;
        ApiUpdateManager.checkAsync(this, false, () -> {
            if (updateActive && !isFinishing() && !isDestroyed()) AppUpdateActivity.offer(this);
        });
    }

    @Override
    protected void onPause() {
        updateActive = false;
        readingCards.removeCallbacks(calendarRollover);
        super.onPause();
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
        readingCards.resetSwipe();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        coverLoader.dispose();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        readingCards.saveSelection(state);
        super.onSaveInstanceState(state);
    }

    private void scheduleCalendarRollover() {
        ZonedDateTime now = ZonedDateTime.now();
        long delay = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).toMillis();
        readingCards.removeCallbacks(calendarRollover);
        readingCards.postDelayed(calendarRollover, delay + 100);
    }

    private void nextQuote() {
        if (cachedLibrary.highlights.isEmpty()) return;
        store.next(cachedLibrary.highlights.size());
        HighlightWidgetProvider.renderAll(this);
        refreshUi();
    }

    private boolean updateQuoteFavoriteForSwipe(boolean left) {
        if (currentHighlight == null) return false;
        try {
            FavoriteNote favorite = FavoriteNote.fromHighlight(currentHighlight, cachedLibrary);
            boolean saved = favoriteStore.read().containsKey(FavoriteNote.key(favorite.note));
            if (left != saved) return true;
            if (saved) favoriteStore.toggle(favorite);
            else favoriteStore.add(favorite);
            quoteCard.setActivated(!saved);
            quoteCard.playFavoriteSweep();
            if (saved) Toast.makeText(this, R.string.quote_removed, Toast.LENGTH_SHORT).show();
            else {
                quoteCard.announceForAccessibility(getString(R.string.quote_saved));
            }
            return true;
        } catch (IllegalStateException error) {
            Toast.makeText(this, R.string.favorite_save_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean openQuoteBook() {
        if (currentHighlight == null || currentHighlight.bookId.isEmpty()) return false;
        startActivity(new Intent(this, BookNotesActivity.class)
                .putExtra("book_id", currentHighlight.bookId)
                .putExtra("book_title", currentHighlight.title));
        return true;
    }

    private boolean shareQuote() {
        if (currentHighlight == null || currentHighlight.text.trim().isEmpty()) return false;
        startActivity(new Intent(this, QuoteShareActivity.class)
                .putExtra(QuoteShareActivity.EXTRA_TEXT, currentHighlight.text)
                .putExtra(QuoteShareActivity.EXTRA_AUTHOR, currentHighlight.author)
                .putExtra(QuoteShareActivity.EXTRA_TITLE, currentHighlight.title));
        return true;
    }

    private void refreshUi() {
        long synced = store.lastSync();
        long generation = store.generation();
        if (synced != loadedSync || generation != loadedGeneration) {
            cachedLibrary = store.readLibrary();
            cachedBooks.clear();
            for (ShelfBook book : cachedLibrary.books) cachedBooks.put(book.id, book);
            loadedSync = synced;
            loadedGeneration = generation;
        }
        coverLoader.loadMissing(cachedLibrary.books, () -> {
            readingCards.refreshCovers();
            refreshUi();
        });
        readingCards.bind(cachedLibrary, coverLoader);
        List<Highlight> highlights = cachedLibrary.highlights;
        Map<String, FavoriteNote> favorites = favoriteStore.read();
        quoteCard.setSwipeEnabled(!highlights.isEmpty());
        quoteStack.setCardCount(highlights.size());
        currentHighlight = null;
        if (highlights.isEmpty()) {
            bindQuote(quoteCard, null, favorites);
        } else {
            int index = store.index(highlights.size());
            currentHighlight = highlights.get(index);
            bindQuote(quoteCard, currentHighlight, favorites);
            if (highlights.size() > 1) bindQuote(nextCard, highlights.get((index + 1) % highlights.size()), favorites);
        }
        quoteStack.contentChanged();
    }

    private void bindQuote(View card, Highlight highlight, Map<String, FavoriteNote> favorites) {
        card.setActivated(highlight != null
                && favorites.containsKey(FavoriteNote.key(highlight.bookId, highlight.id)));
        TextView quoteView = card.findViewById(R.id.current_quote);
        TextView thoughtView = card.findViewById(R.id.current_thought);
        View thoughtPanel = card.findViewById(R.id.current_thought_panel);
        View bookPanel = card.findViewById(R.id.current_book_panel);
        TextView bookView = card.findViewById(R.id.current_book);
        TextView chapterView = card.findViewById(R.id.current_chapter);
        ImageView coverView = card.findViewById(R.id.current_cover);
        TextView coverPlaceholder = card.findViewById(R.id.current_cover_placeholder);
        if (highlight == null) {
            quoteView.setText(R.string.empty_quote);
            thoughtPanel.setVisibility(View.GONE);
            bookPanel.setVisibility(View.GONE);
            bookView.setText("");
            chapterView.setText("");
            coverView.setTag(null);
            coverView.setImageDrawable(null);
            coverPlaceholder.setText("");
        } else {
            quoteView.setText(formatQuote(highlight.text, quoteView));
            bookPanel.setVisibility(View.VISIBLE);
            bookView.setText(getString(R.string.quote_book, highlight.title));
            chapterView.setText(highlight.chapter);
            chapterView.setVisibility(highlight.chapter.isEmpty() ? View.GONE : View.VISIBLE);
            coverPlaceholder.setText(highlight.title);
            ShelfBook book = cachedBooks.get(highlight.bookId);
            if (book == null) book = new ShelfBook(highlight.bookId, highlight.title,
                    highlight.author, "book", false, 0, 0, 0);
            coverLoader.bind(coverView, coverPlaceholder, book);
            if (highlight.thought.isEmpty()) {
                thoughtPanel.setVisibility(View.GONE);
            } else {
                thoughtView.setText(highlight.thought);
                thoughtPanel.setVisibility(View.VISIBLE);
            }
        }
    }

    private CharSequence formatQuote(String text, TextView view) {
        SpannableString formatted = new SpannableString(text);
        boolean hasChinese = false;
        int indent = Math.round(view.getPaint().measureText("汉汉"));
        for (int start = 0; start < text.length();) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            for (int offset = start; offset < end;) {
                int codePoint = text.codePointAt(offset);
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                    hasChinese = true;
                    formatted.setSpan(new LeadingMarginSpan.Standard(indent, 0),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
                offset += Character.charCount(codePoint);
            }
            start = end;
        }
        view.setJustificationMode(Build.VERSION.SDK_INT >= 35 && hasChinese
                ? LineBreaker.JUSTIFICATION_MODE_INTER_CHARACTER : LineBreaker.JUSTIFICATION_MODE_INTER_WORD);
        return formatted;
    }
}
