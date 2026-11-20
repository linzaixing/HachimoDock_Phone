package com.example.hachimophone;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * 外屏小组件：把当前 Agent 状态挂到外屏桌面上，不必开整页 Activity。
 *
 * 刷新由 {@link BridgeService} 在收到 state 包时调用 {@link #refresh(Context)} 主动触发，
 * 因此不需要 updatePeriodMillis 轮询（外屏常亮，轮询反而费电）。
 */
public final class CoverWidgetProvider extends AppWidgetProvider {

    /** 供 Service 调用：把最新状态同步到所有已添加的小组件实例 */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, CoverWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            for (int id : ids) m.updateAppWidget(id, build(c));
        } catch (Exception ignored) {
            // 小组件不是关键路径，失败不影响主流程
        }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c));
    }

    private static RemoteViews build(Context c) {
        AppState.State s = AppState.get().snapshot();
        String state = s.state == null || s.state.isEmpty() ? "idle" : s.state;

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_cover);
        v.setTextViewText(R.id.widget_chip, chipText(s, state));
        v.setTextColor(R.id.widget_chip, accent(state));
        v.setImageViewResource(R.id.widget_pet, photo(state));

        String label = s.label == null || s.label.isEmpty() ? defaultLabel(state) : s.label;
        if (!s.connected) label = "PC Bridge 未连接";
        v.setTextViewText(R.id.widget_label, label);

        // 点击进入外屏展示页（从外屏小组件启动，系统会在外屏打开）
        Intent i = new Intent(c, DeviceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_pet, pi);
        v.setOnClickPendingIntent(R.id.widget_chip, pi);
        return v;
    }

    private static String chipText(AppState.State s, String state) {
        // 小组件地方小：agent + 状态，多 agent 时前面加 +N
        String agent = s.agent == null || s.agent.trim().isEmpty()
                ? "HACHIMO" : s.agent.trim().toUpperCase(java.util.Locale.ROOT);
        int others = AppState.get().others().size();
        return (others > 0 ? "+" + others + " " : "") + agent + " · " + stateName(state);
    }

    private static String stateName(String state) {
        switch (state) {
            case "thinking": return "思考中";
            case "tool": return "执行中";
            case "waiting": return "等待确认";
            case "done": return "已完成";
            case "error": return "出错";
            default: return "待机";
        }
    }

    /** 宠物缩略图（来自 HachimoDock 的 terrier-clips/thumbs，非商业素材） */
    private static int photo(String state) {
        switch (state) {
            case "thinking": return R.drawable.terrier_thinking;
            case "tool": return R.drawable.terrier_tool;
            case "waiting": return R.drawable.terrier_waiting;
            case "done": return R.drawable.terrier_done;
            case "error": return R.drawable.terrier_error;
            default: return R.drawable.terrier_idle;
        }
    }

    private static int accent(String state) {
        switch (state) {
            case "thinking": return 0xFF67E8F9;
            case "tool": return 0xFFFBBF24;
            case "waiting": return 0xFFF472B6;
            case "done": return 0xFF4ADE80;
            case "error": return 0xFFF87171;
            default: return 0xFF94A3B8;
        }
    }

    private static String defaultLabel(String state) {
        switch (state) {
            case "thinking": return "正在思考…";
            case "tool": return "正在执行工具…";
            case "waiting": return "需要你确认";
            case "done": return "任务完成";
            case "error": return "执行出错";
            default: return "待机中";
        }
    }
}
