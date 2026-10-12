package com.wenotes.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.widget.ImageView;
import android.widget.TextView;

final class AppNavigation {
    static void bind(Activity activity, int selected) {
        int[] items = {R.id.nav_home, R.id.nav_shelf, R.id.nav_favorites, R.id.nav_connection};
        int[] icons = {R.id.nav_home_icon, R.id.nav_shelf_icon, R.id.nav_favorites_icon, R.id.nav_connection_icon};
        int[] labels = {R.id.nav_home_label, R.id.nav_shelf_label, R.id.nav_favorites_label, R.id.nav_connection_label};
        for (int i = 0; i < items.length; i++) {
            final int destination = i;
            int color = activity.getColor(i == selected ? R.color.primary : R.color.text_secondary);
            ImageView icon = activity.findViewById(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(color));
            icon.setSelected(i == selected);
            TextView label = activity.findViewById(labels[i]);
            label.setTextColor(color);
            AppStyle.select(label, i == selected);
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
}
