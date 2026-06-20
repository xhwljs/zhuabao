package com.answer.revealer;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口：Hook 目标应用 tz.ycsy.az 的答题接口
 * 数据写入 StatsProvider (content://com.answer.revealer.stats) 供模块 UI 读取
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String TARGET_PATH_KEYWORD = "getQuestion";

    // ContentProvider URI
    private static final Uri PROVIDER_URI = Uri.parse("content://com.answer.revealer.stats/update");
    private static final Uri PROVIDER_REQUEST_URI = Uri.parse("content://com.answer.revealer.stats/request");
    private static final Uri PROVIDER_QUERY_URI = Uri.parse("content://com.answer.revealer.stats/query");

    // 配置
    private static final String CONFIG_KEY_AUTO_SELECT = "auto_select_enabled";
    private static final String CONFIG_SP_NAME = "answer_revealer_status";
    private static final String ANSWER_MARKER = "正确答案";

    // HTTP 客户端类扫描列表
    private static final String[] HTTP_CLIENT_CLASS_NAMES = {
            "okhttp3.OkHttpClient",
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.Request",
            "okhttp3.Response",
            "retrofit2.Retrofit",
            "com.android.volley.toolbox.Volley",
            "com.android.volley.toolbox.StringRequest",
            "android.webkit.WebView",
            "android.webkit.WebViewClient",
            "io.flutter.embedding.engine.FlutterEngine",
            "com.facebook.react.ReactInstanceManager",
            "org.apache.cordova.CordovaWebView",
            "com.tencent.smtt.sdk.WebView",
            "com.tencent.smtt.sdk.WebViewClient"
    };

    private static final java.util.Set<String> initializedPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final AtomicInteger requestCounter = new AtomicInteger(0);
    private static final AtomicInteger targetHitCounter = new AtomicInteger(0);

    private static volatile Context appContext;

    // ============ ActivityThread Context 获取 ============
    private static Context getAppContextFromActivityThread() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            if (activityThread == null) return null;
            return (Context) XposedHelpers.callMethod(activityThread, "getApplication");
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;

        // 模块自己的包：hook isModuleActive() 方法
        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        MODULE_PACKAGE + ".MainActivity", lpparam.classLoader, "isModuleActive",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(true);
                            }
                        }
                );
            } catch (Throwable ignored) {}
            return;
        }

        // 非目标包跳过
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        // 每次进入目标应用都执行统计写入（不做去重）
        // initializedPackages 仅用于避免重复安装 hook 方法
        if (initializedPackages.add(TARGET_PACKAGE)) {
            // 首次初始化：安装 hook 方法
            final ClassLoader cl = lpparam.classLoader;

            // 获取 Context
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = getAppContextFromActivityThread();
                        if (ctx != null) appContext = ctx;
                        XposedHelpers.findAndHookMethod(
                                "android.app.Application", cl, "onCreate",
                                new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        try {
                                            Context c = (Context) param.thisObject;
                                            if (appContext == null) appContext = c;
                                        } catch (Throwable ignored) {}
                                    }
                                }
                        );
                    } catch (Throwable ignored) {}
                }
            }).start();

            // 安装网络 Hook
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        setupNetworkHooks(cl);
                        setupAutoSelectHooks(cl);
                        List<String> clients = scanHttpClients(cl);

                        // 写入 ContentProvider（不再写 hook 安装数量
                        writeStatsToProvider(PROVIDER_URI, clients);

                        // Toast 提示
                        showToastSafe("✓ 答案模块已加载");

                        try {
                            XposedBridge.log("[答案模块] Hook 安装完成");
                        } catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        try {
                            XposedBridge.log("[答案模块] 初始化错误: " + t.getMessage());
                        } catch (Throwable ignored) {}
                    }
                }
            }).start();
        } else {
            // 已初始化过：只做一次完整统计写入（确保数据刷新）
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                        if (ctx == null) return;
                        ContentValues values = new ContentValues();
                        values.put("module_active_v1", true);
                        values.put("last_hook_time", System.currentTimeMillis());
                        ctx.getContentResolver().update(PROVIDER_URI, values, null, null);
                    } catch (Throwable ignored) {}
                }
            }).start();
        }
    }

    // ============ 安装网络 Hook ============
    private void setupNetworkHooks(final ClassLoader cl) {

        // === 1. OkHttp ===
        if (hasClass(cl, "okhttp3.OkHttpClient") || hasClass(cl, "okhttp3.Request")) {
            // Hook RealCall.execute() / getResponseWithInterceptorChain
            for (String realCallName : new String[]{"okhttp3.RealCall", "okhttp3.internal.connection.RealCall"}) {
                try {
                    XposedHelpers.findAndHookMethod(realCallName, cl, "getResponseWithInterceptorChain",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object response = param.getResult();
                                        if (response != null) processOkHttpResponse(response, param);
                                    } catch (Throwable ignored) {}
                                }
                            }
                    );
                } catch (Throwable ignored) {}
            }

            try {
                XposedHelpers.findAndHookMethod("okhttp3.Call", cl, "execute",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    if (response != null) processOkHttpResponse(response, param);
                                } catch (Throwable ignored) {}
                            }
                        }
                );
            } catch (Throwable ignored) {}
        }

        // === 2. HttpURLConnection ===
        try {
            XposedHelpers.findAndHookMethod("java.net.HttpURLConnection", cl, "getInputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object url = XposedHelpers.callMethod(conn, "getURL");
                                String urlStr = url != null ? url.toString() : "";
                                writeRequestRecord("HTTP_URL_CONN", urlStr);
                                if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                Object bodyIn = param.getResult();
                                if (!(bodyIn instanceof java.io.InputStream)) return;

                                // 读取内容，修改后替换
                                byte[] bytes = readAll((java.io.InputStream) bodyIn);
                                if (bytes == null) return;
                                String body = new String(bytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);
                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();

                                if (!modified.equals(body)) {
                                    showToastSafe("✓ 已标记正确答案");
                                }
                                param.setResult(new ByteArrayInputStream(modified.getBytes(StandardCharsets.UTF_8)));
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] HttpURLConnection 处理异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // === 3. WebView: shouldInterceptRequest (目标是检测 getQuestion API 并替换响应) ===
        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", "android.webkit.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object req = param.args[1];
                                Object urlObj = XposedHelpers.callMethod(req, "getUrl");
                                if (urlObj == null) return;
                                String urlStr = urlObj.toString();

                                writeRequestRecord("WEBVIEW_INTERCEPT", urlStr);

                                if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();

                                // 转发 headers
                                Map<String, String> headers = new HashMap<>();
                                try {
                                    Object h = XposedHelpers.callMethod(req, "getRequestHeaders");
                                    if (h instanceof Map) {
                                        for (Map.Entry<?, ?> e : ((Map<?, ?>) h).entrySet()) {
                                            if (e.getKey() != null && e.getValue() != null) {
                                                headers.put(e.getKey().toString(), e.getValue().toString());
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}

                                // 发起请求读取响应
                                byte[] responseBytes = null;
                                int statusCode = 200;
                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    for (Map.Entry<String, String> e : headers.entrySet()) {
                                        try { conn.addRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
                                    }
                                    conn.connect();
                                    statusCode = conn.getResponseCode();
                                    java.io.InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    responseBytes = readAll(is);
                                    if (is != null) is.close();
                                } catch (Throwable t) {
                                    try { XposedBridge.log("[答案模块] WebView 请求失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String body = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);
                                if (!modified.equals(body)) {
                                    showToastSafe("✓ 已标记正确答案");
                                }

                                // 返回修改后的响应给 WebView
                                byte[] outBytes = modified.getBytes(StandardCharsets.UTF_8);
                                android.webkit.WebResourceResponse wresp =
                                        new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                                new ByteArrayInputStream(outBytes));
                                try {
                                    wresp.setStatusCodeAndReasonPhrase(statusCode, "OK");
                                    Map<String, String> respHeaders = new HashMap<>();
                                    respHeaders.put("Content-Type", "application/json; charset=UTF-8");
                                    try { wresp.setResponseHeaders(respHeaders); } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                                param.setResult(wresp);
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] WebView shouldInterceptRequest 异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
            try { XposedBridge.log("[答案模块] WebView shouldInterceptRequest hook 失败: " + ignored.getMessage()); } catch (Throwable ignored2) {}
        }

        // === 4. 旧版 WebView shouldInterceptRequest(WebView, String) ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String urlStr = (String) param.args[1];
                                if (urlStr == null || !urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();
                                writeRequestRecord("WEBVIEW_INTERCEPT_OLD", urlStr);

                                byte[] responseBytes = null;
                                int statusCode = 200;
                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    conn.connect();
                                    statusCode = conn.getResponseCode();
                                    java.io.InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    responseBytes = readAll(is);
                                    if (is != null) is.close();
                                } catch (Throwable t) {
                                    try { XposedBridge.log("[答案模块] WebView旧版 请求失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String body = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);
                                if (!modified.equals(body)) {
                                    showToastSafe("✓ 已标记正确答案");
                                }

                                byte[] outBytes = modified.getBytes(StandardCharsets.UTF_8);
                                android.webkit.WebResourceResponse wresp =
                                        new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                                new ByteArrayInputStream(outBytes));
                                try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                param.setResult(wresp);
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] WebView旧版 异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // === 5. 腾讯 X5 WebView ===
        try {
            XposedHelpers.findAndHookMethod("com.tencent.smtt.sdk.WebViewClient", cl, "shouldInterceptRequest",
                    "com.tencent.smtt.sdk.WebView", "com.tencent.smtt.export.external.interfaces.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object req = param.args[1];
                                Object urlObj = XposedHelpers.callMethod(req, "getUrl");
                                if (urlObj == null) return;
                                String urlStr = urlObj.toString();
                                writeRequestRecord("X5WEBVIEW_INTERCEPT", urlStr);
                                if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

                                targetHitCounter.incrementAndGet();
                                updateHitCountInProvider();

                                byte[] responseBytes = null;
                                int statusCode = 200;
                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    conn.connect();
                                    statusCode = conn.getResponseCode();
                                    java.io.InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    responseBytes = readAll(is);
                                    if (is != null) is.close();
                                } catch (Throwable t) {
                                    try { XposedBridge.log("[答案模块] X5 请求失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String body = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(body);
                                if (!modified.equals(body)) showToastSafe("✓ 已标记正确答案");

                                byte[] outBytes = modified.getBytes(StandardCharsets.UTF_8);
                                try {
                                    // 优先用 X5 的 WebResourceResponse
                                    Class<?> x5respCls = XposedHelpers.findClassIfExists(
                                            "com.tencent.smtt.export.external.interfaces.WebResourceResponse", cl);
                                    if (x5respCls != null) {
                                        Object x5resp = x5respCls.getConstructor(String.class, String.class, java.io.InputStream.class)
                                                .newInstance("application/json", "UTF-8", new ByteArrayInputStream(outBytes));
                                        param.setResult(x5resp);
                                        return;
                                    }
                                } catch (Throwable ignored) {}
                                // fallback: 系统类
                                android.webkit.WebResourceResponse wresp =
                                        new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                                new ByteArrayInputStream(outBytes));
                                try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                param.setResult(wresp);
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] X5 异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }

    // ============ OkHttp 响应处理 ============
    private void processOkHttpResponse(Object response, XC_MethodHook.MethodHookParam param) {
        try {
            String urlStr = extractOkHttpUrl(response);
            if (urlStr == null) return;
            writeRequestRecord("OKHTTP_RESP", urlStr);
            if (!urlStr.contains(TARGET_PATH_KEYWORD)) return;

            targetHitCounter.incrementAndGet();
            updateHitCountInProvider();

            byte[] bodyBytes = extractOkHttpBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) return;

            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBodyWithStyle(body);
            if (!modified.equals(body)) {
                String contentType = extractOkHttpContentType(response);
                Object newResp = buildOkHttpResponse(response, modified, contentType);
                if (newResp != null) {
                    param.setResult(newResp);
                    showToastSafe("✓ 已标记正确答案");
                }
            }
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] OkHttp处理异常: " + t.getMessage()); } catch (Throwable ignored2) {}
        }
    }

    private String extractOkHttpUrl(Object response) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) return null;
            Object url = XposedHelpers.callMethod(request, "url");
            return url != null ? url.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] extractOkHttpBodyBytes(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return null;
            try {
                Object bytes = XposedHelpers.callMethod(body, "bytes");
                if (bytes instanceof byte[]) return (byte[]) bytes;
            } catch (Throwable ignored) {}
            try {
                Object str = XposedHelpers.callMethod(body, "string");
                if (str instanceof String) return ((String) str).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractOkHttpContentType(Object response) {
        try {
            Object body = XposedHelpers.callMethod(response, "body");
            if (body == null) return null;
            Object ct = XposedHelpers.callMethod(body, "contentType");
            return ct != null ? ct.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object buildOkHttpResponse(Object originalResponse, String newBodyStr, String contentType) {
        try {
            // 获取原始 response 的 classloader
            ClassLoader cl = originalResponse.getClass().getClassLoader();
            if (cl == null) cl = Thread.currentThread().getContextClassLoader();

            Class<?> responseBodyCls = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", cl);
            if (responseBodyCls == null) return null;

            byte[] bodyBytes = newBodyStr.getBytes(StandardCharsets.UTF_8);
            Object mediaType = null;
            try {
                if (contentType != null && !contentType.isEmpty()) {
                    Class<?> mtCls = XposedHelpers.findClassIfExists("okhttp3.MediaType", cl);
                    if (mtCls != null) {
                        mediaType = XposedHelpers.callStaticMethod(mtCls, "parse", contentType);
                    }
                }
            } catch (Throwable ignored) {}

            Object newBody = null;
            try {
                newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, bodyBytes);
            } catch (Throwable ignored) {}
            if (newBody == null) {
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, newBodyStr);
                } catch (Throwable ignored) {}
            }
            if (newBody == null) return null;

            Object newBuilder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            newBuilder = XposedHelpers.callMethod(newBuilder, "body", newBody);
            return XposedHelpers.callMethod(newBuilder, "build");
        } catch (Throwable t) {
            return null;
        }
    }

    // ============ 修改 JSON：在正确选项前加红色标记 ============
    private String modifyAnswerBodyWithStyle(String bodyStr) {
        try {
            JSONObject root = new JSONObject(bodyStr);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return bodyStr;
            JSONArray options = data.optJSONArray("answerOptionList");
            if (options == null || options.length() == 0) return bodyStr;
            boolean changed = false;
            for (int i = 0; i < options.length(); i++) {
                JSONObject opt = options.optJSONObject(i);
                if (opt == null) continue;
                if (opt.optInt("isRight") == 1) {
                    String text = opt.optString("optionText", "");
                    opt.put("optionText", "【 " + text + " 正确答案 】");
                    changed = true;
                }
            }
            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
            return bodyStr;
        }
    }

    // ============ 工具类 ============
    private static byte[] readAll(java.io.InputStream is) {
        if (is == null) return null;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasClass(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<String> scanHttpClients(ClassLoader cl) {
        List<String> result = new ArrayList<>();
        try {
            for (String className : HTTP_CLIENT_CLASS_NAMES) {
                try {
                    Class.forName(className, false, cl);
                    result.add(className);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return result;
    }

    // ============ 自动选中答案：读取开关状态 ============
    private static boolean readAutoSelectEnabled() {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return false;

            // 1. 先尝试 ContentProvider 查询
            try {
                Cursor cursor = ctx.getContentResolver().query(PROVIDER_QUERY_URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        try {
                            String key = cursor.getString(cursor.getColumnIndex("key"));
                            if (CONFIG_KEY_AUTO_SELECT.equals(key)) {
                                long val = cursor.getLong(cursor.getColumnIndex("value"));
                                String valueStr = cursor.getString(cursor.getColumnIndex("value_str"));
                                cursor.close();
                                return val > 0 || "1".equals(valueStr) || "true".equalsIgnoreCase(valueStr);
                            }
                        } catch (Throwable ignored) {}
                    } while (cursor.moveToNext());
                    cursor.close();
                }
            } catch (Throwable ignored) {}

            // 2. Fallback：从目标应用 SP 读取
            try {
                android.content.SharedPreferences sp = ctx.getSharedPreferences(
                        CONFIG_SP_NAME, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                return sp.getBoolean(CONFIG_KEY_AUTO_SELECT, false);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    // ============ 安装自动选中 Hook（WebView + 原生UI） ============
    private void setupAutoSelectHooks(final ClassLoader cl) {
        // === 1. WebView: onPageFinished → 注入 JS 点击正确答案 ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageFinished",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                final Object webView = param.args[0];
                                if (webView == null) return;

                                // 注入 JS：遍历所有元素的文本，找到包含"正确答案"的元素，向上找可点击父级并点击
                                final String js = "(function(){var m='正确答案';var a=document.querySelectorAll('*');for(var i=0;i<a.length;i++){var t=a[i].innerText||a[i].textContent||'';if(t.indexOf(m)>=0){var c=a[i];for(var j=0;j<6&&c;j++){if(typeof c.click==='function'){try{c.click();return;}catch(e){}}c=c.parentElement;}}}})();";

                                // loadUrl 兼容所有 WebView 版本
                                try {
                                    XposedHelpers.callMethod(webView, "loadUrl", "javascript:" + js);
                                } catch (Throwable ignored) {
                                    // evaluateJavascript (API >= 19) 作为 fallback
                                    try {
                                        XposedHelpers.callMethod(webView, "evaluateJavascript", js, null);
                                    } catch (Throwable ignored2) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try {
                XposedBridge.log("[答案模块] WebView onPageFinished hook 已安装");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] WebView onPageFinished hook失败: " + t.getMessage()); } catch (Throwable ignored) {}
        }

        // === 2. Activity onResume → 扫描原生 UI 找正确答案并点击 ===
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                final Object activityObj = param.thisObject;
                                if (activityObj == null) return;

                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Object window = XposedHelpers.callMethod(activityObj, "getWindow");
                                            if (window == null) return;
                                            Object decorView = XposedHelpers.callMethod(window, "getDecorView");
                                            if (decorView instanceof View) {
                                                clickViewWithMarker((View) decorView);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }, 500);
                            } catch (Throwable ignored) {}
                        }
                    });
            try {
                XposedBridge.log("[答案模块] Activity onResume hook 已安装");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] Activity onResume hook失败: " + t.getMessage()); } catch (Throwable ignored) {}
        }

        // === 3. TextView setText → 检测动态内容包含"正确答案"时点击父容器 ===
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setText",
                    CharSequence.class, android.widget.TextView.BufferType.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                CharSequence text = (CharSequence) param.args[0];
                                if (text == null || !text.toString().contains(ANSWER_MARKER)) return;

                                final Object tvObj = param.thisObject;
                                if (!(tvObj instanceof View)) return;

                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            View cur = (View) tvObj;
                                            for (int i = 0; i < 6; i++) {
                                                if (cur == null) break;
                                                if (cur.isClickable() && cur.isEnabled()) {
                                                    cur.performClick();
                                                    return;
                                                }
                                                Object parent = cur.getParent();
                                                if (parent instanceof View) {
                                                    cur = (View) parent;
                                                } else {
                                                    break;
                                                }
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }, 300);
                            } catch (Throwable ignored) {}
                        }
                    });
            try {
                XposedBridge.log("[答案模块] TextView setText hook 已安装");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] TextView setText hook失败: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    // ============ 递归扫描视图树，点击含"正确答案"标记的元素 ============
    private static void clickViewWithMarker(View view) {
        if (view == null) return;
        try {
            // 检查当前 view 是否 TextView，文本是否包含标记
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                CharSequence text = tv.getText();
                if (text != null && text.toString().contains(ANSWER_MARKER)) {
                    // 向上找可点击的父元素
                    View cur = tv;
                    for (int i = 0; i < 6; i++) {
                        if (cur == null) break;
                        if (cur.isClickable() && cur.isEnabled()) {
                            cur.performClick();
                            return;
                        }
                        Object parent = cur.getParent();
                        if (parent instanceof View) {
                            cur = (View) parent;
                        } else {
                            break;
                        }
                    }
                }
            }
            // 递归遍历子元素
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    clickViewWithMarker(child);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ============ 写入 ContentProvider ============
    private static void writeStatsToProvider(Uri uri, List<String> clients) {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return;

            ContentValues values = new ContentValues();
            values.put("module_active_v1", true);
            values.put("last_hook_time", System.currentTimeMillis());
            values.put("package_hooked", TARGET_PACKAGE);
            values.put("target_hit_count", targetHitCounter.get());
            values.put("request_count", requestCounter.get());

            if (clients != null && !clients.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String s : clients) sb.append(s).append("\n");
                values.put("detected_clients", sb.toString());
            }

            // 跨进程 ContentProvider 写入偶尔会失败，最多重试 3 次
            boolean success = false;
            for (int attempt = 0; attempt < 3 && !success; attempt++) {
                try {
                    ctx.getContentResolver().update(uri, values, null, null);
                    success = true;
                } catch (Throwable t) {
                    try { Thread.sleep(200); } catch (Throwable ignored2) {}
                    if (attempt == 2) {
                        throw t;
                    }
                }
            }
        } catch (Throwable t) {
            // 若 ContentProvider 失败，写入目标应用自己的 SP 作为备用
            try {
                Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                if (ctx != null) {
                    android.content.SharedPreferences sp = ctx.getSharedPreferences("answer_revealer_status",
                            Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                    android.content.SharedPreferences.Editor editor = sp.edit();
                    editor.putBoolean("module_active_v1", true);
                    editor.putLong("last_hook_time", System.currentTimeMillis());
                    editor.putInt("target_hit_count", targetHitCounter.get());
                    editor.putInt("request_count", requestCounter.get());
                    if (clients != null && !clients.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (String s : clients) sb.append(s).append("\n");
                        editor.putString("detected_clients", sb.toString());
                    }
                    editor.apply();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void updateHitCountInProvider() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                    if (ctx == null) return;
                    ContentValues values = new ContentValues();
                    values.put("module_active_v1", true);
                    values.put("target_hit_count", targetHitCounter.get());
                    values.put("request_count", requestCounter.get());
                    values.put("last_hook_time", System.currentTimeMillis());

                    // 重试 2 次提高成功率
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            ctx.getContentResolver().update(PROVIDER_URI, values, null, null);
                            return;
                        } catch (Throwable ignored) {
                            try { Thread.sleep(150); } catch (Throwable ignored2) {}
                        }
                    }

                    // ContentProvider 失败 → fallback 写入目标应用自己的 SP
                    try {
                        android.content.SharedPreferences sp = ctx.getSharedPreferences(
                                "answer_revealer_status",
                                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                        sp.edit().putBoolean("module_active_v1", true)
                                .putInt("target_hit_count", targetHitCounter.get())
                                .putInt("request_count", requestCounter.get())
                                .putLong("last_hook_time", System.currentTimeMillis())
                                .apply();
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void writeRequestRecord(String type, String urlStr) {
        requestCounter.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                    if (ctx == null) return;
                    ContentValues values = new ContentValues();
                    values.put("type", type);
                    values.put("url", urlStr);
                    ctx.getContentResolver().insert(PROVIDER_REQUEST_URI, values);
                } catch (Throwable t) {
                    // fallback: 目标应用自己的 SP
                    try {
                        Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                        if (ctx != null) {
                            android.content.SharedPreferences sp = ctx.getSharedPreferences("answer_revealer_status",
                                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                            String key = "req_" + String.format("%05d", requestCounter.get()) + "_" + System.currentTimeMillis();
                            sp.edit().putString(key, type + "|" + urlStr).apply();
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }).start();
    }

    // ============ Toast ============
    private void showToastSafe(final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
                    if (ctx != null) {
                        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    // ============ 暴露给外部读取（调试用） ============
    public static int getTargetHitCount() { return targetHitCounter.get(); }
    public static int getRequestCount() { return requestCounter.get(); }
}
