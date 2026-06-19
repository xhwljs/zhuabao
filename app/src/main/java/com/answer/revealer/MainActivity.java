package com.answer.revealer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

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

        LinearLayout.LayoutParams section = lpMatch();
        section.topMargin = pad;

        TextView statusLabel = new TextView(this);
        statusLabel.setText("模块状态");
        statusLabel.setTextSize(16);
        statusLabel.setTextColor(0xFF1976D2);
        root.addView(statusLabel, section);

        TextView status = new TextView(this);
        boolean active = MainActivity.isModuleActive();
        status.setText(active ? "模块已激活并生效" : "模块尚未在 LSPosed 中启用\n请在 LSPosed 管理器中启用本模块并勾选作用域为 tz.ycsy.az");
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
        path.setText("/edu-core-server/app/exam/getQuestion");
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
        info.setText("拦截上述接口的响应，自动标红正确选项并弹窗显示完整请求和响应内容。\n\n提示：在 LSPosed 管理器中启用本模块后，需要杀掉目标应用进程并重新打开。");
        info.setTextSize(13);
        info.setTextColor(Color.BLACK);
        root.addView(info, lpMatch());

        setContentView(root);
    }

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
