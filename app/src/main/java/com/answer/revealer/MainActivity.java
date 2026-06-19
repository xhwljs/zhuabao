package com.answer.revealer;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
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

    // 颜色配置（Material Design 风格）
    private static final int COLOR_PRIMARY = 0xFF2196F3;        // 亮蓝
    private static final int COLOR_PRIMARY_DARK = 0xFF1976D2;   // 深蓝
    private static final int COLOR_ACCENT = 0xFF4CAF50;         // 绿
    private static final int COLOR_WARNING = 0xFFFF9800;        // 橙
    private static final int COLOR_DANGER = 0xFFF44336;         // 红
    private static final int COLOR_INFO = 0xFF00BCD4;           // 青
    private static final int COLOR_TEXT_PRIMARY = 0xFF212121;
    private static final int COLOR_TEXT_SECONDARY = 0xFF757575;
    private static final int COLOR_TEXT_LIGHT = 0xFFE0E0E0;
    private static final int COLOR_BG = 0xFFF5F7FA;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_DIVIDER = 0xFFE0E4EC;

    private LinearLayout mRoot;
    private Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());

        int pad = dp(16);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.setPadding(0, 0, 0, dp(24));

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(pad, dp(20), pad, pad);

        // ====== 标题 ======
        addHeader(mRoot);

        // ====== 模块状态卡 ======
        addStatusCard(mRoot);

        // ====== 快捷操作卡 ======
        addActionCard(mRoot);

        // ====== 统计卡（数字） ======
        addStatsCard(mRoot);

        // ====== 目标应用信息 ======
        addTargetInfoCard(mRoot);

        // ====== HTTP 客户端检测 ======
        addHttpClientsCard(mRoot);

        // ====== 最近请求 ======
        addRecentRequestsCard(mRoot);

        // ====== 说明 ======
        addInfoCard(mRoot);

        scrollView.addView(mRoot);
        setContentView(scrollView);

        // 异步刷新数据
        refreshStatsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到界面都刷新
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
                        updateUIWithData(data);
                    }
                });
            }
        }).start();
    }

    private static class StatsData {
        boolean moduleActive = false;
        int hookInstalledCount = -1;
        int requestCount = -1;
        int targetHitCount = -1;
        long lastHookTime = -1;
        String detectedClients = "";
        List<RequestItem> requests = new ArrayList<>();
        boolean providerAvailable = false;
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
                data.providerAvailable = true;
                do {
                    String key = cursor.getString(cursor.getColumnIndex("key"));
                    long value = cursor.getLong(cursor.getColumnIndex("value"));
                    String valueStr = cursor.getString(cursor.getColumnIndex("value_str"));
                    if ("hook_installed_count".equals(key))
                        data.hookInstalledCount = (int) value;
                    else if ("request_count".equals(key))
                        data.requestCount = (int) value;
                    else if ("target_hit_count".equals(key))
                        data.targetHitCount = (int) value;
                    else if ("last_hook_time".equals(key))
                        data.lastHookTime = value;
                    else if ("module_active_v1".equals(key))
                        data.moduleActive = value > 0 || "true".equalsIgnoreCase(valueStr);
                    else if ("detected_clients".equals(key))
                        data.detectedClients = valueStr != null ? valueStr : "";
                } while (cursor.moveToNext());
            }
        } catch (Throwable t) {
            // ContentProvider 可能还没初始化（目标应用还没启动）
            android.util.Log.w("AnswerModule", "ContentProvider 查询失败: " + t.getMessage());
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
        }

        // 2. 如果 ContentProvider 没数据，尝试从目标应用的 SP 读（跨进程）
        if (data.hookInstalledCount < 0) {
            try {
                Context targetCtx = createPackageContext(TARGET_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                SharedPreferences sp = targetCtx.getSharedPreferences("answer_revealer_status",
                        Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE);
                data.hookInstalledCount = sp.getInt("hook_installed_count", -1);
                data.requestCount = sp.getInt("request_count", -1);
                data.targetHitCount = sp.getInt("target_hit_count", -1);
                data.lastHookTime = sp.getLong("last_hook_time", -1);
                data.moduleActive = sp.getBoolean("module_active_v1", false);
                data.detectedClients = sp.getString("detected_clients", "");
            } catch (Throwable ignored) {}
        }

        // 3. 请求记录 - 从 ContentProvider
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

        // 排序：按时间
        Collections.sort(data.requests, new Comparator<RequestItem>() {
            @Override
            public int compare(RequestItem a, RequestItem b) {
                return Long.compare(b.time, a.time); // 新的在前
            }
        });

        // 4. 也从模块自己的 SP 读（兜底）
        try {
            SharedPreferences selfSp = getSharedPreferences("module_stats", MODE_PRIVATE);
            if (data.hookInstalledCount < 0)
                data.hookInstalledCount = selfSp.getInt("hook_installed_count", -1);
            if (data.requestCount < 0)
                data.requestCount = selfSp.getInt("request_count", -1);
            if (data.targetHitCount < 0)
                data.targetHitCount = selfSp.getInt("target_hit_count", -1);
            if (data.lastHookTime < 0)
                data.lastHookTime = selfSp.getLong("last_hook_time", -1);
        } catch (Throwable ignored) {}

        // 5. 如果 Hook 了自己的 isModuleActive，直接返回 true
        try {
            data.moduleActive = isModuleActive();
        } catch (Throwable ignored) {}

        return data;
    }

    private void updateUIWithData(StatsData data) {
        // 完全重建视图（简单可靠）
        int pad = dp(16);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.setPadding(0, 0, 0, dp(24));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(20), pad, pad);

        addHeader(root);
        addStatusCard(root, data);
        addActionCard(root);
        addStatsCard(root, data);
        addTargetInfoCard(root);
        addHttpClientsCard(root, data);
        addRecentRequestsCard(root, data);
        addInfoCard(root);

        scrollView.addView(root);
        setContentView(scrollView);
        mRoot = root;
    }

    // ============ 标题 ============
    private void addHeader(LinearLayout root) {
        // 渐变顶部
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = dp(20);
        root.addView(header, hp);

        // 主标题
        TextView title = new TextView(this);
        title.setText("答案显示模块");
        title.setTextSize(24);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("LSPosed Hook 管理工具");
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp2.topMargin = dp(4);
        header.addView(subtitle, sp2);

        // 包名
        TextView pkg = new TextView(this);
        pkg.setText("目标: " + TARGET_PACKAGE);
        pkg.setTextSize(11);
        pkg.setTextColor(0xFF90A4AE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(8);
        header.addView(pkg, pp);
    }

    // ============ 模块状态卡（新版） ============
    private void addStatusCard(LinearLayout root) {
        addStatusCard(root, null);
    }

    private void addStatusCard(LinearLayout root, StatsData data) {
        int color = (data != null && data.moduleActive) ? COLOR_ACCENT : COLOR_DANGER;
        String title = (data != null && data.moduleActive) ? "✓ 模块已激活" : "✗ 模块尚未激活";
        String hint;
        if (data != null && data.moduleActive) {
            hint = "Hook 已在目标应用 (tz.ycsy.az) 中生效，可拦截并标记答案";
        } else {
            hint = "请在 LSPosed 管理器中启用本模块，并勾选作用域：\n  • tz.ycsy.az\n  • com.answer.revealer\n启用后杀掉目标应用进程，再重新打开目标应用";
        }

        LinearLayout card = createCard();
        // 顶部色条
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

        // 标题
        TextView title = new TextView(this);
        title.setText("快捷操作");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brp.topMargin = dp(12);
        content.addView(btnRow, brp);

        // 启动目标应用按钮（蓝）
        Button launchBtn = new Button(this);
        launchBtn.setText("启动目标应用");
        launchBtn.setTextColor(0xFFFFFFFF);
        launchBtn.setTextSize(13);
        launchBtn.setBackground(makeRippleButton(COLOR_PRIMARY));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(8);
        launchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { launchTargetApp(); }
        });
        btnRow.addView(launchBtn, lp1);

        // 杀掉目标进程（红）
        Button killBtn = new Button(this);
        killBtn.setText("杀掉目标进程");
        killBtn.setTextColor(0xFFFFFFFF);
        killBtn.setTextSize(13);
        killBtn.setBackground(makeRippleButton(COLOR_DANGER));
        killBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { killTargetProcess(); }
        });
        btnRow.addView(killBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 刷新按钮
        Button refreshBtn = new Button(this);
        refreshBtn.setText("↻ 刷新数据");
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setTextSize(13);
        refreshBtn.setBackground(makeRippleButton(COLOR_TEXT_SECONDARY));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatsAsync();
                Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(refreshBtn, rlp);

        // 清空数据
        Button clearBtn = new Button(this);
        clearBtn.setText("清空统计数据");
        clearBtn.setTextColor(0xFFFFFFFF);
        clearBtn.setTextSize(13);
        clearBtn.setBackground(makeRippleButton(COLOR_WARNING));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    getContentResolver().delete(URI_CLEAR, null, null);
                    // 同时清空自己的 SP
                    getSharedPreferences("module_stats", MODE_PRIVATE).edit().clear().apply();
                    Toast.makeText(MainActivity.this, "已清空统计数据", Toast.LENGTH_SHORT).show();
                    refreshStatsAsync();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "清空失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        content.addView(clearBtn, clp);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 统计卡（数字格子） ============
    private void addStatsCard(LinearLayout root) { addStatsCard(root, null); }

    private void addStatsCard(LinearLayout root, StatsData data) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_ACCENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        // 标题
        TextView title = new TextView(this);
        title.setText("Hook 统计");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        // 数字网格（2x2）
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp1.topMargin = dp(12);
        content.addView(row1, rp1);

        int hc = data != null ? data.hookInstalledCount : -1;
        int hh = data != null ? data.targetHitCount : -1;
        int rc = data != null ? data.requestCount : -1;
        long lt = data != null ? data.lastHookTime : -1;

        // 左上: Hook 安装
        addStatCell(row1, "Hook 安装", hc < 0 ? "--" : String.valueOf(hc), COLOR_PRIMARY);
        // 右上: 答案命中
        addStatCell(row1, "答案命中", hh < 0 ? "--" : String.valueOf(hh), COLOR_ACCENT);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp2.topMargin = dp(8);
        content.addView(row2, rp2);

        // 左下: 请求总数
        addStatCell(row2, "请求总数", rc < 0 ? "--" : String.valueOf(rc), COLOR_WARNING);
        // 右下: 最近时间
        addStatCell(row2, "最近活跃", formatTime(lt), COLOR_TEXT_SECONDARY);

        card.addView(content);
        root.addView(card, cardParams());
    }

    private void addStatCell(LinearLayout parent, String label, String value, int color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFFE0E4EC);
        cell.setBackground(bg);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int margin = dp(4);
        cp.setMargins(margin, 0, margin, 0);
        cell.setLayoutParams(cp);
        cell.setPadding(dp(12), dp(14), dp(12), dp(14));

        // 值
        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(20);
        val.setTextColor(color);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        val.setGravity(Gravity.CENTER);
        cell.addView(val, lpMatch());

        // 标签
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(11);
        lab.setTextColor(COLOR_TEXT_SECONDARY);
        lab.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        cell.addView(lab, lp);

        parent.addView(cell);
    }

    // ============ 目标应用信息 ============
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
            boolean running = isPackageRunning(TARGET_PACKAGE);
            sb.append("状态: ").append(running ? "运行中" : "未运行");
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

    // ============ HTTP 客户端检测卡（列表） ============
    private void addHttpClientsCard(LinearLayout root) { addHttpClientsCard(root, null); }

    private void addHttpClientsCard(LinearLayout root, StatsData data) {
        LinearLayout card = createCard();
        card.addView(createColorStrip(COLOR_PRIMARY));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));

        // 标题
        TextView title = new TextView(this);
        title.setText("检测到的 HTTP 客户端 / 框架");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        // 解析客户端列表
        String raw = data != null ? data.detectedClients : "";
        if (raw == null) raw = "";
        List<String> clients = new ArrayList<>();
        if (!raw.trim().isEmpty()) {
            for (String s : raw.split("\n")) {
                if (s != null && s.trim().length() > 0) clients.add(s.trim());
            }
        }

        if (!clients.isEmpty()) {
            // 计数
            TextView count = new TextView(this);
            count.setText("共 " + clients.size() + " 个类已在目标应用中发现");
            count.setTextSize(12);
            count.setTextColor(COLOR_ACCENT);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(8);
            content.addView(count, cp);

            // 列表
            for (int i = 0; i < clients.size(); i++) {
                View row = makeListItem(i + 1, clients.get(i), null, COLOR_PRIMARY);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (i == 0) ? dp(8) : dp(6);
                content.addView(row, lp);
            }
        } else {
            TextView empty = new TextView(this);
            empty.setText("尚未检测到 HTTP 客户端\n\n请按以下步骤操作：\n  1. 在 LSPosed 管理器中确认模块已启用\n  2. 点击「杀掉目标进程」\n  3. 点击「启动目标应用」进入答题页\n  4. 回到本页面点击「刷新数据」\n\n目标应用使用 Flutter / React Native 等跨平台框架时，Java 层类可能较少，这是正常的。");
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

    // ============ 最近请求记录（列表） ============
    private void addRecentRequestsCard(LinearLayout root) { addRecentRequestsCard(root, null); }

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
            TextView count = new TextView(this);
            count.setText("共 " + requests.size() + " 条");
            count.setTextSize(12);
            count.setTextColor(COLOR_ACCENT);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(8);
            content.addView(count, cp);

            int limit = Math.min(requests.size(), 30);
            for (int i = 0; i < limit; i++) {
                RequestItem item = requests.get(i);
                // 判断类型颜色
                int color = COLOR_PRIMARY;
                if (item.type != null) {
                    if (item.type.contains("WEBVIEW")) color = COLOR_INFO;
                    else if (item.type.contains("OKHTTP")) color = COLOR_PRIMARY;
                    else if (item.type.contains("URL")) color = COLOR_ACCENT;
                    else if (item.type.contains("SOCKET")) color = COLOR_DANGER;
                }
                View row = makeListItem(i + 1, item.url, item.type, color);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (i == 0) ? dp(8) : dp(6);
                content.addView(row, lp);
            }

            if (requests.size() > limit) {
                TextView more = new TextView(this);
                more.setText("... 还有 " + (requests.size() - limit) + " 条");
                more.setTextSize(11);
                more.setTextColor(COLOR_TEXT_SECONDARY);
                more.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mp.topMargin = dp(8);
                content.addView(more, mp);
            }
        } else {
            TextView empty = new TextView(this);
            empty.setText("尚未捕获任何请求\n\n请先打开目标应用并进入答题页面。\n如果答题页面是 WebView，请求将被拦截并记录。");
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
        body.setText("1. LSPosed 在目标应用启动时注入 Hook 代码\n2. Hook WebView.shouldInterceptRequest 拦截 API 请求\n3. 当检测到题目接口（包含 getQuestion）时，修改 JSON 将正确选项加上「正确答案」标识\n4. 修改后的数据通过 ContentProvider 保存在模块进程中（跨进程共享）\n5. 本应用查询 ContentProvider 读取统计数据");
        body.setTextSize(12);
        body.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(10);
        content.addView(body, bp);

        card.addView(content);
        root.addView(card, cardParams());
    }

    // ============ 列表行 ============
    private View makeListItem(int index, String mainText, String typeLabel, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(8));
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
        circLp.gravity = Gravity.CENTER_VERTICAL;
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
        tap.leftMargin = dp(12);
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

        TextView mainTv = new TextView(this);
        mainTv.setText(mainText);
        mainTv.setTextSize(11);
        mainTv.setTextColor(COLOR_TEXT_PRIMARY);
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (typeLabel != null && !typeLabel.isEmpty()) mtp.topMargin = dp(2);
        textArea.addView(mainTv, mtp);

        row.addView(textArea);
        return row;
    }

    // ============ 卡片工具 ============
    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(COLOR_CARD);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), 0xFFE0E4EC);
        card.setBackground(gd);

        return card;
    }

    private View createColorStrip(int color) {
        View strip = new View(this);
        strip.setBackgroundColor(color);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        // 顶部圆角跟随卡片
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

    private void killTargetProcess() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(TARGET_PACKAGE);
        } catch (Throwable ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Runtime.getRuntime().exec("killall " + TARGET_PACKAGE);
            }
        } catch (Throwable ignored) {}
        Toast.makeText(this, "已发送 kill 命令，请再次启动目标应用", Toast.LENGTH_SHORT).show();
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) { return false; }
    }

    private boolean isPackageRunning(String pkg) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (pkg.equals(info.processName)) return true;
                if (info.processName != null && info.processName.startsWith(pkg + ":")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ============ Xposed Hook 的方法（hook 成功后返回 true） ============
    public static boolean isModuleActive() {
        return false;
    }
}
