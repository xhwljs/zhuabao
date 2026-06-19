package com.answer.revealer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.webkit.WebResourceResponse;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
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

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String TARGET_PATH = "getQuestion";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String SP_NAME = "answer_revealer_status";

    // 常见 HTTP 客户端 / 网络框架 类名
    private static final String[] HTTP_CLIENT_CLASS_NAMES = {
            "okhttp3.OkHttpClient",
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.Dispatcher",
            "okhttp3.Request",
            "okhttp3.Response",
            "okhttp3.ResponseBody",
            "retrofit2.Retrofit",
            "retrofit2.OkHttpCall",
            "org.apache.http.impl.client.DefaultHttpClient",
            "org.apache.http.impl.client.CloseableHttpClient",
            "org.apache.http.client.HttpClient",
            "java.net.HttpURLConnection",
            "javax.net.ssl.HttpsURLConnection",
            "com.android.volley.toolbox.Volley",
            "com.android.volley.toolbox.StringRequest",
            "com.android.volley.toolbox.JsonObjectRequest",
            "android.webkit.WebView",
            "android.webkit.WebViewClient",
            "org.xutils.http.RequestParams",
            "org.xutils.x",
            "com.lidroid.xutils.HttpUtils",
            "com.loopj.android.http.AsyncHttpClient",
            "com.squareup.okhttp.OkHttpClient",
            "io.flutter.embedding.engine.FlutterEngine",
            "io.flutter.plugin.common.MethodChannel",
            "com.facebook.react.ReactInstanceManager",
            "com.facebook.react.bridge.NativeModuleCallExceptionHandler",
            "org.apache.cordova.CordovaWebView",
            "org.apache.cordova.CordovaInterface",
            "com.tencent.smtt.sdk.WebView",
            "com.tencent.smtt.sdk.WebViewClient"
    };

    private static final java.util.Set<String> initializedPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final List<String> detectedClients =
            Collections.synchronizedList(new ArrayList<String>());

    private static final AtomicInteger hookInstalledCount = new AtomicInteger(0);
    private static final AtomicInteger requestCounter = new AtomicInteger(0);
    private static final AtomicInteger targetHitCounter = new AtomicInteger(0);

    private static volatile Context appContext;
    private static ClassLoader targetClassLoader;

    // ====== 从 ActivityThread 拿 Application Context ======
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

        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            hookSelf(lpparam.classLoader);
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application", lpparam.classLoader, "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Context ctx = (Context) param.thisObject;
                                    appContext = ctx;
                                    SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                                    sp.edit().putBoolean("module_active_v1", true)
                                            .putLong("last_hook_time", System.currentTimeMillis()).commit();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                );
            } catch (Throwable ignored) {
            }
            return;
        }

        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;
        if (!initializedPackages.add(TARGET_PACKAGE)) return;

        targetClassLoader = lpparam.classLoader;

        // 获取 Context
        Context ctxNow = getAppContextFromActivityThread();
        if (ctxNow != null) {
            appContext = ctxNow;
            try {
                SharedPreferences sp = ctxNow.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                sp.edit().putBoolean("module_active_v1", true)
                        .putLong("last_hook_time", System.currentTimeMillis()).commit();
            } catch (Throwable ignored) {
            }
        }

        // Hook Application.onCreate（兜底获取 Context）
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application", lpparam.classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                if (appContext == null) appContext = ctx;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        // 扫描 HTTP 客户端
        scanHttpClients();

        // 安装网络 Hook
        int before = hookInstalledCount.get();

        if (hasClass("okhttp3.OkHttpClient") || hasClass("okhttp3.Request")) {
            try { hookOkHttpLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        }
        try { hookHttpURLConnectionLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookSocketLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookWebViewLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookURLOpenConnection(lpparam.classLoader); } catch (Throwable ignored) {}

        int installed = hookInstalledCount.get() - before;

        // 写入检测结果并弹 Toast
        try {
            Context c = appContext != null ? appContext : getAppContextFromActivityThread();
            if (c != null) {
                writeDetectionToModuleSP(c, installed);
                showToastSafe("【答案模块】已加载，安装 " + installed + " 个 Hook，检测到 "
                        + detectedClients.size() + " 个 HTTP 相关类");
            }
            XposedBridge.log("[答案模块] hook 安装完成，目标包=" + TARGET_PACKAGE
                    + "，hook数=" + installed + "，检测到HTTP类=" + detectedClients.size());
        } catch (Throwable ignored) {
            try {
                XposedBridge.log("[答案模块] hook 安装完成（无 Context），目标包="
                        + TARGET_PACKAGE + "，hook数=" + installed);
            } catch (Throwable ignored2) {
            }
        }
    }

    // ====== 写入 SharedPreferences ======
    private static void writeDetectionToModuleSP(Context ctx, int installedCount) {
        try {
            Context moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            SharedPreferences sp = moduleCtx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            SharedPreferences.Editor editor = sp.edit();
            if (detectedClients != null && !detectedClients.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String s : detectedClients) {
                    sb.append(s).append("\n");
                }
                editor.putString("detected_clients", sb.toString());
            }
            editor.putInt("hook_installed_count", installedCount);
            editor.putInt("target_hit_count", targetHitCounter.get());
            editor.putInt("request_count", requestCounter.get());
            editor.putLong("last_hook_time", System.currentTimeMillis());
            editor.commit();
        } catch (Throwable ignored) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(SP_NAME,
                        Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                SharedPreferences.Editor editor = sp.edit();
                if (detectedClients != null && !detectedClients.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String s : detectedClients) {
                        sb.append(s).append("\n");
                    }
                    editor.putString("detected_clients", sb.toString());
                }
                editor.putInt("hook_installed_count", installedCount);
                editor.putInt("target_hit_count", targetHitCounter.get());
                editor.putInt("request_count", requestCounter.get());
                editor.putLong("last_hook_time", System.currentTimeMillis());
                editor.commit();
            } catch (Throwable ignored2) {
            }
        }
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, targetClassLoader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void scanHttpClients() {
        try {
            for (String className : HTTP_CLIENT_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className, false, targetClassLoader);
                    if (clazz != null) detectedClients.add(className);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookSelf(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    MODULE_PACKAGE + ".MainActivity", cl, "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    // ========== OkHttp Hook ==========
    private void hookOkHttpLayer(final ClassLoader cl) {
        for (String realCallName : new String[]{"okhttp3.RealCall", "okhttp3.internal.connection.RealCall"}) {
            try {
                XposedHelpers.findAndHookMethod(realCallName, cl, "getResponseWithInterceptorChain",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    if (response != null) processOkHttpResponse(response, param);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                );
                hookInstalledCount.incrementAndGet();
            } catch (Throwable ignored) {
            }
        }

        try {
            XposedHelpers.findAndHookMethod("okhttp3.Call", cl, "execute",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) processOkHttpResponse(response, param);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("okhttp3.Callback", cl, "onResponse",
                    "okhttp3.Call", "okhttp3.Response",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.args[1];
                                if (response != null) processOkHttpResponse(response, param);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("okhttp3.Interceptor$Chain", cl, "proceed",
                    "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) processOkHttpResponse(response, param);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("okhttp3.OkHttpClient", cl, "newCall",
                    "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object request = param.args[0];
                                Object url = XposedHelpers.callMethod(request, "url");
                                if (url != null) {
                                    String urlStr = url.toString();
                                    showToastSafe("[OkHttp请求] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    writeRequestRecord("OKHTTP", urlStr);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    // ========== HttpURLConnection Hook ==========
    private void hookHttpURLConnectionLayer(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("java.net.HttpURLConnection", cl, "getResponseCode",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object url = XposedHelpers.callMethod(conn, "getURL");
                                if (url != null) {
                                    String urlStr = url.toString();
                                    showToastSafe("[HttpURLConnection] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    writeRequestRecord("HTTP_URL_CONN", urlStr);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("java.net.HttpURLConnection", cl, "getInputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object url = XposedHelpers.callMethod(conn, "getURL");
                                String urlStr = url != null ? url.toString() : "";
                                if (!urlStr.contains(TARGET_PATH)) return;

                                InputStream is = (InputStream) param.getResult();
                                if (is == null) return;
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                                is.close();
                                String bodyStr = baos.toString("UTF-8");
                                String modified = modifyAnswerBody(bodyStr);
                                showDialog(urlStr, bodyStr, modified);
                                param.setResult(new ByteArrayInputStream(modified.getBytes(StandardCharsets.UTF_8)));
                            } catch (Throwable t) {
                                showToastSafe("[HttpURLConnection处理] " + t.getMessage());
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    // ========== Socket 底层 Hook ==========
    private void hookSocketLayer(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("java.net.Socket", cl, "connect",
                    "java.net.SocketAddress", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object addr = param.args[0];
                                if (addr instanceof InetSocketAddress) {
                                    InetSocketAddress isa = (InetSocketAddress) addr;
                                    showToastSafe("[Socket] " + isa.getHostName() + ":" + isa.getPort());
                                    writeRequestRecord("SOCKET", isa.getHostName() + ":" + isa.getPort());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    // ========== URL.openConnection Hook ==========
    private void hookURLOpenConnection(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("java.net.URL", cl, "openConnection",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                java.net.URL url = (java.net.URL) param.thisObject;
                                String urlStr = url.toString();
                                showToastSafe("[URL] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                writeRequestRecord("URL_CONN", urlStr);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    // 核心修复：WebView Hook —— 拦截 getQuestion API 并修改响应
    // ============================================================
    private void hookWebViewLayer(final ClassLoader cl) {

        // === 系统 WebView: shouldInterceptRequest(WebView, WebResourceRequest) ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "shouldInterceptRequest",
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

                                // 只有 getQuestion 接口才拦截并修改响应
                                if (!urlStr.contains(TARGET_PATH)) {
                                    // 非目标接口，弹个短 Toast 告诉用户已拦截（调试用）
                                    showToastSafe("[WebView] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    return;
                                }

                                targetHitCounter.incrementAndGet();
                                showToastSafe("【答案模块】拦截 getQuestion API，正在修改响应...");

                                // 转发请求 headers
                                Map<String, String> reqHeaders = new HashMap<String, String>();
                                try {
                                    Object headers = XposedHelpers.callMethod(req, "getRequestHeaders");
                                    if (headers instanceof Map) {
                                        for (Map.Entry<?, ?> e : ((Map<?, ?>) headers).entrySet()) {
                                            if (e.getKey() != null && e.getValue() != null) {
                                                reqHeaders.put(e.getKey().toString(), e.getValue().toString());
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }

                                // 用 HttpURLConnection 自己发起请求
                                byte[] responseBytes = null;
                                Map<String, String> respHeaders = new HashMap<String, String>();
                                String contentType = "application/json";
                                int statusCode = 200;

                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    // 转发 headers（主要是 cookie/authorization 等认证信息）
                                    for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                                        try {
                                            conn.addRequestProperty(e.getKey(), e.getValue());
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    conn.connect();

                                    statusCode = conn.getResponseCode();
                                    InputStream is;
                                    if (statusCode >= 200 && statusCode < 300) {
                                        is = conn.getInputStream();
                                    } else {
                                        is = conn.getErrorStream();
                                    }
                                    // 读取响应体
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    if (is != null) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                                        is.close();
                                    }
                                    responseBytes = baos.toByteArray();
                                    // 提取响应 headers
                                    Map<String, List<String>> fields = conn.getHeaderFields();
                                    for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                                        if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                                            respHeaders.put(e.getKey(), e.getValue().get(0));
                                            if ("content-type".equalsIgnoreCase(e.getKey())) {
                                                contentType = e.getValue().get(0);
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    showToastSafe("请求失败: " + t.getMessage());
                                    try { XposedBridge.log("[答案模块] WebView拦截请求失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) {
                                    showToastSafe("响应体为空，放弃拦截");
                                    return;
                                }

                                // 修改 JSON 响应（标记正确答案为红色加粗）
                                String bodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(bodyStr);

                                if (!modified.equals(bodyStr)) {
                                    showToastSafe("【答案模块】成功标记正确答案！");
                                }

                                // 同时弹对话框显示原始/修改后的内容（调试用，用户可以看到已生效）
                                showDialog(urlStr, bodyStr, modified);

                                // 构造 WebResourceResponse 返回给 WebView
                                byte[] modifiedBytes = modified.getBytes(StandardCharsets.UTF_8);
                                ByteArrayInputStream finalInputStream = new ByteArrayInputStream(modifiedBytes);
                                String finalContentType = contentType;
                                WebResourceResponse wresp;
                                // 尝试使用较新 API（带 encoding 和 status）
                                try {
                                    wresp = new WebResourceResponse("application/json", "UTF-8", finalInputStream);
                                    try {
                                        wresp.setStatusCodeAndReasonPhrase(statusCode, "OK");
                                        wresp.setResponseHeaders(new HashMap<String, String>(respHeaders));
                                    } catch (Throwable ignored) {
                                        // 老版本不支持，忽略
                                    }
                                    param.setResult(wresp);
                                    try { XposedBridge.log("[答案模块] WebView拦截返回成功，响应长度=" + modifiedBytes.length); } catch (Throwable ignored2) {}
                                } catch (Throwable t) {
                                    showToastSafe("构造响应失败: " + t.getMessage());
                                    try { XposedBridge.log("[答案模块] WebResourceResponse构造失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                }
                            } catch (Throwable t) {
                                // 出错时至少记录到 Xposed 日志
                                try {
                                    XposedBridge.log("[答案模块] WebView shouldInterceptRequest 异常: "
                                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                                } catch (Throwable ignored2) {
                                }
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
            try { XposedBridge.log("[答案模块] 系统WebView shouldInterceptRequest hook失败: " + ignored.getMessage()); } catch (Throwable ignored2) {}
        }

        // === 系统 WebView: shouldInterceptRequest(WebView, String) 老版本 API ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String urlStr = (String) param.args[1];
                                if (urlStr == null || !urlStr.contains(TARGET_PATH)) return;
                                targetHitCounter.incrementAndGet();
                                showToastSafe("【答案模块】拦截 getQuestion API(旧API)，正在修改响应...");

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
                                    InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    if (is != null) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                                        is.close();
                                    }
                                    responseBytes = baos.toByteArray();
                                } catch (Throwable t) {
                                    showToastSafe("请求失败: " + t.getMessage());
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String bodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(bodyStr);
                                showDialog(urlStr, bodyStr, modified);

                                byte[] modifiedBytes = modified.getBytes(StandardCharsets.UTF_8);
                                WebResourceResponse wresp = new WebResourceResponse("application/json", "UTF-8",
                                        new ByteArrayInputStream(modifiedBytes));
                                try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                param.setResult(wresp);
                            } catch (Throwable t) {
                                try {
                                    XposedBridge.log("[答案模块] WebView shouldInterceptRequest旧版异常: " + t.getMessage());
                                } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        // === 系统 WebView: 老版本 loadUrl 仅记录 ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebView", cl, "loadUrl",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                if (url != null) {
                                    writeRequestRecord("WEBVIEW", url);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        // === 系统 WebView: onPageFinished 注入 JS 作为兜底 ===
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageFinished",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[1];
                                // 仅在答题相关页面注入 JS
                                if (url == null || (!url.contains("train") && !url.contains("exam") && !url.contains("question"))) return;

                                Object webView = param.args[0];
                                // 注入 JS：高亮正确答案（如果页面上 JSON 字段里有 isRight）
                                String js = "javascript:(function(){try{"
                                        + "var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);"
                                        + "var node; while(node = walker.nextNode()){"
                                        + "var p = node.parentNode; if(!p || !p.textContent) continue;"
                                        + "try{"
                                        + "var text = p.textContent; if(!text) continue;"
                                        + "var idx = text.indexOf('\"isRight\":1'); var idx2 = text.indexOf('\"isRight\": 1');"
                                        + "if(idx < 0 && idx2 < 0) continue;"
                                        + "p.style.color='red'; p.style.fontWeight='bold';"
                                        + "p.style.border = '2px solid red'; p.style.padding='4px';"
                                        + "}catch(e){}"
                                        + "}"
                                        + "var jsonNodes = document.querySelectorAll('*');"
                                        + "for(var i=0;i<jsonNodes.length;i++){"
                                        + "try{"
                                        + "var jtext = jsonNodes[i].textContent; if(!jtext || jtext.indexOf('answerOptionList')<0) continue;"
                                        + "var obj = JSON.parse(jtext); if(obj && obj.data && obj.data.answerOptionList){"
                                        + "for(var j=0;j<obj.data.answerOptionList.length;j++){"
                                        + "if(obj.data.answerOptionList[j].isRight==1){"
                                        + "obj.data.answerOptionList[j].optionText = '【正确答案: '+obj.data.answerOptionList[j].optionText+' 】';"
                                        + "}"
                                        + "}"
                                        + "}"
                                        + "}catch(e){}"
                                        + "}"
                                        + "}catch(e){}})();";

                                XposedHelpers.callMethod(webView, "loadUrl", js);
                                try { XposedBridge.log("[答案模块] JS 已注入到页面: " + url); } catch (Throwable ignored2) {}
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] WebView onPageFinished 注入JS异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
            try { XposedBridge.log("[答案模块] WebView onPageFinished hook失败: " + ignored.getMessage()); } catch (Throwable ignored2) {}
        }

        // === 腾讯 X5 WebView: shouldInterceptRequest ===
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
                                if (!urlStr.contains(TARGET_PATH)) return;

                                targetHitCounter.incrementAndGet();
                                showToastSafe("【答案模块】X5拦截 getQuestion API，正在修改响应...");

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
                                    InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    if (is != null) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                                        is.close();
                                    }
                                    responseBytes = baos.toByteArray();
                                } catch (Throwable t) {
                                    showToastSafe("X5请求失败: " + t.getMessage());
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String bodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(bodyStr);
                                showDialog(urlStr, bodyStr, modified);

                                byte[] modifiedBytes = modified.getBytes(StandardCharsets.UTF_8);
                                ByteArrayInputStream modifiedIs = new ByteArrayInputStream(modifiedBytes);
                                try {
                                    // X5 的 WebResourceResponse 可能使用腾讯自己的类
                                    Class<?> x5respCls = XposedHelpers.findClassIfExists(
                                            "com.tencent.smtt.export.external.interfaces.WebResourceResponse", cl);
                                    if (x5respCls != null) {
                                        Object x5resp = x5respCls.getConstructor(String.class, String.class, InputStream.class)
                                                .newInstance("application/json", "UTF-8", modifiedIs);
                                        param.setResult(x5resp);
                                    }
                                } catch (Throwable t) {
                                    // 退而求其次，用系统的 WebResourceResponse（如果 X5 兼容的话）
                                    try {
                                        WebResourceResponse wresp = new WebResourceResponse("application/json", "UTF-8", modifiedIs);
                                        try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                        param.setResult(wresp);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] X5 shouldInterceptRequest 异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
            try { XposedBridge.log("[答案模块] X5 WebView shouldInterceptRequest hook失败: " + ignored.getMessage()); } catch (Throwable ignored2) {}
        }
    }

    // ========== OkHttp 响应处理 ==========
    private void processOkHttpResponse(Object response, XC_MethodHook.MethodHookParam param) {
        try {
            String urlStr = extractOkHttpUrl(response);
            if (urlStr == null) return;

            writeRequestRecord("OKHTTP_RESP", urlStr);

            if (!urlStr.contains(TARGET_PATH)) return;

            targetHitCounter.incrementAndGet();
            showToastSafe("【答案模块】OkHttp 命中 getQuestion API！");

            byte[] bodyBytes = extractOkHttpBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) {
                showToastSafe("响应体为空");
                return;
            }

            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBodyWithStyle(bodyStr);

            if (!modified.equals(bodyStr)) {
                String contentType = extractOkHttpContentType(response);
                Object newResp = buildOkHttpResponse(response, modified, contentType, targetClassLoader);
                if (newResp != null) {
                    param.setResult(newResp);
                    showDialog(urlStr, bodyStr, modified);
                    return;
                }
            }
            showDialog(urlStr, bodyStr, bodyStr);
        } catch (Throwable t) {
            showToastSafe("处理响应失败: " + t.getMessage());
            try { XposedBridge.log("[答案模块] OkHttp 处理异常: " + t.getMessage()); } catch (Throwable ignored2) {}
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
            } catch (Throwable ignored) {
            }
            try {
                Object str = XposedHelpers.callMethod(body, "string");
                if (str instanceof String) return ((String) str).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
            try {
                Object source = XposedHelpers.callMethod(body, "source");
                if (source != null) {
                    Object buffer = XposedHelpers.callMethod(source, "getBuffer");
                    if (buffer != null) {
                        Object clone = XposedHelpers.callMethod(buffer, "clone");
                        if (clone instanceof byte[]) return (byte[]) clone;
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
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

    private Object buildOkHttpResponse(Object originalResponse, String newBodyStr, String contentType, ClassLoader cl) {
        try {
            ClassLoader safeCl = cl != null ? cl : targetClassLoader;
            if (safeCl == null) return null;
            byte[] bodyBytes = newBodyStr.getBytes(StandardCharsets.UTF_8);

            Object mediaType = null;
            try {
                if (contentType != null && !contentType.isEmpty()) {
                    Class<?> mediaTypeCls = XposedHelpers.findClassIfExists("okhttp3.MediaType", safeCl);
                    if (mediaTypeCls != null) {
                        mediaType = XposedHelpers.callStaticMethod(mediaTypeCls, "parse", contentType);
                    }
                }
            } catch (Throwable ignored) {
            }

            Class<?> responseBodyCls = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", safeCl);
            if (responseBodyCls == null) return null;

            Object newBody = null;
            try {
                newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, bodyBytes);
            } catch (Throwable ignored) {
            }
            if (newBody == null) {
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, newBodyStr);
                } catch (Throwable ignored) {
                }
            }
            if (newBody == null) {
                try {
                    Object buffer = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClassIfExists("okio.Buffer", safeCl), "writeUtf8", newBodyStr);
                    if (buffer != null) {
                        newBody = XposedHelpers.callStaticMethod(responseBodyCls, "create",
                                mediaType, (long) bodyBytes.length, buffer);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (newBody == null) return null;

            Object newBuilder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            newBuilder = XposedHelpers.callMethod(newBuilder, "body", newBody);
            return XposedHelpers.callMethod(newBuilder, "build");
        } catch (Throwable t) {
            return null;
        }
    }

    // ========== 原版修改（不带 HTML 样式，保留） ==========
    private String modifyAnswerBody(String bodyStr) {
        try {
            JSONObject root = new JSONObject(bodyStr);
            if (!"success".equals(root.optString("code"))) return bodyStr;
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

    // ========== 新版修改（给正确答案加上 HTML 红色加粗样式，WebView 渲染会直接显示红色加粗） ==========
    private String modifyAnswerBodyWithStyle(String bodyStr) {
        try {
            JSONObject root = new JSONObject(bodyStr);
            if (!"success".equals(root.optString("code"))) return bodyStr;
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
                    opt.put("optionText",
                            "<span style=\"color:#c62828;font-weight:900;font-size:16px;\">【 " + text + " 正确答案 】</span>");
                    changed = true;
                }
            }
            return changed ? root.toString() : bodyStr;
        } catch (Throwable t) {
            return bodyStr;
        }
    }

    // ========== 写入请求记录 ==========
    private void writeRequestRecord(String type, String urlStr) {
        try {
            Context ctx = appContext;
            if (ctx == null) ctx = getAppContextFromActivityThread();
            if (ctx == null) return;
            try {
                ctx = ctx.createPackageContext(MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            } catch (Throwable ignored) {
            }
            SharedPreferences sp = ctx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            int counter = requestCounter.incrementAndGet();
            String key = "req_" + String.format("%05d", counter) + "_" + System.currentTimeMillis();
            sp.edit().putString(key, type + "|" + urlStr).apply();
        } catch (Throwable ignored) {
        }
    }

    // ========== Toast ==========
    private void showToastSafe(final String message) {
        Context ctx = appContext;
        if (ctx == null) ctx = getAppContextFromActivityThread();
        if (ctx != null) {
            postToast(ctx, message);
            try { XposedBridge.log("[答案模块] " + message); } catch (Throwable ignored) {}
            return;
        }
        try {
            XposedBridge.log("[答案模块][无Context] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static void postToast(final Context ctx, final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ========== 弹窗 ==========
    private void showDialog(final String url, final String originalBody, final String modifiedBody) {
        Context ctx = appContext;
        if (ctx == null) ctx = getAppContextFromActivityThread();
        if (ctx == null) return;

        final Context finalCtx = ctx;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    int pad = dp2px(finalCtx, 16);
                    LinearLayout container = new LinearLayout(finalCtx);
                    container.setOrientation(LinearLayout.VERTICAL);
                    container.setPadding(pad, pad, pad, pad);

                    TextView titleReq = new TextView(finalCtx);
                    titleReq.setText("请求 URL");
                    titleReq.setTextSize(14);
                    titleReq.setTextColor(0xFF1976D2);
                    container.addView(titleReq, lpMatch());

                    TextView urlView = new TextView(finalCtx);
                    urlView.setText(url);
                    urlView.setTextSize(11);
                    urlView.setTextColor(0xFF212121);
                    container.addView(urlView, lpMatch());

                    TextView titleResp = new TextView(finalCtx);
                    titleResp.setText("响应内容");
                    titleResp.setTextSize(14);
                    titleResp.setTextColor(0xFF1976D2);
                    container.addView(titleResp, lpMatch());

                    TextView bodyView = new TextView(finalCtx);
                    bodyView.setText(preview(originalBody));
                    bodyView.setTextSize(11);
                    bodyView.setTextColor(0xFF212121);
                    bodyView.setMovementMethod(new ScrollingMovementMethod());
                    bodyView.setMaxHeight(dp2px(finalCtx, 300));
                    container.addView(bodyView, lpMatch());

                    if (!originalBody.equals(modifiedBody)) {
                        TextView titleMod = new TextView(finalCtx);
                        titleMod.setText("已修改（正确答案已标记）");
                        titleMod.setTextSize(14);
                        titleMod.setTextColor(0xFFc62828);
                        container.addView(titleMod, lpMatch());

                        TextView modView = new TextView(finalCtx);
                        modView.setText(preview(modifiedBody));
                        modView.setTextSize(11);
                        modView.setTextColor(0xFF212121);
                        modView.setMovementMethod(new ScrollingMovementMethod());
                        modView.setMaxHeight(dp2px(finalCtx, 300));
                        container.addView(modView, lpMatch());
                    }

                    ScrollView scrollView = new ScrollView(finalCtx);
                    scrollView.addView(container);

                    new AlertDialog.Builder(finalCtx)
                            .setTitle("【题目响应已拦截】")
                            .setView(scrollView)
                            .setPositiveButton("关闭", null)
                            .setCancelable(true)
                            .show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String preview(String body) {
        if (body == null) return "";
        if (body.length() > 4000) return body.substring(0, 4000) + "\n... (已截断)";
        return body;
    }

    private static LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp2px(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    public static List<String> getDetectedClients() {
        return new ArrayList<String>(detectedClients);
    }

    public static int getTargetHitCount() {
        return targetHitCounter.get();
    }

    public static int getRequestCount() {
        return requestCounter.get();
    }

    public static int getHookInstalledCount() {
        return hookInstalledCount.get();
    }
}
