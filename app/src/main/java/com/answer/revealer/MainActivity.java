package com.answer.revealer;

import android.app.Activity;
import android.app.Dialog;
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
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String MODULE_PACKAGE = "com.answer.revealer";

    // ContentProvider URIs
    private static final Uri URI_QUERY = Uri.parse("content://com.answer.revealer.stats/query");
    private static final Uri URI_CLEAR = Uri.parse("content://com.answer.revealer.stats/clear");
    private static final Uri URI_UPDATE = Uri.parse("content://com.answer.revealer.stats/update");

    // ============ 主题色系统 ============
    // 10种精选主题，每种含独特图标、风格描述、强调色
    private static final String[] THEME_NAMES = {
            "海洋青", "森林绿", "日落橙", "靛蓝紫", "玫瑰红",
            "天空蓝", "薰衣紫", "琥珀金", "薄荷绿", "珊瑚粉"
    };
    private static final String[] THEME_ICONS = {
            "答", "森", "日", "梦", "玫",
            "蓝", "薰", "金", "薄", "珊"
    };
    private static final String[] THEME_DESC = {
            "清澈自然", "生机盎然", "温暖活力", "静谧优雅", "热情浪漫",
            "宁静开阔", "梦幻柔和", "尊贵大气", "清新舒适", "甜美活泼"
    };
    // 主题色数组：[主色, 浅色, 深色文字, 背景色, 强调色(第二主色)]
    private static final int[][] THEME_COLORS = {
            {0xFF0D9488, 0xFF14B8A6, 0xFF0F172A, 0xFFF0FDFA, 0xFF06B6D4},  // 海洋青 + 青色强调
            {0xFF059669, 0xFF10B981, 0xFF022C22, 0xFFECFDF5, 0xFF84CC16},  // 森林绿 + 青柠强调
            {0xFFF97316, 0xFFFB923C, 0xFF1E1E1E, 0xFFFFF7ED, 0xFFF59E0B},  // 日落橙 + 琥珀强调
            {0xFF6366F1, 0xFF818CF8, 0xFF1E1B4B, 0xFFF5F3FF, 0xFFA855F7},  // 靛蓝紫 + 紫色强调
            {0xFFE11D48, 0xFFF43F5E, 0xFF1C1917, 0xFFFFF1F2, 0xFFEC4899},  // 玫瑰红 + 粉色强调
            {0xFF2563EB, 0xFF3B82F6, 0xFF1E3A8A, 0xFFEFF6FF, 0xFF0EA5E9},  // 天空蓝 + 天蓝强调
            {0xFF8B5CF6, 0xFFA78BFA, 0xFF4C1D95, 0xFFFAF5FF, 0xFFD946EF},  // 薰衣紫 + 品红强调
            {0xFFD97706, 0xFFF59E0B, 0xFF78350F, 0xFFFFFBEB, 0xFFFBBF24},  // 琥珀金 + 亮黄强调
            {0xFF059669, 0xFF34D399, 0xFF064E3B, 0xFFECFDF5, 0xFF10B981},  // 薄荷绿 + 翡翠强调
            {0xFFF43F5E, 0xFFFB7185, 0xFF881337, 0xFFFFF1F2, 0xFFFDA4AF}   // 珊瑚粉 + 浅粉强调
    };

    // 当前主题索引
    private int currentTheme = 0;

    // 动态主题色（根据选择变化）
    private int THEME_PRIMARY;
    private int THEME_PRIMARY_LIGHT;
    private int THEME_TEXT_DARK;
    private int THEME_BG;
    private int THEME_ACCENT;
    private String THEME_ICON;
    private String THEME_NAME;

    // 固定色值
    private static final int DS_CARD         = 0xFFFFFFFF;
    private static final int DS_CARD_SOFT    = 0xFFF5F5F5;
    private static final int DS_BORDER       = 0xFFE5E5E5;
    private static final int DS_TEXT         = 0xFF1A1A1A;
    private static final int DS_TEXT_SECOND  = 0xFF6B7280;
    private static final int DS_TEXT_MUTED   = 0xFF9CA3AF;
    private static final int DS_ACCENT       = 0xFF10B981;
    private static final int DS_ERROR        = 0xFFEF4444;
    private static final int DS_GRAY         = 0xFFD1D5DB;

    private Handler mHandler;
    private StatsData mData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 加载主题设置
        loadThemePreference();

        mHandler = new Handler(Looper.getMainLooper());

        // 先显示骨架屏
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

    // ============ 主题管理 ============
    private void loadThemePreference() {
        try {
            SharedPreferences sp = getSharedPreferences("module_theme", MODE_PRIVATE);
            currentTheme = sp.getInt("theme_index", 0);
        } catch (Throwable ignored) {
            currentTheme = 0;
        }
        applyThemeColors();
    }

    private void applyThemeColors() {
        if (currentTheme >= 0 && currentTheme < THEME_COLORS.length) {
            THEME_PRIMARY = THEME_COLORS[currentTheme][0];
            THEME_PRIMARY_LIGHT = THEME_COLORS[currentTheme][1];
            THEME_TEXT_DARK = THEME_COLORS[currentTheme][2];
            THEME_BG = THEME_COLORS[currentTheme][3];
            THEME_ACCENT = THEME_COLORS[currentTheme][4];
            THEME_ICON = THEME_ICONS[currentTheme];
            THEME_NAME = THEME_NAMES[currentTheme];
        }
    }

    private void saveThemePreference(int themeIndex) {
        try {
            SharedPreferences sp = getSharedPreferences("module_theme", MODE_PRIVATE);
            sp.edit().putInt("theme_index", themeIndex).apply();
        } catch (Throwable ignored) {}
    }

    // ============ 主题设置（经典对话框风格，与主页一致） ============
    private void showThemeSelector() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(DS_CARD);
        content.setPadding(0, 0, 0, 0);

        // ========== 顶部 Hero 标题区（与主页 Hero 卡片风格一致） ==========
        LinearLayout heroHeader = new LinearLayout(this);
        heroHeader.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable heroBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{THEME_PRIMARY, THEME_ACCENT, THEME_PRIMARY_LIGHT});
        heroBg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        heroHeader.setBackground(heroBg);
        heroHeader.setPadding(dp(20), dp(16), dp(20), dp(18));

        // 第一行：关闭按钮 + 标题 + 主题图标
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        // 关闭按钮
        LinearLayout closeBtn = new LinearLayout(this);
        closeBtn.setGravity(Gravity.CENTER);
        GradientDrawable closeGd = new GradientDrawable();
        closeGd.setColor(0x33FFFFFF);
        closeGd.setCornerRadius(dp(100));
        closeBtn.setBackground(closeGd);
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        TextView closeTv = new TextView(this);
        closeTv.setText("×");
        closeTv.setTextSize(20);
        closeTv.setTextColor(0xFFFFFFFF);
        closeTv.setGravity(Gravity.CENTER);
        closeTv.setTypeface(null, android.graphics.Typeface.BOLD);
        closeBtn.addView(closeTv);
        headerRow.addView(closeBtn);

        // 标题文字
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tclp.leftMargin = dp(14);
        titleCol.setLayoutParams(tclp);

        TextView titleMain = new TextView(this);
        titleMain.setText("主题设置");
        titleMain.setTextSize(18);
        titleMain.setTextColor(0xFFFFFFFF);
        titleMain.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(titleMain);

        TextView titleSub = new TextView(this);
        titleSub.setText("选择你喜欢的主题风格");
        titleSub.setTextSize(12);
        titleSub.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.topMargin = dp(2);
        titleSub.setLayoutParams(tsLp);
        titleCol.addView(titleSub);

        headerRow.addView(titleCol);

        // 右侧主题图标
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable();
        ibGd.setColor(0xFFFFFFFF);
        ibGd.setCornerRadius(dp(12));
        iconBox.setBackground(ibGd);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView iconTv = new TextView(this);
        iconTv.setText(THEME_ICON);
        iconTv.setTextSize(20);
        iconTv.setTextColor(THEME_PRIMARY);
        iconTv.setGravity(Gravity.CENTER);
        iconTv.setTypeface(null, android.graphics.Typeface.BOLD);
        iconBox.addView(iconTv);
        headerRow.addView(iconBox);

        heroHeader.addView(headerRow);

        // 当前主题标签行
        LinearLayout currentRow = new LinearLayout(this);
        currentRow.setOrientation(LinearLayout.HORIZONTAL);
        currentRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = dp(14);
        currentRow.setLayoutParams(crLp);

        // 当前主题胶囊
        LinearLayout currentTag = new LinearLayout(this);
        currentTag.setOrientation(LinearLayout.HORIZONTAL);
        currentTag.setGravity(Gravity.CENTER);
        currentTag.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable ctGd = new GradientDrawable();
        ctGd.setColor(0x30FFFFFF);
        ctGd.setCornerRadius(dp(100));
        currentTag.setBackground(ctGd);

        View currentDot = new View(this);
        GradientDrawable cdGd = new GradientDrawable();
        cdGd.setShape(GradientDrawable.OVAL);
        cdGd.setColor(0xFFFFFFFF);
        currentDot.setBackground(cdGd);
        currentDot.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(6)));
        currentTag.addView(currentDot);

        TextView currentText = new TextView(this);
        currentText.setText(" 当前: " + THEME_NAME);
        currentText.setTextSize(12);
        currentText.setTextColor(0xFFFFFFFF);
        currentText.setTypeface(null, android.graphics.Typeface.BOLD);
        currentTag.addView(currentText);

        currentRow.addView(currentTag);
        heroHeader.addView(currentRow);

        content.addView(heroHeader);

        // ========== 主题列表区（与主页卡片风格一致） ==========
        ScrollView listScroll = new ScrollView(this);
        listScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listScroll.setPadding(0, dp(12), 0, dp(8));
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        lslp.weight = 1;
        listScroll.setLayoutParams(lslp);

        LinearLayout themeList = new LinearLayout(this);
        themeList.setOrientation(LinearLayout.VERTICAL);
        themeList.setPadding(dp(16), dp(4), dp(16), dp(4));

        // 主题列表标题
        LinearLayout sectionTitle = new LinearLayout(this);
        sectionTitle.setOrientation(LinearLayout.HORIZONTAL);
        sectionTitle.setGravity(Gravity.CENTER_VERTICAL);
        sectionTitle.setPadding(dp(4), dp(4), dp(4), dp(10));

        TextView sectionTv = new TextView(this);
        sectionTv.setText("全部主题");
        sectionTv.setTextSize(13);
        sectionTv.setTextColor(DS_TEXT);
        sectionTv.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionTitle.addView(sectionTv);

        View s1 = new View(this);
        s1.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        sectionTitle.addView(s1);

        TextView countTv = new TextView(this);
        countTv.setText(THEME_NAMES.length + " 个");
        countTv.setTextSize(12);
        countTv.setTextColor(DS_TEXT_MUTED);
        sectionTitle.addView(countTv);

        themeList.addView(sectionTitle);

        // 主题项列表（与主页卡片风格一致）
        for (int i = 0; i < THEME_NAMES.length; i++) {
            final int themeIdx = i;
            final int[] colors = THEME_COLORS[i];
            final boolean isSelected = i == currentTheme;

            LinearLayout themeItem = new LinearLayout(this);
            themeItem.setOrientation(LinearLayout.HORIZONTAL);
            themeItem.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable tiBg = new GradientDrawable();
            tiBg.setColor(DS_CARD);
            tiBg.setCornerRadius(dp(14));
            tiBg.setStroke(dp(1), isSelected ? colors[0] : DS_BORDER);
            themeItem.setBackground(tiBg);
            themeItem.setPadding(dp(12), dp(12), dp(12), dp(12));

            LinearLayout.LayoutParams tilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tilp.bottomMargin = dp(8);
            themeItem.setLayoutParams(tilp);

            // 左侧颜色图标
            LinearLayout colorIcon = new LinearLayout(this);
            colorIcon.setGravity(Gravity.CENTER);
            GradientDrawable ciGd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{colors[0], colors[4], colors[1]});
            ciGd.setCornerRadius(dp(10));
            colorIcon.setBackground(ciGd);
            colorIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView ciText = new TextView(this);
            ciText.setText(THEME_ICONS[i]);
            ciText.setTextSize(16);
            ciText.setTextColor(0xFFFFFFFF);
            ciText.setGravity(Gravity.CENTER);
            ciText.setTypeface(null, android.graphics.Typeface.BOLD);
            colorIcon.addView(ciText);
            themeItem.addView(colorIcon);

            // 中间文字区
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tclp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tclp2.leftMargin = dp(12);
            textCol.setLayoutParams(tclp2);

            TextView nameTv = new TextView(this);
            nameTv.setText(THEME_NAMES[i]);
            nameTv.setTextSize(14);
            nameTv.setTextColor(isSelected ? colors[0] : DS_TEXT);
            nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(nameTv);

            TextView descTv = new TextView(this);
            descTv.setText(THEME_DESC[i]);
            descTv.setTextSize(11);
            descTv.setTextColor(DS_TEXT_SECOND);
            LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dtLp.topMargin = dp(2);
            descTv.setLayoutParams(dtLp);
            textCol.addView(descTv);

            themeItem.addView(textCol);

            // 右侧单选指示器
            if (isSelected) {
                LinearLayout radioChecked = new LinearLayout(this);
                radioChecked.setGravity(Gravity.CENTER);
                GradientDrawable rcGd = new GradientDrawable();
                rcGd.setShape(GradientDrawable.OVAL);
                rcGd.setColor(colors[0]);
                radioChecked.setBackground(rcGd);
                radioChecked.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));

                View innerDot = new View(this);
                GradientDrawable idGd = new GradientDrawable();
                idGd.setShape(GradientDrawable.OVAL);
                idGd.setColor(0xFFFFFFFF);
                innerDot.setBackground(idGd);
                innerDot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
                radioChecked.addView(innerDot);

                themeItem.addView(radioChecked);
            } else {
                View radioEmpty = new View(this);
                GradientDrawable reGd = new GradientDrawable();
                reGd.setShape(GradientDrawable.OVAL);
                reGd.setStroke(dp(2), DS_BORDER);
                radioEmpty.setBackground(reGd);
                radioEmpty.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
                themeItem.addView(radioEmpty);
            }

            // 点击事件
            themeItem.setOnClickListener(v -> {
                currentTheme = themeIdx;
                saveThemePreference(themeIdx);
                applyThemeColors();
                dialog.dismiss();
                renderFullUI();
                Toast.makeText(this, "已切换到「" + THEME_NAMES[themeIdx] + "」", Toast.LENGTH_SHORT).show();
            });

            themeList.addView(themeItem);
        }

        listScroll.addView(themeList);
        content.addView(listScroll);

        // ========== 底部操作区 ==========
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(16), dp(12), dp(16), dp(16));
        bottomBar.setBackgroundColor(DS_CARD_SOFT);

        View cancelBtn = buildButton("取消", DS_GRAY, v -> dialog.dismiss());
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(cancelBtn);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        bottomBar.addView(spacer);

        View confirmBtn = buildButton("确定", THEME_PRIMARY, v -> dialog.dismiss());
        confirmBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(confirmBtn);

        content.addView(bottomBar);

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ============ 骨架屏 ============
    private View buildSkeletonUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(THEME_BG);
        sv.setPadding(0, dp(4), 0, dp(40));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(20));

        addSkeletonBlock(root, dp(120), dp(8));
        addSkeletonBlock(root, dp(80), dp(8));
        addSkeletonBlock(root, dp(60), dp(8));
        addSkeletonBlock(root, dp(80), dp(8));

        sv.addView(root);
        return sv;
    }

    private void addSkeletonBlock(LinearLayout parent, int height, int topMargin) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD_SOFT);
        gd.setCornerRadius(dp(16));

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

    // ============ 完整 UI（大气美观设计） ============
    private void renderFullUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(THEME_BG);
        sv.setPadding(0, dp(4), 0, dp(40));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(20));

        // 1. Hero 顶部卡片（渐变背景）
        addHeroCard(root);

        // 2. 状态指示卡片（三列状态）
        addStatusTripleCard(root);

        // 3. 快捷操作卡片（开关+按钮）
        addActionCard(root);

        // 4. 统计数据卡片（两列数据）
        addStatsCard(root);

        // 5. 日志记录卡片
        addLogCard(root);

        // 6. 工作原理卡片
        addInfoCard(root);

        sv.addView(root);
        setContentView(sv);
    }

    // ============ Hero 顶部卡片（大气渐变设计） ============
    private void addHeroCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        // 渐变背景：主题色渐变（主色 -> 强调色 -> 浅色）
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{THEME_PRIMARY, THEME_ACCENT, THEME_PRIMARY_LIGHT});
        gd.setCornerRadius(dp(20));
        card.setBackground(gd);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));

        // 装饰元素：右上角光晕效果
        card.setClipToPadding(false);

        // 第一行：图标 + 标题 + 主题按钮
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        // 图标（白色圆角方块 + 主题图标文字）
        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ibGd = new GradientDrawable();
        ibGd.setColor(0xFFFFFFFF);
        ibGd.setCornerRadius(dp(14));
        iconBox.setBackground(ibGd);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));

        // 图标内部：渐变文字效果
        TextView iconChar = new TextView(this);
        iconChar.setText(THEME_ICON);
        iconChar.setTextSize(22);
        iconChar.setTextColor(THEME_PRIMARY);
        iconChar.setGravity(Gravity.CENTER);
        iconChar.setTypeface(null, android.graphics.Typeface.BOLD);
        iconBox.addView(iconChar);
        titleRow.addView(iconBox);

        // 标题列
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tclp.leftMargin = dp(14);
        titleCol.setLayoutParams(tclp);

        TextView titleMain = new TextView(this);
        titleMain.setText("答案显示模块");
        titleMain.setTextSize(18);
        titleMain.setTextColor(0xFFFFFFFF);
        titleMain.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(titleMain);

        TextView subTitle = new TextView(this);
        subTitle.setText("LSPosed Hook · 智能答题助手");
        subTitle.setTextSize(12);
        subTitle.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(3);
        subTitle.setLayoutParams(stlp);
        titleCol.addView(subTitle);
        titleRow.addView(titleCol);

        // 主题选择按钮（带主题图标预览）
        LinearLayout themeBtn = new LinearLayout(this);
        themeBtn.setOrientation(LinearLayout.HORIZONTAL);
        themeBtn.setGravity(Gravity.CENTER);
        GradientDrawable tbGd = new GradientDrawable();
        tbGd.setColor(0x33FFFFFF);
        tbGd.setCornerRadius(dp(100));
        themeBtn.setBackground(tbGd);
        themeBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        themeBtn.setOnClickListener(v -> showThemeSelector());

        TextView themeTv = new TextView(this);
        themeTv.setText(THEME_NAME);
        themeTv.setTextSize(11);
        themeTv.setTextColor(0xFFFFFFFF);
        themeTv.setTypeface(null, android.graphics.Typeface.BOLD);
        themeBtn.addView(themeTv);

        // 主题色圆点
        View themeDot = new View(this);
        GradientDrawable dotGd = new GradientDrawable();
        dotGd.setShape(GradientDrawable.OVAL);
        dotGd.setColor(0xFFFFFFFF);
        themeDot.setBackground(dotGd);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.leftMargin = dp(6);
        themeDot.setLayoutParams(dotLp);
        themeBtn.addView(themeDot);

        titleRow.addView(themeBtn);
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
        pkgLp.topMargin = dp(14);
        pkgRow.setLayoutParams(pkgLp);

        // 白色半透明背景条 + 左边框装饰
        GradientDrawable pkgBg = new GradientDrawable();
        pkgBg.setColor(0x20FFFFFF);
        pkgBg.setCornerRadius(dp(10));
        pkgRow.setBackground(pkgBg);
        pkgRow.setPadding(dp(12), dp(8), dp(12), dp(8));

        // 左侧状态图标列
        LinearLayout pkgIconCol = new LinearLayout(this);
        pkgIconCol.setOrientation(LinearLayout.VERTICAL);
        pkgIconCol.setGravity(Gravity.CENTER);

        View statusBigDot = new View(this);
        GradientDrawable sbdGd = new GradientDrawable();
        sbdGd.setShape(GradientDrawable.OVAL);
        sbdGd.setColor(installed ? 0xFFFFFFFF : DS_ERROR);
        statusBigDot.setBackground(sbdGd);
        statusBigDot.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
        pkgIconCol.addView(statusBigDot);

        pkgRow.addView(pkgIconCol);

        // 包名信息列
        LinearLayout pkgInfoCol = new LinearLayout(this);
        pkgInfoCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams picLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        picLp.leftMargin = dp(10);
        pkgInfoCol.setLayoutParams(picLp);

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText(TARGET_PACKAGE);
        pkgLabel.setTextSize(11);
        pkgLabel.setTextColor(0xFFFFFFFF);
        pkgLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        pkgInfoCol.addView(pkgLabel);

        TextView pkgStatusLabel = new TextView(this);
        pkgStatusLabel.setText(installed ? "目标应用已就绪" : "目标应用未安装");
        pkgStatusLabel.setTextSize(10);
        pkgStatusLabel.setTextColor(0xAAFFFFFF);
        LinearLayout.LayoutParams pslLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pslLp.topMargin = dp(2);
        pkgStatusLabel.setLayoutParams(pslLp);
        pkgInfoCol.addView(pkgStatusLabel);

        pkgRow.addView(pkgInfoCol);

        // 右侧版本号
        if (installed && versionName != null) {
            TextView versionTv = new TextView(this);
            versionTv.setText("v" + versionName);
            versionTv.setTextSize(10);
            versionTv.setTextColor(0xFFFFFFFF);
            versionTv.setBackgroundColor(0x33FFFFFF);
            versionTv.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable vtGd = new GradientDrawable();
            vtGd.setColor(0x33FFFFFF);
            vtGd.setCornerRadius(dp(100));
            versionTv.setBackground(vtGd);
            pkgRow.addView(versionTv);
        }

        card.addView(pkgRow);

        // 第三行：主题特色标签
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = dp(10);
        tagRow.setLayoutParams(trLp);

        String[] featureTags = {"自动答题", "自动下一题", "实时日志"};
        for (int i = 0; i < featureTags.length; i++) {
            TextView tagTv = new TextView(this);
            tagTv.setText(featureTags[i]);
            tagTv.setTextSize(10);
            tagTv.setTextColor(0xFFFFFFFF);
            tagTv.setGravity(Gravity.CENTER);
            GradientDrawable tagGd = new GradientDrawable();
            tagGd.setColor(0x25FFFFFF);
            tagGd.setCornerRadius(dp(100));
            tagGd.setStroke(dp(1), 0x40FFFFFF);
            tagTv.setBackground(tagGd);
            tagTv.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) tagLp.leftMargin = dp(6);
            tagTv.setLayoutParams(tagLp);
            tagRow.addView(tagTv);
        }

        card.addView(tagRow);
        root.addView(card, cardParams());
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
        gd.setCornerRadius(dp(20));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(6), dp(12), dp(6), dp(12));

        // 列1：模块状态
        addStatusCol(card, active ? "✓" : "!", active ? "已激活" : "未激活",
                active ? THEME_PRIMARY : DS_GRAY);

        addStatusDivider(card);

        // 列2：自动答题
        addStatusCol(card, autoOn ? "⚡" : "○", autoOn ? "答题开" : "答题关",
                autoOn ? THEME_PRIMARY : DS_GRAY);

        addStatusDivider(card);

        // 列3：自动下一题
        addStatusCol(card, autoNextOn ? "→" : "○", autoNextOn ? "下一题开" : "下一题关",
                autoNextOn ? THEME_PRIMARY : DS_GRAY);

        root.addView(card, cardParams());
    }

    private void addStatusCol(LinearLayout parent, String icon, String title, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.setPadding(dp(8), dp(8), dp(8), dp(8));

        // 圆形图标背景
        LinearLayout iconBg = new LinearLayout(this);
        iconBg.setGravity(Gravity.CENTER);
        GradientDrawable bgGd = new GradientDrawable();
        bgGd.setShape(GradientDrawable.OVAL);
        bgGd.setColor(color);
        iconBg.setBackground(bgGd);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconBg.setLayoutParams(iconLp);

        TextView iconTv = new TextView(this);
        iconTv.setText(icon);
        iconTv.setTextSize(14);
        iconTv.setTextColor(0xFFFFFFFF);
        iconTv.setGravity(Gravity.CENTER);
        iconTv.setTypeface(null, android.graphics.Typeface.BOLD);
        iconBg.addView(iconTv);
        col.addView(iconBg);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(11);
        titleTv.setTextColor(DS_TEXT);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        titleTv.setLayoutParams(tlp);
        col.addView(titleTv);

        parent.addView(col);
    }

    private void addStatusDivider(LinearLayout parent) {
        View div = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_BORDER);
        div.setBackground(gd);
        div.setLayoutParams(new LinearLayout.LayoutParams(dp(2), dp(44)));
        parent.addView(div);
    }

    // ============ 快捷操作卡片 ============
    private void addActionCard(LinearLayout root) {
        final boolean autoOn = mData != null && mData.autoSelectEnabled;
        final boolean autoNextOn = mData != null && mData.autoNextEnabled;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(20));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 标题
        TextView title = new TextView(this);
        title.setText("快捷操作");
        title.setTextSize(13);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        // 开关行（两列）
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.topMargin = dp(12);
        switchRow.setLayoutParams(swLp);

        // 自动答题开关
        View switch1 = buildSwitch("自动答题", autoOn, v -> toggleAutoSelect(!autoOn));
        switch1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(switch1);

        View spacer1 = new View(this);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        switchRow.addView(spacer1);

        // 自动下一题开关
        View switch2 = buildSwitch("自动下一题", autoNextOn, v -> toggleAutoNext(!autoNextOn));
        switch2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(switch2);

        card.addView(switchRow);

        // 按钮行（三列）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(12);
        btnRow.setLayoutParams(brLp);

        View btn1 = buildButton("启动应用", THEME_PRIMARY, v -> launchTargetApp());
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

    private View buildSwitch(String label, boolean on, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? THEME_BG : DS_CARD_SOFT);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), on ? THEME_PRIMARY : DS_BORDER);
        row.setBackground(bg);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setOnClickListener(click);

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(12);
        labelTv.setTextColor(DS_TEXT);
        labelTv.setTypeface(null, android.graphics.Typeface.BOLD);
        labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelTv);

        // 状态指示器（圆角矩形）
        View indicator = new View(this);
        GradientDrawable indGd = new GradientDrawable();
        indGd.setCornerRadius(dp(4));
        indGd.setColor(on ? THEME_PRIMARY : DS_GRAY);
        indicator.setBackground(indGd);
        LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(dp(24), dp(12));
        indicator.setLayoutParams(indLp);
        row.addView(indicator);

        return row;
    }

    private View buildButton(String label, int color, View.OnClickListener click) {
        LinearLayout btn = new LinearLayout(this);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(12));
        btn.setBackground(gd);
        btn.setPadding(dp(10), dp(10), dp(10), dp(10));
        btn.setOnClickListener(click);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.addView(tv);

        return btn;
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
        gd.setCornerRadius(dp(20));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        addStatCol(card, "命中次数", hh < 0 ? "—" : String.valueOf(hh), THEME_PRIMARY);
        addStatDivider(card);
        addStatCol(card, "最近活跃", lt <= 0 ? "—" : formatTimeShort(lt), THEME_ACCENT);

        root.addView(card, cardParams());
    }

    private void addStatCol(LinearLayout parent, String label, String value, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(22);
        val.setTextColor(color);
        val.setGravity(Gravity.CENTER);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(val);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(11);
        lbl.setTextColor(DS_TEXT_MUTED);
        lbl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(4);
        lbl.setLayoutParams(llp);
        col.addView(lbl);

        parent.addView(col);
    }

    private void addStatDivider(LinearLayout parent) {
        View div = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_BORDER);
        div.setBackground(gd);
        div.setLayoutParams(new LinearLayout.LayoutParams(dp(2), dp(36)));
        parent.addView(div);
    }

    // ============ 日志记录卡片 ============
    private void addLogCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(20));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 标题行
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleLeft = new LinearLayout(this);
        titleLeft.setOrientation(LinearLayout.HORIZONTAL);
        titleLeft.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("运行日志");
        title.setTextSize(13);
        title.setTextColor(DS_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleLeft.addView(title);

        final TextView countBadge = new TextView(this);
        countBadge.setText("0");
        countBadge.setTextSize(10);
        countBadge.setTextColor(0xFFFFFFFF);
        countBadge.setGravity(Gravity.CENTER);
        countBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable badgeGd = new GradientDrawable();
        badgeGd.setShape(GradientDrawable.OVAL);
        badgeGd.setColor(THEME_PRIMARY);
        countBadge.setBackground(badgeGd);
        countBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(8);
        countBadge.setLayoutParams(badgeLp);
        titleLeft.addView(countBadge);

        titleRow.addView(titleLeft);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        titleRow.addView(spacer);

        TextView hintTv = new TextView(this);
        hintTv.setText("查看全部");
        hintTv.setTextSize(11);
        hintTv.setTextColor(THEME_PRIMARY);
        hintTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(hintTv);

        card.addView(titleRow);
        card.setOnClickListener(v -> showLogDialog());

        // 最新日志预览区域
        final LinearLayout logPreviewContainer = new LinearLayout(this);
        logPreviewContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpcLp.topMargin = dp(12);
        logPreviewContainer.setLayoutParams(lpcLp);

        final TextView loadingTv = new TextView(this);
        loadingTv.setText("加载中...");
        loadingTv.setTextSize(12);
        loadingTv.setTextColor(DS_TEXT_MUTED);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, dp(16), 0, dp(16));
        logPreviewContainer.addView(loadingTv);

        card.addView(logPreviewContainer);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(12);
        btnRow.setLayoutParams(brLp);

        View btn1 = buildButton("查看日志", THEME_PRIMARY, v -> showLogDialog());
        btn1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btn1);

        View s1 = new View(this);
        s1.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        btnRow.addView(s1);

        View btn2 = buildButton("清空日志", DS_ERROR, v -> clearLogs());
        btn2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btn2);

        card.addView(btnRow);
        root.addView(card, cardParams());

        // 异步加载最新日志
        new Thread(() -> {
            final java.util.ArrayList<LogItem> logs = loadLogData();
            mHandler.post(() -> {
                logPreviewContainer.removeAllViews();
                countBadge.setText(String.valueOf(logs.size()));

                if (logs == null || logs.isEmpty()) {
                    LinearLayout emptyState = new LinearLayout(this);
                    emptyState.setOrientation(LinearLayout.VERTICAL);
                    emptyState.setGravity(Gravity.CENTER);
                    emptyState.setPadding(0, dp(20), 0, dp(16));
                    emptyState.setBackgroundColor(0x00000000);

                    // 空状态图标容器
                    LinearLayout emptyIconBox = new LinearLayout(this);
                    emptyIconBox.setGravity(Gravity.CENTER);
                    GradientDrawable eibGd = new GradientDrawable();
                    eibGd.setShape(GradientDrawable.OVAL);
                    eibGd.setColor(DS_CARD_SOFT);
                    eibGd.setStroke(dp(2), DS_BORDER);
                    emptyIconBox.setBackground(eibGd);
                    emptyIconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));

                    TextView emptyIconTv = new TextView(this);
                    emptyIconTv.setText("📋");
                    emptyIconTv.setTextSize(20);
                    emptyIconTv.setGravity(Gravity.CENTER);
                    emptyIconBox.addView(emptyIconTv);
                    emptyState.addView(emptyIconBox);

                    TextView emptyTitleTv = new TextView(this);
                    emptyTitleTv.setText("暂无日志记录");
                    emptyTitleTv.setTextSize(13);
                    emptyTitleTv.setTextColor(DS_TEXT);
                    emptyTitleTv.setGravity(Gravity.CENTER);
                    emptyTitleTv.setTypeface(null, android.graphics.Typeface.BOLD);
                    LinearLayout.LayoutParams ettLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    ettLp.topMargin = dp(10);
                    emptyTitleTv.setLayoutParams(ettLp);
                    emptyState.addView(emptyTitleTv);

                    TextView emptyDescTv = new TextView(this);
                    emptyDescTv.setText("开始答题后将自动记录运行日志");
                    emptyDescTv.setTextSize(11);
                    emptyDescTv.setTextColor(DS_TEXT_MUTED);
                    emptyDescTv.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams edtLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    edtLp.topMargin = dp(4);
                    emptyDescTv.setLayoutParams(edtLp);
                    emptyState.addView(emptyDescTv);

                    logPreviewContainer.addView(emptyState);
                } else {
                    int showCount = Math.min(logs.size(), 3);
                    for (int i = 0; i < showCount; i++) {
                        View previewItem = buildLogPreviewItem(logs.get(i));
                        logPreviewContainer.addView(previewItem);
                    }

                    if (logs.size() > 3) {
                        TextView moreTv = new TextView(this);
                        moreTv.setText("还有 " + (logs.size() - 3) + " 条记录，点击查看全部");
                        moreTv.setTextSize(11);
                        moreTv.setTextColor(DS_TEXT_MUTED);
                        moreTv.setGravity(Gravity.CENTER);
                        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        mtLp.topMargin = dp(8);
                        moreTv.setLayoutParams(mtLp);
                        logPreviewContainer.addView(moreTv);
                    }
                }
            });
        }).start();
    }

    // ============ 构建日志预览条目（简化版） ============
    private View buildLogPreviewItem(LogItem item) {
        LinearLayout itemView = new LinearLayout(this);
        itemView.setOrientation(LinearLayout.HORIZONTAL);
        itemView.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DS_CARD_SOFT);
        bg.setCornerRadius(dp(10));
        itemView.setBackground(bg);
        itemView.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        itemView.setLayoutParams(lp);

        // 左侧类型指示点
        View dot = new View(this);
        GradientDrawable dotGd = new GradientDrawable();
        dotGd.setShape(GradientDrawable.OVAL);
        dotGd.setColor("answer".equals(item.type) ? THEME_PRIMARY : THEME_ACCENT);
        dot.setBackground(dotGd);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        itemView.addView(dot);

        // 中间内容区域
        LinearLayout contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ccLp.leftMargin = dp(10);
        contentCol.setLayoutParams(ccLp);

        // 第一行：类型 + 时间
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView typeTv = new TextView(this);
        typeTv.setText("answer".equals(item.type) ? "自动答题" : "下一题");
        typeTv.setTextSize(11);
        typeTv.setTextColor("answer".equals(item.type) ? THEME_PRIMARY : THEME_ACCENT);
        typeTv.setTypeface(null, android.graphics.Typeface.BOLD);
        row1.addView(typeTv);

        View s1 = new View(this);
        s1.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row1.addView(s1);

        TextView timeTv = new TextView(this);
        timeTv.setText(formatLogTime(item.time));
        timeTv.setTextSize(10);
        timeTv.setTextColor(DS_TEXT_MUTED);
        row1.addView(timeTv);

        contentCol.addView(row1);

        // 第二行：方法（简化显示）
        if (item.method != null && !item.method.isEmpty()) {
            TextView methodTv = new TextView(this);
            String methodText = item.method;
            if (methodText.length() > 30) {
                methodText = methodText.substring(0, 27) + "...";
            }
            methodTv.setText("方法: " + methodText);
            methodTv.setTextSize(11);
            methodTv.setTextColor(DS_TEXT_SECOND);
            LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mtLp.topMargin = dp(2);
            methodTv.setLayoutParams(mtLp);
            contentCol.addView(methodTv);
        }

        itemView.addView(contentCol);

        return itemView;
    }

    // ============ 日志对话框 ============
    private void showLogDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(DS_CARD);

        // 标题栏（渐变背景）
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable titleBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{THEME_PRIMARY, THEME_ACCENT});
        titleBar.setBackground(titleBg);
        titleBar.setPadding(dp(20), dp(16), dp(20), dp(16));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText("运行日志");
        titleTv.setTextSize(18);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(titleTv);

        final TextView countTv = new TextView(this);
        countTv.setText("加载中...");
        countTv.setTextSize(11);
        countTv.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctLp.topMargin = dp(2);
        countTv.setLayoutParams(ctLp);
        titleCol.addView(countTv);

        titleBar.addView(titleCol);

        View titleSpacer = new View(this);
        titleSpacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        titleBar.addView(titleSpacer);

        // 复制按钮
        LinearLayout copyBtn = new LinearLayout(this);
        copyBtn.setGravity(Gravity.CENTER);
        GradientDrawable copyGd = new GradientDrawable();
        copyGd.setColor(0x33FFFFFF);
        copyGd.setCornerRadius(dp(100));
        copyBtn.setBackground(copyGd);
        copyBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        copyBtn.setOnClickListener(v -> copyLogsToClipboard());

        TextView copyTv = new TextView(this);
        copyTv.setText("复制全部");
        copyTv.setTextSize(11);
        copyTv.setTextColor(0xFFFFFFFF);
        copyTv.setTypeface(null, android.graphics.Typeface.BOLD);
        copyBtn.addView(copyTv);
        titleBar.addView(copyBtn);

        content.addView(titleBar);

        // 统计栏
        LinearLayout statsBar = new LinearLayout(this);
        statsBar.setOrientation(LinearLayout.HORIZONTAL);
        statsBar.setGravity(Gravity.CENTER_VERTICAL);
        statsBar.setBackgroundColor(DS_CARD_SOFT);
        statsBar.setPadding(dp(16), dp(10), dp(16), dp(10));

        final TextView answerCountTv = new TextView(this);
        answerCountTv.setText("答题: 0");
        answerCountTv.setTextSize(11);
        answerCountTv.setTextColor(THEME_PRIMARY);
        answerCountTv.setTypeface(null, android.graphics.Typeface.BOLD);
        statsBar.addView(answerCountTv);

        View statsSpacer = new View(this);
        statsSpacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        statsBar.addView(statsSpacer);

        final TextView nextCountTv = new TextView(this);
        nextCountTv.setText("下一题: 0");
        nextCountTv.setTextSize(11);
        nextCountTv.setTextColor(THEME_ACCENT);
        nextCountTv.setTypeface(null, android.graphics.Typeface.BOLD);
        statsBar.addView(nextCountTv);

        content.addView(statsBar);

        // 日志列表容器（ScrollView）
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        final LinearLayout logList = new LinearLayout(this);
        logList.setOrientation(LinearLayout.VERTICAL);
        logList.setPadding(dp(12), dp(10), dp(12), dp(10));
        logList.setBackgroundColor(DS_CARD);

        // 加载中提示
        TextView loadingTv = new TextView(this);
        loadingTv.setText("加载中...");
        loadingTv.setTextSize(12);
        loadingTv.setTextColor(DS_TEXT_MUTED);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, dp(40), 0, dp(40));
        logList.addView(loadingTv);

        // 加载日志数据
        new Thread(() -> {
            final java.util.ArrayList<LogItem> logs = loadLogData();
            mHandler.post(() -> {
                logList.removeAllViews();
                countTv.setText("共 " + logs.size() + " 条记录");

                int answerCount = 0;
                int nextCount = 0;
                for (LogItem item : logs) {
                    if ("answer".equals(item.type)) answerCount++;
                    else nextCount++;
                }
                answerCountTv.setText("答题: " + answerCount);
                nextCountTv.setText("下一题: " + nextCount);

                if (logs == null || logs.isEmpty()) {
                    // 空状态
                    LinearLayout emptyState = new LinearLayout(this);
                    emptyState.setOrientation(LinearLayout.VERTICAL);
                    emptyState.setGravity(Gravity.CENTER);
                    emptyState.setPadding(0, dp(50), 0, dp(50));

                    LinearLayout emptyIconBox = new LinearLayout(this);
                    emptyIconBox.setGravity(Gravity.CENTER);
                    GradientDrawable eibGd = new GradientDrawable();
                    eibGd.setShape(GradientDrawable.OVAL);
                    eibGd.setColor(DS_CARD_SOFT);
                    eibGd.setStroke(dp(2), DS_BORDER);
                    emptyIconBox.setBackground(eibGd);
                    emptyIconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(72)));

                    TextView emptyIconTv = new TextView(this);
                    emptyIconTv.setText("📋");
                    emptyIconTv.setTextSize(28);
                    emptyIconTv.setGravity(Gravity.CENTER);
                    emptyIconBox.addView(emptyIconTv);
                    emptyState.addView(emptyIconBox);

                    TextView emptyTitleTv = new TextView(this);
                    emptyTitleTv.setText("暂无日志记录");
                    emptyTitleTv.setTextSize(15);
                    emptyTitleTv.setTextColor(DS_TEXT);
                    emptyTitleTv.setGravity(Gravity.CENTER);
                    emptyTitleTv.setTypeface(null, android.graphics.Typeface.BOLD);
                    LinearLayout.LayoutParams ettLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    ettLp.topMargin = dp(14);
                    emptyTitleTv.setLayoutParams(ettLp);
                    emptyState.addView(emptyTitleTv);

                    TextView emptyDescTv = new TextView(this);
                    emptyDescTv.setText("开始答题后，运行日志将自动记录在这里");
                    emptyDescTv.setTextSize(12);
                    emptyDescTv.setTextColor(DS_TEXT_MUTED);
                    emptyDescTv.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams edtLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    edtLp.topMargin = dp(6);
                    emptyDescTv.setLayoutParams(edtLp);
                    emptyState.addView(emptyDescTv);

                    logList.addView(emptyState);
                } else {
                    for (LogItem item : logs) {
                        View logItem = buildLogItem(item);
                        logList.addView(logItem);
                    }
                }
            });
        }).start();

        scrollView.addView(logList);
        content.addView(scrollView);

        // 底部按钮栏
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(16), dp(12), dp(16), dp(16));
        bottomBar.setBackgroundColor(DS_CARD_SOFT);

        View clearBtn = buildButton("清空日志", DS_ERROR, v -> {
            clearLogs();
            dialog.dismiss();
            refreshStatsAsync();
        });
        clearBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(clearBtn);

        View s = new View(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        bottomBar.addView(s);

        View closeBtn = buildButton("关闭", THEME_PRIMARY, v -> dialog.dismiss());
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(closeBtn);

        content.addView(bottomBar);

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ============ 构建日志条目 ============
    private View buildLogItem(LogItem item) {
        boolean isAnswer = "answer".equals(item.type);
        int accentColor = isAnswer ? THEME_PRIMARY : THEME_ACCENT;

        LinearLayout itemView = new LinearLayout(this);
        itemView.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DS_CARD);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), DS_BORDER);
        itemView.setBackground(bg);
        itemView.setPadding(0, 0, dp(12), 0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        itemView.setLayoutParams(lp);

        // 左侧彩色装饰条
        View leftBar = new View(this);
        GradientDrawable barGd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{accentColor, (accentColor & 0x80FFFFFF)});
        barGd.setCornerRadii(new float[]{dp(12), dp(12), 0, 0, 0, 0, dp(12), dp(12)});
        leftBar.setBackground(barGd);
        leftBar.setLayoutParams(new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT));
        itemView.addView(leftBar);

        // 右侧内容区域
        LinearLayout contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        contentCol.setPadding(dp(12), dp(10), 0, dp(10));
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        contentCol.setLayoutParams(ccLp);

        // 第一行：类型标签 + 时间
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        // 类型标签
        LinearLayout typeBox = new LinearLayout(this);
        typeBox.setGravity(Gravity.CENTER);
        typeBox.setOrientation(LinearLayout.HORIZONTAL);

        // 类型图标点
        View typeDot = new View(this);
        GradientDrawable dotGd = new GradientDrawable();
        dotGd.setShape(GradientDrawable.OVAL);
        dotGd.setColor(accentColor);
        typeDot.setBackground(dotGd);
        typeDot.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(6)));
        typeBox.addView(typeDot);

        TextView typeTv = new TextView(this);
        typeTv.setText(isAnswer ? "自动答题" : "下一题");
        typeTv.setTextSize(11);
        typeTv.setTextColor(accentColor);
        typeTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttLp.leftMargin = dp(6);
        typeTv.setLayoutParams(ttLp);
        typeBox.addView(typeTv);

        row1.addView(typeBox);

        View s1 = new View(this);
        s1.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row1.addView(s1);

        TextView timeTv = new TextView(this);
        timeTv.setText(formatLogTime(item.time));
        timeTv.setTextSize(11);
        timeTv.setTextColor(DS_TEXT_MUTED);
        row1.addView(timeTv);

        contentCol.addView(row1);

        // 第二行：方法
        if (item.method != null && !item.method.isEmpty()) {
            TextView methodTv = new TextView(this);
            methodTv.setText("方法: " + item.method);
            methodTv.setTextSize(12);
            methodTv.setTextColor(DS_TEXT);
            methodTv.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mtLp.topMargin = dp(6);
            methodTv.setLayoutParams(mtLp);
            contentCol.addView(methodTv);
        }

        // 第三行：详情
        if (item.detail != null && !item.detail.isEmpty()) {
            TextView detailTv = new TextView(this);
            detailTv.setText(item.detail);
            detailTv.setTextSize(11);
            detailTv.setTextColor(DS_TEXT_SECOND);
            detailTv.setMaxLines(2);
            detailTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dtLp.topMargin = dp(3);
            detailTv.setLayoutParams(dtLp);
            contentCol.addView(detailTv);
        }

        itemView.addView(contentCol);

        return itemView;
    }

    // ============ 格式化日志时间 ============
    private String formatLogTime(long time) {
        if (time <= 0) return "";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm:ss");
        return sdf.format(new java.util.Date(time));
    }

    // ============ 复制日志到剪贴板 ============
    private void copyLogsToClipboard() {
        new Thread(() -> {
            final java.util.ArrayList<LogItem> logs = loadLogData();
            mHandler.post(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== 运行日志 ===\n\n");
                    for (LogItem item : logs) {
                        sb.append("[").append(formatLogTime(item.time)).append("] ");
                        sb.append("answer".equals(item.type) ? "[自动答题]" : "[下一题]");
                        sb.append(" 方法: ").append(item.method != null ? item.method : "");
                        if (item.detail != null && !item.detail.isEmpty()) {
                            sb.append(" 详情: ").append(item.detail);
                        }
                        sb.append("\n");
                    }
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", sb.toString()));
                    }
                    Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    // ============ 清空日志 ============
    private void clearLogs() {
        try {
            getContentResolver().delete(
                    Uri.parse("content://" + MODULE_PACKAGE + ".stats/log_clear"),
                    null, null);
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "清空失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ============ 工作原理卡片 ============
    private void addInfoCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(DS_CARD);
        gd.setCornerRadius(dp(20));
        gd.setStroke(dp(1), DS_BORDER);
        card.setBackground(gd);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("工作原理");
        title.setTextSize(13);
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
            srp.topMargin = dp(8);
            stepRow.setLayoutParams(srp);

            // 步骤编号（主题色圆形）
            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setTextSize(11);
            num.setTextColor(0xFFFFFFFF);
            num.setGravity(Gravity.CENTER);
            GradientDrawable numBg = new GradientDrawable();
            numBg.setShape(GradientDrawable.OVAL);
            numBg.setColor(THEME_PRIMARY);
            num.setBackground(numBg);
            num.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
            stepRow.addView(num);

            TextView stepTv = new TextView(this);
            stepTv.setText(steps[i]);
            stepTv.setTextSize(12);
            stepTv.setTextColor(DS_TEXT_SECOND);
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            stlp.leftMargin = dp(10);
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

    // ============ 日志数据模型 ============
    private static class LogItem {
        int id;
        String type;
        String method;
        String detail;
        long time;
    }

    // ============ 加载日志数据 ============
    private java.util.ArrayList<LogItem> loadLogData() {
        java.util.ArrayList<LogItem> list = new java.util.ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    Uri.parse("content://" + MODULE_PACKAGE + ".stats/log"),
                    null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    try {
                        LogItem item = new LogItem();
                        item.id = cursor.getInt(cursor.getColumnIndex("_id"));
                        item.type = cursor.getString(cursor.getColumnIndex("type"));
                        item.method = cursor.getString(cursor.getColumnIndex("method"));
                        item.detail = cursor.getString(cursor.getColumnIndex("detail"));
                        item.time = cursor.getLong(cursor.getColumnIndex("time"));
                        list.add(item);
                    } catch (Throwable ignored) {}
                } while (cursor.moveToNext());
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
        }
        return list;
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