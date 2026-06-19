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

    private static final String SP_NAME = "module_stats";
    private static final String TAG = "StatsProvider";

    // 列名
    public static final String KEY_HOOK_INSTALLED = "hook_installed_count";
    public static final String KEY_REQUEST_COUNT = "request_count";
    public static final String KEY_TARGET_HIT_COUNT = "target_hit_count";
    public static final String KEY_LAST_TIME = "last_hook_time";
    public static final String KEY_MODULE_ACTIVE = "module_active_v1";
    public static final String KEY_DETECTED_CLIENTS = "detected_clients";
    public static final String KEY_PKG_HOOKED = "package_hooked";

    private static final String[] ALL_KEYS = {
            KEY_HOOK_INSTALLED,
            KEY_REQUEST_COUNT,
            KEY_TARGET_HIT_COUNT,
            KEY_LAST_TIME,
            KEY_MODULE_ACTIVE,
            KEY_DETECTED_CLIENTS,
            KEY_PKG_HOOKED
    };

    // 进程内缓存（ContentProvider 可能在调用者进程，也可能在自身进程）
    // 主要依赖 SP 持久化，cache 仅用于快速判断
    private static final ConcurrentHashMap<String, Long> sCachedCounts = new ConcurrentHashMap<>();

    private SharedPreferences mSP;

    @Override
    public boolean onCreate() {
        try {
            // 注意：这里用 Context.MODE_WORLD_READABLE 会被较新的系统拒绝
            // 但 ContentProvider 本身就允许跨进程，所以直接用默认的 MODE_PRIVATE 即可
            // 因为 ContentProvider.onCreate() 在提供者进程执行，读写的是自己包名的 SP
            mSP = getContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            Log.d(TAG, "StatsProvider created");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "onCreate error: " + t.getMessage());
            return false;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            SharedPreferences sp = ensureSP();
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

            // 默认：返回主要统计
            String[] columns = {"key", "value", "value_str"};
            cursor = new MatrixCursor(columns);

            for (String key : ALL_KEYS) {
                if (KEY_DETECTED_CLIENTS.equals(key) || KEY_PKG_HOOKED.equals(key)) {
                    String v = sp.getString(key, "");
                    cursor.newRow().add(key).add((long) (v == null || v.isEmpty() ? 0 : v.split("\n").length)).add(v == null ? "" : v);
                } else if (KEY_MODULE_ACTIVE.equals(key)) {
                    long v = sp.getBoolean(key, false) ? 1L : 0L;
                    cursor.newRow().add(key).add(v).add(String.valueOf(v));
                } else {
                    long v = sp.getLong(key, 0);
                    cursor.newRow().add(key).add(v).add(String.valueOf(v));
                }
            }
            return cursor;
        } catch (Throwable t) {
            Log.e(TAG, "query error: " + t.getMessage());
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
            SharedPreferences sp = ensureSP();
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
                // 更新请求总数
                editor.putLong(KEY_REQUEST_COUNT, sp.getLong(KEY_REQUEST_COUNT, 0) + 1);
                editor.putLong(KEY_LAST_TIME, System.currentTimeMillis());
                editor.apply();

                // 通知监听者
                getContext().getContentResolver().notifyChange(URI_QUERY, null);
                return uri;
            }

            // 通用插入
            writeValues(sp, values);
            getContext().getContentResolver().notifyChange(URI_QUERY, null);
            return uri;
        } catch (Throwable t) {
            Log.e(TAG, "insert error: " + t.getMessage());
            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            String path = uri.getLastPathSegment();
            SharedPreferences sp = ensureSP();
            if (sp == null) return 0;

            if ("clear".equals(path)) {
                // 清空全部
                int count = sp.getAll().size();
                sp.edit().clear().apply();
                getContext().getContentResolver().notifyChange(URI_QUERY, null);
                return count;
            }

            // 删除指定 key 的请求（selection = "key=?"）
            if (selection != null && selection.startsWith("key=?")
                    && selectionArgs != null && selectionArgs.length > 0) {
                if (sp.contains(selectionArgs[0])) {
                    sp.edit().remove(selectionArgs[0]).apply();
                    getContext().getContentResolver().notifyChange(URI_QUERY, null);
                    return 1;
                }
            }
            return 0;
        } catch (Throwable t) {
            Log.e(TAG, "delete error: " + t.getMessage());
            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        try {
            SharedPreferences sp = ensureSP();
            if (sp == null) return 0;
            int updated = writeValues(sp, values);
            getContext().getContentResolver().notifyChange(URI_QUERY, null);
            return updated;
        } catch (Throwable t) {
            Log.e(TAG, "update error: " + t.getMessage());
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
            Log.e(TAG, "ensureSP error: " + t.getMessage());
            return null;
        }
    }

    private static Cursor emptyCursor() {
        return new MatrixCursor(new String[]{"key", "value", "value_str"});
    }
}
