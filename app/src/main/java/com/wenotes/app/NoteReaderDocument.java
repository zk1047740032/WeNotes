package com.wenotes.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;

/** Text remains one paginated layout; note decorations are drawn around each visible fragment. */
final class NoteReaderDocument {
    private static final class NoteBlock {
        int label;
        int quoteStart = -1;
        int quoteEnd;
        int thoughtStart = -1;
        int thoughtEnd;
        int end;
        boolean highlight;
    }

    static final class Page {
        final int firstLine;
        final int endLine;
        final int start;
        final int end;
        final String chapter;

        Page(StaticLayout layout, int firstLine, int endLine, String chapter) {
            this.firstLine = firstLine;
            this.endLine = endLine;
            this.start = layout.getLineStart(firstLine);
            this.end = layout.getLineEnd(endLine - 1);
            this.chapter = chapter;
        }
    }

    final StaticLayout layout;
    final List<Page> pages;
    private final List<NoteBlock> blocks;
    private final float density;
    private final int pagePadding;
    private final int rule;
    private final int outline;
    private final int paper;
    private final Drawable highlightIcon;
    private final Drawable noteIcon;
    private final Paint decoration = new Paint(Paint.ANTI_ALIAS_FLAG);

    private NoteReaderDocument(Context context, StaticLayout layout, List<Page> pages, List<NoteBlock> blocks) {
        this.layout = layout;
        this.pages = pages;
        this.blocks = blocks;
        density = context.getResources().getDisplayMetrics().density;
        pagePadding = dp(18);
        rule = context.getColor(R.color.reader_rule);
        outline = context.getColor(R.color.reader_bubble_outline);
        paper = context.getColor(R.color.reader_paper);
        highlightIcon = context.getDrawable(R.drawable.ic_reader_highlight);
        noteIcon = context.getDrawable(R.drawable.ic_reader_note);
    }

