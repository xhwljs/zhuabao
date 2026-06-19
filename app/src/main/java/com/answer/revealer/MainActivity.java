package com.answer.revealer;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String SP_NAME = "answer_revealer_status";

    private SharedPreferences targetSP; // 目标应用的 SP（通过 createPackageContext 获取）
    private SharedPreferences moduleSP; // 模块自己的 SP

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 先尝试从目标应用的 createPackageContext 读 SP
        try {
            Context targetCtx = createPackageContext(TARGET_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            targetSP = targetCtx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
        } catch (Throwable ignored) {
            targetSP = null;
        }
        moduleSP = getSharedPreferences(SP_NAME, MODE_PRIVATE | MODE_MULTI_PROCESS);

        int pad = dp2px(16);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setPadding(pad, pad, pad, pad);

        // ====== 标题 ======
        TextView title = new TextView(this);
        title.setText("答案显示模块");
        title.setTextSize(22);
        title.setTextColor(0xFF000000);
        root.addView(title, lpMatch());

        // ====== 模块状态 ======
        boolean active = isModuleActive();
        if (!active) {
            // 从自己的 SP 或目标包的 SP 里读
            try {
                active = moduleSP.getBoolean("module_active_v1", false);
            } catch (Throwable ignored) {
            }
            if (!active && targetSP != null) {
                try {
                    active = targetSP.getBoolean("module_active_v1", false);
                } catch (Throwable ignored) {
                }
            }
        }

        addSectionTitle(root, "模块状态");
        TextView statusText = new TextView(this);
        if (active) {
            statusText.setText("✓ 模块已激活并生效\n提示：请先杀掉目标应用 tz.ycsy.az 的进程，再打开一次，模块才会在目标应用中生效。");
            statusText.setTextColor(0xFF1B5E20);
        } else {
            statusText.setText("✗ 模块尚未在 LSPosed 中启用\n\n请在 LSPosed 管理器中启用本模块，并确认作用域勾选了：\n  • tz.ycsy.az\n  • com.answer.revealer\n\n启用后杀掉目标应用进程，再打开目标应用。");
            statusText.setTextColor(0xFFc62828);
        }
        statusText.setTextSize(13);
        root.addView(statusText, lpMatch());

        // ====== hook 安装统计 ======
        int hookCount = getIntFromBothSP("hook_installed_count", 0);
        int reqCount = getIntFromBothSP("request_count", 0);
        int hitCount = getIntFromBothSP("target_hit_count", 0);
        long lastHookTime = getLongFromBothSP("last_hook_time", 0);

        addSectionTitle(root, "Hook 安装 / 请求统计");
        TextView hookStat = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Hook 安装数量: ").append(hookCount).append("\n");
        sb.append("检测到请求数: ").append(reqCount).append("\n");
        sb.append("目标接口命中: ").append(hitCount).append("\n");
        if (lastHookTime > 0) {
            sb.append("最近 Hook 时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(lastHookTime)));
        } else {
            sb.append("最近 Hook 时间: 未检测到");
        }
        hookStat.setText(sb.toString());
        hookStat.setTextSize(13);
        hookStat.setTextColor(hookCount > 0 ? 0xFF1B5E20 : 0xFFc62828);
        root.addView(hookStat, lpMatch());

        // ====== 目标应用检测 ======
        addSectionTitle(root, "目标应用检测");
        StringBuilder targetInfo = new StringBuilder();
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        if (installed) {
            targetInfo.append("✓ tz.ycsy.az 已安装\n");
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
                if (pi != null && pi.versionName != null) {
                    targetInfo.append("  版本: ").append(pi.versionName).append("\n");
                }
            } catch (Throwable ignored) {
            }
            boolean running = isPackageRunning(TARGET_PACKAGE);
            targetInfo.append("  运行状态: ").append(running ? "运行中" : "未运行");
        } else {
            targetInfo.append("✗ 未检测到 tz.ycsy.az\n请先安装目标应用。");
        }
        TextView targetInfoText = new TextView(this);
        targetInfoText.setText(targetInfo.toString());
        targetInfoText.setTextSize(13);
        targetInfoText.setTextColor(0xFF333333);
        root.addView(targetInfoText, lpMatch());

        // ====== 快捷操作按钮 ======
        addSectionTitle(root, "快捷操作");

        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        btnRow1.setGravity(android.view.Gravity.CENTER);
        root.addView(btnRow1, lpMatch());

        Button btnLaunch = new Button(this);
        btnLaunch.setText("启动目标应用");
        btnLaunch.setTextSize(13);
        btnLaunch.setTextColor(0xFFFFFFFF);
        btnLaunch.setBackground(makeButtonBg(0xFF1976D2, dp2px(8)));
        btnLaunch.setEnabled(installed);
        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchTargetApp();
            }
        });
        LinearLayout.LayoutParams lpBtn1 = new LinearLayout.LayoutParams(0, dp2px(44), 1f);
        lpBtn1.rightMargin = dp2px(8);
        btnRow1.addView(btnLaunch, lpBtn1);

        Button btnStop = new Button(this);
        btnStop.setText("杀掉目标进程");
        btnStop.setTextSize(13);
        btnStop.setTextColor(0xFFFFFFFF);
        btnStop.setBackground(makeButtonBg(0xFFc62828, dp2px(8)));
        btnStop.setEnabled(installed);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                killTargetProcess();
            }
        });
        btnRow1.addView(btnStop, new LinearLayout.LayoutParams(0, dp2px(44), 1f));

        Button btnRefresh = new Button(this);
        btnRefresh.setText("刷新检测数据");
        btnRefresh.setTextSize(13);
        btnRefresh.setTextColor(0xFFFFFFFF);
        btnRefresh.setBackground(makeButtonBg(0xFF2E7D32, dp2px(8)));
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 重新尝试获取目标包的 Context
                try {
                    Context targetCtx = createPackageContext(TARGET_PACKAGE,
                            Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                    targetSP = targetCtx.getSharedPreferences(SP_NAME,
                            Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                } catch (Throwable ignored) {
                    targetSP = null;
                }
                Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
                recreate();
            }
        });
        LinearLayout.LayoutParams lpBtnR = lpMatch();
        lpBtnR.topMargin = dp2px(8);
        root.addView(btnRefresh, lpBtnR);

        // ====== HTTP 客户端检测 ======
        addSectionTitle(root, "HTTP 客户端 / 框架检测");
        TextView httpStatus = new TextView(this);
        List<String> detectedList = loadStringListFromBothSP("detected_clients");
        if (detectedList != null && !detectedList.isEmpty()) {
            StringBuilder sb2 = new StringBuilder();
            sb2.append("在目标应用中检测到以下 HTTP 相关类 / 框架：\n\n");
            for (String s : detectedList) {
                sb2.append("  • ").append(s).append("\n");
            }
            sb2.append("\n提示：如果检测到 Flutter/React Native 等跨平台框架，说明网络层可能走 Java 层以外，需要额外 Hook。");
            httpStatus.setText(sb2.toString());
            httpStatus.setTextColor(0xFF1B5E20);
        } else {
            httpStatus.setText("尚未检测到目标应用中的 HTTP 客户端\n可能原因：\n  1. 模块尚未在 LSPosed 中启用\n  2. 目标应用尚未被重新启动（需要杀掉进程后重新打开）\n  3. 目标应用使用了非 Java 层的网络库（如 Flutter / 原生 C++）\n\n请按照以下步骤操作：\n  1. 点击「杀掉目标进程」\n  2. 点击「启动目标应用」打开目标应用\n  3. 在目标应用中进入答题页面\n  4. 回到本页面点击「刷新检测数据」");
            httpStatus.setTextColor(0xFF757575);
        }
        httpStatus.setTextSize(12);
        root.addView(httpStatus, lpMatch());

        // ====== 最近请求记录 ======
        addSectionTitle(root, "最近请求记录");
        TextView reqRecordText = new TextView(this);
        StringBuilder recordSb = new StringBuilder();
        List<String> reqList = loadRequestRecords();
        if (reqList != null && !reqList.isEmpty()) {
            recordSb.append("检测到的网络请求（按顺序）：\n\n");
            int showCount = Math.min(reqList.size(), 30);
            for (int i = 0; i < showCount; i++) {
                recordSb.append(String.valueOf(i + 1)).append(". ").append(reqList.get(i)).append("\n");
            }
            if (reqList.size() > showCount) {
                recordSb.append("\n... 还有 ").append(reqList.size() - showCount).append(" 条记录");
            }
            reqRecordText.setText(recordSb.toString());
            reqRecordText.setTextColor(0xFF333333);
        } else {
            reqRecordText.setText("尚未捕获任何请求\n可能原因：\n  • 目标应用还没有进行网络请求\n  • 目标应用使用了非 Java 层的网络库\n  • Hook 没有生效（请确认模块已启用并重启目标应用）\n\n请打开目标应用并进入答题页面。");
            reqRecordText.setTextColor(0xFF757575);
        }
        reqRecordText.setTextSize(11);
        root.addView(reqRecordText, lpMatch());

        // ====== Hook 点列表 ======
        addSectionTitle(root, "已安装的 Hook 入口（调试用）");
        TextView hookInfoText = new TextView(this);
        hookInfoText.setText(
                "模块会 Hook 以下入口来拦截网络请求：\n\n" +
                        "  A. Java/Kotlin 层：\n" +
                        "     1. okhttp3.RealCall.getResponseWithInterceptorChain()\n" +
                        "     2. okhttp3.internal.connection.RealCall.getResponseWithInterceptorChain()\n" +
                        "     3. okhttp3.Call.execute()\n" +
                        "     4. okhttp3.Callback.onResponse(Call, Response)\n" +
                        "     5. okhttp3.OkHttpClient.newCall(Request)\n" +
                        "     6. okhttp3.Interceptor.Chain.proceed(Request)\n" +
                        "     7. java.net.HttpURLConnection.getResponseCode()\n" +
                        "     8. java.net.HttpURLConnection.getInputStream()\n" +
                        "     9. java.net.URL.openConnection()\n\n" +
                        "  B. WebView 层：\n" +
                        "     10. android.webkit.WebView.loadUrl(String)\n" +
                        "     11. android.webkit.WebViewClient.shouldInterceptRequest()\n" +
                        "     12. com.tencent.smtt.sdk.WebView（腾讯 X5 内核）\n\n" +
                        "  C. TCP 底层：\n" +
                        "     13. java.net.Socket.connect() / getOutputStream()\n\n" +
                        "如果「HTTP 客户端检测」显示为空白，说明目标应用可能使用了：\n" +
                        "  • Flutter（Dio / http.dart，走 Dart VM 层）\n" +
                        "  • React Native（JS 网络桥接）\n" +
                        "  • Cordova / 小程序容器\n" +
                        "  • 自研的底层网络库\n" +
                        "\n请将「HTTP 客户端检测」的内容告诉开发者，以便添加对应的 Hook。"
        );
        hookInfoText.setTextSize(11);
        hookInfoText.setTextColor(0xFF666666);
        root.addView(hookInfoText, lpMatch());

        scrollView.addView(root);
        setContentView(scrollView);
    }

    // ========== 子组件创建方法 ==========
    private void addSectionTitle(LinearLayout root, String text) {
        int pad = dp2px(16);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp = lpMatch();
        lp.topMargin = pad;
        root.addView(tv, lp);
    }

    private GradientDrawable makeButtonBg(int color, int radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radiusPx);
        bg.setColor(color);
        return bg;
    }

    // ========== 目标应用启动 / 停止 ==========
    private void launchTargetApp() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                Toast.makeText(this, "正在启动 tz.ycsy.az...", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Throwable ignored) {
        }

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
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(TARGET_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                Toast.makeText(this, "正在启动 tz.ycsy.az...", Toast.LENGTH_SHORT).show();
                return;
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "无法启动目标应用，请手动在系统桌面打开", Toast.LENGTH_LONG).show();
    }

    private void killTargetProcess() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(TARGET_PACKAGE);
            }
        } catch (Throwable ignored) {
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Runtime.getRuntime().exec("killall " + TARGET_PACKAGE);
            }
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "已发送 kill 命令，请再次启动目标应用", Toast.LENGTH_LONG).show();
    }

    // ========== 辅助方法 ==========
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
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ========== SharedPreferences 工具：优先从模块自己的 SP 读，失败则从目标包 SP 读 ==========
    private int getIntFromBothSP(String key, int defValue) {
        try {
            if (moduleSP != null) {
                int v = moduleSP.getInt(key, defValue);
                if (v != defValue) return v;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (targetSP != null) {
                return targetSP.getInt(key, defValue);
            }
        } catch (Throwable ignored) {
        }
        return defValue;
    }

    private long getLongFromBothSP(String key, long defValue) {
        try {
            if (moduleSP != null) {
                long v = moduleSP.getLong(key, defValue);
                if (v != defValue) return v;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (targetSP != null) {
                return targetSP.getLong(key, defValue);
            }
        } catch (Throwable ignored) {
        }
        return defValue;
    }

    private List<String> loadStringListFromBothSP(String key) {
        // 先从模块自己的 SP 读
        List<String> list = loadStringList(key, moduleSP);
        if (list != null && !list.isEmpty()) return list;
        // 再从目标包的 SP 读
        return loadStringList(key, targetSP);
    }

    private List<String> loadStringList(String key, SharedPreferences sp) {
        if (sp == null) return null;
        try {
            String val = sp.getString(key, null);
            if (val == null) return null;
            String[] arr = val.split("\n");
            List<String> list = new ArrayList<String>();
            for (String s : arr) {
                if (s != null && s.trim().length() > 0) list.add(s);
            }
            return list;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private List<String> loadRequestRecords() {
        List<String> list = new ArrayList<String>();

        // 从模块自己的 SP 读
        loadReqFromSP(list, moduleSP);
        // 从目标包 SP 读
        loadReqFromSP(list, targetSP);

        // 排序（key 格式是 req_XXXXX_timestamp）
        java.util.Collections.sort(list);
        return list;
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
        } catch (Throwable ignored) {
        }
    }

    // ========== 由 XposedInit Hook 替换的方法 ==========
    public static boolean isModuleActive() {
        return false;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp2px(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
