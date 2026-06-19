package com.answer.revealer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String SP_NAME = "answer_revealer_status";
    private static final String SP_KEY_ACTIVE = "module_active_v1";
    private static final String SP_KEY_LAST = "last_intercept_time";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp2px(16);
        int padBtn = dp2px(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, pad, pad, pad);

        // ── 标题 ──────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText("答案显示模块");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        root.addView(title, lpMatch());

        // ── 模块状态检测 ──────────────────────────────────────
        boolean active = isModuleActive();
        if (!active) {
            try {
                SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
                active = sp.getBoolean(SP_KEY_ACTIVE, false);
            } catch (Throwable ignored) {
            }
        }

        LinearLayout.LayoutParams section = lpMatch();
        section.topMargin = pad;

        TextView statusLabel = new TextView(this);
        statusLabel.setText("模块状态");
        statusLabel.setTextSize(16);
        statusLabel.setTextColor(0xFF1976D2);
        root.addView(statusLabel, section);

        TextView status = new TextView(this);
        if (active) {
            status.setText("✓ 模块已激活并生效");
            try {
                SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
                long last = sp.getLong(SP_KEY_LAST, 0);
                if (last > 0) {
                    status.setText(status.getText() + "\n最近拦截时间：" +
                            DateFormat.format("yyyy-MM-dd HH:mm:ss", last));
                } else {
                    status.setText(status.getText() + "\n尚未检测到目标应用的接口请求");
                }
            } catch (Throwable ignored) {
            }
        } else {
            status.setText("✗ 模块尚未在 LSPosed 中启用\n\n请在 LSPosed 管理器中启用本模块，并确认作用域中已勾选：\n  • tz.ycsy.az\n  • com.answer.revealer\n\n启用后杀掉目标应用进程，再打开目标应用即可生效。");
        }
        status.setTextSize(13);
        status.setTextColor(active ? 0xFF2E7D32 : 0xFFc62828);
        root.addView(status, lpMatch());

        // ── 目标应用检测 ──────────────────────────────────────
        TextView targetStatusLabel = new TextView(this);
        targetStatusLabel.setText("目标应用检测");
        targetStatusLabel.setTextSize(16);
        targetStatusLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lpTs = lpMatch();
        lpTs.topMargin = pad;
        root.addView(targetStatusLabel, lpTs);

        TextView targetStatus = new TextView(this);
        boolean installed = isPackageInstalled(TARGET_PACKAGE);
        boolean running = isPackageRunning(TARGET_PACKAGE);
        if (installed) {
            StringBuilder sb = new StringBuilder();
            sb.append("✓ tz.ycsy.az 已安装");
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
                if (pi != null) {
                    if (pi.versionName != null) sb.append(" (v").append(pi.versionName).append(")");
                    if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        sb.append("\n  应用类型：系统应用");
                    } else {
                        sb.append("\n  应用类型：普通应用");
                    }
                    sb.append("\n  当前状态：").append(running ? "运行中" : "未运行");
                }
            } catch (Throwable ignored) {
            }
            targetStatus.setText(sb.toString());
            targetStatus.setTextColor(0xFF2E7D32);
        } else {
            targetStatus.setText("✗ 未检测到 tz.ycsy.az\n请先安装目标应用，或确认包名是否正确");
            targetStatus.setTextColor(0xFFc62828);
        }
        targetStatus.setTextSize(13);
        root.addView(targetStatus, lpMatch());

        // ── 快捷操作按钮区域 ──────────────────────────────────
        TextView actionLabel = new TextView(this);
        actionLabel.setText("快捷操作");
        actionLabel.setTextSize(16);
        actionLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lpAction = lpMatch();
        lpAction.topMargin = pad;
        root.addView(actionLabel, lpAction);

        // 按钮容器（横向）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnRowLp = lpMatch();
        btnRowLp.topMargin = dp2px(8);
        root.addView(btnRow, btnRowLp);

        // 「启动目标应用」按钮
        Button btnLaunch = new Button(this);
        btnLaunch.setText("启动目标应用");
        btnLaunch.setTextSize(14);
        btnLaunch.setTextColor(Color.WHITE);
        btnLaunch.setBackground(makeButtonBg(0xFF1976D2, dp2px(8)));
        btnLaunch.setPadding(padBtn, padBtn / 2, padBtn, padBtn / 2);
        btnLaunch.setEnabled(installed);
        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchTargetApp();
            }
        });
        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(0, dp2px(44), 1f);
        btnLp1.rightMargin = dp2px(8);
        btnRow.addView(btnLaunch, btnLp1);

        // 「强制停止」按钮
        Button btnStop = new Button(this);
        btnStop.setText("强制停止");
        btnStop.setTextSize(14);
        btnStop.setTextColor(Color.WHITE);
        btnStop.setBackground(makeButtonBg(0xFFc62828, dp2px(8)));
        btnStop.setPadding(padBtn, padBtn / 2, padBtn, padBtn / 2);
        btnStop.setEnabled(installed);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmForceStop();
            }
        });
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, dp2px(44), 1f);
        btnRow.addView(btnStop, btnLp2);

        // 第二行按钮：杀掉目标应用进程
        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnRowLp2 = lpMatch();
        btnRowLp2.topMargin = dp2px(8);
        root.addView(btnRow2, btnRowLp2);

        Button btnKill = new Button(this);
        btnKill.setText("杀掉目标应用进程（调试）");
        btnKill.setTextSize(12);
        btnKill.setTextColor(Color.WHITE);
        btnKill.setBackground(makeButtonBg(0xFF795548, dp2px(8)));
        btnKill.setPadding(padBtn, padBtn / 2, padBtn, padBtn / 2);
        btnKill.setEnabled(installed);
        btnKill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                killTargetProcess();
            }
        });
        LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(44));
        btnRow2.addView(btnKill, btnLp3);

        // ── Hook 接口路径 ─────────────────────────────────────
        TextView pathLabel = new TextView(this);
        pathLabel.setText("Hook 接口路径");
        pathLabel.setTextSize(16);
        pathLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp3 = lpMatch();
        lp3.topMargin = pad;
        root.addView(pathLabel, lp3);

        TextView path = new TextView(this);
        path.setText("/edu-core-server/app/exam/getQuestion\n\n（同时 Hook OkHttp 3.x / 4.x / 5.x 的同步与异步调用）");
        path.setTextSize(13);
        path.setTextColor(Color.BLACK);
        root.addView(path, lpMatch());

        // ── 功能说明 ─────────────────────────────────────────
        TextView infoLabel = new TextView(this);
        infoLabel.setText("使用说明");
        infoLabel.setTextSize(16);
        infoLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp4 = lpMatch();
        lp4.topMargin = pad;
        root.addView(infoLabel, lp4);

        TextView info = new TextView(this);
        info.setText("1. 确保在 LSPosed 管理器中已启用本模块，并勾选两个作用域：\n    • tz.ycsy.az\n    • com.answer.revealer\n\n2. 点「杀掉目标应用进程（调试）」或在系统设置中停止目标应用。\n\n3. 点「启动目标应用」打开 tz.ycsy.az，应看到 Toast 提示「答案显示模块已生效」。\n\n4. 进入答题页面触发 /edu-core-server/app/exam/getQuestion 请求，正确答案会被标注为「 xxx 正确答案」格式并弹窗显示。\n\n5. 如未生效，点击「杀掉目标应用进程（调试）」再重新打开目标应用。\n\n重要：LSPosed 作用域中「com.answer.revealer」用于检测模块自身是否激活；「tz.ycsy.az」是 Hook 目标。两者都必须勾选！");
        info.setTextSize(13);
        info.setTextColor(Color.BLACK);
        root.addView(info, lpMatch());

        setContentView(root);
    }

    /**
     * 检测包是否已安装
     */
    private boolean isPackageInstalled(String pkgName) {
        try {
            getPackageManager().getPackageInfo(pkgName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 检测包的进程是否正在运行
     */
    private boolean isPackageRunning(String pkgName) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (pkgName.equals(info.processName)) {
                    return true;
                }
                if (info.processName != null && info.processName.startsWith(pkgName + ":")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 启动目标应用 - 多路径尝试
     */
    private void launchTargetApp() {
        try {
            // 方法1：PackageManager.getLaunchIntentForPackage
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
            // 方法2：通过 PackageManager.getInstalledPackages 中查找应用的 launcher Activity
            PackageInfo pi = getPackageManager().getPackageInfo(TARGET_PACKAGE, PackageManager.GET_ACTIVITIES);
            if (pi != null && pi.activities != null && pi.activities.length > 0) {
                String activityName = pi.activities[0].name;
                Intent intent = new Intent();
                intent.setClassName(TARGET_PACKAGE, activityName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    Toast.makeText(this, "正在启动 " + activityName, Toast.LENGTH_SHORT).show();
                    return;
                } catch (Throwable t) {
                    Toast.makeText(this, "尝试启动 Activity 失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            // 方法3：通过 ApplicationInfo 的 className 启动
            ApplicationInfo ai = getPackageManager().getApplicationInfo(TARGET_PACKAGE, PackageManager.GET_META_DATA);
            if (ai != null) {
                Intent intent = new Intent();
                intent.setClassName(TARGET_PACKAGE, ai.className);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    Toast.makeText(this, "正在启动应用入口类", Toast.LENGTH_SHORT).show();
                    return;
                } catch (Throwable t) {
                    Toast.makeText(this, "尝试启动入口类失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            // 方法4：通过 META_DATA 中定义的入口或用 ACTION_VIEW
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(TARGET_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                Toast.makeText(this, "正在启动 tz.ycsy.az...", Toast.LENGTH_SHORT).show();
                return;
            } catch (Throwable t) {
                // 继续 fallback
            }
        } catch (Throwable ignored) {
        }

        try {
            // 方法5：Shell am start
            Runtime.getRuntime().exec("am start -n " + TARGET_PACKAGE + "/");
            Toast.makeText(this, "已发送启动命令（shell 方式）", Toast.LENGTH_SHORT).show();
            return;
        } catch (Throwable ignored) {
        }

        Toast.makeText(this,
                "无法启动 tz.ycsy.az\n该应用可能没有声明 Launcher Activity\n请在系统桌面或应用列表中手动打开",
                Toast.LENGTH_LONG).show();
    }

    /**
     * 确认强制停止对话框
     */
    private void confirmForceStop() {
        new AlertDialog.Builder(this)
                .setTitle("确认停止目标应用")
                .setMessage("确定要停止 tz.ycsy.az 吗？\n\n停止后重新打开目标应用，模块才能生效。\n\n提示：停止后请手动在应用列表中打开 tz.ycsy.az。")
                .setPositiveButton("确定停止", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        forceStopTargetApp();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 停止目标应用 - 多种方法组合
     */
    private void forceStopTargetApp() {
        boolean stopped = false;

        // 方法1：killBackgroundProcesses - Android 自带 API，普通应用可以用
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(TARGET_PACKAGE);
                stopped = true;
            }
        } catch (Throwable ignored) {
        }

        // 方法2：Shell am force-stop
        if (!stopped) {
            try {
                Runtime.getRuntime().exec("am force-stop " + TARGET_PACKAGE);
                stopped = true;
            } catch (Throwable ignored) {
            }
        }

        // 方法3：Shell kill pid
        if (!stopped) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                    if (list != null) {
                        for (ActivityManager.RunningAppProcessInfo info : list) {
                            if (info.processName != null && (TARGET_PACKAGE.equals(info.processName)
                                    || info.processName.startsWith(TARGET_PACKAGE + ":"))) {
                                // 杀进程
                                Process.killProcess(info.pid);
                            }
                        }
                    }
                }
                stopped = true;
            } catch (Throwable ignored) {
            }
        }

        // 方法4：Shell killall
        if (!stopped) {
            try {
                Runtime.getRuntime().exec("killall " + TARGET_PACKAGE);
                stopped = true;
            } catch (Throwable ignored) {
            }
        }

        if (stopped) {
            Toast.makeText(this, "已停止 tz.ycsy.az，请重新打开目标应用", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "无法停止目标应用，请在系统设置中手动停止 tz.ycsy.az", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 杀掉目标应用进程（通过 killBackgroundProcesses + shell），用于调试
     */
    private void killTargetProcess() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(TARGET_PACKAGE);
            }
        } catch (Throwable ignored) {
        }

        try {
            // 同时尝试杀掉所有以目标包名开头的进程
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                if (list != null) {
                    int killed = 0;
                    for (ActivityManager.RunningAppProcessInfo info : list) {
                        if (info.processName != null && (TARGET_PACKAGE.equals(info.processName)
                                || info.processName.startsWith(TARGET_PACKAGE + ":"))) {
                            Process.killProcess(info.pid);
                            killed++;
                        }
                    }
                    if (killed > 0) {
                        Toast.makeText(this, "已杀掉 " + killed + " 个目标应用进程，请重新打开 tz.ycsy.az", Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Runtime.getRuntime().exec("am force-stop " + TARGET_PACKAGE);
            Toast.makeText(this, "已发送 shell force-stop 命令（需 root 或系统权限）", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "请在系统设置→应用→tz.ycsy.az→强制停止中手动操作", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 生成圆角按钮背景
     */
    private GradientDrawable makeButtonBg(int color, int radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radiusPx);
        bg.setColor(color);
        return bg;
    }

    /**
     * 模块激活检测。
     * 优先由 Xposed Hook 在模块包中替换此方法返回值为 true；
     * 如 hook 未生效，MainActivity.onCreate 会再通过 SharedPreferences 做二次检测。
     */
    public static boolean isModuleActive() {
        return false;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp2px(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
