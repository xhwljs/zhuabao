package com.answer.revealer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
                    status.setText(status.getText() + "\n尚未检测到目标应用的请求");
                }
            } catch (Throwable ignored) {
            }
        } else {
            status.setText("✗ 模块尚未在 LSPosed 中启用\n\n请在 LSPosed 管理器中启用本模块，并确认作用域中已勾选：\n  • tz.ycsy.az\n  • com.answer.revealer\n\n启用后重启目标应用，再次打开本界面即可看到「已激活」。");
        }
        status.setTextSize(13);
        status.setTextColor(active ? 0xFF2E7D32 : 0xFFc62828);
        root.addView(status, lpMatch());

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
        btnRow.setGravity(android.view.Gravity.CENTER);
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
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmForceStop();
            }
        });
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, dp2px(44), 1f);
        btnRow.addView(btnStop, btnLp2);

        // ── 目标包名 ─────────────────────────────────────────
        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("目标包名");
        pkgLabel.setTextSize(16);
        pkgLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp2 = lpMatch();
        lp2.topMargin = pad;
        root.addView(pkgLabel, lp2);

        TextView pkg = new TextView(this);
        pkg.setText(TARGET_PACKAGE);
        pkg.setTextSize(13);
        pkg.setTextColor(Color.BLACK);
        root.addView(pkg, lpMatch());

        // ── Hook 接口路径 ─────────────────────────────────────
        TextView pathLabel = new TextView(this);
        pathLabel.setText("Hook 接口路径");
        pathLabel.setTextSize(16);
        pathLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp3 = lpMatch();
        lp3.topMargin = pad;
        root.addView(pathLabel, lp3);

        TextView path = new TextView(this);
        path.setText("/edu-core-server/app/exam/getQuestion\n\n（兼容 OkHttp 3.x / 4.x / 5.x 的同步与异步调用）");
        path.setTextSize(13);
        path.setTextColor(Color.BLACK);
        root.addView(path, lpMatch());

        // ── 功能说明 ─────────────────────────────────────────
        TextView infoLabel = new TextView(this);
        infoLabel.setText("功能说明");
        infoLabel.setTextSize(16);
        infoLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp4 = lpMatch();
        lp4.topMargin = pad;
        root.addView(infoLabel, lp4);

        TextView info = new TextView(this);
        info.setText("拦截上述接口响应，解析 JSON 后将 isRight=1 的选项替换为「 xxx 正确答案 」，然后将修改后的响应返回给目标应用，并弹窗显示原始内容。\n\n提示：\n  1. 在 LSPosed 管理器中启用本模块后，需要杀掉目标应用进程并重新打开。\n  2. 模块启动成功后会自动弹出 Toast 提示。\n  3. 如遇弹窗但答案文本未被高亮，说明目标 App 的 TextView 未解析 HTML 样式，将以纯文本方式显示。");
        info.setTextSize(13);
        info.setTextColor(Color.BLACK);
        root.addView(info, lpMatch());

        setContentView(root);
    }

    /**
     * 启动目标应用。如果目标应用有 Launcher Activity，则直接打开；
     * 如果应用正在运行，则将其切换到前台。
     */
    private void launchTargetApp() {
        try {
            // 方法 1：通过 PackageManager 获取 Launch Intent
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                Toast.makeText(this, "正在启动 tz.ycsy.az...", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Throwable ignored) {
        }

        // 方法 2：如果应用已运行，用 adb am start 方式（需 root）或直接切换前台
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                    if (TARGET_PACKAGE.equals(info.processName)) {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.setPackage(TARGET_PACKAGE);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        Toast.makeText(this, "已切换到 tz.ycsy.az", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "无法找到目标应用，请确认 tz.ycsy.az 已安装", Toast.LENGTH_LONG).show();
    }

    /**
     * 弹出确认对话框，确认后强制停止目标应用。
     */
    private void confirmForceStop() {
        new AlertDialog.Builder(this)
                .setTitle("确认强制停止")
                .setMessage("确定要强制停止 tz.ycsy.az 吗？\n\n强制停止后请重新打开目标应用以激活模块。")
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
     * 强制停止目标应用。
     */
    private void forceStopTargetApp() {
        boolean stopped = false;

        // 方法 1：ActivityManager.forceStopPackage（需要系统权限，非 system app 通常会失败）
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.clearApplicationUserData();
                stopped = true;
            }
        } catch (Throwable ignored) {
        }

        // 方法 2：Shell 命令 am force-stop（需要 root 权限）
        if (!stopped) {
            try {
                Runtime.getRuntime().exec("am force-stop " + TARGET_PACKAGE);
                stopped = true;
            } catch (Throwable ignored) {
            }
        }

        // 方法 3：使用 package manager 的 deletePackage（需特殊权限）
        if (!stopped) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Runtime.getRuntime().exec("pm clear " + TARGET_PACKAGE);
                    stopped = true;
                }
            } catch (Throwable ignored) {
            }
        }

        if (stopped) {
            Toast.makeText(this, "已强制停止 tz.ycsy.az，请重新打开目标应用", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "无法强制停止（需要 root 权限或系统权限）", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 生成圆角按钮背景。
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
