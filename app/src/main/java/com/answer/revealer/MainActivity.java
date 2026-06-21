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

    // ============ UI/UX Pro Max 设计系统 V2 ============
    // Pattern: Playful Warmth | Style: Soft Gradient + Pastel (完全推翻前版)
    // 主题：暖米色背景 + 柔粉/天蓝/柠檬黄 点缀 (warm SaaS / playful utility)
    private static final int DS_BG           = 0xFFFFF7ED; // warm cream, 页面主背景
    private static final int DS_CARD         = 0xFFFFFFFF; // pure white, 卡片表面
    private static final int DS_CARD_SOFT    = 0xFFFBF5F3; // stone-50, 柔和副卡
    private static final int DS_BORDER       = 0xFFF0E6E0; // soft warm border
    private static final int DS_BORDER_2     = 0xFFE4D5CE; // warmer border line
    private static final int DS_TEXT         = 0xFF1F1B16; // dark warm brown, 主文字
    private static final int DS_TEXT_MUTED   = 0xFF8B7866; // warm brown-500, 次要文字
    private static final int DS_TEXT_DIM     = 0xFFB8A898; // warm grey, 三级文字
    private static final int DS_ACCENT       = 0xFFEC4899; // pink-500, 主强调色
    private static final int DS_ACCENT_LIGHT = 0xFFFCE7F3; // pink-50, 主强调浅
    private static final int DS_ACCENT_DARK  = 0xFFBE185D; // pink-700, 主强调深
    private static final int DS_BLUE         = 0xFF3B82F6; // blue-500, 辅蓝
    private static final int DS_BLUE_LIGHT   = 0xFFEFF6FF; // blue-50, 蓝浅
    private static final int DS_BLUE_DARK    = 0xFF1D4ED8; // blue-700, 蓝深
    private static final int DS_YELLOW       = 0xFFF59E0B; // amber-500, 柠檬黄
    private static final int DS_YELLOW_LIGHT = 0xFFFEF3C7; // amber-100, 黄浅
    private static final int DS_ERROR        = 0xFFE11D48; // rose-600
    private static final int DS_ERROR_LIGHT  = 0xFFFFE4E6; // rose soft
    private static final int DS_SUCCESS      = 0xFF059669; // emerald-600
    private static final int DS_SUCCESS_LIGHT= 0xFFD1FAE5; // emerald soft
    private static final int DS_VIOLET       = 0xFF8B5CF6; // violet-500
    private static final int DS_TEAL         = 0xFF14B8A6; // teal-500

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

    // ============ 主渲染 V2 ============
    private void renderUI() {
        int pad = dp(18);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(DS_BG);
        scrollView.setPadding(0, dp(6), 0, dp(40));
        scrollView.setId(android.R.id.list);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(10), pad, pad);

        // Hero 顶部（渐变标题 + 目标应用标识）
        addHeader(root);

        // 模块状态（双列状态面板 + 自动答题 + 功能亮点）
        addStatusCard(root, mData);

        // 快捷操作（自定义开关 + 渐变操作按钮）
        addActionCard(root, mData);

        // 统计数据（多列小卡片 + 数值徽章）
        addStatsCard(root, mData);

        // HTTP 客户端（芯片标签 + 列表）
        addHttpClientsCard(root, mData);

        // 最近请求（时间轴卡片）
        addRecentRequestsCard(root, mData);

        // 工作原理（数字阶梯卡）
        addInfoCard(root);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    // ============ Hero 顶部 (V2 - 渐变标题卡) ============
    // Pattern: Hero Gradient Banner | Style: Soft Pink + Blue
    private void addHeader(LinearLayout root) {
        // === 外层渐变主卡 ===
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable heroGd = new GradientDrawable(
                GradientDrawable.Orientation.BR_TL,
                new int[]{0xFFFBCFE8, 0xFFFFF7ED});
        heroGd.setCornerRadius(dp(28));
        heroGd.setStroke(dp(1), 0xFFF4D5E8);
        heroCard.setBackground(heroGd);
        heroCard.setPadding(dp(20), dp(22), dp(20), dp(22));

        // === 顶部行：圆形图标 + 标题 ===
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // 左侧：粉蓝渐变图标方块
        LinearLayout iconBox = new LinearLayout(this);
        GradientDrawable ibGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFEC4899, 0xFF8B5CF6});
        ibGd.setCornerRadius(dp(18));
        iconBox.setBackground(ibGd);
        iconBox.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(68), dp(68));
        iconBox.setLayoutParams(ibLp);

        TextView iconChar = new TextView(this);
        iconChar.setText("答");
        iconChar.setTextSize(26);
        iconChar.setTextColor(0xFFFFFFFF);
        iconChar.setTypeface(null, android.graphics.Typeface.BOLD);
        iconChar.setGravity(Gravity.CENTER);
        iconBox.addView(iconChar);
        topRow.addView(iconBox);

        // 右侧：标题 + 副标题 + 状态点
        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams taLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        taLp.leftMargin = dp(16);
        titleArea.setLayoutParams(taLp);

        // 状态标签
        TextView titleMain = new TextView(this);
        titleMain.setText("答案显示模块");
        titleMain.setTextSize(22);
        titleMain.setTextColor(DS_TEXT);
        titleMain.setTypeface(null, android.graphics.Typeface.BOLD);
        titleArea.addView(titleMain);

        TextView subtitleTv = new TextView(this);
        subtitleTv.setText("LSPosed Hook · 智能答题助手");
        subtitleTv.setTextSize(12);
        subtitleTv.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subtitleTv.setLayoutParams(subLp);
        titleArea.addView(subtitleTv);

        topRow.addView(titleArea);
        heroCard.addView(topRow);

        // === 状态胶囊行 ===
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams chipRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipRowLp.topMargin = dp(16);
        chipRow.setLayoutParams(chipRowLp);

        // chip 1: 版本
        LinearLayout chip1 = new LinearLayout(this);
        chip1.setOrientation(LinearLayout.HORIZONTAL);
        chip1.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable c1Gd = new GradientDrawable();
        c1Gd.setColor(0xFFEC4899);
        c1Gd.setCornerRadius(dp(100));
        chip1.setBackground(c1Gd);
        chip1.setPadding(dp(12), dp(7), dp(12), dp(7));

        TextView c1Text = new TextView(this);
        c1Text.setText("♥ v1.0");
        c1Text.setTextSize(11);
        c1Text.setTextColor(0xFFFFFFFF);
        c1Text.setTypeface(null, android.graphics.Typeface.BOLD);
        chip1.addView(c1Text);
        chipRow.addView(chip1);

        // chip 2: Hook 状态
        LinearLayout chip2 = new LinearLayout(this);
        chip2.setOrientation(LinearLayout.HORIZONTAL);
        chip2.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable c2Gd = new GradientDrawable();
        c2Gd.setColor(DS_BLUE);
        c2Gd.setCornerRadius(dp(100));
        chip2.setBackground(c2Gd);
        chip2.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams c2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c2Lp.leftMargin = dp(8);
        chip2.setLayoutParams(c2Lp);

        TextView c2Text = new TextView(this);
        c2Text.setText("✦ Hook");
        c2Text.setTextSize(11);
        c2Text.setTextColor(0xFFFFFFFF);
        c2Text.setTypeface(null, android.graphics.Typeface.BOLD);
        chip2.addView(c2Text);
        chipRow.addView(chip2);

        heroCard.addView(chipRow);

        // === 目标应用信息分隔卡片 ===
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable infoGd = new GradientDrawable();
        infoGd.setColor(0xFFFFFFFF);
        infoGd.setCornerRadius(dp(20));
        infoCard.setBackground(infoGd);
        infoCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(18);
        infoCard.setLayoutParams(infoLp);

        // 信息标题行
        LinearLayout infoHeader = new LinearLayout(this);
        infoHeader.setOrientation(LinearLayout.HORIZONTAL);
        infoHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout infoBadge = new LinearLayout(this);
        infoBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeGd = new GradientDrawable();
        badgeGd.setColor(DS_ACCENT_LIGHT);
        badgeGd.setCornerRadius(dp(8));
        infoBadge.setBackground(badgeGd);
        infoBadge.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView badgeIcon = new TextView(this);
        badgeIcon.setText("◉");
        badgeIcon.setTextSize(9);
        badgeIcon.setTextColor(DS_ACCENT);
        badgeIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        infoBadge.addView(badgeIcon);
        infoHeader.addView(infoBadge);

        TextView infoTitle = new TextView(this);
        infoTitle.setText("  目标应用");
        infoTitle.setTextSize(13);
        infoTitle.setTextColor(DS_TEXT);
        infoTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        infoHeader.addView(infoTitle);
        infoCard.addView(infoHeader);

        // 包名 + 状态
        LinearLayout pkgRow = new LinearLayout(this);
        pkgRow.setOrientation(LinearLayout.HORIZONTAL);
        pkgRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pkgLp.topMargin = dp(10);
        pkgRow.setLayoutParams(pkgLp);

        LinearLayout pkgTextCol = new LinearLayout(this);
        pkgTextCol.setOrientation(LinearLayout.VERTICAL);
        pkgTextCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("应用包名");
        pkgLabel.setTextSize(10);
        pkgLabel.setTextColor(DS_TEXT_MUTED);
        pkgTextCol.addView(pkgLabel);

        TextView pkgVal = new TextView(this);
        pkgVal.setText(TARGET_PACKAGE);
        pkgVal.setTextSize(13);
        pkgVal.setTextColor(DS_ACCENT);
        pkgVal.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams pvl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvl.topMargin = dp(2);
        pkgVal.setLayoutParams(pvl);
        pkgTextCol.addView(pkgVal);
        pkgRow.addView(pkgTextCol);

        // 右侧状态
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable spGd = new GradientDrawable();
        spGd.setColor(installed ? DS_SUCCESS_LIGHT : DS_ERROR_LIGHT);
        spGd.setCornerRadius(dp(100));
        statusPill.setBackground(spGd);
        statusPill.setPadding(dp(12), dp(7), dp(12), dp(7));

        TextView spDot = new TextView(this);
        spDot.setText(installed ? "✓" : "✕");
        spDot.setTextSize(10);
        spDot.setTextColor(installed ? DS_SUCCESS : DS_ERROR);
        spDot.setGravity(Gravity.CENTER);
        spDot.setTypeface(null, android.graphics.Typeface.BOLD);
        statusPill.addView(spDot);

        TextView spText = new TextView(this);
        spText.setText(" " + (installed ? "已安装" : "未安装"));
        spText.setTextSize(11);
        spText.setTextColor(installed ? DS_SUCCESS : DS_ERROR);
        spText.setTypeface(null, android.graphics.Typeface.BOLD);
        statusPill.addView(spText);
        pkgRow.addView(statusPill);
        infoCard.addView(pkgRow);

        // 分隔虚线
        View sep = new View(this);
        GradientDrawable sepGd = new GradientDrawable();
        sepGd.setColor(DS_BORDER);
        sep.setBackground(sepGd);
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        seplp.topMargin = dp(14);
        seplp.bottomMargin = dp(14);
        sep.setLayoutParams(seplp);
        infoCard.addView(sep);

        // 第二行：版本号 + 模块ID
        LinearLayout verRow = new LinearLayout(this);
        verRow.setOrientation(LinearLayout.HORIZONTAL);
        verRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout verCol = new LinearLayout(this);
        verCol.setOrientation(LinearLayout.VERTICAL);
        verCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView verLabel = new TextView(this);
        verLabel.setText("应用版本");
        verLabel.setTextSize(10);
        verLabel.setTextColor(DS_TEXT_MUTED);
        verCol.addView(verLabel);

        String versionName = null;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (pi != null && pi.versionName != null) versionName = pi.versionName;
        } catch (Throwable ignored) {}
        TextView verVal = new TextView(this);
        if (versionName != null) {
            verVal.setText("v" + versionName);
            verVal.setTextColor(DS_TEXT);
        } else {
            verVal.setText("未检测到");
            verVal.setTextColor(DS_TEXT_DIM);
        }
        verVal.setTextSize(13);
        verVal.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams vvl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vvl.topMargin = dp(2);
        verVal.setLayoutParams(vvl);
        verCol.addView(verVal);
        verRow.addView(verCol);

        LinearLayout modCol = new LinearLayout(this);
        modCol.setOrientation(LinearLayout.VERTICAL);
        modCol.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        modCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView modLabel = new TextView(this);
        modLabel.setText("模块标识");
        modLabel.setTextSize(10);
        modLabel.setTextColor(DS_TEXT_MUTED);
        modLabel.setGravity(Gravity.END);
        modCol.addView(modLabel);

        TextView modVal = new TextView(this);
        modVal.setText(MODULE_PACKAGE);
        modVal.setTextSize(11);
        modVal.setTextColor(DS_TEXT_MUTED);
        modVal.setGravity(Gravity.END);
        LinearLayout.LayoutParams mvl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mvl.topMargin = dp(2);
        modVal.setLayoutParams(mvl);
        modCol.addView(modVal);
        verRow.addView(modCol);
        infoCard.addView(verRow);
        heroCard.addView(infoCard);
        root.addView(heroCard, cardParams());
    }

    // ============ 模块状态卡 (V2 - 双列彩色面板) ============
    private void addStatusCard(LinearLayout root, StatsData data) {
        boolean active = data != null && data.moduleActive;
        boolean autoOn = data != null && data.autoSelectEnabled;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(26));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(20));

        // === 标题徽章 ===
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBadge = new LinearLayout(this);
        titleBadge.setGravity(Gravity.CENTER);
        GradientDrawable tbGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{DS_ACCENT, DS_VIOLET});
        tbGd.setCornerRadius(dp(10));
        titleBadge.setBackground(tbGd);
        titleBadge.setPadding(dp(10), dp(5), dp(10), dp(5));

        TextView tbText = new TextView(this);
        tbText.setText("模块状态");
        tbText.setTextSize(11);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        // === 双列状态卡片 ===
        LinearLayout twoCol = new LinearLayout(this);
        twoCol.setOrientation(LinearLayout.HORIZONTAL);
        twoCol.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams twoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        twoLp.topMargin = dp(14);
        twoCol.setLayoutParams(twoLp);

        int[] col1Colors = active
                ? new int[]{DS_SUCCESS, 0xFF0FB37C}
                : new int[]{DS_ERROR, 0xFFB91C3B};
        LinearLayout col1 = buildStatusPanel(
                active ? "模块已激活" : "模块未激活",
                active ? "✓" : "!",
                active ? "Hook 已在目标应用中生效" : "请在 LSPosed 管理器中启用本模块",
                col1Colors, 1.0f, 0);
        twoCol.addView(col1);

        int[] col2Colors = autoOn
                ? new int[]{DS_ACCENT, DS_ACCENT_DARK}
                : new int[]{0xFF9CA3AF, 0xFF6B7280};
        LinearLayout col2 = buildStatusPanel(
                autoOn ? "自动答题已开启" : "自动答题已关闭",
                autoOn ? "⚡" : "○",
                autoOn ? "自动识别并点击正确答案" : "需手动操作",
                col2Colors, 1.0f, dp(10));
        twoCol.addView(col2);
        content.addView(twoCol);

        // === 功能亮点标题 ===
        LinearLayout featTitleRow = new LinearLayout(this);
        featTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        featTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ftrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ftrLp.topMargin = dp(20);
        featTitleRow.setLayoutParams(ftrLp);

        LinearLayout featTagWrap = new LinearLayout(this);
        featTagWrap.setGravity(Gravity.CENTER);
        GradientDrawable ftwGd = new GradientDrawable();
        ftwGd.setColor(DS_YELLOW_LIGHT);
        ftwGd.setCornerRadius(dp(10));
        featTagWrap.setBackground(ftwGd);
        featTagWrap.setPadding(dp(10), dp(5), dp(10), dp(5));

        TextView featTag = new TextView(this);
        featTag.setText("✦ 功能亮点");
        featTag.setTextSize(11);
        featTag.setTextColor(DS_YELLOW);
        featTag.setTypeface(null, android.graphics.Typeface.BOLD);
        featTagWrap.addView(featTag);
        featTitleRow.addView(featTagWrap);
        content.addView(featTitleRow);

        // === 功能亮点列表 ===
        String[] featLabels = {"答案识别", "多题型支持", "自动点击", "数据共享", "实时统计"};
        String[] featDescs = {
                "自动识别正确答案并高亮标记",
                "支持单选 / 多选 / 判断 / 填空题",
                "答案自动选中，无需手动点击",
                "通过 ContentProvider 跨进程共享",
                "实时显示命中次数与请求记录"
        };
        int[] featColors = {DS_YELLOW, DS_ACCENT, DS_SUCCESS, DS_VIOLET, DS_BLUE};
        String[] featIcons = {"✨", "📋", "🚀", "🔗", "📊"};

        for (int i = 0; i < featLabels.length; i++) {
            LinearLayout hRow = new LinearLayout(this);
            hRow.setOrientation(LinearLayout.HORIZONTAL);
            hRow.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(DS_CARD);
            rowBg.setCornerRadius(dp(16));
            rowBg.setStroke(dp(1), DS_BORDER);
            hRow.setBackground(rowBg);
            hRow.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams hrp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hrp.topMargin = dp(8);
            hRow.setLayoutParams(hrp);

            LinearLayout iconBox = new LinearLayout(this);
            iconBox.setGravity(Gravity.CENTER);
            GradientDrawable iBoxGd = new GradientDrawable();
            iBoxGd.setColor(featColors[i]);
            iBoxGd.setCornerRadius(dp(12));
            iconBox.setBackground(iBoxGd);
            LinearLayout.LayoutParams iBLp = new LinearLayout.LayoutParams(dp(42), dp(42));
            iconBox.setLayoutParams(iBLp);

            TextView iconTv = new TextView(this);
            iconTv.setText(featIcons[i]);
            iconTv.setTextSize(16);
            iconTv.setTextColor(0xFFFFFFFF);
            iconTv.setGravity(Gravity.CENTER);
            iconTv.setTypeface(null, android.graphics.Typeface.BOLD);
            iconBox.addView(iconTv);
            hRow.addView(iconBox);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tclp.leftMargin = dp(14);
            textCol.setLayoutParams(tclp);

            TextView rowTitle = new TextView(this);
            rowTitle.setText(featLabels[i]);
            rowTitle.setTextSize(14);
            rowTitle.setTextColor(DS_TEXT);
            rowTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(rowTitle);

            TextView rowDesc = new TextView(this);
            rowDesc.setText(featDescs[i]);
            rowDesc.setTextSize(11);
            rowDesc.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rdLp.topMargin = dp(3);
            rowDesc.setLayoutParams(rdLp);
            textCol.addView(rowDesc);
            hRow.addView(textCol);

            LinearLayout arrowBox = new LinearLayout(this);
            arrowBox.setGravity(Gravity.CENTER);
            GradientDrawable abGd = new GradientDrawable();
            abGd.setColor(0xFFFFF7ED);
            abGd.setCornerRadius(dp(100));
            abGd.setStroke(dp(1), DS_BORDER);
            arrowBox.setBackground(abGd);
            LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(dp(28), dp(28));
            arrowBox.setLayoutParams(abLp);

            TextView arrowTv = new TextView(this);
            arrowTv.setText("\u203a");
            arrowTv.setTextSize(18);
            arrowTv.setTextColor(featColors[i]);
            arrowTv.setGravity(Gravity.CENTER);
            arrowTv.setTypeface(null, android.graphics.Typeface.BOLD);
            arrowBox.addView(arrowTv);
            hRow.addView(arrowBox);

            content.addView(hRow);
        }

        // 底部提示（未激活时）
        if (!active) {
            LinearLayout tipBox = new LinearLayout(this);
            tipBox.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable tipGd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFFFE4E6, 0xFFFFF0F0});
            tipGd.setCornerRadius(dp(16));
            tipGd.setStroke(dp(1), 0xFFFBC5CA);
            tipBox.setBackground(tipGd);
            tipBox.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tipLp.topMargin = dp(16);
            tipBox.setLayoutParams(tipLp);

            LinearLayout tipHeader = new LinearLayout(this);
            tipHeader.setOrientation(LinearLayout.HORIZONTAL);
            tipHeader.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout tiWrap = new LinearLayout(this);
            tiWrap.setGravity(Gravity.CENTER);
            GradientDrawable tiGd = new GradientDrawable();
            tiGd.setColor(DS_ERROR);
            tiGd.setCornerRadius(dp(8));
            tiWrap.setBackground(tiGd);
            tiWrap.setPadding(dp(8), dp(6), dp(8), dp(6));
            TextView tiIcon = new TextView(this);
            tiIcon.setText("!");
            tiIcon.setTextSize(12);
            tiIcon.setTextColor(0xFFFFFFFF);
            tiIcon.setTypeface(null, android.graphics.Typeface.BOLD);
            tiIcon.setGravity(Gravity.CENTER);
            tiWrap.addView(tiIcon);
            tipHeader.addView(tiWrap);

            TextView tipTitle = new TextView(this);
            tipTitle.setText("  模块未激活");
            tipTitle.setTextSize(13);
            tipTitle.setTextColor(DS_ERROR);
            tipTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tipHeader.addView(tipTitle);
            tipBox.addView(tipHeader);

            TextView tipText = new TextView(this);
            tipText.setText("请在 LSPosed 管理器中启用本模块\n作用域需包含：" + TARGET_PACKAGE + " 和 " + MODULE_PACKAGE);
            tipText.setTextSize(11);
            tipText.setTextColor(DS_TEXT_MUTED);
            tipText.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ttLp.topMargin = dp(8);
            tipText.setLayoutParams(ttLp);
            tipBox.addView(tipText);

            content.addView(tipBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // 辅助：构建状态面板 (V2)
    private LinearLayout buildStatusPanel(String title, String symbol, String desc,
                                          int[] colors, float weight, int leftMargin) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        GradientDrawable pGd = new GradientDrawable(
                GradientDrawable.Orientation.BR_TL, colors);
        pGd.setCornerRadius(dp(20));
        panel.setBackground(pGd);
        panel.setPadding(dp(12), dp(16), dp(12), dp(16));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        pLp.leftMargin = leftMargin;
        panel.setLayoutParams(pLp);

        TextView sym = new TextView(this);
        sym.setText(symbol);
        sym.setTextSize(22);
        sym.setTextColor(0xFFFFFFFF);
        sym.setGravity(Gravity.CENTER);
        sym.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(sym);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(12);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        titleTv.setLayoutParams(tlp);
        panel.addView(titleTv);

        TextView descTv = new TextView(this);
        descTv.setText(desc);
        descTv.setTextSize(10);
        descTv.setTextColor(0xFFFFFFFF);
        descTv.setGravity(Gravity.CENTER);
        descTv.setAlpha(0.9f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        descTv.setLayoutParams(dlp);
        panel.addView(descTv);

        return panel;
    }

    // ============ 快捷操作卡 (V2 - 渐变开关卡片) ============
    private void addActionCard(LinearLayout root, StatsData data) {
        final boolean[] toggleState = {data != null && data.autoSelectEnabled};

        // === 外层卡片：暖米色背景 + 细边
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(26));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // === 标题：渐变徽章
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBadge = new LinearLayout(this);
        GradientDrawable tbGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{DS_ACCENT, DS_VIOLET});
        tbGd.setCornerRadius(dp(10));
        titleBadge.setBackground(tbGd);
        titleBadge.setGravity(Gravity.CENTER);
        titleBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView tbText = new TextView(this);
        tbText.setText("> 快捷操作");
        tbText.setTextSize(12);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        // === 自动答题开关卡片（粉紫渐变）
        final LinearLayout toggleCard = new LinearLayout(this);
        toggleCard.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable toggleBg = new GradientDrawable();
        if (toggleState[0]) {
            toggleBg.setColor(DS_ACCENT_LIGHT);
        } else {
            toggleBg.setColor(0xFFFFF7ED);
        }
        toggleBg.setCornerRadius(dp(22));
        toggleBg.setStroke(dp(2), toggleState[0] ? DS_ACCENT : DS_BORDER_2);
        toggleCard.setBackground(toggleBg);
        toggleCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tcLp.topMargin = dp(14);
        toggleCard.setLayoutParams(tcLp);

        // 开关卡片主行
        LinearLayout toggleMain = new LinearLayout(this);
        toggleMain.setOrientation(LinearLayout.HORIZONTAL);
        toggleMain.setGravity(Gravity.CENTER_VERTICAL);

        // 左：渐变图标方块
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{toggleState[0] ? DS_ACCENT : 0xFFA0A0A0,
                           toggleState[0] ? DS_VIOLET : 0xFF787878});
        ibGd.setCornerRadius(dp(18));
        iconBox.setBackground(ibGd);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(60), dp(60));
        iconBox.setLayoutParams(ibLp);

        // 图标
        TextView iconEmoji = new TextView(this);
        iconEmoji.setText("⚡");
        iconEmoji.setTextSize(24);
        iconEmoji.setTextColor(0xFFFFFFFF);
        iconEmoji.setGravity(Gravity.CENTER);
        iconBox.addView(iconEmoji);
        toggleMain.addView(iconBox);

        // 中：标题 + 描述
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcolLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tcolLp.leftMargin = dp(14);
        textCol.setLayoutParams(tcolLp);

        final TextView toggleTitle = new TextView(this);
        toggleTitle.setText("自动答题开关");
        toggleTitle.setTextSize(16);
        toggleTitle.setTextColor(DS_TEXT);
        toggleTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(toggleTitle);

        final TextView toggleDesc = new TextView(this);
        toggleDesc.setText(toggleState[0] ? "自动识别并点击正确答案" : "点击开关开启此功能");
        toggleDesc.setTextSize(11);
        toggleDesc.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tdLp.topMargin = dp(4);
        toggleDesc.setLayoutParams(tdLp);
        textCol.addView(toggleDesc);
        toggleMain.addView(textCol);

        // 右：大号滑块开关
        final android.widget.FrameLayout switchTrack = new android.widget.FrameLayout(this);
        final GradientDrawable scGd = new GradientDrawable();
        scGd.setColor(toggleState[0] ? DS_ACCENT : 0xFFC9C9C9);
        scGd.setCornerRadius(dp(24));
        switchTrack.setBackground(scGd);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(dp(76), dp(40));
        switchTrack.setLayoutParams(scLp);

        final View switchThumb = new View(this);
        final GradientDrawable thumbGd = new GradientDrawable();
        thumbGd.setShape(GradientDrawable.OVAL);
        thumbGd.setColor(0xFFFFFFFF);
        thumbGd.setStroke(dp(2), toggleState[0] ? DS_VIOLET : 0xFF787878);
        switchThumb.setBackground(thumbGd);
        final android.widget.FrameLayout.LayoutParams thumbLp =
                new android.widget.FrameLayout.LayoutParams(dp(32), dp(32),
                        toggleState[0] ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                                        : (Gravity.LEFT | Gravity.CENTER_VERTICAL));
        thumbLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        switchThumb.setLayoutParams(thumbLp);
        switchTrack.addView(switchThumb);
        toggleMain.addView(switchTrack);

        toggleCard.addView(toggleMain);

        // 状态行
        final LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable srGd = new GradientDrawable();
        srGd.setColor(toggleState[0] ? 0xFFFFF7ED : DS_CARD);
        srGd.setCornerRadius(dp(14));
        srGd.setStroke(dp(1), toggleState[0] ? DS_ACCENT : DS_BORDER_2);
        statusRow.setBackground(srGd);
        statusRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(12);
        statusRow.setLayoutParams(srLp);

        final TextView statusText = new TextView(this);
        statusText.setText(toggleState[0] ? "✓ 功能已启用，自动答题生效中" : "○ 功能已关闭，点击开关开启");
        statusText.setTextSize(12);
        statusText.setTextColor(toggleState[0] ? DS_ACCENT : DS_TEXT_MUTED);
        statusText.setGravity(Gravity.CENTER);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        statusText.setLayoutParams(stLp);
        statusRow.addView(statusText);
        toggleCard.addView(statusRow);

        // 点击切换逻辑
        toggleCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleState[0] = !toggleState[0];
                if (toggleState[0]) {
                    toggleBg.setColor(DS_ACCENT_LIGHT);
                    toggleBg.setStroke(dp(2), DS_ACCENT);
                } else {
                    toggleBg.setColor(0xFFFFF7ED);
                    toggleBg.setStroke(dp(2), DS_BORDER_2);
                }
                iconBox.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{toggleState[0] ? DS_ACCENT : 0xFFA0A0A0,
                                   toggleState[0] ? DS_VIOLET : 0xFF787878}));
                toggleDesc.setText(toggleState[0] ? "自动识别并点击正确答案" : "点击开关开启此功能");
                statusText.setText(toggleState[0] ? "✓ 功能已启用，自动答题生效中" : "○ 功能已关闭，点击开关开启");
                statusText.setTextColor(toggleState[0] ? DS_ACCENT : DS_TEXT_MUTED);
                srGd.setColor(toggleState[0] ? 0xFFFFF7ED : DS_CARD);
                srGd.setStroke(dp(1), toggleState[0] ? DS_ACCENT : DS_BORDER_2);
                scGd.setColor(toggleState[0] ? DS_ACCENT : 0xFFC9C9C9);
                thumbGd.setStroke(dp(2), toggleState[0] ? DS_VIOLET : 0xFF787878);
                thumbLp.gravity = toggleState[0] ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                                                    : (Gravity.LEFT | Gravity.CENTER_VERTICAL);
                switchThumb.setLayoutParams(thumbLp);
                switchThumb.requestLayout();

                try {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put("auto_select_enabled", toggleState[0]);
                    getContentResolver().update(
                            android.net.Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"),
                            values, null, null);
                } catch (Throwable ignored) {}
                try {
                    android.content.SharedPreferences sp = getSharedPreferences("module_stats", MODE_PRIVATE);
                    sp.edit().putBoolean("auto_select_enabled", toggleState[0]).apply();
                } catch (Throwable ignored) {}

                android.widget.Toast.makeText(MainActivity.this,
                        toggleState[0] ? "✓ 自动答题已开启" : "自动答题已关闭",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(toggleCard);

        // === 主按钮：启动目标应用（渐变粉）
        Button launchBtn = makeActionButtonV2("▶ 启动目标应用", new int[]{DS_ACCENT, DS_VIOLET});
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp(16);
        launchBtn.setLayoutParams(lbLp);
        launchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { launchTargetApp(); }
        });
        content.addView(launchBtn);

        // === 并排按钮：刷新 + 清空
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(12);
        btnRow.setLayoutParams(brLp);

        Button refreshBtn = makeActionButtonV2("↻ 刷新数据", new int[]{DS_BLUE, DS_BLUE_DARK});
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        rLp.rightMargin = dp(10);
        refreshBtn.setLayoutParams(rLp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatsAsync();
                android.widget.Toast.makeText(MainActivity.this, "数据已刷新", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(refreshBtn);

        Button clearBtn = makeActionButtonV2("✕ 清空统计", new int[]{DS_ERROR, 0xFFB91C1C});
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        clearBtn.setLayoutParams(cLp);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
                    boolean savedAuto = selfSp.getBoolean("auto_select_enabled", false);
                    try {
                        getContentResolver().delete(
                                android.net.Uri.parse("content://" + MODULE_PACKAGE + ".stats/clear"),
                                null, null);
                    } catch (Throwable ignored) {}
                    selfSp.edit().clear().putBoolean("auto_select_enabled", savedAuto).apply();
                    android.widget.Toast.makeText(MainActivity.this, "已清空统计", android.widget.Toast.LENGTH_SHORT).show();
                    refreshStatsAsync();
                } catch (Throwable t) {
                    android.widget.Toast.makeText(MainActivity.this, "清空失败", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnRow.addView(clearBtn);
        content.addView(btnRow);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // 辅助：V2 风格渐变按钮
    private Button makeActionButtonV2(String text, int[] colors) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(14);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, colors);
        bg.setCornerRadius(dp(18));
        btn.setBackground(bg);
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));
        return btn;
    }

    // ============ 统计数据卡 (V2 - 多列数值面板) ============
    private void addStatsCard(LinearLayout root, StatsData data) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(26));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // === 标题：渐变徽章
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBadge = new LinearLayout(this);
        GradientDrawable tbGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{DS_SUCCESS, 0xFF0FB37C});
        tbGd.setCornerRadius(dp(10));
        titleBadge.setBackground(tbGd);
        titleBadge.setGravity(Gravity.CENTER);
        titleBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView tbText = new TextView(this);
        tbText.setText("> 数据统计");
        tbText.setTextSize(12);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        // === 三列数据面板
        int hh = data != null ? data.targetHitCount : -1;
        int rc = data != null ? data.requestCount : -1;
        long lt = data != null ? data.lastHookTime : -1;
        boolean hasAnyData = (hh >= 0 || rc >= 0 || lt > 0);

        String[] labels = {"命中次数", "请求总数", "最近活跃"};
        String[] values = {
                hh < 0 ? "—" : String.valueOf(hh),
                rc < 0 ? "—" : String.valueOf(rc),
                lt <= 0 ? "—" : formatTime(lt)};
        int[][] cardColors = {
                {DS_ACCENT, DS_VIOLET},
                {DS_BLUE, DS_BLUE_DARK},
                {DS_YELLOW, 0xFFD97706}};

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = dp(16);
        statsRow.setLayoutParams(srlp);

        for (int i = 0; i < 3; i++) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.BR_TL, cardColors[i]);
            bg.setCornerRadius(dp(22));
            box.setBackground(bg);
            box.setPadding(dp(8), dp(18), dp(8), dp(18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) lp.leftMargin = dp(10);
            box.setLayoutParams(lp);

            TextView valTv = new TextView(this);
            valTv.setText(values[i]);
            valTv.setTextSize(20);
            valTv.setTextColor(0xFFFFFFFF);
            valTv.setGravity(Gravity.CENTER);
            valTv.setTypeface(null, android.graphics.Typeface.BOLD);
            box.addView(valTv);

            TextView lblTv = new TextView(this);
            lblTv.setText(labels[i]);
            lblTv.setTextSize(10);
            lblTv.setTextColor(0xFFFFFFFF);
            lblTv.setAlpha(0.9f);
            lblTv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lplp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lplp.topMargin = dp(6);
            lblTv.setLayoutParams(lplp);
            box.addView(lblTv);

            statsRow.addView(box);
        }
        content.addView(statsRow);

        // === 空状态提示
        if (!hasAnyData) {
            LinearLayout tipBox = new LinearLayout(this);
            tipBox.setOrientation(LinearLayout.HORIZONTAL);
            tipBox.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable tipGd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFECFEFF, 0xFFFFF7ED});
            tipGd.setCornerRadius(dp(18));
            tipGd.setStroke(dp(1), DS_BORDER);
            tipBox.setBackground(tipGd);
            tipBox.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tipLp.topMargin = dp(16);
            tipBox.setLayoutParams(tipLp);

            TextView tipIcon = new TextView(this);
            tipIcon.setText("✦");
            tipIcon.setTextSize(14);
            tipIcon.setTextColor(DS_BLUE);
            tipBox.addView(tipIcon);

            TextView tipText = new TextView(this);
            tipText.setText("  请先打开目标应用，进入答题页面后返回刷新");
            tipText.setTextSize(11);
            tipText.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            ttLp.leftMargin = dp(6);
            tipText.setLayoutParams(ttLp);
            tipBox.addView(tipText);

            content.addView(tipBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ HTTP 客户端检测列表 (V2 - 芯片标签) ============
    private void addHttpClientsCard(LinearLayout root, StatsData data) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(26));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // === 标题徽章
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBadge = new LinearLayout(this);
        GradientDrawable tbGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{DS_BLUE, DS_BLUE_DARK});
        tbGd.setCornerRadius(dp(10));
        titleBadge.setBackground(tbGd);
        titleBadge.setGravity(Gravity.CENTER);
        titleBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView tbText = new TextView(this);
        tbText.setText("> HTTP 客户端");
        tbText.setTextSize(12);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        // === 内容
        String raw = data != null ? data.detectedClients : "";
        if (raw == null) raw = "";
        java.util.List<String> clients = new java.util.ArrayList<String>();
        if (!raw.trim().isEmpty()) {
            for (String s : raw.split("\n")) {
                if (s != null && s.trim().length() > 0) clients.add(s.trim());
            }
        }

        if (!clients.isEmpty()) {
            // 芯片标签
            LinearLayout chipWrap = new LinearLayout(this);
            chipWrap.setOrientation(LinearLayout.HORIZONTAL);
            chipWrap.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable cwGd = new GradientDrawable();
            cwGd.setColor(DS_ACCENT_LIGHT);
            cwGd.setCornerRadius(dp(100));
            chipWrap.setBackground(cwGd);
            chipWrap.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams cwLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cwLp.topMargin = dp(14);
            chipWrap.setLayoutParams(cwLp);

            TextView countTv = new TextView(this);
            countTv.setText("✓ 已发现 " + clients.size() + " 个客户端类");
            countTv.setTextSize(11);
            countTv.setTextColor(DS_ACCENT);
            countTv.setTypeface(null, android.graphics.Typeface.BOLD);
            chipWrap.addView(countTv);
            content.addView(chipWrap);

            // 列表
            for (int i = 0; i < clients.size(); i++) {
                View row = makeListItemV2(i + 1, clients.get(i), null, DS_BLUE, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(10);
                content.addView(row, lp);
            }
        } else {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            GradientDrawable ebGd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{DS_BLUE_LIGHT, 0xFFFFF7ED});
            ebGd.setCornerRadius(dp(20));
            ebGd.setStroke(dp(1), DS_BORDER);
            emptyBox.setBackground(ebGd);
            emptyBox.setPadding(dp(20), dp(22), dp(20), dp(22));
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ebLp.topMargin = dp(14);
            emptyBox.setLayoutParams(ebLp);

            TextView ei = new TextView(this);
            ei.setText("🔍");
            ei.setTextSize(28);
            ei.setGravity(Gravity.CENTER);
            emptyBox.addView(ei);

            TextView empty = new TextView(this);
            empty.setText("\n尚未检测到 HTTP 客户端\n请先打开目标应用并进入答题页面");
            empty.setTextSize(12);
            empty.setTextColor(DS_TEXT_MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = dp(8);
            empty.setLayoutParams(ep);
            emptyBox.addView(empty);

            content.addView(emptyBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 最近请求记录 (V2 - 时间轴卡片) ============
    private void addRecentRequestsCard(LinearLayout root, StatsData data) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(26));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // 标题徽章
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBadge = new LinearLayout(this);
        GradientDrawable tbGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{DS_SUCCESS, 0xFF0FB37C});
        tbGd.setCornerRadius(dp(10));
        titleBadge.setBackground(tbGd);
        titleBadge.setGravity(Gravity.CENTER);
        titleBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView tbText = new TextView(this);
        tbText.setText("> 最近请求");
        tbText.setTextSize(12);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        java.util.List<RequestItem> requests = data != null ? data.requests : null;

        if (requests != null && !requests.isEmpty()) {
            // 计数徽章
            LinearLayout countWrap = new LinearLayout(this);
            countWrap.setOrientation(LinearLayout.HORIZONTAL);
            countWrap.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable cwGd = new GradientDrawable();
            cwGd.setColor(DS_ACCENT_LIGHT);
            cwGd.setCornerRadius(dp(100));
            countWrap.setBackground(cwGd);
            countWrap.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams cwLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cwLp.topMargin = dp(14);
            countWrap.setLayoutParams(cwLp);

            TextView countTv = new TextView(this);
            countTv.setText("✓ 共 " + requests.size() + " 条记录");
            countTv.setTextSize(11);
            countTv.setTextColor(DS_ACCENT);
            countTv.setTypeface(null, android.graphics.Typeface.BOLD);
            countWrap.addView(countTv);
            content.addView(countWrap);

            // 分页
            int totalPages = (requests.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            if (mCurrentPage >= totalPages) mCurrentPage = totalPages - 1;
            if (mCurrentPage < 0) mCurrentPage = 0;

            int startIdx = mCurrentPage * PAGE_SIZE;
            int endIdx = Math.min(startIdx + PAGE_SIZE, requests.size());

            TextView rangeTv = new TextView(this);
            rangeTv.setText("第 " + (startIdx + 1) + "-" + endIdx + " 条 / 第 "
                    + (mCurrentPage + 1) + " 页 / 共 " + totalPages + " 页");
            rangeTv.setTextSize(10);
            rangeTv.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(6);
            rangeTv.setLayoutParams(rlp);
            content.addView(rangeTv);

            for (int i = startIdx; i < endIdx; i++) {
                final RequestItem item = requests.get(i);
                final int idx = i + 1;
                int color = DS_BLUE;
                if (item.type != null) {
                    if (item.type.contains("WEBVIEW")) color = DS_VIOLET;
                    else if (item.type.contains("OKHTTP")) color = DS_BLUE;
                    else if (item.type.contains("URL")) color = DS_ACCENT;
                    else if (item.type.contains("SOCKET")) color = DS_ERROR;
                }
                View row = makeListItemV2(idx, item.url, item.type, color, true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showRequestDetailV2(item, idx);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(10);
                content.addView(row, lp);
            }

            // 分页按钮
            LinearLayout pagerRow = new LinearLayout(this);
            pagerRow.setOrientation(LinearLayout.HORIZONTAL);
            pagerRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pprp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pprp.topMargin = dp(18);
            content.addView(pagerRow, pprp);

            final boolean hasPrev = mCurrentPage > 0;
            final boolean hasNext = mCurrentPage < totalPages - 1;

            Button prevBtn = makeActionButtonV2("◀ 上一页",
                    new int[]{DS_ACCENT, DS_VIOLET});
            LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            prevLp.rightMargin = dp(10);
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
                disabledBg.setColor(DS_BORDER_2);
                disabledBg.setCornerRadius(dp(18));
                prevBtn.setBackground(disabledBg);
                prevBtn.setEnabled(false);
            }
            pagerRow.addView(prevBtn);

            Button nextBtn = makeActionButtonV2("下一页 ▶",
                    new int[]{DS_ACCENT, DS_VIOLET});
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
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
                disabledBg.setColor(DS_BORDER_2);
                disabledBg.setCornerRadius(dp(18));
                nextBtn.setBackground(disabledBg);
                nextBtn.setEnabled(false);
            }
            pagerRow.addView(nextBtn);
        } else {
            // 空状态
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            GradientDrawable ebGd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{DS_ACCENT_LIGHT, 0xFFFFF7ED});
            ebGd.setCornerRadius(dp(20));
            ebGd.setStroke(dp(1), DS_BORDER);
            emptyBox.setBackground(ebGd);
            emptyBox.setPadding(dp(20), dp(22), dp(20), dp(22));
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ebLp.topMargin = dp(14);
            emptyBox.setLayoutParams(ebLp);

            TextView ei = new TextView(this);
            ei.setText("📭");
            ei.setTextSize(28);
            ei.setGravity(Gravity.CENTER);
            emptyBox.addView(ei);

            TextView empty = new TextView(this);
            empty.setText("\n尚未捕获任何请求\n请先打开目标应用并进入答题页面");
            empty.setTextSize(12);
            empty.setTextColor(DS_TEXT_MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = dp(8);
            empty.setLayoutParams(ep);
            emptyBox.addView(empty);

            content.addView(emptyBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 列表行 (V2 - 索引徽章样式) ============
    private View makeListItemV2(int index, String mainText, String typeLabel,
                                int color, boolean truncate) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DS_CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), DS_BORDER);
        row.setBackground(bg);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        // 左：索引圆章
        LinearLayout circle = new LinearLayout(this);
        GradientDrawable circBg = new GradientDrawable();
        circBg.setShape(GradientDrawable.OVAL);
        circBg.setColor(color);
        circle.setBackground(circBg);
        circle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams circLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        circle.setLayoutParams(circLp);

        TextView num = new TextView(this);
        num.setText(String.valueOf(index));
        num.setTextSize(14);
        num.setTextColor(0xFFFFFFFF);
        num.setTypeface(null, android.graphics.Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        circle.addView(num);
        row.addView(circle);

        // 中间文本
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tap = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tap.leftMargin = dp(12);
        textArea.setLayoutParams(tap);

        if (typeLabel != null && !typeLabel.isEmpty()) {
            // 类型徽章
            LinearLayout typeWrap = new LinearLayout(this);
            GradientDrawable twGd = new GradientDrawable();
            twGd.setColor(color);
            twGd.setCornerRadius(dp(8));
            typeWrap.setBackground(twGd);
            typeWrap.setGravity(Gravity.CENTER);
            typeWrap.setPadding(dp(10), dp(3), dp(10), dp(3));
            LinearLayout.LayoutParams twLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            typeWrap.setLayoutParams(twLp);

            TextView typeTv = new TextView(this);
            typeTv.setText(typeLabel);
            typeTv.setTextSize(10);
            typeTv.setTextColor(0xFFFFFFFF);
            typeTv.setTypeface(null, android.graphics.Typeface.BOLD);
            typeWrap.addView(typeTv);
            textArea.addView(typeWrap);
        }

        String displayText = mainText;
        boolean isLong = truncate && mainText != null && mainText.length() > URL_PREVIEW_LENGTH;
        if (isLong) displayText = mainText.substring(0, URL_PREVIEW_LENGTH) + "...";

        TextView mainTv = new TextView(this);
        mainTv.setText(displayText);
        mainTv.setTextSize(11);
        mainTv.setTextColor(DS_TEXT);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (typeLabel != null && !typeLabel.isEmpty()) mtp.topMargin = dp(4);
        mainTv.setLayoutParams(mtp);
        textArea.addView(mainTv);

        if (isLong) {
            TextView more = new TextView(this);
            more.setText("▸ 点击查看完整内容 (" + mainText.length() + " 字符)");
            more.setTextSize(10);
            more.setTextColor(color);
            more.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mrp.topMargin = dp(6);
            more.setLayoutParams(mrp);
            textArea.addView(more);
        }

        row.addView(textArea);

        if (truncate) {
            TextView arrow = new TextView(this);
            arrow.setText(" ›");
            arrow.setTextSize(22);
            arrow.setTextColor(color);
            arrow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.setMargins(dp(6), 0, 0, 0);
            arrow.setLayoutParams(alp);
            row.addView(arrow);
        }

        return row;
    }

    // ============ 请求详情弹窗 (V2 - 粉紫渐变) ============
    private void showRequestDetailV2(final RequestItem item, int index) {
        int color = DS_ACCENT;
        if (item.type != null) {
            if (item.type.contains("WEBVIEW")) color = DS_VIOLET;
            else if (item.type.contains("OKHTTP")) color = DS_BLUE;
            else if (item.type.contains("URL")) color = DS_ACCENT;
            else if (item.type.contains("SOCKET")) color = DS_ERROR;
        }
        final int finalColor = color;

        ScrollView sv = new ScrollView(this);
        sv.setPadding(0, 0, 0, 0);
        sv.setBackgroundColor(DS_BG);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(16), dp(20), dp(12));
        container.setBackgroundColor(DS_BG);

        // 顶部：徽章编号 + 类型
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(14);
        container.addView(header, hlp);

        // 编号方块
        LinearLayout circle = new LinearLayout(this);
        GradientDrawable circBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{finalColor, DS_VIOLET});
        circBg.setCornerRadius(dp(16));
        circle.setBackground(circBg);
        circle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams circLp = new LinearLayout.LayoutParams(dp(42), dp(42));
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
        headerTexts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView typeTv = new TextView(this);
        typeTv.setText(item.type != null ? item.type.toUpperCase() : "UNKNOWN");
        typeTv.setTextSize(14);
        typeTv.setTextColor(finalColor);
        typeTv.setTypeface(null, android.graphics.Typeface.BOLD);
        headerTexts.addView(typeTv);

        TextView timeLabel = new TextView(this);
        timeLabel.setText(formatFullTime(item.time));
        timeLabel.setTextSize(11);
        timeLabel.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(3);
        timeLabel.setLayoutParams(tlp);
        headerTexts.addView(timeLabel);
        header.addView(headerTexts);

        // 分隔
        View divider = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setColor(DS_BORDER);
        divider.setBackground(dd);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.bottomMargin = dp(14);
        container.addView(divider, dlp);

        // URL 标题
        TextView urlLabel = new TextView(this);
        urlLabel.setText("完整请求 URL");
        urlLabel.setTextSize(12);
        urlLabel.setTextColor(DS_TEXT);
        urlLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams ullp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ullp.bottomMargin = dp(10);
        container.addView(urlLabel, ullp);

        // URL 内容（渐变卡片）
        LinearLayout urlCard = new LinearLayout(this);
        GradientDrawable ucGd = new GradientDrawable();
        ucGd.setColor(0xFFFFF7ED);
        ucGd.setCornerRadius(dp(16));
        ucGd.setStroke(dp(1), DS_BORDER);
        urlCard.setBackground(ucGd);
        urlCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams uclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uclp.bottomMargin = dp(10);

        TextView urlContent = new TextView(this);
        urlContent.setText(item.url != null ? item.url : "(空)");
        urlContent.setTextSize(12);
        urlContent.setTextColor(DS_TEXT);
        urlContent.setAutoLinkMask(Linkify.WEB_URLS);
        urlContent.setLinksClickable(true);
        urlContent.setLineSpacing(dp(2), 1f);
        urlCard.addView(urlContent);
        container.addView(urlCard, uclp);

        // 字符数
        TextView lengthTv = new TextView(this);
        lengthTv.setText("字符长度: " + (item.url != null ? item.url.length() : 0));
        lengthTv.setTextSize(11);
        lengthTv.setTextColor(DS_TEXT_MUTED);
        lengthTv.setGravity(Gravity.RIGHT);
        container.addView(lengthTv);

        sv.addView(container);

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("请求详情");
        builder.setView(sv);
        builder.setPositiveButton("复制 URL", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", item.url));
                    android.widget.Toast.makeText(MainActivity.this,
                            "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    android.widget.Toast.makeText(MainActivity.this,
                            "复制失败: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("关闭", null);

        android.app.AlertDialog dialog = builder.create();
        dialog.show();

        try {
            android.widget.Button posBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            posBtn.setTextColor(finalColor);
            posBtn.setTextSize(13);
            android.widget.Button negBtn = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
            negBtn.setTextColor(DS_TEXT_MUTED);
            negBtn.setTextSize(13);
        } catch (Throwable ignored) {}
    }

    // ============ 工作原理说明卡 (V2 - 阶梯数字卡) ============
    private void addInfoCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(26));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // 标题徽章
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBadge = new LinearLayout(this);
        GradientDrawable tbGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{DS_VIOLET, DS_ACCENT});
        tbGd.setCornerRadius(dp(10));
        titleBadge.setBackground(tbGd);
        titleBadge.setGravity(Gravity.CENTER);
        titleBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView tbText = new TextView(this);
        tbText.setText("> 工作原理");
        tbText.setTextSize(12);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        // 步骤列表
        String[] steps = {
                "LSPosed 在目标应用启动时注入 Hook 代码，监听网络请求",
                "拦截 WebView / OkHttp 请求，解析返回的 JSON 数据",
                "识别题目正确答案后，在页面上高亮标记并自动点击",
                "统计数据通过 ContentProvider 跨进程传递给本模块",
                "本模块读取并展示统计、请求历史等信息"};

        int[] stepColors = {DS_ACCENT, DS_BLUE, DS_SUCCESS, DS_VIOLET, DS_YELLOW};

        for (int i = 0; i < steps.length; i++) {
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.HORIZONTAL);
            stepRow.setGravity(Gravity.CENTER_VERTICAL);

            // 阶梯渐变背景：不同色块交替
            GradientDrawable rowGd = new GradientDrawable();
            rowGd.setColor(DS_CARD);
            rowGd.setCornerRadius(dp(18));
            rowGd.setStroke(dp(1), DS_BORDER);
            stepRow.setBackground(rowGd);
            stepRow.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srlp.topMargin = dp(10);
            stepRow.setLayoutParams(srlp);

            // 数字徽章
            LinearLayout numCircle = new LinearLayout(this);
            GradientDrawable numGd = new GradientDrawable();
            numGd.setShape(GradientDrawable.OVAL);
            numGd.setColor(stepColors[i]);
            numCircle.setBackground(numGd);
            numCircle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            numCircle.setLayoutParams(numLp);

            TextView numTv = new TextView(this);
            numTv.setText(String.valueOf(i + 1));
            numTv.setTextSize(14);
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
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            stlLp.leftMargin = dp(12);
            stepText.setLayoutParams(stlLp);
            stepRow.addView(stepText);

            content.addView(stepRow);
        }

        // 底部提示
        LinearLayout tipBox = new LinearLayout(this);
        tipBox.setOrientation(LinearLayout.HORIZONTAL);
        tipBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable tipGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{DS_YELLOW_LIGHT, DS_CARD});
        tipGd.setCornerRadius(dp(18));
        tipGd.setStroke(dp(1), DS_BORDER);
        tipBox.setBackground(tipGd);
        tipBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(16);
        tipBox.setLayoutParams(tipLp);

        TextView tipIcon = new TextView(this);
        tipIcon.setText("✦");
        tipIcon.setTextSize(14);
        tipIcon.setTextColor(DS_YELLOW);
        tipBox.addView(tipIcon);

        TextView tipTv = new TextView(this);
        tipTv.setText("  每页显示 " + PAGE_SIZE + " 条请求记录，点击分页按钮翻页浏览历史");
        tipTv.setTextSize(11);
        tipTv.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        ttLp.leftMargin = dp(6);
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
