package com.answer.revealer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "tz.ycsy.az";
    private static final String TARGET_PATH = "/edu-core-server/app/exam/getQuestion";
    private static final String MODULE_PACKAGE = "com.answer.revealer";
    private static final String SP_NAME = "answer_revealer_status";

    // 常见 HTTP 客户端 / 网络框架 类名
    private static final String[] HTTP_CLIENT_CLASS_NAMES = {
            // OkHttp / Retrofit
            "okhttp3.OkHttpClient",
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.Dispatcher",
            "okhttp3.Request",
            "okhttp3.Response",
            "okhttp3.ResponseBody",
            "retrofit2.Retrofit",
            "retrofit2.OkHttpCall",
            // Apache HttpClient
            "org.apache.http.impl.client.DefaultHttpClient",
            "org.apache.http.impl.client.CloseableHttpClient",
            "org.apache.http.client.HttpClient",
            // 原生
            "java.net.HttpURLConnection",
            "javax.net.ssl.HttpsURLConnection",
            // Volley
            "com.android.volley.toolbox.Volley",
            "com.android.volley.toolbox.StringRequest",
            "com.android.volley.toolbox.JsonObjectRequest",
            // WebView
            "android.webkit.WebView",
            "android.webkit.WebViewClient",
            // 国产框架
            "org.xutils.http.RequestParams",
            "org.xutils.x",
            "com.lidroid.xutils.HttpUtils",
            "com.loopj.android.http.AsyncHttpClient",
            "com.squareup.okhttp.OkHttpClient",
            // 跨平台框架（Flutter / React Native / Cordova）
            "io.flutter.embedding.engine.FlutterEngine",
            "io.flutter.plugin.common.MethodChannel",
            "com.facebook.react.ReactInstanceManager",
            "com.facebook.react.bridge.NativeModuleCallExceptionHandler",
            "org.apache.cordova.CordovaWebView",
            "org.apache.cordova.CordovaInterface",
            // 小程序 / WebView 容器
            "com.tencent.smtt.sdk.WebView",
            "com.tencent.smtt.sdk.WebViewClient"
    };

    private static final Set<String> initializedPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final List<String> detectedClients =
            Collections.synchronizedList(new ArrayList<String>());

    // hook 安装计数器
    private static final AtomicInteger hookInstalledCount = new AtomicInteger(0);

    // 请求与目标命中统计
    private static final AtomicInteger requestCounter = new AtomicInteger(0);
    private static final AtomicInteger targetHitCounter = new AtomicInteger(0);

    // 共享状态
    private static volatile Context appContext;
    private static ClassLoader targetClassLoader;

    // ====== 工具：从 ActivityThread 拿 Application Context（不依赖 Application.onCreate）======
    private static Context getAppContextFromActivityThread() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            if (activityThread == null) return null;
            Context ctx = (Context) XposedHelpers.callMethod(activityThread, "getApplication");
            return ctx;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;

        // —— 处理模块自己的包 ——
        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            hookSelf(lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "onCreate",
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
            return;
        }

        // —— 非目标包，跳过 ——
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        // —— 确保只初始化一次 ——
        if (!initializedPackages.add(TARGET_PACKAGE)) return;

        targetClassLoader = lpparam.classLoader;

        // ====== 关键修复 1：进入目标包时，立刻尝试获取 Context ======
        // 策略：先尝试 ActivityThread.currentActivityThread().getApplication()
        // 如果拿不到就记下来等 Application.onCreate 时再补充
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

        // ====== 关键修复 2：hook Application.onCreate（兜底获取 Context）======
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                if (appContext == null) appContext = ctx;
                                try {
                                    SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                                    sp.edit().putBoolean("module_active_v1", true)
                                            .putLong("last_hook_time", System.currentTimeMillis()).commit();
                                } catch (Throwable ignored) {
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        // ====== 关键修复 3：扫描 HTTP 客户端（包括 Flutter/React Native 等）======
        scanHttpClients();

        // ====== 关键修复 4：安装网络 Hook（OkHttp / HttpURLConnection / Socket / WebView / URL）======
        int before = hookInstalledCount.get();

        if (hasClass("okhttp3.OkHttpClient") || hasClass("okhttp3.Request")) {
            try { hookOkHttpLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        }
        try { hookHttpURLConnectionLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookSocketLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookWebViewLayer(lpparam.classLoader); } catch (Throwable ignored) {}
        try { hookURLOpenConnection(lpparam.classLoader); } catch (Throwable ignored) {}

        int after = hookInstalledCount.get();
        int installed = after - before;

        // ====== 关键修复 5：把检测结果写入模块 SP，并弹 Toast ======
        try {
            Context c = appContext != null ? appContext : getAppContextFromActivityThread();
            if (c != null) {
                writeDetectionToModuleSP(c, installed);
                showToastSafe("【答案模块】已加载，共安装 " + installed + " 个网络 Hook，检测到 "
                        + detectedClients.size() + " 个 HTTP 相关类");
            }
            // 同时记录到 XposedBridge 日志（无论是否有 Context 都能看到）
            XposedBridge.log("[答案模块] hook 安装完成，目标包=" + TARGET_PACKAGE
                    + "，hook数=" + installed + "，检测到HTTP类=" + detectedClients.size());
        } catch (Throwable ignored) {
            // 失败至少记到 Xposed 日志
            try {
                XposedBridge.log("[答案模块] hook 安装完成（无 Context），目标包="
                        + TARGET_PACKAGE + "，hook数=" + installed);
            } catch (Throwable ignored2) {
            }
        }
    }

    // ========== 把检测结果写入模块自己的 SharedPreferences ==========
    private static void writeDetectionToModuleSP(Context ctx, int installedCount) {
        try {
            Context moduleCtx = ctx.createPackageContext(MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            SharedPreferences sp = moduleCtx.getSharedPreferences(SP_NAME,
                    Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            SharedPreferences.Editor editor = sp.edit();
            // 写入 HTTP 客户端检测结果
            if (detectedClients != null && !detectedClients.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String s : detectedClients) {
                    sb.append(s).append("\n");
                }
                editor.putString("detected_clients", sb.toString());
            }
            // 写入 hook 安装数量
            editor.putInt("hook_installed_count", installedCount);
            editor.putInt("target_hit_count", targetHitCounter.get());
            editor.putInt("request_count", requestCounter.get());
            editor.putLong("last_hook_time", System.currentTimeMillis());
            editor.commit();
        } catch (Throwable ignored) {
            // 如果跨进程失败，就写入目标应用自己的 SP（UI 可以通过 createPackageContext 反向读）
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

    // ========== 检测类是否存在 ==========
    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, targetClassLoader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ========== 扫描并记录 HTTP 客户端（包括 Flutter/React Native 等跨平台框架）==========
    private static void scanHttpClients() {
        try {
            for (String className : HTTP_CLIENT_CLASS_NAMES) {
                try {
                    Class<?> clazz = Class.forName(className, false, targetClassLoader);
                    if (clazz != null) {
                        detectedClients.add(className);
                    }
                } catch (ClassNotFoundException ignored) {
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ========== 模块自己的 Hook ==========
    private void hookSelf(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    MODULE_PACKAGE + ".MainActivity",
                    cl,
                    "isModuleActive",
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
                XposedHelpers.findAndHookMethod(
                        realCallName, cl, "getResponseWithInterceptorChain",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    if (response != null) {
                                        processOkHttpResponse(response, param);
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

        try {
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Call", cl, "execute",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processOkHttpResponse(response, param);
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
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Callback", cl, "onResponse",
                    "okhttp3.Call", "okhttp3.Response",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.args[1];
                                if (response != null) {
                                    processOkHttpResponse(response, param);
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
            XposedHelpers.findAndHookMethod(
                    "okhttp3.Interceptor$Chain", cl, "proceed", "okhttp3.Request",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response != null) {
                                    processOkHttpResponse(response, param);
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
            XposedHelpers.findAndHookMethod(
                    "okhttp3.OkHttpClient", cl, "newCall", "okhttp3.Request",
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
            XposedHelpers.findAndHookMethod(
                    "java.net.HttpURLConnection", cl, "getResponseCode",
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
            XposedHelpers.findAndHookMethod(
                    "java.net.HttpURLConnection", cl, "getInputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object conn = param.thisObject;
                                Object url = XposedHelpers.callMethod(conn, "getURL");
                                String urlStr = url != null ? url.toString() : "";
                                if (!urlStr.contains(TARGET_PATH)) {
                                    return;
                                }
                                InputStream is = (InputStream) param.getResult();
                                if (is == null) return;
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = is.read(buf)) != -1) {
                                    baos.write(buf, 0, n);
                                }
                                is.close();
                                String bodyStr = baos.toString("UTF-8");
                                String modified = modifyAnswerBody(bodyStr);
                                showDialog(urlStr, bodyStr, modified);
                                param.setResult(new java.io.ByteArrayInputStream(
                                        modified.getBytes(StandardCharsets.UTF_8)));
                            } catch (Throwable t) {
                                showToastSafe("[HttpURLConnection 处理] " + t.getMessage());
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    // ========== Socket 底层 Hook（兜底，抓所有 TCP 连接）==========
    private void hookSocketLayer(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.Socket", cl, "connect",
                    "java.net.SocketAddress", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object addr = param.args[0];
                                if (addr instanceof InetSocketAddress) {
                                    InetSocketAddress isa = (InetSocketAddress) addr;
                                    String host = isa.getHostName();
                                    showToastSafe("[Socket] " + host + ":" + isa.getPort());
                                    writeRequestRecord("SOCKET", host + ":" + isa.getPort());
                                } else if (addr != null) {
                                    showToastSafe("[Socket] " + addr.toString());
                                    writeRequestRecord("SOCKET", addr.toString());
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
            XposedHelpers.findAndHookMethod(
                    "java.net.Socket", cl, "getOutputStream",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object socket = param.thisObject;
                                Object addr = XposedHelpers.callMethod(socket, "getRemoteSocketAddress");
                                if (addr != null) {
                                    showToastSafe("[Socket输出] " + addr.toString().substring(0,
                                            Math.min(addr.toString().length(), 60)));
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
            XposedHelpers.findAndHookMethod(
                    "java.net.URL", cl, "openConnection",
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

    // ========== WebView Hook ==========
    private void hookWebViewLayer(final ClassLoader cl) {
        // 系统 WebView
        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebView", cl, "loadUrl",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                if (url != null) {
                                    showToastSafe("[WebView] " + url.substring(0, Math.min(url.length(), 60)));
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

        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebView", cl, "loadUrl",
                    String.class, java.util.Map.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                if (url != null) {
                                    showToastSafe("[WebView+Headers] " + url.substring(0, Math.min(url.length(), 60)));
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

        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebViewClient", cl, "shouldInterceptRequest",
                    "android.webkit.WebView", "android.webkit.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object req = param.args[1];
                                Object urlObj = XposedHelpers.callMethod(req, "getUrl");
                                if (urlObj != null) {
                                    String urlStr = urlObj.toString();
                                    showToastSafe("[WebView拦截] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    writeRequestRecord("WEBVIEW_INTERCEPT", urlStr);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            hookInstalledCount.incrementAndGet();
        } catch (Throwable ignored) {
        }

        // 腾讯 X5 WebView（国产 App 常用）
        try {
            XposedHelpers.findAndHookMethod(
                    "com.tencent.smtt.sdk.WebView", cl, "loadUrl",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                if (url != null) {
                                    showToastSafe("[X5WebView] " + url.substring(0, Math.min(url.length(), 60)));
                                    writeRequestRecord("X5WEBVIEW", url);
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
            XposedHelpers.findAndHookMethod(
                    "com.tencent.smtt.sdk.WebViewClient", cl, "shouldInterceptRequest",
                    "com.tencent.smtt.sdk.WebView", "com.tencent.smtt.export.external.interfaces.WebResourceRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object req = param.args[1];
                                Object urlObj = XposedHelpers.callMethod(req, "getUrl");
                                if (urlObj != null) {
                                    String urlStr = urlObj.toString();
                                    showToastSafe("[X5WebView拦截] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
                                    writeRequestRecord("X5WEBVIEW_INTERCEPT", urlStr);
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

    // ========== 处理 OkHttp 响应 ==========
    private void processOkHttpResponse(Object response, XC_MethodHook.MethodHookParam param) {
        try {
            String urlStr = extractOkHttpUrl(response);
            if (urlStr == null) return;

            showToastSafe("[响应] " + urlStr.substring(0, Math.min(urlStr.length(), 60)));
            writeRequestRecord("OKHTTP_RESP", urlStr);

            if (!urlStr.contains(TARGET_PATH)) return;

            showToastSafe("=== 目标接口命中！ ===");
            targetHitCounter.incrementAndGet();

            byte[] bodyBytes = extractOkHttpBodyBytes(response);
            if (bodyBytes == null || bodyBytes.length == 0) {
                showToastSafe("响应体为空");
                return;
            }

            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            String modified = modifyAnswerBody(bodyStr);

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
                            XposedHelpers.findClassIfExists("okio.Buffer", safeCl),
                            "writeUtf8", newBodyStr);
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

    // ========== 修改答案内容 ==========
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
            sp.edit().putString(key, type + "|" + urlStr).commit();
        } catch (Throwable ignored) {
        }
    }

    // ========== Toast（多层 Context 兜底）==========
    private void showToastSafe(final String message) {
        Context ctx = appContext;
        if (ctx == null) ctx = getAppContextFromActivityThread();
        if (ctx != null) {
            postToast(ctx, message);
            // 同时记录到 Xposed 日志，方便即使 Toast 不出也能调试
            try { XposedBridge.log("[答案模块] " + message); } catch (Throwable ignored) {}
            return;
        }
        // Context 都没有的最后手段：只写 Xposed 日志
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

    // ========== 暴露给 UI 读取检测结果的方法 ==========
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
