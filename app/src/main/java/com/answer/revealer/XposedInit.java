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

    // 存储当前题目的正确答案文本（用于 JS 直接选中）
    private static volatile String sCorrectAnswerText = null;
    // 存储当前正确答案标记文本（【 xxx 正确答案 】）
    private static volatile String sMarkedAnswerText = null;
    // 记录上次更新答案的时间戳（防止使用过期答案）
    private static volatile long sCorrectAnswerTimestamp = 0;
    // 已自动选中标志（防止重复点击其他选项）
    private static final java.util.concurrent.atomic.AtomicBoolean sAlreadyAutoSelected = new java.util.concurrent.atomic.AtomicBoolean(false);

    // ContentProvider URI - 答案文本
    private static final Uri PROVIDER_ANSWER_URI = Uri.parse("content://com.answer.revealer.stats/answer");
    private static final String KEY_ANSWER_TEXT = "answer_text";
    private static final String KEY_ANSWER_MARKED = "answer_marked_text";
    private static final String KEY_ANSWER_TIME = "answer_time";

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

                                // === 只做 JS 注入 —— 3 次延迟，完全移除 Java 层触摸 ===
                                final Object webViewObj = param.args[0];
                                if (webViewObj != null) {
                                    try {
                                        XposedBridge.log("[答案模块] WebView 拦截 getQuestion，answerText="
                                                + (sCorrectAnswerText != null ? sCorrectAnswerText.substring(0, Math.min(30, sCorrectAnswerText.length())) : "null"));
                                    } catch (Throwable ignored) {}

                                    // 2500ms / 3500ms / 4500ms 三次 JS 注入
                                    long[] delays = {2500, 3500, 4500};
                                    for (long delay : delays) {
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                injectJsIntoWebView(webViewObj, "[shouldInterceptRequest-" + delay + "ms]");
                                            }
                                        }, delay);
                                    }
                                }
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
            // 收集所有正确选项文本——多选题时多个，拼接后存到 sCorrectAnswerText
            java.util.List<String> correctAnswers = new java.util.ArrayList<>();
            for (int i = 0; i < options.length(); i++) {
                JSONObject opt = options.optJSONObject(i);
                if (opt == null) continue;
                // 兼容 isRight 为 Int(1) 或 Boolean(true)，以及 isCorrect/correct 等不同字段名
                boolean isCorrect = false;
                try {
                    if (opt.optInt("isRight") == 1) isCorrect = true;
                    else if (opt.optBoolean("isRight")) isCorrect = true;
                    else if (opt.optBoolean("isCorrect")) isCorrect = true;
                    else if (opt.optBoolean("correct")) isCorrect = true;
                    else if (opt.optInt("isCorrect") == 1) isCorrect = true;
                    else if (opt.optInt("correct") == 1) isCorrect = true;
                    else {
                        // 兜底：检查 JSON 中 isRight 字段的真实类型
                        if (opt.has("isRight")) {
                            Object v = opt.get("isRight");
                            if (Boolean.TRUE.equals(v)) isCorrect = true;
                        }
                    }
                } catch (Throwable ignored) {}
                if (isCorrect) {
                    String text = opt.optString("optionText", "");
                    if (text != null && !text.isEmpty()) {
                        correctAnswers.add(text);
                    }
                    String marked = "【 " + text + " 正确答案 】";
                    opt.put("optionText", marked);
                    changed = true;
                }
            }
            // 将所有正确答案拼接后存储（JS 层可根据是否含"、"判断为多选题）
            if (changed && !correctAnswers.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                StringBuilder markedSb = new StringBuilder();
                for (int i = 0; i < correctAnswers.size(); i++) {
                    if (i > 0) { sb.append("、"); }
                    sb.append(correctAnswers.get(i));
                }
                for (int i = 0; i < correctAnswers.size(); i++) {
                    if (i > 0) { markedSb.append("、"); }
                    markedSb.append("【 ").append(correctAnswers.get(i)).append(" 正确答案 】");
                }
                sCorrectAnswerText = sb.toString();
                sMarkedAnswerText = markedSb.toString();
                sCorrectAnswerTimestamp = System.currentTimeMillis();
                sAlreadyAutoSelected.set(false);
                writeAnswerToProvider(sCorrectAnswerText, sMarkedAnswerText);
            }
            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
            return bodyStr;
        }
    }

    // ============ 写入答案文本到 ContentProvider ============
    private static void writeAnswerToProvider(String answerText, String markedText) {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return;
            ContentValues values = new ContentValues();
            values.put(KEY_ANSWER_TEXT, answerText != null ? answerText : "");
            values.put(KEY_ANSWER_MARKED, markedText != null ? markedText : "");
            values.put(KEY_ANSWER_TIME, System.currentTimeMillis());
            ctx.getContentResolver().update(PROVIDER_ANSWER_URI, values, null, null);
        } catch (Throwable ignored) {}
    }

    // ============ 从 ContentProvider 读取答案文本（备用）============
    private static String readAnswerFromProvider() {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return null;
            Cursor cursor = ctx.getContentResolver().query(PROVIDER_ANSWER_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                try {
                    int idx = cursor.getColumnIndex("value_str");
                    if (idx >= 0) {
                        String v = cursor.getString(idx);
                        if (v != null && !v.isEmpty()) {
                            cursor.close();
                            return v;
                        }
                    }
                } catch (Throwable ignored) {}
                cursor.close();
            }
        } catch (Throwable ignored) {}
        return null;
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

    // ============ 安装自动选中 Hook ============
    private void setupAutoSelectHooks(final ClassLoader cl) {
        // === 1. WebChromeClient.onConsoleMessage（新版API）===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebChromeClient", cl, "onConsoleMessage",
                    "android.webkit.ConsoleMessage",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object msg = param.args[0];
                                if (msg == null) return;
                                String text = (String) XposedHelpers.callMethod(msg, "message");
                                if (text != null && text.contains("[ANSWER]")) {
                                    try { XposedBridge.log("[答案模块] " + text); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 1b. onConsoleMessage(String, int, String)（旧版API）===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebChromeClient", cl, "onConsoleMessage",
                    String.class, int.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String text = (String) param.args[0];
                                if (text != null && text.contains("[ANSWER]")) {
                                    try { XposedBridge.log("[答案模块] " + text); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 1c. 腾讯X5内核 onConsoleMessage ===
        try {
            XposedHelpers.findAndHookMethod("com.tencent.smtt.sdk.WebChromeClient", cl, "onConsoleMessage",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                for (Object arg : param.args) {
                                    if (arg == null) continue;
                                    String t = arg.toString();
                                    if (t != null && t.contains("[ANSWER]")) {
                                        try { XposedBridge.log("[答案模块] " + t); } catch (Throwable ignored) {}
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 2. WebViewClient.onPageStarted → 页面加载后 2.5s/3.5s/4.5s 注入 JS ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageStarted",
                    "android.webkit.WebView", String.class, "android.graphics.Bitmap",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                long[] delays = {2500, 3500, 4500};
                                for (long d : delays) {
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() {
                                            injectJsIntoWebView(webView, "[onPageStarted-" + ((int)d) + "ms]");
                                        }
                                    }, d);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 3. WebViewClient.onPageFinished → 页面加载完成后 1.5s/2.5s/3.5s 注入 JS ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageFinished",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                long[] delays = {1500, 2500, 3500};
                                for (long d : delays) {
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() {
                                            injectJsIntoWebView(webView, "[onPageFinished-" + ((int)d) + "ms]");
                                        }
                                    }, d);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 4. WebChromeClient.onProgressChanged → 进度变化时（100%时）注入 JS ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebChromeClient", cl, "onProgressChanged",
                    "android.webkit.WebView", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                int progress = (Integer) param.args[1];
                                if (progress < 80) return; // 只在页面快加载完时才注入
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() {
                                        injectJsIntoWebView(webView, "[onProgressChanged-100-" + progress + "%]");
                                    }
                                }, 2000);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 5. Activity onResume → 扫描原生 UI 找正确答案标记并点击（备选） ===
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                if (sAlreadyAutoSelected.get()) return;
                                final Object activityObj = param.thisObject;
                                if (activityObj == null) return;

                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            Object window = XposedHelpers.callMethod(activityObj, "getWindow");
                                            if (window == null) return;
                                            Object decorView = XposedHelpers.callMethod(window, "getDecorView");
                                            if (decorView instanceof View) {
                                                clickViewWithMarkerEnhanced((View) decorView, "Activity.onResume");
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }, 1500);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 6. TextView setText → 检测动态内容含"正确答案"时点击父容器（备选） ===
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setText",
                    CharSequence.class, android.widget.TextView.BufferType.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                if (sAlreadyAutoSelected.get()) return;
                                CharSequence text = (CharSequence) param.args[0];
                                if (text == null || !text.toString().contains(ANSWER_MARKER)) return;

                                final Object tvObj = param.thisObject;
                                if (!(tvObj instanceof View)) return;

                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            View cur = (View) tvObj;
                                            for (int j = 0; j < 6; j++) {
                                                if (cur == null) break;
                                                if (cur.isClickable() && cur.isEnabled()) {
                                                    if (performEnhancedClick(cur, "TextView.setText")) return;
                                                }
                                                Object parent = cur.getParent();
                                                if (parent instanceof View) cur = (View) parent; else break;
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }, 800);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}
    }

    // ============ 统一的 JS 注入入口（核心：多渠道返回执行结果到LSPosed日志） ============
    private static void injectJsIntoWebView(Object webViewObj, final String sourceTag) {
        try {
            if (!readAutoSelectEnabled()) return;
            if (webViewObj == null) return;

            // 如果已经成功选中过，就不再重复（防止在选项间乱跳）
            if (sAlreadyAutoSelected.get()) return;

            String answerText = sCorrectAnswerText;
            if (answerText == null || answerText.isEmpty()) return;

            long age = System.currentTimeMillis() - sCorrectAnswerTimestamp;
            if (sCorrectAnswerTimestamp > 0 && age > 30000) return;

            // 构建 JS 参数
            String markerText = sMarkedAnswerText != null ? sMarkedAnswerText : ("【 " + answerText + " 正确答案 】");
            String safeM = escapeJsString(markerText);
            String safeA = escapeJsString(answerText);
            String safeTag = escapeJsString(sourceTag);

            final String js = buildAutoClickJS2(safeA, safeM, safeTag);
            final String shortAnswer = answerText.substring(0, Math.min(20, answerText.length()));

            // === 日志：注入前 ===
            try {
                XposedBridge.log("[答案模块] " + sourceTag + " -> 注入JS(答案=" + shortAnswer + " 长度=" + js.length() + ")");
            } catch (Throwable ignored) {}

            // === 构造 ValueCallback 来捕获 evaluateJavascript 的返回值（不过滤，所有内容都记）===
            Object callback = null;
            try {
                callback = java.lang.reflect.Proxy.newProxyInstance(
                        android.webkit.ValueCallback.class.getClassLoader(),
                        new Class<?>[]{android.webkit.ValueCallback.class},
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                                try {
                                    // 记录所有返回值，不过滤
                                    Object val = args != null && args.length > 0 ? args[0] : null;
                                    String valStr = val == null ? "null" : val.toString();
                                    try {
                                        XposedBridge.log("[答案模块] " + sourceTag + " -> JS返回值=" + valStr);
                                    } catch (Throwable ignored2) {}
                                    // 如果 JS 返回值中显示成功选中，立即标记
                                    if (valStr.contains("SUCCESS") || valStr.contains("SEL=1")) {
                                        sAlreadyAutoSelected.set(true);
                                        try { XposedBridge.log("[答案模块] " + sourceTag + " -> ★ 已自动选中答案！"); } catch (Throwable ignored2) {}
                                    }
                                } catch (Throwable ignored) {}
                                return null;
                            }
                        });
            } catch (Throwable t) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " -> ValueCallback创建失败: " + t.getMessage()); } catch (Throwable ignored) {}
            }

            // === 方式1：evaluateJavascript + ValueCallback ===
            boolean injected = false;
            try {
                XposedHelpers.callMethod(webViewObj, "evaluateJavascript", js, callback);
                injected = true;
                try {
                    XposedBridge.log("[答案模块] " + sourceTag + " -> evaluateJavascript已调用(ValueCallback=" + (callback != null) + ")");
                } catch (Throwable ignored) {}
            } catch (Throwable te) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " -> evaluateJavascript异常: " + te.getMessage()); } catch (Throwable ignored) {}
            }

            // === 方式2：loadUrl("javascript:") 兜底（没有返回值机制，依赖title变化）===
            if (!injected) {
                try {
                    XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:" + js);
                    injected = true;
                    try { XposedBridge.log("[答案模块] " + sourceTag + " -> loadUrl已调用"); } catch (Throwable ignored) {}
                } catch (Throwable te) {
                    try { XposedBridge.log("[答案模块] " + sourceTag + " -> loadUrl异常: " + te.getMessage()); } catch (Throwable ignored) {}
                }
            }

            // === 记录：JS 已被注入（但执行结果要看 ValueCallback 或 title 变化）===
            if (injected) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " -> JS已注入，等待执行结果"); } catch (Throwable ignored) {}
            } else {
                try { XposedBridge.log("[答案模块] " + sourceTag + " -> 所有JS注入方式均失败"); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] " + sourceTag + " -> 注入顶级异常: " + t.getMessage()); } catch (Throwable ignored2) {}
        }
    }

    // ============ JS 字符串转义（处理所有可能破坏 JS 字符串的字符） ============
    private static String escapeJsString(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '<': sb.append("\\u003C"); break;
                case '>': sb.append("\\u003E"); break;
                case '/': sb.append("\\u002F"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ============ 构建自动选中 JS（v8 — try块内返回明确的 SUCCESS/FAIL 字符串）============
    private static String buildAutoClickJS2(String safeA, String safeM, String safeTag) {
        StringBuilder sb = new StringBuilder();
        // 整体 IIFE + try-catch，确保任何情况都有返回值
        sb.append("(function(){try{");
        sb.append("var TAG='").append(safeTag).append("';");
        sb.append("var AT='").append(safeA).append("';");
        sb.append("var D=document;var SEL=0;var FOUND='';");
        // IS_MULTI: AT 含分隔符或页面含"多选/不定项"
        sb.append("var IS_MULTI=(AT.indexOf('、')>=0||AT.indexOf(',')>=0||AT.indexOf(';')>=0);");
        sb.append("if(!IS_MULTI){try{var pgTxt=D.body.innerText;if(pgTxt){IS_MULTI=(pgTxt.indexOf('多选')>=0||pgTxt.indexOf('不定项')>=0);}}catch(e){}}");
        sb.append("function l(m){try{console.log('[ANSWER]'+TAG+' '+m);}catch(e){}try{document.title=TAG+':'+String(m).substring(0,40);}catch(e){}}");
        // dc(el)：对 INPUT 直接 checked=true+dispatch change（不再 click，避免 toggle 回 false）
        sb.append("function dc(el){if(!el||!el.tagName||el.tagName.toUpperCase()==='HTML'||el.tagName.toUpperCase()==='BODY')return;if(SEL_SET.indexOf(el)>=0)return;SEL_SET.push(el);");
        sb.append("var t=el.tagName.toUpperCase();l('DC:tag='+t+' txt='+(el.innerText||el.value||'').toString().substring(0,20));");
        // INPUT: 直接 checked=true + dispatch change，不 click（checkbox click 会 toggle 回 false）
        sb.append("if(t==='INPUT'){try{el.checked=true;el.setAttribute('checked','checked');}catch(e){}try{var ce=document.createEvent('Event');ce.initEvent('change',true,true);el.dispatchEvent(ce);}catch(e){}SEL++;return;}");
        // LABEL: 找关联 input，直接设 checked=true
        sb.append("if(t==='LABEL'){var inp=null;var lf=el.getAttribute?el.getAttribute('for'):null;if(lf){inp=D.getElementById(lf);}if(!inp&&el.querySelector){inp=el.querySelector('input[type=checkbox],input[type=radio]');}if(inp){try{inp.checked=true;inp.setAttribute('checked','checked');}catch(e){}try{var ce2=document.createEvent('Event');ce2.initEvent('change',true,true);inp.dispatchEvent(ce2);}catch(e){}SEL++;return;}else{try{el.click();}catch(e){}SEL++;}return;}");
        // BUTTON/A/其他: click + dispatchEvent
        sb.append("try{if(el.click)el.click();}catch(e){}");
        sb.append("try{var evs=['click','mousedown','mouseup','change','input'];for(var vi=0;vi<evs.length;vi++){try{var evt;if(evs[vi]==='click'||evs[vi].indexOf('mouse')>=0){evt=new MouseEvent(evs[vi],{bubbles:true,cancelable:true,view:window,button:0});}else{evt=document.createEvent('Event');evt.initEvent(evs[vi],true,true);}el.dispatchEvent(evt);}catch(e){}}}catch(e){}");
        sb.append("SEL++;}");
        // SEL_SET: 已点击元素集合（防止 dc() 重复点击同一元素）
        sb.append("var SEL_SET=[];");
        // fc(el)：精确找到含答案文本的元素并点击。多选题用 AT 逐个关键词搜索；单选题用 TreeWalker 已有的 mts[]
        sb.append("function fc(el){l('FC:el='+el.tagName+' txt='+(el.innerText||'').toString().substring(0,20));");
        // 多选题(IS_MULTI=true)：遍历页面所有相关元素，精确匹配答案文本找可点击 input
        sb.append("if(IS_MULTI){var kwArr=AT.split(/、|\\,|;/);for(var ki=0;ki<kwArr.length;ki++){var kw=String(kwArr[ki]||'').trim();if(!kw||kw.length<1)continue;l('FC:MULTI找 kw='+kw.substring(0,15));");
        sb.append("var all=D.body.querySelectorAll('label,li,div,span,p,input');for(var ai=0;ai<all.length;ai++){var e=all[ai];var etxt='';try{etxt=(e.innerText||e.textContent||'').toString();}catch(err){}if(etxt.indexOf(kw)>=0){");
        sb.append("var t2=e.tagName.toUpperCase();if(t2==='INPUT'&&(e.type==='checkbox'||e.type==='radio')){l('FC:MULTI→直接INPUT '+e.type);dc(e);}else if(t2==='LABEL'){l('FC:MULTI→LABEL');dc(e);}else{var n=e.nextElementSibling;if(n){var ntxt='';try{ntxt=(n.innerText||n.textContent||'').toString();}catch(err){}if(ntxt.indexOf(kw)>=0&&n.tagName&&n.tagName.toUpperCase()==='INPUT'){l('FC:MULTI→兄弟INPUT');dc(n);}else if(n.querySelector){var nin=n.querySelector('input[type=checkbox],input[type=radio]');if(nin){l('FC:MULTI→兄弟内INPUT');dc(nin);}}}}}}}");
        sb.append("}else{");
        // 单选题(IS_MULTI=false)：用 closest() 找可点击祖先
        sb.append("var lb=el.closest?el.closest('label'):null;if(lb){l('FC:LABEL→');dc(lb);return;}var inp=el.closest?el.closest('input,button,a,select,textarea'):null;if(inp){l('FC:INPUT→'+inp.tagName);dc(inp);return;}var cur=el.parentElement;while(cur&&cur.tagName&&cur.tagName!=='BODY'&&cur.tagName!=='HTML'){if(cur.getAttribute&&cur.getAttribute('onclick')){l('FC:onclick→'+cur.tagName);dc(cur);return;}cur=cur.parentElement;}}");
        sb.append("}");
        // 策略1：TreeWalker 收集含"正确答案"标记的节点，逐个 fc()
        sb.append("l('START AT='+AT+' MULTI='+IS_MULTI);");
        sb.append("if(D.body){var mts=[];var tw=D.createTreeWalker(D.body,NodeFilter.SHOW_TEXT,null,false);var node;while(node=tw.nextNode()){if(node.nodeValue&&node.nodeValue.indexOf('正确答案')>=0&&node.parentElement){mts.push(node.parentElement);}}l('策略1:匹配'+mts.length+'个');for(var mi=0;mi<mts.length;mi++){try{fc(mts[mi]);}catch(e){}}}");
        // 策略2：querySelectorAll 扫描含"正确答案"的元素
        sb.append("if(SEL===0){var a2=D.body.querySelectorAll('label,li,div,span,p,input');l('策略2:scan '+a2.length);for(var qi=0;qi<a2.length;qi++){var t3='';try{t3=(a2[qi].innerText||a2[qi].textContent||a2[qi].value||'').toString();}catch(e){}if(t3&&t3.indexOf('正确答案')>=0&&t3.length<300){l('策略2:找到 '+a2[qi].tagName);fc(a2[qi]);if(!IS_MULTI&&SEL>0)break;}}}");
        // 策略3：按答案文本搜索
        sb.append("if(SEL===0&&AT){l('策略3:按文本 '+AT);var kwArr2=IS_MULTI?AT.split(/、|\\,|;/):[AT];for(var ki2=0;ki2<kwArr2.length;ki2++){if(!IS_MULTI&&SEL>0)break;var kw2=String(kwArr2[ki2]||'').trim();if(!kw2)continue;var a3=D.body.querySelectorAll('label,li,div,span,p,input');for(var si=0;si<a3.length;si++){var t4='';try{t4=(a3[si].innerText||a3[si].textContent||a3[si].value||'').toString();}catch(e){}if(t4&&t4.length<200&&t4.indexOf(kw2)>=0){l('策略3:找到 '+a3[si].tagName+' kw='+kw2.substring(0,15));fc(a3[si]);if(!IS_MULTI&&SEL>0)break;}}}}");
        // 策略4：MutationObserver
        sb.append("if(SEL===0&&window.MutationObserver){l('策略4:观察DOM');try{var obs=new MutationObserver(function(){var ns=D.body.querySelectorAll('label,li,span,input');for(var oi=0;oi<ns.length;oi++){var t5='';try{t5=(ns[oi].innerText||ns[oi].textContent||'').toString();}catch(e){}if(t5&&t5.indexOf('正确答案')>=0){fc(ns[oi]);}}if(!IS_MULTI&&SEL>0)obs.disconnect();});obs.observe(D.body||D.documentElement,{childList:true,subtree:true,characterData:true});setTimeout(function(){try{obs.disconnect();}catch(e){}},10000);}catch(e){l('策略4失败:'+e.message);}}");
        sb.append("l('DONE SEL='+SEL);");
        sb.append("return(SEL>0?'[ANSWER]SUCCESS|'+TAG+'|MULTI='+IS_MULTI+'|SEL='+SEL+'|FOUND='+FOUND:'[ANSWER]FAIL|'+TAG+'|MULTI='+IS_MULTI);");
        sb.append("}catch(e){try{console.log('[ANSWER]'+TAG+' ERR:'+e.message);}catch(e2){}return '[ANSWER]EXCEPTION|'+TAG+'|err='+e.message;");
        sb.append("}})();");
        return sb.toString();
    }

    // ============ 增强版点击：多种方式尝试 ============
    private static boolean performEnhancedClick(View view, String source) {
        if (view == null) return false;
        try {
            try {
                XposedBridge.log("[答案模块] [" + source + "] 尝试点击: " + getViewInfo(view));
            } catch (Throwable ignored) {}

            // 方式1: performClick
            try {
                if (view.performClick()) {
                    try { XposedBridge.log("[答案模块] [" + source + "] 点击成功: performClick"); } catch (Throwable ignored) {}
                    showToastSafe("✓ [" + source + "] 自动选中");
                    return true;
                }
            } catch (Throwable ignored) {}

            // 方式2: 反射调用 dispatchTouchEvent
            try {
                Class<?> motionEventCls = Class.forName("android.view.MotionEvent");
                Object downEvent = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                        System.currentTimeMillis(), System.currentTimeMillis(), 0,
                        (float) view.getWidth() / 2, (float) view.getHeight() / 2, 0);
                Object upEvent = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                        System.currentTimeMillis(), System.currentTimeMillis(), 1,
                        (float) view.getWidth() / 2, (float) view.getHeight() / 2, 0);
                XposedHelpers.callMethod(view, "dispatchTouchEvent", downEvent);
                XposedHelpers.callMethod(view, "dispatchTouchEvent", upEvent);
                try { XposedBridge.log("[答案模块] [" + source + "] 点击成功: dispatchTouchEvent"); } catch (Throwable ignored) {}
                showToastSafe("✓ [" + source + "] 自动选中");
                return true;
            } catch (Throwable ignored) {}

            // 方式3: 反射调用 setPressed + invalidate
            try {
                view.setPressed(true);
                view.invalidate();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        view.setPressed(false);
                        view.invalidate();
                    }
                }, 100);
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
        return false;
    }

    // ============ 获取视图信息（调试用） ============
    private static String getViewInfo(View view) {
        if (view == null) return "null";
        try {
            String className = view.getClass().getSimpleName();
            String text = "";
            if (view instanceof TextView) {
                text = ((TextView) view).getText() != null ? ((TextView) view).getText().toString() : "";
            }
            return className + " clickable=" + view.isClickable() + " enabled=" + view.isEnabled() + " text=" + text.substring(0, Math.min(30, text.length()));
        } catch (Throwable t) {
            return view.getClass().getSimpleName();
        }
    }

    // ============ 递归扫描视图树，点击含"正确答案"标记的元素（增强版） ============
    private static boolean clickViewWithMarkerEnhanced(View view, String source) {
        if (view == null) return false;
        try {
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                CharSequence text = tv.getText();
                if (text != null && text.toString().contains(ANSWER_MARKER)) {
                    View cur = tv;
                    for (int i = 0; i < 8; i++) {
                        if (cur == null) break;
                        if (cur.isClickable() && cur.isEnabled()) {
                            if (performEnhancedClick(cur, source)) {
                                return true;
                            }
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
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (clickViewWithMarkerEnhanced(child, source)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
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
    private static void showToastSafe(final String message) {
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
