package com.answer.revealer;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String SP_NAME = "answer_revealer_status";

    // 颜色配置
    private static final int COLOR_PRIMARY = 0xFF2196F3;      // 蓝
    private static final int COLOR_PRIMARY_DARK = 0xFF1565C0; // 深蓝
    private static final int COLOR_ACCENT = 0xFF4CAF50;       // 绿
    private static final int COLOR_WARNING = 0xFFFF9800;      // 橙
    private static final int COLOR_DANGER = 0xFFF44336;       // 红
    private static final int COLOR_TEXT_PRIMARY = 0xFF212121;
    private static final int COLOR_TEXT_SECONDARY = 0xFF757575;
    private static final int COLOR_BG = 0xFFF5F7FA;           // 浅灰
    private static final int COLOR_CARD = 0xFFFFFFFF;         // 白

    // 尺寸
    private int dp8, dp12, dp16, dp24, dp32;

    // 数据容器
    private volatile boolean moduleActive = false;
    private volatile int hookInstalledCount = -1;
    private volatile int requestCount = -1;
    private volatile int targetHitCount = -1;
    private volatile long lastHookTime = -1;
    private volatile List<String> detectedClientsList = null;
    private volatile List<String> recentRequestsList = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp16 = dp(16);
        dp8 = dp(8);
        dp12 = dp(12);
        dp24 = dp(24);
        dp32 = dp(32);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ====== 标题 ======
        addHeader(root);

        // ====== 加载数据（异步，避免卡住 UI）======
        loadStatsAsync();

        // ====== 模块状态卡 ======
        addStatusCard(root);

        // ====== 统计数据卡 ======
        addStatsCard(root);

        // ====== 目标应用检测卡 ======
        addTargetInfoCard(root);

        // ====== 快捷操作 ======
        addActionCard(root);

        // ====== 检测到的 HTTP 客户端 ======
        addHttpClientsCard(root);

        // ====== 最近请求记录 ======
        addRecentRequestsCard(root);

        // ====== 说明 ======
        addInfoCard(root);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到界面都刷新一次数据
        loadStatsAsync();
        refreshAllCards();
    }

    // ============ 创建组件工具 ============

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = dp24;
        root.addView(header, hp);

        TextView title = new TextView(this);
        title.setText("答案显示模块");
        title.setTextSize(24);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, lpMatch());

        TextView subtitle = new TextView(this);
        subtitle.setText("LSPosed Hook 管理工具");
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp4();
        header.addView(subtitle, sp);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(makeCardTitle("模块状态", COLOR_PRIMARY_DARK));
        card.addView(makeStatusContent());
        root.addView(card, cardParams());
    }

    private void addStatsCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(makeCardTitle("Hook 统计", COLOR_ACCENT));
        card.addView(makeStatsContent());
        root.addView(card, cardParams());
    }

    private void addTargetInfoCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(makeCardTitle("目标应用信息", COLOR_PRIMARY_DARK));
        card.addView(makeTargetInfoContent());
        root.addView(card, cardParams());
    }

    private void addActionCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(makeCardTitle("快捷操作", COLOR_WARNING));
        card.addView(makeActionsContent());
        root.addView(card, cardParams());
    }

    private void addHttpClientsCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(makeCardTitle("检测到的 HTTP 客户端 / 框架", COLOR_PRIMARY));
        card.addView(makeHttpClientsContent());
        root.addView(card, cardParams());
    }

    private void addRecentRequestsCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(makeCardTitle("最近请求记录", COLOR_PRIMARY));
        card.addView(makeRecentRequestsContent());
        root.addView(card, cardParams());
    }

    private void addInfoCard(LinearLayout root) {
        LinearLayout card = createCard();
        card.addView(makeCardTitle("说明", COLOR_TEXT_SECONDARY));
        TextView info = new TextView(this);
        info.setText("• 模块需在 LSPosed 管理器中启用，并同时勾选目标应用 tz.ycsy.az\n• 进入答题页面后，模块会自动检测 HTTP 客户端并拦截请求\n• 检测结果会自动写入模块的统计数据中\n• 如果检测结果为空，请先杀掉目标应用进程后再打开");
        info.setTextSize(12);
        info.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMargins(dp16, 0, dp16, dp16);
        card.addView(info, ip);
        root.addView(card, cardParams());
    }

    // ============ 卡片构造 ============

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(COLOR_CARD);

        // 圆角卡片
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(dp12);
        gd.setStroke(dp(1), 0xFFE0E4EC);
        card.setBackground(gd);

        card.setPadding(0, dp16, 0, 0);
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp16;
        return p;
    }

    private TextView makeCardTitle(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp16, 0, dp16, dp12);
        tv.setLayoutParams(p);
        return tv;
    }

    private View makeStatusContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp16, 0, dp16, dp16);

        // 获取状态：优先 Hook 方法，否则从 SP 读取
        boolean active = readActiveFromSP();

        TextView status = new TextView(this);
        if (active) {
            status.setText("✓ 模块已激活并生效");
            status.setTextColor(COLOR_ACCENT);
        } else {
            status.setText("✗ 模块尚未在 LSPosed 中启用");
            status.setTextColor(COLOR_DANGER);
        }
        status.setTextSize(14);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(status, lpMatch());

        TextView hint = new TextView(this);
        if (active) {
            hint.setText("模块已在目标应用 (tz.ycsy.az) 中激活");
        } else {
            hint.setText("请在 LSPosed 管理器中启用本模块，并勾选作用域：\n  • tz.ycsy.az\n  • com.answer.revealer\n启用后杀掉目标应用进程，再重新打开目标应用");
        }
        hint.setTextSize(12);
        hint.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp8;
        box.addView(hint, hp);

        return box;
    }

    private View makeStatsContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp16, 0, dp16, dp16);

        // 四列横排
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowParams);

        // 列 1: Hook 安装数
        addStatCell(row, "Hook 安装", hookInstalledCount < 0 ? "--" : String.valueOf(hookInstalledCount), COLOR_PRIMARY);
        // 列 2: 请求数
        addStatCell(row, "检测请求", requestCount < 0 ? "--" : String.valueOf(requestCount), COLOR_ACCENT);
        // 列 3: 目标命中
        addStatCell(row, "答案标记", targetHitCount < 0 ? "--" : String.valueOf(targetHitCount), COLOR_WARNING);
        // 列 4: 时间
        addStatCell(row, "最近时间", formatTime(lastHookTime), COLOR_TEXT_SECONDARY);

        box.addView(row, rowParams());
        return box;
    }

    private void addStatCell(LinearLayout parent, String label, String value, int color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackgroundColor(color | 0x0A000000); // 非常浅的底色
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x0FFFFFFF & color | 0x0A000000);
        gd.setCornerRadius(dp8);
        gd.setColor(0xFFFFFFFF);
        gd.setStroke(dp(1), 0xFFE0E4EC);
        cell.setBackground(gd);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int margin = dp(4);
        cp.setMargins(margin, 0, margin, 0);
        cell.setLayoutParams(cp);
        cell.setPadding(dp8, dp12, dp8, dp12);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(18);
        val.setTextColor(color);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        val.setGravity(Gravity.CENTER);
        cell.addView(val, lpMatch());

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(11);
        lab.setTextColor(COLOR_TEXT_SECONDARY);
        lab.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp4();
        cell.addView(lab, lp);

        parent.addView(cell);
    }

    private View makeTargetInfoContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp16, 0, dp16, dp16);

        StringBuilder sb = new StringBuilder();
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        if (installed) {
            sb.append("✓ tz.ycsy.az 已安装\n");
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
                if (pi != null && pi.versionName != null) {
                    sb.append("版本: ").append(pi.versionName).append("\n");
                }
            } catch (Throwable ignored) {}
            boolean running = isPackageRunning(TARGET_PACKAGE);
            sb.append("状态: ").append(running ? "运行中" : "未运行");
        } else {
            sb.append("✗ 未检测到 tz.ycsy.az\n请先安装目标应用");
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        box.addView(tv, lpMatch());
        return box;
    }

    private View makeActionsContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp16, 0, dp16, dp16);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(row, rp);

        // 启动按钮
        Button launch = new Button(this);
        launch.setText("启动目标应用");
        launch.setTextColor(0xFFFFFFFF);
        launch.setBackground(makeButtonBg(COLOR_PRIMARY, dp8));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp8;
        launch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchTargetApp();
            }
        });
        row.addView(launch, lp1);

        // 停止按钮
        Button stop = new Button(this);
        stop.setText("杀掉目标进程");
        stop.setTextColor(0xFFFFFFFF);
        stop.setBackground(makeButtonBg(COLOR_DANGER, dp8));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                killTargetProcess();
            }
        });
        row.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 刷新按钮
        Button refresh = new Button(this);
        refresh.setText("刷新检测数据");
        refresh.setTextColor(0xFFFFFFFF);
        refresh.setBackground(makeButtonBg(COLOR_TEXT_SECONDARY, dp8));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadStatsAsync();
                refreshAllCards();
                Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp12;
        box.addView(refresh, rlp);
        return box;
    }

    private View makeHttpClientsContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp16, 0, dp16, dp16);

        if (detectedClientsList != null && !detectedClientsList.isEmpty()) {
            // 显示数量
            TextView summary = new TextView(this);
            summary.setText("共检测到 " + detectedClientsList.size() + " 个 HTTP 相关类：");
            summary.setTextSize(13);
            summary.setTextColor(COLOR_ACCENT);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.bottomMargin = dp8;
            box.addView(summary, sp);

            // 每一行
            for (String s : detectedClientsList) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setPadding(dp8, dp8, dp8, dp8);
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(0xFFF0F7FF);
                gd.setCornerRadius(dp8);
                item.setBackground(gd);

                TextView dot = new TextView(this);
                dot.setText("●");
                dot.setTextColor(COLOR_PRIMARY);
                dot.setTextSize(12);
                dot.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(dp24, ViewGroup.LayoutParams.WRAP_CONTENT);
                dp1.gravity = Gravity.CENTER_VERTICAL;
                item.addView(dot, dp1);

                TextView text = new TextView(this);
                text.setText(s);
                text.setTextSize(12);
                text.setTextColor(COLOR_TEXT_PRIMARY);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tp.gravity = Gravity.CENTER_VERTICAL;
                item.addView(text, tp);

                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.bottomMargin = dp8;
                box.addView(item, ip);
            }
        } else {
            TextView tv = new TextView(this);
            tv.setText("尚未检测到 HTTP 客户端\n\n请按以下步骤操作：\n  1. 在 LSPosed 中确认模块已启用\n  2. 点击「杀掉目标进程」\n  3. 点击「启动目标应用」并进入答题页\n  4. 回到本页面点击「刷新检测数据」\n\n目标应用使用 Flutter / React Native 等跨平台框架时，可能不会显示 Java 层的 HTTP 客户端类，这是正常的。");
            tv.setTextSize(12);
            tv.setTextColor(COLOR_TEXT_SECONDARY);
            box.addView(tv, lpMatch());
        }
        return box;
    }

    private View makeRecentRequestsContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp16, 0, dp16, dp16);

        if (recentRequestsList != null && !recentRequestsList.isEmpty()) {
            TextView summary = new TextView(this);
            summary.setText("共 " + recentRequestsList.size() + " 条请求记录：");
            summary.setTextSize(13);
            summary.setTextColor(COLOR_ACCENT);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.bottomMargin = dp8;
            box.addView(summary, sp);

            int maxShow = Math.min(recentRequestsList.size(), 30);
            for (int i = 0; i < maxShow; i++) {
                String req = recentRequestsList.get(i);
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp8, dp8, dp8, dp8);
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(0xFFFAFAFA);
                gd.setCornerRadius(dp8);
                gd.setStroke(dp(1), 0xFFE0E4EC);
                item.setBackground(gd);

                TextView type = new TextView(this);
                // 解析类型
                int barIdx = req.indexOf("|");
                String label = barIdx > 0 ? req.substring(0, barIdx) : "?";
                String url = barIdx > 0 && barIdx < req.length() - 1 ? req.substring(barIdx + 1) : req;
                type.setText("#" + (i + 1) + " [" + label + "]");
                type.setTextSize(11);
                type.setTextColor(COLOR_PRIMARY);
                type.setTypeface(null, android.graphics.Typeface.BOLD);
                item.addView(type, lpMatch());

                TextView urlText = new TextView(this);
                urlText.setText(url);
                urlText.setTextSize(11);
                urlText.setTextColor(COLOR_TEXT_PRIMARY);
                LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                up.topMargin = dp4();
                item.addView(urlText, up);

                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ip.bottomMargin = dp8;
                box.addView(item, ip);
            }
            if (recentRequestsList.size() > maxShow) {
                TextView more = new TextView(this);
                more.setText("... 还有 " + (recentRequestsList.size() - maxShow) + " 条");
                more.setTextSize(11);
                more.setTextColor(COLOR_TEXT_SECONDARY);
                more.setGravity(Gravity.CENTER);
                box.addView(more, lpMatch());
            }
        } else {
            TextView tv = new TextView(this);
            tv.setText("尚未捕获任何请求\n\n请先打开目标应用并进入答题页面。\n如果答题页面是 WebView，请求会走 WEBVIEW_INTERCEPT 类型。");
            tv.setTextSize(12);
            tv.setTextColor(COLOR_TEXT_SECONDARY);
            box.addView(tv, lpMatch());
        }
        return box;
    }

    // ============ 刷新整个卡片列表 ============

    private void refreshAllCards() {
        // 重建 UI
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            // 用 recreate 最简单
            // 但我们在 onResume 已经调用了 loadStatsAsync，它会更新内部变量
            // 所以这里只需要 invalidate
            View v = findViewById(android.R.id.content);
            if (v != null) {
                v.invalidate();
            }
        }
    }

    // ============ 异步加载数据 ============

    private void loadStatsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 1. 从模块自己的 SP 读取
                SharedPreferences selfSp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
                hookInstalledCount = selfSp.getInt("hook_installed_count", -1);
                requestCount = selfSp.getInt("request_count", -1);
                targetHitCount = selfSp.getInt("target_hit_count", -1);
                lastHookTime = selfSp.getLong("last_hook_time", -1);
                moduleActive = selfSp.getBoolean("module_active_v1", false);
                String clients = selfSp.getString("detected_clients", null);
                detectedClientsList = parseList(clients);

                // 2. 如果模块自己的 SP 没有值，尝试从目标应用的 SP 读（跨进程）
                if (hookInstalledCount < 0 || requestCount < 0 || targetHitCount < 0) {
                    try {
                        Context targetCtx = createPackageContext(TARGET_PACKAGE,
                                Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                        SharedPreferences targetSp = targetCtx.getSharedPreferences(SP_NAME,
                                Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE);
                        if (hookInstalledCount < 0)
                            hookInstalledCount = targetSp.getInt("hook_installed_count", -1);
                        if (requestCount < 0)
                            requestCount = targetSp.getInt("request_count", -1);
                        if (targetHitCount < 0)
                            targetHitCount = targetSp.getInt("target_hit_count", -1);
                        if (lastHookTime < 0)
                            lastHookTime = targetSp.getLong("last_hook_time", -1);
                        if (!moduleActive)
                            moduleActive = targetSp.getBoolean("module_active_v1", false);
                        if (detectedClientsList == null || detectedClientsList.isEmpty()) {
                            String c2 = targetSp.getString("detected_clients", null);
                            detectedClientsList = parseList(c2);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                // 3. 加载请求记录（从两边都读）
                List<String> reqs = new ArrayList<String>();
                try {
                    loadReqFromSP(reqs, selfSp);
                } catch (Throwable ignored) {}
                try {
                    Context targetCtx = createPackageContext(TARGET_PACKAGE,
                            Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                    SharedPreferences targetSp = targetCtx.getSharedPreferences(SP_NAME,
                            Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE);
                    loadReqFromSP(reqs, targetSp);
                } catch (Throwable ignored) {}
                Collections.sort(reqs);
                recentRequestsList = reqs;

                // 4. 同步到 UI 线程刷新
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        // 重新创建主 View 来显示最新数据
                        rebuildUI();
                    }
                });
            }
        }).start();
    }

    private void rebuildUI() {
        int pad = dp16;
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        addHeader(root);
        addStatusCard(root);
        addStatsCard(root);
        addTargetInfoCard(root);
        addActionCard(root);
        addHttpClientsCard(root);
        addRecentRequestsCard(root);
        addInfoCard(root);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private boolean readActiveFromSP() {
        // 先尝试通过 Xposed Hook 的方法
        try {
            if (isModuleActive()) return true;
        } catch (Throwable ignored) {}

        // 再从模块自己的 SP 读
        try {
            SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
            if (sp.getBoolean("module_active_v1", false)) return true;
        } catch (Throwable ignored) {}

        // 再从目标应用的 SP 读
        try {
            Context targetCtx = createPackageContext(TARGET_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            SharedPreferences sp = targetCtx.getSharedPreferences(SP_NAME,
                    Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE);
            if (sp.getBoolean("module_active_v1", false)) return true;
        } catch (Throwable ignored) {}

        return false;
    }

    private void loadReqFromSP(List<String> list, SharedPreferences sp) {
        if (sp == null) return;
        try {
            Map<String, ?> all = sp.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                if (key != null && key.startsWith("req_")) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        list.add((String) value);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static List<String> parseList(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] arr = s.split("\n");
        List<String> list = new ArrayList<String>();
        for (String item : arr) {
            if (item != null && item.trim().length() > 0) list.add(item.trim());
        }
        return list;
    }

    private String formatTime(long t) {
        if (t <= 0) return "--";
        long diff = System.currentTimeMillis() - t;
        if (diff < 0) diff = 0;
        if (diff < 60 * 1000) return diff / 1000 + " 秒前";
        if (diff < 60 * 60 * 1000) return diff / 60000 + " 分钟前";
        if (diff < 24 * 60 * 60 * 1000) return diff / 3600000 + " 小时前";
        if (diff < 7 * 24 * 60 * 60 * 1000) return diff / 86400000 + " 天前";
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
            return sdf.format(new java.util.Date(t));
        } catch (Throwable ignored) {
            return "已检测";
        }
    }

    private int dp4() { return dp(4); }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable makeButtonBg(int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    // ============ 目标应用启动 / 停止 ============

    private void launchTargetApp() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                Toast.makeText(this, "正在启动 tz.ycsy.az...", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "正在启动 tz.ycsy.az...", Toast.LENGTH_SHORT).show();
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
        } catch (Throwable t) {
            return false;
        }
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

    // ============ Xposed Hook 替换 ============
    public static boolean isModuleActive() {
        return false;
    }
}
