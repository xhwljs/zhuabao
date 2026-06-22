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

        // 4. 模块自己的 SP 兜底 - 自动答题开关以 SP 为准（保证 UI 状态一致）
        try {
            SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
            if (data.requestCount < 0)
                data.requestCount = selfSp.getInt("request_count", -1);
            if (data.targetHitCount < 0)
                data.targetHitCount = selfSp.getInt("target_hit_count", -1);
            if (data.lastHookTime < 0)
                data.lastHookTime = selfSp.getLong("last_hook_time", -1);
            // SP 中保存的开关是用户操作的最终来源，优先级最高
            boolean spAuto = selfSp.getBoolean("auto_select_enabled", data.autoSelectEnabled);
            if (!data.autoSelectLoaded) {
                data.autoSelectEnabled = spAuto;
            } else {
                // 若 ContentProvider 和 SP 不一致，以 SP 用户最后点击的为准
                if (data.autoSelectEnabled != spAuto) data.autoSelectEnabled = spAuto;
            }
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

    // ============ Hero 顶部栏（V3 - 渐变横幅 + 紧凑信息卡） ============
    // 设计：上半部分是粉蓝紫色渐变横幅（大字标题 + 彩色状态标签），下半部分是紧凑的目标应用信息卡
    private void addHeader(LinearLayout root) {
        // === 外层容器（垂直） ===
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        // === 渐变横幅（上半部分） ===
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bannerGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFEC4899, 0xFF8B5CF6, 0xFF3B82F6});
        bannerGd.setCornerRadius(dp(24));
        banner.setBackground(bannerGd);
        banner.setPadding(dp(20), dp(22), dp(20), dp(22));

        // 第一行：大号图标方块 + 标题
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        // 图标方块
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setOrientation(LinearLayout.VERTICAL);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable();
        ibGd.setColor(0xFFFFFFFF);
        ibGd.setCornerRadius(dp(16));
        iconBox.setBackground(ibGd);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        iconBox.setLayoutParams(ibLp);

        TextView iconChar = new TextView(this);
        iconChar.setText("答");
        iconChar.setTextSize(22);
        iconChar.setTextColor(0xFFEC4899);
        iconChar.setGravity(Gravity.CENTER);
        iconChar.setTypeface(null, android.graphics.Typeface.BOLD);
        iconBox.addView(iconChar);
        titleRow.addView(iconBox);

        // 标题文本列
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tclp.leftMargin = dp(14);
        titleCol.setLayoutParams(tclp);

        TextView titleMain = new TextView(this);
        titleMain.setText("答案显示模块");
        titleMain.setTextSize(20);
        titleMain.setTextColor(0xFFFFFFFF);
        titleMain.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(titleMain);

        TextView subTitle = new TextView(this);
        subTitle.setText("LSPosed Hook · 智能答题助手");
        subTitle.setTextSize(11);
        subTitle.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(3);
        subTitle.setLayoutParams(stlp);
        titleCol.addView(subTitle);
        titleRow.addView(titleCol);

        banner.addView(titleRow);

        // 第二行：彩色状态胶囊标签
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = dp(16);
        chipRow.setLayoutParams(crLp);

        // 标签1：v1.0
        LinearLayout chip1 = new LinearLayout(this);
        chip1.setGravity(Gravity.CENTER);
        GradientDrawable ch1Gd = new GradientDrawable();
        ch1Gd.setColor(0xFFFFFFFF);
        ch1Gd.setCornerRadius(dp(100));
        chip1.setBackground(ch1Gd);
        chip1.setPadding(dp(12), dp(6), dp(12), dp(6));
        TextView ch1Tv = new TextView(this);
        ch1Tv.setText("v1.0");
        ch1Tv.setTextSize(11);
        ch1Tv.setTextColor(0xFFEC4899);
        ch1Tv.setTypeface(null, android.graphics.Typeface.BOLD);
        chip1.addView(ch1Tv);
        chipRow.addView(chip1);

        // 标签2：LSPosed
        LinearLayout chip2 = new LinearLayout(this);
        chip2.setGravity(Gravity.CENTER);
        GradientDrawable ch2Gd = new GradientDrawable();
        ch2Gd.setColor(0x33FFFFFF);
        ch2Gd.setCornerRadius(dp(100));
        chip2.setBackground(ch2Gd);
        chip2.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams ch2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ch2Lp.leftMargin = dp(8);
        chip2.setLayoutParams(ch2Lp);
        TextView ch2Tv = new TextView(this);
        ch2Tv.setText("✦ LSPosed");
        ch2Tv.setTextSize(11);
        ch2Tv.setTextColor(0xFFFFFFFF);
        ch2Tv.setTypeface(null, android.graphics.Typeface.BOLD);
        chip2.addView(ch2Tv);
        chipRow.addView(chip2);

        // 标签3：模块包名
        LinearLayout chip3 = new LinearLayout(this);
        chip3.setGravity(Gravity.CENTER);
        GradientDrawable ch3Gd = new GradientDrawable();
        ch3Gd.setColor(0x33FFFFFF);
        ch3Gd.setCornerRadius(dp(100));
        chip3.setBackground(ch3Gd);
        chip3.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams ch3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ch3Lp.leftMargin = dp(8);
        chip3.setLayoutParams(ch3Lp);
        TextView ch3Tv = new TextView(this);
        ch3Tv.setText(MODULE_PACKAGE);
        ch3Tv.setTextSize(10);
        ch3Tv.setTextColor(0xFFFFFFFF);
        ch3Tv.setTypeface(null, android.graphics.Typeface.BOLD);
        chip3.addView(ch3Tv);
        chipRow.addView(chip3);

        banner.addView(chipRow);

        // === 目标应用信息卡（下半部分）===
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable infoGd = new GradientDrawable();
        infoGd.setColor(DS_CARD);
        infoGd.setCornerRadius(dp(20));
        infoGd.setStroke(dp(1), DS_BORDER);
        infoCard.setBackground(infoGd);
        infoCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(14);
        infoCard.setLayoutParams(infoLp);

        // 信息标题行
        LinearLayout infoTitle = new LinearLayout(this);
        infoTitle.setOrientation(LinearLayout.HORIZONTAL);
        infoTitle.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout infoBadge = new LinearLayout(this);
        infoBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeGd = new GradientDrawable();
        badgeGd.setColor(DS_BLUE_LIGHT);
        badgeGd.setCornerRadius(dp(8));
        infoBadge.setBackground(badgeGd);
        infoBadge.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView badgeIcon = new TextView(this);
        badgeIcon.setText("目标应用");
        badgeIcon.setTextSize(10);
        badgeIcon.setTextColor(DS_BLUE);
        badgeIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        infoBadge.addView(badgeIcon);
        infoTitle.addView(infoBadge);
        infoCard.addView(infoTitle);

        // 两行信息
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        String versionName = null;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (pi != null && pi.versionName != null) versionName = pi.versionName;
        } catch (Throwable ignored) {}

        // 第一行：包名 + 状态胶囊
        LinearLayout pkgRow = new LinearLayout(this);
        pkgRow.setOrientation(LinearLayout.HORIZONTAL);
        pkgRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pkgLp.topMargin = dp(12);
        pkgRow.setLayoutParams(pkgLp);

        LinearLayout pkgCol = new LinearLayout(this);
        pkgCol.setOrientation(LinearLayout.VERTICAL);
        pkgCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("包名");
        pkgLabel.setTextSize(10);
        pkgLabel.setTextColor(DS_TEXT_MUTED);
        pkgCol.addView(pkgLabel);

        TextView pkgVal = new TextView(this);
        pkgVal.setText(TARGET_PACKAGE);
        pkgVal.setTextSize(12);
        pkgVal.setTextColor(DS_TEXT);
        pkgVal.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams pvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvlp.topMargin = dp(2);
        pkgVal.setLayoutParams(pvlp);
        pkgCol.addView(pkgVal);
        pkgRow.addView(pkgCol);

        // 状态胶囊
        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable spGd = new GradientDrawable();
        spGd.setColor(installed ? DS_SUCCESS_LIGHT : 0xFFFFE4E6);
        spGd.setCornerRadius(dp(100));
        statusPill.setBackground(spGd);
        statusPill.setPadding(dp(12), dp(6), dp(12), dp(6));

        TextView spIcon = new TextView(this);
        spIcon.setText(installed ? "✓" : "✕");
        spIcon.setTextSize(10);
        spIcon.setTextColor(installed ? DS_SUCCESS : DS_ERROR);
        spIcon.setGravity(Gravity.CENTER);
        spIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        statusPill.addView(spIcon);

        TextView spText = new TextView(this);
        spText.setText(" " + (installed ? "已安装" : "未安装"));
        spText.setTextSize(10);
        spText.setTextColor(installed ? DS_SUCCESS : DS_ERROR);
        spText.setTypeface(null, android.graphics.Typeface.BOLD);
        statusPill.addView(spText);
        pkgRow.addView(statusPill);
        infoCard.addView(pkgRow);

        // 分隔线
        View sep = new View(this);
        GradientDrawable sepGd = new GradientDrawable();
        sepGd.setColor(DS_BORDER);
        sep.setBackground(sepGd);
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        seplp.topMargin = dp(12);
        sep.setLayoutParams(seplp);
        infoCard.addView(sep);

        // 第二行：版本 + 模块标识
        LinearLayout verRow = new LinearLayout(this);
        verRow.setOrientation(LinearLayout.HORIZONTAL);
        verRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout verCol = new LinearLayout(this);
        verCol.setOrientation(LinearLayout.VERTICAL);
        verCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView verLabel = new TextView(this);
        verLabel.setText("版本");
        verLabel.setTextSize(10);
        verLabel.setTextColor(DS_TEXT_MUTED);
        verCol.addView(verLabel);

        TextView verVal = new TextView(this);
        verVal.setText(versionName != null ? "v" + versionName : "未检测到");
        verVal.setTextSize(12);
        verVal.setTextColor(DS_TEXT);
        verVal.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams vvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vvlp.topMargin = dp(2);
        verVal.setLayoutParams(vvlp);
        verCol.addView(verVal);
        verRow.addView(verCol);

        LinearLayout modCol = new LinearLayout(this);
        modCol.setOrientation(LinearLayout.VERTICAL);
        modCol.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        modCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView modLabel = new TextView(this);
        modLabel.setText("模块 ID");
        modLabel.setTextSize(10);
        modLabel.setTextColor(DS_TEXT_MUTED);
        modLabel.setGravity(Gravity.END);
        modCol.addView(modLabel);

        TextView modVal = new TextView(this);
        modVal.setText(MODULE_PACKAGE);
        modVal.setTextSize(10);
        modVal.setTextColor(DS_TEXT_MUTED);
        modVal.setGravity(Gravity.END);
        LinearLayout.LayoutParams mvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mvlp.topMargin = dp(2);
        modVal.setLayoutParams(mvlp);
        modCol.addView(modVal);
        verRow.addView(modCol);
        infoCard.addView(verRow);

        // 组装到外层
        LinearLayout.LayoutParams bnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outer.addView(banner, bnLp);
        outer.addView(infoCard);
        root.addView(outer, cardParams());
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

    // ============ 快捷操作卡（功能亮点风格：图标 + 标题描述 + 开关/按钮） ============
    private void addActionCard(LinearLayout root, StatsData data) {
        final boolean autoOn = data != null && data.autoSelectEnabled;

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

        // 标题徽章
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
        tbText.setText("快捷操作");
        tbText.setTextSize(11);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        // ========== 列表项 1：自动答题开关 ==========
        LinearLayout row1 = buildActionRowV3(
                "⚡", DS_ACCENT, DS_VIOLET,
                "自动答题",
                autoOn ? "已开启，自动识别并点击答案" : "点击右侧开关开启此功能",
                autoOn,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            boolean current = !autoOn;
                            ContentValues cv = new ContentValues();
                            cv.put("auto_select_enabled", current);
                            getContentResolver().update(
                                    android.net.Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"),
                                    cv, null, null);
                        } catch (Throwable ignored) {}
                        try {
                            SharedPreferences sp = getSharedPreferences("module_stats", MODE_PRIVATE);
                            sp.edit().putBoolean("auto_select_enabled", !autoOn).apply();
                        } catch (Throwable ignored) {}
                        Toast.makeText(MainActivity.this,
                                !autoOn ? "✓ 自动答题已开启" : "自动答题已关闭",
                                Toast.LENGTH_SHORT).show();
                        // 刷新整个 UI，确保状态卡与开关同步
                        refreshStatsAsync();
                    }
                },
                true // 使用开关
        );
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Lp.topMargin = dp(14);
        content.addView(row1, row1Lp);

        // ========== 列表项 2：启动目标应用 ==========
        LinearLayout row2 = buildActionRowV3(
                "▶", DS_BLUE, 0xFF1D4ED8,
                "启动目标应用",
                "打开配置的目标应用 (" + TARGET_PACKAGE + ")",
                false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { launchTargetApp(); }
                },
                false
        );
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(10);
        content.addView(row2, row2Lp);

        // ========== 列表项 3：刷新数据 ==========
        LinearLayout row3 = buildActionRowV3(
                "↻", DS_SUCCESS, 0xFF0FB37C,
                "刷新统计数据",
                "从 Hook 进程重新拉取最新数据",
                false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        refreshStatsAsync();
                        Toast.makeText(MainActivity.this, "数据已刷新", Toast.LENGTH_SHORT).show();
                    }
                },
                false
        );
        LinearLayout.LayoutParams row3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row3Lp.topMargin = dp(10);
        content.addView(row3, row3Lp);

        // ========== 列表项 4：清空统计 ==========
        LinearLayout row4 = buildActionRowV3(
                "✕", DS_ERROR, 0xFFB91C1C,
                "清空统计记录",
                "清除已记录的命中次数、请求列表等数据",
                false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
                            boolean savedAuto = selfSp.getBoolean("auto_select_enabled", false);
                            try {
                                getContentResolver().delete(
                                        android.net.Uri.parse("content://" + MODULE_PACKAGE + ".stats/clear"),
                                        null, null);
                            } catch (Throwable ignored) {}
                            selfSp.edit().clear().putBoolean("auto_select_enabled", savedAuto).apply();
                            Toast.makeText(MainActivity.this, "已清空统计", Toast.LENGTH_SHORT).show();
                            refreshStatsAsync();
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this, "清空失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                },
                false
        );
        LinearLayout.LayoutParams row4Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row4Lp.topMargin = dp(10);
        content.addView(row4, row4Lp);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // 工具：构建功能亮点风格的操作行（左：彩色渐变图标，中：标题描述，右：开关或箭头按钮）
    private LinearLayout buildActionRowV3(final String icon, int color1, int color2,
                                          final String title, final String desc,
                                          boolean toggleOn,
                                          final View.OnClickListener action,
                                          boolean useSwitch) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable rowGd = new GradientDrawable();
        rowGd.setColor(DS_CARD);
        rowGd.setCornerRadius(dp(18));
        rowGd.setStroke(dp(1), DS_BORDER);
        row.setBackground(rowGd);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        // 左：彩色渐变图标方块
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setOrientation(LinearLayout.VERTICAL);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{color1, color2});
        ibGd.setCornerRadius(dp(14));
        iconBox.setBackground(ibGd);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconBox.setLayoutParams(ibLp);

        TextView iconTv = new TextView(this);
        iconTv.setText(icon);
        iconTv.setTextSize(18);
        iconTv.setTextColor(0xFFFFFFFF);
        iconTv.setGravity(Gravity.CENTER);
        iconTv.setTypeface(null, android.graphics.Typeface.BOLD);
        iconBox.addView(iconTv);
        row.addView(iconBox);

        // 中：标题 + 描述
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tclp.leftMargin = dp(14);
        textCol.setLayoutParams(tclp);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(14);
        titleTv.setTextColor(DS_TEXT);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(titleTv);

        TextView descTv = new TextView(this);
        descTv.setText(desc);
        descTv.setTextSize(11);
        descTv.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        descTv.setLayoutParams(dlp);
        textCol.addView(descTv);
        row.addView(textCol);

        // 右：开关或箭头按钮
        if (useSwitch) {
            // 开关控件
            final android.widget.FrameLayout track = new android.widget.FrameLayout(this);
            final GradientDrawable trackGd = new GradientDrawable();
            trackGd.setColor(toggleOn ? color1 : DS_BORDER_2);
            trackGd.setCornerRadius(dp(20));
            track.setBackground(trackGd);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(dp(64), dp(34));
            track.setLayoutParams(tLp);

            final View thumb = new View(this);
            GradientDrawable thumbGd = new GradientDrawable();
            thumbGd.setShape(GradientDrawable.OVAL);
            thumbGd.setColor(0xFFFFFFFF);
            thumbGd.setStroke(dp(2), toggleOn ? color2 : 0xFF787878);
            thumb.setBackground(thumbGd);
            final android.widget.FrameLayout.LayoutParams thumbLp =
                    new android.widget.FrameLayout.LayoutParams(dp(28), dp(28),
                            toggleOn ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                                     : (Gravity.LEFT | Gravity.CENTER_VERTICAL));
            thumbLp.setMargins(dp(3), dp(3), dp(3), dp(3));
            thumb.setLayoutParams(thumbLp);
            track.addView(thumb);
            row.addView(track);

            // 整行点击触发开关
            row.setOnClickListener(action);
        } else {
            // 箭头图标（可点击整行）
            LinearLayout arrowBox = new LinearLayout(this);
            arrowBox.setGravity(Gravity.CENTER);
            GradientDrawable abGd = new GradientDrawable();
            abGd.setColor(0xFFFFF7ED);
            abGd.setCornerRadius(dp(100));
            abGd.setStroke(dp(1), DS_BORDER);
            arrowBox.setBackground(abGd);
            LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(dp(32), dp(32));
            arrowBox.setLayoutParams(abLp);

            TextView arrowTv = new TextView(this);
            arrowTv.setText("›");
            arrowTv.setTextSize(20);
            arrowTv.setTextColor(color1);
            arrowTv.setGravity(Gravity.CENTER);
            arrowTv.setTypeface(null, android.graphics.Typeface.BOLD);
            arrowBox.addView(arrowTv);
            row.addView(arrowBox);

            row.setOnClickListener(action);
        }

        return row;
    }
    // ============ 统计数据卡（V3 - 列表+进度条形式） ============
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

        // === 标题徽章
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
        tbText.setText("数据统计");
        tbText.setTextSize(11);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);
        content.addView(titleRow);

        // 数据准备
        int hh = data != null ? data.targetHitCount : -1;
        int rc = data != null ? data.requestCount : -1;
        long lt = data != null ? data.lastHookTime : -1;
        boolean hasAnyData = (hh >= 0 || rc >= 0 || lt > 0);

        // 计算最大值用于进度条归一化
        int maxVal = Math.max(Math.max(Math.max(hh, 0), rc), 1);
        if (maxVal < 10) maxVal = 10;

        // ============ 条目 1：命中次数 ============
        View row1 = buildStatRowV3(
                "📊", "命中次数",
                hh < 0 ? "—" : String.valueOf(hh),
                "答题题目命中次数",
                DS_ACCENT, DS_VIOLET,
                hh < 0 ? 0 : (float) hh / maxVal);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);
        row1.setLayoutParams(rlp);
        content.addView(row1);

        // ============ 条目 2：请求总数 ============
        View row2 = buildStatRowV3(
                "🌐", "请求总数",
                rc < 0 ? "—" : String.valueOf(rc),
                "已拦截的 HTTP 请求数量",
                DS_BLUE, DS_BLUE_DARK,
                rc < 0 ? 0 : (float) rc / maxVal);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = dp(10);
        row2.setLayoutParams(r2lp);
        content.addView(row2);

        // ============ 条目 3：最近活跃 ============
        View row3 = buildStatRowV3(
                "⏰", "最近活跃",
                lt <= 0 ? "—" : formatTime(lt),
                "最后一次 Hook 时间",
                DS_YELLOW, 0xFFD97706,
                lt > 0 ? 1.0f : 0.0f);
        LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r3lp.topMargin = dp(10);
        row3.setLayoutParams(r3lp);
        content.addView(row3);

        // === 空状态提示（如所有数据为空时显示） ===
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

    // 工具：构建统计行（V3 - 图标 + 标题 + 数值 + 进度条）
    private View buildStatRowV3(String icon, String label, String value, String desc,
                                 int color1, int color2, float progress) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable rowGd = new GradientDrawable();
        rowGd.setColor(DS_CARD);
        rowGd.setCornerRadius(dp(18));
        rowGd.setStroke(dp(1), DS_BORDER);
        row.setBackground(rowGd);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        // 左侧图标方块
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{color1, color2});
        ibGd.setCornerRadius(dp(12));
        iconBox.setBackground(ibGd);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconBox.setLayoutParams(ibLp);

        TextView iconTv = new TextView(this);
        iconTv.setText(icon);
        iconTv.setTextSize(16);
        iconTv.setGravity(Gravity.CENTER);
        iconBox.addView(iconTv);
        row.addView(iconBox);

        // 中间文本列（标题 + 描述 + 进度条）
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tclp.leftMargin = dp(14);
        textCol.setLayoutParams(tclp);

        // 标题行（标签 + 数值）
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(13);
        labelTv.setTextColor(DS_TEXT);
        labelTv.setTypeface(null, android.graphics.Typeface.BOLD);
        labelRow.addView(labelTv);

        TextView spacer = new TextView(this);
        spacer.setText("  ");
        spacer.setTextSize(12);
        labelRow.addView(spacer);

        TextView valueTv = new TextView(this);
        valueTv.setText(value);
        valueTv.setTextSize(15);
        valueTv.setTextColor(color1);
        valueTv.setTypeface(null, android.graphics.Typeface.BOLD);
        labelRow.addView(valueTv);

        textCol.addView(labelRow);

        // 描述文字
        TextView descTv = new TextView(this);
        descTv.setText(desc);
        descTv.setTextSize(10);
        descTv.setTextColor(DS_TEXT_MUTED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        descTv.setLayoutParams(dlp);
        textCol.addView(descTv);

        // 进度条（渐变）
        LinearLayout progressBar = new LinearLayout(this);
        progressBar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable pbGd = new GradientDrawable();
        pbGd.setColor(0xFFF0E6E0);
        pbGd.setCornerRadius(dp(100));
        progressBar.setBackground(pbGd);
        progressBar.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pblp.topMargin = dp(10);
        progressBar.setLayoutParams(pblp);

        // 进度填充部分（根据百分比）
        View progressFill = new View(this);
        GradientDrawable pfgd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{color1, color2});
        pfgd.setCornerRadius(dp(100));
        progressFill.setBackground(pfgd);
        int fillWeight = (int) (progress * 1000);
        LinearLayout.LayoutParams pflp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, fillWeight > 0 ? fillWeight : 1);
        if (fillWeight == 0) progressFill.setVisibility(View.GONE);
        progressFill.setLayoutParams(pflp);
        progressBar.addView(progressFill);
        textCol.addView(progressBar);

        row.addView(textCol);
        return row;
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
    // ============ 最近请求记录（V3 - 折叠摘要形式） ============
    // 只显示前 3 条，剩余通过"展开/收起"切换；每行更紧凑，单行显示
    private void addRecentRequestsCard(LinearLayout root, StatsData data) {
        final java.util.List<RequestItem> requests = data != null ? data.requests : null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(DS_CARD);
        cardGd.setCornerRadius(dp(26));
        cardGd.setStroke(dp(1), DS_BORDER);
        card.setBackground(cardGd);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        // === 标题徽章 + 计数（同一行）
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
        tbText.setText("最近请求");
        tbText.setTextSize(11);
        tbText.setTextColor(0xFFFFFFFF);
        tbText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBadge.addView(tbText);
        titleRow.addView(titleBadge);

        if (requests != null && !requests.isEmpty()) {
            TextView countTv = new TextView(this);
            countTv.setText("  ·  共 " + requests.size() + " 条记录");
            countTv.setTextSize(11);
            countTv.setTextColor(DS_TEXT_MUTED);
            titleRow.addView(countTv);
        }
        content.addView(titleRow);

        if (requests != null && !requests.isEmpty()) {
            // 容器：所有请求行（初始只显示前3条）
            final LinearLayout listWrap = new LinearLayout(this);
            listWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(14);
            listWrap.setLayoutParams(wlp);

            final int totalCount = requests.size();
            final int previewCount = Math.min(3, totalCount);

            for (int i = 0; i < totalCount; i++) {
                final RequestItem item = requests.get(i);
                final int idx = i + 1;
                int color = DS_BLUE;
                if (item.type != null) {
                    if (item.type.contains("WEBVIEW")) color = DS_VIOLET;
                    else if (item.type.contains("OKHTTP")) color = DS_BLUE;
                    else if (item.type.contains("URL")) color = DS_ACCENT;
                    else if (item.type.contains("SOCKET")) color = DS_ERROR;
                }
                View row = makeCompactRequestRowV3(idx, item.url, item.type, color,
                        item.time);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showRequestDetailV2(item, idx);
                    }
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(6);
                row.setLayoutParams(rlp);

                if (i < previewCount) {
                    row.setVisibility(View.VISIBLE);
                } else {
                    row.setVisibility(View.GONE);
                }
                listWrap.addView(row);
            }
            content.addView(listWrap);

            // === 展开/收起按钮（如果总数 > 预览数）
            if (totalCount > previewCount) {
                final LinearLayout toggleBtnWrap = new LinearLayout(this);
                toggleBtnWrap.setOrientation(LinearLayout.HORIZONTAL);
                toggleBtnWrap.setGravity(Gravity.CENTER);
                GradientDrawable tbg = new GradientDrawable();
                tbg.setColor(0xFFFFF7ED);
                tbg.setCornerRadius(dp(100));
                tbg.setStroke(dp(1), DS_BORDER);
                toggleBtnWrap.setBackground(tbg);
                toggleBtnWrap.setPadding(dp(18), dp(10), dp(18), dp(10));
                LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tblp.topMargin = dp(14);
                toggleBtnWrap.setLayoutParams(tblp);

                final TextView toggleBtnText = new TextView(this);
                toggleBtnText.setText("▾ 展开全部 " + totalCount + " 条记录");
                toggleBtnText.setTextSize(11);
                toggleBtnText.setTextColor(DS_BLUE);
                toggleBtnText.setGravity(Gravity.CENTER);
                toggleBtnText.setTypeface(null, android.graphics.Typeface.BOLD);
                toggleBtnWrap.addView(toggleBtnText);

                final boolean[] expanded = {false};
                toggleBtnWrap.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        expanded[0] = !expanded[0];
                        for (int i = 0; i < listWrap.getChildCount(); i++) {
                            View child = listWrap.getChildAt(i);
                            if (expanded[0] || i < previewCount) {
                                child.setVisibility(View.VISIBLE);
                            } else if (i >= previewCount) {
                                child.setVisibility(View.GONE);
                            }
                        }
                        toggleBtnText.setText(
                                expanded[0]
                                        ? "▴ 收起（目前显示全部 " + totalCount + " 条）"
                                        : "▾ 展开全部 " + totalCount + " 条记录");
                    }
                });
                content.addView(toggleBtnWrap);
            }
        } else {
            // 空状态（更紧凑）
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.HORIZONTAL);
            emptyBox.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable ebGd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{DS_ACCENT_LIGHT, 0xFFFFF7ED});
            ebGd.setCornerRadius(dp(18));
            ebGd.setStroke(dp(1), DS_BORDER);
            emptyBox.setBackground(ebGd);
            emptyBox.setPadding(dp(18), dp(16), dp(18), dp(16));
            LinearLayout.LayoutParams eblp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eblp.topMargin = dp(14);
            emptyBox.setLayoutParams(eblp);

            TextView ei = new TextView(this);
            ei.setText("📭");
            ei.setTextSize(18);
            ei.setGravity(Gravity.CENTER);
            emptyBox.addView(ei);

            TextView empty = new TextView(this);
            empty.setText("  尚未捕获任何请求，先打开目标应用");
            empty.setTextSize(11);
            empty.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            etlp.leftMargin = dp(10);
            empty.setLayoutParams(etlp);
            emptyBox.addView(empty);

            content.addView(emptyBox);
        }

        card.addView(content);
        root.addView(card, cardParams());
    }

    // 工具：构建紧凑的请求行（V3 - 单行更紧凑）
    private View makeCompactRequestRowV3(int index, String url, String typeLabel,
                                          int color, long time) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DS_CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), DS_BORDER);
        row.setBackground(bg);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        // 左侧：小圆形索引徽章
        LinearLayout circle = new LinearLayout(this);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable cGd = new GradientDrawable();
        cGd.setShape(GradientDrawable.OVAL);
        cGd.setColor(color);
        circle.setBackground(cGd);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        circle.setLayoutParams(cLp);

        TextView num = new TextView(this);
        num.setText(String.valueOf(index));
        num.setTextSize(11);
        num.setTextColor(0xFFFFFFFF);
        num.setTypeface(null, android.graphics.Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        circle.addView(num);
        row.addView(circle);

        // 中间：类型标签 + URL 截断（单行）
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tap = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tap.leftMargin = dp(10);
        textArea.setLayoutParams(tap);

        // 第一行：类型标签（小胶囊）+ 时间
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);

        if (typeLabel != null && !typeLabel.isEmpty()) {
            LinearLayout typePill = new LinearLayout(this);
            typePill.setGravity(Gravity.CENTER);
            GradientDrawable tpGd = new GradientDrawable();
            tpGd.setColor(color);
            tpGd.setCornerRadius(dp(100));
            typePill.setBackground(tpGd);
            typePill.setPadding(dp(8), dp(3), dp(8), dp(3));

            TextView typeTv = new TextView(this);
            typeTv.setText(typeLabel.length() > 8 ? typeLabel.substring(0, 8) : typeLabel);
            typeTv.setTextSize(9);
            typeTv.setTextColor(0xFFFFFFFF);
            typeTv.setTypeface(null, android.graphics.Typeface.BOLD);
            typeTv.setGravity(Gravity.CENTER);
            typePill.addView(typeTv);
            metaRow.addView(typePill);
        }

        // 时间戳（小文本）
        if (time > 0) {
            TextView tvTime = new TextView(this);
            tvTime.setText("  ·  " + formatTime(time));
            tvTime.setTextSize(10);
            tvTime.setTextColor(DS_TEXT_DIM);
            metaRow.addView(tvTime);
        }
        textArea.addView(metaRow);

        // URL（截断，单行）
        String shortUrl = url;
        if (shortUrl != null && shortUrl.length() > 50) {
            shortUrl = shortUrl.substring(0, 47) + "...";
        }
        if (shortUrl != null) {
            TextView urlTv = new TextView(this);
            urlTv.setText(shortUrl);
            urlTv.setTextSize(11);
            urlTv.setTextColor(DS_TEXT);
            urlTv.setSingleLine(true);
            LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            urlLp.topMargin = dp(2);
            urlTv.setLayoutParams(urlLp);
            textArea.addView(urlTv);
        }

        row.addView(textArea);

        // 右侧：展开箭头（小）
        TextView arrow = new TextView(this);
        arrow.setText(" ›");
        arrow.setTextSize(20);
        arrow.setTextColor(color);
        arrow.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(arrow);

        return row;
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
