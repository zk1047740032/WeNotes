package com.wenotes.app;

import android.graphics.Typeface;
import android.widget.TextView;

/** Shared selected typography; backgrounds and colors follow resource state lists. */
final class AppStyle {
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private AppStyle() { }

    static void select(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTypeface(selected ? MEDIUM : REGULAR);
    }
}