    static NoteReaderDocument create(Context context, List<BookNote> notes, TextPaint paint,
                                     int width, int height, float lineSpacing) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        List<Integer> chapterStarts = new ArrayList<>();
        List<String> chapters = new ArrayList<>();
        List<NoteBlock> blocks = new ArrayList<>();
        float density = context.getResources().getDisplayMetrics().density;
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        int muted = context.getColor(R.color.reader_muted);
        int blue = context.getColor(R.color.reader_thought);
        BookNote previous = null;
        for (BookNote note : notes) {
            if (Thread.currentThread().isInterrupted()) return null;
            if (previous == null || previous.chapterOrder != note.chapterOrder || !previous.chapter.equals(note.chapter)) {
                String chapter = note.chapter.isEmpty() ? context.getString(R.string.general_notes) : note.chapter;
                chapterStarts.add(text.length());
                chapters.add(chapter);
                int start = text.length();
                text.append(chapter).append("\n\n");
                text.setSpan(new RelativeSizeSpan(1.3f), start, start + chapter.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new StyleSpan(Typeface.BOLD), start, start + chapter.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            NoteBlock block = new NoteBlock();
            block.highlight = "highlight".equals(note.kind) && note.content.isEmpty();
            block.label = text.length();
            text.append(context.getString(block.highlight ? R.string.note_highlight : R.string.reader_note)).append("\n");
            style(text, block.label, text.length(), 12 * scaledDensity / paint.getTextSize(), muted);
            text.setSpan(new LeadingMarginSpan.Standard(Math.round(20 * density)), block.label, text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!note.quote.isEmpty()) {
                block.quoteStart = text.length();
                text.append(note.quote);
                block.quoteEnd = text.length();
                if (!block.highlight) text.setSpan(new LeadingMarginSpan.Standard(Math.round(15 * density)),
                        block.quoteStart, block.quoteEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!block.highlight) {
                text.append(note.quote.isEmpty() ? "\n" : "\n\n");
                block.thoughtStart = text.length();
                text.append(context.getString(R.string.reader_my_thought)).append("\n");
                style(text, block.thoughtStart, text.length(), 12 * scaledDensity / paint.getTextSize(), blue);
                int contentStart = text.length();
                text.append(note.content.isEmpty() ? context.getString(R.string.no_text_content) : note.content);
                block.thoughtEnd = text.length();
                style(text, contentStart, block.thoughtEnd, 17 * scaledDensity / paint.getTextSize(), blue);
                text.setSpan(new LeadingMarginSpan.Standard(Math.round(46 * density)), block.thoughtStart, block.thoughtEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (note.quote.isEmpty()) {
                text.append(context.getString(R.string.no_text_content));
            }
            block.end = text.length();
            blocks.add(block);
            text.append("\n\n");
            previous = note;
        }
        if (text.length() >= 2) text.delete(text.length() - 2, text.length());
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width - Math.round(18 * density)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(lineSpacing, 1.12f).build();
        List<Page> pages = new ArrayList<>();
        int availableHeight = Math.max(1, height - Math.round(36 * density));
        int chapterIndex = 0;
        for (int first = 0; first < layout.getLineCount();) {
            if (Thread.currentThread().isInterrupted()) return null;
            int end = first;
            int top = layout.getLineTop(first);
            while (end < layout.getLineCount() && layout.getLineBottom(end) - top <= availableHeight) end++;
            end = Math.max(first + 1, end);
            for (NoteBlock block : blocks) {
                if (block.label >= layout.getLineEnd(end - 1)) break;
                int label = layout.getLineForOffset(block.label);
                if (label > first && label < end && end - label < 3) end = label;
                if (block.thoughtStart >= 0) {
                    int thought = layout.getLineForOffset(block.thoughtStart);
                    if (thought > first && thought < end && end - thought < 2) end = thought;
                }
                if (label > first && label < end && end - label < 3) end = label;
            }
            // Keep a chapter heading with its first note instead of stranding it at the foot.
            for (int offset : chapterStarts) {
                int heading = layout.getLineForOffset(offset);
                if (heading > first && heading < end && end - heading < 4) {
                    end = heading;
                    break;
                }
            }
            while (chapterIndex + 1 < chapterStarts.size()
                    && chapterStarts.get(chapterIndex + 1) <= layout.getLineStart(first)) chapterIndex++;
            pages.add(new Page(layout, first, end, chapters.isEmpty() ? "" : chapters.get(chapterIndex)));
            first = end;
        }
        return new NoteReaderDocument(context, layout, pages, blocks);
    }

    private static void style(SpannableStringBuilder text, int start, int end, float size, int color) {
        text.setSpan(new RelativeSizeSpan(size), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new TypefaceSpan("sans-serif"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    void drawPage(Canvas canvas, Page page, int left, int top, int width, int height) {
        int firstTop = layout.getLineTop(page.firstLine);
        int lastBottom = layout.getLineBottom(page.endLine - 1);
        int save = canvas.save();
        canvas.clipRect(left, top, left + width, top + height);
        canvas.translate(left, top + pagePadding - firstTop);
        float fragmentTop = firstTop - pagePadding + dp(1);
        float fragmentBottom = lastBottom + pagePadding - dp(1);
        decoration.setStrokeWidth(Math.max(1, density));
        for (NoteBlock block : blocks) {
            if (block.label >= page.end) break;
            if (block.end <= page.start) continue;
            int labelLine = layout.getLineForOffset(block.label);
            if (labelLine >= page.firstLine && labelLine < page.endLine) {
                int iconTop = (layout.getLineTop(labelLine) + layout.getLineBottom(labelLine) - dp(14)) / 2;
                Drawable icon = block.highlight ? highlightIcon : noteIcon;
                icon.setBounds(0, iconTop, dp(14), iconTop + dp(14));
                icon.draw(canvas);
            }
            if (block.highlight) {
                int lastLine = layout.getLineForOffset(Math.max(block.label, block.end - 1));
                if (lastLine >= page.firstLine && lastLine < page.endLine) {
                    decoration.setColor(rule);
                    canvas.drawLine(0, layout.getLineBottom(lastLine) + dp(16), width, layout.getLineBottom(lastLine) + dp(16), decoration);
                }
                continue;
            }
            int firstThoughtLine = layout.getLineForOffset(block.thoughtStart);
            int lastThoughtLine = layout.getLineForOffset(block.thoughtEnd - 1);
            float bubbleTop = layout.getLineTop(firstThoughtLine) - dp(17);
            float bubbleBottom = layout.getLineBottom(lastThoughtLine) + dp(17);
            float sourceTop = block.quoteStart < 0 ? bubbleTop : layout.getLineTop(layout.getLineForOffset(block.quoteStart));
            decoration.setColor(rule);
            canvas.drawLine(0, Math.max(sourceTop, fragmentTop), 0, Math.min(bubbleBottom, fragmentBottom), decoration);
            if (firstThoughtLine >= page.endLine || lastThoughtLine < page.firstLine) continue;
            RectF frame = new RectF(dp(29), Math.max(bubbleTop, fragmentTop), width - dp(1), Math.min(bubbleBottom, fragmentBottom));
            decoration.setStyle(Paint.Style.FILL);
            decoration.setColor(paper);
            canvas.drawRoundRect(frame, dp(14), dp(14), decoration);
            decoration.setStyle(Paint.Style.STROKE);
            decoration.setColor(outline);
            canvas.drawRoundRect(frame, dp(14), dp(14), decoration);
            float tailY = Math.min(frame.top + dp(29), frame.centerY());
            canvas.drawLine(0, tailY, frame.left - dp(6), tailY, decoration);
            decoration.setStyle(Paint.Style.FILL);
            decoration.setColor(paper);
            canvas.drawRect(frame.left - dp(1), tailY - dp(7), frame.left + dp(1), tailY + dp(7), decoration);
            Path tail = new Path();
            tail.moveTo(frame.left, tailY - dp(6));
            tail.lineTo(frame.left - dp(6), tailY);
            tail.lineTo(frame.left, tailY + dp(6));
            decoration.setColor(outline);
            decoration.setStyle(Paint.Style.STROKE);
            canvas.drawPath(tail, decoration);
            decoration.setStyle(Paint.Style.FILL);
        }
        int text = canvas.save();
        canvas.clipRect(0, firstTop, width, lastBottom);
        layout.draw(canvas);
        canvas.restoreToCount(text);
        canvas.restoreToCount(save);
    }

    private int dp(float value) { return Math.round(value * density); }

    int pageForOffset(int offset) {
        for (int i = pages.size() - 1; i >= 0; i--) if (offset >= pages.get(i).start) return i;
        return 0;
    }
}
