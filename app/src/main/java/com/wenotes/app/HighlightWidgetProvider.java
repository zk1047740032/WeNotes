package com.wenotes.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

public final class HighlightWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_NEXT = "com.wenotes.app.NEXT";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        HighlightStore store = new HighlightStore(context);
        List<Highlight> highlights = store.read();
        store.rotateIfDue(highlights.size());
        for (int widgetId : widgetIds) render(context, manager, widgetId, store, highlights);
        if (new SecretStore(context).hasKey()) {
            SyncWorker.schedule(context);
            if (highlights.isEmpty() && (store.lastSync() == 0L
                    || System.currentTimeMillis() - store.lastSync() > 6L * 60L * 60L * 1000L)) {
                SyncWorker.syncNow(context);
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_NEXT.equals(intent.getAction())) {
            HighlightStore store = new HighlightStore(context);
            store.next(store.read().size());
            renderAll(context);
        }
    }

    static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, HighlightWidgetProvider.class));
        HighlightStore store = new HighlightStore(context);
        List<Highlight> highlights = store.read();
        for (int id : ids) render(context, manager, id, store, highlights);
    }

    private static void render(Context context, AppWidgetManager manager, int id,
                               HighlightStore store, List<Highlight> highlights) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_highlight);
        if (highlights.isEmpty()) {
            boolean hasKey = new SecretStore(context).hasKey();
            String status = store.status();
            String appName = context.getString(R.string.app_name);
            views.setTextViewText(R.id.widget_quote, !hasKey
                    ? "打开 " + appName + "，连接微信读书。"
                    : status.startsWith("同步完成") ? "还没有可显示的划线。"
                    : status.startsWith("同步失败") ? "同步失败，打开 " + appName + " 查看原因。"
                    : "正在同步你的划线…");
            views.setTextViewText(R.id.widget_book, hasKey ? status : "点击组件开始设置");
            views.setTextViewText(R.id.widget_count, "");
            views.setViewVisibility(R.id.widget_thought, View.GONE);
        } else {
            int index = store.index(highlights.size());
            Highlight highlight = highlights.get(index);
            views.setTextViewText(R.id.widget_quote, "“" + highlight.text + "”");
            views.setTextViewText(R.id.widget_book, "《" + highlight.title + "》"
                    + (highlight.author.isEmpty() ? "" : " · " + highlight.author));
            views.setTextViewText(R.id.widget_count, (index + 1) + " / " + highlights.size());
            if (highlight.thought.isEmpty()) {
                views.setViewVisibility(R.id.widget_thought, View.GONE);
            } else {
                views.setTextViewText(R.id.widget_thought, "我的想法：" + highlight.thought);
                views.setViewVisibility(R.id.widget_thought, View.VISIBLE);
            }
        }
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openIntent);
        Intent next = new Intent(context, HighlightWidgetProvider.class).setAction(ACTION_NEXT);
        PendingIntent nextIntent = PendingIntent.getBroadcast(context, 1, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_next, nextIntent);
        manager.updateAppWidget(id, views);
    }
}
