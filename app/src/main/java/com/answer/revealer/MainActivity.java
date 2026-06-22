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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
    private static final String KEY_AUTO_NEXT = "auto_next_enabled";

    // ============ UI/UX Pro Max 设计系统 V3 ============
    // Pattern: Data-Dense Dashboard | Style: Dark Compact
    // 暗色主题 + 紧凑布局，重要操作在首屏，功能区上移
    private static final int DS_BG           = 0xFF0F172A; // slate-900 深色背景
    private static final int DS_CARD         = 0xFF1E293B; // slate-800 卡片
    private static final int DS_CARD_SOFT    = 0xFF334155; // slate-700 副卡
    private static final int DS_BORDER       = 0xFF334155; // slate-700 边框
    private static final int DS_TEXT         = 0xFFF1F5F9; // slate-100 主文字
    private static final int DS_TEXT_MUTED   = 0xFF94A3B8; // slate-400 次要
    private static final int DS_TEXT_DIM     = 0xFF64748B; // slate-500 三级
    private static final int DS_ACCENT       = 0xFF22C55E; // green-500 主强调
    private static final int DS_ACCENT_LIGHT = 0xFF166534; // green-800
    private static final int DS_ACCENT_DARK  = 0xFF15803D; // green-700
    private static final int DS_BLUE         = 0xFF3B82F6; // blue-500
    private static final int DS_BLUE_LIGHT   = 0xFF1E40AF; // blue-800
    private static final int DS_BLUE_DARK    = 0xFF1D4ED8; // blue-700
    private static final int DS_YELLOW       = 0xFFF59E0B; // amber-500
    private static final int DS_ERROR        = 0xFFEF4444; // red-500
    private static final int DS_ERROR_LIGHT  = 0xFFB91C1C; // red-700
    private static final int DS_SUCCESS      = 0xFF22C55E; // green-500
    private static final int DS_VIOLET       = 0xFF8B5CF6; // violet-500

    // 分页 & 截断
    private static final int PAGE_SIZE = 10;
    private static final int URL_PREVIEW_LENGTH = 60;

    private Handler mHandler;

    // ViewHolder 缓存（避免每次刷新重建整个 UI）
    private View mCachedRootView;
    private boolean mUIPartiallyBuilt = false;

    // 当前分页
    private int mCurrentPage = 0;

    // 数据缓存
    private StatsData mData;

    // ============ 启动优化：骨架屏 + 后台加载 ============
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());

        // 优化1：先显示骨架屏（快速响应），后台加载数据
        setContentView(buildSkeletonUI());
        mUIPartiallyBuilt = true;

        // 优化2：后台线程加载数据，完成后更新 UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StatsData data = loadStatsData();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mData = data;
                        renderFullUI();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 优化3：避免 onResume 重复重建 UI，只在数据有变化时更新
        if (mCachedRootView != null && mData != null) {
            // 轻量刷新：只更新状态文本，不重建 UI
            refreshStatsAsync();
        }
    }

    // ============ 骨架屏（快速响应） ============
    private View buildSkeletonUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(DS_BG);
        sv.setPadding(0, dp(4), 0, dp(40));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(16));

        // 骨架 Header
        addSkeletonBlock(root, dp(120), dp(26));
        // 骨架 操作区
        addSkeletonBlock(root, dp(80), dp(14));
        addSkeletonBlock(root, dp(80), dp(14));
        addSkeletonBlock(root, dp(80), dp(14));
        // 骨架 统计区
        addSkeletonBlock(root, dp(60), dp(14));
        addSkeletonBlock(root, dp(60), dp(14));
        addSkeletonBlock(root, dp(60), dp(14));

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

    // ============ 异步轻量刷新 ============
    private void refreshStatsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StatsData data = loadStatsData();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mData = data;
                        updateUICached(data);
                    }
                });
            }
        }).start();
    }

    // ============ 完整 UI（数据加载完成后） ============
    private void renderFullUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(DS_BG);
        sv.setPadding(0, dp(4), 0, dp(40));
        sv.setId(android.R.id.list);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(16));

        // Hero 顶部（暗色紧凑）
        addHeaderV3(root);

        // 快捷操作（功能上移到顶部）
        addActionCardV3(root, mData);

        // 状态双列面板
        addStatusCardV3(root, mData);

        // 统计数据（紧凑三列）
        addStatsCardV3(root, mData);

        // HTTP 客户端（可折叠）
        addHttpClientsCardV3(root, mData);

        // 最近请求（可折叠）
        addRecentRequestsCardV3(root, mData);

        // 工作原理（默认折叠）
        addInfoCardV3(root);

        sv.addView(root);
        mCachedRootView = sv;
        setContentView(sv);
    }

    // ============ 轻量 UI 更新（只更新数据，不重建 View） ============
    private void updateUICached(StatsData data) {
        if (mCachedRootView == null) {
            renderFullUI();
            return;
        }
        // 如果数据结构变化太大，直接重建
        if (mData == null || mData.requests == null || mData.requests.size() > 20) {
            renderFullUI();
            return;
        }
        // 否则直接用现有 view 刷新（可选优化，暂时用重建代替）
        renderFullUI();
    }

    // ============ Hero 顶部栏 V3（暗色紧凑） ============
    private void addHeaderV3(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E293B, 0xFF0F172A});
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 第一行：图标 + 标题
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        // 图标
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable();
        ibGd.setColor(DS_ACCENT);
        ibGd.setCornerRadius(dp(12));
        iconBox.setBackground(ibGd);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconBox.setLayoutParams(ibLp);

        TextView iconChar = new TextView(this);
        iconChar.setText("答");
        iconChar.setTextSize(18);
        iconChar.setTextColor(0xFFFFFFFF);
        iconChar.setGravity(Gravity.CENTER);
        iconChar.setTypeface(null, android.graphics.Typeface.BOLD);
        iconBox.addView(iconChar);
        titleRow.addView(iconBox);

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
        subTitle.setText("LSPosed Hook · 智能答题");
        subTitle.setTextSize(11);
        subTitle.setTextColor(DS_TEXT_MUTED);
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
        chTv.setTextColor(DS_ACCENT);
        chTv.setTypeface(null, android.graphics.Typeface.BOLD);
        chip.addView(chTv);
        titleRow.addView(chip);

        card.addView(titleRow);

        // 第二行：目标应用信息（紧凑单行）
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        String versionName = null;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (pi != null && pi.versionName != null) versionName = pi.versionName;
        } catch (Throwable ignored) {}

        LinearLayout pkgRow = new LinearLayout(this);
        pkgRow.setOrientation(LinearLayout.HORIZONTAL);
        pkgRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pkgLp.topMargin = dp(10);
        pkgRow.setLayoutParams(pkgLp);

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("目标: " + TARGET_PACKAGE + " " + (versionName != null ? "v" + versionName : ""));
        pkgLabel.setTextSize(11);
        pkgLabel.setTextColor(DS_TEXT_MUTED);
        pkgRow.addView(pkgLabel);

        View spacer = new View(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
        spacer.setLayoutParams(spLp);
        pkgRow.addView(spacer);

        // 状态点
        View statusDot = new View(this);
        GradientDrawable sdGd = new GradientDrawable();
        sdGd.setShape(GradientDrawable.OVAL);
        sdGd.setColor(installed ? DS_SUCCESS : DS_ERROR);
        statusDot.setBackground(sdGd);
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        sdLp.rightMargin = dp(6);
        statusDot.setLayoutParams(sdLp);
        pkgRow.addView(statusDot);

        TextView statusText = new TextView(this);
        statusText.setText(installed ? "已安装" : "未安装");
        statusText.setTextSize(11);
        statusText.setTextColor(installed ? DS_SUCCESS : DS_ERROR);
        pkgRow.addView(statusText);

        card.addView(pkgRow);
        root.addView(card, cardParamsSmall());
    }

    // ============ 快捷操作卡 V3（功能上移，紧凑） ============
    private void addActionCardV3(LinearLayout root, StatsData data) {
        final boolean autoOn = data != null && data.autoSelectEnabled;
        final boolean autoNextOn = data != null && data.autoNextEnabled;

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

        // 操作行：开关 + 按钮（横向两列布局）
        LinearLayout opRow = new LinearLayout(this);
        opRow.setOrientation(LinearLayout.HORIZONTAL);
        opRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams opLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        opLp.topMargin = dp(10);
        opRow.setLayoutParams(opLp);

        // 左：自动答题开关
        View switchRow = buildCompactSwitch(
                "自动答题", autoOn, DS_ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleAutoSelect(!autoOn);
                    }
                });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        switchRow.setLayoutParams(slp);
        opRow.addView(switchRow);

        // 中间间距
        View spacer = new View(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dp(10), 1);
        spacer.setLayoutParams(spLp);
        opRow.addView(spacer);

        // 右：自动下一题开关
        View switchRow2 = buildCompactSwitch(
                "自动下一题", autoNextOn, DS_YELLOW,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleAutoNext(!autoNextOn);
                    }
                });
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        switchRow2.setLayoutParams(slp2);
        opRow.addView(switchRow2);

        card.addView(opRow);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(10);
        btnRow.setLayoutParams(brLp);

        View btn1 = buildCompactButton("启动目标应用", DS_BLUE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { launchTargetApp(); }
                });
        LinearLayout.LayoutParams b1lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btn1.setLayoutParams(b1lp);
        btnRow.addView(btn1);

        View spacer2 = new View(this);
        LinearLayout.LayoutParams sp2Lp = new LinearLayout.LayoutParams(dp(8), 1);
        spacer2.setLayoutParams(sp2Lp);
        btnRow.addView(spacer2);

        View btn2 = buildCompactButton("刷新数据", DS_SUCCESS,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        refreshStatsAsync();
                        Toast.makeText(MainActivity.this, "数据已刷新", Toast.LENGTH_SHORT).show();
                    }
                });
        LinearLayout.LayoutParams b2lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btn2.setLayoutParams(b2lp);
        btnRow.addView(btn2);

        View spacer3 = new View(this);
        LinearLayout.LayoutParams sp3Lp = new LinearLayout.LayoutParams(dp(8), 1);
        spacer3.setLayoutParams(sp3Lp);
        btnRow.addView(spacer3);

        View btn3 = buildCompactButton("清空记录", DS_ERROR,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { showClearConfirm(); }
                });
        LinearLayout.LayoutParams b3lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btn3.setLayoutParams(b3lp);
        btnRow.addView(btn3);

        card.addView(btnRow);
        root.addView(card, cardParamsSmall());
    }

    private View buildCompactSwitch(String label, boolean on, int color,
                                    View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DS_CARD_SOFT);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(12);
        labelTv.setTextColor(DS_TEXT);
        labelTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelTv.setLayoutParams(llp);

        View indicator = new View(this);
        GradientDrawable indGd = new GradientDrawable();
        indGd.setShape(GradientDrawable.OVAL);
        indGd.setColor(on ? color : DS_TEXT_DIM);
        indicator.setBackground(indGd);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(10), dp(10));
        indicator.setLayoutParams(ilp);

        row.addView(labelTv);
        row.addView(indicator);
        row.setOnClickListener(click);
        row.setClickable(true);
        return row;
    }

    private View buildCompactButton(String label, int color, View.OnClickListener click) {
        LinearLayout btn = new LinearLayout(this);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(10));
        btn.setBackground(gd);
        btn.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.addView(tv);

        btn.setOnClickListener(click);
        btn.setClickable(true);
        return btn;
    }

    private void toggleAutoSelect(boolean on) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("auto_select_enabled", on);
            getContentResolver().update(
                    Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"), cv, null, null);
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
            getContentResolver().update(
                    Uri.parse("content://" + MODULE_PACKAGE + ".stats/update"), cv, null, null);
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
                            getContentResolver().delete(
                                    Uri.parse("content://" + MODULE_PACKAGE + ".stats/clear"), null, null);
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

    // ============ 状态面板 V3（紧凑双列） ============
    private void addStatusCardV3(LinearLayout root, StatsData data) {
        boolean active = data != null && data.moduleActive;
        boolean autoOn = data != null && data.autoSelectEnabled;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        // 左：模块状态
        LinearLayout col1 = new LinearLayout(this);
        col1.setOrientation(LinearLayout.VERTICAL);
        col1.setGravity(Gravity.CENTER);
        GradientDrawable c1Gd = new GradientDrawable(
                GradientDrawable.Orientation.BR_TL,
                active ? new int[]{DS_SUCCESS, 0xFF15803D} : new int[]{DS_ERROR, 0xFFB91C1C});
        c1Gd.setCornerRadius(dp(12));
        col1.setBackground(c1Gd);
        LinearLayout.LayoutParams c1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col1.setLayoutParams(c1Lp);
        col1.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView s1Icon = new TextView(this);
        s1Icon.setText(active ? "✓" : "!");
        s1Icon.setTextSize(18);
        s1Icon.setTextColor(0xFFFFFFFF);
        s1Icon.setGravity(Gravity.CENTER);
        col1.addView(s1Icon);

        TextView s1Title = new TextView(this);
        s1Title.setText(active ? "模块已激活" : "模块未激活");
        s1Title.setTextSize(12);
        s1Title.setTextColor(0xFFFFFFFF);
        s1Title.setGravity(Gravity.CENTER);
        s1Title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams s1tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s1tLp.topMargin = dp(4);
        s1Title.setLayoutParams(s1tLp);
        col1.addView(s1Title);

        TextView s1Desc = new TextView(this);
        s1Desc.setText(active ? "Hook 已生效" : "请在 LSPosed 中启用");
        s1Desc.setTextSize(10);
        s1Desc.setTextColor(0xCCFFFFFF);
        s1Desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams s1dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s1dLp.topMargin = dp(2);
        s1Desc.setLayoutParams(s1dLp);
        col1.addView(s1Desc);
        card.addView(col1);

        // 间距
        View spacer = new View(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dp(8), 1);
        spacer.setLayoutParams(spLp);
        card.addView(spacer);

        // 右：自动答题
        LinearLayout col2 = new LinearLayout(this);
        col2.setOrientation(LinearLayout.VERTICAL);
        col2.setGravity(Gravity.CENTER);
        GradientDrawable c2Gd = new GradientDrawable(
                GradientDrawable.Orientation.BR_TL,
                autoOn ? new int[]{DS_ACCENT, DS_ACCENT_DARK} : new int[]{DS_CARD_SOFT, DS_CARD_SOFT});
        c2Gd.setCornerRadius(dp(12));
        col2.setBackground(c2Gd);
        LinearLayout.LayoutParams c2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col2.setLayoutParams(c2Lp);
        col2.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView s2Icon = new TextView(this);
        s2Icon.setText(autoOn ? "⚡" : "○");
        s2Icon.setTextSize(18);
        s2Icon.setTextColor(0xFFFFFFFF);
        s2Icon.setGravity(Gravity.CENTER);
        col2.addView(s2Icon);

        TextView s2Title = new TextView(this);
        s2Title.setText(autoOn ? "自动答题开" : "自动答题关");
        s2Title.setTextSize(12);
        s2Title.setTextColor(0xFFFFFFFF);
        s2Title.setGravity(Gravity.CENTER);
        s2Title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams s2tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s2tLp.topMargin = dp(4);
        s2Title.setLayoutParams(s2tLp);
        col2.addView(s2Title);

        TextView s2Desc = new TextView(this);
        s2Desc.setText(autoOn ? "正在自动答题" : "需手动操作");
        s2Desc.setTextSize(10);
        s2Desc.setTextColor(0xCCFFFFFF);
        s2Desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams s2dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s2dLp.topMargin = dp(2);
        s2Desc.setLayoutParams(s2dLp);
        col2.addView(s2Desc);
        card.addView(col2);

        root.addView(card, cardParamsSmall());
    }

    // ============ 统计数据卡 V3（紧凑单行三列） ============
    private void addStatsCardV3(LinearLayout root, StatsData data) {
        int hh = data != null ? data.targetHitCount : -1;
        int rc = data != null ? data.requestCount : -1;
        long lt = data != null ? data.lastHookTime : -1;

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
        addStatCol(card, "请求总数", rc < 0 ? "—" : String.valueOf(rc), DS_BLUE);
        addStatDivider(card);
        addStatCol(card, "最近活跃", lt <= 0 ? "—" : formatTimeShort(lt), DS_YELLOW);

        root.addView(card, cardParamsSmall());
    }

    private void addStatCol(LinearLayout parent, String label, String value, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);

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
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, dp(32));
        div.setLayoutParams(lp);
        parent.addView(div);
    }

    // ============ HTTP 客户端 V3（可折叠） ============
    private void addHttpClientsCardV3(LinearLayout root, StatsData data) {
        String raw = data != null ? data.detectedClients : "";
        if (raw == null) raw = "";
        List<String> clients = new ArrayList<>();
        if (!raw.trim().isEmpty()) {
            for (String s : raw.split("\n")) {
                if (s != null && s.trim().length() > 0) clients.add(s.trim());
            }
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        // 标题行
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("HTTP 客户端");
        title.setTextSize(12);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title);

        View spacer = new View(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
        spacer.setLayoutParams(spLp);
        titleRow.addView(spacer);

        if (!clients.isEmpty()) {
            TextView count = new TextView(this);
            count.setText(clients.size() + " 个");
            count.setTextSize(11);
            count.setTextColor(DS_ACCENT);
            titleRow.addView(count);
        }

        card.addView(titleRow);

        if (!clients.isEmpty()) {
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            crLp.topMargin = dp(8);
            chipRow.setLayoutParams(crLp);

            for (int i = 0; i < Math.min(clients.size(), 3); i++) {
                LinearLayout chip = new LinearLayout(this);
                chip.setGravity(Gravity.CENTER);
                GradientDrawable chGd = new GradientDrawable();
                chGd.setColor(DS_CARD_SOFT);
                chGd.setCornerRadius(dp(100));
                chip.setBackground(chGd);
                chip.setPadding(dp(8), dp(4), dp(8), dp(4));
                TextView chTv = new TextView(this);
                String shortName = clients.get(i);
                if (shortName.length() > 15) shortName = shortName.substring(0, 15) + "…";
                chTv.setText(shortName);
                chTv.setTextSize(10);
                chTv.setTextColor(DS_BLUE);
                chip.addView(chTv);
                LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                chLp.rightMargin = dp(6);
                chip.setLayoutParams(chLp);
                chipRow.addView(chip);
            }
            if (clients.size() > 3) {
                TextView more = new TextView(this);
                more.setText("+" + (clients.size() - 3));
                more.setTextSize(10);
                more.setTextColor(DS_TEXT_MUTED);
                chipRow.addView(more);
            }
            card.addView(chipRow);
        } else {
            TextView empty = new TextView(this);
            empty.setText("尚未检测到 HTTP 客户端");
            empty.setTextSize(11);
            empty.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = dp(8);
            empty.setLayoutParams(ep);
            card.addView(empty);
        }

        root.addView(card, cardParamsSmall());
    }

    // ============ 最近请求 V3（折叠） ============
    private void addRecentRequestsCardV3(LinearLayout root, StatsData data) {
        final List<RequestItem> requests = data != null ? data.requests : null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        // 标题行
        final TextView title = new TextView(this);
        title.setText("最近请求  " + (requests != null && !requests.isEmpty() ? requests.size() + " 条" : ""));
        title.setTextSize(12);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        if (requests != null && !requests.isEmpty()) {
            // 只显示前 2 条
            for (int i = 0; i < Math.min(2, requests.size()); i++) {
                RequestItem item = requests.get(i);
                View row = buildCompactRequestRow(i + 1, item.url, item.type, DS_BLUE);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(6);
                row.setLayoutParams(rlp);
                card.addView(row);
            }

            // 展开按钮
            if (requests.size() > 2) {
                final TextView expandBtn = new TextView(this);
                expandBtn.setText("展开全部 " + requests.size() + " 条 ›");
                expandBtn.setTextSize(11);
                expandBtn.setTextColor(DS_BLUE);
                LinearLayout.LayoutParams eblp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                eblp.topMargin = dp(6);
                expandBtn.setLayoutParams(eblp);
                card.addView(expandBtn);
            }
        } else {
            TextView empty = new TextView(this);
            empty.setText("尚未捕获请求");
            empty.setTextSize(11);
            empty.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.topMargin = dp(8);
            empty.setLayoutParams(ep);
            card.addView(empty);
        }

        root.addView(card, cardParamsSmall());
    }

    private View buildCompactRequestRow(int idx, String url, String type, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DS_CARD_SOFT);
        bg.setCornerRadius(dp(8));
        row.setBackground(bg);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        // 索引
        TextView num = new TextView(this);
        num.setText(String.valueOf(idx));
        num.setTextSize(10);
        num.setTextColor(0xFFFFFFFF);
        num.setGravity(Gravity.CENTER);
        num.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable numBg = new GradientDrawable();
        numBg.setShape(GradientDrawable.OVAL);
        numBg.setColor(color);
        num.setBackground(numBg);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(20), dp(20));
        num.setLayoutParams(nlp);

        // URL
        String shortUrl = url;
        if (shortUrl != null && shortUrl.length() > 40) {
            shortUrl = shortUrl.substring(0, 37) + "...";
        }
        TextView urlTv = new TextView(this);
        urlTv.setText(shortUrl != null ? shortUrl : "");
        urlTv.setTextSize(11);
        urlTv.setTextColor(DS_TEXT);
        urlTv.setSingleLine();
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ulp.leftMargin = dp(6);
        urlTv.setLayoutParams(ulp);

        row.addView(num);
        row.addView(urlTv);
        return row;
    }

    // ============ 工作原理 V3（折叠） ============
    private void addInfoCardV3(LinearLayout root) {
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
                "识别答案并自动点击",
                "ContentProvider 跨进程共享数据"
        };

        for (int i = 0; i < steps.length; i++) {
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.HORIZONTAL);
            stepRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(18), dp(18));
            num.setLayoutParams(nlp);

            TextView stepTv = new TextView(this);
            stepTv.setText(steps[i]);
            stepTv.setTextSize(11);
            stepTv.setTextColor(DS_TEXT_MUTED);
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            stlp.leftMargin = dp(8);
            stepTv.setLayoutParams(stlp);

            stepRow.addView(num);
            stepRow.addView(stepTv);
            card.addView(stepRow);
        }

        root.addView(card, cardParamsSmall());
    }

    // ============ 工具方法 ============
    private LinearLayout.LayoutParams cardParamsSmall() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        int requestCount = -1;
        int targetHitCount = -1;
        long lastHookTime = -1;
        String detectedClients = "";
        List<RequestItem> requests = new ArrayList<>();
        boolean autoSelectEnabled = false;
        boolean autoSelectLoaded = false;
        boolean autoNextEnabled = false;
        boolean autoNextLoaded = false;
    }

    private static class RequestItem {
        String type;
        String url;
        long time;
    }

    private StatsData loadStatsData() {
        StatsData data = new StatsData();

        // 1. ContentProvider 查询（核心数据）
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(URI_QUERY, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String key = cursor.getString(cursor.getColumnIndex("key"));
                    long value = cursor.getLong(cursor.getColumnIndex("value"));
                    String valueStr = null;
                    try { valueStr = cursor.getString(cursor.getColumnIndex("value_str")); } catch (Throwable ignored) {}
                    if ("request_count".equals(key)) data.requestCount = (int) value;
                    else if ("target_hit_count".equals(key)) data.targetHitCount = (int) value;
                    else if ("last_hook_time".equals(key)) data.lastHookTime = value;
                    else if ("detected_clients".equals(key)) data.detectedClients = valueStr != null ? valueStr : "";
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

        // 2. 请求记录
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
        } catch (Throwable ignored) {}

        // 排序
        Collections.sort(data.requests, new Comparator<RequestItem>() {
            @Override
            public int compare(RequestItem a, RequestItem b) {
                return Long.compare(b.time, a.time);
            }
        });

        // 3. 模块 SP（开关状态）
        try {
            SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
            boolean spAuto = selfSp.getBoolean("auto_select_enabled", data.autoSelectEnabled);
            if (!data.autoSelectLoaded || data.autoSelectEnabled != spAuto) data.autoSelectEnabled = spAuto;
            boolean spNext = selfSp.getBoolean("auto_next_enabled", data.autoNextEnabled);
            if (!data.autoNextLoaded || data.autoNextEnabled != spNext) data.autoNextEnabled = spNext;
            if (data.requestCount < 0) data.requestCount = selfSp.getInt("request_count", -1);
            if (data.targetHitCount < 0) data.targetHitCount = selfSp.getInt("target_hit_count", -1);
            if (data.lastHookTime < 0) data.lastHookTime = selfSp.getLong("last_hook_time", -1);
        } catch (Throwable ignored) {}

        // 4. isModuleActive（已在后台线程调用）
        try { data.moduleActive = isModuleActive(); } catch (Throwable ignored) {}

        return data;
    }
}
