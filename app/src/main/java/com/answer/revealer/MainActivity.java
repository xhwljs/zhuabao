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

    // ============ UI/UX Pro Max 设计系统 ============
    // Pattern: Scroll-Triggered Storytelling | Style: Vibrant & Block-based + Micro-interactions
    // 主题：Code Dark + Run Green  (developer tool / IDE style)
    private static final int DS_BG           = 0xFF0F172A; // slate-900, page bg
    private static final int DS_CARD         = 0xFF1E293B; // slate-800, card surface
    private static final int DS_CARD_ELEV    = 0xFF334155; // slate-700, elevated surface
    private static final int DS_BORDER       = 0xFF334155; // slate-700
    private static final int DS_TEXT         = 0xFFF8FAFC; // slate-50, primary text
    private static final int DS_TEXT_MUTED   = 0xFF94A3B8; // slate-400, secondary text
    private static final int DS_TEXT_DIM     = 0xFF64748B; // slate-500, tertiary text
    private static final int DS_CTA          = 0xFF22C55E; // emerald-500, "run green" CTA
    private static final int DS_CTA_DARK     = 0xFF15803D; // emerald-700, dark CTA
    private static final int DS_CTA_LIGHT    = 0xFF86EFAC; // emerald-300, light CTA
    private static final int DS_ERROR        = 0xFFEF4444; // red-500
    private static final int DS_ERROR_LIGHT  = 0xFFFEE2E2; // red-50
    private static final int DS_WARNING      = 0xFFF59E0B; // amber-500
    private static final int DS_INFO         = 0xFF3B82F6; // blue-500
    private static final int DS_INFO_LIGHT   = 0xFFDBEAFE; // blue-50
    private static final int DS_PURPLE       = 0xFF8B5CF6; // violet-500
    private static final int DS_SKY          = 0xFF06B6D4; // cyan-500
    private static final int DS_ORANGE       = 0xFFF97316; // orange-500

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
        scrollView.setBackgroundColor(DS_BG);
        scrollView.setPadding(0, dp(8), 0, dp(32));
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

    // ============ 顶部栏（UI/UX Pro Max 重新设计） ============
    // Pattern: Scroll-Triggered Storytelling hero | Style: Code Dark + Run Green
    private void addHeader(LinearLayout root) {
        // 外层主卡片：深色代码编辑器背景
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(20));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(20));

        // ========== 顶部 Hero：圆形图标 + 标题 + 技术标签 ==========
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        // 左侧渐变圆形图标容器：绿色高亮（CTA 颜色）
        LinearLayout iconWrap = new LinearLayout(this);
        iconWrap.setGravity(Gravity.CENTER);
        GradientDrawable iwGd = new GradientDrawable();
        iwGd.setShape(GradientDrawable.OVAL);
        iwGd.setColors(new int[]{DS_CTA, DS_CTA_DARK});
        iconWrap.setBackground(iwGd);
        iconWrap.setPadding(dp(1), dp(1), dp(1), dp(1));
        LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(dp(60), dp(60));
        iconWrap.setLayoutParams(iwLp);

        // 内层深色圆形
        LinearLayout innerIcon = new LinearLayout(this);
        innerIcon.setGravity(Gravity.CENTER);
        GradientDrawable innerBg = new GradientDrawable();
        innerBg.setShape(GradientDrawable.OVAL);
        innerBg.setColor(DS_CARD);
        innerIcon.setBackground(innerBg);
        LinearLayout.LayoutParams innerLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        innerIcon.setLayoutParams(innerLp);

        // 白色 Terminal-style ">" 标记
        TextView iconTv = new TextView(this);
        iconTv.setText(">_");
        iconTv.setTextSize(18);
        iconTv.setTextColor(DS_CTA_LIGHT);
        iconTv.setGravity(Gravity.CENTER);
        iconTv.setTypeface(null, android.graphics.Typeface.BOLD);
        innerIcon.addView(iconTv);
        iconWrap.addView(innerIcon);
        headerRow.addView(iconWrap);

        // 右侧文字区
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams taLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        taLp.leftMargin = dp(14);
        textArea.setLayoutParams(taLp);

        // 大标题：应用名称
        TextView title = new TextView(this);
        title.setText("Answer Revealer");
        title.setTextSize(18);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        textArea.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("答案显示模块 · LSPosed Hook");
        subtitle.setTextSize(11);
        subtitle.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subtitle.setLayoutParams(subLp);
        textArea.addView(subtitle);

        // 状态胶囊标签行：三个装饰性标签
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = dp(10);
        tagRow.setLayoutParams(trLp);

        // Tag 1: 绿色 "ACTIVE" 风格
        LinearLayout tag1Wrap = new LinearLayout(this);
        tag1Wrap.setOrientation(LinearLayout.HORIZONTAL);
        tag1Wrap.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable t1Bg = new GradientDrawable();
        t1Bg.setColor(DS_CTA_DARK);
        t1Bg.setCornerRadius(dp(100));
        tag1Wrap.setBackground(t1Bg);
        tag1Wrap.setPadding(dp(10), dp(4), dp(10), dp(4));

        TextView t1Dot = new TextView(this);
        t1Dot.setText("●");
        t1Dot.setTextSize(8);
        t1Dot.setTextColor(DS_CTA_LIGHT);
        t1Dot.setGravity(Gravity.CENTER);
        tag1Wrap.addView(t1Dot);

        TextView tag1 = new TextView(this);
        tag1.setText(" MODULE v1.0");
        tag1.setTextSize(10);
        tag1.setTextColor(DS_CTA_LIGHT);
        tag1.setGravity(Gravity.CENTER);
        tag1.setTypeface(null, android.graphics.Typeface.BOLD);
        tag1Wrap.addView(tag1);
        tagRow.addView(tag1Wrap);

        // Tag 2: 蓝色 "HOOK"
        LinearLayout tag2Wrap = new LinearLayout(this);
        tag2Wrap.setOrientation(LinearLayout.HORIZONTAL);
        tag2Wrap.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable t2Bg = new GradientDrawable();
        t2Bg.setColor(DS_CARD_ELEV);
        t2Bg.setCornerRadius(dp(100));
        t2Bg.setStroke(dp(1), DS_BORDER);
        tag2Wrap.setBackground(t2Bg);
        tag2Wrap.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams t2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2Lp.leftMargin = dp(6);
        tag2Wrap.setLayoutParams(t2Lp);

        TextView tag2 = new TextView(this);
        tag2.setText("HOOK");
        tag2.setTextSize(10);
        tag2.setTextColor(DS_TEXT);
        tag2.setGravity(Gravity.CENTER);
        tag2.setTypeface(null, android.graphics.Typeface.BOLD);
        tag2Wrap.addView(tag2);
        tagRow.addView(tag2Wrap);

        textArea.addView(tagRow);
        headerRow.addView(textArea);
        content.addView(headerRow);

        // ========== 中间状态小卡片：显示 target 信息 ==========
        // 使用带边框的暗色卡片，左右两列布局
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(0xFF0B1220); // slightly darker inset card
        infoBg.setCornerRadius(dp(14));
        infoBg.setStroke(dp(1), DS_BORDER);
        infoCard.setBackground(infoBg);
        infoCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icLp.topMargin = dp(16);
        infoCard.setLayoutParams(icLp);

        // 信息行 1：目标应用
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labelCol = new LinearLayout(this);
        labelCol.setOrientation(LinearLayout.VERTICAL);
        labelCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f));

        TextView r1Label = new TextView(this);
        r1Label.setText("TARGET_APP");
        r1Label.setTextSize(9);
        r1Label.setTextColor(DS_TEXT_DIM);
        r1Label.setTypeface(null, android.graphics.Typeface.BOLD);
        labelCol.addView(r1Label);

        TextView r1Value = new TextView(this);
        r1Value.setText(TARGET_PACKAGE);
        r1Value.setTextSize(11);
        r1Value.setTextColor(DS_CTA_LIGHT);
        r1Value.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams r1vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1vLp.topMargin = dp(2);
        r1Value.setLayoutParams(r1vLp);
        labelCol.addView(r1Value);
        row1.addView(labelCol);

        // 右侧：状态徽章
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams rColLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f);
        rightCol.setLayoutParams(rColLp);

        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        GradientDrawable spBg = new GradientDrawable();
        spBg.setColor(installed ? DS_CTA_DARK : 0xFF7F1D1D);
        spBg.setCornerRadius(dp(100));
        statusPill.setBackground(spBg);
        statusPill.setPadding(dp(10), dp(5), dp(10), dp(5));

        TextView spDot = new TextView(this);
        spDot.setText("●");
        spDot.setTextSize(8);
        spDot.setTextColor(installed ? DS_CTA_LIGHT : 0xFFFECACA);
        spDot.setGravity(Gravity.CENTER);
        statusPill.addView(spDot);

        TextView spText = new TextView(this);
        spText.setText(" " + (installed ? "已安装" : "未安装"));
        spText.setTextSize(10);
        spText.setTextColor(installed ? DS_CTA_LIGHT : 0xFFFECACA);
        spText.setTypeface(null, android.graphics.Typeface.BOLD);
        statusPill.addView(spText);
        rightCol.addView(statusPill);
        row1.addView(rightCol);

        infoCard.addView(row1);

        // 分隔线
        View sep = new View(this);
        GradientDrawable sepGd = new GradientDrawable();
        sepGd.setColor(DS_BORDER);
        sep.setBackground(sepGd);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        sepLp.topMargin = dp(12);
        sepLp.bottomMargin = dp(12);
        sep.setLayoutParams(sepLp);
        infoCard.addView(sep);

        // 信息行 2：版本号
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout r2LabelCol = new LinearLayout(this);
        r2LabelCol.setOrientation(LinearLayout.VERTICAL);
        r2LabelCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f));

        TextView r2Label = new TextView(this);
        r2Label.setText("APP_VERSION");
        r2Label.setTextSize(9);
        r2Label.setTextColor(DS_TEXT_DIM);
        r2Label.setTypeface(null, android.graphics.Typeface.BOLD);
        r2LabelCol.addView(r2Label);

        String versionName = null;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (pi != null && pi.versionName != null) versionName = pi.versionName;
        } catch (Throwable ignored) {}

        TextView r2Value = new TextView(this);
        if (versionName != null) {
            r2Value.setText("v" + versionName);
            r2Value.setTextColor(DS_CTA);
        } else {
            r2Value.setText("— not detected —");
            r2Value.setTextColor(DS_TEXT_DIM);
        }
        r2Value.setTextSize(12);
        r2Value.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams r2vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2vLp.topMargin = dp(2);
        r2Value.setLayoutParams(r2vLp);
        r2LabelCol.addView(r2Value);
        row2.addView(r2LabelCol);

        // 右侧：Module ID
        LinearLayout r2Right = new LinearLayout(this);
        r2Right.setOrientation(LinearLayout.VERTICAL);
        r2Right.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams r2rLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f);
        r2Right.setLayoutParams(r2rLp);

        TextView r2IdLabel = new TextView(this);
        r2IdLabel.setText("MODULE_ID");
        r2IdLabel.setTextSize(9);
        r2IdLabel.setTextColor(DS_TEXT_DIM);
        r2IdLabel.setGravity(Gravity.END);
        r2IdLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        r2Right.addView(r2IdLabel);

        TextView r2IdValue = new TextView(this);
        r2IdValue.setText(MODULE_PACKAGE);
        r2IdValue.setTextSize(10);
        r2IdValue.setTextColor(DS_TEXT_MUTED);
        r2IdValue.setGravity(Gravity.END);
        LinearLayout.LayoutParams r2ivLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2ivLp.topMargin = dp(2);
        r2IdValue.setLayoutParams(r2ivLp);
        r2Right.addView(r2IdValue);
        row2.addView(r2Right);

        infoCard.addView(row2);

        content.addView(infoCard);
        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 模块状态卡（UI/UX Pro Max 深色主题） ============
    private void addStatusCard(LinearLayout root, StatsData data) {
        boolean active = data != null && data.moduleActive;
        boolean autoOn = data != null && data.autoSelectEnabled;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(20));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // === 模块状态标题行 ===
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setGravity(Gravity.CENTER);
        GradientDrawable twGd = new GradientDrawable();
        twGd.setColor(active ? DS_CTA_DARK : 0xFF7F1D1D);
        twGd.setCornerRadius(dp(8));
        titleWrap.setBackground(twGd);
        titleWrap.setPadding(dp(6), dp(4), dp(6), dp(4));

        TextView tIcon = new TextView(this);
        tIcon.setText("⏚");
        tIcon.setTextSize(12);
        tIcon.setTextColor(DS_CTA_LIGHT);
        tIcon.setGravity(Gravity.CENTER);
        tIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        titleWrap.addView(tIcon);
        titleRow.addView(titleWrap);

        TextView title = new TextView(this);
        title.setText(" MODULE_STATUS");
        title.setTextSize(13);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);

        content.addView(titleRow);

        // === 状态条：深色卡片内带渐变圆角容器 ===
        LinearLayout statusBanner = new LinearLayout(this);
        statusBanner.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sbGd = new GradientDrawable();
        if (active) {
            sbGd.setColors(new int[]{DS_CTA, DS_CTA_DARK});
        } else {
            sbGd.setColors(new int[]{DS_ERROR, 0xFF991B1B});
        }
        sbGd.setCornerRadius(dp(14));
        statusBanner.setBackground(sbGd);
        statusBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(14);
        statusBanner.setLayoutParams(sbLp);

        LinearLayout sbRow = new LinearLayout(this);
        sbRow.setOrientation(LinearLayout.HORIZONTAL);
        sbRow.setGravity(Gravity.CENTER_VERTICAL);

        // 状态图标：白色圆 + 符号
        LinearLayout sbIconWrap = new LinearLayout(this);
        sbIconWrap.setGravity(Gravity.CENTER);
        GradientDrawable sbIconBg = new GradientDrawable();
        sbIconBg.setShape(GradientDrawable.OVAL);
        sbIconBg.setColor(0xFFFFFFFF);
        sbIconWrap.setBackground(sbIconBg);
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(dp(40), dp(40));
        sbIconWrap.setLayoutParams(silp);

        TextView sbIcon = new TextView(this);
        sbIcon.setText(active ? "✓" : "!");
        sbIcon.setTextSize(20);
        sbIcon.setTextColor(active ? DS_CTA_DARK : DS_ERROR);
        sbIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        sbIcon.setGravity(Gravity.CENTER);
        sbIconWrap.addView(sbIcon);
        sbRow.addView(sbIconWrap);

        // 文字
        LinearLayout sbText = new LinearLayout(this);
        sbText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stLp.leftMargin = dp(14);
        sbText.setLayoutParams(stLp);

        TextView sbTitle = new TextView(this);
        sbTitle.setText(active ? "模块已激活" : "模块未激活");
        sbTitle.setTextSize(15);
        sbTitle.setTextColor(0xFFFFFFFF);
        sbTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        sbText.addView(sbTitle);

        TextView sbDesc = new TextView(this);
        sbDesc.setText(active ? "Hook 已在目标应用中生效" : "请在 LSPosed 管理器中启用本模块");
        sbDesc.setTextSize(11);
        sbDesc.setTextColor(0xFFE5E7EB);
        LinearLayout.LayoutParams sbdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbdLp.topMargin = dp(2);
        sbDesc.setLayoutParams(sbdLp);
        sbText.addView(sbDesc);
        sbRow.addView(sbText);

        // 右侧：状态徽章（药丸形）
        LinearLayout stateBadge = new LinearLayout(this);
        stateBadge.setGravity(Gravity.CENTER);
        GradientDrawable sbgBg = new GradientDrawable();
        sbgBg.setColor(0xFFFFFFFF);
        sbgBg.setCornerRadius(dp(20));
        stateBadge.setBackground(sbgBg);
        stateBadge.setPadding(dp(12), dp(5), dp(12), dp(5));

        TextView stateBadgeText = new TextView(this);
        stateBadgeText.setText(active ? "ACTIVE" : "INACTIVE");
        stateBadgeText.setTextSize(10);
        stateBadgeText.setTextColor(active ? DS_CTA_DARK : DS_ERROR);
        stateBadgeText.setTypeface(null, android.graphics.Typeface.BOLD);
        stateBadge.addView(stateBadgeText);
        sbRow.addView(stateBadge);

        statusBanner.addView(sbRow);
        content.addView(statusBanner);

        // === 自动答题状态卡片（深色内嵌） ===
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable arGd = new GradientDrawable();
        arGd.setColor(autoOn ? 0xFF0B2218 : 0xFF1E293B);
        arGd.setCornerRadius(dp(12));
        arGd.setStroke(dp(1), autoOn ? DS_CTA_DARK : DS_BORDER);
        autoRow.setBackground(arGd);
        autoRow.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = dp(14);
        autoRow.setLayoutParams(arLp);

        // 左：小图标圆
        LinearLayout aCircle = new LinearLayout(this);
        aCircle.setGravity(Gravity.CENTER);
        GradientDrawable acGd = new GradientDrawable();
        acGd.setShape(GradientDrawable.OVAL);
        acGd.setColor(autoOn ? DS_CTA : DS_CARD_ELEV);
        aCircle.setBackground(acGd);
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        aCircle.setLayoutParams(acLp);

        TextView aEmoji = new TextView(this);
        aEmoji.setText(autoOn ? "⚡" : "⚙");
        aEmoji.setTextSize(14);
        aEmoji.setTextColor(0xFFFFFFFF);
        aEmoji.setGravity(Gravity.CENTER);
        aCircle.addView(aEmoji);
        autoRow.addView(aCircle);

        // 中间：文字
        LinearLayout autoText = new LinearLayout(this);
        autoText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        atLp.leftMargin = dp(12);
        autoText.setLayoutParams(atLp);

        TextView autoTitle = new TextView(this);
        autoTitle.setText("AUTO_ANSWER");
        autoTitle.setTextSize(11);
        autoTitle.setTextColor(autoOn ? DS_CTA_LIGHT : DS_TEXT_MUTED);
        autoTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        autoText.addView(autoTitle);

        TextView autoDesc = new TextView(this);
        autoDesc.setText(autoOn ? "已启用，自动识别并点击正确答案" : "已关闭，需手动操作");
        autoDesc.setTextSize(11);
        autoDesc.setTextColor(autoOn ? DS_TEXT : DS_TEXT_MUTED);
        LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adLp.topMargin = dp(2);
        autoDesc.setLayoutParams(adLp);
        autoText.addView(autoDesc);
        autoRow.addView(autoText);

        // 右：状态标签
        LinearLayout aBadgeWrap = new LinearLayout(this);
        aBadgeWrap.setGravity(Gravity.CENTER);
        GradientDrawable abBg = new GradientDrawable();
        abBg.setColor(autoOn ? DS_CTA_DARK : DS_CARD_ELEV);
        abBg.setCornerRadius(dp(20));
        aBadgeWrap.setBackground(abBg);
        aBadgeWrap.setPadding(dp(10), dp(5), dp(10), dp(5));

        TextView aBadge = new TextView(this);
        aBadge.setText(autoOn ? "ENABLED" : "DISABLED");
        aBadge.setTextSize(9);
        aBadge.setTextColor(0xFFFFFFFF);
        aBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        aBadgeWrap.addView(aBadge);
        autoRow.addView(aBadgeWrap);

        content.addView(autoRow);

        // === 功能亮点标题（FEATURES） ===
        LinearLayout featTitleRow = new LinearLayout(this);
        featTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        featTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ftrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ftrLp.topMargin = dp(16);
        ftrLp.bottomMargin = dp(4);
        featTitleRow.setLayoutParams(ftrLp);

        LinearLayout featTagWrap = new LinearLayout(this);
        featTagWrap.setGravity(Gravity.CENTER);
        GradientDrawable ftwGd = new GradientDrawable();
        ftwGd.setColor(DS_CARD_ELEV);
        ftwGd.setCornerRadius(dp(6));
        featTagWrap.setBackground(ftwGd);
        featTagWrap.setPadding(dp(8), dp(3), dp(8), dp(3));

        TextView featTag = new TextView(this);
        featTag.setText("FEATURES");
        featTag.setTextSize(9);
        featTag.setTextColor(DS_TEXT_MUTED);
        featTag.setTypeface(null, android.graphics.Typeface.BOLD);
        featTagWrap.addView(featTag);
        featTitleRow.addView(featTagWrap);
        content.addView(featTitleRow);

        // === 功能亮点列表（每行：彩色圆章 + 标题 + 描述） ===
        int[] iconColors = {DS_WARNING, DS_INFO, DS_CTA, DS_PURPLE, DS_SKY};
        String[] featLabels = {"答案识别", "多题型支持", "自动点击", "数据共享", "实时统计"};
        String[] featDescs = {
                "自动识别正确答案并高亮标记",
                "支持单选 / 多选 / 判断 / 填空题",
                "答案自动选中，无需手动点击",
                "通过 ContentProvider 跨进程共享",
                "实时显示命中次数与请求记录"
        };

        for (int i = 0; i < featLabels.length; i++) {
            LinearLayout hRow = new LinearLayout(this);
            hRow.setOrientation(LinearLayout.HORIZONTAL);
            hRow.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(0xFF0B1220);
            rowBg.setCornerRadius(dp(12));
            rowBg.setStroke(dp(1), DS_BORDER);
            hRow.setBackground(rowBg);
            hRow.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams hrp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hrp.topMargin = dp(8);
            hRow.setLayoutParams(hrp);

            // 左侧彩色圆章
            LinearLayout bulletBox = new LinearLayout(this);
            bulletBox.setGravity(Gravity.CENTER);
            GradientDrawable bbGd = new GradientDrawable();
            bbGd.setShape(GradientDrawable.OVAL);
            bbGd.setColor(iconColors[i]);
            bulletBox.setBackground(bbGd);
            LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(dp(28), dp(28));
            bulletBox.setLayoutParams(bblp);

            TextView bulletTv = new TextView(this);
            bulletTv.setText(String.valueOf(i + 1));
            bulletTv.setTextSize(12);
            bulletTv.setTextColor(0xFFFFFFFF);
            bulletTv.setTypeface(null, android.graphics.Typeface.BOLD);
            bulletTv.setGravity(Gravity.CENTER);
            bulletBox.addView(bulletTv);
            hRow.addView(bulletBox);

            // 文字
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tclp.leftMargin = dp(12);
            textCol.setLayoutParams(tclp);

            TextView rowTitle = new TextView(this);
            rowTitle.setText(featLabels[i]);
            rowTitle.setTextSize(13);
            rowTitle.setTextColor(DS_TEXT);
            rowTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(rowTitle);

            TextView rowDesc = new TextView(this);
            rowDesc.setText(featDescs[i]);
            rowDesc.setTextSize(11);
            rowDesc.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rdLp.topMargin = dp(2);
            rowDesc.setLayoutParams(rdLp);
            textCol.addView(rowDesc);

            hRow.addView(textCol);

            // 右：小圆形装饰点
            LinearLayout dotBox = new LinearLayout(this);
            dotBox.setGravity(Gravity.CENTER);
            GradientDrawable dotGd = new GradientDrawable();
            dotGd.setShape(GradientDrawable.OVAL);
            dotGd.setColor(iconColors[i]);
            dotBox.setBackground(dotGd);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotBox.setLayoutParams(dotLp);
            hRow.addView(dotBox);

            content.addView(hRow);
        }

        // 底部提示（未激活时显示）
        if (!active) {
            LinearLayout tipBox = new LinearLayout(this);
            tipBox.setOrientation(LinearLayout.HORIZONTAL);
            tipBox.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable tipGd = new GradientDrawable();
            tipGd.setColor(0xFF1C0F0F);
            tipGd.setCornerRadius(dp(12));
            tipGd.setStroke(dp(1), 0xFF7F1D1D);
            tipBox.setBackground(tipGd);
            tipBox.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tipLp.topMargin = dp(16);
            tipBox.setLayoutParams(tipLp);

            // 左：三角形警示图标
            LinearLayout tiWrap = new LinearLayout(this);
            tiWrap.setGravity(Gravity.CENTER);
            GradientDrawable tiGd = new GradientDrawable();
            tiGd.setColor(0xFF7F1D1D);
            tiGd.setCornerRadius(dp(8));
            tiWrap.setBackground(tiGd);
            tiWrap.setPadding(dp(6), dp(5), dp(6), dp(5));
            TextView tiIcon = new TextView(this);
            tiIcon.setText("!");
            tiIcon.setTextSize(12);
            tiIcon.setTextColor(0xFFFECACA);
            tiIcon.setTypeface(null, android.graphics.Typeface.BOLD);
            tiIcon.setGravity(Gravity.CENTER);
            tiWrap.addView(tiIcon);
            tipBox.addView(tiWrap);

            TextView tipText = new TextView(this);
            tipText.setText("请在 LSPosed 管理器中启用本模块，作用域需包含：\n• "
                    + TARGET_PACKAGE + "\n• " + MODULE_PACKAGE);
            tipText.setTextSize(11);
            tipText.setTextColor(0xFFFECACA);
            tipText.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ttLp.leftMargin = dp(10);
            tipText.setLayoutParams(ttLp);
            tipBox.addView(tipText);

            content.addView(tipBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 快捷操作卡（UI/UX Pro Max 深色主题） ============
    private void addActionCard(LinearLayout root, StatsData data) {
        final boolean[] toggleState = {data != null && data.autoSelectEnabled};

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(20));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // 标题：QUICK_ACTIONS 标签
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setGravity(Gravity.CENTER);
        GradientDrawable twGd = new GradientDrawable();
        twGd.setColor(DS_CTA_DARK);
        twGd.setCornerRadius(dp(8));
        titleWrap.setBackground(twGd);
        titleWrap.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView tIcon = new TextView(this);
        tIcon.setText("▶");
        tIcon.setTextSize(10);
        tIcon.setTextColor(DS_CTA_LIGHT);
        tIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        tIcon.setGravity(Gravity.CENTER);
        titleWrap.addView(tIcon);
        titleRow.addView(titleWrap);

        TextView header = new TextView(this);
        header.setText(" QUICK_ACTIONS");
        header.setTextSize(13);
        header.setTextColor(DS_TEXT);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(header);
        content.addView(titleRow);

        // ============ 自动答题开关卡片（深色主题 + iOS 风格滑动开关） ============
        final LinearLayout toggleCard = new LinearLayout(this);
        toggleCard.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable toggleBg = new GradientDrawable();
        if (toggleState[0]) {
            toggleBg.setColors(new int[]{DS_CTA, DS_CTA_DARK});
        } else {
            toggleBg.setColors(new int[]{0xFF334155, 0xFF1E293B});
        }
        toggleBg.setCornerRadius(dp(16));
        toggleCard.setBackground(toggleBg);
        toggleCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tcLp.topMargin = dp(14);
        toggleCard.setLayoutParams(tcLp);

        // 开关卡片主行：左图标 / 中文字 / 右滑动开关
        LinearLayout toggleMain = new LinearLayout(this);
        toggleMain.setOrientation(LinearLayout.HORIZONTAL);
        toggleMain.setGravity(Gravity.CENTER_VERTICAL);

        // 左：白色圆形图标
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable();
        ibGd.setShape(GradientDrawable.OVAL);
        ibGd.setColor(0xFFFFFFFF);
        iconBox.setBackground(ibGd);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconBox.setLayoutParams(ibLp);

        TextView iconEmoji = new TextView(this);
        iconEmoji.setText("⚡");
        iconEmoji.setTextSize(22);
        iconEmoji.setGravity(Gravity.CENTER);
        iconBox.addView(iconEmoji);
        toggleMain.addView(iconBox);

        // 中：标题 + 描述
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcolLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcolLp.leftMargin = dp(14);
        textCol.setLayoutParams(tcolLp);

        final TextView toggleTitle = new TextView(this);
        toggleTitle.setText("AUTO_ANSWER");
        toggleTitle.setTextSize(14);
        toggleTitle.setTextColor(0xFFFFFFFF);
        toggleTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(toggleTitle);

        final TextView toggleDesc = new TextView(this);
        toggleDesc.setText(toggleState[0] ? "自动识别并点击正确答案" : "点击右侧开关开启此功能");
        toggleDesc.setTextSize(11);
        toggleDesc.setTextColor(0xFFE5E7EB);
        LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tdLp.topMargin = dp(3);
        toggleDesc.setLayoutParams(tdLp);
        textCol.addView(toggleDesc);

        toggleMain.addView(textCol);

        // ====== 右：iOS 风格滑动开关（FrameLayout + gravity 控制左右滑动） ======
        final android.widget.FrameLayout switchTrack = new android.widget.FrameLayout(this);
        final GradientDrawable scGd = new GradientDrawable();
        scGd.setColor(toggleState[0] ? 0xFFFFFFFF : 0xFF64748B);
        scGd.setCornerRadius(dp(18));
        switchTrack.setBackground(scGd);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(dp(60), dp(32));
        switchTrack.setLayoutParams(scLp);

        final View switchThumb = new View(this);
        final GradientDrawable thumbGd = new GradientDrawable();
        thumbGd.setShape(GradientDrawable.OVAL);
        thumbGd.setColor(toggleState[0] ? DS_CTA : 0xFFCBD5E1);
        thumbGd.setStroke(dp(2), 0xFFFFFFFF);
        switchThumb.setBackground(thumbGd);
        final android.widget.FrameLayout.LayoutParams thumbLp =
                new android.widget.FrameLayout.LayoutParams(dp(28), dp(28),
                        toggleState[0] ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                                        : (Gravity.LEFT | Gravity.CENTER_VERTICAL));
        thumbLp.setMargins(dp(2), dp(2), dp(2), dp(2));
        switchThumb.setLayoutParams(thumbLp);
        switchTrack.addView(switchThumb);
        toggleMain.addView(switchTrack);

        toggleCard.addView(toggleMain);

        // 底部状态条
        final LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable srGd = new GradientDrawable();
        srGd.setColor(0x22FFFFFF);
        srGd.setCornerRadius(dp(10));
        statusRow.setBackground(srGd);
        statusRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(12);
        statusRow.setLayoutParams(srLp);

        final TextView statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(10);
        statusDot.setTextColor(toggleState[0] ? DS_CTA_LIGHT : 0xFF94A3B8);
        statusDot.setGravity(Gravity.CENTER);
        statusRow.addView(statusDot);

        final TextView statusText = new TextView(this);
        statusText.setText(toggleState[0] ? " 功能已启用，自动答题生效中" : " 功能已关闭，点击上方开关开启");
        statusText.setTextSize(11);
        statusText.setTextColor(0xFFE5E7EB);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stLp.leftMargin = dp(4);
        statusText.setLayoutParams(stLp);
        statusRow.addView(statusText);

        toggleCard.addView(statusRow);

        // 切换逻辑
        toggleCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleState[0] = !toggleState[0];

                if (toggleState[0]) {
                    toggleBg.setColors(new int[]{DS_CTA, DS_CTA_DARK});
                } else {
                    toggleBg.setColors(new int[]{0xFF334155, 0xFF1E293B});
                }

                toggleDesc.setText(toggleState[0] ? "自动识别并点击正确答案" : "点击右侧开关开启此功能");
                statusText.setText(toggleState[0] ? " 功能已启用，自动答题生效中" : " 功能已关闭，点击上方开关开启");
                statusDot.setTextColor(toggleState[0] ? DS_CTA_LIGHT : 0xFF94A3B8);

                // 更新 iOS 开关（gravity 控制左右滑动位置）
                scGd.setColor(toggleState[0] ? 0xFFFFFFFF : 0xFF64748B);
                thumbGd.setColor(toggleState[0] ? DS_CTA : 0xFFCBD5E1);
                thumbGd.setStroke(dp(2), 0xFFFFFFFF);
                thumbLp.gravity = toggleState[0] ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                                                  : (Gravity.LEFT | Gravity.CENTER_VERTICAL);
                switchThumb.setLayoutParams(thumbLp);
                switchThumb.requestLayout();

                // 保存状态
                try {
                    ContentValues values = new ContentValues();
                    values.put("auto_select_enabled", toggleState[0]);
                    getContentResolver().update(
                            Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"),
                            values, null, null);
                } catch (Throwable ignored) {}
                try {
                    SharedPreferences sp = getSharedPreferences("module_stats", MODE_PRIVATE);
                    sp.edit().putBoolean("auto_select_enabled", toggleState[0]).apply();
                } catch (Throwable ignored) {}

                Toast.makeText(MainActivity.this,
                        toggleState[0] ? "✓ 自动答题已开启" : "自动答题已关闭",
                        Toast.LENGTH_SHORT).show();
            }
        });

        content.addView(toggleCard);

        // 主按钮：RUN / 启动目标应用（绿色主按钮）
        Button launchBtn = makeActionButton("▶ RUN 启动目标应用",
                new int[]{DS_CTA, DS_CTA_DARK},
                DS_TEXT, true, false);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp(14);
        launchBtn.setLayoutParams(lbLp);
        launchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { launchTargetApp(); }
        });
        content.addView(launchBtn);

        // 并排按钮：刷新 + 清空（深色内嵌卡片风格）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(10);
        btnRow.setLayoutParams(brLp);

        Button refreshBtn = makeActionButton("↻ 刷新数据",
                new int[]{0xFF0F172A, 0xFF1E293B},
                DS_TEXT_MUTED, false, true);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rLp.rightMargin = dp(8);
        refreshBtn.setLayoutParams(rLp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCurrentPage = 0;
                refreshStatsAsync();
                Toast.makeText(MainActivity.this, "数据已刷新", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(refreshBtn);

        Button clearBtn = makeActionButton("✕ 清空统计",
                new int[]{0xFF0F172A, 0xFF1E293B},
                DS_TEXT_MUTED, false, true);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clearBtn.setLayoutParams(cLp);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
                    boolean savedAuto = selfSp.getBoolean("auto_select_enabled", false);
                    try {
                        getContentResolver().delete(
                                Uri.parse("content://" + MODULE_PACKAGE + ".stats/clear"),
                                null, null);
                    } catch (Throwable ignored) {}
                    selfSp.edit().clear().putBoolean("auto_select_enabled", savedAuto).apply();
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put("auto_select_enabled", savedAuto);
                        getContentResolver().update(
                                Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"),
                                cv, null, null);
                    } catch (Throwable ignored) {}
                    Toast.makeText(MainActivity.this, "已清空统计", Toast.LENGTH_SHORT).show();
                    mCurrentPage = 0;
                    refreshStatsAsync();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "清空失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnRow.addView(clearBtn);
        content.addView(btnRow);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // 辅助方法：深色主题操作按钮
    private Button makeActionButton(String text, int[] colors, int textColor, boolean main, boolean subtle) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(main ? 15 : 13);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColors(colors);
        bg.setCornerRadius(dp(14));
        if (subtle) {
            bg.setStroke(dp(1), DS_BORDER);
        }
        btn.setBackground(bg);
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));
        return btn;
    }

    // ============ 统计数据卡（深色主题） ============
    private void addStatsCard(LinearLayout root, StatsData data) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(20));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // 标题：STATS 标签
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setGravity(Gravity.CENTER);
        GradientDrawable twGd = new GradientDrawable();
        twGd.setColor(0xFF1E3A8A);
        twGd.setCornerRadius(dp(8));
        titleWrap.setBackground(twGd);
        titleWrap.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView ti = new TextView(this);
        ti.setText("◆");
        ti.setTextSize(10);
        ti.setTextColor(DS_CTA);
        ti.setTypeface(null, android.graphics.Typeface.BOLD);
        titleWrap.addView(ti);
        titleRow.addView(titleWrap);

        TextView title = new TextView(this);
        title.setText(" STATS");
        title.setTextSize(13);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);
        content.addView(titleRow);

        int hh = data != null ? data.targetHitCount : -1;
        int rc = data != null ? data.requestCount : -1;
        long lt = data != null ? data.lastHookTime : -1;
        boolean hasAnyData = (hh >= 0 || rc >= 0 || lt > 0);

        // 列表形式：每行 = 左彩色渐变圆章 + 中文字 + 右数值
        String[] labels = {"HIT_COUNT", "REQUESTS", "LAST_SEEN"};
        String[] subLabels = {"答案命中", "请求总数", "最近活跃"};
        String[] values = {
                hh < 0 ? "—" : String.valueOf(hh) + "x",
                rc < 0 ? "—" : String.valueOf(rc) + "x",
                lt <= 0 ? "—" : formatTime(lt)
        };
        int[][] colorSets = {
                {DS_CTA, DS_CTA_DARK},        // 绿色 CTA
                {0xFF3B82F6, 0xFF1E40AF},    // 蓝色
                {0xFF8B5CF6, 0xFF6D28D9}     // 紫色
        };
        String[] emojis = {"◉", "◎", "◈"};

        // 列表容器
        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lwLp.topMargin = dp(14);
        listWrap.setLayoutParams(lwLp);

        for (int i = 0; i < labels.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(0xFF0F172A);
            rowBg.setCornerRadius(dp(14));
            rowBg.setStroke(dp(1), DS_BORDER);
            row.setBackground(rowBg);
            row.setPadding(dp(12), dp(12), dp(14), dp(12));

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rLp.topMargin = dp(8);
            row.setLayoutParams(rLp);

            // 左：彩色渐变圆章
            LinearLayout circleWrap = new LinearLayout(this);
            circleWrap.setGravity(Gravity.CENTER);
            GradientDrawable cBg = new GradientDrawable();
            cBg.setShape(GradientDrawable.OVAL);
            cBg.setColors(colorSets[i]);
            circleWrap.setBackground(cBg);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            circleWrap.setLayoutParams(cLp);

            TextView cEmoji = new TextView(this);
            cEmoji.setText(emojis[i]);
            cEmoji.setTextSize(18);
            cEmoji.setTextColor(0xFFFFFFFF);
            cEmoji.setGravity(Gravity.CENTER);
            cEmoji.setTypeface(null, android.graphics.Typeface.BOLD);
            circleWrap.addView(cEmoji);
            row.addView(circleWrap);

            // 中：标签 + 中文副标题
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tcLp.leftMargin = dp(12);
            textCol.setLayoutParams(tcLp);

            TextView labelTv = new TextView(this);
            labelTv.setText(labels[i]);
            labelTv.setTextSize(13);
            labelTv.setTextColor(DS_TEXT);
            labelTv.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(labelTv);

            TextView hintTv = new TextView(this);
            hintTv.setText(subLabels[i] + " · " + (hasAnyData ? "已同步" : "等待中"));
            hintTv.setTextSize(10);
            hintTv.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hLp.topMargin = dp(2);
            hintTv.setLayoutParams(hLp);
            textCol.addView(hintTv);

            row.addView(textCol);

            // 右：数值卡片
            LinearLayout valueBox = new LinearLayout(this);
            valueBox.setOrientation(LinearLayout.VERTICAL);
            valueBox.setGravity(Gravity.CENTER);
            GradientDrawable vBg = new GradientDrawable();
            vBg.setColor(DS_CARD);
            vBg.setCornerRadius(dp(10));
            vBg.setStroke(dp(1), DS_BORDER);
            valueBox.setBackground(vBg);
            valueBox.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.addView(valueBox);

            TextView valueTv = new TextView(this);
            valueTv.setText(values[i]);
            valueTv.setTextSize(13);
            valueTv.setTextColor(DS_CTA);
            valueTv.setGravity(Gravity.CENTER);
            valueTv.setTypeface(null, android.graphics.Typeface.BOLD);
            valueBox.addView(valueTv);

            listWrap.addView(row);
        }

        content.addView(listWrap);

        // 空状态：底部小提示卡（深色主题）
        if (!hasAnyData) {
            LinearLayout tipBox = new LinearLayout(this);
            tipBox.setOrientation(LinearLayout.HORIZONTAL);
            tipBox.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable tipGd = new GradientDrawable();
            tipGd.setColor(0xFF1E293B);
            tipGd.setCornerRadius(dp(12));
            tipGd.setStroke(dp(1), 0xFF334155);
            tipBox.setBackground(tipGd);
            tipBox.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tipLp.topMargin = dp(12);
            tipBox.setLayoutParams(tipLp);

            TextView tipIcon = new TextView(this);
            tipIcon.setText("◈");
            tipIcon.setTextSize(12);
            tipIcon.setTextColor(DS_CTA);
            tipIcon.setGravity(Gravity.CENTER);
            tipIcon.setTypeface(null, android.graphics.Typeface.BOLD);
            tipBox.addView(tipIcon);

            TextView tipText = new TextView(this);
            tipText.setText("  请先打开目标应用，进入答题页面后返回刷新");
            tipText.setTextSize(11);
            tipText.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ttLp.leftMargin = dp(4);
            tipText.setLayoutParams(ttLp);
            tipBox.addView(tipText);

            content.addView(tipBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 统计数字盒 ============
    private LinearLayout makeStatBox(String label, String value, int[] bgColors, int textColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColors(bgColors);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(8), dp(14), dp(8), dp(14));

        TextView valTv = new TextView(this);
        valTv.setText(value);
        valTv.setTextSize(18);
        valTv.setTextColor(textColor);
        valTv.setGravity(Gravity.CENTER);
        valTv.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(valTv);

        TextView lblTv = new TextView(this);
        lblTv.setText(label);
        lblTv.setTextSize(10);
        lblTv.setTextColor(textColor);
        lblTv.setAlpha(0.9f);
        lblTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lblTv.setLayoutParams(lp);
        box.addView(lblTv);

        return box;
    }

    // ============ HTTP 客户端检测列表（深色主题） ============
    private void addHttpClientsCard(LinearLayout root, StatsData data) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(20));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // 标题
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setGravity(Gravity.CENTER);
        GradientDrawable twGd = new GradientDrawable();
        twGd.setColor(0xFF0E7490);
        twGd.setCornerRadius(dp(8));
        titleWrap.setBackground(twGd);
        titleWrap.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView ti = new TextView(this);
        ti.setText("◆");
        ti.setTextSize(10);
        ti.setTextColor(DS_CTA);
        ti.setTypeface(null, android.graphics.Typeface.BOLD);
        titleWrap.addView(ti);
        titleRow.addView(titleWrap);

        TextView title = new TextView(this);
        title.setText(" HTTP_CLIENTS");
        title.setTextSize(13);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);
        content.addView(titleRow);

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
            count.setText("● 已发现 " + clients.size() + " 个类");
            count.setTextSize(11);
            count.setTextColor(DS_CTA);
            count.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(12);
            content.addView(count, cp);

            for (int i = 0; i < clients.size(); i++) {
                View row = makeListItem(i + 1, clients.get(i), null, 0xFF06B6D4, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                content.addView(row, lp);
            }
        } else {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            GradientDrawable ebGd = new GradientDrawable();
            ebGd.setColor(0xFF0F172A);
            ebGd.setCornerRadius(dp(12));
            ebGd.setStroke(dp(1), DS_BORDER);
            emptyBox.setBackground(ebGd);
            emptyBox.setPadding(dp(16), dp(16), dp(16), dp(16));
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ebLp.topMargin = dp(12);
            emptyBox.setLayoutParams(ebLp);

            TextView ei = new TextView(this);
            ei.setText("◯");
            ei.setTextSize(22);
            ei.setTextColor(DS_TEXT_MUTED);
            ei.setGravity(Gravity.CENTER);
            ei.setTypeface(null, android.graphics.Typeface.BOLD);
            emptyBox.addView(ei);

            TextView empty = new TextView(this);
            empty.setText("\n尚未检测到 HTTP 客户端\n\n请先打开目标应用并进入答题页面");
            empty.setTextSize(11);
            empty.setTextColor(DS_TEXT_MUTED);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            empty.setLayoutParams(ep);
            emptyBox.addView(empty);

            content.addView(emptyBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 最近请求记录（深色主题） ============
    private void addRecentRequestsCard(LinearLayout root, StatsData data) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(20));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // 标题
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setGravity(Gravity.CENTER);
        GradientDrawable twGd = new GradientDrawable();
        twGd.setColor(0xFF6D28D9);
        twGd.setCornerRadius(dp(8));
        titleWrap.setBackground(twGd);
        titleWrap.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView ti = new TextView(this);
        ti.setText("◆");
        ti.setTextSize(10);
        ti.setTextColor(DS_CTA);
        ti.setTypeface(null, android.graphics.Typeface.BOLD);
        titleWrap.addView(ti);
        titleRow.addView(titleWrap);

        TextView title = new TextView(this);
        title.setText(" REQUEST_LOG");
        title.setTextSize(13);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);
        content.addView(titleRow);

        List<RequestItem> requests = data != null ? data.requests : null;

        if (requests != null && !requests.isEmpty()) {
            TextView count = new TextView(this);
            count.setText("● 共 " + requests.size() + " 条记录");
            count.setTextSize(11);
            count.setTextColor(DS_CTA);
            count.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(12);
            content.addView(count, cp);

            // 分页
            int totalPages = (requests.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            if (mCurrentPage >= totalPages) mCurrentPage = totalPages - 1;
            if (mCurrentPage < 0) mCurrentPage = 0;

            int startIdx = mCurrentPage * PAGE_SIZE;
            int endIdx = Math.min(startIdx + PAGE_SIZE, requests.size());

            TextView rangeTv = new TextView(this);
            rangeTv.setText("第 " + (startIdx + 1) + "-" + endIdx + " 条 / 第 " + (mCurrentPage + 1) + " 页 / 共 " + totalPages + " 页");
            rangeTv.setTextSize(10);
            rangeTv.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            content.addView(rangeTv, rlp);

            for (int i = startIdx; i < endIdx; i++) {
                final RequestItem item = requests.get(i);
                final int idx = i + 1;
                int color = 0xFF3B82F6;
                if (item.type != null) {
                    if (item.type.contains("WEBVIEW")) color = 0xFF06B6D4;
                    else if (item.type.contains("OKHTTP")) color = 0xFF3B82F6;
                    else if (item.type.contains("URL")) color = DS_CTA;
                    else if (item.type.contains("SOCKET")) color = 0xFFEF4444;
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
                lp.topMargin = dp(8);
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

            Button prevBtn = makeActionButton("◀ PREV",
                    new int[]{DS_CTA, DS_CTA_DARK}, DS_TEXT, false, false);
            LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            prevLp.rightMargin = dp(8);
            prevBtn.setLayoutParams(prevLp);
            if (hasPrev) {
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
                GradientDrawable disabledBg = new GradientDrawable();
                disabledBg.setColor(0xFF334155);
                disabledBg.setCornerRadius(dp(14));
                prevBtn.setBackground(disabledBg);
                prevBtn.setEnabled(false);
            }
            pagerRow.addView(prevBtn);

            Button nextBtn = makeActionButton("NEXT ▶",
                    new int[]{DS_CTA, DS_CTA_DARK}, DS_TEXT, false, false);
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nextBtn.setLayoutParams(nextLp);
            if (hasNext) {
                nextBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mCurrentPage++;
                        renderUI();
                    }
                });
            } else {
                GradientDrawable disabledBg = new GradientDrawable();
                disabledBg.setColor(0xFF334155);
                disabledBg.setCornerRadius(dp(14));
                nextBtn.setBackground(disabledBg);
                nextBtn.setEnabled(false);
            }
            pagerRow.addView(nextBtn);
        } else {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            GradientDrawable ebGd = new GradientDrawable();
            ebGd.setColor(0xFF0F172A);
            ebGd.setCornerRadius(dp(12));
            ebGd.setStroke(dp(1), DS_BORDER);
            emptyBox.setBackground(ebGd);
            emptyBox.setPadding(dp(16), dp(16), dp(16), dp(16));
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ebLp.topMargin = dp(12);
            emptyBox.setLayoutParams(ebLp);

            TextView ei = new TextView(this);
            ei.setText("◯");
            ei.setTextSize(22);
            ei.setTextColor(DS_TEXT_MUTED);
            ei.setGravity(Gravity.CENTER);
            ei.setTypeface(null, android.graphics.Typeface.BOLD);
            emptyBox.addView(ei);

            TextView empty = new TextView(this);
            empty.setText("\n尚未捕获任何请求\n\n请先打开目标应用并进入答题页面");
            empty.setTextSize(11);
            empty.setTextColor(DS_TEXT_MUTED);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            empty.setLayoutParams(ep);
            emptyBox.addView(empty);

            content.addView(emptyBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 列表行（深色主题） ============
    private View makeListItem(int index, String mainText, String typeLabel, int color, boolean truncate) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), DS_BORDER);
        row.setBackground(bg);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        // 序号圆章
        LinearLayout circle = new LinearLayout(this);
        GradientDrawable circBg = new GradientDrawable();
        circBg.setShape(GradientDrawable.OVAL);
        circBg.setColor(color);
        circle.setBackground(circBg);
        circle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams circLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        circLp.setMargins(0, 0, dp(12), 0);
        circle.setLayoutParams(circLp);

        TextView num = new TextView(this);
        num.setText(String.valueOf(index));
        num.setTextSize(12);
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

        String displayText = mainText;
        boolean isLong = truncate && mainText != null && mainText.length() > URL_PREVIEW_LENGTH;
        if (isLong) {
            displayText = mainText.substring(0, URL_PREVIEW_LENGTH) + "...";
        }

        TextView mainTv = new TextView(this);
        mainTv.setText(displayText);
        mainTv.setTextSize(11);
        mainTv.setTextColor(DS_TEXT);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (typeLabel != null && !typeLabel.isEmpty()) mtp.topMargin = dp(2);
        textArea.addView(mainTv, mtp);

        if (isLong) {
            TextView more = new TextView(this);
            more.setText("▸ 点击查看完整内容 (" + mainText.length() + " 字符)");
            more.setTextSize(10);
            more.setTextColor(DS_CTA);
            more.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mrp.topMargin = dp(4);
            textArea.addView(more, mrp);
        }

        row.addView(textArea);

        if (truncate) {
            TextView arrow = new TextView(this);
            arrow.setText(" ›");
            arrow.setTextSize(20);
            arrow.setTextColor(DS_TEXT_MUTED);
            arrow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.setMargins(dp(6), 0, 0, 0);
            arrow.setLayoutParams(alp);
            row.addView(arrow);
        }

        return row;
    }

    // ============ 请求详情弹窗（深色主题） ============
    private void showRequestDetail(RequestItem item, int index) {
        int color = DS_CTA;
        if (item.type != null) {
            if (item.type.contains("WEBVIEW")) color = 0xFF06B6D4;
            else if (item.type.contains("OKHTTP")) color = 0xFF3B82F6;
            else if (item.type.contains("URL")) color = DS_CTA;
            else if (item.type.contains("SOCKET")) color = 0xFFEF4444;
        }

        ScrollView sv = new ScrollView(this);
        sv.setPadding(0, 0, 0, 0);
        sv.setBackgroundColor(DS_BG);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(12));
        container.setBackgroundColor(DS_BG);

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
        timeLabel.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        headerTexts.addView(timeLabel, tlp);

        header.addView(headerTexts);

        // 分隔线
        View divider = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(DS_BORDER);
        divider.setBackground(dd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.bottomMargin = dp(12);
        container.addView(divider, dlp);

        TextView urlLabel = new TextView(this);
        urlLabel.setText("▸ 完整请求 URL");
        urlLabel.setTextSize(12);
        urlLabel.setTextColor(DS_CTA);
        urlLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams ullp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ullp.bottomMargin = dp(8);
        container.addView(urlLabel, ullp);

        TextView urlContent = new TextView(this);
        urlContent.setText(item.url != null ? item.url : "(空)");
        urlContent.setTextSize(12);
        urlContent.setTextColor(DS_TEXT);
        urlContent.setAutoLinkMask(Linkify.WEB_URLS);
        urlContent.setLinksClickable(true);
        urlContent.setLineSpacing(dp(2), 1f);
        GradientDrawable urlBox = new GradientDrawable();
        urlBox.setColor(DS_CARD);
        urlBox.setCornerRadius(dp(8));
        urlBox.setStroke(dp(1), DS_BORDER);
        urlContent.setBackground(urlBox);
        urlContent.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams uclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uclp.bottomMargin = dp(10);
        container.addView(urlContent, uclp);

        TextView lengthTv = new TextView(this);
        lengthTv.setText("字符长度: " + (item.url != null ? item.url.length() : 0));
        lengthTv.setTextSize(11);
        lengthTv.setTextColor(DS_TEXT_MUTED);
        lengthTv.setGravity(Gravity.RIGHT);
        container.addView(lengthTv);

        sv.addView(container);

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

        try {
            Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            posBtn.setTextColor(DS_CTA);
            posBtn.setTextSize(13);
            Button negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            negBtn.setTextColor(DS_TEXT_MUTED);
            negBtn.setTextSize(13);
        } catch (Throwable ignored) {}
    }

    // ============ 工作原理说明卡（深色主题） ============
    private void addInfoCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(20));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // 标题
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setGravity(Gravity.CENTER);
        GradientDrawable twGd = new GradientDrawable();
        twGd.setColor(0xFF78350F);
        twGd.setCornerRadius(dp(8));
        titleWrap.setBackground(twGd);
        titleWrap.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView ti = new TextView(this);
        ti.setText("◆");
        ti.setTextSize(10);
        ti.setTextColor(DS_CTA);
        ti.setTypeface(null, android.graphics.Typeface.BOLD);
        titleWrap.addView(ti);
        titleRow.addView(titleWrap);

        TextView title = new TextView(this);
        title.setText(" HOW_IT_WORKS");
        title.setTextSize(13);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);
        content.addView(titleRow);

        String[] steps = {
                "LSPosed 在目标应用启动时注入 Hook 代码，监听网络请求",
                "拦截 WebView / OkHttp 请求，解析返回的 JSON 数据",
                "识别题目正确答案后，在页面上高亮标记并自动点击",
                "统计数据通过 ContentProvider 跨进程传递给本模块",
                "本模块读取并展示统计、请求历史等信息"
        };

        int[] stepColors = {0xFF6366F1, 0xFF8B5CF6, 0xFFA855F7, 0xFFD946EF, 0xFFEC4899};

        for (int i = 0; i < steps.length; i++) {
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.HORIZONTAL);
            stepRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srlp.topMargin = dp(10);
            stepRow.setLayoutParams(srlp);

            LinearLayout numCircle = new LinearLayout(this);
            numCircle.setGravity(Gravity.CENTER);
            GradientDrawable numGd = new GradientDrawable();
            numGd.setShape(GradientDrawable.OVAL);
            numGd.setColor(stepColors[i]);
            numCircle.setBackground(numGd);
            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(dp(26), dp(26));
            numCircle.setLayoutParams(numLp);

            TextView numTv = new TextView(this);
            numTv.setText(String.valueOf(i + 1));
            numTv.setTextSize(12);
            numTv.setTextColor(0xFFFFFFFF);
            numTv.setTypeface(null, android.graphics.Typeface.BOLD);
            numTv.setGravity(Gravity.CENTER);
            numCircle.addView(numTv);
            stepRow.addView(numCircle);

            TextView stepText = new TextView(this);
            stepText.setText(steps[i]);
            stepText.setTextSize(12);
            stepText.setTextColor(DS_TEXT);
            LinearLayout.LayoutParams stlLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            stlLp.leftMargin = dp(12);
            stepText.setLayoutParams(stlLp);
            stepRow.addView(stepText);

            content.addView(stepRow);
        }

        // 底部提示
        LinearLayout tipBox = new LinearLayout(this);
        tipBox.setOrientation(LinearLayout.HORIZONTAL);
        tipBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable tipGd = new GradientDrawable();
        tipGd.setColor(0xFF0F172A);
        tipGd.setCornerRadius(dp(10));
        tipGd.setStroke(dp(1), DS_BORDER);
        tipBox.setBackground(tipGd);
        tipBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(14);
        tipBox.setLayoutParams(tipLp);

        TextView tipIcon = new TextView(this);
        tipIcon.setText("◈");
        tipIcon.setTextSize(12);
        tipIcon.setTextColor(DS_CTA);
        tipIcon.setGravity(Gravity.CENTER);
        tipIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        tipBox.addView(tipIcon);

        TextView tipTv = new TextView(this);
        tipTv.setText("  每页显示 " + PAGE_SIZE + " 条请求记录，点击分页按钮翻页浏览历史");
        tipTv.setTextSize(11);
        tipTv.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttLp.leftMargin = dp(4);
        tipTv.setLayoutParams(ttLp);
        tipBox.addView(tipTv);

        content.addView(tipBox);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 工具方法 ============
    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), DS_BORDER);
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
