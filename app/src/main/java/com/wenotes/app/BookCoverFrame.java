package com.wenotes.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Keeps covers in book proportions as grid columns resize. */
public final class BookCoverFrame extends FrameLayout {
    public BookCoverFrame(Context context, AttributeSet attributes) { super(context, attributes); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int height = Math.round(MeasureSpec.getSize(widthSpec) * 1.42f);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
