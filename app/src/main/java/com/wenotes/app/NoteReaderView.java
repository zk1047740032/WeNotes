package com.wenotes.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Paper pages fold around the perpendicular bisector between the corner and the dragged tip. */
final class NoteReaderView extends View {
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ExecutorService paginator = Executors.newSingleThreadExecutor();
    private final int paper;
    private final int muted;
    private final int touchSlop;
    private List<BookNote> notes = new ArrayList<>();
    private String title = "";
    private String message = "";
    private NoteReaderDocument document;
    private Future<?> pagination;
    private int requestId;
    private boolean disposed;
    private int pageIndex;
    private int positionOffset;
    private int bodyLeft;
    private int bodyTop;
    private int bodyWidth;
    private int bodyHeight;
    private int bitmapWidth;
    private int bitmapHeight;
    private Bitmap currentBitmap;
    private Bitmap targetBitmap;
    private int turnDirection;
    private float progress;
    private float downX;
    private float downY;
    private float bend;
    private boolean topCorner;
    private boolean dragging;
    private boolean moved;
    private boolean gestureActive;
    private VelocityTracker velocity;
    private ValueAnimator animator;

    NoteReaderView(Context context) {
        super(context);
        paper = context.getColor(R.color.reader_paper);
        muted = context.getColor(R.color.reader_muted);
        bodyPaint.setColor(context.getColor(R.color.reader_ink));
        bodyPaint.setTextSize(sp(19));
        bodyPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        smallPaint.setColor(muted);
        smallPaint.setTextSize(sp(11));
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void restoreOffset(int offset) { positionOffset = Math.max(0, offset); }
    int selectedOffset() { return document == null ? positionOffset : document.pages.get(pageIndex).start; }
    int selectedPage() { return pageIndex; }
    int pageCount() { return document == null ? 0 : document.pages.size(); }
    NoteReaderDocument document() { return document; }
    float turnProgress() { return progress; }

    void bind(String bookTitle, List<BookNote> orderedNotes) {
        title = bookTitle;
        notes = new ArrayList<>(orderedNotes);
        rebuild();
    }

    void setMessage(String value) {
        positionOffset = selectedOffset();
        cancelPagination();
        resetTurn();
        document = null;
        currentBitmap = null;
        notes.clear();
        message = value;
        setContentDescription(value);
        invalidate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int nextLeft = getPaddingLeft() + dp(28);
        int nextTop = getPaddingTop() + dp(64);
        int nextWidth = getWidth() - nextLeft - getPaddingRight() - dp(28);
        int nextHeight = getHeight() - nextTop - getPaddingBottom() - dp(56);
        if (bodyLeft == nextLeft && bodyTop == nextTop && bodyWidth == nextWidth && bodyHeight == nextHeight
                && bitmapWidth == getWidth() && bitmapHeight == getHeight()) return;
        bodyLeft = nextLeft;
        bodyTop = nextTop;
        bodyWidth = nextWidth;
        bodyHeight = nextHeight;
        bitmapWidth = getWidth();
        bitmapHeight = getHeight();
        rebuild();
    }

    private void rebuild() {
        if (disposed) return;
        positionOffset = selectedOffset();
        cancelPagination();
        resetTurn();
        document = null;
        currentBitmap = null;
        if (notes.isEmpty() || bodyWidth <= 0 || bodyHeight <= 0) { invalidate(); return; }
        message = getContext().getString(R.string.loading_library);
        int version = requestId;
        int width = bodyWidth;
        int height = bodyHeight;
        List<BookNote> source = new ArrayList<>(notes);
        TextPaint textPaint = new TextPaint(bodyPaint);
        pagination = paginator.submit(() -> {
            NoteReaderDocument result = NoteReaderDocument.create(getContext(), source, textPaint, width, height, dp(7));
            if (result == null) return;
            post(() -> {
                if (disposed || version != requestId) return;
                document = result;
                pageIndex = result.pageForOffset(positionOffset);
                currentBitmap = renderPage(pageIndex);
                updateAccessibility();
                invalidate();
            });
        });
        invalidate();
    }

    private void cancelPagination() {
        requestId++;
        if (pagination != null) pagination.cancel(true);
        pagination = null;
    }

    private Bitmap renderPage(int index) {
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paper);
        smallPaint.setColor(muted);
        smallPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(TextUtils.ellipsize(title, smallPaint, bodyWidth, TextUtils.TruncateAt.END).toString(),
                bodyLeft, getPaddingTop() + dp(28), smallPaint);
        paint.setColor(getContext().getColor(R.color.reader_rule));
        canvas.drawRect(bodyLeft, getPaddingTop() + dp(41), bodyLeft + bodyWidth, getPaddingTop() + dp(41) + dp(0.5f), paint);
        NoteReaderDocument.Page page = document.pages.get(index);
        document.drawPage(canvas, page, bodyLeft, bodyTop, bodyWidth, bodyHeight);
        float footer = getHeight() - getPaddingBottom() - dp(28);
        canvas.drawText(TextUtils.ellipsize(page.chapter, smallPaint, bodyWidth * 0.58f, TextUtils.TruncateAt.END).toString(),
                bodyLeft, footer, smallPaint);
        smallPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(getContext().getString(R.string.reader_page, index + 1, pageCount()), bodyLeft + bodyWidth, footer, smallPaint);
        smallPaint.setTextAlign(Paint.Align.LEFT);
        float lineY = getHeight() - getPaddingBottom() - dp(14);
        canvas.drawRect(bodyLeft, lineY, bodyLeft + bodyWidth, lineY + dp(1), paint);
        paint.setColor(muted);
        canvas.drawRect(bodyLeft, lineY, bodyLeft + bodyWidth * (index + 1f) / pageCount(), lineY + dp(1), paint);
        return bitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(paper);
        if (currentBitmap == null) {
            int width = Math.max(1, getWidth() - dp(64) - getPaddingLeft() - getPaddingRight());
            StaticLayout empty = StaticLayout.Builder.obtain(message, 0, message.length(), bodyPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(dp(7), 1).build();
            canvas.save();
            canvas.translate(getPaddingLeft() + dp(32), Math.max(getPaddingTop() + dp(64), (getHeight() - empty.getHeight()) / 2f));
            empty.draw(canvas);
            canvas.restore();
            return;
        }
        paint.setShader(null);
        paint.setAlpha(255);
        if (targetBitmap == null || progress <= 0) {
            canvas.drawBitmap(currentBitmap, 0, 0, paint);
            return;
        }
        // A previous page unfolds along the same geometry in reverse.
        Bitmap sheet = turnDirection > 0 ? currentBitmap : targetBitmap;
        Bitmap beneath = turnDirection > 0 ? targetBitmap : currentBitmap;
        drawCurl(canvas, sheet, beneath, turnDirection > 0 ? progress : 1 - progress);
    }

    private void drawCurl(Canvas canvas, Bitmap sheet, Bitmap beneath, float amount) {
        if (amount <= 0.001f) { canvas.drawBitmap(sheet, 0, 0, paint); return; }
        canvas.drawBitmap(beneath, 0, 0, paint);
        if (amount >= 0.999f) return;
        float width = getWidth();
        float height = getHeight();
        float cornerY = topCorner ? 0 : height;
        float tipX = width - 2 * width * amount;
        float tipY = cornerY + (topCorner ? bend : -bend) * (float) Math.sin(Math.PI * amount);
        float nx = width - tipX;
        float ny = cornerY - tipY;
        float length = (float) Math.hypot(nx, ny);
        nx /= length;
        ny /= length;
        float mx = (width + tipX) / 2;
        float my = (cornerY + tipY) / 2;
        float distance = nx * mx + ny * my;
        List<PointF> front = halfPage(width, height, nx, ny, distance, false);
        List<PointF> lifted = halfPage(width, height, nx, ny, distance, true);
        Path frontPath = polygon(front, 0);
        Path liftedPath = polygon(lifted, 0);
        int save = canvas.save();
        canvas.clipPath(frontPath);
        canvas.drawBitmap(sheet, 0, 0, paint);
        canvas.restoreToCount(save);

        // The shadow falls on the exposed next page along the crease.
        save = canvas.save();
        canvas.clipPath(liftedPath);
        paint.setShader(new LinearGradient(mx, my, mx + nx * dp(44), my + ny * dp(44),
                0x48000000, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        canvas.restoreToCount(save);

        List<PointF> reflected = new ArrayList<>();
        for (PointF point : lifted) {
            float offset = nx * point.x + ny * point.y - distance;
            reflected.add(new PointF(point.x - 2 * nx * offset, point.y - 2 * ny * offset));
        }
        Path back = polygon(reflected, dp(12) * (float) Math.sin(Math.PI * amount));
        save = canvas.save();
        canvas.translate(dp(3), dp(3));
        paint.setColor(0x26000000);
        canvas.drawPath(back, paint);
        canvas.restoreToCount(save);
        save = canvas.save();
        canvas.clipPath(back);
        paint.setColor(0xFFF1F3F6);
        canvas.drawPath(back, paint);
        Matrix reflection = new Matrix();
        reflection.setValues(new float[]{1 - 2 * nx * nx, -2 * nx * ny, 2 * distance * nx,
                -2 * nx * ny, 1 - 2 * ny * ny, 2 * distance * ny, 0, 0, 1});
        paint.setAlpha(38);
        canvas.drawBitmap(sheet, reflection, paint);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(mx - nx * dp(64), my - ny * dp(64), mx, my,
                new int[]{0x00687589, 0x22FFFFFF, 0x44687589}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        canvas.drawPath(back, paint);
        paint.setShader(null);
        canvas.restoreToCount(save);
    }

    private static List<PointF> halfPage(float width, float height, float nx, float ny, float distance, boolean lifted) {
        PointF[] corners = {new PointF(0, 0), new PointF(width, 0), new PointF(width, height), new PointF(0, height)};
        List<PointF> result = new ArrayList<>();
        PointF previous = corners[corners.length - 1];
        float sign = lifted ? 1 : -1;
        float previousDistance = sign * (nx * previous.x + ny * previous.y - distance);
        for (PointF current : corners) {
            float currentDistance = sign * (nx * current.x + ny * current.y - distance);
            if ((currentDistance >= 0) != (previousDistance >= 0)) {
                float ratio = previousDistance / (previousDistance - currentDistance);
                result.add(new PointF(previous.x + ratio * (current.x - previous.x), previous.y + ratio * (current.y - previous.y)));
            }
            if (currentDistance >= 0) result.add(current);
            previous = current;
            previousDistance = currentDistance;
        }
        return result;
    }

    private static Path polygon(List<PointF> points, float radius) {
        Path path = new Path();
        if (points.isEmpty()) return path;
        for (int i = 0; i < points.size(); i++) {
            PointF previous = points.get((i + points.size() - 1) % points.size());
            PointF current = points.get(i);
            PointF next = points.get((i + 1) % points.size());
            float before = Math.min(0.4f, radius / Math.max(1, (float) Math.hypot(previous.x - current.x, previous.y - current.y)));
            float after = Math.min(0.4f, radius / Math.max(1, (float) Math.hypot(next.x - current.x, next.y - current.y)));
            float x = current.x + (previous.x - current.x) * before;
            float y = current.y + (previous.y - current.y) * before;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            path.quadTo(current.x, current.y, current.x + (next.x - current.x) * after,
                    current.y + (next.y - current.y) * after);
        }
        path.close();
        return path;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (disposed || animator != null) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            gestureActive = true;
            dragging = false;
            moved = false;
            downX = event.getX();
            downY = event.getY();
            topCorner = downY < getHeight() / 2f;
            bend = Math.max(dp(32), Math.abs((topCorner ? 0 : getHeight()) - downY) * 0.35f);
            releaseVelocity();
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
            return true;
        }
        if (!gestureActive) return true;
        if (velocity != null) velocity.addMovement(event);
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) moved = true;
            if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) dragging = true;
            if (dragging) {
                if (preview(dx < 0 ? 1 : -1)) {
                    progress = Math.min(0.999f, Math.abs(dx) / Math.max(1, getWidth()));
                    bend = Math.min(getHeight() * 0.3f, Math.max(dp(32), Math.abs((topCorner ? 0 : getHeight()) - event.getY()) * 0.35f));
                    invalidate();
                }
            }
        } else if (action == MotionEvent.ACTION_UP) {
            gestureActive = false;
            if (dragging && targetBitmap != null) {
                velocity.computeCurrentVelocity(1000);
                float speed = velocity.getXVelocity();
                boolean commit = Math.abs(speed) > dp(650) && progress > 0.04f
                        ? speed * turnDirection < 0 : progress > 0.25f;
                settle(commit);
            } else if (!moved) {
                if (pageCount() == 0 || downX > getWidth() * 0.3f && downX < getWidth() * 0.7f) performClick();
                else turnPage(downX < getWidth() * 0.3f ? -1 : 1);
            } else if (dragging && pageCount() > 0) boundary(dx < 0 ? 1 : -1);
            releaseVelocity();
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            gestureActive = false;
            if (targetBitmap != null) settle(false);
            releaseVelocity();
        }
        return true;
    }

