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

                                // === 关键改进：在此处直接启动 JS 注入（同一次拦截，答案文本已设置）===
                                final Object webViewObj = param.args[0];
                                if (webViewObj != null) {
                                    try {
                                        XposedBridge.log("[答案模块] WebView 拦截 getQuestion，answerText="
                                                + (sCorrectAnswerText != null ? sCorrectAnswerText.substring(0, Math.min(30, sCorrectAnswerText.length())) : "null"));
                                    } catch (Throwable ignored) {}
                                    // 延迟注入 JS（等待页面渲染）
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            injectJsIntoWebView(webViewObj, "[shouldInterceptRequest-延迟800]");
                                        }
                                    }, 800);
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            injectJsIntoWebView(webViewObj, "[shouldInterceptRequest-延迟2000]");
                                        }
                                    }, 2000);
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            injectJsIntoWebView(webViewObj, "[shouldInterceptRequest-延迟4000]");
                                        }
                                    }, 4000);

                                    // === 新增：Java 层原生触摸（备选方案）===
                                    if (sCorrectAnswerText != null && !sCorrectAnswerText.isEmpty()) {
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                autoTouchWebView(webViewObj, sCorrectAnswerText, "[shouldInterceptRequest-原生触摸3000]");
                                            }
                                        }, 3000);
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                autoTouchWebView(webViewObj, sCorrectAnswerText, "[shouldInterceptRequest-原生触摸5000]");
                                            }
                                        }, 5000);
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

    // ============ 安装自动选中 Hook（WebView + 原生UI） ============
    private void setupAutoSelectHooks(final ClassLoader cl) {
        // === 1. WebView: onPageFinished → 注入 JS + 原生触摸 ============
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageFinished",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() { injectJsIntoWebView(webView, "[onPageFinished-800]"); }
                                }, 800);
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() { injectJsIntoWebView(webView, "[onPageFinished-2000]"); }
                                }, 2000);
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() { injectJsIntoWebView(webView, "[onPageFinished-4000]"); }
                                }, 4000);
                                // 原生触摸
                                if (sCorrectAnswerText != null && !sCorrectAnswerText.isEmpty()) {
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() { autoTouchWebView(webView, sCorrectAnswerText, "[onPageFinished-触摸3500]"); }
                                    }, 3500);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 2. WebViewClient.shouldInterceptRequest after hook（备用） ============
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", "android.webkit.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                Object result = param.getResult();
                                if (result == null) return;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() { injectJsIntoWebView(webView, "[shouldIntercept-1200]"); }
                                }, 1200);
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() { injectJsIntoWebView(webView, "[shouldIntercept-2500]"); }
                                }, 2500);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 3. WebChromeClient.onConsoleMessage → 捕获 JS 日志到 XposedBridge ============
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

        // === 4. WebView.onPageStarted → 延迟注入 ============
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageStarted",
                    "android.webkit.WebView", String.class, "android.graphics.Bitmap",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                final Object webView = param.args[0];
                                if (webView == null) return;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() { injectJsIntoWebView(webView, "[onPageStarted-1500]"); }
                                }, 1500);
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override public void run() { injectJsIntoWebView(webView, "[onPageStarted-3500]"); }
                                }, 3500);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // === 5. Activity onResume → 扫描原生 UI 找正确答案并点击 ============
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) {
                            try {
                                if (!readAutoSelectEnabled()) return;
                                final Object activityObj = param.thisObject;
                                if (activityObj == null) return;

                                final long[] delays = {500, 1200, 2000};
                                for (int i = 0; i < delays.length; i++) {
                                    final int attempt = i + 1;
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() {
                                            try {
                                                Object window = XposedHelpers.callMethod(activityObj, "getWindow");
                                                if (window == null) return;
                                                Object decorView = XposedHelpers.callMethod(window, "getDecorView");
                                                if (decorView instanceof View) {
                                                    if (clickViewWithMarkerEnhanced((View) decorView, "Activity.onResume[" + attempt + "]")) {
                                                        return;
                                                    }
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }, delays[i]);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[答案模块] Activity onResume hook 已安装"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] Activity onResume hook失败: " + t.getMessage()); } catch (Throwable ignored) {}
        }

        // === 6. TextView setText → 检测动态内容含"正确答案"时点击父容器 ============
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

                                final long[] delays = {300, 800, 1500};
                                for (int i = 0; i < delays.length; i++) {
                                    final int attempt = i + 1;
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() {
                                            try {
                                                View cur = (View) tvObj;
                                                for (int j = 0; j < 6; j++) {
                                                    if (cur == null) break;
                                                    if (cur.isClickable() && cur.isEnabled()) {
                                                        if (performEnhancedClick(cur, "TextView.setText[" + attempt + "]")) return;
                                                    }
                                                    Object parent = cur.getParent();
                                                    if (parent instanceof View) cur = (View) parent; else break;
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }, delays[i]);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            try { XposedBridge.log("[答案模块] TextView setText hook 已安装"); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] TextView setText hook失败: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    // ============ 统一的 JS 注入入口（多方式增强版） ============
    private static void injectJsIntoWebView(Object webViewObj, String sourceTag) {
        try {
            if (!readAutoSelectEnabled()) return;
            if (webViewObj == null) return;

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

            String safeA = escapeJsString(answerText);
            String safeM = escapeJsString(sMarkedAnswerText != null ? sMarkedAnswerText : "");
            final String js = buildAutoClickJS2(safeA, safeM);

            try {
                XposedBridge.log("[答案模块] " + sourceTag + " 注入JS答案=" + answerText.substring(0, Math.min(40, answerText.length())) + " JS长=" + js.length());
            } catch (Throwable ignored) {}

            // ========== 方式 1: evaluateJavascript (带 ValueCallback) ==========
            try {
                Object callback = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.webkit.ValueCallback", webViewObj.getClass().getClassLoader()),
                        "toString"  // 用匿名类替代，这里改用反射构造
                );
                XposedHelpers.callMethod(webViewObj, "evaluateJavascript", js, null);
                try { XposedBridge.log("[答案模块] " + sourceTag + " 方式1 evaluateJavascript OK"); } catch (Throwable ignored) {}
                return;
            } catch (Throwable ignored) {}

            // ========== 方式 2: loadUrl("javascript:...") 带前缀 ==========
            try {
                XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:(function(){" + js + "})()");
                try { XposedBridge.log("[答案模块] " + sourceTag + " 方式2 loadUrl OK"); } catch (Throwable ignored) {}
                return;
            } catch (Throwable ignored) {}

            // ========== 方式 3: 直接执行纯 JS（不包装 IIFE） ==========
            try {
                XposedHelpers.callMethod(webViewObj, "evaluateJavascript", js, null);
                try { XposedBridge.log("[答案模块] " + sourceTag + " 方式3 纯evaluateJavascript OK"); } catch (Throwable ignored) {}
                return;
            } catch (Throwable ignored) {}

            // ========== 方式 4: 超简化版 JS - 只做最基本的 checked 设置 ==========
            try {
                String miniJs = "var t='".concat(safeA).concat("';var ls=document.querySelectorAll('label');for(var i=0;i<ls.length;i++){var tx=ls[i].innerText||'';if(tx.indexOf(t)>=0){var inp=ls[i].querySelector('input');if(inp){inp.checked=true;try{inp.dispatchEvent(new Event('change'));}catch(e){};try{inp.style.backgroundColor='#4CAF50';}catch(e){}break;}}}console.log('[答案模块] miniJS done');");
                XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:" + miniJs);
                try { XposedBridge.log("[答案模块] " + sourceTag + " 方式4 miniJS OK"); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                try { XposedBridge.log("[答案模块] " + sourceTag + " 所有JS注入方式失败: " + t.getMessage()); } catch (Throwable ignored2) {}
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

    // ============ 构建自动选中 JS（v2 增强版：10+ 策略 ============
    private static String buildAutoClickJS2(String safeA, String safeM) {
        StringBuilder sb = new StringBuilder();
        sb.append("try{");
        sb.append("var AT='").append(safeA).append("';var AM='").append(safeM).append("';var S=false;var D=document;");

        // ========== doClick: 对一个元素执行点击（多种JS事件） ==========
        sb.append("function dc(el){");
        sb.append("try{el.checked=true;}catch(e){}");
        sb.append("try{if(el.click)el.click();}catch(e){}");
        sb.append("try{var ev1=new MouseEvent('click',{bubbles:true,cancelable:true,view:window);el.dispatchEvent(ev1);}catch(e){}");
        sb.append("try{var d=document.createEvent('MouseEvents');d.initMouseEvent('mousedown',true,true,window,0,0,0,0,0,false,false,false,false,0,null);el.dispatchEvent(d);}catch(e){}");
        sb.append("try{var u=document.createEvent('MouseEvents');u.initMouseEvent('mouseup',true,true,window,0,0,0,0,0,false,false,false,false,0,null);el.dispatchEvent(u);}catch(e){}");
        sb.append("try{var ce=document.createEvent('HTMLEvents');ce.initEvent('change',true,true);el.dispatchEvent(ce);}catch(e){}");
        sb.append("try{var ie=document.createEvent('HTMLEvents');ie.initEvent('input',true,true);el.dispatchEvent(ie);}catch(e){}");
        sb.append("try{if(el.parentElement){el.parentElement.click();}catch(e){}");
        sb.append("try{el.focus();}catch(e){}");
        sb.append("try{el.setAttribute('checked','checked');}catch(e){}");
        sb.append("try{if(el.value){el.value='true';}catch(e){}");
        sb.append("try{el.style.backgroundColor='#4CAF50';el.style.color='#fff';el.style.padding='4px 8px';el.style.borderRadius='4px';}catch(e){}");
        sb.append("S=true;console.log('[答案模块] doClick成功: '+el.tagName);}");

        // ========== 策略1: label 匹配答案文本
        sb.append("try{var lbs=D.querySelectorAll('label,div,span');");
        sb.append("for(var i=0;i<lbs.length;i++){var lb=lbs[i];");
        sb.append("var tt=lb.innerText||lb.textContent||'';tt=tt.toString();");
        sb.append("if(tt.indexOf(AT)>=0){");
        sb.append("var inp=lb.querySelector?lb.querySelector('input[type=radio],input[type=checkbox],input,button'):null;if(inp){dc(inp);break;}");
        sb.append("var fid=lb.getAttribute&&lb.getAttribute('for');if(fid){var ip2=D.getElementById(fid);if(ip2){dc(ip2);break;}}");
        sb.append("dc(lb);if(S)break;}");
        sb.append("}}catch(e){}");

        // ========== 策略2: 遍历所有 input type=radio/checkbox 父元素文本匹配
        sb.append("if(!S){try{var ins=D.querySelectorAll('input[type=radio],input[type=checkbox],input');");
        sb.append("for(var j=0;j<ins.length;j++){var ip=ins[j];var pu=(ip.parentElement)?(ip.parentElement.innerText||ip.parentElement.textContent||''):'';");
        sb.append("if(pu.indexOf(AT)>=0){dc(ip);break;}}catch(e){}");

        // ========== 策略3: 遍历所有 div/span/li/p/td + 向上查找可点击元素
        sb.append("if(!S){try{var els=D.querySelectorAll('div,span,li,p,td,button,a');");
        sb.append("for(var k=0;k<els.length;k++){var el2=els[k];");
        sb.append("var et='';try{et=(el2.innerText||el2.textContent||'').toString();}catch(e){}");
        sb.append("if(et.indexOf(AT)>=0){");
        sb.append("var cu=el2;for(var lv=0;lv<12;lv++){if(!cu)break;");
        sb.append("if(cu.tagName==='INPUT'||cu.tagName==='BUTTON'||cu.tagName==='A'){dc(cu);break;}");
        sb.append("var qp=cu.querySelector&&cu.querySelector('input,button');if(qp){dc(qp);break;}");
        sb.append("if(cu.onclick||cu.style&&cu.style&&cu.style.cursor){try{dc(cu);break;}catch(e){}}");
        sb.append("cu=cu.parentElement;}if(S)break;}");
        sb.append("}}catch(e){}");

        // ========== 策略4: 通过 正确答案 标记文本查找
        sb.append("if(!S&&AM){try{var al=D.querySelectorAll('*');");
        sb.append("for(var m=0;m<al.length;m++){var e3=al[m];");
        sb.append("var t3='';try{t3=(e3.innerText||e3.textContent||'').toString();}catch(e){}");
        sb.append("if(t3.indexOf(AM)>=0){var cu3=e3;for(var lv3=0;lv3<18;lv3++){if(!cu3)break;");
        sb.append("if(cu3.tagName==='INPUT'||cu3.tagName==='BUTTON'||cu3.tagName==='A'){dc(cu3);break;}");
        sb.append("var qp3=cu3.querySelector&&cu3.querySelector('input,button');if(qp3){dc(qp3);break;}");
        sb.append("cu3=cu3.parentElement;}if(S)break;}");
        sb.append("}}catch(e){}");

        // ========== 策略5: 找 form 中的 button/input submit
        sb.append("if(!S){try{var forms=D.querySelectorAll('form');");
        sb.append("for(var fi=0;fi<forms.length;fi++){");
        sb.append("var ftx=forms[fi].innerText||forms[fi].textContent||'';if(ftx.indexOf(AT)>=0){");
        sb.append("var fbs=forms[fi].querySelectorAll('input,button');");
        sb.append("for(var fb=0;fb<fbs.length;fb++){var fbb=fbs[fb];var btx='';try{btx=(fbb.innerText||fbb.value||'').toString();}catch(e){}if(btx.indexOf(AT)>=0||(fbb.parentElement&&fbb.parentElement.innerText&&fbb.parentElement.innerText.indexOf(AT)>=0){dc(fbb);break;}}if(S)break;}");
        sb.append("}}catch(e){}");

        // ========== 策略6: 遍历所有可点击元素
        sb.append("if(!S){try{var ev2=D.querySelectorAll('button,a,[onclick],[role=button],[tabindex]');");
        sb.append("for(var qi=0;qi<ev2.length;qi++){var ev22=ev2[qi];var et2='';try{et2=(ev22.innerText||ev22.textContent||'').toString();}catch(e){}");
        sb.append("if(et2.indexOf(AT)>=0){dc(ev22);if(S)break;}");
        sb.append("}}catch(e){}");

        // ========== 策略7: 通过 input 的 value 属性
        sb.append("if(!S){try{var allinp=D.getElementsByTagName('input');");
        sb.append("for(var ai=0;ai<allinp.length;ai++){var ai2=allinp[ai];var vv='';try{vv=(ai2.value||'').toString();}catch(e){}");
        sb.append("if(vv.indexOf(AT)>=0){dc(ai2);break;}");
        sb.append("var p=ai2.parentElement;if(p){var pt='';try{pt=(p.innerText||p.textContent||'').toString();}catch(e){}if(pt.indexOf(AT)>=0){dc(ai2);break;}}}");
        sb.append("}}catch(e){}");

        // ========== 策略8: 原生 dispatch KeyboardEvent（模拟用户键盘点击）
        sb.append("if(!S){try{var sp1=D.querySelectorAll('label,div');for(var si=0;si<sp1.length;si++){var spt=(sp1[si].innerText||'').toString();if(spt.indexOf(AT)>=0){var spkp=sp1[si].querySelector('input');if(spkp){try{var kb=document.createEvent('KeyboardEvent');kb.initKeyboardEvent('keydown',true,true,window,0,0,0,0,0,0,0);spkp.dispatchEvent(kb);}catch(e){}dc(spkp);break;}}});}catch(e){}");

        // ========== 策略9: 通过 position:relative clickable elements click via getBoundingClientRect
        sb.append("if(!S){try{var labels2=D.querySelectorAll('div,span,li');");
        sb.append("for(var zi=0;zi<labels2.length;zi++){");
        sb.append("var le=labels2[zi];var lt='';try{lt=(le.innerText||le.textContent||'').toString();}catch(e){}");
        sb.append("if(lt.indexOf(AT)>=0){");
        sb.append("try{var rect=le.getBoundingClientRect();var evt2=document.createEvent('MouseEvents');evt2.initMouseEvent('click',true,true,window,1,rect.left+rect.width/2,rect.top+rect.height/2,rect.left+rect.width/2,rect.top+rect.height/2,false,false,false,false,0,null);le.dispatchEvent(evt2);S=true;console.log('[答案模块] BoundingRect成功');}catch(e){}");
        sb.append("if(S)break;}");
        sb.append("}}catch(e){}");

        // ========== 策略10: MutationObserver 监听动态内容
        sb.append("if(!S&&window.MutationObserver){try{");
        sb.append("var obs=new MutationObserver(function(){if(S)return;");
        sb.append("var lss=D.querySelectorAll('label,div,input,button');");
        sb.append("for(var oi=0;oi<lss.length;oi++){var lel=lss[oi];");
        sb.append("var ltx='';try{ltx=(lel.innerText||lel.textContent||'').toString();}catch(e){}");
        sb.append("if(ltx.indexOf(AT)>=0||(AM&&ltx.indexOf(AM)>=0)){var inp4=lel.querySelector&&lel.querySelector('input,button');if(inp4){dc(inp4);obs.disconnect();return;}else{dc(lel);obs.disconnect();return;}}}");
        sb.append("});obs.observe(D.body||D.documentElement,{childList:true,subtree:true,characterData:true,attributes:true});");
        sb.append("setTimeout(function(){try{obs.disconnect();}catch(e){}},20000);console.log('[答案模块] MutationObserver启动');}catch(e){}");

        // ========== 策略11: 直接模拟触摸（PointerEvents）
        sb.append("if(!S){try{var pobs='';var plbls=D.querySelectorAll('div,label,span,button');");
        sb.append("for(var pti=0;pti<plbls.length;pti++){");
        sb.append("var ple=plbls[pti];var pltx='';try{pltx=(ple.innerText||ple.textContent||'').toString();}catch(e){}");
        sb.append("if(pltx.indexOf(AT)>=0){");
        sb.append("try{var prect=ple.getBoundingClientRect();var pe=document.createEvent('MouseEvents');pe.initMouseEvent('pointerdown',true,true,window,1,prect.left+prect.width/2,prect.top+prect.height/2,prect.left+prect.width/2,prect.top+prect.height/2,false,false,false,false,0,null);ple.dispatchEvent(pe);}catch(e){}");
        sb.append("try{var pu2=document.createEvent('MouseEvents');pu2.initMouseEvent('pointerup',true,true,window,1,prect.left+prect.width/2,prect.top+prect.height/2,prect.left+prect.width/2,prect.top+prect.height/2,false,false,false,false,0,null);ple.dispatchEvent(pu2);S=true;console.log('[答案模块] Pointer成功');}catch(e){}");
        sb.append("if(S)break;}");
        sb.append("}}catch(e){}");

        // ========== 策略12: 遍历所有元素，含答案文本就点击其第一子元素
        sb.append("if(!S){try{var all2=document.body.getElementsByTagName('*');");
        sb.append("for(var xi=0;xi<Math.min(all2.length,3000);xi++){");
        sb.append("var xe=all2[xi];var xtx='';try{xtx=(xe.innerText||xe.textContent||'').toString();}catch(e){}");
        sb.append("if(xtx&&(xtx.indexOf(AT)>=0||(AM&&xtx.indexOf(AM)>=0))&&xtx.length<300){");
        sb.append("var xcu=xe;for(var xlv=0;xlv<8;xlv++){if(!xcu)break;");
        sb.append("if(xcu.querySelector&&xcu.querySelector('input,button')){var xi2=xcu.querySelector('input,button');if(xi2){dc(xi2);break;}}");
        sb.append("xcu=xcu.parentElement;}if(S)break;}");
        sb.append("}}catch(e){}");

        sb.append("console.log('[答案模块] JS执行完成 selected='+S);");

        sb.append("}catch(e){console.log('[答案模块] JS顶层异常:'+e.message);}");
        return sb.toString();
    }

    // ============ 构建自动选中 JS（保留原方法名，调用 v2） ============
    private String buildAutoClickJS() {
        String answerText = sCorrectAnswerText;
        if (answerText == null || answerText.isEmpty()) return "";
        String safeA = escapeJsString(answerText);
        String safeM = escapeJsString(sMarkedAnswerText != null ? sMarkedAnswerText : "");
        return buildAutoClickJS2(safeA, safeM);
    }

    // ============ WebView 原生触摸点击：通过 JS 获取坐标，在 Android 层发送触摸事件 ============
    private static void autoTouchWebView(final Object webViewObj, final String answerText, final String source) {
        try {
            if (webViewObj == null || answerText == null || answerText.isEmpty()) return;

            // 先尝试：在 WebView 中执行 JS 获取正确答案元素坐标，然后模拟触摸
            String safeA = escapeJsString(answerText);
            String js = "function getRectOfAnswer(){try{"
                    + "var AT='" + safeA + "';"
                    + "var lbs=document.querySelectorAll('label,div,span,button');for(var i=0;i<lbs.length;i++){"
                    + "var tt=lbs[i].innerText||'';if(tt.indexOf(AT)>=0){"
                    + "var r=lbs[i].getBoundingClientRect();"
                    + "if(r.width>0&&r.height>0)return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height});}}"
                    + "}catch(e){return '';}return '';}getRectOfAnswer();";

            try {
                // 使用 loadUrl 触发 JS 执行，同时记录答案文本
                XposedHelpers.callMethod(webViewObj, "loadUrl", "javascript:" + js);
                try { XposedBridge.log("[答案模块] " + source + " 已启动触摸坐标获取"); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            // 延迟 1.5s 后尝试多种 Java 层点击（用 JS 回调）
            final Object webViewFinal = webViewObj;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!(webViewFinal instanceof android.webkit.WebView)) {
                            // 如果不是系统 WebView 也尝试反射调用
                            try {
                                int[] loc = new int[2];
                                XposedHelpers.callMethod(webViewFinal, "getLocationOnScreen", (Object) loc);
                                int centerX = loc[0] + 100;
                                int centerY = loc[1] + 100;
                                dispatchSimulatedTouch(webViewFinal, centerX, centerY);
                            } catch (Throwable ignored) {}
                        } else {
                            android.webkit.WebView wv = (android.webkit.WebView) webViewFinal;
                            // 尝试遍历 WebView 的内容：取中部坐标点击
                            int[] loc = new int[2];
                            wv.getLocationOnScreen(loc);
                            int w = wv.getWidth();
                            int h = wv.getHeight();
                            if (w > 0 && h > 0) {
                                // 点击多个位置尝试
                                int[][] points = {
                                        {w / 2, h / 3},
                                        {w / 2, h / 2},
                                        {w / 2, h * 2 / 3},
                                        {w / 4, h / 2},
                                        {w * 3 / 4, h / 2},
                                };
                                for (int pi = 0; pi < points.length; pi++) {
                                    final int px = loc[0] + points[pi][0];
                                    final int py = loc[1] + points[pi][1];
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                dispatchSimulatedTouch(webViewFinal, px, py);
                                            } catch (Throwable ignored) {}
                                        }
                                    }, pi * 300);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }, 1500);
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] autoTouchWebView 异常: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    // ============ 对指定 View/WebView 发送一次触摸点击（Java 层）============
    private static void dispatchSimulatedTouch(Object viewObj, int x, int y) {
        try {
            if (viewObj == null) return;
            long downTime = System.currentTimeMillis();
            Class<?> motionEventCls = Class.forName("android.view.MotionEvent");
            // ACTION_DOWN
            Object down = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                    downTime, downTime, 0, (float) x, (float) y, 0);
            XposedHelpers.callMethod(viewObj, "dispatchTouchEvent", down);
            // ACTION_UP
            Object up = XposedHelpers.callStaticMethod(motionEventCls, "obtain",
                    downTime, System.currentTimeMillis() + 50, 1, (float) x, (float) y, 0);
            XposedHelpers.callMethod(viewObj, "dispatchTouchEvent", up);
            try { XposedBridge.log("[答案模块] dispatchSimulatedTouch: x=" + x + " y=" + y); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { XposedBridge.log("[答案模块] dispatchSimulatedTouch 异常: " + t.getMessage()); } catch (Throwable ignored2) {}
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
