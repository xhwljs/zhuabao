package com.answer.revealer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String MODULE_PACKAGE = "com.answer.revealer";

    // ContentProvider URIs
    private static final Uri URI_QUERY = Uri.parse("content://com.answer.revealer.stats/query");
    private static final Uri URI_CLEAR = Uri.parse("content://com.answer.revealer.stats/clear");
    private static final Uri URI_UPDATE = Uri.parse("content://com.answer.revealer.stats/update");

    // ============ UI/UX Pro Max 亮色设计系统 ============
    // Pattern: Data-Dense Dashboard | Style: Light Clean
    private static final int DS_BG           = 0xFFF5F7FA; // 浅灰背景
    private static final int DS_CARD         = 0xFFFFFFFF; // 白色卡片
    private static final int DS_CARD_SOFT    = 0xFFF0F4F8; // 副卡浅灰
    private static final int DS_BORDER       = 0xFFD0D7E0; // 边框
    private static final int DS_TEXT         = 0xFF1A1A2E; // 主文字深色
    private static final int DS_TEXT_SECOND  = 0xFF5C6674; // 次要文字
    private static final int DS_TEXT_MUTED   = 0xFF8E9AAF; // 三级文字
    private static final int DS_PRIMARY      = 0xFF2196F3; // 蓝色主色
    private static final int DS_PRIMARY_DARK = 0xFF1565C0; // 蓝色深色
    private static final int DS_ACCENT       = 0xFF4CAF50; // 绿色强调
    private static final int DS_ACCENT_DARK  = 0xFF2E7D32; // 绿色深色
    private static final int DS_YELLOW       = 0xFFFF9800; // 橙色
    private static final int DS_YELLOW_DARK  = 0xFFF57C00; // 橙色深色
    private static final int DS_VIOLET       = 0xFF9C27B0; // 紫色
    private static final int DS_ERROR        = 0xFFF44336; // 红色
    private static final int DS_GRAY         = 0xFFBDBDBD; // 灰色（未激活）

    private Handler mHandler;
    private StatsData mData;

    // ============ 启动优化：主题背景 + 骨架屏 ============
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());

        // 先显示骨架屏（快速响应）
        setContentView(buildSkeletonUI());

        // 后台线程加载数据
        new Thread(() -> {
            final StatsData data = loadStatsData();
            mHandler.post(() -> {
                mData = data;
                renderFullUI();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mData != null) {
            refreshStatsAsync();
        }
    }

    // ============ 骨架屏 ============
    private View buildSkeletonUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(DS_BG);
        sv.setPadding(0, dp(4), 0, dp(40));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(16));

        addSkeletonBlock(root, dp(100), dp(12));
        addSkeletonBlock(root, dp(70), dp(12));
        addSkeletonBlock(root, dp(60), dp(12));
        addSkeletonBlock(root, dp(50), dp(12));

        sv.addView(root);
        return sv;
    }

    private void addSkeletonBlock(LinearLayout parent, int height, int topMargin) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD_SOFT);
        gd.setCornerRadius(dp(12));

        View v = new View(this);
        v.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = topMargin;
        v.setLayoutParams(lp);
        parent.addView(v);
    }

    private void refreshStatsAsync() {
        new Thread(() -> {
            final StatsData data = loadStatsData();
            mHandler.post(() -> {
                mData = data;
                renderFullUI();
            });
        }).start();
    }

    // ============ 完整 UI ============
    private void renderFullUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(DS_BG);
        sv.setPadding(0, dp(4), 0, dp(40));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(16));

        // 1. Hero 顶部卡片（优化）
        addHeroCard(root);

        // 2. 快捷操作（开关+按钮）
        addActionCard(root);

        // 3. 状态三列卡片（模块+自动答题+自动下一题）
        addStatusTripleCard(root);

        // 4. 统计数据（紧凑三列）
        addStatsCard(root);

        // 5. 工作原理
        addInfoCard(root);

        sv.addView(root);
        setContentView(sv);
    }

    // ============ Hero 顶部卡片（亮色优化） ============
    private void addHeroCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFFFFF, 0xFFF0F4F8});
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 第一行：图标 + 标题 + 版本
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        // 图标（蓝色圆角方块）
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable();
        ibGd.setColor(DS_PRIMARY);
        ibGd.setCornerRadius(dp(12));
        iconBox.setBackground(ibGd);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView iconChar = new TextView(this);
        iconChar.setText("答");
        iconChar.setTextSize(18);
        iconChar.setTextColor(0xFFFFFFFF);
        iconChar.setGravity(Gravity.CENTER);
        iconChar.setTypeface(null, android.graphics.Typeface.BOLD);
        iconBox.addView(iconChar);
        titleRow.addView(iconBox);

        // 标题列
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tclp.leftMargin = dp(12);
        titleCol.setLayoutParams(tclp);

        TextView titleMain = new TextView(this);
        titleMain.setText("答案显示模块");
        titleMain.setTextSize(16);
        titleMain.setTextColor(DS_TEXT);
        titleMain.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(titleMain);

        TextView subTitle = new TextView(this);
        subTitle.setText("LSPosed Hook · 智能答题助手");
        subTitle.setTextSize(11);
        subTitle.setTextColor(DS_TEXT_SECOND);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(2);
        subTitle.setLayoutParams(stlp);
        titleCol.addView(subTitle);
        titleRow.addView(titleCol);

        // 版本标签
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER);
        GradientDrawable chGd = new GradientDrawable();
        chGd.setColor(DS_CARD_SOFT);
        chGd.setCornerRadius(dp(100));
        chip.setBackground(chGd);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));

        TextView chTv = new TextView(this);
        chTv.setText("v1.0");
        chTv.setTextSize(10);
        chTv.setTextColor(DS_PRIMARY);
        chTv.setTypeface(null, android.graphics.Typeface.BOLD);
        chip.addView(chTv);
        titleRow.addView(chip);

        card.addView(titleRow);

        // 第二行：目标应用信息
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        String versionName = null;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (pi != null && pi.versionName != null) versionName = pi.versionName;
        } catch (Throwable ignored) {}

        LinearLayout pkgRow = new LinearLayout(this);
        pkgRow.setOrientation(LinearLayout.HORIZONTAL);
        pkgRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pkgLp.topMargin = dp(12);
        pkgRow.setLayoutParams(pkgLp);

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("目标应用: " + TARGET_PACKAGE);
        pkgLabel.setTextSize(11);
        pkgLabel.setTextColor(DS_TEXT_SECOND);
        pkgRow.addView(pkgLabel);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        pkgRow.addView(spacer);

        // 状态点 + 文字
        View statusDot = new View(this);
        GradientDrawable sdGd = new GradientDrawable();
        sdGd.setShape(GradientDrawable.OVAL);
        sdGd.setColor(installed ? DS_ACCENT : DS_ERROR);
        statusDot.setBackground(sdGd);
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        sdLp.rightMargin = dp(6);
        statusDot.setLayoutParams(sdLp);
        pkgRow.addView(statusDot);

        TextView statusText = new TextView(this);
        statusText.setText(installed ? "已安装" + (versionName != null ? " v" + versionName : "") : "未安装");
        statusText.setTextSize(11);
        statusText.setTextColor(installed ? DS_ACCENT : DS_ERROR);
        pkgRow.addView(statusText);

        card.addView(pkgRow);
        root.addView(card, cardParams());
    }

    // ============ 快捷操作卡片 ============
    private void addActionCard(LinearLayout root) {
        final boolean autoOn = mData != null && mData.autoSelectEnabled;
        final boolean autoNextOn = mData != null && mData.autoNextEnabled;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        // 标题
        TextView title = new TextView(this);
        title.setText("快捷操作");
        title.setTextSize(12);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        // 开关行（两列）
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.topMargin = dp(10);
        switchRow.setLayoutParams(swLp);

        // 自动答题开关
        View switch1 = buildSwitch("自动答题", autoOn, DS_ACCENT, v -> toggleAutoSelect(!autoOn));
        switch1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(switch1);

        View spacer1 = new View(this);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        switchRow.addView(spacer1);

        // 自动下一题开关
        View switch2 = buildSwitch("自动下一题", autoNextOn, DS_YELLOW, v -> toggleAutoNext(!autoNextOn));
        switch2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(switch2);

        card.addView(switchRow);

        // 按钮行（三列）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(10);
        btnRow.setLayoutParams(brLp);

        View btn1 = buildButton("启动应用", DS_PRIMARY, v -> launchTargetApp());
        btn1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btn1);

        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        btnRow.addView(spacer2);

        View btn2 = buildButton("刷新数据", DS_ACCENT, v -> {
            refreshStatsAsync();
            Toast.makeText(this, "数据已刷新", Toast.LENGTH_SHORT).show();
        });
        btn2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btn2);

        View spacer3 = new View(this);
        spacer3.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        btnRow.addView(spacer3);

        View btn3 = buildButton("清空记录", DS_ERROR, v -> showClearConfirm());
        btn3.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btn3);

        card.addView(btnRow);
        root.addView(card, cardParams());
    }

    private View buildSwitch(String label, boolean on, int color, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DS_CARD_SOFT);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setOnClickListener(click);

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(12);
        labelTv.setTextColor(DS_TEXT);
        labelTv.setTypeface(null, android.graphics.Typeface.BOLD);
        labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelTv);

        // 状态指示器
        View indicator = new View(this);
        GradientDrawable indGd = new GradientDrawable();
        indGd.setShape(GradientDrawable.OVAL);
        indGd.setColor(on ? color : DS_GRAY);
        indicator.setBackground(indGd);
        indicator.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
        row.addView(indicator);

        return row;
    }

    private View buildButton(String label, int color, View.OnClickListener click) {
        LinearLayout btn = new LinearLayout(this);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(10));
        btn.setBackground(gd);
        btn.setPadding(dp(8), dp(8), dp(8), dp(8));
        btn.setOnClickListener(click);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.addView(tv);

        return btn;
    }

    // ============ 状态三列卡片（模块+自动答题+自动下一题） ============
    private void addStatusTripleCard(LinearLayout root) {
        boolean active = mData != null && mData.moduleActive;
        boolean autoOn = mData != null && mData.autoSelectEnabled;
        boolean autoNextOn = mData != null && mData.autoNextEnabled;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(8), dp(10), dp(8), dp(10));

        // 列1：模块状态
        addStatusCol(card, active ? "✓" : "!", active ? "已激活" : "未激活",
                active ? DS_ACCENT : DS_GRAY, active ? DS_ACCENT_DARK : DS_GRAY);

        addStatusDivider(card);

        // 列2：自动答题
        addStatusCol(card, autoOn ? "⚡" : "○", autoOn ? "答题开" : "答题关",
                autoOn ? DS_PRIMARY : DS_GRAY, autoOn ? DS_PRIMARY_DARK : DS_GRAY);

        addStatusDivider(card);

        // 列3：自动下一题
        addStatusCol(card, autoNextOn ? "→" : "○", autoNextOn ? "下一题开" : "下一题关",
                autoNextOn ? DS_YELLOW : DS_GRAY, autoNextOn ? DS_YELLOW_DARK : DS_GRAY);

        root.addView(card, cardParams());
    }

    private void addStatusCol(LinearLayout parent, String icon, String title, int colorStart, int colorEnd) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.BR_TL,
                new int[]{colorStart, colorEnd});
        gd.setCornerRadius(dp(10));
        col.setBackground(gd);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView iconTv = new TextView(this);
        iconTv.setText(icon);
        iconTv.setTextSize(16);
        iconTv.setTextColor(0xFFFFFFFF);
        iconTv.setGravity(Gravity.CENTER);
        col.addView(iconTv);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(11);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(4);
        titleTv.setLayoutParams(tlp);
        col.addView(titleTv);

        parent.addView(col);
    }

    private void addStatusDivider(LinearLayout parent) {
        View div = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_BORDER);
        div.setBackground(gd);
        div.setLayoutParams(new LinearLayout.LayoutParams(dp(4), dp(40)));
        parent.addView(div);
    }

    // ============ 统计数据卡片 ============
    private void addStatsCard(LinearLayout root) {
        int hh = mData != null ? mData.targetHitCount : -1;
        long lt = mData != null ? mData.lastHookTime : -1;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        addStatCol(card, "命中次数", hh < 0 ? "—" : String.valueOf(hh), DS_ACCENT);
        addStatDivider(card);
        addStatCol(card, "最近活跃", lt <= 0 ? "—" : formatTimeShort(lt), DS_YELLOW);

        root.addView(card, cardParams());
    }

    private void addStatCol(LinearLayout parent, String label, String value, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(20);
        val.setTextColor(color);
        val.setGravity(Gravity.CENTER);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(val);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(10);
        lbl.setTextColor(DS_TEXT_MUTED);
        lbl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(2);
        lbl.setLayoutParams(llp);
        col.addView(lbl);

        parent.addView(col);
    }

    private void addStatDivider(LinearLayout parent) {
        View div = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_BORDER);
        div.setBackground(gd);
        div.setLayoutParams(new LinearLayout.LayoutParams(1, dp(32)));
        parent.addView(div);
    }

    // ============ 工作原理卡片 ============
    private void addInfoCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("工作原理");
        title.setTextSize(12);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        String[] steps = {
                "LSPosed 注入 Hook，监听网络请求",
                "拦截 WebView/OkHttp，解析 JSON 数据",
                "识别答案并自动点击选项",
                "ContentProvider 跨进程共享数据"
        };

        for (int i = 0; i < steps.length; i++) {
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.HORIZONTAL);
            stepRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srp.topMargin = dp(6);
            stepRow.setLayoutParams(srp);

            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setTextSize(10);
            num.setTextColor(0xFFFFFFFF);
            num.setGravity(Gravity.CENTER);
            GradientDrawable numBg = new GradientDrawable();
            numBg.setShape(GradientDrawable.OVAL);
            numBg.setColor(DS_VIOLET);
            num.setBackground(numBg);
            num.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
            stepRow.addView(num);

            TextView stepTv = new TextView(this);
            stepTv.setText(steps[i]);
            stepTv.setTextSize(11);
            stepTv.setTextColor(DS_TEXT_SECOND);
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            stlp.leftMargin = dp(8);
            stepTv.setLayoutParams(stlp);
            stepRow.addView(stepTv);

            card.addView(stepRow);
        }

        root.addView(card, cardParams());
    }

    // ============ 工具方法 ============
    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        return p;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private String formatTimeShort(long t) {
        if (t <= 0) return "—";
        long diff = System.currentTimeMillis() - t;
        if (diff < 0) diff = 0;
        if (diff < 60 * 1000L) return (diff / 1000L) + "秒";
        if (diff < 60 * 60 * 1000L) return (diff / 60000L) + "分";
        if (diff < 24 * 60 * 60 * 1000L) return (diff / 3600000L) + "时";
        return (diff / 86400000L) + "天";
    }

    private void toggleAutoSelect(boolean on) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("auto_select_enabled", on);
            getContentResolver().update(Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"), cv, null, null);
        } catch (Throwable ignored) {}
        try {
            SharedPreferences sp = getSharedPreferences("module_stats", MODE_PRIVATE);
            sp.edit().putBoolean("auto_select_enabled", on).apply();
        } catch (Throwable ignored) {}
        Toast.makeText(this, on ? "✓ 自动答题已开启" : "自动答题已关闭", Toast.LENGTH_SHORT).show();
        refreshStatsAsync();
    }

    private void toggleAutoNext(boolean on) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("auto_next_enabled", on);
            getContentResolver().update(Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"), cv, null, null);
        } catch (Throwable ignored) {}
        try {
            SharedPreferences sp = getSharedPreferences("module_stats", MODE_PRIVATE);
            sp.edit().putBoolean("auto_next_enabled", on).apply();
        } catch (Throwable ignored) {}
        Toast.makeText(this, on ? "✓ 自动下一题已开启" : "自动下一题已关闭", Toast.LENGTH_SHORT).show();
        refreshStatsAsync();
    }

    private void showClearConfirm() {
        new AlertDialog.Builder(this)
                .setTitle("清空统计记录")
                .setMessage("确定要清空所有统计和请求记录吗？")
                .setPositiveButton("清空", (d, w) -> {
                    try {
                        SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
                        boolean savedAuto = selfSp.getBoolean("auto_select_enabled", false);
                        boolean savedNext = selfSp.getBoolean("auto_next_enabled", false);
                        try {
                            getContentResolver().delete(Uri.parse("content://" + MODULE_PACKAGE + ".stats/clear"), null, null);
                        } catch (Throwable ignored) {}
                        selfSp.edit().clear()
                                .putBoolean("auto_select_enabled", savedAuto)
                                .putBoolean("auto_next_enabled", savedNext)
                                .apply();
                    } catch (Throwable t) {}
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                    refreshStatsAsync();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchTargetApp() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                Toast.makeText(this, "正在启动…", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(TARGET_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "正在启动…", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动，请手动打开", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) { return false; }
    }

    public static boolean isModuleActive() {
        return false;
    }

    // ============ 数据模型 ============
    private static class StatsData {
        boolean moduleActive = false;
        int targetHitCount = -1;
        long lastHookTime = -1;
        boolean autoSelectEnabled = false;
        boolean autoSelectLoaded = false;
        boolean autoNextEnabled = false;
        boolean autoNextLoaded = false;
    }

    private StatsData loadStatsData() {
        StatsData data = new StatsData();

        // ContentProvider 查询
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(URI_QUERY, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String key = cursor.getString(cursor.getColumnIndex("key"));
                    long value = cursor.getLong(cursor.getColumnIndex("value"));
                    String valueStr = null;
                    try { valueStr = cursor.getString(cursor.getColumnIndex("value_str")); } catch (Throwable ignored) {}
                    if ("target_hit_count".equals(key)) data.targetHitCount = (int) value;
                    else if ("last_hook_time".equals(key)) data.lastHookTime = value;
                    else if ("auto_select_enabled".equals(key)) {
                        data.autoSelectEnabled = value > 0 || "1".equals(valueStr) || "true".equalsIgnoreCase(valueStr);
                        data.autoSelectLoaded = true;
                    } else if ("auto_next_enabled".equals(key)) {
                        data.autoNextEnabled = value > 0 || "1".equals(valueStr) || "true".equalsIgnoreCase(valueStr);
                        data.autoNextLoaded = true;
                    }
                } while (cursor.moveToNext());
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
        }

        // SharedPreferences
        try {
            SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
            boolean spAuto = selfSp.getBoolean("auto_select_enabled", data.autoSelectEnabled);
            if (!data.autoSelectLoaded || data.autoSelectEnabled != spAuto) data.autoSelectEnabled = spAuto;
            boolean spNext = selfSp.getBoolean("auto_next_enabled", data.autoNextEnabled);
            if (!data.autoNextLoaded || data.autoNextEnabled != spNext) data.autoNextEnabled = spNext;
            if (data.targetHitCount < 0) data.targetHitCount = selfSp.getInt("target_hit_count", -1);
            if (data.lastHookTime < 0) data.lastHookTime = selfSp.getLong("last_hook_time", -1);
        } catch (Throwable ignored) {}

        try { data.moduleActive = isModuleActive(); } catch (Throwable ignored) {}

        return data;
    }
}