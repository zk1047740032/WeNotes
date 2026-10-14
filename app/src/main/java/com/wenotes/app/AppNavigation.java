package com.wenotes.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

final class AppNavigation {
    static void bind(Activity activity, int selected) {
        applyWindowInsets(activity);
        int[] items = {R.id.nav_home, R.id.nav_shelf, R.id.nav_favorites, R.id.nav_connection};
        int[] icons = {R.id.nav_home_icon, R.id.nav_shelf_icon, R.id.nav_favorites_icon, R.id.nav_connection_icon};
        for (int i = 0; i < items.length; i++) {
            final int destination = i;
            int color = activity.getColor(i == selected ? R.color.primary : R.color.text_secondary);
            ImageView icon = activity.findViewById(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(color));
            icon.setSelected(i == selected);
            activity.findViewById(items[i]).setSelected(i == selected);
            activity.findViewById(items[i]).setOnClickListener(view -> {
                if (destination == 1 && activity instanceof BookshelfActivity) {
                    ((BookshelfActivity) activity).openRecent();
                    return;
                }
                if (destination == 0 && activity instanceof MainActivity
                        || destination == 2 && activity instanceof BookNotesActivity
                        && ((BookNotesActivity) activity).isGlobalFavorites()
                        || destination == 3 && activity instanceof ConnectionActivity) return;
                Class<?> target = destination == 0 ? MainActivity.class : destination == 3
                        ? ConnectionActivity.class : destination == 2 ? BookNotesActivity.class : BookshelfActivity.class;
                Intent intent = new Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (destination == 2) {
                    // A book's notes may already be on the stack; recreate it in global favorites mode.
                    intent.putExtra("favorite_notes", true);
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                }
                activity.startActivity(intent);
            });
        }
    }

    private static void applyWindowInsets(Activity activity) {
        View navigation = activity.findViewById(R.id.bottom_navigation);
        View root = (View) navigation.getParent();
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        root.setFitsSystemWindows(false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safeArea = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(safeArea.left, safeArea.top, safeArea.right,
                    keyboard.bottom);
            navigation.setPadding(0, 0, 0, Math.max(0, safeArea.bottom - keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
