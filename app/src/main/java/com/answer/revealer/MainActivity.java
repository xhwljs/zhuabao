package com.answer.revealer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String SP_NAME = "answer_revealer_status";
    private static final String SP_KEY_ACTIVE = "module_active_v1";
    private static final String SP_KEY_LAST = "last_intercept_time";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp2px(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("答案显示模块");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        root.addView(title, lpMatch());

        // 模块状态检测：优先由 Xposed Hook 替换返回值；失败后回退到 SharedPreferences
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
            status.setText("✓ 模块已激活并生效\n");
            try {
                SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
                long last = sp.getLong(SP_KEY_LAST, 0);
                if (last > 0) {
                    status.setText(status.getText() + "最近拦截时间：" +
                            DateFormat.format("yyyy-MM-dd HH:mm:ss", last));
                } else {
                    status.setText(status.getText() + "尚未检测到目标应用的请求");
                }
            } catch (Throwable ignored) {
            }
        } else {
            status.setText("✗ 模块尚未在 LSPosed 中启用\n\n请在 LSPosed 管理器中启用本模块，并确认作用域中已勾选：\n  • tz.ycsy.az\n  • com.answer.revealer\n\n启用后重启目标应用，再次打开本界面即可看到「已激活」。");
        }
        status.setTextSize(13);
        status.setTextColor(active ? 0xFF2E7D32 : 0xFFc62828);
        root.addView(status, lpMatch());

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("目标包名");
        pkgLabel.setTextSize(16);
        pkgLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp2 = lpMatch();
        lp2.topMargin = pad;
        root.addView(pkgLabel, lp2);

        TextView pkg = new TextView(this);
        pkg.setText("tz.ycsy.az");
        pkg.setTextSize(13);
        pkg.setTextColor(Color.BLACK);
        root.addView(pkg, lpMatch());

        TextView pathLabel = new TextView(this);
        pathLabel.setText("Hook 接口路径");
        pathLabel.setTextSize(16);
        pathLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp3 = lpMatch();
        lp3.topMargin = pad;
        root.addView(pathLabel, lp3);

        TextView path = new TextView(this);
        path.setText("/edu-core-server/app/exam/getQuestion\n\n（同时兼容 OkHttp 3.x / 4.x / 5.x 的异步与同步调用）");
        path.setTextSize(13);
        path.setTextColor(Color.BLACK);
        root.addView(path, lpMatch());

        TextView infoLabel = new TextView(this);
        infoLabel.setText("功能说明");
        infoLabel.setTextSize(16);
        infoLabel.setTextColor(0xFF1976D2);
        LinearLayout.LayoutParams lp4 = lpMatch();
        lp4.topMargin = pad;
        root.addView(infoLabel, lp4);

        TextView info = new TextView(this);
        info.setText("拦截上述接口响应，解析 JSON 后将 isRight=1 的选项替换为【 xxx 正确答案 】，然后将修改后的响应返回给目标应用，并弹窗显示原始内容。\n\n提示：\n  1. 在 LSPosed 管理器中启用本模块后，需要杀掉目标应用进程并重新打开。\n  2. 如遇弹窗但答案文本未被高亮，说明目标 App 的 TextView 未解析 HTML 样式，将以纯文本方式显示「 xxx 正确答案 」。");
        info.setTextSize(13);
        info.setTextColor(Color.BLACK);
        root.addView(info, lpMatch());

        setContentView(root);
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
