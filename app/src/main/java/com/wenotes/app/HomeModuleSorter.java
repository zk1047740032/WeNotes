package com.wenotes.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Keeps home-card sorting separate from quote and connection state. */
final class HomeModuleSorter implements View.OnDragListener {
    private static final String ORDER_KEY = "module_order";
    private final Activity activity;
    private final ScrollView scroll;
    private final LinearLayout modules;
    private final SharedPreferences preferences;
    private final List<View> defaultOrder;
    private final String[] moduleKeys = {"shelf", "connection", "quote"};
    private final int[] titleIds = {
            R.string.my_shelf, R.string.connection_title, R.string.current_quote_title
    };
    private final int[] screenLocation = new int[2];
    private final int[] moduleLocation = new int[2];
    private View dragged;
    private List<View> previousOrder;
    private float dragY;
    private boolean inside;
    private boolean dropped;
    private boolean scrollPosted;

    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            scrollPosted = false;
            if (dragged == null || !inside) return;
            int edge = dp(64);
            float top = scroll.getPaddingTop() + edge;
            float bottom = scroll.getHeight() - scroll.getPaddingBottom() - edge;
            float direction = dragY < top ? (dragY - top) / edge
                    : dragY > bottom ? (dragY - bottom) / edge : 0f;
            int distance = Math.round(dp(12) * Math.max(-1f, Math.min(1f, direction)));
            if (distance == 0) return;
            int before = scroll.getScrollY();
            scroll.scrollBy(0, distance);
            if (scroll.getScrollY() != before) {
                updatePosition();
                postScroll();
            }
        }
    };

    HomeModuleSorter(Activity activity) {
        this.activity = activity;
        scroll = activity.findViewById(R.id.home_scroll);
        modules = activity.findViewById(R.id.home_modules);
        preferences = activity.getSharedPreferences("home_layout", Context.MODE_PRIVATE);
        defaultOrder = Arrays.asList(activity.findViewById(R.id.module_shelf),
                activity.findViewById(R.id.module_connection),
                activity.findViewById(R.id.module_quote));
        restoreOrder();
        scroll.setOnDragListener(this);
        for (View module : defaultOrder) {
            module.setOnLongClickListener(view -> beginDrag(module));
            module.setFocusable(true);
            bindButtons(module, module);
            addAccessibilityActions(module);
        }
    }

    private void restoreOrder() {
        List<View> order = new ArrayList<>();
        for (String key : preferences.getString(ORDER_KEY, "").split(",")) {
            int index = Arrays.asList(moduleKeys).indexOf(key);
            if (index >= 0 && !order.contains(defaultOrder.get(index))) {
                order.add(defaultOrder.get(index));
            }
        }
        // Missing or obsolete entries cannot hide a module after an upgrade.
        for (View module : defaultOrder) if (!order.contains(module)) order.add(module);
        applyOrder(order);
    }

    private void applyOrder(List<View> order) {
        modules.removeAllViews();
        for (View module : order) modules.addView(module);
    }

    private List<View> currentOrder() {
        List<View> order = new ArrayList<>();
        for (int i = 0; i < modules.getChildCount(); i++) order.add(modules.getChildAt(i));
        return order;
    }

    private void saveOrder() {
        List<String> keys = new ArrayList<>();
        for (View module : currentOrder()) keys.add(moduleKeys[defaultOrder.indexOf(module)]);
        preferences.edit().putString(ORDER_KEY, String.join(",", keys)).apply();
    }

    private void bindButtons(View view, View module) {
        if (view != module && view.isClickable()) {
            view.setOnLongClickListener(button -> beginDrag(module));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindButtons(group.getChildAt(i), module);
            }
        }
    }

    private boolean beginDrag(View module) {
        if (dragged != null) return false;
        dragged = module;
        previousOrder = currentOrder();
        dropped = false;
        if (!module.startDragAndDrop(null, new ModuleShadow(module), this, 0)) {
            finishDrag(false);
            return false;
        }
        module.setAlpha(0.35f);
        module.announceForAccessibility(activity.getString(
                R.string.module_drag_started, title(module)));
        return true;
    }

    @Override
    public boolean onDrag(View view, DragEvent event) {
        if (event.getLocalState() != this || dragged == null) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                inside = true;
                dragY = event.getY();
                updatePosition();
                postScroll();
                return true;
            case DragEvent.ACTION_DROP:
                dragY = event.getY();
                updatePosition();
                stopScroll();
                dropped = true;
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                inside = false;
                stopScroll();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                finishDrag(dropped && event.getResult());
                return true;
            default:
                return true;
        }
    }

    private void updatePosition() {
        scroll.getLocationOnScreen(screenLocation);
        modules.getLocationOnScreen(moduleLocation);
        float localY = dragY + screenLocation[1] - moduleLocation[1];
        int top = modules.getPaddingTop();
        int destination = 0;
        // Use logical bounds so another pointer event during an animation is stable.
        for (View module : currentOrder()) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) module.getLayoutParams();
            top += params.topMargin;
            if (module != dragged && localY > top + module.getHeight() / 2f) destination++;
            top += module.getHeight() + params.bottomMargin;
        }
        moveModule(dragged, destination);
    }

    private void moveModule(View module, int destination) {
        if (modules.indexOfChild(module) == destination) return;
        TransitionManager.beginDelayedTransition(modules, new ChangeBounds().setDuration(160));
        modules.removeView(module);
        modules.addView(module, destination);
    }

    private void postScroll() {
        if (!scrollPosted) {
            scrollPosted = true;
            scroll.postOnAnimation(autoScroll);
        }
    }

    private void stopScroll() {
        scroll.removeCallbacks(autoScroll);
        scrollPosted = false;
    }

    private void finishDrag(boolean accepted) {
        stopScroll();
        inside = false;
        if (dragged == null) return;
        View module = dragged;
        module.setAlpha(1f);
        if (accepted) {
            saveOrder();
            announcePosition(module);
        } else {
            TransitionManager.endTransitions(modules);
            applyOrder(previousOrder);
        }
        dragged = null;
        previousOrder = null;
        dropped = false;
    }

    private void addAccessibilityActions(View module) {
        module.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(title(module));
                int position = modules.indexOfChild(module);
                if (position > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.move_module_up, activity.getString(R.string.module_move_up)));
                if (position < modules.getChildCount() - 1) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.move_module_down, activity.getString(R.string.module_move_down)));
                }
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == R.id.move_module_up || action == R.id.move_module_down) {
                    if (dragged != null) return false;
                    int position = modules.indexOfChild(module);
                    int destination = position + (action == R.id.move_module_up ? -1 : 1);
                    if (destination < 0 || destination >= modules.getChildCount()) return false;
                    moveModule(module, destination);
                    saveOrder();
                    announcePosition(module);
                    return true;
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
    }

    private String title(View module) {
        return activity.getString(titleIds[defaultOrder.indexOf(module)]);
    }

    private void announcePosition(View module) {
        module.announceForAccessibility(activity.getString(R.string.module_moved,
                title(module), modules.indexOfChild(module) + 1));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    void dispose() {
        finishDrag(false);
        scroll.setOnDragListener(null);
        TransitionManager.endTransitions(modules);
    }

    /** A compact preview stays readable even when the quote fills several screens. */
    private final class ModuleShadow extends View.DragShadowBuilder {
        private final LinearLayout preview;

        ModuleShadow(View module) {
            super(module);
            preview = new LinearLayout(activity);
            preview.setOrientation(LinearLayout.VERTICAL);
            preview.setPadding(dp(18), dp(12), dp(18), dp(12));
            GradientDrawable background = new GradientDrawable();
            background.setColor(activity.getColor(R.color.surface));
            background.setCornerRadius(dp(16));
            background.setStroke(dp(2), activity.getColor(R.color.primary));
            preview.setBackground(background);
            TextView label = new TextView(activity);
            label.setText(title(module));
            label.setTextSize(18);
            label.setTextColor(activity.getColor(R.color.primary_dark));
            label.setTypeface(null, Typeface.BOLD);
            preview.addView(label);
            TextView hint = new TextView(activity);
            hint.setText(R.string.module_drag_shadow);
            hint.setTextSize(12);
            hint.setTextColor(activity.getColor(R.color.text_secondary));
            preview.addView(hint);
            preview.measure(View.MeasureSpec.makeMeasureSpec(module.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            preview.layout(0, 0, preview.getMeasuredWidth(), preview.getMeasuredHeight());
        }

        @Override
        public void onProvideShadowMetrics(Point size, Point touch) {
            size.set(preview.getWidth(), preview.getHeight());
            touch.set(size.x / 2, size.y / 2);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            preview.draw(canvas);
        }
    }
}
