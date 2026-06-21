package com.answer.revealer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String MODULE_PACKAGE = "com.answer.revealer";

    // ContentProvider URIs
    private static final Uri URI_QUERY = Uri.parse("content://com.answer.revealer.stats/query");
    private static final Uri URI_REQUESTS = Uri.parse("content://com.answer.revealer.stats/requests");
    private static final Uri URI_CLEAR = Uri.parse("content://com.answer.revealer.stats/clear");
    private static final Uri URI_UPDATE = Uri.parse("content://com.answer.revealer.stats/update");

    // 配置项 key
    private static final String KEY_AUTO_SELECT = "auto_select_enabled";

    // 颜色配置（Material Design 风格）
    private static final int COLOR_PRIMARY = 0xFF2196F3;
    private static final int COLOR_PRIMARY_DARK = 0xFF1976D2;
    private static final int COLOR_ACCENT = 0xFF4CAF50;
    private static final int COLOR_WARNING = 0xFFFF9800;
    private static final int COLOR_DANGER = 0xFFF44336;
    private static final int COLOR_INFO = 0xFF00BCD4;
    private static final int COLOR_TEXT_PRIMARY = 0xFF212121;
    private static final int COLOR_TEXT_SECONDARY = 0xFF757575;
    private static final int COLOR_BG = 0xFFF5F7FA;
    private static final int COLOR_CARD = 0xFFFFFFFF;

    // 分页 & 截断
    private static final int PAGE_SIZE = 10;
    private static final int URL_PREVIEW_LENGTH = 80;

    private Handler mHandler;

    // 当前分页
    private int mCurrentPage = 0;

    // 数据缓存
    private StatsData mData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
        // 初次进入刷一次
        refreshStatsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatsAsync();
    }

    // ============ 异步刷新 ============
    private void refreshStatsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StatsData data = loadStatsData();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mData = data;
                        renderUI();
                    }
                });
            }
        }).start();
    }

    // ============ 数据模型 ============
    private static class StatsData {
        boolean moduleActive = false;
        int requestCount = -1;
        int targetHitCount = -1;
        long lastHookTime = -1;
        String detectedClients = "";
        List<RequestItem> requests = new ArrayList<>();
        boolean autoSelectEnabled = false;
        boolean autoSelectLoaded = false;
    }

    private static class RequestItem {
        String type;
        String url;
        long time;
    }

    private StatsData loadStatsData() {
        StatsData data = new StatsData();

        // 1. 优先从 ContentProvider 读（目标应用 Hook 写入的）
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(URI_QUERY, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String key = cursor.getString(cursor.getColumnIndex("key"));
                    long value = cursor.getLong(cursor.getColumnIndex("value"));
                    String valueStr = null;
                    try {
                        valueStr = cursor.getString(cursor.getColumnIndex("value_str"));
                    } catch (Throwable ignored) {}
                    if ("request_count".equals(key))
                        data.requestCount = (int) value;
                    else if ("target_hit_count".equals(key))
                        data.targetHitCount = (int) value;
                    else if ("last_hook_time".equals(key))
                        data.lastHookTime = value;
                    else if ("detected_clients".equals(key))
                        data.detectedClients = valueStr != null ? valueStr : "";
                    else if ("auto_select_enabled".equals(key)) {
                        data.autoSelectEnabled = value > 0 || "1".equals(valueStr) || "true".equalsIgnoreCase(valueStr);
                        data.autoSelectLoaded = true;
                    }
                } while (cursor.moveToNext());
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
        }

        // 2. 回退：从目标应用的 SharedPreferences 读
        if (data.requestCount <= 0 || data.targetHitCount <= 0) {
            try {
                Context targetCtx = createPackageContext(TARGET_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                SharedPreferences sp = targetCtx.getSharedPreferences("answer_revealer_status",
                        Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE);
                if (data.requestCount <= 0) {
                    int v = sp.getInt("request_count", 0);
                    if (v > 0) data.requestCount = v;
                }
                if (data.targetHitCount <= 0) {
                    int v = sp.getInt("target_hit_count", 0);
                    if (v > 0) data.targetHitCount = v;
                }
                if (data.lastHookTime <= 0)
                    data.lastHookTime = sp.getLong("last_hook_time", -1);
                if (data.detectedClients == null || data.detectedClients.isEmpty())
                    data.detectedClients = sp.getString("detected_clients", "");
            } catch (Throwable ignored) {}
        }

        // 3. 请求记录 - 从 ContentProvider 读
        try {
            Cursor rc = getContentResolver().query(URI_REQUESTS, null, null, null, null);
            if (rc != null && rc.moveToFirst()) {
                do {
                    try {
                        RequestItem item = new RequestItem();
                        item.type = rc.getString(rc.getColumnIndex("type"));
                        item.url = rc.getString(rc.getColumnIndex("url"));
                        item.time = rc.getLong(rc.getColumnIndex("time"));
                        data.requests.add(item);
                    } catch (Throwable ignored) {}
                } while (rc.moveToNext());
                rc.close();
            }
        } catch (Throwable t) {
            // 回退：从目标应用 SP 读 req_ 前缀的 key
            try {
                Context targetCtx = createPackageContext(TARGET_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                SharedPreferences sp = targetCtx.getSharedPreferences("answer_revealer_status",
                        Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE);
                Map<String, ?> all = sp.getAll();
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    if (e.getKey() != null && e.getKey().startsWith("req_") && e.getValue() instanceof String) {
                        String val = (String) e.getValue();
                        int bar = val.indexOf("|");
                        RequestItem item = new RequestItem();
                        item.type = bar > 0 ? val.substring(0, bar) : "unknown";
                        item.url = bar > 0 && bar < val.length() - 1 ? val.substring(bar + 1) : val;
                        try {
                            int last = e.getKey().lastIndexOf("_");
                            if (last > 0) item.time = Long.parseLong(e.getKey().substring(last + 1));
                        } catch (Throwable ignored) {}
                        data.requests.add(item);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 排序：按时间倒序（新的在前）
        Collections.sort(data.requests, new Comparator<RequestItem>() {
            @Override
            public int compare(RequestItem a, RequestItem b) {
                return Long.compare(b.time, a.time);
            }
        });

        // 4. 模块自己的 SP 兜底
        try {
            SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
            if (data.requestCount < 0)
                data.requestCount = selfSp.getInt("request_count", -1);
            if (data.targetHitCount < 0)
                data.targetHitCount = selfSp.getInt("target_hit_count", -1);
            if (data.lastHookTime < 0)
                data.lastHookTime = selfSp.getLong("last_hook_time", -1);
            if (!data.autoSelectLoaded)
                data.autoSelectEnabled = selfSp.getBoolean("auto_select_enabled", false);
        } catch (Throwable ignored) {}

        // 5. 通过 Xposed Hook 判断是否激活（使用 isModuleActive() 的返回值作为最终判定）
        try {
            data.moduleActive = isModuleActive();
        } catch (Throwable ignored) {}

        return data;
    }

    // ============ 主渲染 ============
    private void renderUI() {
        int pad = dp(14);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.setPadding(0, dp(4), 0, dp(20));
        scrollView.setId(android.R.id.list);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(12), pad, pad);

        // 顶部栏（含应用图标+名称+目标应用信息）
        addHeader(root);

        // 模块状态（激活/未激活 + 自动答题亮点）
        addStatusCard(root, mData);

        // 快捷操作（含美观的自动答题开关 + 启动/刷新/清空按钮）
        addActionCard(root, mData);

        // 统计数据
        addStatsCard(root, mData);

        // HTTP 客户端列表
        addHttpClientsCard(root, mData);

        // 最近请求
        addRecentRequestsCard(root, mData);

        // 工作原理
        addInfoCard(root);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    // ============ 顶部栏（含图标、标题、目标应用信息） ============
    private void addHeader(LinearLayout root) {
        // 外层卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(COLOR_CARD);
        cardGd.setCornerRadius(dp(16));
        cardGd.setStroke(dp(1), 0xFFE0E4EC);
        card.setBackground(cardGd);

        // 顶部渐变色条（蓝色 → 绿色，象征科技与通过）
        View topStrip = new View(this);
        GradientDrawable stripGd = new GradientDrawable();
        stripGd.setColors(new int[]{0xFF2196F3, 0xFF4CAF50});
        stripGd.setCornerRadii(new float[]{dp(16), dp(16), 0, 0, 0, 0, dp(16), dp(16)});
        topStrip.setBackground(stripGd);
        LinearLayout.LayoutParams topStripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        card.addView(topStrip, topStripLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        // 左侧圆形图标（蓝绿渐变 + 白色勾选）
        LinearLayout iconWrapper = new LinearLayout(this);
        iconWrapper.setGravity(Gravity.CENTER);
        GradientDrawable iconGd = new GradientDrawable();
        iconGd.setShape(GradientDrawable.OVAL);
        iconGd.setColors(new int[]{0xFF2196F3, 0xFF4CAF50});
        iconWrapper.setBackground(iconGd);
        LinearLayout.LayoutParams iconWp = new LinearLayout.LayoutParams(dp(58), dp(58));
        iconWrapper.setLayoutParams(iconWp);

        TextView iconTv = new TextView(this);
        iconTv.setText("✓");
        iconTv.setTextSize(26);
        iconTv.setTextColor(0xFFFFFFFF);
        iconTv.setTypeface(null, android.graphics.Typeface.BOLD);
        iconTv.setGravity(Gravity.CENTER);
        iconWrapper.addView(iconTv);
        content.addView(iconWrapper);

        // 右侧文字信息区
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams taLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        taLp.leftMargin = dp(14);
        textArea.setLayoutParams(taLp);

        // 主标题
        TextView title = new TextView(this);
        title.setText("答案显示模块");
        title.setTextSize(20);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        textArea.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("LSPosed Hook 管理工具");
        subtitle.setTextSize(12);
        subtitle.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp2.topMargin = dp(2);
        subtitle.setLayoutParams(sp2);
        textArea.addView(subtitle);

        // 分隔线
        View divider = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(0xFFE8EAED);
        divider.setBackground(dd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10);
        dlp.bottomMargin = dp(10);
        textArea.addView(divider, dlp);

        // 目标应用信息行（横向排列）
        LinearLayout targetRow = new LinearLayout(this);
        targetRow.setOrientation(LinearLayout.HORIZONTAL);
        targetRow.setGravity(Gravity.CENTER_VERTICAL);
        textArea.addView(targetRow);

        // 🎯 图标
        TextView targetLabel = new TextView(this);
        targetLabel.setText("🎯");
        targetLabel.setTextSize(14);
        targetRow.addView(targetLabel);

        // 目标应用标题
        TextView targetTitle = new TextView(this);
        targetTitle.setText("目标应用");
        targetTitle.setTextSize(12);
        targetTitle.setTextColor(COLOR_TEXT_SECONDARY);
        targetTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttLp.leftMargin = dp(6);
        targetTitle.setLayoutParams(ttLp);
        targetRow.addView(targetTitle);

        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        TextView targetStatus = new TextView(this);
        targetStatus.setText(installed ? "已安装" : "未安装");
        targetStatus.setTextSize(11);
        targetStatus.setTextColor(0xFFFFFFFF);
        targetStatus.setGravity(Gravity.CENTER);
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(installed ? COLOR_ACCENT : COLOR_DANGER);
        statusBg.setCornerRadius(dp(100));
        targetStatus.setBackground(statusBg);
        targetStatus.setPadding(dp(10), dp(3), dp(10), dp(3));
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tslp.leftMargin = dp(8);
        tslp.rightMargin = dp(8);
        targetStatus.setLayoutParams(tslp);
        targetRow.addView(targetStatus);

        // 包名
        TextView pkgTv = new TextView(this);
        pkgTv.setText(TARGET_PACKAGE);
        pkgTv.setTextSize(11);
        pkgTv.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pkgTv.setLayoutParams(pkgLp);
        targetRow.addView(pkgTv);

        // 版本信息（第二行）
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (pi != null && pi.versionName != null) {
                LinearLayout versionRow = new LinearLayout(this);
                versionRow.setOrientation(LinearLayout.HORIZONTAL);
                versionRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams vrLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vrLp.topMargin = dp(6);
                versionRow.setLayoutParams(vrLp);

                TextView versionIcon = new TextView(this);
                versionIcon.setText("📦");
                versionIcon.setTextSize(12);
                versionRow.addView(versionIcon);

                TextView versionLabel = new TextView(this);
                versionLabel.setText("版本");
                versionLabel.setTextSize(11);
                versionLabel.setTextColor(COLOR_TEXT_SECONDARY);
                versionLabel.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams vlLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vlLp.leftMargin = dp(6);
                versionLabel.setLayoutParams(vlLp);
                versionRow.addView(versionLabel);

                TextView versionValue = new TextView(this);
                versionValue.setText("v" + pi.versionName);
                versionValue.setTextSize(11);
                versionValue.setTextColor(COLOR_PRIMARY);
                versionValue.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams vvLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vvLp.leftMargin = dp(6);
                versionValue.setLayoutParams(vvLp);
                versionRow.addView(versionValue);

                textArea.addView(versionRow);
            }
        } catch (Throwable ignored) {}

        content.addView(textArea);
        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 模块状态卡（带亮点提示） ============
    private void addStatusCard(LinearLayout root, StatsData data) {
        boolean active = data != null && data.moduleActive;
        boolean autoOn = data != null && data.autoSelectEnabled;
        int color = active ? COLOR_ACCENT : COLOR_DANGER;

        // 外层卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(COLOR_CARD);
        cardGd.setCornerRadius(dp(16));
        cardGd.setStroke(dp(1), 0xFFE0E4EC);
        card.setBackground(cardGd);

        // 顶部色条
        View topStrip = new View(this);
        GradientDrawable stripGd = new GradientDrawable();
        stripGd.setColor(color);
        stripGd.setCornerRadii(new float[]{dp(16), dp(16), 0, 0, 0, 0, dp(16), dp(16)});
        topStrip.setBackground(stripGd);
        LinearLayout.LayoutParams topStripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        card.addView(topStrip, topStripLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(16));

        // 状态标题行（图标 + 文字 + 状态标签）
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        // 状态图标（圆形渐变）
        LinearLayout statusIcon = new LinearLayout(this);
        statusIcon.setGravity(Gravity.CENTER);
        GradientDrawable iconGd = new GradientDrawable();
        iconGd.setShape(GradientDrawable.OVAL);
        if (active) {
            iconGd.setColors(new int[]{0xFF4CAF50, 0xFF2E7D32});
        } else {
            iconGd.setColors(new int[]{0xFFF44336, 0xFFB71C1C});
        }
        statusIcon.setBackground(iconGd);
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(dp(44), dp(44));
        statusIcon.setLayoutParams(sip);

        TextView iconTv = new TextView(this);
        iconTv.setText(active ? "✓" : "!");
        iconTv.setTextSize(20);
        iconTv.setTextColor(0xFFFFFFFF);
        iconTv.setTypeface(null, android.graphics.Typeface.BOLD);
        iconTv.setGravity(Gravity.CENTER);
        statusIcon.addView(iconTv);
        headerRow.addView(statusIcon);

        // 文字区域
        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        titleArea.setLayoutParams(tlp);

        TextView titleTv = new TextView(this);
        titleTv.setText(active ? "模块已激活" : "模块未激活");
        titleTv.setTextSize(16);
        titleTv.setTextColor(color);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleArea.addView(titleTv);

        TextView descTv = new TextView(this);
        descTv.setText(active ? "Hook 已在目标应用中生效" : "请在 LSPosed 管理器中启用本模块");
        descTv.setTextSize(12);
        descTv.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams descLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp2.topMargin = dp(2);
        descTv.setLayoutParams(descLp2);
        titleArea.addView(descTv);
        headerRow.addView(titleArea);

        // 状态标签
        TextView statusBadge = new TextView(this);
        statusBadge.setText(active ? "ACTIVE" : "INACTIVE");
        statusBadge.setTextSize(10);
        statusBadge.setTextColor(0xFFFFFFFF);
        statusBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeGd = new GradientDrawable();
        if (active) {
            badgeGd.setColors(new int[]{0xFF4CAF50, 0xFF2E7D32});
        } else {
            badgeGd.setColors(new int[]{0xFFF44336, 0xFFB71C1C});
        }
        badgeGd.setCornerRadius(dp(100));
        statusBadge.setBackground(badgeGd);
        statusBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        headerRow.addView(statusBadge);

        content.addView(headerRow);

        // 分隔线
        View divider = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(0xFFEEEEEE);
        divider.setBackground(dd);
        LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp2.topMargin = dp(12);
        divLp2.bottomMargin = dp(12);
        content.addView(divider, divLp2);

        // 功能亮点标题
        LinearLayout featureHeader = new LinearLayout(this);
        featureHeader.setOrientation(LinearLayout.HORIZONTAL);
        featureHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView featureIcon = new TextView(this);
        featureIcon.setText("✨");
        featureIcon.setTextSize(14);
        featureHeader.addView(featureIcon);

        TextView featureTitle = new TextView(this);
        featureTitle.setText(" 功能亮点");
        featureTitle.setTextSize(13);
        featureTitle.setTextColor(COLOR_TEXT_PRIMARY);
        featureTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        featureHeader.addView(featureTitle);

        // 自动答题状态小标签
        TextView autoStatusBadge = new TextView(this);
        autoStatusBadge.setText(autoOn ? "自动答题 · ON" : "自动答题 · OFF");
        autoStatusBadge.setTextSize(10);
        autoStatusBadge.setTextColor(0xFFFFFFFF);
        autoStatusBadge.setGravity(Gravity.CENTER);
        GradientDrawable autoBadgeBg = new GradientDrawable();
        if (autoOn) {
            autoBadgeBg.setColor(COLOR_ACCENT);
        } else {
            autoBadgeBg.setColor(0xFF90A4AE);
        }
        autoBadgeBg.setCornerRadius(dp(100));
        autoStatusBadge.setBackground(autoBadgeBg);
        autoStatusBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        autoStatusBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams autoBadgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoBadgeLp.leftMargin = dp(10);
        autoStatusBadge.setLayoutParams(autoBadgeLp);
        featureHeader.addView(autoStatusBadge);

        content.addView(featureHeader);

        // 功能亮点列表（带彩色图标）
        String[] highlightIcons = {"🎯", "📝", "⚡", "🔒", "📊"};
        String[] highlights = {
                "自动识别正确答案并高亮标记",
                "支持单选 / 多选 / 判断 / 填空题",
                "答案自动选中，无需手动点击",
                "通过 ContentProvider 跨进程数据共享",
                "实时统计与请求日志查看"
        };
        int[] highlightColors = {0xFFFF9800, 0xFF2196F3, 0xFF4CAF50, 0xFF9C27B0, 0xFF00BCD4};

        for (int i = 0; i < highlights.length; i++) {
            LinearLayout hRow = new LinearLayout(this);
            hRow.setOrientation(LinearLayout.HORIZONTAL);
            hRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hrp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hrp.topMargin = (i == 0) ? dp(10) : dp(6);
            hRow.setLayoutParams(hrp);

            // 彩色小圆圈图标
            LinearLayout bulletCircle = new LinearLayout(this);
            bulletCircle.setGravity(Gravity.CENTER);
            GradientDrawable bcGd = new GradientDrawable();
            bcGd.setShape(GradientDrawable.OVAL);
            bcGd.setColor(highlightColors[i]);
            bulletCircle.setBackground(bcGd);
            LinearLayout.LayoutParams bclp = new LinearLayout.LayoutParams(dp(22), dp(22));
            bulletCircle.setLayoutParams(bclp);

            TextView bulletIcon = new TextView(this);
            bulletIcon.setText(highlightIcons[i]);
            bulletIcon.setTextSize(11);
            bulletIcon.setGravity(Gravity.CENTER);
            bulletCircle.addView(bulletIcon);
            hRow.addView(bulletCircle);

            TextView hText = new TextView(this);
            hText.setText(highlights[i]);
            hText.setTextSize(12);
            hText.setTextColor(COLOR_TEXT_PRIMARY);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.leftMargin = dp(10);
            hText.setLayoutParams(hlp);
            hRow.addView(hText);

            content.addView(hRow);
        }

        // 底部提示（未激活时显示）
        if (!active) {
            TextView tip = new TextView(this);
            tip.setText("\n📌 请在 LSPosed 管理器中启用本模块，并勾选作用域：\n" +
                    "    • " + TARGET_PACKAGE + "（目标应用）\n" +
                    "    • " + MODULE_PACKAGE + "（模块自身）\n" +
                    "    启用后请重启目标应用。");
            tip.setTextSize(11);
            tip.setTextColor(COLOR_DANGER);
            tip.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tipLp.topMargin = dp(10);
            tip.setLayoutParams(tipLp);
            content.addView(tip);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 快捷操作卡（含美观自动答题开关 + 操作按钮） ============
    private void addActionCard(LinearLayout root, StatsData data) {
        // 外层卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(COLOR_CARD);
        cardGd.setCornerRadius(dp(16));
        cardGd.setStroke(dp(1), 0xFFE0E4EC);
        card.setBackground(cardGd);

        // 顶部渐变色条（蓝→绿）
        View topStrip = new View(this);
        GradientDrawable stripGd = new GradientDrawable();
        stripGd.setColors(new int[]{0xFF2196F3, 0xFF4CAF50});
        stripGd.setCornerRadii(new float[]{dp(16), dp(16), 0, 0, 0, 0, dp(16), dp(16)});
        topStrip.setBackground(stripGd);
        LinearLayout.LayoutParams topStripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        card.addView(topStrip, topStripLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(16));

        // 标题行（快捷操作 + 图标）
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleIconWrap = new LinearLayout(this);
        titleIconWrap.setGravity(Gravity.CENTER);
        GradientDrawable titleIconGd = new GradientDrawable();
        titleIconGd.setShape(GradientDrawable.OVAL);
        titleIconGd.setColor(0xFFE3F2FD);
        titleIconWrap.setBackground(titleIconGd);
        LinearLayout.LayoutParams tilp = new LinearLayout.LayoutParams(dp(28), dp(28));
        titleIconWrap.setLayoutParams(tilp);

        TextView sectionIcon = new TextView(this);
        sectionIcon.setText("⚡");
        sectionIcon.setTextSize(14);
        sectionIcon.setGravity(Gravity.CENTER);
        titleIconWrap.addView(sectionIcon);
        titleRow.addView(titleIconWrap);

        TextView title = new TextView(this);
        title.setText("快捷操作");
        title.setTextSize(16);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        title.setLayoutParams(tlp);
        titleRow.addView(title);

        content.addView(titleRow);

        // 分隔线
        View divider = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(0xFFEEEEEE);
        divider.setBackground(dd);
        LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp2.topMargin = dp(12);
        divLp2.bottomMargin = dp(14);
        content.addView(divider, divLp2);

        // === 自动答题开关（核心功能，重新设计的美观开关） ===
        final boolean[] switchState = {data != null && data.autoSelectEnabled};

        // 开关容器卡片
        final LinearLayout toggleCard = new LinearLayout(this);
        toggleCard.setOrientation(LinearLayout.HORIZONTAL);
        toggleCard.setGravity(Gravity.CENTER_VERTICAL);
        final GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setColor(switchState[0] ? 0xFFF1F8E9 : 0xFFFAFBFC);
        toggleBg.setCornerRadius(dp(14));
        toggleBg.setStroke(dp(2), switchState[0] ? 0xFF66BB6A : 0xFFE0E0E0);
        toggleCard.setBackground(toggleBg);
        toggleCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleCard.setLayoutParams(tcLp);

        // 左侧：大图标（圆形渐变背景）
        final LinearLayout bigIcon = new LinearLayout(this);
        bigIcon.setGravity(Gravity.CENTER);
        final GradientDrawable bigIconBg = new GradientDrawable();
        bigIconBg.setShape(GradientDrawable.OVAL);
        if (switchState[0]) {
            bigIconBg.setColors(new int[]{0xFF4CAF50, 0xFF2E7D32});
        } else {
            bigIconBg.setColors(new int[]{0xFF90A4AE, 0xFF607D8B});
        }
        bigIcon.setBackground(bigIconBg);
        LinearLayout.LayoutParams bilp = new LinearLayout.LayoutParams(dp(56), dp(56));
        bigIcon.setLayoutParams(bilp);

        final TextView iconEmoji = new TextView(this);
        iconEmoji.setText(switchState[0] ? "✓" : "✗");
        iconEmoji.setTextSize(26);
        iconEmoji.setTextColor(0xFFFFFFFF);
        iconEmoji.setTypeface(null, android.graphics.Typeface.BOLD);
        iconEmoji.setGravity(Gravity.CENTER);
        bigIcon.addView(iconEmoji);
        toggleCard.addView(bigIcon);

        // 中间：标题 + 描述 + 小标签
        LinearLayout switchText = new LinearLayout(this);
        switchText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stLp.leftMargin = dp(14);
        stLp.rightMargin = dp(10);
        switchText.setLayoutParams(stLp);

        TextView switchTitle = new TextView(this);
        switchTitle.setText("自动答题");
        switchTitle.setTextSize(17);
        switchTitle.setTextColor(COLOR_TEXT_PRIMARY);
        switchTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        switchText.addView(switchTitle);

        final TextView switchDesc2 = new TextView(this);
        switchDesc2.setText(switchState[0] ? "自动识别并点击正确答案" : "点击右侧开关启用自动答题");
        switchDesc2.setTextSize(12);
        switchDesc2.setTextColor(switchState[0] ? COLOR_ACCENT : COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sdLp.topMargin = dp(3);
        switchDesc2.setLayoutParams(sdLp);
        switchText.addView(switchDesc2);

        // 小状态标签
        final TextView miniStatus = new TextView(this);
        miniStatus.setText(switchState[0] ? "● 运行中" : "○ 已停用");
        miniStatus.setTextSize(10);
        miniStatus.setTextColor(switchState[0] ? COLOR_ACCENT : 0xFF90A4AE);
        miniStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msLp.topMargin = dp(4);
        miniStatus.setLayoutParams(msLp);
        switchText.addView(miniStatus);

        toggleCard.addView(switchText);

        // 右侧：自定义滑动开关（更美观）
        final LinearLayout customSwitch = new LinearLayout(this);
        customSwitch.setOrientation(LinearLayout.VERTICAL);
        customSwitch.setGravity(Gravity.CENTER);
        final GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setColor(switchState[0] ? 0xFF81C784 : 0xFFCFD8DC);
        trackBg.setCornerRadius(dp(15));
        customSwitch.setBackground(trackBg);
        LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(dp(52), dp(30));
        customSwitch.setLayoutParams(cslp);

        // 白色滑块
        final View thumb = new View(this);
        final GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(0xFFFFFFFF);
        thumbBg.setStroke(dp(1), switchState[0] ? 0xFF66BB6A : 0xFFB0BEC5);
        thumb.setBackground(thumbBg);
        final LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        thumbLp.setMargins(switchState[0] ? dp(26) : dp(2), dp(3), switchState[0] ? dp(2) : dp(26), dp(3));
        thumb.setLayoutParams(thumbLp);
        customSwitch.addView(thumb);

        toggleCard.addView(customSwitch);

        // 整行点击切换
        toggleCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchState[0] = !switchState[0];

                // 更新开关卡片背景
                toggleBg.setColor(switchState[0] ? 0xFFF1F8E9 : 0xFFFAFBFC);
                toggleBg.setStroke(dp(2), switchState[0] ? 0xFF66BB6A : 0xFFE0E0E0);

                // 更新左侧大图标
                if (switchState[0]) {
                    bigIconBg.setColors(new int[]{0xFF4CAF50, 0xFF2E7D32});
                } else {
                    bigIconBg.setColors(new int[]{0xFF90A4AE, 0xFF607D8B});
                }
                iconEmoji.setText(switchState[0] ? "✓" : "✗");

                // 更新描述
                switchDesc2.setText(switchState[0] ? "自动识别并点击正确答案" : "点击右侧开关启用自动答题");
                switchDesc2.setTextColor(switchState[0] ? COLOR_ACCENT : COLOR_TEXT_SECONDARY);

                // 更新小状态标签
                miniStatus.setText(switchState[0] ? "● 运行中" : "○ 已停用");
                miniStatus.setTextColor(switchState[0] ? COLOR_ACCENT : 0xFF90A4AE);

                // 更新右侧自定义开关
                trackBg.setColor(switchState[0] ? 0xFF81C784 : 0xFFCFD8DC);
                thumbLp.setMargins(switchState[0] ? dp(26) : dp(2), dp(3),
                        switchState[0] ? dp(2) : dp(26), dp(3));
                thumbBg.setStroke(dp(1), switchState[0] ? 0xFF66BB6A : 0xFFB0BEC5);

                // 保存状态
                try {
                    ContentValues values = new ContentValues();
                    values.put("auto_select_enabled", switchState[0]);
                    getContentResolver().update(Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"),
                            values, null, null);
                } catch (Throwable ignored) {}
                try {
                    SharedPreferences sp = getSharedPreferences("module_stats", MODE_PRIVATE);
                    sp.edit().putBoolean("auto_select_enabled", switchState[0]).apply();
                } catch (Throwable ignored) {}

                Toast.makeText(MainActivity.this,
                        switchState[0] ? "✓ 自动答题已开启" : "自动答题已关闭",
                        Toast.LENGTH_SHORT).show();
            }
        });

        content.addView(toggleCard);

        // 操作按钮区域标题
        LinearLayout actionTitleRow = new LinearLayout(this);
        actionTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        actionTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams atrlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        atrlp.topMargin = dp(18);
        actionTitleRow.setLayoutParams(atrlp);

        TextView actionIconTv = new TextView(this);
        actionIconTv.setText("🎮");
        actionIconTv.setTextSize(12);
        actionTitleRow.addView(actionIconTv);

        TextView actionTitle = new TextView(this);
        actionTitle.setText(" 操作按钮");
        actionTitle.setTextSize(12);
        actionTitle.setTextColor(COLOR_PRIMARY_DARK);
        actionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        actionTitleRow.addView(actionTitle);

        content.addView(actionTitleRow);

        // 按钮区域：启动目标应用（大按钮，蓝色渐变）
        Button launchBtn = new Button(this);
        launchBtn.setText("🚀 启动目标应用");
        launchBtn.setTextColor(0xFFFFFFFF);
        launchBtn.setTextSize(14);
        GradientDrawable launchBg = new GradientDrawable();
        launchBg.setColors(new int[]{0xFF42A5F5, 0xFF1976D2});
        launchBg.setCornerRadius(dp(12));
        launchBtn.setBackground(launchBg);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp(10);
        launchBtn.setLayoutParams(lbLp);
        launchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchTargetApp();
            }
        });
        content.addView(launchBtn);

        // 刷新 + 清空 并排按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(10);
        btnRow.setLayoutParams(brLp);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("🔄 刷新数据");
        refreshBtn.setTextColor(COLOR_PRIMARY_DARK);
        refreshBtn.setTextSize(13);
        GradientDrawable refreshBg = new GradientDrawable();
        refreshBg.setColor(0xFFE3F2FD);
        refreshBg.setCornerRadius(dp(12));
        refreshBg.setStroke(dp(1), 0xFFBBDEFB);
        refreshBtn.setBackground(refreshBg);
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rbp.rightMargin = dp(8);
        refreshBtn.setLayoutParams(rbp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCurrentPage = 0;
                refreshStatsAsync();
                Toast.makeText(MainActivity.this, "数据已刷新", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(refreshBtn);

        Button clearBtn = new Button(this);
        clearBtn.setText("🗑 清空统计");
        clearBtn.setTextColor(0xFFD84315);
        clearBtn.setTextSize(13);
        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setColor(0xFFFFF3E0);
        clearBg.setCornerRadius(dp(12));
        clearBg.setStroke(dp(1), 0xFFFFCCBC);
        clearBtn.setBackground(clearBg);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clearBtn.setLayoutParams(cbp);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    // ⚠️ 保留自动答题开关状态：先读取
                    SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
                    boolean savedAuto = selfSp.getBoolean("auto_select_enabled", false);

                    // 1. 清空 ContentProvider 统计（通过 clear 路径）
                    try {
                        getContentResolver().delete(
                                Uri.parse("content://" + MODULE_PACKAGE + ".stats/clear"),
                                null, null);
                    } catch (Throwable ignored) {}

                    // 2. 清空模块自己的 SP，再恢复 auto_select_enabled
                    selfSp.edit().clear().putBoolean("auto_select_enabled", savedAuto).apply();

                    // 3. 同步恢复 ContentProvider 中的 auto_select_enabled
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put("auto_select_enabled", savedAuto);
                        getContentResolver().update(
                                Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"),
                                cv, null, null);
                    } catch (Throwable ignored) {}

                    Toast.makeText(MainActivity.this, "已清空统计数据（自动答题状态已保留）", Toast.LENGTH_SHORT).show();
                    mCurrentPage = 0;
                    refreshStatsAsync();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "清空失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnRow.addView(clearBtn);

        content.addView(btnRow);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 统计数据卡（列表形式） ============
    private void addStatsCard(LinearLayout root, StatsData data) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_ACCENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("统计数据");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        int hh = data != null ? data.targetHitCount : -1;
        int rc = data != null ? data.requestCount : -1;
        long lt = data != null ? data.lastHookTime : -1;

        // 列表项数据
        List<StatItem> items = new ArrayList<>();
        items.add(new StatItem("答案命中", hh < 0 ? null : String.valueOf(hh), COLOR_ACCENT));
        items.add(new StatItem("请求总数", rc < 0 ? null : String.valueOf(rc), COLOR_WARNING));
        items.add(new StatItem("最近活跃", formatTime(lt), COLOR_TEXT_SECONDARY));

        // 渲染列表
        boolean hasAnyData = false;
        for (int i = 0; i < items.size(); i++) {
            StatItem item = items.get(i);
            View row = makeStatListItem(item.label, item.value, item.color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (i == 0) ? dp(10) : dp(6);
            content.addView(row, lp);
            if (item.value != null) hasAnyData = true;
        }

        // 空状态提示
        if (!hasAnyData) {
            TextView empty = new TextView(this);
            empty.setText("暂无统计数据\n\n请先打开目标应用，进入答题页面后返回刷新");
            empty.setTextSize(12);
            empty.setTextColor(COLOR_TEXT_SECONDARY);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = dp(10);
            content.addView(empty, ep);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    private static class StatItem {
        String label;
        String value;
        int color;
        StatItem(String label, String value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    // ============ 统计列表行 ============
    private View makeStatListItem(String label, String value, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFE0E4EC);
        row.setBackground(bg);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        // 左侧色块
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(color);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMargins(0, 0, dp(10), 0);
        dot.setLayoutParams(dotLp);
        row.addView(dot);

        // 标签
        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(13);
        labelTv.setTextColor(COLOR_TEXT_PRIMARY);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.gravity = Gravity.CENTER_VERTICAL;
        labelTv.setLayoutParams(labelLp);
        row.addView(labelTv);

        // 值
        TextView valueTv = new TextView(this);
        if (value != null) {
            valueTv.setText(value);
            valueTv.setTextColor(color);
            valueTv.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            valueTv.setText("--");
            valueTv.setTextColor(0xFFB0BEC5);
        }
        valueTv.setTextSize(14);
        valueTv.setGravity(Gravity.END);
        row.addView(valueTv);

        return row;
    }

    // ============ HTTP 客户端检测列表 ============
    private void addHttpClientsCard(LinearLayout root, StatsData data) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_PRIMARY));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("检测到的 HTTP 客户端 / 框架");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        String raw = data != null ? data.detectedClients : "";
        if (raw == null) raw = "";
        List<String> clients = new ArrayList<>();
        if (!raw.trim().isEmpty()) {
            for (String s : raw.split("\n")) {
                if (s != null && s.trim().length() > 0) clients.add(s.trim());
            }
        }

        if (!clients.isEmpty()) {
            TextView count = new TextView(this);
            count.setText("共 " + clients.size() + " 个类已在目标应用中发现");
            count.setTextSize(12);
            count.setTextColor(COLOR_ACCENT);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(8);
            content.addView(count, cp);

            for (int i = 0; i < clients.size(); i++) {
                View row = makeListItem(i + 1, clients.get(i), null, COLOR_PRIMARY, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (i == 0) ? dp(8) : dp(6);
                content.addView(row, lp);
            }
        } else {
            TextView empty = new TextView(this);
            empty.setText("尚未检测到 HTTP 客户端\n\n请按以下步骤操作：\n  1. 在 LSPosed 管理器中确认模块已启用，作用域包含目标应用\n  2. 点击「启动目标应用」进入答题页\n  3. 回到本页面点击「刷新数据」");
            empty.setTextSize(12);
            empty.setTextColor(COLOR_TEXT_SECONDARY);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = dp(8);
            content.addView(empty, ep);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 最近请求记录（分页 + 截断） ============
    private void addRecentRequestsCard(LinearLayout root, StatsData data) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_WARNING));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("最近请求记录");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        List<RequestItem> requests = data != null ? data.requests : null;

        if (requests != null && !requests.isEmpty()) {
            // 总数
            TextView count = new TextView(this);
            count.setText("共 " + requests.size() + " 条记录");
            count.setTextSize(12);
            count.setTextColor(COLOR_ACCENT);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(8);
            content.addView(count, cp);

            // 计算分页
            int totalPages = (requests.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            if (mCurrentPage >= totalPages) mCurrentPage = totalPages - 1;
            if (mCurrentPage < 0) mCurrentPage = 0;

            int startIdx = mCurrentPage * PAGE_SIZE;
            int endIdx = Math.min(startIdx + PAGE_SIZE, requests.size());

            // 当前范围提示
            TextView rangeTv = new TextView(this);
            rangeTv.setText("显示第 " + (startIdx + 1) + " - " + endIdx + " 条 / 第 " + (mCurrentPage + 1) + " 页 / 共 " + totalPages + " 页");
            rangeTv.setTextSize(11);
            rangeTv.setTextColor(COLOR_TEXT_SECONDARY);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            content.addView(rangeTv, rlp);

            // 渲染当前页条目
            for (int i = startIdx; i < endIdx; i++) {
                final RequestItem item = requests.get(i);
                final int idx = i + 1;
                int color = COLOR_PRIMARY;
                if (item.type != null) {
                    if (item.type.contains("WEBVIEW")) color = COLOR_INFO;
                    else if (item.type.contains("OKHTTP")) color = COLOR_PRIMARY;
                    else if (item.type.contains("URL")) color = COLOR_ACCENT;
                    else if (item.type.contains("SOCKET")) color = COLOR_DANGER;
                }
                View row = makeListItem(idx, item.url, item.type, color, true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showRequestDetail(item, idx);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                content.addView(row, lp);
            }

            // 分页按钮
            LinearLayout pagerRow = new LinearLayout(this);
            pagerRow.setOrientation(LinearLayout.HORIZONTAL);
            pagerRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pprp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pprp.topMargin = dp(14);
            content.addView(pagerRow, pprp);

            final boolean hasPrev = mCurrentPage > 0;
            final boolean hasNext = mCurrentPage < totalPages - 1;

            Button prevBtn = new Button(this);
            prevBtn.setText("◀ 上一页");
            prevBtn.setTextSize(12);
            prevBtn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            prevLp.rightMargin = dp(8);
            prevBtn.setLayoutParams(prevLp);
            if (hasPrev) {
                prevBtn.setBackground(makeRippleButton(COLOR_PRIMARY));
                prevBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mCurrentPage > 0) {
                            mCurrentPage--;
                            renderUI();
                        }
                    }
                });
            } else {
                prevBtn.setBackground(makeRippleButton(0xFFB0BEC5));
                prevBtn.setEnabled(false);
            }
            pagerRow.addView(prevBtn);

            Button nextBtn = new Button(this);
            nextBtn.setText("下一页 ▶");
            nextBtn.setTextSize(12);
            nextBtn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nextBtn.setLayoutParams(nextLp);
            if (hasNext) {
                nextBtn.setBackground(makeRippleButton(COLOR_PRIMARY));
                nextBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mCurrentPage++;
                        renderUI();
                    }
                });
            } else {
                nextBtn.setBackground(makeRippleButton(0xFFB0BEC5));
                nextBtn.setEnabled(false);
            }
            pagerRow.addView(nextBtn);
        } else {
            TextView empty = new TextView(this);
            empty.setText("尚未捕获任何请求\n\n请先打开目标应用并进入答题页面。答题页面通常使用 WebView，请求将被自动拦截并记录。");
            empty.setTextSize(12);
            empty.setTextColor(COLOR_TEXT_SECONDARY);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = dp(8);
            content.addView(empty, ep);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 列表行（支持截断 + "查看全部"） ============
    private View makeListItem(int index, String mainText, String typeLabel, int color, boolean truncate) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFFE0E4EC);
        row.setBackground(bg);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        // 序号圆章
        LinearLayout circle = new LinearLayout(this);
        GradientDrawable circBg = new GradientDrawable();
        circBg.setColor(color);
        circBg.setCornerRadius(dp(14));
        circle.setBackground(circBg);
        circle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams circLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        circLp.setMargins(0, 0, dp(12), 0);
        circle.setLayoutParams(circLp);

        TextView num = new TextView(this);
        num.setText(String.valueOf(index));
        num.setTextSize(13);
        num.setTextColor(0xFFFFFFFF);
        num.setTypeface(null, android.graphics.Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        circle.addView(num);

        row.addView(circle);

        // 右侧文本区
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tap = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tap.gravity = Gravity.CENTER_VERTICAL;
        textArea.setLayoutParams(tap);

        if (typeLabel != null && !typeLabel.isEmpty()) {
            TextView typeTv = new TextView(this);
            typeTv.setText(typeLabel);
            typeTv.setTextSize(11);
            typeTv.setTextColor(color);
            typeTv.setTypeface(null, android.graphics.Typeface.BOLD);
            typeTv.setAllCaps(true);
            textArea.addView(typeTv);
        }

        // URL 文本（可能截断）
        String displayText = mainText;
        boolean isLong = truncate && mainText != null && mainText.length() > URL_PREVIEW_LENGTH;
        if (isLong) {
            displayText = mainText.substring(0, URL_PREVIEW_LENGTH) + "...";
        }

        TextView mainTv = new TextView(this);
        mainTv.setText(displayText);
        mainTv.setTextSize(11);
        mainTv.setTextColor(COLOR_TEXT_PRIMARY);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (typeLabel != null && !typeLabel.isEmpty()) mtp.topMargin = dp(2);
        textArea.addView(mainTv, mtp);

        // 如果是长 URL，加一个 "查看全部" 提示
        if (isLong) {
            TextView more = new TextView(this);
            more.setText("▸ 点击查看完整内容 (" + mainText.length() + " 字符)");
            more.setTextSize(10);
            more.setTextColor(COLOR_PRIMARY);
            more.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mrp.topMargin = dp(4);
            textArea.addView(more, mrp);
        }

        row.addView(textArea);

        // 点击箭头（可点击感）
        if (truncate) {
            TextView arrow = new TextView(this);
            arrow.setText(" ›");
            arrow.setTextSize(20);
            arrow.setTextColor(COLOR_TEXT_SECONDARY);
            arrow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.setMargins(dp(6), 0, 0, 0);
            arrow.setLayoutParams(alp);
            row.addView(arrow);
        }

        return row;
    }

    // ============ 请求详情弹窗（美化） ============
    private void showRequestDetail(RequestItem item, int index) {
        // 颜色
        int color = COLOR_PRIMARY;
        if (item.type != null) {
            if (item.type.contains("WEBVIEW")) color = COLOR_INFO;
            else if (item.type.contains("OKHTTP")) color = COLOR_PRIMARY;
            else if (item.type.contains("URL")) color = COLOR_ACCENT;
            else if (item.type.contains("SOCKET")) color = COLOR_DANGER;
        }

        // 容器（可滚动）
        ScrollView sv = new ScrollView(this);
        sv.setPadding(0, 0, 0, 0);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(12));

        // 顶部：序号 + 类型
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(14);
        container.addView(header, hlp);

        // 序号圆章
        LinearLayout circle = new LinearLayout(this);
        GradientDrawable circBg = new GradientDrawable();
        circBg.setColor(color);
        circBg.setCornerRadius(dp(18));
        circle.setBackground(circBg);
        circle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams circLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        circLp.setMargins(0, 0, dp(12), 0);
        circle.setLayoutParams(circLp);
        TextView num = new TextView(this);
        num.setText(String.valueOf(index));
        num.setTextSize(15);
        num.setTextColor(0xFFFFFFFF);
        num.setTypeface(null, android.graphics.Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        circle.addView(num);
        header.addView(circle);

        LinearLayout headerTexts = new LinearLayout(this);
        headerTexts.setOrientation(LinearLayout.VERTICAL);
        headerTexts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView typeTv = new TextView(this);
        typeTv.setText(item.type != null ? item.type.toUpperCase() : "UNKNOWN");
        typeTv.setTextSize(13);
        typeTv.setTextColor(color);
        typeTv.setTypeface(null, android.graphics.Typeface.BOLD);
        typeTv.setAllCaps(true);
        headerTexts.addView(typeTv);

        TextView timeLabel = new TextView(this);
        timeLabel.setText(formatFullTime(item.time));
        timeLabel.setTextSize(11);
        timeLabel.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        headerTexts.addView(timeLabel, tlp);

        header.addView(headerTexts);

        // 分隔线
        View divider = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(0xFFE0E4EC);
        divider.setBackground(dd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.bottomMargin = dp(12);
        container.addView(divider, dlp);

        // "URL" 标题
        TextView urlLabel = new TextView(this);
        urlLabel.setText("完整请求 URL");
        urlLabel.setTextSize(12);
        urlLabel.setTextColor(COLOR_PRIMARY_DARK);
        urlLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams ullp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ullp.bottomMargin = dp(8);
        container.addView(urlLabel, ullp);

        // URL 内容
        TextView urlContent = new TextView(this);
        urlContent.setText(item.url != null ? item.url : "(空)");
        urlContent.setTextSize(12);
        urlContent.setTextColor(COLOR_TEXT_PRIMARY);
        urlContent.setAutoLinkMask(Linkify.WEB_URLS);
        urlContent.setLinksClickable(true);
        urlContent.setLineSpacing(dp(2), 1f);
        GradientDrawable urlBox = new GradientDrawable();
        urlBox.setColor(0xFFFAFBFC);
        urlBox.setCornerRadius(dp(8));
        urlBox.setStroke(dp(1), 0xFFE0E4EC);
        urlContent.setBackground(urlBox);
        urlContent.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams uclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uclp.bottomMargin = dp(10);
        container.addView(urlContent, uclp);

        // 长度信息
        TextView lengthTv = new TextView(this);
        lengthTv.setText("字符长度: " + (item.url != null ? item.url.length() : 0));
        lengthTv.setTextSize(11);
        lengthTv.setTextColor(COLOR_TEXT_SECONDARY);
        lengthTv.setGravity(Gravity.RIGHT);
        container.addView(lengthTv);

        sv.addView(container);

        // 创建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("请求详情");
        builder.setView(sv);
        builder.setPositiveButton("复制 URL", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", item.url));
                    Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("关闭", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        // 美化按钮颜色
        try {
            Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            posBtn.setTextColor(COLOR_PRIMARY_DARK);
            posBtn.setTextSize(13);
            Button negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            negBtn.setTextColor(COLOR_TEXT_SECONDARY);
            negBtn.setTextSize(13);
        } catch (Throwable ignored) {}
    }

    // ============ 工作原理说明卡 ============
    private void addInfoCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(0xFF9C27B0));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        // 标题行
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleIcon = new TextView(this);
        titleIcon.setText("💡");
        titleIcon.setTextSize(14);
        titleRow.addView(titleIcon);

        TextView title = new TextView(this);
        title.setText(" 工作原理");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);
        content.addView(titleRow);

        // 步骤说明
        String[] steps = {
                "LSPosed 在目标应用启动时注入 Hook 代码，监听网络请求",
                "拦截 WebView / OkHttp 请求，解析返回的 JSON 数据",
                "识别题目正确答案后，在页面上高亮标记并自动点击",
                "统计数据通过 ContentProvider 跨进程传递给本模块",
                "本模块读取并展示统计、请求历史等信息"
        };

        for (int i = 0; i < steps.length; i++) {
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.HORIZONTAL);
            stepRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srlp.topMargin = dp(8);
            stepRow.setLayoutParams(srlp);

            // 序号圆章
            LinearLayout numCircle = new LinearLayout(this);
            numCircle.setGravity(Gravity.CENTER);
            GradientDrawable numGd = new GradientDrawable();
            numGd.setShape(GradientDrawable.OVAL);
            numGd.setColor(0xFF9C27B0);
            numCircle.setBackground(numGd);
            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            numCircle.setLayoutParams(numLp);

            TextView numTv = new TextView(this);
            numTv.setText(String.valueOf(i + 1));
            numTv.setTextSize(11);
            numTv.setTextColor(0xFFFFFFFF);
            numTv.setTypeface(null, android.graphics.Typeface.BOLD);
            numTv.setGravity(Gravity.CENTER);
            numCircle.addView(numTv);
            stepRow.addView(numCircle);

            // 步骤文字
            TextView stepText = new TextView(this);
            stepText.setText(steps[i]);
            stepText.setTextSize(12);
            stepText.setTextColor(COLOR_TEXT_PRIMARY);
            LinearLayout.LayoutParams stlLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stlLp.leftMargin = dp(10);
            stepText.setLayoutParams(stlLp);
            stepRow.addView(stepText);

            content.addView(stepRow);
        }

        // 底部小提示
        TextView tipTv = new TextView(this);
        tipTv.setText("\n📌 每页显示 " + PAGE_SIZE + " 条请求记录，点击分页按钮翻页浏览历史");
        tipTv.setTextSize(11);
        tipTv.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(8);
        tipTv.setLayoutParams(tipLp);
        content.addView(tipTv);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 工具方法 ============
    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), 0xFFE0E4EC);
        card.setBackground(gd);
        return card;
    }

    private View createColorStrip(int color) {
        View strip = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadii(new float[]{dp(12), dp(12), dp(12), dp(12), 0, 0, 0, 0});
        strip.setBackground(gd);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        strip.setLayoutParams(sp);
        return strip;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(14);
        return p;
    }

    private GradientDrawable makeRippleButton(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(8));
        return gd;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private String formatTime(long t) {
        if (t <= 0) return "--";
        long diff = System.currentTimeMillis() - t;
        if (diff < 0) diff = 0;
        if (diff < 60 * 1000L) return (diff / 1000L) + " 秒前";
        if (diff < 60 * 60 * 1000L) return (diff / 60000L) + " 分钟前";
        if (diff < 24 * 60 * 60 * 1000L) return (diff / 3600000L) + " 小时前";
        if (diff < 7 * 24 * 60 * 60 * 1000L) return (diff / 86400000L) + " 天前";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            return sdf.format(new Date(t));
        } catch (Throwable ignored) {
            return "已检测";
        }
    }

    private String formatFullTime(long t) {
        if (t <= 0) return "时间未知";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(t));
        } catch (Throwable ignored) {
            return "时间未知";
        }
    }

    // ============ 目标应用启动 / 停止 ============
    private void launchTargetApp() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                Toast.makeText(this, "正在启动 " + TARGET_PACKAGE + "...", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Throwable ignored) {}

        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE,
                    android.content.pm.PackageManager.GET_ACTIVITIES);
            if (pi != null && pi.activities != null && pi.activities.length > 0) {
                String activityName = pi.activities[0].name;
                Intent intent = new Intent();
                intent.setClassName(TARGET_PACKAGE, activityName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    Toast.makeText(this, "正在启动 " + activityName, Toast.LENGTH_SHORT).show();
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(TARGET_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                Toast.makeText(this, "正在启动 " + TARGET_PACKAGE, Toast.LENGTH_SHORT).show();
                return;
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        Toast.makeText(this, "无法启动目标应用，请手动在系统桌面打开", Toast.LENGTH_LONG).show();
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) { return false; }
    }

    // ============ Xposed Hook 的方法（hook 成功后返回 true） ============
    public static boolean isModuleActive() {
        return false;
    }
}
