package com.wenotes.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** Draws the selected share design from actual text, without app branding. */
final class QuoteImageRenderer {
    static final int BLACK_WHITE = 0;
    static final int BLUE_MAGAZINE = 1;
    static final int WIDTH = 1080;
    private static final int BLUE = Color.rgb(40, 86, 204);

    private QuoteImageRenderer() { }

    static String source(String author, String title) {
        String name = author == null ? "" : author.trim();
        String book = title == null || title.trim().isEmpty() ? "" : "《" + title.trim() + "》";
        return name.isEmpty() ? book : book.isEmpty() ? name : name + " · " + book;
    }

    static Bitmap render(String text, String author, String title, int style) {
        boolean blue = style == BLUE_MAGAZINE;
        int foreground = blue ? Color.WHITE : Color.BLACK;
        int x = blue ? 88 : 132;
        int width = WIDTH - x - 88;
        int top = blue ? 480 : 208;
        TextPaint quotePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        quotePaint.setColor(foreground);
        quotePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        float size = blue ? 100 : 140;
        String words = text.trim();
        if (words.length() <= 80 && !words.contains("\n")) {
            // Short quotes read as phrases; keep closing punctuation with its sentence.
            words = words.replaceAll("([，。！？；][”’」』）】]*+)(?=\\S)", "$1\n");
        }
        StaticLayout quote;
        do {
            quotePaint.setTextSize(size);
            quote = layout(words, quotePaint, width, 1.22f);
            size -= 8;
        } while (quote.getHeight() > 1800 && size >= 56);

        TextPaint sourcePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        sourcePaint.setColor(foreground);
        sourcePaint.setTextSize(36);
        sourcePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        String attribution = source(author, title);
        StaticLayout source = layout(attribution, sourcePaint, width, 1.3f);
        int sourceTop = top + quote.getHeight() + (blue ? 144 : 64);
        int height = Math.max(1440, sourceTop + (attribution.isEmpty() ? 0 : source.getHeight()) + 200);
        // Bound allocations for unusually large imported highlights; never silently truncate text.
        if (height > 8000) throw new IllegalArgumentException("Quote is too long for a single image");
        Bitmap image = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(blue ? BLUE : Color.WHITE);
        Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
        detail.setColor(foreground);
        if (blue) {
            detail.setColor(Color.rgb(178, 202, 255));
            drawQuotation(canvas, detail, 88, 148);
            drawQuotation(canvas, detail, 230, 148);
            detail.setColor(foreground);
            detail.setStrokeWidth(3);
            canvas.drawLine(x, sourceTop - 62, x + 220, sourceTop - 62, detail);
        } else {
            detail.setStrokeWidth(4);
            canvas.drawLine(88, top, 88, sourceTop + (attribution.isEmpty() ? 0 : source.getHeight()), detail);
        }
        drawLayout(canvas, quote, x, top);
        if (!attribution.isEmpty()) drawLayout(canvas, source, x, sourceTop);
        return image;
    }

    @SuppressLint("InlinedApi") // The balanced-strategy value also works with StaticLayout on API 23-28.
    private static StaticLayout layout(String text, TextPaint paint, int width, float spacing) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0, spacing).setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
    }

    private static void drawLayout(Canvas canvas, StaticLayout text, int x, int y) {
        canvas.save();
        canvas.translate(x, y);
        text.draw(canvas);
        canvas.restore();
    }

    private static void drawQuotation(Canvas canvas, Paint paint, int x, int y) {
        Path mark = new Path();
        mark.moveTo(x + 118, y);
        mark.cubicTo(x + 32, y + 30, x, y + 86, x, y + 152);
        mark.lineTo(x, y + 234);
        mark.lineTo(x + 110, y + 234);
        mark.lineTo(x + 110, y + 116);
        mark.lineTo(x + 50, y + 116);
        mark.cubicTo(x + 54, y + 82, x + 78, y + 54, x + 118, y + 34);
        mark.close();
        canvas.drawPath(mark, paint);
    }
}
