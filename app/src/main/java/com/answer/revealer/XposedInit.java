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

                                // === 关键改进：只启动一次精确选中 ===
                                final Object webViewObj = param.args[0];
                                if (webViewObj != null) {
                                    try {
                                        XposedBridge.log("[答案模块] WebView 拦截 getQuestion，answerText="
                                                + (sCorrectAnswerText != null ? sCorrectAnswerText.substring(0, Math.min(30, sCorrectAnswerText.length())) : "null"));
                                    } catch (Throwable ignored) {}

                                    // === 只做一次：2秒后注入 JS（给页面足够时间渲染）===
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            injectJsIntoWebView(webViewObj, "[shouldInterceptRequest-延迟2000]");
                                        }
                                    }, 2000);

                                    // === 精确触摸：JS获取坐标后在该位置点击一次（4秒后）===
                                    if (sCorrectAnswerText != null && !sCorrectAnswerText.isEmpty() && sMarkedAnswerText != null) {
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                autoPreciseTouchWebView(webViewObj, sMarkedAnswerText, "[精确触摸4000]");
                                            }
                                        }, 4000);
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
            for (int i = 0; i < options.length(); i++) {
                JSONObject opt = options.optJSONObject(i);
                if (opt == null) continue;
                if (opt.optInt("isRight") == 1) {
                    String text = opt.optString("optionText", "");
                    // 存储正确答案原始文本，供 JS 直接选中使用
                    sCorrectAnswerText = text;
                    sMarkedAnswerText = "【 " + text + " 正确答案 】";
                    sCorrectAnswerTimestamp = System.currentTimeMillis();
                    // 重置"已自动选中"标志（这是一道新题目，可以再次自动选中）
                    sAlreadyAutoSelected.set(false);
                    // 同时写入 ContentProvider 作为备份
                    writeAnswerToProvider(text, sMarkedAnswerText);
                    opt.put("optionText", sMarkedAnswerText);
                    changed = true;
                }
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

    // ============ 安装自动选中 Hook（简化版：只在 shouldInterceptRequest 中注入） ============
    private void setupAutoSelectHooks(final ClassLoader cl) {
        // === 1. WebChromeClient.onConsoleMessage → 捕获 JS 日志到 LSPosed（调试用） ===
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
                                if (text != null && text.contains("答案模块")) {
                                    try { XposedBridge.log("[答案模块] JS: " + text); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 2. Activity onResume → 扫描原生 UI 找"正确答案"标记并点击（备选） ===
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

        // === 3. TextView setText → 检测动态内容含"正确答案"时点击父容器（备选） ===
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

    // ============ 统一的 JS 注入入口（多方式增强版） ============
    private static void injectJsIntoWebView(Object webViewObj, String sourceTag) {
        try {
            if (!readAutoSelectEnabled()) return;
            if (webViewObj == null) return;

            // 如果之前已经成功选中过，就不再重复
            if (sAlreadyAutoSelected.get()) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " 跳过: 已自动选中过"); } catch (Throwable ignored) {}
                return;
            }

            String answerText = sCorrectAnswerText;
            if (answerText == null || answerText.isEmpty()) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " 跳过: 答案文本为空"); } catch (Throwable ignored) {}
                return;
            }

            long age = System.currentTimeMillis() - sCorrectAnswerTimestamp;
            if (sCorrectAnswerTimestamp > 0 && age > 30000) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " 跳过: 答案已过期(" + age + "ms)"); } catch (Throwable ignored) {}
                return;
            }

            // 核心：优先使用"正确答案"标记文本（【 xxx 正确答案 】）作为搜索关键字
            // 这样能精确找到正确选项，不会误点其他选项
            String markerText = sMarkedAnswerText != null ? sMarkedAnswerText : ("【 " + answerText + " 正确答案 】");
            String safeM = escapeJsString(markerText);
            String safeA = escapeJsString(answerText);

            final String js = buildAutoClickJS2(safeA, safeM);

            try {
                XposedBridge.log("[答案模块] " + sourceTag + " 注入JS答案=" + answerText.substring(0, Math.min(40, answerText.length())) + " JS长=" + js.length());
            } catch (Throwable ignored) {}

            boolean injected = false;
            try {
                XposedHelpers.callMethod(webViewObj, "evaluateJavascript", js, null);
                injected = true;
                try { XposedBridge.log("[答案模块] " + sourceTag + " evaluateJavascript OK"); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            if (!injected) {
                try {
                    XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:" + js);
                    injected = true;
                } catch (Throwable ignored) {}
            }

            if (injected) {
                sAlreadyAutoSelected.set(true);
            }

            if (!injected) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " 所有JS注入方式失败"); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] " + sourceTag + " 注入异常: " + t.getMessage()); } catch (Throwable ignored2) {}
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

    // ============ 构建自动选中 JS：只点击含"正确答案"标记的元素 ============
    private static String buildAutoClickJS2(String safeA, String safeM) {
        StringBuilder sb = new StringBuilder();
        sb.append("try{");
        // AM = 标记文本（【 xxx 正确答案 】），最精确
        // AT = 原始答案文本（备用）
        sb.append("var AT='").append(safeA).append("';var AM='").append(safeM).append("';");
        sb.append("var D=document;var FOUND=0;var SEL=0;");

        // ========== 核心：对元素执行选中操作 ==========
        sb.append("function dc(el){");
        sb.append("if(SEL>0)return;SEL++;");
        sb.append("try{el.checked=true;}catch(e){}");
        sb.append("try{if(el.click)el.click();}catch(e){}");
        sb.append("try{var ev1=new MouseEvent('click',{bubbles:true,cancelable:true,view:window);el.dispatchEvent(ev1);}catch(e){}");
        sb.append("try{var md=document.createEvent('MouseEvents');md.initMouseEvent('mousedown',true,true,window,0,0,0,0,0,false,false,false,false,0,null);el.dispatchEvent(md);}catch(e){}");
        sb.append("try{var mu=document.createEvent('MouseEvents');mu.initMouseEvent('mouseup',true,true,window,0,0,0,0,0,false,false,false,false,0,null);el.dispatchEvent(mu);}catch(e){}");
        sb.append("try{var ce=document.createEvent('HTMLEvents');ce.initEvent('change',true,true);el.dispatchEvent(ce);}catch(e){}");
        sb.append("try{var ie=document.createEvent('HTMLEvents');ie.initEvent('input',true,true);el.dispatchEvent(ie);}catch(e){}");
        sb.append("try{el.setAttribute('checked','checked');}catch(e){}");
        sb.append("try{el.style.backgroundColor='#4CAF50';el.style.color='#fff';el.style.padding='4px 8px';el.style.borderRadius='4px';}catch(e){}");
        sb.append("try{if(el.parentElement){el.parentElement.click();}catch(e){}");
        sb.append("console.log('[答案模块] 成功选中元素: tag='+el.tagName+' text='+(el.innerText||el.value||'').substring(0,40));}");

        // ========== findAndClick: 在元素及其子元素中查找并点击 ==========
        sb.append("function fc(el){");
        sb.append("if(SEL>0)return;");
        sb.append("var inp=el.querySelector?el.querySelector('input,button,[role=button],[onclick]'):null;");
        sb.append("if(inp){dc(inp);return;}");
        sb.append("var cu=el;for(var i=0;i<15;i++){");
        sb.append("if(!cu)break;");
        sb.append("if(cu.tagName==='INPUT'||cu.tagName==='BUTTON'||cu.tagName==='A'){dc(cu);return;}");
        sb.append("var q=cu.querySelector?cu.querySelector('input,button'):null;if(q){dc(q);return;}");
        sb.append("cu=cu.parentElement;}dc(el);}");

        // ========== 策略1: 使用标记文本（【xxx正确答案】）搜索 —— 最精确，优先使用 ==========
        sb.append("if(AM&&AM.indexOf('正确答案')>0){");
        sb.append("var all=D.body.querySelectorAll('*');");
        sb.append("for(var i=0;i<all.length;i++){");
        sb.append("var txt='';try{txt=(all[i].innerText||all[i].textContent||'').toString();}catch(e){}");
        sb.append("if(txt.indexOf('正确答案')>0){");
        sb.append("FOUND++;");
        sb.append("if(FOUND===1){");
        sb.append("var ael=all[i];fc(ael);break;}}}");
        sb.append("}");

        // ========== 策略2: 备选 —— 遍历所有元素找含原始答案文本的元素 ==========
        sb.append("if(SEL===0){");
        sb.append("var all2=D.body.querySelectorAll('label,div,span,li,p,td');");
        sb.append("for(var j=0;j<all2.length;j++){");
        sb.append("var txt2='';try{txt2=(all2[j].innerText||all2[j].textContent||'').toString();}catch(e){}");
        sb.append("if(txt2&&txt2.length<500&&(AM&&txt2.indexOf(AM)>=0||txt2.indexOf(AT)>=0)){");
        sb.append("fc(all2[j]);if(SEL>0)break;}}}");

        // ========== 策略3: MutationObserver —— 延迟5秒监听，页面可能动态加载 ==========
        sb.append("if(SEL===0&&window.MutationObserver){try{");
        sb.append("var obs=new MutationObserver(function(m){if(SEL>0){obs.disconnect();return;}");
        sb.append("var nodes=D.body.querySelectorAll('label,div,span,li');");
        sb.append("for(var oi=0;oi<nodes.length;oi++){");
        sb.append("var nt='';try{nt=(nodes[oi].innerText||nodes[oi].textContent||'').toString();}catch(e){}");
        sb.append("if(nt&&nt.length<500&&(nt.indexOf('正确答案')>0||(AM&&nt.indexOf(AM)>=0)||nt.indexOf(AT)>=0)){");
        sb.append("fc(nodes[oi]);if(SEL>0){obs.disconnect();return;}}});");
        sb.append("obs.observe(D.body||D.documentElement,{childList:true,subtree:true,characterData:true});");
        sb.append("setTimeout(function(){try{obs.disconnect();}catch(e){}},8000);");
        sb.append("console.log('[答案模块] MutationObserver 启动监听');");
        sb.append("}catch(e){}}");

        sb.append("console.log('[答案模块] JS执行完成 selected='+SEL);");
        sb.append("}catch(e){console.log('[答案模块] JS顶层异常:'+e.message);}");
        return sb.toString();
    }

    // ============ 构建自动选中 JS（保留原方法名，调用 v2）============
    private String buildAutoClickJS() {
        String answerText = sCorrectAnswerText;
        if (answerText == null || answerText.isEmpty()) return "";
        String safeA = escapeJsString(answerText);
        String safeM = escapeJsString(sMarkedAnswerText != null ? sMarkedAnswerText : "");
        return buildAutoClickJS2(safeA, safeM);
    }

    // ============ 精确触摸：JS获取含标记元素坐标，在该精确位置点击一次 ============
    private static void autoPreciseTouchWebView(final Object webViewObj, final String markedText, final String source) {
        try {
            if (webViewObj == null || markedText == null || markedText.isEmpty()) return;
            if (sAlreadyAutoSelected.get()) return;

            String safeM = escapeJsString(markedText);

            // 只在 WebView 中执行一次触摸
            // 先获取元素坐标（通过 evaluateJavascript 或 loadUrl）
            String js = "try{"
                    + "var M='" + safeM + "';"
                    + "var nodes=document.body.querySelectorAll('*');var r=null;"
                    + "for(var i=0;i<nodes.length;i++){"
                    + "var tt='';try{tt=(nodes[i].innerText||nodes[i].textContent||'').toString();}catch(e){}"
                    + "if(tt.indexOf('正确答案')>0||(M&&tt.indexOf(M)>=0)){"
                    + "var ri=nodes[i].getBoundingClientRect();"
                    + "if(ri.width>0&&ri.height>0){r=ri;break;}}}"
                    + "if(r){console.log('[答案模块] 触摸坐标: x='+(r.left+r.width/2)+' y='+(r.top+r.height/2));}"
                    + "}catch(e){console.log('[答案模块] 坐标获取异常:'+e.message);}";

            try {
                XposedHelpers.callMethod(webViewObj, "evaluateJavascript", js, null);
            } catch (Throwable ignored) {
                try { XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:" + js); } catch (Throwable ignored2) {}
            }

            // 延迟 800ms 后：读取 WebView 位置并在元素位置发送一次触摸
            final Object webViewFinal = webViewObj;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sAlreadyAutoSelected.get()) return;

                        int[] loc = new int[2];
                        int w = 0, h = 0;
                        if (webViewFinal instanceof android.webkit.WebView) {
                            android.webkit.WebView wv = (android.webkit.WebView) webViewFinal;
                            wv.getLocationOnScreen(loc);
                            w = wv.getWidth();
                            h = wv.getHeight();
                        } else {
                            try {
                                XposedHelpers.callMethod(webViewFinal, "getLocationOnScreen", (Object) loc);
                                Object wv = XposedHelpers.callMethod(webViewFinal, "getWidth");
                                Object hv = XposedHelpers.callMethod(webViewFinal, "getHeight");
                                if (wv instanceof Integer) w = (Integer) wv;
                                if (hv instanceof Integer) h = (Integer) hv;
                            } catch (Throwable ignored) {
                                w = 500; h = 800;
                            }
                        }

                        if (w <= 0 || h <= 0) return;

                        // 在 WebView 中心偏上位置发送一次触摸（因为题目选项通常在页面中部）
                        // 由于我们无法直接读取 JS 返回的精确坐标，这里使用一种启发式方法
                        long downTime = System.currentTimeMillis();
                        Class<?> motionEventCls = Class.forName("android.view.MotionEvent");

                        // 点击 WebView 中心位置的一次触摸（配合 JS 中的精确点击）
                        int targetX = loc[0] + w / 2;
                        int targetY = loc[1] + h / 3;

                        Object down = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                                downTime, downTime, 0, (float) targetX, (float) targetY, 0);
                        XposedHelpers.callMethod(webViewFinal, "dispatchTouchEvent", down);
                        Object up = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                                downTime, System.currentTimeMillis() + 100, 1, (float) targetX, (float) targetY, 0);
                        XposedHelpers.callMethod(webViewFinal, "dispatchTouchEvent", up);

                        sAlreadyAutoSelected.set(true);
                        try { XposedBridge.log("[答案模块] " + source + " 精确触摸: x=" + targetX + " y=" + targetY); } catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        try { XposedBridge.log("[答案模块] " + source + " 精确触摸异常: " + t.getMessage()); } catch (Throwable ignored) {}
                    }
                }
            }, 800);
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] autoPreciseTouchWebView 异常: " + t.getMessage()); } catch (Throwable ignored) {}
        }
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