    private boolean preview(int direction) {
        if (pageCount() == 0 || pageIndex + direction < 0 || pageIndex + direction >= pageCount()) {
            turnDirection = 0;
            targetBitmap = null;
            progress = 0;
            invalidate();
            return false;
        }
        if (turnDirection != direction || targetBitmap == null) {
            turnDirection = direction;
            targetBitmap = renderPage(pageIndex + direction);
        }
        return true;
    }

    private boolean turnPage(int direction) {
        if (animator != null) return false;
        if (!preview(direction)) { if (pageCount() > 0) boundary(direction); return false; }
        topCorner = false;
        bend = getHeight() * 0.18f;
        progress = 0;
        settle(true);
        return true;
    }

    private void settle(boolean commit) {
        float end = commit ? 1 : 0;
        animator = ValueAnimator.ofFloat(progress, end);
        animator.setDuration(Math.max(140, (long) (340 * Math.abs(end - progress))));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> { progress = (float) animation.getAnimatedValue(); invalidate(); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (commit) {
                    pageIndex += turnDirection;
                    currentBitmap = targetBitmap;
                    positionOffset = selectedOffset();
                }
                animator = null;
                targetBitmap = null;
                turnDirection = 0;
                progress = 0;
                updateAccessibility();
                invalidate();
            }
        });
        animator.start();
    }

    private void resetTurn() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        releaseVelocity();
        targetBitmap = null;
        progress = 0;
        turnDirection = 0;
        dragging = false;
        gestureActive = false;
    }

    private void boundary(int direction) {
        Toast.makeText(getContext(), direction > 0 ? R.string.reader_last_page : R.string.reader_first_page, Toast.LENGTH_SHORT).show();
    }

    private void releaseVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }

    @Override public boolean performClick() { return super.performClick(); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_SPACE) return turnPage(1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_PAGE_UP) return turnPage(-1);
        return super.onKeyDown(keyCode, event);
    }

    private void updateAccessibility() {
        if (document == null) return;
        NoteReaderDocument.Page page = document.pages.get(pageIndex);
        setContentDescription(getContext().getString(R.string.reader_page_description, title, pageIndex + 1,
                pageCount(), document.layout.getText().subSequence(page.start, page.end).toString().trim()));
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(pageCount() > 1);
        if (pageIndex > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, getContext().getString(R.string.reader_previous)));
        if (pageIndex + 1 < pageCount()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, getContext().getString(R.string.reader_next)));
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return turnPage(1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return turnPage(-1);
        return super.performAccessibilityAction(action, arguments);
    }

    void dispose() {
        disposed = true;
        cancelPagination();
        resetTurn();
        paginator.shutdownNow();
        currentBitmap = null;
        document = null;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
