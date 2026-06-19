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

        Context ctxNow = getAppContextFromActivityThread();
        if (ctxNow != null) {
            appContext = ctxNow;
        }

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

        scanHttpClients();

        int before = hookInstalledCount.get();

        if (hasClass("okhttp3.OkHttpClient") || hasClass("okhttp3.Request")) {
            try { hookOkHttpLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        }
        try { hookHttpURLConnectionLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookSocketLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookWebViewLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookURLOpenConnection(lpparam.classLoader); } catch (Throwable ignored) {}

        int installed = hookInstalledCount.get() - before;

        // === 关键：把状态写入 BOTH 模块自己的 SP AND 目标应用的 SP ===
        // 这样模块 UI 无论从哪个方向读都能读到数据
        try {
            Context c = appContext != null ? appContext : getAppContextFromActivityThread();
            if (c != null) {
                writeStatsToSPs(c, installed);
                // 仅弹这一个 Toast（模块加载成功）
                postToast(c, "✓ 答案模块已加载，共安装 " + installed + " 个 Hook");
            }
            XposedBridge.log("[答案模块] hook安装完成，目标包=" + TARGET_PACKAGE
                    + "，hook数=" + installed + "，检测到HTTP类=" + detectedClients.size());
        } catch (Throwable ignored) {
            try {
                XposedBridge.log("[答案模块] hook安装完成（无Context），hook数=" + installed);
            } catch (Throwable ignored2) {
            }
        }
    }

    // ========== 同时写入目标应用 SP 和 模块 SP ==========
    private static void writeStatsToSPs(Context ctx, int installedCount) {
        // 1. 写入目标应用自己的 SharedPreferences（模块 UI 可通过 createPackageContext 读取）
        try {
            SharedPreferences sp = ctx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            writeStatsToEditor(sp.edit(), installedCount);
        } catch (Throwable ignored) {
        }

        // 2. 同时写入模块包名下的 SharedPreferences（模块 UI 也可以直接读自己的）
        try {
            Context moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            SharedPreferences sp = moduleCtx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            writeStatsToEditor(sp.edit(), installedCount);
        } catch (Throwable ignored) {
        }
    }

    private static void writeStatsToEditor(SharedPreferences.Editor editor, int installedCount) {
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
    }

    // ========== 每次拦截到目标 API 后也更新计数到两个 SP ==========
    private static void updateHitStats() {
        try {
            Context ctx = appContext != null ? appContext : getAppContextFromActivityThread();
            if (ctx == null) return;

            // 写入目标应用的 SP
            try {
                SharedPreferences sp = ctx.getSharedPreferences(SP_NAME,
                        Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                sp.edit().putInt("target_hit_count", targetHitCounter.get())
                        .putInt("request_count", requestCounter.get())
                        .putLong("last_hook_time", System.currentTimeMillis())
                        .commit();
            } catch (Throwable ignored) {
            }

            // 写入模块的 SP
            try {
                Context moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                SharedPreferences sp = moduleCtx.getSharedPreferences(SP_NAME,
                        Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                sp.edit().putInt("target_hit_count", targetHitCounter.get())
                        .putInt("request_count", requestCounter.get())
                        .putLong("last_hook_time", System.currentTimeMillis())
                        .commit();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
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
                                    writeRequestRecord("OKHTTP", url.toString());
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
                                if (url != null) writeRequestRecord("HTTP_URL_CONN", url.toString());
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
                                String modified = modifyAnswerBodyWithStyle(bodyStr);
                                targetHitCounter.incrementAndGet();
                                updateHitStats();
                                showToastSafe("✓ 已标记正确答案");
                                param.setResult(new ByteArrayInputStream(modified.getBytes(StandardCharsets.UTF_8)));
                            } catch (Throwable ignored) {
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
                                writeRequestRecord("URL_CONN", url.toString());
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    // ========== WebView Hook（核心拦截） ==========
    private void hookWebViewLayer(final ClassLoader cl) {

        // 系统 WebView: shouldInterceptRequest(WebView, WebResourceRequest)
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

                                if (!urlStr.contains(TARGET_PATH)) return;

                                targetHitCounter.incrementAndGet();
                                updateHitStats();

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

                                byte[] responseBytes = null;
                                int statusCode = 200;
                                HttpURLConnection conn = null;
                                try {
                                    URL realUrl = new URL(urlStr);
                                    conn = (HttpURLConnection) realUrl.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);
                                    for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                                        try { conn.addRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
                                    }
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
                                    try { XposedBridge.log("[答案模块] WebView请求失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String bodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(bodyStr);

                                if (!modified.equals(bodyStr)) {
                                    showToastSafe("✓ 已标记正确答案");
                                }

                                byte[] modifiedBytes = modified.getBytes(StandardCharsets.UTF_8);
                                ByteArrayInputStream finalInputStream = new ByteArrayInputStream(modifiedBytes);
                                try {
                                    WebResourceResponse wresp = new WebResourceResponse("application/json", "UTF-8", finalInputStream);
                                    try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                    param.setResult(wresp);
                                } catch (Throwable t) {
                                    try { XposedBridge.log("[答案模块] WebResourceResponse构造失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                }
                            } catch (Throwable t) {
                                try {
                                    XposedBridge.log("[答案模块] WebView拦截异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                                } catch (Throwable ignored2) {
                                }
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
            try { XposedBridge.log("[答案模块] WebView shouldInterceptRequest hook失败: " + ignored.getMessage()); } catch (Throwable ignored2) {}
        }

        // 系统 WebView: shouldInterceptRequest(WebView, String) 旧版
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
                                updateHitStats();

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
                                    try { XposedBridge.log("[答案模块] WebView旧版请求失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String bodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(bodyStr);
                                if (!modified.equals(bodyStr)) showToastSafe("✓ 已标记正确答案");

                                byte[] modifiedBytes = modified.getBytes(StandardCharsets.UTF_8);
                                WebResourceResponse wresp = new WebResourceResponse("application/json", "UTF-8",
                                        new ByteArrayInputStream(modifiedBytes));
                                try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                param.setResult(wresp);
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] WebView旧版拦截异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        // 系统 WebView: loadUrl 仅记录
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebView", cl, "loadUrl",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                if (url != null) writeRequestRecord("WEBVIEW", url);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        // 系统 WebView: onPageFinished 注入 JS（兜底）
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", cl, "onPageFinished",
                    "android.webkit.WebView", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[1];
                                if (url == null || (!url.contains("train") && !url.contains("exam") && !url.contains("question"))) return;

                                Object webView = param.args[0];
                                String js = "javascript:(function(){try{"
                                        + "var all=document.querySelectorAll('*');"
                                        + "for(var i=0;i<all.length;i++){"
                                        + "try{"
                                        + "var t=all[i].textContent; if(!t || t.indexOf('answerOptionList')<0) continue;"
                                        + "var obj=JSON.parse(t); if(obj && obj.data && obj.data.answerOptionList){"
                                        + "for(var j=0;j<obj.data.answerOptionList.length;j++){"
                                        + "if(obj.data.answerOptionList[j].isRight==1){"
                                        + "obj.data.answerOptionList[j].optionText='【正确答案: '+obj.data.answerOptionList[j].optionText+' 】';"
                                        + "}"
                                        + "}"
                                        + "}"
                                        + "}catch(e){}"
                                        + "}"
                                        + "}catch(e){}})();";

                                XposedHelpers.callMethod(webView, "loadUrl", js);
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] WebView JS注入异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
            try { XposedBridge.log("[答案模块] WebView onPageFinished hook失败: " + ignored.getMessage()); } catch (Throwable ignored2) {}
        }

        // 腾讯 X5 WebView: shouldInterceptRequest
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
                                updateHitStats();

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
                                    try { XposedBridge.log("[答案模块] X5请求失败: " + t.getMessage()); } catch (Throwable ignored2) {}
                                } finally {
                                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                                }

                                if (responseBytes == null || responseBytes.length == 0) return;

                                String bodyStr = new String(responseBytes, StandardCharsets.UTF_8);
                                String modified = modifyAnswerBodyWithStyle(bodyStr);
                                if (!modified.equals(bodyStr)) showToastSafe("✓ 已标记正确答案");

                                byte[] modifiedBytes = modified.getBytes(StandardCharsets.UTF_8);
                                ByteArrayInputStream modifiedIs = new ByteArrayInputStream(modifiedBytes);
                                try {
                                    Class<?> x5respCls = XposedHelpers.findClassIfExists(
                                            "com.tencent.smtt.export.external.interfaces.WebResourceResponse", cl);
                                    if (x5respCls != null) {
                                        Object x5resp = x5respCls.getConstructor(String.class, String.class, InputStream.class)
                                                .newInstance("application/json", "UTF-8", modifiedIs);
                                        param.setResult(x5resp);
                                    }
                                } catch (Throwable t) {
                                    try {
                                        WebResourceResponse wresp = new WebResourceResponse("application/json", "UTF-8", modifiedIs);
                                        try { wresp.setStatusCodeAndReasonPhrase(statusCode, "OK"); } catch (Throwable ignored) {}
                                        param.setResult(wresp);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            } catch (Throwable t) {
                                try { XposedBridge.log("[答案模块] X5拦截异常: " + t.getMessage()); } catch (Throwable ignored2) {}
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
            try { XposedBridge.log("[答案模块] X5 WebView hook失败: " + ignored.getMessage()); } catch (Throwable ignored2) {}
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
            updateHitStats();

            byte[] bodyBytes = extractOkHttpBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) return;

            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBodyWithStyle(bodyStr);

            if (!modified.equals(bodyStr)) {
                String contentType = extractOkHttpContentType(response);
                Object newResp = buildOkHttpResponse(response, modified, contentType, targetClassLoader);
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

    // ========== 请求记录（同时写到两个 SP） ==========
    private void writeRequestRecord(String type, String urlStr) {
        try {
            Context ctx = appContext;
            if (ctx == null) ctx = getAppContextFromActivityThread();
            if (ctx == null) return;

            int counter = requestCounter.incrementAndGet();
            String key = "req_" + String.format("%05d", counter) + "_" + System.currentTimeMillis();
            String value = type + "|" + urlStr;

            // 写入目标应用的 SP
            try {
                SharedPreferences sp = ctx.getSharedPreferences(SP_NAME,
                        Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                sp.edit().putString(key, value).commit();
            } catch (Throwable ignored) {
            }

            // 写入模块的 SP
            try {
                Context moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                SharedPreferences sp = moduleCtx.getSharedPreferences(SP_NAME,
                        Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
                sp.edit().putString(key, value).commit();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    // ========== Toast（仅保留关键提示） ==========
    private void showToastSafe(final String message) {
        Context ctx = appContext;
        if (ctx == null) ctx = getAppContextFromActivityThread();
        if (ctx != null) {
            postToast(ctx, message);
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

    // ========== 暴露给 UI 读取 ==========
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
