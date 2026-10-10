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
            ((ImageView) activity.findViewById(icons[i])).setImageTintList(ColorStateList.valueOf(color));
            ((TextView) activity.findViewById(labels[i])).setTextColor(color);
            activity.findViewById(items[i]).setSelected(i == selected);
            activity.findViewById(items[i]).setOnClickListener(view -> {
                if ((destination == 1 || destination == 2) && activity instanceof BookshelfActivity) {
                    ((BookshelfActivity) activity).openFavorites(destination == 2);
                    return;
                }
                if (destination == 0 && activity instanceof MainActivity
                        || destination == 3 && activity instanceof ConnectionActivity) return;
                Class<?> target = destination == 0 ? MainActivity.class : destination == 3
                        ? ConnectionActivity.class : BookshelfActivity.class;
                activity.startActivity(new Intent(activity, target).putExtra("show_favorites", destination == 2)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            });
        }
    }
}
