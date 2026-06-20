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
                    else if ("module_active_v1".equals(key))
                        data.moduleActive = value > 0 || "true".equalsIgnoreCase(valueStr);
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
                if (!data.moduleActive)
                    data.moduleActive = sp.getBoolean("module_active_v1", false);
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

        // 5. 通过 Xposed Hook 判断是否激活
        try {
            if (isModuleActive()) data.moduleActive = true;
        } catch (Throwable ignored) {}

        return data;
    }

    // ============ 主渲染 ============
    private void renderUI() {
        int pad = dp(16);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.setPadding(0, 0, 0, dp(24));
        scrollView.setId(android.R.id.list);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(20), pad, pad);

        // 标题
        addHeader(root);

        // 模块状态
        addStatusCard(root, mData);

        // 操作按钮
        addActionCard(root);

        // 统计数字
        addStatsCard(root, mData);

        // 功能设置
        addFeatureSettingsCard(root, mData);

        // 目标应用信息
        addTargetInfoCard(root);

        // HTTP 客户端列表
        addHttpClientsCard(root, mData);

        // 最近请求（分页）
        addRecentRequestsCard(root, mData);

        // 说明
        addInfoCard(root);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    // ============ 标题 ============
    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = dp(20);
        root.addView(header, hp);

        TextView title = new TextView(this);
        title.setText("答案显示模块");
        title.setTextSize(24);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("LSPosed Hook 管理工具");
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp2.topMargin = dp(4);
        header.addView(subtitle, sp2);

        TextView pkg = new TextView(this);
        pkg.setText("目标: " + TARGET_PACKAGE);
        pkg.setTextSize(11);
        pkg.setTextColor(0xFF90A4AE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(8);
        header.addView(pkg, pp);
    }

    // ============ 模块状态卡 ============
    private void addStatusCard(LinearLayout root, StatsData data) {
        boolean active = data != null && data.moduleActive;
        int color = active ? COLOR_ACCENT : COLOR_DANGER;
        String title = active ? "✓ 模块已激活" : "✗ 模块尚未激活";
        String hint = active
                ? "Hook 已在目标应用中生效，可拦截并标记答案"
                : "请在 LSPosed 管理器中启用本模块，并勾选作用域：tz.ycsy.az 和 com.answer.revealer。启用后请重启目标应用。";

        LinearLayout card = createCard();
        card.addView(createColorStrip(color));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(15);
        titleTv.setTextColor(color);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(titleTv);

        TextView hintTv = new TextView(this);
        hintTv.setText(hint);
        hintTv.setTextSize(12);
        hintTv.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        htp.topMargin = dp(8);
        content.addView(hintTv, htp);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 快捷操作卡 ============
    private void addActionCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_PRIMARY));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("快捷操作");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.topMargin = dp(12);

        // 启动目标应用按钮
        Button launchBtn = new Button(this);
        launchBtn.setText("启动目标应用");
        launchBtn.setTextColor(0xFFFFFFFF);
        launchBtn.setTextSize(13);
        launchBtn.setBackground(makeRippleButton(COLOR_PRIMARY));
        launchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { launchTargetApp(); }
        });
        content.addView(launchBtn, wrap);

        // 刷新数据（与下面一行清空按钮对齐）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brp2.topMargin = dp(12);
        content.addView(btnRow, brp2);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("↻ 刷新数据");
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setTextSize(13);
        refreshBtn.setBackground(makeRippleButton(COLOR_INFO));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.rightMargin = dp(8);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCurrentPage = 0;
                refreshStatsAsync();
                Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(refreshBtn, rlp);

        Button clearBtn = new Button(this);
        clearBtn.setText("清空统计数据");
        clearBtn.setTextColor(0xFFFFFFFF);
        clearBtn.setTextSize(13);
        clearBtn.setBackground(makeRippleButton(COLOR_WARNING));
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    getContentResolver().delete(URI_CLEAR, null, null);
                    getSharedPreferences("module_stats", MODE_PRIVATE).edit().clear().apply();
                    Toast.makeText(MainActivity.this, "已清空，请打开目标应用触发新统计", Toast.LENGTH_SHORT).show();
                    mCurrentPage = 0;
                    refreshStatsAsync();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "清空失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnRow.addView(clearBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 统计数字卡（列表形式） ============
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

    // ============ 功能设置卡 ============
    private void addFeatureSettingsCard(LinearLayout root, StatsData data) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_PRIMARY));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("功能设置");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        // 当前开关状态
        final boolean[] currentState = {data != null && data.autoSelectEnabled};

        // 开关行
        final LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable switchBg = new GradientDrawable();
        switchBg.setColor(0xFFFAFBFC);
        switchBg.setCornerRadius(dp(10));
        switchBg.setStroke(dp(1), 0xFFE0E4EC);
        switchRow.setBackground(switchBg);
        switchRow.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = dp(10);
        switchRow.setLayoutParams(srlp);

        // 左侧：标签 + 描述
        LinearLayout leftArea = new LinearLayout(this);
        leftArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftArea.setLayoutParams(lpLeft);

        TextView labelTv = new TextView(this);
        labelTv.setText("自动选中正确答案");
        labelTv.setTextSize(14);
        labelTv.setTextColor(COLOR_TEXT_PRIMARY);
        labelTv.setTypeface(null, android.graphics.Typeface.BOLD);
        leftArea.addView(labelTv);

        TextView descTv = new TextView(this);
        descTv.setText("进入答题页后，自动识别并点击标记了正确答案的选项");
        descTv.setTextSize(11);
        descTv.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams lpDesc = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpDesc.topMargin = dp(2);
        leftArea.addView(descTv, lpDesc);

        switchRow.addView(leftArea);

        // 右侧：状态显示（可点击切换）
        final TextView stateTv = new TextView(this);
        stateTv.setTextSize(13);
        stateTv.setTypeface(null, android.graphics.Typeface.BOLD);
        stateTv.setGravity(Gravity.CENTER);
        stateTv.setPadding(dp(12), dp(6), dp(12), dp(6));
        final GradientDrawable stateBg = new GradientDrawable();
        stateBg.setCornerRadius(dp(100));
        stateTv.setBackground(stateBg);
        updateSwitchState(stateTv, stateBg, currentState[0]);
        switchRow.addView(stateTv);

        // 点击切换
        switchRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentState[0] = !currentState[0];
                updateSwitchState(stateTv, stateBg, currentState[0]);
                try {
                    ContentValues values = new ContentValues();
                    values.put(KEY_AUTO_SELECT, currentState[0]);
                    getContentResolver().update(URI_UPDATE, values, null, null);
                    // 同时写入本地 SP 兜底
                    SharedPreferences sp = getSharedPreferences("module_stats", MODE_PRIVATE);
                    sp.edit().putBoolean(KEY_AUTO_SELECT, currentState[0]).apply();
                    Toast.makeText(MainActivity.this,
                            currentState[0] ? "已开启自动选中" : "已关闭自动选中",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    // 回滚显示
                    currentState[0] = !currentState[0];
                    updateSwitchState(stateTv, stateBg, currentState[0]);
                }
            }
        });

        content.addView(switchRow);

        card.addView(content);
        root.addView(card, cardParams());
    }

    private void updateSwitchState(TextView stateTv, GradientDrawable bg, boolean on) {
        if (on) {
            stateTv.setText("已开启");
            stateTv.setTextColor(0xFFFFFFFF);
            bg.setColor(COLOR_ACCENT);
        } else {
            stateTv.setText("已关闭");
            stateTv.setTextColor(0xFFFFFFFF);
            bg.setColor(0xFF90A4AE);
        }
    }


    // ============ 目标应用信息卡 ============
    private void addTargetInfoCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_INFO));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("目标应用信息");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        StringBuilder sb = new StringBuilder();
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        if (installed) {
            sb.append("✓ ").append(TARGET_PACKAGE).append(" 已安装\n");
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
                if (pi != null && pi.versionName != null) {
                    sb.append("版本: ").append(pi.versionName).append("\n");
                }
            } catch (Throwable ignored) {}
            sb.append("包名: ").append(TARGET_PACKAGE);
        } else {
            sb.append("✗ 未检测到 ").append(TARGET_PACKAGE).append("\n请先安装目标应用");
        }

        TextView info = new TextView(this);
        info.setText(sb.toString());
        info.setTextSize(13);
        info.setTextColor(COLOR_TEXT_PRIMARY);
        LinearLayout.LayoutParams ip2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip2.topMargin = dp(10);
        content.addView(info, ip2);

        card.addView(content);
        root.addView(card, cardParams());
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

    // ============ 说明卡 ============
    private void addInfoCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_TEXT_SECONDARY));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("工作原理");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        TextView body = new TextView(this);
        body.setText("1. LSPosed 在目标应用启动时注入 Hook 代码\n2. Hook WebView.shouldInterceptRequest 拦截 API 请求\n3. 当检测到题目接口时，修改 JSON 将正确选项加上标识\n4. 统计数据通过 ContentProvider 写入模块进程\n5. 本应用查询 ContentProvider 读取并展示统计\n\n• 每页显示 " + PAGE_SIZE + " 条请求记录，点击分页按钮翻页\n• 点击任意记录可查看完整 URL，或复制到剪贴板");
        body.setTextSize(12);
        body.setTextColor(COLOR_TEXT_SECONDARY);
        body.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(10);
        content.addView(body, bp);

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
