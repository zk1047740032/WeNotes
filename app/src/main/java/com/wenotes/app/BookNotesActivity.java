package com.wenotes.app;

import android.os.Bundle;

public final class BookNotesActivity extends LibraryActivity {
    private BookDetailScreen detailScreen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        detailScreen = new BookDetailScreen(this, getIntent().getStringExtra("book_id"), state);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        detailScreen.save(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        detailScreen.dispose();
        super.onDestroy();
    }

    @Override
    protected void showLibrary() {
        detailScreen.show(snapshot);
    }
}
