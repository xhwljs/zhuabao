package com.answer.revealer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨进程统计数据提供者
 * 目标应用 (tz.ycsy.az) 通过 ContentResolver 把 hook 统计数据写入此处
 * 模块应用 (com.answer.revealer) 的 MainActivity 通过 ContentResolver 读取
 *
 * 使用方式：
 *   写入: contentResolver.update(Uri.parse("content://com.answer.revealer.stats/update"), values, null, null);
 *   读取: contentResolver.query(Uri.parse("content://com.answer.revealer.stats/query"), null, null, null, null);
 *   记录请求: contentResolver.insert(Uri.parse("content://com.answer.revealer.stats/request"), values);
 *   清空: contentResolver.delete(Uri.parse("content://com.answer.revealer.stats/clear"), null, null);
 *
 * 底层用 SharedPreferences 存储（ContentProvider 进程可访问），性能足够且实现简单。
 */
public class StatsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.answer.revealer.stats";
    public static final Uri URI_BASE = Uri.parse("content://" + AUTHORITY);
    public static final Uri URI_UPDATE = Uri.withAppendedPath(URI_BASE, "update");
    public static final Uri URI_QUERY = Uri.withAppendedPath(URI_BASE, "query");
    public static final Uri URI_REQUEST = Uri.withAppendedPath(URI_BASE, "request");
    public static final Uri URI_CLEAR = Uri.withAppendedPath(URI_BASE, "clear");
    public static final Uri URI_ANSWER = Uri.withAppendedPath(URI_BASE, "answer");
    public static final Uri URI_LOG = Uri.withAppendedPath(URI_BASE, "log");
    public static final Uri URI_LOG_CLEAR = Uri.withAppendedPath(URI_BASE, "log_clear");

    private static final String SP_NAME = "module_stats";
    private static final String TAG = "StatsProvider";

    // 列名
    public static final String KEY_TARGET_HIT_COUNT = "target_hit_count";
    public static final String KEY_LAST_TIME = "last_hook_time";
    public static final String KEY_MODULE_ACTIVE = "module_active_v1";
    public static final String KEY_PKG_HOOKED = "package_hooked";
    public static final String KEY_AUTO_SELECT = "auto_select_enabled";
    public static final String KEY_AUTO_NEXT = "auto_next_enabled";
    public static final String KEY_ANSWER_TEXT = "answer_text";
    public static final String KEY_ANSWER_MARKED = "answer_marked_text";
    public static final String KEY_LOG_COUNTER = "_log_counter";
    public static final String LOG_PREFIX = "log_";
    public static final String LOG_TYPE_ANSWER = "answer";
    public static final String LOG_TYPE_NEXT = "next";

    private static final String[] ALL_KEYS = {
            KEY_TARGET_HIT_COUNT,
            KEY_LAST_TIME,
            KEY_MODULE_ACTIVE,
            KEY_PKG_HOOKED,
            KEY_AUTO_SELECT,
            KEY_AUTO_NEXT
    };

    // 目标应用包名 - 用于 fallback 读取其 SP
    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String TARGET_SP_NAME = "answer_revealer_status";

    // 进程内缓存（ContentProvider 可能在调用者进程，也可能在自身进程）
    // 主要依赖 SP 持久化，cache 仅用于快速判断
    private static final ConcurrentHashMap<String, Long> sCachedCounts = new ConcurrentHashMap<>();

    private SharedPreferences mSP;

    @Override
    public boolean onCreate() {
        try {
            mSP = getContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);

            return true;
        } catch (Throwable t) {

            return false;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            // 每次查询强制重新获取 SP，确保读到最新值
            SharedPreferences sp = getContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (sp == null) return emptyCursor();

            // 读取所有键值
            MatrixCursor cursor;
            String path = uri.getLastPathSegment();

            if ("requests".equals(path)) {
                // 返回请求记录列表
                List<MatrixCursor.RowBuilder> rows = new ArrayList<>();
                List<Map.Entry<String, ?>> reqEntries = new ArrayList<>();
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    if (e.getKey() != null && e.getKey().startsWith("req_")) {
                        reqEntries.add(e);
                    }
                }
                // 按 key 排序（时间序）
                Collections.sort(reqEntries, new Comparator<Map.Entry<String, ?>>() {
                    @Override
                    public int compare(Map.Entry<String, ?> a, Map.Entry<String, ?> b) {
                        return a.getKey().compareTo(b.getKey());
                    }
                });

                String[] columns = {"_id", "type", "url", "time"};
                cursor = new MatrixCursor(columns);
                for (int i = 0; i < reqEntries.size(); i++) {
                    Map.Entry<String, ?> e = reqEntries.get(i);
                    String val = e.getValue() instanceof String ? (String) e.getValue() : "";
                    int bar = val.indexOf("|");
                    String type = bar > 0 ? val.substring(0, bar) : "unknown";
                    String url = bar > 0 && bar < val.length() - 1 ? val.substring(bar + 1) : val;
                    // 从 key 解析时间戳
                    long t = 0;
                    try {
                        int last = e.getKey().lastIndexOf("_");
                        if (last > 0) {
                            t = Long.parseLong(e.getKey().substring(last + 1));
                        }
                    } catch (Throwable ignored) {}
                    cursor.newRow().add(i + 1).add(type).add(url).add(t);
                }
                return cursor;
            }

            if ("log".equals(path)) {
                List<Map.Entry<String, ?>> logEntries = new ArrayList<>();
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    if (e.getKey() != null && e.getKey().startsWith(LOG_PREFIX)) {
                        logEntries.add(e);
                    }
                }
                Collections.sort(logEntries, new Comparator<Map.Entry<String, ?>>() {
                    @Override
                    public int compare(Map.Entry<String, ?> a, Map.Entry<String, ?> b) {
                        return b.getKey().compareTo(a.getKey());
                    }
                });

                String[] columns = {"_id", "type", "method", "detail", "time"};
                cursor = new MatrixCursor(columns);
                int limit = Math.min(logEntries.size(), 200);
                for (int i = 0; i < limit; i++) {
                    Map.Entry<String, ?> e = logEntries.get(i);
                    String val = e.getValue() instanceof String ? (String) e.getValue() : "";
                    String[] parts = val.split("\\|", -1);
                    String type = parts.length > 0 ? parts[0] : "";
                    String method = parts.length > 1 ? parts[1] : "";
                    String detail = parts.length > 2 ? parts[2] : "";
                    long t = 0;
                    try {
                        int last = e.getKey().lastIndexOf("_");
                        if (last > 0) {
                            t = Long.parseLong(e.getKey().substring(last + 1));
                        }
                    } catch (Throwable ignored) {}
                    cursor.newRow().add(i + 1).add(type).add(method).add(detail).add(t);
                }
                return cursor;
            }

            // 默认：返回主要统计
            // === 如果自己的 SP 没有数据，尝试从目标应用的 SP 读取并同步
            // 这是双保险：ContentProvider 写入失败时 XposedInit 会写入目标应用的 SP
            try {
                long selfTargetHitCount = sp.getLong(KEY_TARGET_HIT_COUNT, 0);
                if (selfTargetHitCount == 0) {
                    Context targetCtx = getContext().createPackageContext(TARGET_PACKAGE,
                            Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                    SharedPreferences targetSp = targetCtx.getSharedPreferences(TARGET_SP_NAME,
                            Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE);
                    int targetHitCount = targetSp.getInt(KEY_TARGET_HIT_COUNT, 0);
                    if (targetHitCount > 0) {
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putLong(KEY_TARGET_HIT_COUNT, (long) targetHitCount);
                        editor.putLong(KEY_LAST_TIME, targetSp.getLong(KEY_LAST_TIME, 0));
                        editor.putBoolean(KEY_AUTO_SELECT, targetSp.getBoolean(KEY_AUTO_SELECT, false));
                        editor.apply();

                    }
                }
            } catch (Throwable ignored) {}

            String[] columns = {"key", "value", "value_str"};
            cursor = new MatrixCursor(columns);

            // 支持 answer 路径：直接返回答案文本
            if ("answer".equals(path)) {
                String at = sp.getString(KEY_ANSWER_TEXT, "");
                if (at != null && !at.isEmpty()) {
                    cursor.newRow().add(KEY_ANSWER_TEXT).add(0L).add(at);
                }
                String mt = sp.getString(KEY_ANSWER_MARKED, "");
                if (mt != null && !mt.isEmpty()) {
                    cursor.newRow().add(KEY_ANSWER_MARKED).add(0L).add(mt);
                }
                return cursor;
            }

            for (String key : ALL_KEYS) {
                if (KEY_PKG_HOOKED.equals(key)) {
                    String v = sp.getString(key, "");
                    cursor.newRow().add(key).add((long) (v == null || v.isEmpty() ? 0 : v.split("\n").length)).add(v == null ? "" : v);
                } else if (KEY_MODULE_ACTIVE.equals(key) || KEY_AUTO_SELECT.equals(key) || KEY_AUTO_NEXT.equals(key)) {
                    long v = sp.getBoolean(key, false) ? 1L : 0L;
                    cursor.newRow().add(key).add(v).add(String.valueOf(v));
                } else {
                    long v = sp.getLong(key, 0);
                    cursor.newRow().add(key).add(v).add(String.valueOf(v));
                }
            }

            return cursor;
        } catch (Throwable t) {

            return emptyCursor();
        }
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/com.answer.revealer.stats";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            String path = uri.getLastPathSegment();
            SharedPreferences sp = getContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (sp == null) return null;

            if ("request".equals(path)) {
                // 写入一条请求记录
                String type = values.getAsString("type");
                String url = values.getAsString("url");
                if (type == null) type = "unknown";
                if (url == null) url = "";

                String key = "req_" + String.format("%05d", sp.getInt("_req_counter", 0) + 1)
                        + "_" + System.currentTimeMillis();
                SharedPreferences.Editor editor = sp.edit();
                editor.putString(key, type + "|" + url);
                editor.putInt("_req_counter", sp.getInt("_req_counter", 0) + 1);
                editor.putLong(KEY_LAST_TIME, System.currentTimeMillis());
                editor.apply();

                try { getContext().getContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
                return uri;
            }

            if ("log".equals(path)) {
                // 写入一条日志记录
                String type = values.getAsString("type");
                String method = values.getAsString("method");
                String detail = values.getAsString("detail");
                if (type == null) type = "unknown";
                if (method == null) method = "";
                if (detail == null) detail = "";

                int counter = sp.getInt(KEY_LOG_COUNTER, 0) + 1;
                String key = LOG_PREFIX + String.format("%05d", counter)
                        + "_" + System.currentTimeMillis();
                SharedPreferences.Editor editor = sp.edit();
                editor.putString(key, type + "|" + method + "|" + detail);
                editor.putInt(KEY_LOG_COUNTER, counter);
                editor.putLong(KEY_LAST_TIME, System.currentTimeMillis());

                // 限制日志数量，最多保留 200 条，超出删除最旧的
                int logCount = 0;
                List<String> oldLogKeys = new ArrayList<>();
                for (String k : sp.getAll().keySet()) {
                    if (k != null && k.startsWith(LOG_PREFIX)) {
                        logCount++;
                        oldLogKeys.add(k);
                    }
                }
                if (logCount > 200) {
                    Collections.sort(oldLogKeys);
                    int removeCount = logCount - 200;
                    for (int i = 0; i < removeCount && i < oldLogKeys.size(); i++) {
                        editor.remove(oldLogKeys.get(i));
                    }
                }

                editor.apply();
                try { getContext().getContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
                return uri;
            }

            // 通用插入
            writeValues(sp, values);
            try { getContext().getContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
            return uri;
        } catch (Throwable t) {

            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            String path = uri.getLastPathSegment();
            SharedPreferences sp = getContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (sp == null) return 0;

            if ("clear".equals(path)) {
                // 清空统计数据，但保留用户设置（如自动答题开关）
                boolean savedAutoSelect = sp.getBoolean(KEY_AUTO_SELECT, false);
                boolean savedAutoNext = sp.getBoolean(KEY_AUTO_NEXT, false);
                int count = sp.getAll().size();
                sp.edit().clear().apply();
                // 恢复用户设置
                if (savedAutoSelect) {
                    sp.edit().putBoolean(KEY_AUTO_SELECT, savedAutoSelect).apply();
                }
                if (savedAutoNext) {
                    sp.edit().putBoolean(KEY_AUTO_NEXT, savedAutoNext).apply();
                }
                try { getContext().getContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
                return count;
            }

            if ("log_clear".equals(path)) {
                // 只清空日志记录，保留其他数据
                int removed = 0;
                SharedPreferences.Editor editor = sp.edit();
                for (String key : sp.getAll().keySet()) {
                    if (key != null && key.startsWith(LOG_PREFIX)) {
                        editor.remove(key);
                        removed++;
                    }
                }
                editor.remove(KEY_LOG_COUNTER);
                editor.apply();
                try { getContext().getContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
                return removed;
            }

            // 删除指定 key 的请求（selection = "key=?"）
            if (selection != null && selection.startsWith("key=?")
                    && selectionArgs != null && selectionArgs.length > 0) {
                if (sp.contains(selectionArgs[0])) {
                    sp.edit().remove(selectionArgs[0]).apply();
                    try { getContext().getContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
                    return 1;
                }
            }
            return 0;
        } catch (Throwable t) {

            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        try {
            SharedPreferences sp = getContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (sp == null) return 0;
            int updated = writeValues(sp, values);
            if (updated > 0) {
                try { getContext().getContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
            }

            return updated;
        } catch (Throwable t) {

            return 0;
        }
    }

    private int writeValues(SharedPreferences sp, ContentValues values) {
        if (values == null || values.size() == 0) return 0;
        SharedPreferences.Editor editor = sp.edit();
        int updated = 0;
        for (Map.Entry<String, Object> e : values.valueSet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val == null) continue;
            if (val instanceof Long || val instanceof Integer) {
                editor.putLong(key, ((Number) val).longValue());
            } else if (val instanceof Boolean) {
                editor.putBoolean(key, (Boolean) val);
            } else if (val instanceof Float) {
                editor.putFloat(key, (Float) val);
            } else {
                editor.putString(key, val.toString());
            }
            updated++;
        }
        editor.apply();
        return updated;
    }

    private SharedPreferences ensureSP() {
        if (mSP != null) return mSP;
        try {
            mSP = getContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            return mSP;
        } catch (Throwable t) {

            return null;
        }
    }

    private static Cursor emptyCursor() {
        return new MatrixCursor(new String[]{"key", "value", "value_str"});
    }
}
