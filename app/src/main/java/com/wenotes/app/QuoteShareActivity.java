package com.wenotes.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class QuoteShareActivity extends Activity {
    static final String EXTRA_TEXT = "quote_text";
    static final String EXTRA_AUTHOR = "quote_author";
    static final String EXTRA_TITLE = "quote_title";
    private static final int SAVE_PERMISSION = 51;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String text;
    private String author;
    private String title;
    private int style;
    private int generation;
    private Bitmap image;
    private ImageView preview;
    private Button blackWhite;
    private Button blueMagazine;
    private Button save;
    private TextView status;
    private boolean saving;
    private boolean pendingSave;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        text = getIntent().getStringExtra(EXTRA_TEXT);
        author = getIntent().getStringExtra(EXTRA_AUTHOR);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (text == null || text.trim().isEmpty()) { finish(); return; }
        setContentView(R.layout.activity_quote_share);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View root = findViewById(R.id.quote_share_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
        preview = findViewById(R.id.quote_image_preview);
        preview.setContentDescription(text + "\n" + QuoteImageRenderer.source(author, title));
        blackWhite = findViewById(R.id.quote_style_black_white);
        blueMagazine = findViewById(R.id.quote_style_blue_magazine);
        save = findViewById(R.id.quote_image_save);
        status = findViewById(R.id.quote_image_status);
        findViewById(R.id.quote_share_back).setOnClickListener(view -> finish());
        blackWhite.setOnClickListener(view -> selectStyle(QuoteImageRenderer.BLACK_WHITE));
        blueMagazine.setOnClickListener(view -> selectStyle(QuoteImageRenderer.BLUE_MAGAZINE));
        save.setOnClickListener(view -> saveImage());
        selectStyle(state == null ? QuoteImageRenderer.BLACK_WHITE : state.getInt("quote_style"));
    }

    private void selectStyle(int selected) {
        if (saving) return;
        style = selected == QuoteImageRenderer.BLUE_MAGAZINE ? selected : QuoteImageRenderer.BLACK_WHITE;
        AppStyle.select(blackWhite, style == QuoteImageRenderer.BLACK_WHITE);
        AppStyle.select(blueMagazine, style == QuoteImageRenderer.BLUE_MAGAZINE);
        save.setEnabled(false);
        image = null;
        preview.setImageDrawable(null);
        status.setText(R.string.quote_image_loading);
        findViewById(R.id.quote_image_progress).setVisibility(View.VISIBLE);
        int request = ++generation;
        int selectedStyle = style;
        worker.execute(() -> {
            Bitmap rendered = null;
            try { rendered = QuoteImageRenderer.render(text, author, title, selectedStyle); }
            catch (RuntimeException | OutOfMemoryError ignored) { /* Leave the preview retryable. */ }
            Bitmap result = rendered;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || request != generation) {
                    if (result != null) result.recycle();
                    return;
                }
                image = result;
                preview.setImageBitmap(image);
                findViewById(R.id.quote_image_progress).setVisibility(View.GONE);
                status.setText(image == null ? getString(R.string.quote_image_failed) : "");
                save.setEnabled(image != null);
                if (pendingSave && image != null) { pendingSave = false; saveImage(); }
            });
        });
    }

    private void saveImage() {
        if (saving || image == null) return;
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, SAVE_PERMISSION);
            return;
        }
        saving = true;
        save.setEnabled(false);
        blackWhite.setEnabled(false);
        blueMagazine.setEnabled(false);
        status.setText(R.string.quote_image_saving);
        Bitmap selected = image;
        Context context = getApplicationContext();
        worker.execute(() -> {
            boolean saved;
            try { QuoteImageStore.save(context, selected); saved = true; }
            catch (IOException | RuntimeException error) { saved = false; }
            boolean success = saved;
            runOnUiThread(() -> {
                Toast.makeText(context, success ? R.string.quote_image_saved : R.string.quote_image_save_failed,
                        Toast.LENGTH_SHORT).show();
                if (isDestroyed() || isFinishing()) return;
                saving = false;
                save.setEnabled(true);
                blackWhite.setEnabled(true);
                blueMagazine.setEnabled(true);
                status.setText(success ? R.string.quote_image_saved : R.string.quote_image_save_failed);
            });
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != SAVE_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (image == null) pendingSave = true;
            else saveImage();
        } else Toast.makeText(this, R.string.quote_image_permission_denied, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("quote_style", style);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        generation++;
        worker.shutdown(); // Allow an in-flight save to finish when the preview is closed.
        super.onDestroy();
    }
}
