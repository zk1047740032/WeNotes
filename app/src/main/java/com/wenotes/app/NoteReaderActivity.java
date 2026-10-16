package com.wenotes.app;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.List;

public final class NoteReaderActivity extends LibraryActivity {
    private String bookId;
    private NoteReaderView reader;
    private LinearLayout controls;
    private TextView title;
    private WindowInsetsControllerCompat bars;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        bookId = getIntent().getStringExtra("book_id");
        if (bookId == null || bookId.isEmpty()) { finish(); return; }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(true);
        bars.setAppearanceLightNavigationBars(true);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.reader_paper));
        reader = new NoteReaderView(this);
        reader.setId(R.id.note_reader);
        reader.restoreOffset(state == null ? getSharedPreferences("note_reading_positions", Context.MODE_PRIVATE)
                .getInt(bookId, 0) : state.getInt("reader_offset", 0));
        reader.setMessage(getString(R.string.loading_library));
        root.addView(reader, new FrameLayout.LayoutParams(-1, -1));
        reader.setOnClickListener(view -> setControlsVisible(controls.getVisibility() != View.VISIBLE));

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(getColor(R.color.reader_paper));
        ImageButton back = new ImageButton(this);
        back.setId(R.id.library_back);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.text_button_background);
        back.setContentDescription(getString(R.string.back));
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setOnClickListener(view -> finish());
        controls.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = new TextView(this);
        title.setText(getIntent().getStringExtra("book_title"));
        title.setTextColor(getColor(R.color.reader_ink));
        title.setTextSize(16);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        controls.setPadding(dp(8), 0, dp(24), 0);
        root.addView(controls, new FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            if (reader.getPaddingLeft() != safe.left || reader.getPaddingTop() != safe.top
                    || reader.getPaddingRight() != safe.right || reader.getPaddingBottom() != safe.bottom) {
                reader.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) controls.getLayoutParams();
            params.topMargin = safe.top;
            params.leftMargin = safe.left;
            params.rightMargin = safe.right;
            controls.setLayoutParams(params);
            return insets;
        });
        setControlsVisible(state != null && state.getBoolean("reader_controls"));
        if (state == null) Toast.makeText(this, R.string.reader_hint, Toast.LENGTH_LONG).show();
    }

    private void setControlsVisible(boolean visible) {
        controls.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) bars.show(WindowInsetsCompat.Type.systemBars());
        else bars.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    protected void showLibrary() {
        ShelfBook book = null;
        for (ShelfBook candidate : snapshot.books) if (candidate.id.equals(bookId)) { book = candidate; break; }
        List<BookNote> notes = NoteReadingOrder.forBook(snapshot, bookId);
        if (book != null) title.setText(book.title);
        if (!notes.isEmpty()) {
            reader.bind(title.getText().toString(), notes);
            return;
        }
        int message = book == null ? (new SecretStore(this).hasKey() ? R.string.sync_library_first : R.string.connect_first)
                : "album".equals(book.kind) ? R.string.audio_notes_limit
                : "articles".equals(book.kind) ? R.string.articles_notes_limit
                : !snapshot.complete ? R.string.sync_library_first
                : book.bookmarkCount > 0 ? R.string.only_bookmarks : R.string.no_book_notes;
        reader.setMessage(getString(message));
        setControlsVisible(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (reader != null) {
            state.putInt("reader_offset", reader.selectedOffset());
            state.putBoolean("reader_controls", controls.getVisibility() == View.VISIBLE);
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        if (reader != null && reader.pageCount() > 0) getSharedPreferences("note_reading_positions", Context.MODE_PRIVATE)
                .edit().putInt(bookId, reader.selectedOffset()).apply();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (reader != null) reader.dispose();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
